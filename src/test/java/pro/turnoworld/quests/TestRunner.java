package pro.turnoworld.quests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

public final class TestRunner {
    private static int checks;
    private static final Pattern ROW = Pattern.compile("^  (\\d+): \\{(.*)}$");
    private static final Pattern FIELD = Pattern.compile("([a-z][a-z-]*): (?:\\\"([^\\\"]*)\\\"|([^,}]+))");

    public static void main(String[] args) throws Exception {
        Path project = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath();
        Map<Integer, QuestDefinition> quests = parse(project.resolve("src/main/resources/quests.yml"));
        check(quests.size() == 100, "exactly 100 quests");
        for (int id = 1; id <= 100; id++) {
            QuestDefinition q = quests.get(id);
            check(q != null, "quest exists " + id);
            check(q.id() == id, "quest sequence " + id);
            check(q.chapter() == ((id - 1) / 10) + 1, "chapter mapping " + id);
            check(!q.name().isBlank(), "quest name " + id);
            check(q.required() > 0, "positive requirement " + id);
            check(q.money() >= 0, "non-negative reward " + id);
            check(!q.icon().isBlank(), "icon " + id);
            check(!q.target().isBlank(), "target " + id);
            if (id % 10 == 0) check(!q.rewardItem().isBlank(), "chapter reward " + id);
        }
        engineTests(quests);
        persistenceTests();
        rotationTests();
        resourceTests(project);
        System.out.println("TURN0QUESTS_TESTS_OK=" + checks);
    }

    private static Map<Integer, QuestDefinition> parse(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Map<Integer, String> rewards = new HashMap<>();
        boolean inQuests = false;
        for (String line : lines) {
            if (line.equals("quests:")) { inQuests = true; break; }
            Matcher row = ROW.matcher(line);
            if (!row.matches()) continue;
            Map<String, String> fields = fields(row.group(2));
            if (fields.containsKey("reward")) rewards.put(Integer.parseInt(row.group(1)), fields.get("reward"));
        }
        check(rewards.size() == 10, "10 chapter rewards");
        Map<Integer, QuestDefinition> result = new HashMap<>();
        for (String line : lines) {
            if (line.equals("quests:")) { inQuests = true; continue; }
            if (!inQuests) continue;
            Matcher row = ROW.matcher(line);
            if (!row.matches()) continue;
            int id = Integer.parseInt(row.group(1));
            Map<String, String> f = fields(row.group(2));
            if (!f.containsKey("type")) continue;
            QuestDefinition q = new QuestDefinition(id, ((id - 1) / 10) + 1, f.get("name"), List.of(f.get("description")),
                    QuestType.valueOf(f.get("type")), f.getOrDefault("target", "ANY"), Long.parseLong(f.get("required")),
                    Double.parseDouble(f.get("money")), rewards.get(((id - 1) / 10) + 1), Boolean.parseBoolean(f.getOrDefault("timed", "false")),
                    Long.parseLong(f.getOrDefault("time-limit-seconds", "0")), Boolean.parseBoolean(f.getOrDefault("no-death-bonus", "false")),
                    Double.parseDouble(f.getOrDefault("bonus-money", "0")), f.get("icon"));
            result.put(id, q);
        }
        return result;
    }

    private static Map<String, String> fields(String body) {
        Map<String, String> result = new HashMap<>();
        Matcher matcher = FIELD.matcher(body);
        while (matcher.find()) result.put(matcher.group(1), matcher.group(2) == null ? matcher.group(3).trim() : matcher.group(2));
        return result;
    }

