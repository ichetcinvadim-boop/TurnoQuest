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
        double totalMoney = 0;
        int totalShards = 0;
        long substantial = 0;
        for (int id = 1; id <= 100; id++) {
            QuestDefinition q = quests.get(id);
            check(q != null, "quest exists " + id);
            check(q.id() == id, "quest sequence " + id);
            check(q.chapter() == ((id - 1) / 10) + 1, "chapter mapping " + id);
            check(!q.name().isBlank(), "quest name " + id);
            check(q.required() > 0, "positive requirement " + id);
            check(q.money() > 0, "positive money reward " + id);
            check(q.shards() > 0, "positive shard reward " + id);
            check(!q.icon().isBlank(), "icon " + id);
            check(!q.target().isBlank(), "target " + id);
            totalMoney += q.money();
            totalShards += q.shards();
            if (q.required() >= 64 || q.type() == QuestType.ADVANCEMENT) substantial++;
        }
        check(totalMoney == 300000, "season money total");
        check(totalShards == 9700, "season shard total");
        check(substantial >= 75, "season quests require sustained play");
        check(quests.get(1).required() >= 128, "first quest is easy but not instant");
        check(quests.get(94).required() >= 1000000, "late travel quest is legendary");
        check(quests.get(100).shards() > quests.get(10).shards(), "final reward exceeds first chapter");
        int previousChapterShards = 0;
        double previousChapterMoney = 0;
        for (int chapter = 1; chapter <= 10; chapter++) {
            int from = (chapter - 1) * 10 + 1;
            int chapterShards = 0;
            double chapterMoney = 0;
            for (int id = from; id < from + 10; id++) {
                chapterShards += quests.get(id).shards();
                chapterMoney += quests.get(id).money();
            }
            check(chapterShards >= previousChapterShards, "chapter shard progression " + chapter);
            check(chapterMoney > previousChapterMoney, "chapter money progression " + chapter);
            if (chapter == 1) check(chapterShards == 500, "first chapter gives 500 shards");
            previousChapterShards = chapterShards;
            previousChapterMoney = chapterMoney;
        }
        engineTests(quests);
        presentationTests(quests);
        persistenceTests();
        rotationTests();
        resourceTests(project);
        System.out.println("TURN0QUESTS_TESTS_OK=" + checks);
    }

    private static Map<Integer, QuestDefinition> parse(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        boolean inQuests = false;
        for (String line : lines) {
            if (line.equals("quests:")) { inQuests = true; break; }
        }
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
                    Double.parseDouble(f.get("money")), Integer.parseInt(f.get("shards")), Boolean.parseBoolean(f.getOrDefault("timed", "false")),
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
        p.rewardedQuests.add(1); p.rewardedShards.add(1); p.rewardedBonuses.add(1);
        engine.setQuest(p, 54);
        check(p.currentQuest == 54 && p.highestCompleted == 53, "set quest");
        engine.resetTo(p, 34);
        check(p.currentQuest == 34 && p.highestCompleted == 33 && p.progress == 0, "reset returns exact");
        check(p.rewardedQuests.contains(1) && p.rewardedShards.contains(1) && p.rewardedBonuses.contains(1), "reset preserves rewards");
        engine.setQuest(p, 101);
        check(p.finished() && p.highestCompleted == 100, "finish state");
        for (int id = 1; id <= 100; id++) {
            PlayerData one = new PlayerData(UUID.randomUUID(), "P" + id);
            engine.setQuest(one, id);
            QuestDefinition q = quests.get(id);
            ProgressOutcome result = engine.add(one, q, q.type(), sampleTarget(q), q.required(), System.currentTimeMillis());
            check(result.completed(), "every quest completable " + id);
            engine.advance(one, q, System.currentTimeMillis());
            check(one.currentQuest == id + 1, "every quest advances " + id);
        }
    }

    private static String sampleTarget(QuestDefinition quest) {
        return "WOODEN_TOOL".equals(quest.target()) ? "WOODEN_AXE" : quest.target();
    }

    private static void presentationTests(Map<Integer, QuestDefinition> quests) {
        QuestDefinition wooden = quests.get(3);
        check("WOODEN_TOOL".equals(wooden.target()), "wooden tool group configured");
        for (String tool : List.of("WOODEN_SWORD", "WOODEN_PICKAXE", "WOODEN_AXE", "WOODEN_SHOVEL", "WOODEN_HOE"))
            check(wooden.matches(tool), "wooden tool accepted " + tool);
        check(!wooden.matches("STONE_PICKAXE"), "non-wooden tool rejected");
        check(QuestText.instruction(wooden).contains("деревянные инструменты"), "clear wooden tool instruction");
        check(QuestText.targetHint(wooden).contains("меч") && QuestText.targetHint(wooden).contains("мотыга"), "wooden tool choices explained");
        check(QuestText.progressBar(0, 10).contains("■■■■■■■■■■"), "empty progress bar");
        check(QuestText.progressBar(5, 10).startsWith("&a■■■■■"), "half progress bar");
        check(QuestText.progressBar(10, 10).startsWith("&a■■■■■■■■■■"), "full progress bar");
        check(QuestText.formatDuration(65).equals("01:05"), "short duration formatting");
        check(QuestText.formatDuration(3661).equals("01:01:01"), "long duration formatting");
        for (QuestDefinition quest : quests.values()) {
            check(!QuestText.instruction(quest).isBlank(), "instruction visible " + quest.id());
            check(!QuestText.actionName(quest.type()).isBlank(), "action visible " + quest.id());
            check(!QuestText.targetHint(quest).isBlank(), "target hint visible " + quest.id());
            check(!QuestText.amount(quest).isBlank(), "amount visible " + quest.id());
        }
    }

    private static void persistenceTests() throws Exception {
        Path dir = Files.createTempDirectory("turnoquests-test-");
        FileStore store = new FileStore(dir);
        UUID id = UUID.randomUUID();
        PlayerData data = store.getOrCreate(id, "OfflineTester");
        data.currentQuest = 54; data.progress = 17; data.highestCompleted = 53; data.lifetimeHighest = 80; data.prestige = 2;
        data.rewardedQuests.addAll(List.of(1, 2, 10)); data.rewardedShards.addAll(List.of(1, 2, 10));
        data.rewardedBonuses.add(10); data.pendingMoney.addAll(List.of(10, -10)); data.pendingShards.add(10);
        data.shardsEarned = 7; data.claimedSecrets.add("miner-10000");
        store.save(data);
        PlayerData loaded = new FileStore(dir).find("OfflineTester").orElseThrow();
        check(loaded.uuid.equals(id), "offline UUID load");
        check(loaded.currentQuest == 54 && loaded.progress == 17, "progress load");
        check(loaded.highestCompleted == 53 && loaded.lifetimeHighest == 80, "milestones load");
        check(loaded.prestige == 2, "prestige load");
        check(loaded.rewardedQuests.size() == 3 && loaded.rewardedBonuses.contains(10), "reward ledger load");
        check(loaded.pendingMoney.contains(10) && loaded.pendingMoney.contains(-10), "pending money survives restart");
        check(loaded.pendingShards.contains(10) && loaded.shardsEarned == 7, "pending shards survive restart");
        check(loaded.claimedSecrets.contains("miner-10000"), "secrets load");
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
        check(roundtrip.toProperties().size() >= 29, "all state persisted");

        Properties legacy = new Properties();
        legacy.setProperty("uuid", UUID.randomUUID().toString());
        legacy.setProperty("lastName", "Legacy");
        legacy.setProperty("rewardedQuests", "1,2,10");
        legacy.setProperty("pendingMoney", "10,-10");
        legacy.setProperty("pendingItems", "1");
        PlayerData migrated = PlayerData.from(legacy);
        check(migrated.rewardedShards.containsAll(List.of(1, 2, 10)), "legacy completed rewards do not repay shards");
        check(migrated.pendingShards.equals(java.util.Set.of(10)), "legacy unclaimed reward gains shards");
        check(!migrated.toProperties().containsKey("pendingItems"), "legacy item queue removed");
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
        String listener = Files.readString(project.resolve("src/main/java/pro/turnoworld/quests/QuestListener.java"));
        String context = Files.readString(project.resolve("src/main/java/pro/turnoworld/quests/TrackerContext.java"));
        String workflow = Files.readString(project.resolve(".github/workflows/build.yml"));
        String pom = Files.readString(project.resolve("pom.xml"));
        String abiVerifier = Files.readString(project.resolve("build-support/AbiVerifier.java"));
        check(plugin.contains("api-version: '1.21'"), "api version");
        check(plugin.contains("version: 1.5.1"), "plugin version");
        check(gui.contains("Material.RED_DYE") && gui.contains("Material.LIME_DYE"), "red and green reward button states");
        check(gui.contains("claimQuestReward(player") && core.contains("public synchronized boolean claimQuestReward"), "manual reward click wired");
        check(core.contains("Откройте /quests и нажмите зелёную кнопку"), "completion explains manual claim");
        check(config.contains("glowing: true"), "npc visibility default");
        check(plugin.contains("turnoquests.admin.reset") && plugin.contains("turnoquests.admin.reward"), "permissions documented");
        check(plugin.contains("aliases: [tq]"), "tq alias");
        check(config.contains("quest-reward-multiplier: 1.0"), "economy values are explicit");
        check(config.contains("ignore-player-placed-blocks: true") && config.contains("ignore-spawner-and-egg-mobs: true"), "anti exploit defaults");
        check(config.contains("npc:") && config.contains("entity-type: VILLAGER"), "built-in npc defaults");
        check(!config.contains("bosses:") && !config.toLowerCase().contains("arena"), "no boss arenas");
        check(!plugin.contains("MythicMobs") && !plugin.contains("Citizens"), "no unrelated soft dependencies");
        check(plugin.contains("depend: [Vault, PlayerPoints]"), "Vault and PlayerPoints are required");
        check(plugin.contains("PlaceholderAPI") && plugin.contains("CombatLogX") && plugin.contains("TurnoEvents"), "context integrations load after dependencies");
        check(config.contains("playerpoints:") && config.contains("enabled: true"), "shards enabled");
        check(!plugin.contains("ExecutableItems") && !config.contains("executable-items:"), "item reward dependency removed");
        check(!quests.contains("reward:"), "quest file has no item rewards");
        check(gui.contains("quest.shards()") && gui.contains("Получено осколков"), "shards visible in GUI");
        check(core.contains("rewards.shardsAvailable()") && core.contains("quests-before-1.5.1.yml"), "shard validation and migration");
        check(quests.contains("season-version: 1.5.1"), "season balance version");
        String rewardService = Files.readString(project.resolve("src/main/java/pro/turnoworld/quests/RewardService.java"));
        check(rewardService.contains("getMethod(\"give\", java.util.UUID.class, int.class)"), "PlayerPoints UUID API integration");
        check(rewardService.contains("data.pendingShards") && rewardService.contains("data.shardsEarned"), "shard queue is durable");
        check(!quests.contains("MYTHIC_KILL") && !quests.toLowerCase().contains("boss") && !quests.toLowerCase().contains("босс"), "no boss quests");
        check(quests.contains("MINECRAFT:NETHER/ALL_EFFECTS") && quests.contains("MINECRAFT:END/RESPAWN_DRAGON"), "vanilla final objectives");
        check(quests.contains("target: WOODEN_TOOL") && quests.contains("Создайте любые деревянные инструменты"), "clear wooden tool quest");
        check(gui.contains("ЧТО НУЖНО СДЕЛАТЬ") && gui.contains("QuestText.progressBar") && !gui.contains("q.target()"), "clear quest report without raw enum target");
        check(core.contains("trackerActivityUntil") && core.contains("signalQuestAction") && core.contains("trackerContext.suppressed"), "contextual tracker lifecycle");
        check(listener.contains("BlockDamageEvent") && listener.contains("QuestType.KILL") && listener.contains("PlayerFishEvent.State.FISHING"), "tracker starts on real quest actions");
        check(listener.contains("instanceof Ageable crop") && listener.contains("crop.getMaximumAge()"), "only mature crops count");
        check(context.contains("%combatlogx_tag_count%") && context.contains("%turnoevents_joined%")
                && context.contains("Double.parseDouble") && context.contains("value.equals(\"true\")"), "pvp and event suppression");
        check(config.contains("active-seconds: 5") && config.contains("suppress-in-pvp: true") && config.contains("suppress-in-events: true"), "contextual tracker defaults");
        for (String sub : List.of("menu", "tracker", "prestige", "help", "reload", "validate", "backup", "top", "global", "npc",
                "info", "skip", "complete", "reset", "set", "progress", "reward", "history"))
            check(command.contains("\"" + sub + "\""), "command route " + sub);
        check(command.contains("catch (Throwable error)") && command.contains("Level.SEVERE"), "commands log complete failures");
        check(core.contains("catch (Throwable error)") && core.contains("disablePlugin(this)"), "startup failure is logged and disabled safely");
        check(npc.contains("spawnEntity") && npc.contains("catch (Throwable e)"), "npc spawn failure is fully logged");
        check(pom.contains("paper-api") && pom.contains("<version>1.5.1</version>"), "real Paper API build");
        check(pom.contains("org.ow2.asm") && workflow.contains("AbiVerifier"), "runtime ABI verification enabled");
        check(abiVerifier.contains("isPublicObjectMethod(signature)")
                && abiVerifier.contains("getClass()Ljava/lang/Class;"), "ABI verifier accepts Object methods on interfaces");
        check(workflow.contains("pull_request:") && workflow.contains("dependency:build-classpath"), "CI checks every pull request with dependencies");
        for (String forbidden : List.of("build-support/stubs", "jar-stage", "api-stub-classes"))
            check(!workflow.contains(forbidden), "release never builds with stubs: " + forbidden);
    }

    private static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError("FAILED: " + name + " (check " + checks + ")");
    }
}
