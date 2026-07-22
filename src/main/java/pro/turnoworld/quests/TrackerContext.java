package pro.turnoworld.quests;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Optional bridges used only to avoid covering PvP and event displays. */
final class TrackerContext {
    private final TurnoQuests plugin;
    private final Set<String> loggedFailures = new HashSet<>();
    private boolean placeholderMethodResolved;
    private Method placeholderMethod;

    TrackerContext(TurnoQuests plugin) { this.plugin = plugin; }

    boolean suppressed(Player player) {
        if (plugin.getConfig().getBoolean("tracker.suppress-in-pvp", true)
                && positive(resolve(player, plugin.getConfig().getString("tracker.pvp-placeholder", "%combatlogx_tag_count%")))) return true;
        return plugin.getConfig().getBoolean("tracker.suppress-in-events", true) && inTurnoEvent(player);
    }

    private boolean inTurnoEvent(Player player) {
        String placeholder = plugin.getConfig().getString("tracker.event-placeholder", "%turnoevents_joined%");
        String value = resolve(player, placeholder);
        if (positive(value)) return true;
        if (value != null && !value.equals(placeholder)) return false;

        // TurnoEvents can still be queried when PlaceholderAPI is absent.
        Plugin events = plugin.getServer().getPluginManager().getPlugin("TurnoEvents");
        if (events == null || !events.isEnabled()) return false;
        try {
            Object manager = events.getClass().getMethod("manager").invoke(events);
            Object session = manager.getClass().getMethod("session").invoke(manager);
            if (session == null) return false;
            return Boolean.TRUE.equals(session.getClass().getMethod("joined", UUID.class).invoke(session, player.getUniqueId()));
        } catch (ReflectiveOperationException error) {
            logOnce("TurnoEvents", "Не удалось проверить участие игрока в TurnoEvents", error);
            return false;
        }
    }

    private String resolve(Player player, String placeholder) {
        if (placeholder == null || placeholder.isBlank()) return "";
        Plugin papi = plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI");
        if (papi == null || !papi.isEnabled()) return placeholder;
        try {
            if (!placeholderMethodResolved) {
                placeholderMethodResolved = true;
                placeholderMethod = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                        .getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            }
            if (placeholderMethod == null) return placeholder;
            Object result = placeholderMethod.invoke(null, player, placeholder);
            return result == null ? "" : result.toString();
        } catch (ReflectiveOperationException error) {
            logOnce("PlaceholderAPI", "Не удалось прочитать контекстные плейсхолдеры трекера", error);
            return placeholder;
        }
    }

    static boolean positive(String raw) {
        if (raw == null) return false;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("true") || value.equals("yes") || value.equals("да")) return true;
        try { return Double.parseDouble(value.replace(',', '.')) > 0; }
        catch (NumberFormatException ignored) { return false; }
    }

    private void logOnce(String key, String message, Exception error) {
        if (loggedFailures.add(key)) plugin.getLogger().warning(message + ": " + error.getClass().getSimpleName());
    }
}
