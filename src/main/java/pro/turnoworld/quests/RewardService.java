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
import java.util.function.Consumer;

public final class RewardService {
    private final TurnoQuests plugin;
    private Economy economy;

    public RewardService(TurnoQuests plugin) {
        this.plugin = plugin;
        hookEconomy();
    }

    public void hookEconomy() {
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        economy = registration == null ? null : registration.getProvider();
    }

    public boolean economyAvailable() { return economy != null; }

    public void queueQuest(PlayerData data, QuestDefinition quest, boolean timedBonus, boolean noDeathBonus) {
        if (data.rewardedQuests.add(quest.id())) {
            data.pendingMoney.add(quest.id());
        }
        if (quest.id() % 10 == 0 && data.rewardedChapters.add(quest.chapter())) {
            data.pendingItems.add(quest.chapter());
        }
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
        if (online != null) {
            for (Integer chapter : new ArrayList<>(data.pendingItems)) {
                if (online.getInventory().firstEmpty() < 0) {
                    messages.add("&eОсвободите слот для предмета главы " + chapter);
                    break;
                }
                String item = catalog.chapterReward(chapter);
                if (item.isBlank()) { data.pendingItems.remove(chapter); continue; }
                String command = plugin.getConfig().getString("executable-items.give-command", "ei give %player% %item% 1")
                        .replace("%player%", online.getName()).replace("%item%", item);
                if (Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                    data.pendingItems.remove(chapter);
                    messages.add("&aПолучен предмет: &e" + item);
                } else messages.add("&cНе удалось выполнить команду выдачи предмета " + item);
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

        if (online != null && questId % 10 == 0 && data.pendingItems.contains(quest.chapter())) {
            if (online.getInventory().firstEmpty() < 0) {
                messages.add("&eОсвободите один слот для особого предмета главы " + quest.chapter());
            } else {
                String item = catalog.chapterReward(quest.chapter());
                if (item.isBlank()) {
                    data.pendingItems.remove(quest.chapter());
                } else {
                    String command = plugin.getConfig().getString("executable-items.give-command", "ei give %player% %item% 1")
                            .replace("%player%", online.getName()).replace("%item%", item);
                    if (Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                        data.pendingItems.remove(quest.chapter());
                        messages.add("&aПолучен особый предмет: &e" + item);
                    } else messages.add("&cНе удалось выдать предмет " + item + ". Награда сохранена.");
                }
            }
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
        return quest.id() % 10 == 0 && data.pendingItems.contains(quest.chapter());
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        if (economy == null) hookEconomy();
        if (economy == null || amount <= 0) return false;
        EconomyResponse response = economy.depositPlayer(player, amount);
        return response != null && response.transactionSuccess();
    }

    public String format(double value) {
        return economy == null ? String.format(Locale.ROOT, "%,.0f", value) : economy.format(value);
    }

    public double questAmount(double configured) {
        return configured * Math.max(0, plugin.getConfig().getDouble("economy.quest-reward-multiplier", 0.4));
    }
}
