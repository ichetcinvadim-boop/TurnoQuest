package pro.turnoworld.quests;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

public final class TurnoQuests extends JavaPlugin {
    private QuestCatalog catalog;
    private FileStore store;
    private ProgressEngine engine;
    private RewardService rewards;
    private RotationService rotations;
    private GlobalState global;
    private AntiExploitStore antiExploit;
    private QuestGui gui;
    private QuestNpcService npcService;
    private YamlConfiguration messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureResource("quests.yml");
        ensureResource("messages.yml");
        migrateLegacyConfiguration();
        try {
            Path data = getDataFolder().toPath();
            store = new FileStore(data.resolve("data"));
            antiExploit = new AntiExploitStore(data.resolve("data"));
            global = new GlobalState(data.resolve("data"));
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Не удалось создать хранилище TurnoQuests", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        engine = new ProgressEngine();
        rotations = new RotationService(zone());
        rewards = new RewardService(this);
        reloadQuestFiles();
        gui = new QuestGui(this);
        npcService = new QuestNpcService(this);

        QuestCommand command = new QuestCommand(this);
        bind("quests", command);
        bind("turnoquests", command);
        getServer().getPluginManager().registerEvents(new QuestListener(this), this);
        getServer().getPluginManager().registerEvents(npcService, this);
        npcService.restore();

        long saveTicks = Math.max(20L, getConfig().getLong("autosave-seconds", 60) * 20L);
        getServer().getScheduler().runTaskTimer(this, this::saveAll, saveTicks, saveTicks);
        long trackerTicks = Math.max(20L, getConfig().getLong("tracker.interval-ticks", 40));
        getServer().getScheduler().runTaskTimer(this, this::updateTrackers, trackerTicks, trackerTicks);
        getServer().getScheduler().runTaskTimer(this, this::tickPlaytime, 20L, 20L);
        global.normalize(rotations.weekKey());
        if (getConfig().getBoolean("backup-on-start", true)) {
            getServer().getScheduler().runTaskAsynchronously(this, () -> { try { store.backup(); } catch (IOException e) { getLogger().warning("Backup failed: " + e.getMessage()); } });
        }
        List<String> errors = validateConfiguration();
        if (errors.isEmpty()) getLogger().info("TurnoQuests включён: загружено 100 последовательных квестов.");
        else errors.forEach(s -> getLogger().warning("Проверка: " + s));
    }

    @Override
    public void onDisable() { saveAll(); }

