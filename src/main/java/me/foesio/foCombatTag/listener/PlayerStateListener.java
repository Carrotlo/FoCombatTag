package me.foesio.foCombatTag.listener;

import me.foesio.core.command.CommandPlaceholders;
import me.foesio.core.item.FoItemStacks;
import me.foesio.foCombatTag.FoCombatTag;
import me.foesio.foCombatTag.api.CombatTagRemoveReason;
import me.foesio.foCombatTag.api.event.CombatTagRemoveEvent;
import me.foesio.foCombatTag.service.BorderService.BorderPush;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.entity.EnderPearl;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerStateListener implements Listener {
    private static final int PEARL_GUARD_MAX_TICKS = 20 * 20;

    private final FoCombatTag plugin;
    private final Map<UUID, Long> pearlCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, TrackedPearl> trackedPearls = new ConcurrentHashMap<>();
    private final Set<UUID> queuedBorderRenders = ConcurrentHashMap.newKeySet();
    private BukkitTask pearlGuardTask;

    public PlayerStateListener(FoCombatTag plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) {
            return;
        }
        if (event.getTo() == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!plugin.combatTags().isTagged(player)) {
            return;
        }
        if (!borderGuardEnabled(player)) {
            return;
        }
        if (samePosition(event.getFrom(), event.getTo())) {
            return;
        }
        boolean insideEnclosedRegion = plugin.borderService().isEnclosedRegion(event.getFrom());
        BorderPush enclosurePush = plugin.borderService().enclosedRegionEscapePush(event.getFrom(), event.getTo(), insideEnclosedRegion);
        if (enclosurePush != null) {
            event.setTo(event.getFrom());
            plugin.borderService().pushBackFromEnclosedExit(player, enclosurePush.direction());
            renderNextTick(player);
            return;
        }
        if (!insideEnclosedRegion && plugin.borderService().shouldCheckBorderPush(player, event.getTo(), false)) {
            BorderPush borderPush = plugin.borderService().borderPush(player, event.getTo(), false);
            if (borderPush != null) {
                plugin.borderService().pushBackOnce(player, borderPush.direction());
                renderNextTick(player);
                return;
            }
        }
        if (plugin.settings().worldGuardBorderEnabled() && !insideEnclosedRegion) {
            BorderPush escapePush = plugin.borderService().noPvpEscapePush(event.getTo());
            if (escapePush != null) {
                if (!plugin.borderService().isNoPvp(event.getFrom())) {
                    event.setTo(event.getFrom());
                }
                plugin.borderService().pushBackOnce(player, escapePush.direction());
                renderNextTick(player);
                return;
            }
            if (plugin.borderService().isNoPvp(event.getTo())) {
                Location blocked = event.getTo().clone();
                event.setTo(event.getFrom());
                plugin.borderService().pushBackOnce(player, event.getFrom().toVector().subtract(blocked.toVector()));
                renderNextTick(player);
                return;
            }
        }
        if (!sameBlock(event.getFrom(), event.getTo())) {
            renderNextTick(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!plugin.combatTags().isTagged(player)) {
            return;
        }
        if (!borderGuardEnabled(player) || event.getTo() == null) {
            return;
        }
        boolean insideEnclosedRegion = plugin.borderService().isEnclosedRegion(event.getFrom());
        BorderPush enclosurePush = plugin.borderService().enclosedRegionEscapePush(event.getFrom(), event.getTo(), insideEnclosedRegion);
        if (enclosurePush != null) {
            cancelEnclosedRegionTeleport(event, enclosurePush.direction());
            return;
        }
        if (!insideEnclosedRegion) {
            BorderPush borderPush = plugin.borderService().borderPush(player, event.getTo(), false);
            if (borderPush != null) {
                cancelBlockedTeleport(event, borderPush.direction());
                return;
            }
        }
        if (plugin.settings().worldGuardBorderEnabled() && !insideEnclosedRegion) {
            BorderPush escapePush = plugin.borderService().noPvpEscapePush(event.getTo());
            if (escapePush != null) {
                cancelBlockedTeleport(event, escapePush.direction());
                return;
            }
            if (plugin.borderService().isNoPvp(event.getTo())) {
                Location blocked = event.getTo().clone();
                cancelBlockedTeleport(event, event.getFrom().toVector().subtract(blocked.toVector()));
                return;
            }
        }
        renderNextTick(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPearlLanding(PlayerTeleportEvent event) {
        if (!isEnderPearlTeleport(event) || !plugin.settings().pearlExtendsCombat()) {
            return;
        }
        plugin.combatTags().refreshFromPearl(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (!event.isGliding() || !(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!plugin.settings().disableElytraWhileTagged() || !plugin.combatTags().isTagged(player)) {
            return;
        }
        event.setCancelled(true);
        plugin.elytraGuard().enforce(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !plugin.elytraGuard().shouldBlock(player)) {
            return;
        }
        storeBlockedElytraClick(event, player);
        plugin.elytraGuard().enforceNextTick(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !plugin.elytraGuard().shouldBlock(player)) {
            return;
        }
        if (plugin.elytraGuard().isElytra(event.getOldCursor()) && touchesArmorSlot(event)) {
            event.setCancelled(true);
            ItemStack elytra = FoItemStacks.cloneItem(event.getOldCursor());
            player.setItemOnCursor(null);
            plugin.elytraGuard().storeBlockedElytra(player, elytra);
        }
        plugin.elytraGuard().enforceNextTick(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (plugin.elytraGuard().shouldBlock(player) && plugin.elytraGuard().isElytra(event.getItem())) {
            event.setCancelled(true);
            storeBlockedInteractElytra(event, player);
            plugin.elytraGuard().enforceNextTick(player);
            return;
        }
        enforcePearlCooldown(event, player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispenseArmor(BlockDispenseArmorEvent event) {
        if (!(event.getTargetEntity() instanceof Player player) || !plugin.elytraGuard().shouldBlock(player)) {
            return;
        }
        if (plugin.elytraGuard().isElytra(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) {
            return;
        }
        ProjectileSource shooter = pearl.getShooter();
        if (!(shooter instanceof Player player)) {
            return;
        }
        startPearlCooldown(player);
        trackBlockedPearl(pearl, player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        boolean tagged = plugin.combatTags().isTagged(player);
        if (tagged && plugin.settings().killOnLogout() && !player.isDead() && player.getHealth() > 0.0D) {
            try {
                player.setHealth(0.0D);
                plugin.logInfo(player.getName() + " died for logging out while combat tagged.");
            } catch (RuntimeException exception) {
                plugin.logWarning("Failed to kill " + player.getName() + " on combat logout: " + exception.getMessage());
            }
        }
        if (tagged) {
            punishCombatLogout(player);
        }
        plugin.combatTags().untag(player, CombatTagRemoveReason.QUIT, false);
        pearlCooldowns.remove(player.getUniqueId());
        queuedBorderRenders.remove(player.getUniqueId());
        removeTrackedPearls(player.getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        clearDeathTag(player, true);
        plugin.core().scheduler().runForPlayer(player, () -> {
            if (player.isOnline()) {
                clearDeathTag(player, false);
            }
        });
        plugin.combatTags().untagOpponentsOf(player);
        clearDeathTransientState(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        clearDeathTag(player, false);
        clearDeathTransientState(player);
    }

    @EventHandler
    public void onCombatTagRemove(CombatTagRemoveEvent event) {
        queuedBorderRenders.remove(event.getPlayer().getUniqueId());
        removeTrackedPearls(event.getPlayer().getUniqueId());
    }

    private void clearDeathTag(Player player, boolean notify) {
        plugin.combatTags().untag(player, CombatTagRemoveReason.DEATH, notify);
    }

    private void clearDeathTransientState(Player player) {
        pearlCooldowns.remove(player.getUniqueId());
        queuedBorderRenders.remove(player.getUniqueId());
        removeTrackedPearls(player.getUniqueId());
    }

    private void punishCombatLogout(Player player) {
        if (plugin.settings().combatLogoutBroadcastEnabled()) {
            String template = plugin.settings().combatLogoutBroadcastMessage();
            if (template != null && !template.contains("{prefix}")) {
                template = "{prefix}" + template;
            }
            Bukkit.broadcastMessage(plugin.messages().renderTemplate(template, Map.of(
                    "player", player.getName()
            )));
        }
        for (String command : plugin.settings().combatLogoutConsoleCommands()) {
            String parsed = CommandPlaceholders.applyPlayer(command, player);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
        }
    }

    private boolean sameBlock(org.bukkit.Location left, org.bukkit.Location right) {
        return left.getWorld() != null && left.getWorld().equals(right.getWorld())
                && left.getBlockX() == right.getBlockX()
                && left.getBlockY() == right.getBlockY()
                && left.getBlockZ() == right.getBlockZ();
    }

    private boolean samePosition(Location left, Location right) {
        return left.getWorld() != null && left.getWorld().equals(right.getWorld())
                && Double.compare(left.getX(), right.getX()) == 0
                && Double.compare(left.getY(), right.getY()) == 0
                && Double.compare(left.getZ(), right.getZ()) == 0;
    }

    private boolean borderGuardEnabled(Player player) {
        return plugin.settings().worldGuardChecksEnabled()
                && (plugin.settings().worldGuardBorderEnabled() || plugin.settings().hasEnclosedWorldGuardRegions())
                && plugin.worldGuardHook().isAvailable()
                && plugin.settings().isWorldEnabled(player.getWorld());
    }

    private void renderNextTick(Player player) {
        UUID playerId = player.getUniqueId();
        if (!queuedBorderRenders.add(playerId)) {
            return;
        }
        plugin.core().scheduler().runForPlayer(player, () -> {
            queuedBorderRenders.remove(playerId);
            if (player.isOnline() && plugin.combatTags().isTagged(player)) {
                plugin.borderService().render(player);
            }
        });
    }

    private void cancelBlockedTeleport(PlayerTeleportEvent event, Vector push) {
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (isEnderPearlTeleport(event)) {
            showPearlBlockedVisual(player, event.getFrom(), event.getTo());
        } else if (isPhysicalTeleport(event)) {
            plugin.borderService().pushBackOnce(player, push);
        }
        renderNextTick(player);
    }

    private void cancelEnclosedRegionTeleport(PlayerTeleportEvent event, Vector push) {
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (isEnderPearlTeleport(event)) {
            Location impact = firstBlockedTransitionSample(event.getFrom(), event.getTo(), true);
            showPearlBlockedVisual(player, event.getFrom(), impact == null ? event.getTo() : impact);
        } else if (isPhysicalTeleport(event)) {
            plugin.borderService().pushBackFromEnclosedExit(player, push);
        }
        renderNextTick(player);
    }

    private boolean isPhysicalTeleport(PlayerTeleportEvent event) {
        String cause = event.getCause().name();
        return cause.equals("ENDER_PEARL") || cause.equals("CHORUS_FRUIT");
    }

    private boolean isEnderPearlTeleport(PlayerTeleportEvent event) {
        return event.getCause().name().equals("ENDER_PEARL");
    }

    private void enforcePearlCooldown(PlayerInteractEvent event, Player player) {
        if (!pearlCooldownApplies(player)) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.ENDER_PEARL) {
            return;
        }
        if (event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        long now = System.currentTimeMillis();
        long availableAt = pearlCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (availableAt > now) {
            event.setCancelled(true);
            event.setUseItemInHand(Event.Result.DENY);
            long remainingSeconds = Math.max(1L, (availableAt - now + 999L) / 1000L);
            plugin.messages().send(player, "pearl-cooldown", Map.of("{seconds}", String.valueOf(remainingSeconds)));
            return;
        }
    }

    private void startPearlCooldown(Player player) {
        if (!pearlCooldownApplies(player)) {
            return;
        }
        int cooldownTicks = plugin.settings().pearlCooldownSeconds() * 20;
        long durationMillis = plugin.settings().pearlCooldownSeconds() * 1000L;
        pearlCooldowns.put(player.getUniqueId(), System.currentTimeMillis() + durationMillis);
        player.setCooldown(Material.ENDER_PEARL, cooldownTicks);
        plugin.core().scheduler().runForPlayer(player, () -> {
            if (player.isOnline() && pearlCooldownApplies(player)) {
                player.setCooldown(Material.ENDER_PEARL, cooldownTicks);
            }
        });
    }

    private void trackBlockedPearl(EnderPearl pearl, Player player) {
        if (!pearlRegionGuardEnabled(player)) {
            return;
        }
        boolean enforceEnclosedRegion = plugin.borderService().isEnclosedRegion(player.getLocation());
        trackedPearls.put(pearl.getUniqueId(), new TrackedPearl(pearl, player, player.getLocation().clone(), enforceEnclosedRegion));
        startPearlGuardTask();
    }

    private void startPearlGuardTask() {
        if (pearlGuardTask != null) {
            return;
        }
        pearlGuardTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickTrackedPearls, 1L, 1L);
    }

    private void tickTrackedPearls() {
        if (trackedPearls.isEmpty()) {
            stopPearlGuardTask();
            return;
        }
        for (Map.Entry<UUID, TrackedPearl> entry : trackedPearls.entrySet()) {
            TrackedPearl tracked = entry.getValue();
            EnderPearl pearl = tracked.pearl;
            Player player = tracked.player;
            if (!pearl.isValid() || !player.isOnline() || !pearlRegionGuardEnabled(player) || tracked.ticks >= PEARL_GUARD_MAX_TICKS) {
                trackedPearls.remove(entry.getKey(), tracked);
                continue;
            }
            Location current = pearl.getLocation();
            Location blocked = firstBlockedTransitionSample(tracked.previous, current, tracked.enforceEnclosedRegion);
            if (blocked != null) {
                pearl.remove();
                showPearlBlockedVisual(player, tracked.previous, blocked);
                renderNextTick(player);
                trackedPearls.remove(entry.getKey(), tracked);
                continue;
            }
            tracked.previous = current.clone();
            tracked.ticks++;
            if (tracked.ticks >= PEARL_GUARD_MAX_TICKS) {
                trackedPearls.remove(entry.getKey(), tracked);
            }
        }
        if (trackedPearls.isEmpty()) {
            stopPearlGuardTask();
        }
    }

    private void stopPearlGuardTask() {
        if (pearlGuardTask == null) {
            return;
        }
        pearlGuardTask.cancel();
        pearlGuardTask = null;
    }

    private void removeTrackedPearls(UUID playerId) {
        trackedPearls.entrySet().removeIf(entry -> entry.getValue().player.getUniqueId().equals(playerId));
        if (trackedPearls.isEmpty()) {
            stopPearlGuardTask();
        }
    }

    private Location firstBlockedTransitionSample(Location from, Location to, boolean enforceEnclosedRegion) {
        if (to == null || to.getWorld() == null) {
            return null;
        }
        if (from == null || from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return plugin.borderService().blocksRegionTransition(from, to, enforceEnclosedRegion) ? to.clone() : null;
        }
        Vector start = from.toVector();
        Vector delta = to.toVector().subtract(start);
        int samples = Math.max(1, Math.min(16, (int) Math.ceil(delta.length() * 4.0D)));
        Location previous = from;
        for (int index = 1; index <= samples; index++) {
            double progress = index / (double) samples;
            Location sample = new Location(
                    to.getWorld(),
                    start.getX() + delta.getX() * progress,
                    start.getY() + delta.getY() * progress,
                    start.getZ() + delta.getZ() * progress
            );
            if (plugin.borderService().blocksRegionTransition(previous, sample, enforceEnclosedRegion)) {
                return sample;
            }
            previous = sample;
        }
        return null;
    }

    private boolean pearlRegionGuardEnabled(Player player) {
        return plugin.combatTags().isTagged(player) && borderGuardEnabled(player);
    }

    private void showPearlBlockedVisual(Player player, Location from, Location location) {
        if (location == null || !player.isOnline() || !player.getWorld().equals(location.getWorld())) {
            return;
        }
        plugin.borderService().flashPearlImpact(player, from, location);
    }

    private boolean pearlCooldownApplies(Player player) {
        return switch (plugin.settings().pearlCooldownMode()) {
            case ALWAYS -> true;
            case WHEN_TAGGED -> plugin.combatTags().isTagged(player);
            case NEVER -> false;
        };
    }

    private boolean storeBlockedElytraClick(InventoryClickEvent event, Player player) {
        boolean armorSlot = event.getSlotType() == InventoryType.SlotType.ARMOR;
        if (event.isShiftClick() && !armorSlot && plugin.elytraGuard().isElytra(event.getCurrentItem())) {
            event.setCancelled(true);
            ItemStack elytra = FoItemStacks.cloneItem(event.getCurrentItem());
            event.setCurrentItem(null);
            plugin.elytraGuard().storeBlockedElytra(player, elytra);
            return true;
        }
        if (!armorSlot) {
            return false;
        }
        if (plugin.elytraGuard().isElytra(event.getCursor())) {
            event.setCancelled(true);
            ItemStack elytra = FoItemStacks.cloneItem(event.getCursor());
            player.setItemOnCursor(null);
            plugin.elytraGuard().storeBlockedElytra(player, elytra);
            return true;
        }
        if (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0) {
            ItemStack hotbarItem = player.getInventory().getItem(event.getHotbarButton());
            if (plugin.elytraGuard().isElytra(hotbarItem)) {
                event.setCancelled(true);
                player.getInventory().setItem(event.getHotbarButton(), null);
                plugin.elytraGuard().storeBlockedElytra(player, hotbarItem);
                return true;
            }
            return false;
        }
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            ItemStack offhandItem = player.getInventory().getItemInOffHand();
            if (plugin.elytraGuard().isElytra(offhandItem)) {
                event.setCancelled(true);
                player.getInventory().setItemInOffHand(null);
                plugin.elytraGuard().storeBlockedElytra(player, offhandItem);
                return true;
            }
        }
        return false;
    }

    private void storeBlockedInteractElytra(PlayerInteractEvent event, Player player) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            ItemStack item = player.getInventory().getItemInOffHand();
            if (plugin.elytraGuard().isElytra(item)) {
                player.getInventory().setItemInOffHand(null);
                plugin.elytraGuard().storeBlockedElytra(player, item);
            }
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (plugin.elytraGuard().isElytra(item)) {
            player.getInventory().setItemInMainHand(null);
            plugin.elytraGuard().storeBlockedElytra(player, item);
        }
    }

    private boolean touchesArmorSlot(InventoryDragEvent event) {
        for (Map.Entry<Integer, ItemStack> entry : event.getNewItems().entrySet()) {
            if (event.getView().getSlotType(entry.getKey()) == InventoryType.SlotType.ARMOR
                    && plugin.elytraGuard().isElytra(entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static final class TrackedPearl {
        private final EnderPearl pearl;
        private final Player player;
        private final boolean enforceEnclosedRegion;
        private Location previous;
        private int ticks;

        private TrackedPearl(EnderPearl pearl, Player player, Location previous, boolean enforceEnclosedRegion) {
            this.pearl = pearl;
            this.player = player;
            this.previous = previous;
            this.enforceEnclosedRegion = enforceEnclosedRegion;
        }
    }
}
