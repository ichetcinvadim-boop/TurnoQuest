package pro.turnoworld.quests;

import java.util.List;

public record QuestDefinition(
        int id,
        int chapter,
        String name,
        List<String> description,
        QuestType type,
        String target,
        long required,
        double money,
        String rewardItem,
        boolean timed,
        long timeLimitSeconds,
        boolean noDeathBonus,
        double bonusMoney,
        String icon
) {
    public QuestDefinition {
        description = List.copyOf(description);
        target = target == null ? "ANY" : target.toUpperCase();
        rewardItem = rewardItem == null ? "" : rewardItem;
        icon = icon == null || icon.isBlank() ? "PAPER" : icon.toUpperCase();
        if (id < 1 || id > 100) throw new IllegalArgumentException("Quest id must be 1..100");
        if (chapter != ((id - 1) / 10) + 1) throw new IllegalArgumentException("Wrong chapter for quest " + id);
        if (required < 1) throw new IllegalArgumentException("required must be positive");
    }

    public boolean matches(String value) {
        if ("ANY".equals(target) || target.equalsIgnoreCase(value)) return true;
        // Vanilla ores have stone and deepslate variants; both count toward the same
        // mining objective without making administrators duplicate every target.
        return target.endsWith("_ORE") && ("DEEPSLATE_" + target).equalsIgnoreCase(value);
    }
}
