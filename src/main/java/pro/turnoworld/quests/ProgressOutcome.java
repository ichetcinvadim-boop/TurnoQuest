package pro.turnoworld.quests;

public record ProgressOutcome(boolean changed, boolean completed, long added, long progress, boolean timedBonus, boolean noDeathBonus) {
    public static ProgressOutcome none(long progress) { return new ProgressOutcome(false, false, 0, progress, false, false); }
}
