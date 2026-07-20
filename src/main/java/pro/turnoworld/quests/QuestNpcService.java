package pro.turnoworld.quests;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

public final class QuestNpcService implements Listener {
    private static final String TAG = "turnoquests_npc";
    private final TurnoQuests plugin;
    private Entity npc;

    public QuestNpcService(TurnoQuests plugin) { this.plugin = plugin; }

    public void restore() {
        removeDuplicates(false);
        if (plugin.getConfig().getBoolean("npc.enabled", true) && configured() && (npc == null || !npc.isValid())) spawn();
    }

    public boolean setLocation(Player player) {
        Location l = player.getLocation();
        plugin.getConfig().set("npc.world", l.getWorld().getName());
        plugin.getConfig().set("npc.x", l.getX());
        plugin.getConfig().set("npc.y", l.getY());
        plugin.getConfig().set("npc.z", l.getZ());
        plugin.getConfig().set("npc.yaw", l.getYaw());
        plugin.getConfig().set("npc.pitch", l.getPitch());
        plugin.getConfig().set("npc.enabled", true);
        plugin.saveConfig();
        remove();
        return spawn();
    }

    public boolean spawn() {
        if (!configured()) return false;
        if (npc != null && npc.isValid()) return true;
        World world = Bukkit.getWorld(plugin.getConfig().getString("npc.world", ""));
        if (world == null) return false;
        Location location = new Location(world, plugin.getConfig().getDouble("npc.x"), plugin.getConfig().getDouble("npc.y"),
                plugin.getConfig().getDouble("npc.z"), (float) plugin.getConfig().getDouble("npc.yaw"), (float) plugin.getConfig().getDouble("npc.pitch"));
        EntityType type;
        try { type = EntityType.valueOf(plugin.getConfig().getString("npc.entity-type", "VILLAGER").toUpperCase()); }
        catch (IllegalArgumentException e) { type = EntityType.VILLAGER; }
        npc = world.spawnEntity(location, type);
        if (!(npc instanceof LivingEntity living)) { npc.remove(); npc = null; return false; }
        npc.addScoreboardTag(TAG);
        npc.setCustomName(plugin.color(plugin.getConfig().getString("npc.name", "&6&lКвестовый проводник")));
        npc.setCustomNameVisible(true);
        npc.setInvulnerable(true);
        npc.setPersistent(true);
        npc.setGravity(false);
        living.setAI(false);
        living.setSilent(true);
        living.setCollidable(false);
        return true;
    }

    public void remove() {
        if (npc != null && npc.isValid()) npc.remove();
        npc = null;
        removeDuplicates(true);
    }

    private void removeDuplicates(boolean all) {
        Entity keep = null;
        for (World world : Bukkit.getWorlds()) for (Entity entity : world.getEntities()) {
            if (!entity.getScoreboardTags().contains(TAG)) continue;
            if (!all && keep == null) { keep = entity; continue; }
            entity.remove();
        }
        if (!all) npc = keep;
    }

    private boolean configured() {
        String world = plugin.getConfig().getString("npc.world", "");
        return world != null && !world.isBlank();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!event.getRightClicked().getScoreboardTags().contains(TAG)) return;
        event.setCancelled(true);
        plugin.open(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity().getScoreboardTags().contains(TAG)) event.setCancelled(true);
    }
}
