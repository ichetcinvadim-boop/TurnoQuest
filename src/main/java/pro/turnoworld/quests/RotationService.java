package pro.turnoworld.quests;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Locale;

public final class RotationService {
    private static final List<RotatingObjective> DAILY = List.of(
            new RotatingObjective("break", "Шахтёрская смена", QuestType.BREAK, "ANY", 160, 8000),
            new RotatingObjective("kill", "Охота дня", QuestType.KILL, "ANY", 55, 9000),
            new RotatingObjective("fish", "Удачный клёв", QuestType.FISH, "ANY", 24, 8500),
            new RotatingObjective("craft", "Мастерская", QuestType.CRAFT, "ANY", 48, 7500),
            new RotatingObjective("walk", "Путешественник", QuestType.WALK, "ANY", 5500, 7000),
            new RotatingObjective("breed", "Забота о ферме", QuestType.BREED, "ANY", 18, 8500),
            new RotatingObjective("smelt", "Горячая печь", QuestType.SMELT, "ANY", 64, 8000)
    );
    private static final List<RotatingObjective> WEEKLY = List.of(
            new RotatingObjective("break", "Неделя шахтёра", QuestType.BREAK, "ANY", 3000, 55000),
            new RotatingObjective("kill", "Неделя охотника", QuestType.KILL, "ANY", 900, 65000),
            new RotatingObjective("fish", "Большая рыбалка", QuestType.FISH, "ANY", 280, 60000),
            new RotatingObjective("walk", "Вокруг света", QuestType.WALK, "ANY", 75000, 55000),
            new RotatingObjective("craft", "Большая стройка", QuestType.CRAFT, "ANY", 900, 60000)
    );
    private final ZoneId zone;

    public RotationService(ZoneId zone) { this.zone = zone; }

    public String dayKey() { return LocalDate.now(zone).toString(); }
    public String weekKey() {
        LocalDate d = LocalDate.now(zone);
        WeekFields wf = WeekFields.ISO;
        return d.get(wf.weekBasedYear()) + "-W" + String.format(Locale.ROOT, "%02d", d.get(wf.weekOfWeekBasedYear()));
    }
    public RotatingObjective daily() { return DAILY.get(Math.floorMod(dayKey().hashCode(), DAILY.size())); }
    public RotatingObjective weekly() { return WEEKLY.get(Math.floorMod(weekKey().hashCode(), WEEKLY.size())); }

    public void normalize(PlayerData data) {
        String day = dayKey();
        if (!day.equals(data.dailyKey)) { data.dailyKey = day; data.dailyProgress = 0; data.dailyClaimed = false; }
        String week = weekKey();
        if (!week.equals(data.weeklyKey)) { data.weeklyKey = week; data.weeklyProgress = 0; data.weeklyClaimed = false; }
    }
}
