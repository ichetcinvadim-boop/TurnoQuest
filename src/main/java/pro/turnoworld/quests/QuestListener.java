package pro.turnoworld.quests;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class QuestListener implements Listener {
    private static final Set<Material> FARM_BLOCKS = Set.of(Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS, Material.NETHER_WART, Material.COCOA);
    private final TurnoQuests plugin;
    private final Map<UUID, Double> walkRemainder = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastBiomeCheck = new ConcurrentHashMap<>();

    public QuestListener(TurnoQuests plugin) { this.plugin = plugin; }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        plugin.data(event.getPlayer());
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> { plugin.checkCurrentState(event.getPlayer()); plugin.processPending(event.getPlayer()); }, 40L);
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        PlayerData data = plugin.data(event.getPlayer());
        data.lastSeen = System.currentTimeMillis();
        plugin.trySave(data);
        walkRemainder.remove(event.getPlayer().getUniqueId());
    }
    @EventHandler public void onDeath(PlayerDeathEvent event) { plugin.markDeath(event.getEntity()); }
    @EventHandler(priority = EventPriority.HIGHEST) public void onInventory(InventoryClickEvent event) { plugin.gui().click(event); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (plugin.getConfig().getBoolean("anti-exploit.ignore-player-placed-blocks", true) && plugin.antiExploit().removePlaced(event.getBlock().getLocation())) return;
        plugin.record(event.getPlayer(), QuestType.BREAK, event.getBlock().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (plugin.getConfig().getBoolean("anti-exploit.ignore-player-placed-blocks", true) && !FARM_BLOCKS.contains(event.getBlockPlaced().getType()))
            plugin.antiExploit().placed(event.getBlockPlaced().getLocation());
        plugin.record(event.getPlayer(), QuestType.PLACE, event.getBlockPlaced().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!plugin.getConfig().getBoolean("anti-exploit.ignore-spawner-and-egg-mobs", true)) return;
        switch (event.getSpawnReason()) {
            case SPAWNER, SPAWNER_EGG, EGG, DISPENSE_EGG -> plugin.antiExploit().artificial(event.getEntity().getUniqueId());
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        UUID id = entity.getUniqueId();
        boolean artificial = plugin.antiExploit().isArtificial(id);
        plugin.antiExploit().forgetMob(id);
        if (artificial) return;
        Player killer = entity.getKiller();
        if (killer != null) plugin.record(killer, QuestType.KILL, entity.getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = playerDamager(event.getDamager());
        if (attacker != null && event.getEntity() instanceof LivingEntity) {
            long damage = Math.max(1, Math.round(event.getFinalDamage()));
            plugin.record(attacker, QuestType.DAMAGE, event.getEntityType().name(), damage);
        }
        if (event.getEntity() instanceof Player victim && victim.isBlocking()) {
            plugin.record(victim, QuestType.BLOCK_DAMAGE, "ANY", Math.max(1, Math.round(event.getDamage())));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (event.getWhoClicked() instanceof Player player) plugin.record(player, QuestType.CRAFT,
                event.getRecipe().getResult().getType().name(), Math.max(1, event.getRecipe().getResult().getAmount()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmelt(FurnaceExtractEvent event) { plugin.record(event.getPlayer(), QuestType.SMELT, event.getItemType().name(), event.getItemAmount()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        String type = event.getCaught() instanceof Item item ? item.getItemStack().getType().name() : "ANY";
        plugin.record(event.getPlayer(), QuestType.FISH, type, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        LivingEntity breeder = event.getBreeder();
        if (breeder instanceof Player player) plugin.record(player, QuestType.BREED, event.getEntityType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent event) {
        AnimalTamer owner = event.getOwner();
        if (owner instanceof Player player) plugin.record(player, QuestType.TAME, event.getEntityType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) { plugin.record(event.getPlayer(), QuestType.SHEAR, event.getEntity().getType().name(), 1); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) { plugin.record(event.getPlayer(), QuestType.CONSUME, event.getItem().getType().name(), 1); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) { plugin.record(event.getEnchanter(), QuestType.ENCHANT, event.getItem().getType().name(), 1); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBed(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.OK) plugin.record(event.getPlayer(), QuestType.SLEEP, "ANY", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorld(PlayerChangedWorldEvent event) { plugin.record(event.getPlayer(), QuestType.VISIT_WORLD, event.getPlayer().getWorld().getEnvironment().name(), 1); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAdvancement(PlayerAdvancementDoneEvent event) { plugin.record(event.getPlayer(), QuestType.ADVANCEMENT, event.getAdvancement().getKey().toString(), 1); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;
        double distance = from.distance(to);
        if (distance > 0 && distance < 20) {
            double total = walkRemainder.getOrDefault(event.getPlayer().getUniqueId(), 0D) + distance;
            long whole = (long) total;
            walkRemainder.put(event.getPlayer().getUniqueId(), total - whole);
            if (whole > 0) plugin.record(event.getPlayer(), QuestType.WALK, "ANY", whole);
        }
        long now = System.currentTimeMillis();
        long last = lastBiomeCheck.getOrDefault(event.getPlayer().getUniqueId(), 0L);
        if (now - last >= 1000) {
            lastBiomeCheck.put(event.getPlayer().getUniqueId(), now);
            plugin.record(event.getPlayer(), QuestType.VISIT_BIOME, to.getBlock().getBiome().getKey().getKey(), 1);
        }
    }

    private Player playerDamager(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }
}
