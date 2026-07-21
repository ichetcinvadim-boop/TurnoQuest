package pro.turnoworld.quests;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class PlayerData {
    public final UUID uuid;
    public String lastName;
    public int currentQuest = 1;
    public long progress;
    public int highestCompleted;
    public int lifetimeHighest;
    public long questStartedAt;
    public boolean diedDuringQuest;
    public boolean tracker = true;
    public final Set<Integer> rewardedQuests = new HashSet<>();
    public final Set<Integer> rewardedChapters = new HashSet<>();
    public final Set<Integer> rewardedBonuses = new HashSet<>();
    public final Set<Integer> pendingMoney = new HashSet<>();
    public final Set<Integer> pendingItems = new HashSet<>();
    public final Set<String> claimedSecrets = new HashSet<>();
    public String dailyKey = "";
    public long dailyProgress;
    public boolean dailyClaimed;
    public String weeklyKey = "";
    public long weeklyProgress;
    public boolean weeklyClaimed;
    public long blocksBroken;
    public long mobsKilled;
    public long distanceWalked;
    public long playTicks;
    public long fishCaught;
    public long moneyEarned;
    public long lastSeen;
    public int prestige;

    public PlayerData(UUID uuid, String lastName) {
        this.uuid = uuid;
        this.lastName = lastName == null ? "unknown" : lastName;
        this.questStartedAt = System.currentTimeMillis();
    }

    public boolean finished() { return currentQuest > 100; }

    public boolean rewardReady(QuestDefinition quest) {
        return quest != null && currentQuest == quest.id() && progress >= quest.required();
    }

    public Properties toProperties() {
        Properties p = new Properties();
        p.setProperty("uuid", uuid.toString());
        p.setProperty("lastName", lastName);
        p.setProperty("currentQuest", String.valueOf(currentQuest));
        p.setProperty("progress", String.valueOf(progress));
        p.setProperty("highestCompleted", String.valueOf(highestCompleted));
        p.setProperty("lifetimeHighest", String.valueOf(lifetimeHighest));
        p.setProperty("questStartedAt", String.valueOf(questStartedAt));
        p.setProperty("diedDuringQuest", String.valueOf(diedDuringQuest));
        p.setProperty("tracker", String.valueOf(tracker));
        p.setProperty("rewardedQuests", ints(rewardedQuests));
        p.setProperty("rewardedChapters", ints(rewardedChapters));
        p.setProperty("rewardedBonuses", ints(rewardedBonuses));
        p.setProperty("pendingMoney", ints(pendingMoney));
        p.setProperty("pendingItems", ints(pendingItems));
        p.setProperty("claimedSecrets", String.join(",", claimedSecrets));
        p.setProperty("dailyKey", dailyKey);
        p.setProperty("dailyProgress", String.valueOf(dailyProgress));
        p.setProperty("dailyClaimed", String.valueOf(dailyClaimed));
        p.setProperty("weeklyKey", weeklyKey);
        p.setProperty("weeklyProgress", String.valueOf(weeklyProgress));
        p.setProperty("weeklyClaimed", String.valueOf(weeklyClaimed));
        p.setProperty("blocksBroken", String.valueOf(blocksBroken));
        p.setProperty("mobsKilled", String.valueOf(mobsKilled));
        p.setProperty("distanceWalked", String.valueOf(distanceWalked));
        p.setProperty("playTicks", String.valueOf(playTicks));
        p.setProperty("fishCaught", String.valueOf(fishCaught));
        p.setProperty("moneyEarned", String.valueOf(moneyEarned));
        p.setProperty("lastSeen", String.valueOf(lastSeen));
        p.setProperty("prestige", String.valueOf(prestige));
        return p;
    }

    public static PlayerData from(Properties p) {
        PlayerData d = new PlayerData(UUID.fromString(p.getProperty("uuid")), p.getProperty("lastName", "unknown"));
        d.currentQuest = integer(p, "currentQuest", 1);
        d.progress = number(p, "progress", 0);
        d.highestCompleted = integer(p, "highestCompleted", Math.max(0, d.currentQuest - 1));
        d.lifetimeHighest = integer(p, "lifetimeHighest", d.highestCompleted);
        d.questStartedAt = number(p, "questStartedAt", System.currentTimeMillis());
        d.diedDuringQuest = bool(p, "diedDuringQuest", false);
        d.tracker = bool(p, "tracker", true);
        parseInts(p.getProperty("rewardedQuests", ""), d.rewardedQuests);
        parseInts(p.getProperty("rewardedChapters", ""), d.rewardedChapters);
        parseInts(p.getProperty("rewardedBonuses", ""), d.rewardedBonuses);
        parseInts(p.getProperty("pendingMoney", ""), d.pendingMoney);
        parseInts(p.getProperty("pendingItems", ""), d.pendingItems);
        if (!p.getProperty("claimedSecrets", "").isBlank()) d.claimedSecrets.addAll(Arrays.asList(p.getProperty("claimedSecrets").split(",")));
        d.dailyKey = p.getProperty("dailyKey", "");
        d.dailyProgress = number(p, "dailyProgress", 0);
        d.dailyClaimed = bool(p, "dailyClaimed", false);
        d.weeklyKey = p.getProperty("weeklyKey", "");
        d.weeklyProgress = number(p, "weeklyProgress", 0);
        d.weeklyClaimed = bool(p, "weeklyClaimed", false);
        d.blocksBroken = number(p, "blocksBroken", 0);
        d.mobsKilled = number(p, "mobsKilled", 0);
        d.distanceWalked = number(p, "distanceWalked", 0);
        d.playTicks = number(p, "playTicks", 0);
        d.fishCaught = number(p, "fishCaught", 0);
        d.moneyEarned = number(p, "moneyEarned", 0);
        d.lastSeen = number(p, "lastSeen", 0);
        d.prestige = integer(p, "prestige", 0);
        return d;
    }

    private static String ints(Set<Integer> values) {
        return values.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }
    private static void parseInts(String value, Set<Integer> target) {
        if (value == null || value.isBlank()) return;
        for (String part : value.split(",")) try { target.add(Integer.parseInt(part.trim())); } catch (NumberFormatException ignored) { }
    }
    private static int integer(Properties p, String key, int fallback) { try { return Integer.parseInt(p.getProperty(key)); } catch (Exception e) { return fallback; } }
    private static long number(Properties p, String key, long fallback) { try { return Long.parseLong(p.getProperty(key)); } catch (Exception e) { return fallback; } }
    private static boolean bool(Properties p, String key, boolean fallback) { String v = p.getProperty(key); return v == null ? fallback : Boolean.parseBoolean(v); }
}
