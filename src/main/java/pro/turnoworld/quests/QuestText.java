package pro.turnoworld.quests;

import java.util.Locale;

/** Builds player-facing quest text without exposing Bukkit enum names. */
public final class QuestText {
    private QuestText() { }

    public static String instruction(QuestDefinition quest) {
        if (quest.description().isEmpty()) return "Выполните условие задания";
        return String.join(" ", quest.description()).trim();
    }

    public static String actionName(QuestType type) {
        return switch (type) {
            case BREAK -> "добыча блоков";
            case PLACE -> "установка блоков";
            case KILL -> "победа над существами";
            case CRAFT -> "создание предметов";
            case SMELT -> "переплавка";
            case FISH -> "рыбалка";
            case BREED -> "разведение животных";
            case TAME -> "приручение";
            case SHEAR -> "стрижка";
            case CONSUME -> "использование еды или зелий";
            case ENCHANT -> "зачарование";
            case SLEEP -> "сон";
            case WALK -> "путь пешком";
            case PLAYTIME -> "время в игре";
            case VISIT_WORLD -> "посещение мира";
            case VISIT_BIOME -> "посещение биома";
            case ADVANCEMENT -> "получение достижения";
            case DAMAGE -> "нанесение урона";
            case BLOCK_DAMAGE -> "блокирование урона щитом";
            case DELIVER -> "передача предметов";
        };
    }

    public static String targetHint(QuestDefinition quest) {
        if ("WOODEN_TOOL".equals(quest.target()))
            return "любой деревянный меч, кирка, топор, лопата или мотыга";
        if ("ANY".equals(quest.target())) return switch (quest.type()) {
            case BREAK, PLACE -> "любой подходящий блок";
            case KILL -> "любое подходящее существо";
            case CRAFT, ENCHANT, DELIVER -> "любой подходящий предмет";
            case FISH -> "любая пойманная рыба";
            case BREED, TAME, SHEAR -> "любое подходящее животное";
            default -> "любое действие, указанное в задании";
        };
        return "только цель, указанная в задании";
    }

    public static String amount(QuestDefinition quest) {
        long value = quest.required();
        String unit = switch (quest.type()) {
            case DAMAGE, BLOCK_DAMAGE -> "ед. урона";
            case WALK -> plural(value, "блок пути", "блока пути", "блоков пути");
            case PLAYTIME -> plural(value, "тик", "тика", "тиков");
            case BREAK, PLACE -> plural(value, "блок", "блока", "блоков");
            case KILL -> plural(value, "существо", "существа", "существ");
            case CRAFT, SMELT, ENCHANT, DELIVER -> plural(value, "предмет", "предмета", "предметов");
            case FISH -> plural(value, "улов", "улова", "уловов");
            default -> plural(value, "раз", "раза", "раз");
        };
        return value + " " + unit;
    }

    public static String progressBar(long progress, long required) {
        int filled = required <= 0 ? 0 : (int) Math.min(10, Math.max(0, progress) * 10 / required);
        return "&a" + "■".repeat(filled) + "&8" + "■".repeat(10 - filled);
    }

    public static String compactProgress(QuestDefinition quest, long progress) {
        return Math.min(progress, quest.required()) + "/" + quest.required();
    }

    public static String formatDuration(long seconds) {
        long value = Math.max(0, seconds);
        long hours = value / 3600;
        long minutes = (value % 3600) / 60;
        long secs = value % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, secs)
                : String.format(Locale.ROOT, "%02d:%02d", minutes, secs);
    }

    private static String plural(long number, String one, String few, String many) {
        long mod100 = Math.abs(number) % 100;
        long mod10 = mod100 % 10;
        if (mod100 >= 11 && mod100 <= 14) return many;
        if (mod10 == 1) return one;
        if (mod10 >= 2 && mod10 <= 4) return few;
        return many;
    }
}
