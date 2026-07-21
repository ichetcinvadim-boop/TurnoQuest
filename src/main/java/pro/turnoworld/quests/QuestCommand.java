package pro.turnoworld.quests;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class QuestCommand implements CommandExecutor, TabCompleter {
    private final TurnoQuests plugin;
    public QuestCommand(TurnoQuests plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("quests")) return playerCommand(sender, args);
        if (args.length == 0) { help(sender); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("menu")) return playerCommand(sender, slice(args));
        if (sub.equals("tracker")) return toggleTracker(sender);
        if (sub.equals("prestige")) return playerPrestige(sender);
        if (sub.equals("help")) { help(sender); return true; }
        if (sub.equals("reload")) {
            if (!permission(sender, "turnoquests.admin.reload")) return true;
            try { plugin.reloadQuestFiles(); sender.sendMessage(plugin.color(plugin.prefix() + "&aПерезагружено 100 квестов.")); }
            catch (RuntimeException e) { sender.sendMessage(plugin.color(plugin.prefix() + "&cОшибка: " + e.getMessage())); }
            return true;
        }
        if (sub.equals("validate")) {
            if (!permission(sender, "turnoquests.admin.validate")) return true;
            List<String> errors = plugin.validateConfiguration();
            sender.sendMessage(plugin.color(plugin.prefix() + (errors.isEmpty() ? "&aОшибок не найдено." : "&eНайдено замечаний: " + errors.size())));
            errors.forEach(s -> sender.sendMessage(plugin.color("&8- &f" + s)));
            return true;
        }
        if (sub.equals("backup")) {
            if (!permission(sender, "turnoquests.admin.backup")) return true;
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                String message;
                try { message = plugin.color(plugin.prefix() + "&aРезервная копия: &f" + plugin.store().backup().getFileName()); }
                catch (IOException e) { message = plugin.color(plugin.prefix() + "&cОшибка backup: " + e.getMessage()); }
                String result = message;
                plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(result));
            });
            return true;
        }
        if (sub.equals("top")) return top(sender, args);
        if (sub.equals("global") && args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
            if (!permission(sender, "turnoquests.admin.progress")) return true;
            plugin.global().forceReset(plugin.rotations().weekKey());
            sender.sendMessage(plugin.color(plugin.prefix() + "&eОбщая цель недели сброшена."));
            return true;
        }
        if (sub.equals("npc")) return npc(sender, args);

        if (args.length < 2) { help(sender); return true; }
        Optional<PlayerData> found = plugin.findData(args[1]);
        if (found.isEmpty()) { sender.sendMessage(plugin.color(plugin.prefix() + "&cИгрок не найден. Используйте UUID или последний известный ник.")); return true; }
        PlayerData data = found.get();
        switch (sub) {
            case "info" -> info(sender, data);
            case "skip" -> skip(sender, data, args, false);
            case "complete" -> skip(sender, data, args, true);
            case "reset" -> reset(sender, data, args);
            case "set" -> set(sender, data, args);
            case "progress" -> progress(sender, data, args);
            case "reward" -> reward(sender, data, args);
            case "history" -> history(sender, data, args);
            default -> help(sender);
        }
        return true;
    }

    private boolean playerCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(plugin.color(plugin.prefix() + "&cКоманда доступна игроку.")); return true; }
        if (!permission(sender, "turnoquests.use")) return true;
        if (args.length > 0 && args[0].equalsIgnoreCase("prestige")) return playerPrestige(sender);
        if (args.length > 0 && args[0].equalsIgnoreCase("tracker")) return toggleTracker(sender);
        if (args.length > 0) {
            Integer chapter = number(args[0]);
            if (chapter != null && chapter >= 1 && chapter <= 10) { plugin.openChapter(player, chapter); return true; }
        }
        plugin.open(player);
        return true;
    }

    private boolean toggleTracker(CommandSender sender) {
        if (!(sender instanceof Player player)) { sender.sendMessage(plugin.color("&cТолько для игрока.")); return true; }
        PlayerData data = plugin.data(player);
        data.tracker = !data.tracker;
        plugin.trySave(data);
        sender.sendMessage(plugin.color(plugin.prefix() + (data.tracker ? "&aТрекер включён." : "&eТрекер выключен.")));
        return true;
    }

    private boolean playerPrestige(CommandSender sender) {
        if (!(sender instanceof Player player)) { sender.sendMessage(plugin.color("&cТолько для игрока.")); return true; }
        plugin.prestige(player);
        return true;
    }

    private void info(CommandSender sender, PlayerData d) {
        if (!permission(sender, "turnoquests.admin.info")) return;
        sender.sendMessage(plugin.color("&6TurnoQuests: &f" + d.lastName + " &8(" + d.uuid + ")"));
        sender.sendMessage(plugin.color("&7Текущий квест: &e" + d.currentQuest + " &7прогресс: &e" + d.progress));
        sender.sendMessage(plugin.color("&7Пройдено: &f" + d.highestCompleted + " &7лучший результат: &f" + d.lifetimeHighest + " &7престиж: &f" + d.prestige));
        sender.sendMessage(plugin.color("&7Ожидают: &f" + d.pendingMoney.size() + " денежных, " + d.pendingItems.size() + " предметных наград"));
    }

    private void skip(CommandSender sender, PlayerData data, String[] args, boolean reward) {
        if (!permission(sender, "turnoquests.admin.progress")) return;
        if (data.finished()) { sender.sendMessage(plugin.color("&cИгрок уже завершил все квесты.")); return; }
        int quest = args.length >= 3 ? validQuest(sender, args[2]) : data.currentQuest;
        if (quest < 1) return;
        if (quest != data.currentQuest) { sender.sendMessage(plugin.color("&cМожно пропустить только текущий квест #" + data.currentQuest + ". Для перехода используйте /tq set.")); return; }
        QuestDefinition q = plugin.catalog().get(quest);
        if (reward) plugin.complete(plugin.online(data), data, q, false, false, false);
        else plugin.engine().setQuest(data, quest + 1);
        plugin.store().audit(sender.getName(), reward ? "COMPLETE" : "SKIP", data, "quest=" + quest + ", reward=" + reward);
        if (reward && plugin.online(data) == null) plugin.rewards().claim(data, null, plugin.catalog());
        plugin.trySave(data);
        sender.sendMessage(plugin.color(plugin.prefix() + "&aКвест #" + quest + " для " + data.lastName + (reward ? " выполнен — награда ждёт на зелёной кнопке." : " пропущен без награды.")));
    }

    private void reset(CommandSender sender, PlayerData data, String[] args) {
        if (!permission(sender, "turnoquests.admin.reset")) return;
        if (args.length < 3) { sender.sendMessage("/tq reset <игрок|UUID> <1-100>"); return; }
        int quest = validQuest(sender, args[2]); if (quest < 1) return;
        int old = data.currentQuest;
        plugin.engine().resetTo(data, quest);
        plugin.store().audit(sender.getName(), "RESET", data, "from=" + old + ", to=" + quest + ", reward-ledger=preserved");
        plugin.trySave(data);
        sender.sendMessage(plugin.color(plugin.prefix() + "&e" + data.lastName + " возвращён на квест #" + quest + ". Награды повторно не открыты."));
    }

    private void set(CommandSender sender, PlayerData data, String[] args) {
        if (!permission(sender, "turnoquests.admin.progress")) return;
        if (args.length < 3) { sender.sendMessage("/tq set <игрок|UUID> <1-100>"); return; }
        int quest = validQuest(sender, args[2]); if (quest < 1) return;
        int old = data.currentQuest;
        plugin.engine().setQuest(data, quest);
        plugin.store().audit(sender.getName(), "SET", data, "from=" + old + ", to=" + quest);
        plugin.trySave(data);
        sender.sendMessage(plugin.color(plugin.prefix() + "&aТекущий квест " + data.lastName + " установлен на #" + quest));
    }

    private void progress(CommandSender sender, PlayerData data, String[] args) {
        if (!permission(sender, "turnoquests.admin.progress")) return;
        if (args.length < 4 || !(args[2].equalsIgnoreCase("add") || args[2].equalsIgnoreCase("set"))) {
            sender.sendMessage("/tq progress <игрок|UUID> <add|set> <число>"); return;
        }
        if (data.finished()) { sender.sendMessage(plugin.color("&cВсе квесты завершены.")); return; }
        Long value = longNumber(args[3]);
        if (value == null || value < 0) { sender.sendMessage(plugin.color("&cНужно неотрицательное число.")); return; }
        QuestDefinition q = plugin.catalog().get(data.currentQuest);
        long old = data.progress;
        data.progress = Math.min(q.required(), args[2].equalsIgnoreCase("add") ? data.progress + value : value);
        plugin.store().audit(sender.getName(), "PROGRESS", data, "quest=" + q.id() + ", from=" + old + ", to=" + data.progress);
        if (data.progress >= q.required()) plugin.complete(plugin.online(data), data, q, false, false, false); else plugin.trySave(data);
        sender.sendMessage(plugin.color(plugin.prefix() + "&aПрогресс: " + data.progress + "/" + q.required()));
    }

    private void reward(CommandSender sender, PlayerData data, String[] args) {
        if (!permission(sender, "turnoquests.admin.reward")) return;
        if (args.length < 3) { sender.sendMessage("/tq reward <игрок|UUID> <claim|reopen> [квест]"); return; }
        if (args[2].equalsIgnoreCase("claim")) {
            List<String> result = plugin.rewards().claim(data, plugin.online(data), plugin.catalog());
            plugin.trySave(data);
            sender.sendMessage(plugin.color(plugin.prefix() + (result.isEmpty() ? "&eНет доступных наград или предмет ждёт входа игрока." : "&aОбработано: " + result.size())));
            return;
        }
        if (!args[2].equalsIgnoreCase("reopen") || args.length < 4) { sender.sendMessage("/tq reward <игрок> reopen <квест>"); return; }
        int quest = validQuest(sender, args[3]); if (quest < 1) return;
        data.pendingMoney.add(quest);
        if (quest % 10 == 0) data.pendingItems.add(quest / 10);
        plugin.store().audit(sender.getName(), "REWARD_REOPEN", data, "quest=" + quest + ", DANGEROUS=true");
        plugin.trySave(data);
        sender.sendMessage(plugin.color(plugin.prefix() + "&cНаграда #" + quest + " повторно открыта для " + data.lastName + ". Действие записано."));
    }

    private void history(CommandSender sender, PlayerData data, String[] args) {
        if (!permission(sender, "turnoquests.admin.history")) return;
        int limit = args.length >= 3 && number(args[2]) != null ? Math.min(50, Math.max(1, number(args[2]))) : 10;
        List<String> lines = plugin.store().history(data, limit);
        sender.sendMessage(plugin.color("&6История " + data.lastName + ":"));
        if (lines.isEmpty()) sender.sendMessage(plugin.color("&7Записей нет."));
        else lines.forEach(line -> sender.sendMessage(plugin.color("&8- &f" + line)));
    }

    private boolean npc(CommandSender sender, String[] args) {
        if (!permission(sender, "turnoquests.admin.npc")) return true;
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "help";
        if (action.equals("set")) {
            if (!(sender instanceof Player player)) { sender.sendMessage(plugin.color("&cNPC ставится в позиции игрока. Выполните команду из игры.")); return true; }
            sender.sendMessage(plugin.color(plugin.prefix() + (plugin.npcService().setLocation(player) ? "&aКвестовый NPC установлен: &f" + plugin.npcService().locationText() : "&cНе удалось установить NPC. Проверьте консоль.")));
        } else if (action.equals("spawn")) {
            sender.sendMessage(plugin.color(plugin.prefix() + (plugin.npcService().spawn() ? "&aКвестовый NPC создан: &f" + plugin.npcService().locationText() : "&cНе удалось создать NPC. Используйте /tq npc set и проверьте консоль.")));
        } else if (action.equals("remove")) {
            plugin.npcService().remove();
            sender.sendMessage(plugin.color(plugin.prefix() + "&eКвестовый NPC удалён."));
        } else sender.sendMessage("/tq npc <set|spawn|remove>");
        return true;
    }

    private boolean top(CommandSender sender, String[] args) {
        int page = args.length >= 2 && number(args[1]) != null ? Math.max(1, number(args[1])) : 1;
        List<PlayerData> all = new ArrayList<>(plugin.store().allKnown());
        all.sort(Comparator.comparingInt((PlayerData d) -> d.lifetimeHighest).thenComparingLong(d -> d.moneyEarned).reversed());
        int from = (page - 1) * 10;
        sender.sendMessage(plugin.color("&6&lТоп квестов &7— страница " + page));
        for (int i = from; i < Math.min(all.size(), from + 10); i++) {
            PlayerData d = all.get(i);
            sender.sendMessage(plugin.color("&e" + (i + 1) + ". &f" + d.lastName + " &8— &6" + d.lifetimeHighest + "/100 &7(P" + d.prestige + ")"));
        }
        return true;
    }

    private void help(CommandSender sender) {
        sender.sendMessage(plugin.color("&6&lTurnoQuests &f1.3.0"));
        sender.sendMessage(plugin.color("&e/quests [1-10] &7— меню или глава"));
        sender.sendMessage(plugin.color("&e/tq info <игрок|UUID> &7— офлайн-информация"));
        sender.sendMessage(plugin.color("&e/tq skip|complete <игрок|UUID> [квест]"));
        sender.sendMessage(plugin.color("&e/tq reset|set <игрок|UUID> <квест>"));
        sender.sendMessage(plugin.color("&e/tq progress <игрок|UUID> add|set <число>"));
        sender.sendMessage(plugin.color("&e/tq reward <игрок|UUID> claim|reopen [квест]"));
        sender.sendMessage(plugin.color("&e/tq history <игрок|UUID> [количество]"));
        sender.sendMessage(plugin.color("&e/tq npc set|spawn|remove &7— собственный квестовый NPC"));
        sender.sendMessage(plugin.color("&e/tq validate|reload|backup|top"));
    }

    private boolean permission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission) || sender.hasPermission("turnoquests.admin")) return true;
        sender.sendMessage(plugin.color(plugin.prefix() + "&cНет разрешения " + permission));
        return false;
    }
    private int validQuest(CommandSender sender, String raw) {
        Integer value = number(raw);
        if (value == null || value < 1 || value > 100) { sender.sendMessage(plugin.color("&cНужен номер квеста от 1 до 100.")); return -1; }
        return value;
    }
    private static Integer number(String raw) { try { return Integer.parseInt(raw); } catch (NumberFormatException e) { return null; } }
    private static Long longNumber(String raw) { try { return Long.parseLong(raw); } catch (NumberFormatException e) { return null; } }
    private static String[] slice(String[] args) { return java.util.Arrays.copyOfRange(args, 1, args.length); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("quests")) return args.length == 1 ? List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "tracker", "prestige") : List.of();
        if (args.length == 1) return complete(args[0], List.of("menu", "tracker", "prestige", "info", "skip", "complete", "reset", "set", "progress", "reward", "history", "top", "npc", "validate", "reload", "backup", "global"));
        if (args.length == 2 && List.of("info", "skip", "complete", "reset", "set", "progress", "reward", "history").contains(args[0].toLowerCase(Locale.ROOT)))
            return complete(args[1], plugin.store().allKnown().stream().map(d -> d.lastName).distinct().sorted().toList());
        if (args.length == 3 && args[0].equalsIgnoreCase("progress")) return complete(args[2], List.of("add", "set"));
        if (args.length == 3 && args[0].equalsIgnoreCase("reward")) return complete(args[2], List.of("claim", "reopen"));
        if (args.length == 2 && args[0].equalsIgnoreCase("npc")) return complete(args[1], List.of("set", "spawn", "remove"));
        return List.of();
    }
    private static List<String> complete(String prefix, List<String> values) { String p = prefix.toLowerCase(Locale.ROOT); return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(p)).toList(); }
}
