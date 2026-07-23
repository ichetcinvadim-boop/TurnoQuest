package pro.turnoworld.quests;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class QuestGui {
    private static final int[] CHAPTER_SLOTS = {10, 11, 12, 13, 14, 19, 20, 21, 22, 23};
    private static final Material[] CHAPTER_ICONS = {
            Material.OAK_LOG, Material.BOW, Material.WHEAT, Material.DIAMOND_PICKAXE, Material.BRICKS,
            Material.COMPASS, Material.NETHERRACK, Material.END_STONE, Material.NETHER_STAR, Material.DRAGON_EGG
    };
    private final TurnoQuests plugin;

    public QuestGui(TurnoQuests plugin) { this.plugin = plugin; }

    public void openMain(Player player) {
        PlayerData data = plugin.data(player);
        QuestInventoryHolder holder = new QuestInventoryHolder(QuestInventoryHolder.View.MAIN, 0);
        Inventory inv = Bukkit.createInventory(holder, 54, plugin.color(plugin.getConfig().getString("gui.title", "&0Квесты TurnoWorld")));
        holder.inventory(inv);
        fill(inv);
        for (int chapter = 1; chapter <= 10; chapter++) {
            int from = (chapter - 1) * 10 + 1;
            int to = chapter * 10;
            String status = data.highestCompleted >= to ? "&aЗавершена" : data.currentQuest >= from && data.currentQuest <= to ? "&eТекущая" : "&7Заблокирована";
            int complete = Math.max(0, Math.min(10, data.highestCompleted - from + 1));
            List<String> lore = new ArrayList<>();
            lore.add("&7Квесты: &f" + complete + "/10");
            lore.add("&7Статус: " + status);
            lore.add("");
            lore.add("&7Всего за 10 квестов:");
            lore.add("&e" + plugin.rewards().format(plugin.rewards().questAmount(plugin.catalog().chapterMoney(chapter))) + " монет");
            lore.add("&d" + plugin.catalog().chapterShards(chapter) + " осколков");
            lore.add("");
            lore.add("&6Нажмите, чтобы открыть");
            inv.setItem(CHAPTER_SLOTS[chapter - 1], item(CHAPTER_ICONS[chapter - 1], "&6&lГлава " + chapter + " &8• &f" + plugin.catalog().chapterName(chapter), lore));
        }
        QuestDefinition current = data.finished() ? null : plugin.catalog().get(data.currentQuest);
        boolean rewardReady = current != null && data.rewardReady(current);
        inv.setItem(31, item(current == null ? Material.NETHER_STAR : rewardReady ? Material.LIME_DYE : Material.RED_DYE,
                current == null ? "&d&lВсе 100 квестов завершены" : rewardReady ? "&a&lНАГРАДА ГОТОВА — КВЕСТ #" + current.id() : "&c&lТекущий квест #" + current.id(),
                current == null ? List.of("&7Престиж: &f" + data.prestige, "", "&dНажмите для нового престижа") :
                        questLore(current, data, true, rewardReady,
                                rewardReady ? "&aНаграда готова" : "&eВыполняется")));
        RotatingObjective daily = plugin.rotations().daily();
        RotatingObjective weekly = plugin.rotations().weekly();
        inv.setItem(37, rotating(Material.CLOCK, "&a&lЕжедневное задание", daily, data.dailyProgress, data.dailyClaimed));
        inv.setItem(38, rotating(Material.BOOK, "&6&lЕженедельное задание", weekly, data.weeklyProgress, data.weeklyClaimed));
        long globalRequired = plugin.getConfig().getLong("global-weekly.required", 25000);
        inv.setItem(39, item(Material.BEACON, "&b&lОбщая цель сервера", List.of("&7Прогресс: &f" + plugin.global().progress + "/" + globalRequired,
                "&7Участников: &f" + plugin.global().contributors.size(), "&7Награда участнику: &e" + plugin.getConfig().getDouble("global-weekly.reward-money", 25000))));
        inv.setItem(40, item(Material.ENDER_CHEST, "&d&lСекретные достижения", List.of("&7Открыто: &f" + data.claimedSecrets.size() + "/5", "&8Условия скрыты")));
        inv.setItem(41, item(data.tracker ? Material.LIME_DYE : Material.GRAY_DYE, "&e&lКонтекстный трекер: " + (data.tracker ? "&aвключён" : "&cвыключен"),
                List.of("&7Появляется снизу только тогда,", "&7когда вы выполняете текущий квест.", "&7В PvP и во время участия", "&7в ивенте трекер скрывается.", "", "&eНажмите для переключения")));
        inv.setItem(42, item(Material.CHEST, "&6&lКак получить награду", List.of("&7Выполните текущий квест.", "&7Красная кнопка станет &aзелёной&7.", "&7Нажмите на неё для получения приза.", "", "&eНажмите, чтобы открыть текущую главу")));
        inv.setItem(49, item(Material.BOOK, "&f&lСтатистика", List.of("&7Пройдено: &f" + data.highestCompleted + "/100", "&7Лучший результат: &f" + data.lifetimeHighest,
                "&7Престиж: &f" + data.prestige, "&7Сломано блоков: &f" + data.blocksBroken, "&7Побеждено существ: &f" + data.mobsKilled,
                "&7Пройдено блоков: &f" + data.distanceWalked, "&7Заработано: &e" + data.moneyEarned,
                "&7Получено осколков: &d" + data.shardsEarned)));
        player.openInventory(inv);
    }

    public void openChapter(Player player, int chapter) {
        PlayerData data = plugin.data(player);
        QuestInventoryHolder holder = new QuestInventoryHolder(QuestInventoryHolder.View.CHAPTER, chapter);
        Inventory inv = Bukkit.createInventory(holder, 54, plugin.color("&0Глава " + chapter + ": " + plugin.catalog().chapterName(chapter)));
        holder.inventory(inv);
        fill(inv);
        int first = (chapter - 1) * 10 + 1;
        for (int offset = 0; offset < 10; offset++) {
            QuestDefinition q = plugin.catalog().get(first + offset);
            boolean completed = q.id() <= data.highestCompleted;
            boolean current = q.id() == data.currentQuest;
            boolean locked = q.id() > data.currentQuest;
            boolean ready = current && data.rewardReady(q);
            Material icon = locked ? Material.GRAY_DYE : completed ? material(q.icon(), Material.PAPER) : ready ? Material.LIME_DYE : Material.RED_DYE;
            String status = completed ? "&aНаграда получена" : ready ? "&aНаграда готова" : current ? "&cВыполняется" : "&7Заблокирован";
            List<String> lore = questLore(q, data, current, ready, status);
            inv.setItem(20 + offset, item(icon, "&6#" + q.id() + " &f" + q.name(), lore));
        }
        inv.setItem(4, item(Material.CHEST, "&6&lНаграды главы", List.of(
                "&7За все десять квестов:",
                "&e" + plugin.rewards().format(plugin.rewards().questAmount(plugin.catalog().chapterMoney(chapter))) + " монет",
                "&d" + plugin.catalog().chapterShards(chapter) + " осколков",
                "",
                "&7Каждая награда забирается",
                "&7зелёной кнопкой отдельно.")));
        inv.setItem(45, item(Material.ARROW, "&eПредыдущая глава", List.of()));
        inv.setItem(49, item(Material.BARRIER, "&cНазад", List.of("&7Вернуться к главам")));
        inv.setItem(53, item(Material.ARROW, "&eСледующая глава", List.of()));
        player.openInventory(inv);
    }

    public void click(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof QuestInventoryHolder holder)) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= 54) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (holder.view() == QuestInventoryHolder.View.MAIN) clickMain(player, event.getRawSlot());
        else clickChapter(player, holder.chapter(), event.getRawSlot());
    }

    private void clickMain(Player player, int slot) {
        for (int i = 0; i < CHAPTER_SLOTS.length; i++) if (CHAPTER_SLOTS[i] == slot) { openChapter(player, i + 1); return; }
        PlayerData data = plugin.data(player);
        if (slot == 31 && data.finished()) { plugin.prestige(player); openMain(player); }
        else if (slot == 31 && !data.finished()) {
            QuestDefinition current = plugin.catalog().get(data.currentQuest);
            if (data.rewardReady(current)) plugin.claimQuestReward(player, current.id());
            openMain(player);
        }
        else if (slot == 41) { data.tracker = !data.tracker; plugin.trySave(data); openMain(player); }
        else if (slot == 42 && !data.finished()) { openChapter(player, plugin.catalog().get(data.currentQuest).chapter()); }
    }

    private void clickChapter(Player player, int chapter, int slot) {
        if (slot == 49) { openMain(player); return; }
        if (slot == 45) { openChapter(player, Math.max(1, chapter - 1)); return; }
        if (slot == 53) { openChapter(player, Math.min(10, chapter + 1)); return; }
        if (slot < 20 || slot > 29) return;
        int id = (chapter - 1) * 10 + (slot - 20) + 1;
        PlayerData data = plugin.data(player);
        if (data.currentQuest != id) return;
        QuestDefinition quest = plugin.catalog().get(id);
        if (data.rewardReady(quest)) {
            plugin.claimQuestReward(player, id);
            openChapter(player, chapter);
        }
    }

    private ItemStack rotating(Material material, String title, RotatingObjective o, long progress, boolean claimed) {
        return item(material, title, List.of("&f" + o.name(), "&7Прогресс: &e" + Math.min(progress, o.required()) + "&7/&e" + o.required(),
                "&7Награда: &e" + plugin.rewards().format(o.money()), "&7Статус: " + (claimed ? "&aполучена" : "&eактивно")));
    }

    private List<String> questLore(QuestDefinition quest, PlayerData data, boolean current, boolean ready, String status) {
        List<String> lore = new ArrayList<>();
        lore.add("&e&lЧТО НУЖНО СДЕЛАТЬ");
        lore.add("&f" + QuestText.instruction(quest));
        lore.add("");
        lore.add("&7Тип действия: &f" + QuestText.actionName(quest.type()));
        lore.add("&7Засчитывается: &f" + QuestText.targetHint(quest));
        lore.add("&7Нужно: &f" + QuestText.amount(quest));
        if (current) {
            lore.add("");
            lore.add(QuestText.progressBar(data.progress, quest.required()) + " &e" + QuestText.compactProgress(quest, data.progress));
            if (quest.timed()) {
                long elapsed = Math.max(0, (System.currentTimeMillis() - data.questStartedAt) / 1000L);
                long remaining = Math.max(0, quest.timeLimitSeconds() - elapsed);
                lore.add("&bДо конца бонусного времени: &f" + QuestText.formatDuration(remaining));
            }
        }
        lore.add("");
        lore.add("&7Валюта: &e" + plugin.rewards().format(plugin.rewards().questAmount(quest.money())));
        lore.add("&7Осколки: &d" + quest.shards());
        if (quest.timed()) lore.add("&bБонус за время: &e" + plugin.rewards().format(plugin.rewards().questAmount(quest.bonusMoney())));
        if (quest.noDeathBonus()) lore.add("&bБонус без смерти: &e" + plugin.rewards().format(plugin.rewards().questAmount(quest.bonusMoney())));
        lore.add("&7Статус: " + status);
        if (ready) { lore.add(""); lore.add("&a&lНажмите, чтобы забрать награду"); }
        return lore;
    }

    private void fill(Inventory inventory) {
        ItemStack filler = item(material(plugin.getConfig().getString("gui.filler", "BLACK_STAINED_GLASS_PANE"), Material.BLACK_STAINED_GLASS_PANE), " ", List.of());
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler);
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.color(name));
            meta.setLore(lore.stream().map(plugin::color).toList());
            item.setItemMeta(meta);
        }
        return item;
    }

    private Material material(String name, Material fallback) {
        Material material = Material.matchMaterial(name == null ? "" : name);
        return material == null ? fallback : material;
    }
}