    private void bind(String name, QuestCommand command) {
        PluginCommand pluginCommand = getCommand(name);
        if (pluginCommand == null) throw new IllegalStateException("Command missing from plugin.yml: " + name);
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);
    }

    private void ensureResource(String name) {
        if (!new File(getDataFolder(), name).isFile()) saveResource(name, false);
    }

    private void migrateLegacyConfiguration() {
        File quests = new File(getDataFolder(), "quests.yml");
        try {
            String text = Files.readString(quests.toPath());
            if (text.contains("MYTHIC_KILL") || text.contains("BOSS_KILL")) {
                Files.copy(quests.toPath(), new File(getDataFolder(), "quests-before-1.2.0.yml").toPath(), StandardCopyOption.REPLACE_EXISTING);
                saveResource("quests.yml", true);
                getLogger().info("Старые квесты с боссами сохранены в quests-before-1.2.0.yml и заменены новой цепочкой.");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось обновить quests.yml до 1.2.0", e);
        }
        if (getConfig().contains("bosses") || getConfig().contains("citizens")) {
            getConfig().set("bosses", null);
            getConfig().set("citizens", null);
            saveConfig();
        }
    }

    public synchronized void reloadQuestFiles() {
        reloadConfig();
        messages = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
        catalog = QuestCatalog.load(new File(getDataFolder(), "quests.yml"));
        if (rewards != null) rewards.hookEconomy();
    }

    public void record(Player player, QuestType type, String target, long amount) {
        if (player == null || amount <= 0 || !eligible(player)) return;
        PlayerData data = data(player);
        String normalized = target == null ? "ANY" : target.toUpperCase(Locale.ROOT);
        updateStatistics(data, type, amount);
        rotations.normalize(data);
        updateRotating(player, data, rotations.daily(), true, type, normalized, amount);
        updateRotating(player, data, rotations.weekly(), false, type, normalized, amount);
        global.normalize(rotations.weekKey());
        global.add(player.getUniqueId(), globalPoints(type, amount));
        claimGlobal(player, data);
        checkSecrets(player, data);

        if (!data.finished()) {
            QuestDefinition quest = catalog.get(data.currentQuest);
            ProgressOutcome result = engine.add(data, quest, type, normalized, amount, System.currentTimeMillis());
            if (result.completed()) complete(player, data, quest, result.timedBonus(), result.noDeathBonus(), true);
        }
        // Frequent gameplay events stay in memory; autosave and quit save them atomically.
    }

    private void updateRotating(Player player, PlayerData data, RotatingObjective objective, boolean daily,
                                QuestType type, String target, long amount) {
        if (!objective.matches(type, target)) return;
        long progress = daily ? data.dailyProgress : data.weeklyProgress;
        boolean claimed = daily ? data.dailyClaimed : data.weeklyClaimed;
        if (claimed) return;
        progress = Math.min(objective.required(), progress + amount);
        if (daily) data.dailyProgress = progress; else data.weeklyProgress = progress;
        if (progress >= objective.required() && rewards.deposit(player, objective.money())) {
            data.moneyEarned += Math.round(objective.money());
            if (daily) data.dailyClaimed = true; else data.weeklyClaimed = true;
            player.sendMessage(color(prefix() + (daily ? "&aЕжедневное" : "&6Еженедельное") + " задание выполнено: &e+" + rewards.format(objective.money())));
        }
    }

    public void complete(Player online, PlayerData data, QuestDefinition quest, boolean timed, boolean noDeath, boolean announce) {
        data.progress = quest.required();
        rewards.queueQuest(data, quest, timed, noDeath);
        if (announce && online != null) {
            online.sendMessage(color(prefix() + "&aКвест #" + quest.id() + " «" + quest.name() + "» выполнен!"));
            online.sendMessage(color(prefix() + "&eОткройте /quests и нажмите зелёную кнопку, чтобы забрать награду."));
            online.sendTitle(color("&aНаграда готова"), color("&fНажмите зелёную кнопку в &e/quests"), 10, 55, 15);
        }
        trySave(data);
    }

    public synchronized boolean claimQuestReward(Player player, int questId) {
        PlayerData data = data(player);
        if (data.finished() || data.currentQuest != questId) {
            player.sendMessage(color(prefix() + "&cЭто не ваш текущий квест."));
            return false;
        }
        QuestDefinition quest = catalog.get(questId);
        if (!data.rewardReady(quest)) {
            player.sendMessage(color(prefix() + "&cСначала выполните квест полностью."));
            return false;
        }

        List<String> result = rewards.claimQuest(data, player, catalog, questId);
        for (String line : result) player.sendMessage(color(prefix() + line));
        if (rewards.hasPendingForQuest(data, quest)) {
            player.sendMessage(color(prefix() + "&eНаграда сохранена. Исправьте указанную проблему и нажмите зелёную кнопку ещё раз."));
            trySave(data);
            return false;
        }

        engine.advance(data, quest, System.currentTimeMillis());
        if (quest.id() % 10 == 0) announceChapter(player, data, quest.chapter());
        player.sendMessage(color(prefix() + "&aНаграда за квест #" + quest.id() + " получена. Следующий квест открыт!"));
        player.sendTitle(color("&aНаграда получена"), color(data.finished() ? "&dВсе 100 квестов завершены" : "&eОткрыт квест #" + data.currentQuest), 10, 45, 15);
        trySave(data);
        checkCurrentState(player);
        return true;
    }

    private void announceChapter(Player player, PlayerData data, int chapter) {
        String text = "&6&l" + data.lastName + " &fзавершил главу &e" + chapter + " «" + catalog.chapterName(chapter) + "»";
        if (getConfig().getBoolean("milestones.broadcast-chapters", true)) Bukkit.broadcastMessage(color(prefix() + text));
        if (player != null && getConfig().getBoolean("milestones.title-chapters", true))
            player.sendTitle(color("&6&lГлава " + chapter + " завершена"), color("&eОсобая награда получена"), 10, 70, 20);
    }

    public void processPending(Player player) {
        PlayerData data = data(player);
        claimGlobal(player, data);
        for (String line : rewards.claim(data, player, catalog)) player.sendMessage(color(prefix() + line));
        trySave(data);
    }

    public PlayerData data(Player player) {
        PlayerData data = store.getOrCreate(player.getUniqueId(), player.getName());
        data.lastSeen = System.currentTimeMillis();
        rotations.normalize(data);
        return data;
    }

    public Optional<PlayerData> findData(String uuidOrName) {
        Player online = Bukkit.getPlayerExact(uuidOrName);
        if (online != null) return Optional.of(data(online));
        try {
            UUID uuid = UUID.fromString(uuidOrName);
            OfflinePlayer cached = Bukkit.getOfflinePlayer(uuid);
            return Optional.of(store.getOrCreate(uuid, cached.getName()));
        } catch (IllegalArgumentException ignored) { }
        Optional<PlayerData> stored = store.find(uuidOrName);
        if (stored.isPresent()) return stored;
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() != null && offline.getName().equalsIgnoreCase(uuidOrName)) {
                return Optional.of(store.getOrCreate(offline.getUniqueId(), offline.getName()));
            }
        }
        return Optional.empty();
    }

    public Player online(PlayerData data) { return Bukkit.getPlayer(data.uuid); }

    public void markDeath(Player player) { PlayerData data = data(player); data.diedDuringQuest = true; trySave(data); }

    public void open(Player player) { checkCurrentState(player); gui.openMain(player); }
    public void openChapter(Player player, int chapter) { checkCurrentState(player); gui.openChapter(player, Math.max(1, Math.min(10, chapter))); }

    public void checkCurrentState(Player player) {
        PlayerData data = data(player);
        if (data.finished()) return;
        QuestDefinition q = catalog.get(data.currentQuest);
        switch (q.type()) {
            case VISIT_WORLD -> record(player, QuestType.VISIT_WORLD, player.getWorld().getEnvironment().name(), 1);
            case VISIT_BIOME -> record(player, QuestType.VISIT_BIOME, player.getLocation().getBlock().getBiome().getKey().getKey(), 1);
            case ADVANCEMENT -> {
                NamespacedKey key = NamespacedKey.fromString(q.target().toLowerCase(Locale.ROOT));
                Advancement advancement = key == null ? null : Bukkit.getAdvancement(key);
                if (advancement != null && player.getAdvancementProgress(advancement).isDone())
                    record(player, QuestType.ADVANCEMENT, q.target(), 1);
            }
            default -> { }
        }
    }

    public boolean prestige(Player player) {
        PlayerData data = data(player);
        if (!data.finished()) { player.sendMessage(color(prefix() + "&cСначала завершите все 100 квестов.")); return false; }
        double reward = getConfig().getDouble("postgame.prestige-money", 150000);
        if (!rewards.deposit(player, reward)) { player.sendMessage(color(prefix() + "&cЭкономика недоступна.")); return false; }
        data.prestige++;
        data.moneyEarned += Math.round(reward);
        engine.setQuest(data, 1);
        store.audit(player.getName(), "PRESTIGE", data, "level=" + data.prestige + ", money=" + reward);
        trySave(data);
        player.sendMessage(color(prefix() + "&dПрестиж повышен до " + data.prestige + "! &e+" + rewards.format(reward)));
        return true;
    }

    public List<String> validateConfiguration() {
        List<String> errors = new java.util.ArrayList<>(catalog.validate());
        if (!hasPlugin("Vault")) errors.add("Vault не найден: денежные награды останутся в очереди");
        if (!rewards.economyAvailable()) errors.add("Провайдер экономики Vault не найден");
        if (!hasPlugin("ExecutableItems")) errors.add("ExecutableItems не найден: предметные награды не будут выданы");
        if (hasPlugin("BetonQuest")) errors.add("BetonQuest включён и может перехватить /quests — удалите его alias или отключите старый плагин");
        for (int chapter = 1; chapter <= 10; chapter++) if (catalog.chapterReward(chapter).isBlank()) errors.add("Пустой EI reward главы " + chapter);
        validateExternalIds(errors);
        return errors;
    }

    private void validateExternalIds(List<String> errors) {
        File plugins = getDataFolder().getParentFile();
        Path itemRoot = plugins.toPath().resolve("ExecutableItems").resolve("items");
        if (Files.isDirectory(itemRoot)) {
            Set<String> ids = new HashSet<>();
            try (var paths = Files.walk(itemRoot)) {
                paths.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                        .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".yml"))
                        .map(n -> n.substring(0, n.length() - 4).toLowerCase(Locale.ROOT)).forEach(ids::add);
            } catch (IOException e) { errors.add("Не удалось прочитать ExecutableItems/items: " + e.getMessage()); }
            for (int chapter = 1; chapter <= 10; chapter++) {
                String id = catalog.chapterReward(chapter);
                if (!ids.contains(id.toLowerCase(Locale.ROOT))) errors.add("ExecutableItems ID не найден: " + id + " (глава " + chapter + ")");
            }
        }
    }

    private void updateStatistics(PlayerData data, QuestType type, long amount) {
        switch (type) {
            case BREAK -> data.blocksBroken += amount;
            case KILL -> data.mobsKilled += amount;
            case WALK -> data.distanceWalked += amount;
            case PLAYTIME -> data.playTicks += amount;
            case FISH -> data.fishCaught += amount;
            default -> { }
        }
    }

    private void checkSecrets(Player player, PlayerData data) {
        secret(player, data, "miner-10000", data.blocksBroken >= 10000, "Подземный мастер", 15000);
        secret(player, data, "hunter-5000", data.mobsKilled >= 5000, "Неутомимый охотник", 20000);
        secret(player, data, "traveler-1000000", data.distanceWalked >= 1_000_000, "Край света", 30000);
        secret(player, data, "angler-1000", data.fishCaught >= 1000, "Легенда морей", 18000);
        secret(player, data, "centurion", data.lifetimeHighest >= 100, "Сотый рубеж", 50000);
    }

    private void secret(Player player, PlayerData data, String key, boolean unlocked, String name, double money) {
        if (!unlocked || data.claimedSecrets.contains(key) || !rewards.deposit(player, money)) return;
        data.claimedSecrets.add(key);
        data.moneyEarned += Math.round(money);
        player.sendMessage(color(prefix() + "&d&lСекрет открыт: &f" + name + " &e(+" + rewards.format(money) + ")"));
    }

    private void claimGlobal(Player player, PlayerData data) {
        if (!getConfig().getBoolean("global-weekly.enabled", true)) return;
        long required = getConfig().getLong("global-weekly.required", 25000);
        if (!global.mayClaim(player.getUniqueId(), required)) return;
        double amount = getConfig().getDouble("global-weekly.reward-money", 25000);
        if (rewards.deposit(player, amount)) {
            global.claim(player.getUniqueId());
            data.moneyEarned += Math.round(amount);
            player.sendMessage(color(prefix() + "&bОбщая цель недели достигнута: &e+" + rewards.format(amount)));
        }
    }

    private long globalPoints(QuestType type, long amount) {
        return switch (type) {
            case KILL -> amount * 10;
            case FISH, BREED, TAME -> amount * 5;
            case WALK -> amount / 100;
            case BREAK, PLACE, CRAFT, SMELT -> amount;
            default -> 0;
        };
    }

    private void tickPlaytime() { for (Player p : Bukkit.getOnlinePlayers()) record(p, QuestType.PLAYTIME, "ANY", 20); }
    private void updateTrackers() {
        if (!getConfig().getBoolean("tracker.enabled", true)) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = data(player);
            if (!data.tracker || data.finished()) continue;
            QuestDefinition q = catalog.get(data.currentQuest);
            String bar = data.rewardReady(q)
                    ? color("&a&lНаграда готова! &fОткройте &e/quests &fи нажмите зелёную кнопку")
                    : color("&6Квест #" + q.id() + " &8• &f" + q.name() + " &8[&e" + data.progress + "&7/&e" + q.required() + "&8]");
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(bar));
        }
    }

    public void saveAll() {
        if (store != null) store.saveAll();
        if (antiExploit != null) antiExploit.save();
        if (global != null) global.save();
    }

    public void trySave(PlayerData data) { try { store.save(data); } catch (IOException e) { getLogger().warning("Не удалось сохранить " + data.uuid + ": " + e.getMessage()); } }
    private boolean eligible(Player p) { return !(getConfig().getBoolean("anti-exploit.ignore-creative", true) && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)); }
    private ZoneId zone() { try { return ZoneId.of(getConfig().getString("timezone", "Europe/Moscow")); } catch (Exception e) { return ZoneId.of("Europe/Moscow"); } }
    public boolean hasPlugin(String name) { return getServer().getPluginManager().getPlugin(name) != null; }
    public String prefix() { return messages == null ? "&6&lКвесты &8» &f" : messages.getString("prefix", "&6&lКвесты &8» &f"); }
    public String color(String text) { return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text); }

    public QuestCatalog catalog() { return catalog; }
    public FileStore store() { return store; }
    public ProgressEngine engine() { return engine; }
    public RewardService rewards() { return rewards; }
    public RotationService rotations() { return rotations; }
    public GlobalState global() { return global; }
    public AntiExploitStore antiExploit() { return antiExploit; }
    public QuestGui gui() { return gui; }
    public QuestNpcService npcService() { return npcService; }
}
