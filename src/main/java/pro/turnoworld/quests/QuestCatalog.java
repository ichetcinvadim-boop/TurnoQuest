package pro.turnoworld.quests;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class QuestCatalog {
    private final Map<Integer, QuestDefinition> quests;
    private final Map<Integer, String> chapters;

    private QuestCatalog(Map<Integer, QuestDefinition> quests, Map<Integer, String> chapters) {
        this.quests = Collections.unmodifiableMap(quests);
        this.chapters = Collections.unmodifiableMap(chapters);
    }

    public static QuestCatalog load(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Map<Integer, String> chapters = new LinkedHashMap<>();
        ConfigurationSection chapterSection = required(yaml, "chapters");
        for (int i = 1; i <= 10; i++) {
            ConfigurationSection row = required(chapterSection, String.valueOf(i));
            chapters.put(i, row.getString("name", "Глава " + i));
        }
        Map<Integer, QuestDefinition> quests = new LinkedHashMap<>();
        ConfigurationSection questSection = required(yaml, "quests");
        for (int i = 1; i <= 100; i++) {
            ConfigurationSection row = required(questSection, String.valueOf(i));
            String typeName = row.getString("type", "");
            QuestType type;
            try { type = QuestType.valueOf(typeName.toUpperCase()); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown type in quest " + i + ": " + typeName); }
            String description = row.getString("description", "Выполните условие задания");
            QuestDefinition definition = new QuestDefinition(
                    i, ((i - 1) / 10) + 1,
                    row.getString("name", "Квест " + i),
                    List.of(description), type,
                    row.getString("target", "ANY"), row.getLong("required", 1),
                    row.getDouble("money", 0), row.getInt("shards", 0),
                    row.getBoolean("timed", false), row.getLong("time-limit-seconds", 0),
                    row.getBoolean("no-death-bonus", false), row.getDouble("bonus-money", 0),
                    row.getString("icon", "PAPER")
            );
            quests.put(i, definition);
        }
        return new QuestCatalog(quests, chapters);
    }

    public QuestDefinition get(int id) { return quests.get(id); }
    public List<QuestDefinition> all() { return List.copyOf(quests.values()); }
    public String chapterName(int chapter) { return chapters.getOrDefault(chapter, "Глава " + chapter); }
    public double chapterMoney(int chapter) {
        return quests.values().stream().filter(q -> q.chapter() == chapter).mapToDouble(QuestDefinition::money).sum();
    }
    public int chapterShards(int chapter) {
        return quests.values().stream().filter(q -> q.chapter() == chapter).mapToInt(QuestDefinition::shards).sum();
    }
    public int size() { return quests.size(); }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (quests.size() != 100) errors.add("Ожидалось 100 квестов, загружено " + quests.size());
        for (int i = 1; i <= 100; i++) {
            QuestDefinition q = quests.get(i);
            if (q == null) { errors.add("Нет квеста #" + i); continue; }
            if (q.name().isBlank()) errors.add("Пустое имя квеста #" + i);
            if (q.required() <= 0) errors.add("Некорректная цель квеста #" + i);
            if (q.money() <= 0) errors.add("Нет денежной награды квеста #" + i);
            if (q.shards() <= 0) errors.add("Нет награды осколками квеста #" + i);
            if (q.bonusMoney() < 0) errors.add("Отрицательная бонусная награда квеста #" + i);
            if (q.timed() && q.timeLimitSeconds() <= 0) errors.add("Нет лимита времени у квеста #" + i);
        }
        return errors;
    }

    private static ConfigurationSection required(ConfigurationSection parent, String path) {
        ConfigurationSection section = parent.getConfigurationSection(path);
        if (section == null) throw new IllegalArgumentException("Missing configuration section: " + path);
        return section;
    }
}
