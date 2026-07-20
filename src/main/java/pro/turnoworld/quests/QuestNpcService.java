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

import java.util.Locale;

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
        Location source = player.getLocation();
        Location l = new Location(source.getWorld(), source.getBlockX() + 0.5, source.getBlockY(), source.getBlockZ() + 0.5, source.getYaw(), 0);
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
        Location location = configuredLocation();
        if (location == null) return false;
        location.getChunk().load();
        if (npc != null && npc.isValid() && !npc.isDead()) {
            npc.teleport(location);
            configure(npc, location);
            return true;
        }
        return create(location, true);
    }

    private boolean create(Location location, boolean retry) {
        EntityType type;
        try { type = EntityType.valueOf(plugin.getConfig().getString("npc.entity-type", "VILLAGER").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { type = EntityType.VILLAGER; }
        Entity created;
        try { created = location.getWorld().spawnEntity(location, type); }
        catch (RuntimeException e) {
            plugin.getLogger().warning("Не удалось создать квестового NPC: " + e.getMessage());
            return false;
        }
        if (!(created instanceof LivingEntity)) { created.remove(); return false; }
        npc = created;
        configure(created, location);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (created.isValid() && !created.isDead()) {
                created.teleport(location);
                configure(created, location);
                return;
            }
            if (npc == created) npc = null;
            if (retry) {
                plugin.getLogger().warning("NPC был удалён другим плагином сразу после создания. Выполняется повторная попытка.");
                create(location, false);
            }
        }, 10L);
        return created.isValid() && !created.isDead();
    }

    private void configure(Entity entity, Location location) {
        if (!(entity instanceof LivingEntity living)) return;
        entity.addScoreboardTag(TAG);
        entity.setCustomName(plugin.color(plugin.getConfig().getString("npc.name", "&6&lКвестовый проводник")));
        entity.setCustomNameVisible(true);
        entity.setInvulnerable(true);
        entity.setPersistent(true);
        entity.setGravity(true);
        entity.setGlowing(plugin.getConfig().getBoolean("npc.glowing", true));
        entity.setRotation(location.getYaw(), 0);
        living.setInvisible(false);
        living.setAI(false);
        living.setSilent(true);
        living.setCollidable(false);
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

    private Location configuredLocation() {
        World world = Bukkit.getWorld(plugin.getConfig().getString("npc.world", ""));
        if (world == null) return null;
        return new Location(world, plugin.getConfig().getDouble("npc.x"), plugin.getConfig().getDouble("npc.y"),
                plugin.getConfig().getDouble("npc.z"), (float) plugin.getConfig().getDouble("npc.yaw"), 0);
    }

    public String locationText() {
        Location l = npc != null && npc.isValid() ? npc.getLocation() : configuredLocation();
        if (l == null) return "не задана";
        return l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
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
