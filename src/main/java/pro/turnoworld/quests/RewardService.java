package pro.turnoworld.quests;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.lang.reflect.Method;

public final class RewardService {
    private final TurnoQuests plugin;
    private Economy economy;
    private Object playerPointsApi;
    private Method givePoints;

    public RewardService(TurnoQuests plugin) {
        this.plugin = plugin;
        hookEconomy();
        hookPlayerPoints();
    }

    public void hookEconomy() {
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        economy = registration == null ? null : registration.getProvider();
    }

    public boolean economyAvailable() { return economy != null; }
    public boolean shardsAvailable() {
        if (!plugin.getConfig().getBoolean("playerpoints.enabled", true)) return false;
        if (playerPointsApi == null || givePoints == null) hookPlayerPoints();
        return playerPointsApi != null && givePoints != null;
    }

    public void hookPlayerPoints() {
        playerPointsApi = null;
        givePoints = null;
        if (!plugin.getConfig().getBoolean("playerpoints.enabled", true)) return;
        try {
            var pointsPlugin = Bukkit.getPluginManager().getPlugin("PlayerPoints");
            if (pointsPlugin == null || !pointsPlugin.isEnabled()) return;
            playerPointsApi = pointsPlugin.getClass().getMethod("getAPI").invoke(pointsPlugin);
            givePoints = playerPointsApi.getClass().getMethod("give", java.util.UUID.class, int.class);
        } catch (ReflectiveOperationException error) {
            plugin.getLogger().warning("PlayerPoints API недоступен: " + error.getClass().getSimpleName());
            playerPointsApi = null;
            givePoints = null;
        }
    }

    public void queueQuest(PlayerData data, QuestDefinition quest, boolean timedBonus, boolean noDeathBonus) {
        if (data.rewardedQuests.add(quest.id())) {
            data.pendingMoney.add(quest.id());
        }
        if (quest.shards() > 0 && data.rewardedShards.add(quest.id())) data.pendingShards.add(quest.id());
        // Bonus is paid with the main reward. The exact earned bonus is encoded as a
        // negative synthetic id, so a restart cannot duplicate it.
        if ((timedBonus || noDeathBonus) && quest.bonusMoney() > 0 && data.rewardedBonuses.add(quest.id())) data.pendingMoney.add(-quest.id());
    }

    public List<String> claim(PlayerData data, Player online, QuestCatalog catalog) {
        List<String> messages = new ArrayList<>();
        OfflinePlayer account = Bukkit.getOfflinePlayer(data.uuid);
        for (Integer id : new ArrayList<>(data.pendingMoney)) {
            QuestDefinition q = catalog.get(Math.abs(id));
            if (q == null) { data.pendingMoney.remove(id); continue; }
            double amount = questAmount(id < 0 ? q.bonusMoney() : q.money());
            if (amount <= 0) { data.pendingMoney.remove(id); continue; }
            if (deposit(account, amount)) {
                data.pendingMoney.remove(id);
                data.moneyEarned += Math.round(amount);
                messages.add("&a+" + format(amount) + " монет");
            }
        }
        for (Integer id : new ArrayList<>(data.pendingShards)) {
            QuestDefinition q = catalog.get(id);
            if (q == null || q.shards() <= 0) { data.pendingShards.remove(id); continue; }
            if (giveShards(data.uuid, q.shards())) {
                data.pendingShards.remove(id);
                data.shardsEarned += q.shards();
                messages.add("&d+" + q.shards() + " осколков");
            }
        }
        return messages;
    }

    public List<String> claimQuest(PlayerData data, Player online, QuestCatalog catalog, int questId) {
        List<String> messages = new ArrayList<>();
        QuestDefinition quest = catalog.get(questId);
        if (quest == null) return messages;
        OfflinePlayer account = Bukkit.getOfflinePlayer(data.uuid);

        claimMoneyEntry(data, account, quest, questId, quest.money(), messages);
        claimMoneyEntry(data, account, quest, -questId, quest.bonusMoney(), messages);
        if (data.pendingShards.contains(questId)) {
            if (giveShards(data.uuid, quest.shards())) {
                data.pendingShards.remove(questId);
                data.shardsEarned += quest.shards();
                messages.add("&d+" + quest.shards() + " осколков");
            } else messages.add("&cНе удалось выдать " + quest.shards() + " осколков PlayerPoints. Награда сохранена.");
        }
        return messages;
    }

    private void claimMoneyEntry(PlayerData data, OfflinePlayer account, QuestDefinition quest, int entry,
                                 double configured, List<String> messages) {
        if (!data.pendingMoney.contains(entry)) return;
        double amount = questAmount(configured);
        if (amount <= 0) {
            data.pendingMoney.remove(entry);
            return;
        }
        if (deposit(account, amount)) {
            data.pendingMoney.remove(entry);
            data.moneyEarned += Math.round(amount);
            messages.add("&a+" + format(amount) + " монет");
        } else messages.add("&cНе удалось выдать " + format(amount) + " монет. Награда сохранена.");
    }

    public boolean hasPendingForQuest(PlayerData data, QuestDefinition quest) {
        if (quest == null) return false;
        if (data.pendingMoney.contains(quest.id()) || data.pendingMoney.contains(-quest.id())) return true;
        return data.pendingShards.contains(quest.id());
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        if (economy == null) hookEconomy();
        if (economy == null || amount <= 0) return false;
        EconomyResponse response = economy.depositPlayer(player, amount);
        return response != null && response.transactionSuccess();
    }

    public boolean giveShards(java.util.UUID playerId, int amount) {
        if (amount <= 0) return true;
        if (!shardsAvailable()) return false;
        try {
            return Boolean.TRUE.equals(givePoints.invoke(playerPointsApi, playerId, amount));
        } catch (ReflectiveOperationException | RuntimeException error) {
            plugin.getLogger().warning("Не удалось выдать осколки PlayerPoints игроку " + playerId + ": " + error.getClass().getSimpleName());
            return false;
        }
    }

    public String format(double value) {
        return economy == null ? String.format(Locale.ROOT, "%,.0f", value) : economy.format(value);
    }

    public double questAmount(double configured) {
        return configured * Math.max(0, plugin.getConfig().getDouble("economy.quest-reward-multiplier", 1.0));
    }
}