    private static void engineTests(Map<Integer, QuestDefinition> quests) {
        ProgressEngine engine = new ProgressEngine();
        PlayerData p = new PlayerData(UUID.randomUUID(), "Tester");
        QuestDefinition first = quests.get(1);
        check(!engine.add(p, first, QuestType.KILL, "OAK_LOG", 1, System.currentTimeMillis()).changed(), "wrong type ignored");
        check(!engine.add(p, first, QuestType.BREAK, "BIRCH_LOG", 1, System.currentTimeMillis()).changed(), "wrong target ignored");
        ProgressOutcome partial = engine.add(p, first, QuestType.BREAK, "OAK_LOG", 5, System.currentTimeMillis());
        check(partial.changed() && !partial.completed() && p.progress == 5, "partial progress");
        ProgressOutcome done = engine.add(p, first, QuestType.BREAK, "OAK_LOG", 999, System.currentTimeMillis());
        check(done.completed() && p.progress == first.required(), "completion caps");
        check(p.currentQuest == 1 && p.rewardReady(first), "completion waits for reward click");
        engine.advance(p, first, System.currentTimeMillis());
        check(p.currentQuest == 2 && p.highestCompleted == 1 && p.progress == 0, "advance exact");
        check(!p.rewardReady(first), "claimed quest no longer ready");
        check(!engine.add(p, first, QuestType.BREAK, "OAK_LOG", 1, System.currentTimeMillis()).changed(), "old quest locked");
        p.rewardedQuests.add(1); p.rewardedChapters.add(1); p.rewardedBonuses.add(1);
        engine.setQuest(p, 54);
        check(p.currentQuest == 54 && p.highestCompleted == 53, "set quest");
        engine.resetTo(p, 34);
        check(p.currentQuest == 34 && p.highestCompleted == 33 && p.progress == 0, "reset returns exact");
        check(p.rewardedQuests.contains(1) && p.rewardedChapters.contains(1) && p.rewardedBonuses.contains(1), "reset preserves rewards");
        engine.setQuest(p, 101);
        check(p.finished() && p.highestCompleted == 100, "finish state");
        for (int id = 1; id <= 100; id++) {
            PlayerData one = new PlayerData(UUID.randomUUID(), "P" + id);
            engine.setQuest(one, id);
            QuestDefinition q = quests.get(id);
            ProgressOutcome result = engine.add(one, q, q.type(), q.target(), q.required(), System.currentTimeMillis());
            check(result.completed(), "every quest completable " + id);
            engine.advance(one, q, System.currentTimeMillis());
            check(one.currentQuest == id + 1, "every quest advances " + id);
        }
    }

    private static void persistenceTests() throws Exception {
        Path dir = Files.createTempDirectory("turnoquests-test-");
        FileStore store = new FileStore(dir);
        UUID id = UUID.randomUUID();
        PlayerData data = store.getOrCreate(id, "OfflineTester");
        data.currentQuest = 54; data.progress = 17; data.highestCompleted = 53; data.lifetimeHighest = 80; data.prestige = 2;
        data.rewardedQuests.addAll(List.of(1, 2, 10)); data.rewardedBonuses.add(10); data.pendingMoney.addAll(List.of(10, -10)); data.pendingItems.add(5); data.claimedSecrets.add("miner-10000");
        store.save(data);
        PlayerData loaded = new FileStore(dir).find("OfflineTester").orElseThrow();
        check(loaded.uuid.equals(id), "offline UUID load");
        check(loaded.currentQuest == 54 && loaded.progress == 17, "progress load");
        check(loaded.highestCompleted == 53 && loaded.lifetimeHighest == 80, "milestones load");
        check(loaded.prestige == 2, "prestige load");
        check(loaded.rewardedQuests.size() == 3 && loaded.rewardedBonuses.contains(10), "reward ledger load");
        check(loaded.pendingMoney.contains(10) && loaded.pendingMoney.contains(-10), "pending money survives restart");
        check(loaded.pendingItems.contains(5) && loaded.claimedSecrets.contains("miner-10000"), "pending and secrets load");
        check(new FileStore(dir).find(id.toString()).isPresent(), "offline UUID lookup");
        store.audit("Console", "RESET", data, "from=54,to=34");
        check(store.history(data, 10).size() == 1, "audit history");
        Path backup = store.backup();
        check(Files.exists(backup) && Files.size(backup) > 0, "backup exists");
        try (ZipFile zip = new ZipFile(backup.toFile())) {
            check(zip.getEntry("players/" + id + ".properties") != null, "backup player entry");
            check(zip.getEntry("audit.log") != null, "backup audit entry");
        }
        Properties props = data.toProperties();
        PlayerData roundtrip = PlayerData.from(props);
        check(roundtrip.uuid.equals(data.uuid) && roundtrip.lastName.equals(data.lastName), "properties identity");
        check(roundtrip.toProperties().size() >= 28, "all state persisted");
    }

    private static void rotationTests() {
        RotationService rotations = new RotationService(ZoneId.of("Europe/Moscow"));
        check(!rotations.dayKey().isBlank() && rotations.dayKey().length() == 10, "day key");
        check(rotations.weekKey().contains("-W"), "week key");
        check(rotations.daily().required() > 0 && rotations.daily().money() > 0, "daily valid");
        check(rotations.weekly().required() > rotations.daily().required(), "weekly harder");
        PlayerData p = new PlayerData(UUID.randomUUID(), "R");
        rotations.normalize(p);
        check(p.dailyKey.equals(rotations.dayKey()) && p.weeklyKey.equals(rotations.weekKey()), "rotation normalize");
        p.dailyProgress = 99; p.dailyClaimed = true; p.dailyKey = "old";
        rotations.normalize(p);
        check(p.dailyProgress == 0 && !p.dailyClaimed, "daily resets");
        GlobalState global = new GlobalState(Path.of(System.getProperty("java.io.tmpdir"), "tq-global-" + UUID.randomUUID()));
        global.normalize(rotations.weekKey());
        UUID u = UUID.randomUUID(); global.add(u, 100);
        check(global.progress == 100 && global.contributors.contains(u), "global contribution");
        check(global.mayClaim(u, 100), "global claim ready");
        global.claim(u); check(!global.mayClaim(u, 100), "global one claim");
        global.forceReset(rotations.weekKey()); check(global.progress == 0 && global.claimed.isEmpty(), "global reset");
    }

