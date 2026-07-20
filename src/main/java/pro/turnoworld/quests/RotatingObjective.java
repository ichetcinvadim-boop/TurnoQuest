package pro.turnoworld.quests;

public record RotatingObjective(String key, String name, QuestType type, String target, long required, double money) {
    public boolean matches(QuestType eventType, String eventTarget) {
        return type == eventType && ("ANY".equals(target) || target.equalsIgnoreCase(eventTarget));
    }
}
