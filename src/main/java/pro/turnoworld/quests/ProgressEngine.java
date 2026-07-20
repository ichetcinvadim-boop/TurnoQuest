package pro.turnoworld.quests;

public final class ProgressEngine {
    public synchronized ProgressOutcome add(PlayerData player, QuestDefinition quest, QuestType type,
                                            String target, long amount, long now) {
        if (player.finished() || player.currentQuest != quest.id() || quest.type() != type || amount <= 0 || !quest.matches(target)) {
            return ProgressOutcome.none(player.progress);
        }
        long before = player.progress;
        player.progress = Math.min(quest.required(), player.progress + amount);
        boolean complete = before < quest.required() && player.progress >= quest.required();
        boolean timed = complete && quest.timed() && now - player.questStartedAt <= quest.timeLimitSeconds() * 1000L;
        boolean noDeath = complete && quest.noDeathBonus() && !player.diedDuringQuest;
        return new ProgressOutcome(player.progress != before, complete, player.progress - before, player.progress, timed, noDeath);
    }

    public synchronized void advance(PlayerData player, QuestDefinition quest, long now) {
        if (player.currentQuest != quest.id() || player.progress < quest.required()) return;
        player.highestCompleted = Math.max(player.highestCompleted, quest.id());
        player.lifetimeHighest = Math.max(player.lifetimeHighest, quest.id());
        player.currentQuest = quest.id() + 1;
        player.progress = 0;
        player.questStartedAt = now;
        player.diedDuringQuest = false;
    }

    public synchronized void setQuest(PlayerData player, int quest) {
        int normalized = Math.max(1, Math.min(101, quest));
        player.currentQuest = normalized;
        player.highestCompleted = normalized - 1;
        player.lifetimeHighest = Math.max(player.lifetimeHighest, normalized - 1);
        player.progress = 0;
        player.questStartedAt = System.currentTimeMillis();
        player.diedDuringQuest = false;
    }

    public synchronized void resetTo(PlayerData player, int quest) {
        int normalized = Math.max(1, Math.min(100, quest));
        player.currentQuest = normalized;
        player.highestCompleted = normalized - 1;
        player.progress = 0;
        player.questStartedAt = System.currentTimeMillis();
        player.diedDuringQuest = false;
        // Reward ledgers intentionally remain intact: resets never duplicate rewards.
    }
}