    private static void resourceTests(Path project) throws IOException {
        String plugin = Files.readString(project.resolve("src/main/resources/plugin.yml"));
        String config = Files.readString(project.resolve("src/main/resources/config.yml"));
        String quests = Files.readString(project.resolve("src/main/resources/quests.yml"));
        String gui = Files.readString(project.resolve("src/main/java/pro/turnoworld/quests/QuestGui.java"));
        String core = Files.readString(project.resolve("src/main/java/pro/turnoworld/quests/TurnoQuests.java"));
        String command = Files.readString(project.resolve("src/main/java/pro/turnoworld/quests/QuestCommand.java"));
        String npc = Files.readString(project.resolve("src/main/java/pro/turnoworld/quests/QuestNpcService.java"));
        String workflow = Files.readString(project.resolve(".github/workflows/build.yml"));
        String pom = Files.readString(project.resolve("pom.xml"));
        check(plugin.contains("api-version: '1.21'"), "api version");
        check(plugin.contains("version: 1.3.1"), "plugin version");
        check(gui.contains("Material.RED_DYE") && gui.contains("Material.LIME_DYE"), "red and green reward button states");
        check(gui.contains("claimQuestReward(player") && core.contains("public synchronized boolean claimQuestReward"), "manual reward click wired");
        check(core.contains("Откройте /quests и нажмите зелёную кнопку"), "completion explains manual claim");
        check(config.contains("glowing: true"), "npc visibility default");
        check(plugin.contains("turnoquests.admin.reset") && plugin.contains("turnoquests.admin.reward"), "permissions documented");
        check(plugin.contains("aliases: [tq]"), "tq alias");
        check(config.contains("quest-reward-multiplier: 0.4"), "economy balanced");
        check(config.contains("ignore-player-placed-blocks: true") && config.contains("ignore-spawner-and-egg-mobs: true"), "anti exploit defaults");
        check(config.contains("npc:") && config.contains("entity-type: VILLAGER"), "built-in npc defaults");
        check(!config.contains("bosses:") && !config.toLowerCase().contains("arena"), "no boss arenas");
        check(!plugin.contains("MythicMobs") && !plugin.contains("Citizens") && !plugin.contains("PlaceholderAPI"), "no unrelated soft dependencies");
        check(!config.toLowerCase().contains("shard") && !config.toLowerCase().contains("playerpoints"), "no shards");
        check(!quests.contains("MYTHIC_KILL") && !quests.toLowerCase().contains("boss") && !quests.toLowerCase().contains("босс"), "no boss quests");
        check(quests.contains("MINECRAFT:NETHER/ALL_EFFECTS") && quests.contains("MINECRAFT:END/RESPAWN_DRAGON"), "vanilla final objectives");
        check(quests.contains("luk_astralnogo_shtorma") && quests.contains("09_krylya_arhangela"), "first and final item rewards");
        for (String sub : List.of("menu", "tracker", "prestige", "help", "reload", "validate", "backup", "top", "global", "npc",
                "info", "skip", "complete", "reset", "set", "progress", "reward", "history"))
            check(command.contains("\"" + sub + "\""), "command route " + sub);
        check(command.contains("catch (Throwable error)") && command.contains("Level.SEVERE"), "commands log complete failures");
        check(core.contains("catch (Throwable error)") && core.contains("disablePlugin(this)"), "startup failure is logged and disabled safely");
        check(npc.contains("spawnEntity") && npc.contains("catch (Throwable e)"), "npc spawn failure is fully logged");
        check(pom.contains("paper-api") && pom.contains("<version>1.3.1</version>"), "real Paper API build");
        check(pom.contains("org.ow2.asm") && workflow.contains("AbiVerifier"), "runtime ABI verification enabled");
        check(workflow.contains("pull_request:") && workflow.contains("dependency:build-classpath"), "CI checks every pull request with dependencies");
        for (String forbidden : List.of("build-support/stubs", "jar-stage", "api-stub-classes"))
            check(!workflow.contains(forbidden), "release never builds with stubs: " + forbidden);
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError("FAILED: " + name + " (check " + checks + ")");
    }
}
