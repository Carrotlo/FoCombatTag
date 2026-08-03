package me.foesio.foCombatTag.listener;

import me.foesio.foCombatTag.FoCombatTag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CombatListener implements Listener {
    private static final long BLOCK_EXPLOSION_WINDOW_MILLIS = 3000L;
    private static final double BLOCK_EXPLOSION_RADIUS_SQUARED = 144.0D;

    private final FoCombatTag plugin;
    private final Map<BlockTriggerKey, BlockTrigger> recentBlockTriggers = new ConcurrentHashMap<>();

    public CombatListener(FoCombatTag plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplosiveBlockInteract(PlayerInteractEvent event) {
        if (!plugin.settings().tagPlayerExplosions()) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getClickedBlock() == null || !isTrackedExplosiveBlock(event.getClickedBlock().getType())) {
            return;
        }
        if (!canExplodeByUse(event.getClickedBlock().getType(), event.getPlayer().getWorld())) {
            return;
        }
        long now = System.currentTimeMillis();
        BlockTriggerKey key = BlockTriggerKey.from(event.getClickedBlock().getLocation());
        recentBlockTriggers.put(key, new BlockTrigger(event.getPlayer().getUniqueId(), now, key));
        cleanupBlockTriggers(now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (event.getDamage() <= 0.0D) {
            return;
        }
        if (isExplosionDamage(event) && !plugin.settings().tagPlayerExplosions()) {
            return;
        }
        Player attacker = resolveAttacker(event);
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        plugin.combatTags().tagCombat(attacker, victim);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (event.getDamage() <= 0.0D) {
            return;
        }
        if (!isExplosionDamage(event)) {
            return;
        }
        if (!plugin.settings().tagPlayerExplosions()) {
            return;
        }
        Player attacker = null;
        Entity causing = event.getDamageSource().getCausingEntity();
        if (causing instanceof Player player) {
            attacker = player;
        }
        if (attacker == null) {
            attacker = resolveRecentBlockAttacker(victim);
        }
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        plugin.combatTags().tagCombat(attacker, victim);
    }

    private Player resolveAttacker(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) {
            return player;
        }
        Entity causing = event.getDamageSource().getCausingEntity();
        if (causing instanceof Player player) {
            return player;
        }
        return null;
    }

    private Player resolveRecentBlockAttacker(Player victim) {
        long now = System.currentTimeMillis();
        cleanupBlockTriggers(now);
        Location victimLocation = victim.getLocation();
        UUID worldId = victimLocation.getWorld().getUID();
        BlockTrigger best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockTrigger trigger : recentBlockTriggers.values()) {
            if (!trigger.key().worldId().equals(worldId)) {
                continue;
            }
            double distance = trigger.key().distanceSquaredTo(victimLocation);
            if (distance > BLOCK_EXPLOSION_RADIUS_SQUARED) {
                continue;
            }
            if (best == null || trigger.createdAtMillis() > best.createdAtMillis()
                    || (trigger.createdAtMillis() == best.createdAtMillis() && distance < bestDistance)) {
                best = trigger;
                bestDistance = distance;
            }
        }
        return best == null ? null : Bukkit.getPlayer(best.playerId());
    }

    private boolean isExplosionDamage(EntityDamageEvent event) {
        return event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION;
    }

    private boolean isTrackedExplosiveBlock(Material material) {
        return material == Material.RESPAWN_ANCHOR || material.name().endsWith("_BED");
    }

    private boolean canExplodeByUse(Material material, World world) {
        if (world == null) {
            return false;
        }
        if (material == Material.RESPAWN_ANCHOR) {
            return world.getEnvironment() != World.Environment.NETHER;
        }
        return material.name().endsWith("_BED") && world.getEnvironment() != World.Environment.NORMAL;
    }

    private void cleanupBlockTriggers(long now) {
        recentBlockTriggers.entrySet().removeIf(entry -> now - entry.getValue().createdAtMillis() > BLOCK_EXPLOSION_WINDOW_MILLIS);
    }

    private record BlockTriggerKey(UUID worldId, int x, int y, int z) {
        private static BlockTriggerKey from(Location location) {
            return new BlockTriggerKey(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }

        private double distanceSquaredTo(Location location) {
            double centerX = x + 0.5D;
            double centerY = y + 0.5D;
            double centerZ = z + 0.5D;
            return location.distanceSquared(new Location(location.getWorld(), centerX, centerY, centerZ));
        }
    }

    private record BlockTrigger(UUID playerId, long createdAtMillis, BlockTriggerKey key) {
    }
}
