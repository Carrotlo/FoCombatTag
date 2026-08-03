package me.foesio.foCombatTag.service;

import me.foesio.foCombatTag.FoCombatTag;
import me.foesio.foCombatTag.config.Settings;
import me.foesio.foCombatTag.hook.WorldGuardHook;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BorderService {
    private static final int[][] DIRECTIONS = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};
    private static final int[][] HORIZONTAL_DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final double PUSH_TRIGGER_DISTANCE = 0.56D;
    private static final double PLAYER_HALF_WIDTH = 0.31D;
    private static final double PLAYER_HEIGHT = 1.8D;
    private static final double VISUAL_FACE_OFFSET = 0.0125D;
    private static final double VISUAL_CONE_DOT = -0.15D;
    private static final int PLAYER_VISUAL_RADIUS = 2;
    private static final float PLAYER_VISUAL_MIN_VIEW_RANGE = 10.0F;
    private static final float PEARL_IMPACT_MIN_VIEW_RANGE = 16.0F;
    private static final long FAKE_BLOCK_REFRESH_MILLIS = 5_000L;
    private static final double BASE_PUSH_HORIZONTAL_STRENGTH = 0.75D;
    private static final double BASE_PUSH_VERTICAL_STRENGTH = 0.28D;
    private static final long PUSHBACK_COOLDOWN_MILLIS = 250L;
    private static final int NO_PVP_CACHE_CLEANUP_THRESHOLD = 10_000;
    private static final long NO_PVP_CACHE_CLEANUP_INTERVAL_MILLIS = 1_000L;
    private static final int VISUAL_NEAR_ALPHA = 150;
    private static final int VISUAL_FAR_ALPHA = 35;
    private static final int PEARL_IMPACT_NEAR_ALPHA = 210;
    private static final int PEARL_IMPACT_FAR_ALPHA = 70;
    private static final String VISUAL_TEXT = "X";
    private static final Transformation VISUAL_TRANSFORM = new Transformation(
            new Vector3f(-0.07F, 0.005F, 0.0F),
            new Quaternionf(),
            new Vector3f(5.715F, 4.0F, 1.0F),
            new Quaternionf()
    );

    private final FoCombatTag plugin;
    private final WorldGuardHook worldGuardHook;
    private final Map<UUID, Set<BlockKey>> sentBlocks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextBlockRefreshAtMillis = new ConcurrentHashMap<>();
    private final Map<UUID, Map<BorderVisualKey, BorderVisualDisplay>> visualDisplays = new ConcurrentHashMap<>();
    private final Set<TextDisplay> temporaryVisualDisplays = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Map<BorderScanKey, BorderScan>> scanCache = new ConcurrentHashMap<>();
    private final Map<BlockKey, CachedFlag> noPvpCache = new ConcurrentHashMap<>();
    private final Map<BlockKey, CachedFlag> enclosedRegionCache = new ConcurrentHashMap<>();
    private final Map<BlockKey, CachedEscape> noPvpEscapeCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pushbackCooldowns = new ConcurrentHashMap<>();
    private Material cachedCollisionMaterial;
    private BlockData cachedCollisionData;
    private long nextNoPvpCacheCleanupAtMillis;

    public BorderService(FoCombatTag plugin, WorldGuardHook worldGuardHook) {
        this.plugin = plugin;
        this.worldGuardHook = worldGuardHook;
    }

    public void reload() {
        scanCache.clear();
        noPvpCache.clear();
        enclosedRegionCache.clear();
        noPvpEscapeCache.clear();
        pushbackCooldowns.clear();
        cachedCollisionMaterial = null;
        cachedCollisionData = null;
        nextNoPvpCacheCleanupAtMillis = 0L;
        clearAll();
    }

    public void render(Player player) {
        Settings settings = plugin.settings();
        if (!settings.worldGuardChecksEnabled()
                || !hasAnyRegionGuard(settings)
                || !worldGuardHook.isAvailable()
                || !settings.isWorldEnabled(player.getWorld())) {
            clear(player);
            return;
        }

        UUID playerId = player.getUniqueId();
        Map<BlockKey, GuardMode> targetBlockModes = collectGuardBlocks(player, player.getLocation(), settings.worldGuardBorderOnlyAir());
        Set<BlockKey> targetBlocks = targetBlockModes.keySet();
        Set<BlockKey> previous = sentBlocks.computeIfAbsent(playerId, ignored -> new HashSet<>());
        BlockData fakeData = collisionBlockData(settings);
        World world = player.getWorld();
        long now = System.currentTimeMillis();
        boolean refreshBlocks = nextBlockRefreshAtMillis.getOrDefault(playerId, 0L) <= now;
        if (refreshBlocks) {
            nextBlockRefreshAtMillis.put(playerId, now + FAKE_BLOCK_REFRESH_MILLIS);
        }

        for (BlockKey key : targetBlocks) {
            if (!refreshBlocks && previous.contains(key)) {
                continue;
            }
            if (world.getUID().equals(key.worldId())) {
                player.sendBlockChange(new Location(world, key.x(), key.y(), key.z()), fakeData);
            }
        }

        Iterator<BlockKey> iterator = previous.iterator();
        while (iterator.hasNext()) {
            BlockKey key = iterator.next();
            if (!targetBlocks.contains(key)) {
                restore(player, key);
                iterator.remove();
            }
        }

        previous.addAll(targetBlocks);
        if (settings.worldGuardBorderAdvancedVisualsEnabled()) {
            syncVisualDisplays(player, collectVisualPanels(player, targetBlockModes), settings.worldGuardBorderVisualColor());
        } else {
            removeVisualDisplays(playerId);
        }
    }

    private boolean hasAnyRegionGuard(Settings settings) {
        return settings.worldGuardBorderEnabled() || settings.hasEnclosedWorldGuardRegions();
    }

    private BlockData collisionBlockData(Settings settings) {
        Material material = settings.worldGuardBorderCollisionMaterial();
        if (cachedCollisionData == null || cachedCollisionMaterial != material) {
            cachedCollisionMaterial = material;
            cachedCollisionData = material.createBlockData();
        }
        return cachedCollisionData;
    }

    public void clear(Player player) {
        UUID playerId = player.getUniqueId();
        Set<BlockKey> previous = sentBlocks.remove(playerId);
        nextBlockRefreshAtMillis.remove(playerId);
        scanCache.remove(playerId);
        pushbackCooldowns.remove(playerId);
        removeVisualDisplays(playerId);
        if (previous == null) {
            return;
        }
        for (BlockKey key : previous) {
            restore(player, key);
        }
    }

    public void clearAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            clear(player);
        }
        sentBlocks.clear();
        nextBlockRefreshAtMillis.clear();
        for (Map<BorderVisualKey, BorderVisualDisplay> displays : visualDisplays.values()) {
            for (BorderVisualDisplay display : displays.values()) {
                removeDisplay(display.display());
            }
        }
        visualDisplays.clear();
        pushbackCooldowns.clear();
        nextNoPvpCacheCleanupAtMillis = 0L;
        for (TextDisplay display : new HashSet<>(temporaryVisualDisplays)) {
            removeDisplay(display);
        }
        temporaryVisualDisplays.clear();
        scanCache.clear();
        enclosedRegionCache.clear();
        noPvpEscapeCache.clear();
    }

    public BorderPush borderPush(Player player, Location location) {
        if (player == null || location == null || location.getWorld() == null) {
            return null;
        }
        Settings settings = plugin.settings();
        if (!settings.worldGuardChecksEnabled() || !hasAnyRegionGuard(settings) || !worldGuardHook.isAvailable()) {
            return null;
        }
        if (usesEnclosedGuard(player)) {
            return null;
        }
        Vector direction = null;
        double nearestClearance = Double.MAX_VALUE;
        boolean triggered = false;
        long now = System.currentTimeMillis();
        long ttlMillis = settings.worldGuardBorderCacheSeconds() * 1000L;

        for (Map.Entry<BlockKey, GuardMode> entry : collectPushGuardBlocks(player, location).entrySet()) {
            BlockKey key = entry.getKey();
            if (!overlaps(location.getY(), location.getY() + PLAYER_HEIGHT, key.y(), key.y() + 1.0D)) {
                continue;
            }
            for (int[] openSide : HORIZONTAL_DIRECTIONS) {
                Location neighbor = new Location(location.getWorld(), key.x() + openSide[0], key.y(), key.z() + openSide[1]);
                if (!isOpenSide(neighbor, entry.getValue(), now, ttlMillis)) {
                    continue;
                }
                double clearance = clearanceFromBoundary(location, key, openSide);
                if (clearance > PUSH_TRIGGER_DISTANCE || !overlapsBoundarySide(location, key, openSide)) {
                    continue;
                }
                triggered = true;
                if (clearance < nearestClearance) {
                    nearestClearance = clearance;
                    direction = pushDirection(location, key, openSide, entry.getValue());
                }
            }
        }

        if (!triggered || direction == null || direction.lengthSquared() < 0.0001D) {
            return null;
        }
        return new BorderPush(direction.normalize());
    }

    public boolean shouldCheckBorderPush(Player player, Location location) {
        if (player == null || location == null || location.getWorld() == null) {
            return true;
        }
        if (usesEnclosedGuard(player)) {
            return false;
        }
        Set<BlockKey> cachedBlocks = cachedGuardBlocks(player, location, false);
        return cachedBlocks == null || hasNearbyPushCandidate(location, cachedBlocks);
    }

    public BorderPush enclosedRegionEscapePush(Location from, Location to) {
        if (from == null || to == null || from.getWorld() == null || to.getWorld() == null) {
            return null;
        }
        Settings settings = plugin.settings();
        if (!settings.worldGuardChecksEnabled()
                || !settings.hasEnclosedWorldGuardRegions()
                || !worldGuardHook.isAvailable()
                || !settings.isWorldEnabled(from.getWorld())) {
            return null;
        }
        if (!isEnclosedRegion(from)) {
            return null;
        }
        if (!from.getWorld().equals(to.getWorld())) {
            return new BorderPush(new Vector(0.0D, 0.0D, 0.0D));
        }
        if (isEnclosedRegion(to) && (sameBlock(from, to) || sharesSelectedEnclosedRegion(from, to))) {
            return null;
        }
        Vector direction = enclosedRegionCenterDirection(from);
        if (direction == null || direction.lengthSquared() < 0.0001D) {
            direction = from.toVector().subtract(to.toVector());
            direction.setY(0.0D);
        }
        if (direction.lengthSquared() < 0.0001D) {
            direction = new Vector(from.getBlockX() + 0.5D - to.getX(), 0.0D, from.getBlockZ() + 0.5D - to.getZ());
        }
        return direction.lengthSquared() > 0.0001D ? new BorderPush(direction) : null;
    }

    private Vector enclosedRegionCenterDirection(Location from) {
        Location center = worldGuardHook.regionCenter(from, plugin.settings().enclosedWorldGuardRegions());
        if (center == null || !from.getWorld().equals(center.getWorld())) {
            return null;
        }
        Vector direction = center.toVector().subtract(from.toVector());
        direction.setY(0.0D);
        return direction;
    }

    private boolean sameBlock(Location left, Location right) {
        return left.getWorld() != null && left.getWorld().equals(right.getWorld())
                && left.getBlockX() == right.getBlockX()
                && left.getBlockY() == right.getBlockY()
                && left.getBlockZ() == right.getBlockZ();
    }

    private boolean sharesSelectedEnclosedRegion(Location from, Location to) {
        Set<String> fromRegions = selectedRegionIdsAt(from);
        if (fromRegions.isEmpty()) {
            return false;
        }
        Set<String> toRegions = selectedRegionIdsAt(to);
        for (String region : fromRegions) {
            if (toRegions.contains(region)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> selectedRegionIdsAt(Location location) {
        Set<String> configured = plugin.settings().enclosedWorldGuardRegions();
        if (configured.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        for (String region : worldGuardHook.regionIdsAt(location)) {
            if (configured.contains(region)) {
                result.add(region);
            }
        }
        return result;
    }

    public boolean blocksRegionTransition(Location from, Location to) {
        return blocksRegionTransition(from, to, true);
    }

    public boolean blocksRegionTransition(Location from, Location to, boolean enforceEnclosedRegions) {
        Settings settings = plugin.settings();
        if (!settings.worldGuardChecksEnabled() || !worldGuardHook.isAvailable() || to == null || to.getWorld() == null) {
            return false;
        }
        if (enforceEnclosedRegions && from != null && from.getWorld() != null && isEnclosedRegion(from)) {
            return enclosedRegionEscapePush(from, to) != null;
        }
        if (settings.worldGuardBorderEnabled() && isNoPvp(to)) {
            return true;
        }
        return enforceEnclosedRegions && enclosedRegionEscapePush(from, to) != null;
    }

    public BorderPush noPvpEscapePush(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        long ttlMillis = plugin.settings().worldGuardBorderCacheSeconds() * 1000L;
        if (!isNoPvp(location, now, ttlMillis)) {
            return null;
        }

        BlockKey key = BlockKey.from(location);
        if (ttlMillis > 0L) {
            CachedEscape cached = noPvpEscapeCache.get(key);
            if (cached != null && cached.expiresAtMillis() > now) {
                return cached.toBorderPush();
            }
        }

        Vector direction = worldGuardHook.noPvpEscapeDirection(location);
        if (direction == null || direction.lengthSquared() < 0.0001D) {
            direction = fallbackEscapeDirection(location);
        }
        CachedEscape cached = CachedEscape.from(direction, now + ttlMillis);
        if (ttlMillis > 0L) {
            noPvpEscapeCache.put(key, cached);
            cleanupNoPvpCache(now);
        }
        return cached.toBorderPush();
    }

    public boolean pushOutOfNoPvp(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        if (isEnclosedRegion(player.getLocation())) {
            return false;
        }
        BorderPush push = noPvpEscapePush(player.getLocation());
        return push != null && pushBackOnce(player, push.direction());
    }

    public boolean pushBackOnce(Player player, Vector push) {
        return pushBack(player, push, false);
    }

    public boolean pushBackFromEnclosedExit(Player player, Vector push) {
        return pushBack(player, push, true);
    }

    private boolean pushBack(Player player, Vector push, boolean immediate) {
        if (player == null || push == null) {
            return false;
        }
        double multiplier = plugin.settings().worldGuardBorderPushbackMultiplier();
        if (multiplier <= 0.0D) {
            return false;
        }
        Vector velocity = push.clone();
        velocity.setY(0.0D);
        if (velocity.lengthSquared() < 0.01D) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long lastPushAt = pushbackCooldowns.get(player.getUniqueId());
        if (lastPushAt != null && now - lastPushAt < PUSHBACK_COOLDOWN_MILLIS) {
            return false;
        }
        pushbackCooldowns.put(player.getUniqueId(), now);
        if (player.isGliding()) {
            player.setGliding(false);
        }
        velocity.normalize().multiply(BASE_PUSH_HORIZONTAL_STRENGTH * multiplier);
        velocity.setY(BASE_PUSH_VERTICAL_STRENGTH * multiplier);
        player.setNoDamageTicks(0);
        if (immediate) {
            player.setVelocity(velocity.clone());
        }
        plugin.core().scheduler().runForPlayer(player, () -> {
            if (!player.isOnline() || !plugin.combatTags().isTagged(player)) {
                return;
            }
            player.setNoDamageTicks(0);
            player.setVelocity(velocity.clone());
        });
        return true;
    }

    public void flashPearlImpact(Player player, Location from, Location impact) {
        Settings settings = plugin.settings();
        if (player == null
                || impact == null
                || impact.getWorld() == null
                || !player.isOnline()
                || !player.getWorld().equals(impact.getWorld())
                || !settings.worldGuardChecksEnabled()
                || !hasAnyRegionGuard(settings)
                || !settings.worldGuardBorderAdvancedVisualsEnabled()
                || !worldGuardHook.isAvailable()
                || !settings.isWorldEnabled(player.getWorld())) {
            return;
        }

        GuardMode mode = pearlImpactMode(from, impact);
        Set<BorderVisualPanel> panels = collectImpactVisualPanels(player, from, impact, settings.worldGuardBorderPearlImpactRadius(), mode);
        if (panels.isEmpty()) {
            return;
        }

        List<ImpactDisplay> displays = new ArrayList<>();
        for (BorderVisualPanel panel : panels) {
            TextDisplay display = spawnVisualDisplay(player, panel, settings.worldGuardBorderVisualColor(), pearlImpactViewRange(player, panel));
            if (display != null) {
                temporaryVisualDisplays.add(display);
                displays.add(new ImpactDisplay(display, panel));
            }
        }
        if (displays.isEmpty()) {
            return;
        }

        new BukkitRunnable() {
            private int age;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    removeImpactDisplays(displays);
                    cancel();
                    return;
                }
                age++;
                double fade = 1.0D - (age / (double) settings.worldGuardBorderPearlImpactDurationTicks());
                if (fade <= 0.0D) {
                    removeImpactDisplays(displays);
                    cancel();
                    return;
                }
                for (ImpactDisplay impactDisplay : displays) {
                    TextDisplay display = impactDisplay.display();
                    if (!display.isValid()) {
                        continue;
                    }
                    int alpha = Math.max(0, (int) Math.round(impactDisplay.panel().alpha() * fade));
                    updateVisualDisplay(display, impactDisplay.panel(), settings.worldGuardBorderVisualColor(), alpha);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private Set<BlockKey> collectBorderBlocks(Player player, Location base, boolean onlyAir) {
        Settings settings = plugin.settings();
        if (!settings.worldGuardBorderEnabled()) {
            return Set.of();
        }
        return collectBorderBlocks(player, base, onlyAir, settings.worldGuardBorderRadius(), settings.worldGuardBorderHeight(), GuardMode.NO_PVP);
    }

    private Map<BlockKey, GuardMode> collectGuardBlocks(Player player, Location base, boolean onlyAir) {
        Map<BlockKey, GuardMode> result = new HashMap<>();
        Settings settings = plugin.settings();
        if (usesEnclosedGuard(player)) {
            putGuardBlocks(result, collectEnclosedBorderBlocks(player, base, onlyAir), GuardMode.ENCLOSED_REGION);
            return result;
        }
        if (settings.worldGuardBorderEnabled() && !isNoPvp(base)) {
            putGuardBlocks(result, collectBorderBlocks(player, base, onlyAir), GuardMode.NO_PVP);
        }
        return result;
    }

    private Map<BlockKey, GuardMode> collectPushGuardBlocks(Player player, Location base) {
        Map<BlockKey, GuardMode> result = new HashMap<>();
        Settings settings = plugin.settings();
        if (usesEnclosedGuard(player)) {
            return result;
        }
        if (settings.worldGuardBorderEnabled()) {
            putGuardBlocks(result, collectBorderBlocks(player, base, false), GuardMode.NO_PVP);
        }
        return result;
    }

    private boolean usesEnclosedGuard(Player player) {
        return player != null
                && plugin.settings().hasEnclosedWorldGuardRegions()
                && isEnclosedRegion(player.getLocation());
    }

    private void putGuardBlocks(Map<BlockKey, GuardMode> target, Set<BlockKey> blocks, GuardMode mode) {
        for (BlockKey block : blocks) {
            target.putIfAbsent(block, mode);
        }
    }

    private Set<BlockKey> collectEnclosedBorderBlocks(Player player, Location base, boolean onlyAir) {
        Settings settings = plugin.settings();
        if (!settings.hasEnclosedWorldGuardRegions()) {
            return Set.of();
        }
        return collectBorderBlocks(player, base, onlyAir, settings.worldGuardBorderRadius(), settings.worldGuardBorderHeight(), GuardMode.ENCLOSED_REGION);
    }

    private Set<BlockKey> collectBorderBlocks(Player player, Location base, boolean onlyAir, int radius, int height, GuardMode mode) {
        BorderScanKey scanKey = borderScanKey(base, radius, height, onlyAir, mode);
        long now = System.currentTimeMillis();
        long ttlMillis = plugin.settings().worldGuardBorderCacheSeconds() * 1000L;
        Map<BorderScanKey, BorderScan> playerScans = null;
        if (ttlMillis > 0L) {
            playerScans = scanCache.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>());
            BorderScan cached = cachedScan(playerScans, scanKey, now);
            if (cached != null) {
                return cached.blocks();
            }
            if (onlyAir) {
                BorderScan fullScan = cachedScan(playerScans, borderScanKey(base, radius, height, false, mode), now);
                if (fullScan != null) {
                    Set<BlockKey> filtered = airBlocks(base.getWorld(), fullScan.blocks());
                    cacheScan(playerScans, scanKey, filtered, now, fullScan.expiresAtMillis(), false);
                    return filtered;
                }
            }
        }

        Set<BlockKey> result = new HashSet<>();
        World world = base.getWorld();
        int minY = Math.max(world.getMinHeight(), base.getBlockY() - height);
        int maxY = Math.min(world.getMaxHeight() - 1, base.getBlockY() + height);

        for (int x = base.getBlockX() - radius; x <= base.getBlockX() + radius; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = base.getBlockZ() - radius; z <= base.getBlockZ() + radius; z++) {
                    Location location = new Location(world, x, y, z);
                    if (onlyAir && !location.getBlock().getType().isAir()) {
                        continue;
                    }
                    if (isBorderBlock(location, mode, now, ttlMillis)) {
                        result.add(BlockKey.from(location));
                    }
                }
            }
        }
        Set<BlockKey> snapshot = Set.copyOf(result);
        if (ttlMillis > 0L && playerScans != null) {
            cacheScan(playerScans, scanKey, snapshot, now, now + ttlMillis, true);
        }
        return snapshot;
    }

    private BorderScan cachedScan(Map<BorderScanKey, BorderScan> playerScans, BorderScanKey scanKey, long now) {
        BorderScan cached = playerScans.get(scanKey);
        if (cached == null || !cached.key().equals(scanKey)) {
            return null;
        }
        if (cached.expiresAtMillis() <= now) {
            playerScans.remove(scanKey, cached);
            return null;
        }
        return cached;
    }

    private void cacheScan(Map<BorderScanKey, BorderScan> playerScans, BorderScanKey scanKey, Set<BlockKey> blocks,
                           long now, long expiresAtMillis, boolean cleanupGlobalCaches) {
        cleanupScanCache(playerScans, now);
        playerScans.put(scanKey, new BorderScan(scanKey, blocks, expiresAtMillis));
        if (cleanupGlobalCaches) {
            cleanupNoPvpCache(now);
        }
    }

    private Set<BlockKey> airBlocks(World world, Set<BlockKey> blocks) {
        if (blocks.isEmpty()) {
            return Set.of();
        }
        Set<BlockKey> result = new HashSet<>();
        UUID worldId = world.getUID();
        for (BlockKey key : blocks) {
            if (!worldId.equals(key.worldId())) {
                continue;
            }
            if (world.getBlockAt(key.x(), key.y(), key.z()).getType().isAir()) {
                result.add(key);
            }
        }
        return Set.copyOf(result);
    }

    private Set<BlockKey> cachedGuardBlocks(Player player, Location base, boolean onlyAir) {
        long ttlMillis = plugin.settings().worldGuardBorderCacheSeconds() * 1000L;
        if (ttlMillis <= 0L) {
            return null;
        }
        Map<BorderScanKey, BorderScan> playerScans = scanCache.get(player.getUniqueId());
        if (playerScans == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        Set<BlockKey> result = new HashSet<>();
        Settings settings = plugin.settings();
        if (usesEnclosedGuard(player)) {
            BorderScan cached = cachedScan(playerScans, borderScanKey(
                    base,
                    settings.worldGuardBorderRadius(),
                    settings.worldGuardBorderHeight(),
                    onlyAir,
                    GuardMode.ENCLOSED_REGION
            ), now);
            if (cached == null) {
                return null;
            }
            result.addAll(cached.blocks());
            return result;
        }
        if (settings.worldGuardBorderEnabled()) {
            BorderScan cached = cachedScan(playerScans, borderScanKey(
                    base,
                    settings.worldGuardBorderRadius(),
                    settings.worldGuardBorderHeight(),
                    onlyAir,
                    GuardMode.NO_PVP
            ), now);
            if (cached == null) {
                return null;
            }
            result.addAll(cached.blocks());
        }
        return result;
    }

    private BorderScanKey borderScanKey(Location base, int radius, int height, boolean onlyAir, GuardMode mode) {
        return new BorderScanKey(
                base.getWorld().getUID(),
                base.getBlockX(),
                base.getBlockY(),
                base.getBlockZ(),
                radius,
                height,
                onlyAir,
                mode
        );
    }

    private boolean hasNearbyPushCandidate(Location location, Set<BlockKey> cachedBlocks) {
        if (cachedBlocks.isEmpty()) {
            return false;
        }
        double minY = location.getY();
        double maxY = location.getY() + PLAYER_HEIGHT;
        double minX = location.getX() - PLAYER_HALF_WIDTH - PUSH_TRIGGER_DISTANCE;
        double maxX = location.getX() + PLAYER_HALF_WIDTH + PUSH_TRIGGER_DISTANCE;
        double minZ = location.getZ() - PLAYER_HALF_WIDTH - PUSH_TRIGGER_DISTANCE;
        double maxZ = location.getZ() + PLAYER_HALF_WIDTH + PUSH_TRIGGER_DISTANCE;
        UUID worldId = location.getWorld().getUID();
        for (BlockKey key : cachedBlocks) {
            if (!worldId.equals(key.worldId())) {
                continue;
            }
            if (!overlaps(minY, maxY, key.y(), key.y() + 1.0D)) {
                continue;
            }
            if (overlaps(minX, maxX, key.x(), key.x() + 1.0D)
                    && overlaps(minZ, maxZ, key.z(), key.z() + 1.0D)) {
                return true;
            }
        }
        return false;
    }

    private boolean overlaps(double minA, double maxA, double minB, double maxB) {
        return maxA > minB && minA < maxB;
    }

    private boolean overlapsBoundarySide(Location location, BlockKey key, int[] openSide) {
        if (openSide[0] != 0) {
            return overlaps(location.getZ() - PLAYER_HALF_WIDTH, location.getZ() + PLAYER_HALF_WIDTH, key.z(), key.z() + 1.0D);
        }
        return overlaps(location.getX() - PLAYER_HALF_WIDTH, location.getX() + PLAYER_HALF_WIDTH, key.x(), key.x() + 1.0D);
    }

    private double clearanceFromBoundary(Location location, BlockKey key, int[] openSide) {
        if (openSide[0] > 0) {
            return location.getX() - (key.x() + 1.0D);
        }
        if (openSide[0] < 0) {
            return key.x() - location.getX();
        }
        if (openSide[1] > 0) {
            return location.getZ() - (key.z() + 1.0D);
        }
        return key.z() - location.getZ();
    }

    private Vector pushDirection(Location playerLocation, BlockKey key, int[] openSide, GuardMode mode) {
        if (mode == GuardMode.ENCLOSED_REGION) {
            return new Vector(openSide[0], 0.0D, openSide[1]);
        }
        Location noPvpLocation = key.toLocation();
        Location regionCenter = worldGuardHook.noPvpRegionCenter(noPvpLocation);
        if (regionCenter != null && playerLocation.getWorld().equals(regionCenter.getWorld())) {
            Vector awayFromRegion = playerLocation.toVector().subtract(regionCenter.toVector());
            awayFromRegion.setY(0.0D);
            if (awayFromRegion.lengthSquared() > 0.0001D) {
                return awayFromRegion;
            }
        }
        return new Vector(openSide[0], 0.0D, openSide[1]);
    }

    private Vector fallbackEscapeDirection(Location location) {
        Location regionCenter = worldGuardHook.noPvpRegionCenter(location);
        if (regionCenter == null || !location.getWorld().equals(regionCenter.getWorld())) {
            return null;
        }
        Vector awayFromRegion = location.toVector().subtract(regionCenter.toVector());
        awayFromRegion.setY(0.0D);
        return awayFromRegion.lengthSquared() > 0.0001D ? awayFromRegion : null;
    }

    private boolean isBorderBlock(Location location, GuardMode mode, long now, long ttlMillis) {
        boolean guarded = mode == GuardMode.NO_PVP
                ? isNoPvp(location, now, ttlMillis)
                : !isEnclosedRegion(location, now, ttlMillis);
        if (!guarded) {
            return false;
        }
        World world = location.getWorld();
        for (int[] direction : DIRECTIONS) {
            int y = location.getBlockY() + direction[1];
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                continue;
            }
            Location neighbor = new Location(world, location.getBlockX() + direction[0], y, location.getBlockZ() + direction[2]);
            if (mode == GuardMode.NO_PVP && !isNoPvp(neighbor, now, ttlMillis)) {
                return true;
            }
            if (mode == GuardMode.ENCLOSED_REGION && isEnclosedRegion(neighbor, now, ttlMillis)) {
                return true;
            }
        }
        return false;
    }

    public boolean isNoPvp(Location location) {
        return isNoPvp(location, System.currentTimeMillis(), plugin.settings().worldGuardBorderCacheSeconds() * 1000L);
    }

    public boolean isEnclosedRegion(Location location) {
        return isEnclosedRegion(location, System.currentTimeMillis(), plugin.settings().worldGuardBorderCacheSeconds() * 1000L);
    }

    private boolean isNoPvp(Location location, long now, long ttlMillis) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        BlockKey key = BlockKey.from(location);
        if (ttlMillis > 0L) {
            CachedFlag cached = noPvpCache.get(key);
            if (cached != null && cached.expiresAtMillis() > now) {
                return cached.value();
            }
        }
        boolean noPvp = worldGuardHook.isNoPvp(location);
        if (ttlMillis > 0L) {
            noPvpCache.put(key, new CachedFlag(noPvp, now + ttlMillis));
        }
        return noPvp;
    }

    private boolean isEnclosedRegion(Location location, long now, long ttlMillis) {
        Settings settings = plugin.settings();
        if (location == null
                || location.getWorld() == null
                || !settings.hasEnclosedWorldGuardRegions()
                || !settings.isWorldEnabled(location.getWorld())) {
            return false;
        }
        BlockKey key = BlockKey.from(location);
        if (ttlMillis > 0L) {
            CachedFlag cached = enclosedRegionCache.get(key);
            if (cached != null && cached.expiresAtMillis() > now) {
                return cached.value();
            }
        }
        boolean enclosed = worldGuardHook.hasAnyRegion(location, settings.enclosedWorldGuardRegions());
        if (ttlMillis > 0L) {
            enclosedRegionCache.put(key, new CachedFlag(enclosed, now + ttlMillis));
        }
        return enclosed;
    }

    private void cleanupNoPvpCache(long now) {
        if (noPvpCache.size() < NO_PVP_CACHE_CLEANUP_THRESHOLD
                && enclosedRegionCache.size() < NO_PVP_CACHE_CLEANUP_THRESHOLD
                && noPvpEscapeCache.size() < NO_PVP_CACHE_CLEANUP_THRESHOLD) {
            return;
        }
        if (now < nextNoPvpCacheCleanupAtMillis) {
            return;
        }
        nextNoPvpCacheCleanupAtMillis = now + NO_PVP_CACHE_CLEANUP_INTERVAL_MILLIS;
        if (noPvpCache.size() >= NO_PVP_CACHE_CLEANUP_THRESHOLD) {
            noPvpCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        }
        if (enclosedRegionCache.size() >= NO_PVP_CACHE_CLEANUP_THRESHOLD) {
            enclosedRegionCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        }
        if (noPvpEscapeCache.size() >= NO_PVP_CACHE_CLEANUP_THRESHOLD) {
            noPvpEscapeCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        }
    }

    private void cleanupScanCache(Map<BorderScanKey, BorderScan> playerScans, long now) {
        if (playerScans.size() < 32) {
            return;
        }
        playerScans.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        if (playerScans.size() > 96) {
            playerScans.clear();
        }
    }

    private void restore(Player player, BlockKey key) {
        Location location = key.toLocation();
        if (location == null) {
            return;
        }
        if (!player.getWorld().equals(location.getWorld())) {
            return;
        }
        player.sendBlockChange(location, location.getBlock().getBlockData());
    }

    private Set<BorderVisualPanel> collectVisualPanels(Player player, Map<BlockKey, GuardMode> targetBlocks) {
        Set<BorderVisualPanel> panels = new HashSet<>();
        Settings settings = plugin.settings();
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector look = eye.getDirection().setY(0.0D);
        boolean hasLook = look.lengthSquared() > 0.0001D;
        if (hasLook) {
            look.normalize();
        }
        double visualRange = settings.worldGuardBorderVisualRange();
        long now = System.currentTimeMillis();
        long ttlMillis = settings.worldGuardBorderCacheSeconds() * 1000L;
        PlayerVisualCenter center = nearestPlayerVisualCenter(world, eye, look, hasLook, targetBlocks, visualRange, now, ttlMillis);
        if (center == null) {
            return panels;
        }

        int bodyBottomY = player.getLocation().getBlockY();
        int bodyTopY = bodyBottomY + 1;
        int[] openSide = {center.faceX(), center.faceZ()};
        int centerAlpha = alphaForDistance(center.distance(), visualRange);
        for (int y = bodyBottomY - PLAYER_VISUAL_RADIUS; y <= bodyTopY + PLAYER_VISUAL_RADIUS; y++) {
            int verticalDistance = y < bodyBottomY ? bodyBottomY - y : Math.max(0, y - bodyTopY);
            int lateralRange = PLAYER_VISUAL_RADIUS - verticalDistance;
            for (int lateral = -lateralRange; lateral <= lateralRange; lateral++) {
                BlockKey key = playerVisualTileKey(center.key(), y, openSide, lateral);
                if (key.y() < world.getMinHeight() || key.y() >= world.getMaxHeight()) {
                    continue;
                }
                Location panelLocation = panelLocation(world, key, openSide);
                panels.add(new BorderVisualPanel(
                        new BorderVisualKey(world.getUID(), key.x(), key.y(), key.z(), openSide[0], openSide[1]),
                        panelLocation,
                        playerVisualAlpha(centerAlpha, verticalDistance + Math.abs(lateral))
                ));
            }
        }
        return panels;
    }

    private PlayerVisualCenter nearestPlayerVisualCenter(World world, Location eye, Vector look, boolean hasLook,
                                                         Map<BlockKey, GuardMode> targetBlocks, double visualRange, long now,
                                                         long ttlMillis) {
        PlayerVisualCenter nearest = null;
        for (Map.Entry<BlockKey, GuardMode> entry : targetBlocks.entrySet()) {
            BlockKey key = entry.getKey();
            Location blockLocation = key.toLocation();
            if (blockLocation == null || !world.equals(blockLocation.getWorld())) {
                continue;
            }
            for (int[] openSide : HORIZONTAL_DIRECTIONS) {
                Location neighbor = new Location(world, key.x() + openSide[0], key.y(), key.z() + openSide[1]);
                if (!isOpenSide(neighbor, entry.getValue(), now, ttlMillis)) {
                    continue;
                }
                Location panelLocation = panelLocation(world, key, openSide);
                double distance = eye.distance(panelLocation);
                if (distance > visualRange || !isInVisualDirection(eye, look, hasLook, panelLocation)) {
                    continue;
                }
                if (nearest == null || distance < nearest.distance()) {
                    nearest = new PlayerVisualCenter(key, openSide[0], openSide[1], distance);
                }
            }
        }
        return nearest;
    }

    private GuardMode pearlImpactMode(Location from, Location impact) {
        if (plugin.settings().hasEnclosedWorldGuardRegions()
                && from != null
                && impact != null
                && from.getWorld() != null
                && from.getWorld().equals(impact.getWorld())
                && isEnclosedRegion(from)
                && !isEnclosedRegion(impact)) {
            return GuardMode.ENCLOSED_REGION;
        }
        return GuardMode.NO_PVP;
    }

    private Set<BorderVisualPanel> collectImpactVisualPanels(Player player, Location from, Location impact, int radius, GuardMode mode) {
        Set<BorderVisualPanel> panels = new HashSet<>();
        World world = player.getWorld();
        BlockKey center = BlockKey.from(impact);
        int[] openSide = impactOpenSide(world, from, impact, center, mode);
        for (int vertical = -radius; vertical <= radius; vertical++) {
            int lateralRange = radius - Math.abs(vertical);
            for (int lateral = -lateralRange; lateral <= lateralRange; lateral++) {
                BlockKey key = impactTileKey(center, openSide, vertical, lateral);
                if (key.y() < world.getMinHeight() || key.y() >= world.getMaxHeight()) {
                    continue;
                }
                Location panelLocation = panelLocation(world, key, openSide);
                int distance = Math.abs(vertical) + Math.abs(lateral);
                panels.add(new BorderVisualPanel(
                        new BorderVisualKey(world.getUID(), key.x(), key.y(), key.z(), openSide[0], openSide[1]),
                        panelLocation,
                        impactAlphaForDistance(distance, radius)
                ));
            }
        }
        return panels;
    }

    private int[] impactOpenSide(World world, Location from, Location impact, BlockKey center, GuardMode mode) {
        if (from != null && from.getWorld() != null && from.getWorld().equals(world)) {
            double dx = from.getX() - (center.x() + 0.5D);
            double dz = from.getZ() - (center.z() + 0.5D);
            int[] preferred = Math.abs(dx) >= Math.abs(dz)
                    ? new int[]{dx < 0.0D ? -1 : 1, 0}
                    : new int[]{0, dz < 0.0D ? -1 : 1};
            if (isOpenSide(world, center, preferred, mode)) {
                return preferred;
            }
        }

        for (int[] openSide : HORIZONTAL_DIRECTIONS) {
            if (isOpenSide(world, center, openSide, mode)) {
                return openSide;
            }
        }

        if (from != null && from.getWorld() != null && from.getWorld().equals(world)) {
            double dx = from.getX() - impact.getX();
            double dz = from.getZ() - impact.getZ();
            return Math.abs(dx) >= Math.abs(dz)
                    ? new int[]{dx < 0.0D ? -1 : 1, 0}
                    : new int[]{0, dz < 0.0D ? -1 : 1};
        }
        return new int[]{0, 1};
    }

    private boolean isOpenSide(World world, BlockKey key, int[] openSide, GuardMode mode) {
        Location neighbor = new Location(world, key.x() + openSide[0], key.y(), key.z() + openSide[1]);
        return isOpenSide(neighbor, mode, System.currentTimeMillis(), plugin.settings().worldGuardBorderCacheSeconds() * 1000L);
    }

    private boolean isOpenSide(Location neighbor, GuardMode mode, long now, long ttlMillis) {
        return mode == GuardMode.NO_PVP
                ? !isNoPvp(neighbor, now, ttlMillis)
                : isEnclosedRegion(neighbor, now, ttlMillis);
    }

    private BlockKey impactTileKey(BlockKey center, int[] openSide, int vertical, int lateral) {
        int x = center.x();
        int z = center.z();
        if (openSide[0] != 0) {
            z += lateral;
        } else {
            x += lateral;
        }
        return new BlockKey(center.worldId(), center.worldName(), x, center.y() + vertical, z);
    }

    private BlockKey playerVisualTileKey(BlockKey center, int y, int[] openSide, int lateral) {
        int x = center.x();
        int z = center.z();
        if (openSide[0] != 0) {
            z += lateral;
        } else {
            x += lateral;
        }
        return new BlockKey(center.worldId(), center.worldName(), x, y, z);
    }

    private boolean isInVisualDirection(Location eye, Vector look, boolean hasLook, Location panelLocation) {
        if (!hasLook) {
            return true;
        }
        Vector toward = panelLocation.toVector().subtract(eye.toVector());
        toward.setY(0.0D);
        if (toward.lengthSquared() < 0.16D) {
            return true;
        }
        return toward.normalize().dot(look) >= VISUAL_CONE_DOT;
    }

    private Location panelLocation(World world, BlockKey key, int[] openSide) {
        double x = key.x() + 0.5D;
        double z = key.z() + 0.5D;
        float yaw = 0.0F;
        if (openSide[0] > 0) {
            x = key.x() + 1.0D + VISUAL_FACE_OFFSET;
            yaw = -90.0F;
        } else if (openSide[0] < 0) {
            x = key.x() - VISUAL_FACE_OFFSET;
            yaw = 90.0F;
        } else if (openSide[1] > 0) {
            z = key.z() + 1.0D + VISUAL_FACE_OFFSET;
            yaw = 0.0F;
        } else if (openSide[1] < 0) {
            z = key.z() - VISUAL_FACE_OFFSET;
            yaw = 180.0F;
        }
        return new Location(world, x, key.y(), z, yaw, 0.0F);
    }

    private int alphaForDistance(double distance, double visualRange) {
        if (visualRange <= 1.0D) {
            return VISUAL_NEAR_ALPHA;
        }
        double progress = Math.max(0.0D, Math.min(1.0D, (distance - 1.0D) / (visualRange - 1.0D)));
        return (int) Math.round(VISUAL_NEAR_ALPHA + (VISUAL_FAR_ALPHA - VISUAL_NEAR_ALPHA) * progress);
    }

    private int playerVisualAlpha(int centerAlpha, int tileDistance) {
        double progress = Math.max(0.0D, Math.min(1.0D, tileDistance / (double) PLAYER_VISUAL_RADIUS));
        double smoothProgress = progress * progress * (3.0D - (2.0D * progress));
        return Math.max(0, (int) Math.round(centerAlpha * (1.0D - (0.55D * smoothProgress))));
    }

    private int impactAlphaForDistance(double distance, double visualRange) {
        if (visualRange <= 0.0D) {
            return PEARL_IMPACT_NEAR_ALPHA;
        }
        double progress = Math.max(0.0D, Math.min(1.0D, distance / visualRange));
        return (int) Math.round(PEARL_IMPACT_NEAR_ALPHA + (PEARL_IMPACT_FAR_ALPHA - PEARL_IMPACT_NEAR_ALPHA) * progress);
    }

    private void syncVisualDisplays(Player player, Set<BorderVisualPanel> targetPanels, Color color) {
        Map<BorderVisualKey, BorderVisualDisplay> displays = visualDisplays.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>());
        int colorKey = colorKey(color);
        for (BorderVisualPanel panel : targetPanels) {
            BorderVisualDisplay current = displays.get(panel.key());
            TextDisplay display = current == null ? null : current.display();
            if (display == null || !display.isValid()) {
                display = spawnVisualDisplay(player, panel, color, playerBorderViewRange());
                if (display != null) {
                    displays.put(panel.key(), new BorderVisualDisplay(display, panel.alpha(), colorKey));
                }
                continue;
            }
            if (current.alpha() == panel.alpha() && current.colorKey() == colorKey) {
                continue;
            }
            updateVisualDisplay(display, panel, color);
            displays.put(panel.key(), new BorderVisualDisplay(display, panel.alpha(), colorKey));
        }

        Set<BorderVisualKey> targetKeys = new HashSet<>();
        for (BorderVisualPanel panel : targetPanels) {
            targetKeys.add(panel.key());
        }
        for (BorderVisualKey key : new HashSet<>(displays.keySet())) {
            if (!targetKeys.contains(key)) {
                BorderVisualDisplay display = displays.remove(key);
                removeDisplay(display == null ? null : display.display());
            }
        }
        if (displays.isEmpty()) {
            visualDisplays.remove(player.getUniqueId());
        }
    }

    private int colorKey(Color color) {
        return (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
    }

    private TextDisplay spawnVisualDisplay(Player player, BorderVisualPanel panel, Color color, float viewRange) {
        if (!player.getWorld().equals(panel.location().getWorld())) {
            return null;
        }
        TextDisplay display = player.getWorld().spawn(panel.location(), TextDisplay.class, entity -> {
            updateVisualDisplay(entity, panel, color);
            entity.setPersistent(false);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setSilent(true);
            entity.setVisibleByDefault(false);
            entity.setViewRange(viewRange);
            entity.setShadowRadius(0.0F);
            entity.setShadowStrength(0.0F);
            entity.setBrightness(new Display.Brightness(15, 15));
        });
        player.showEntity(plugin, display);
        return display;
    }

    private float playerBorderViewRange() {
        return Math.max(PLAYER_VISUAL_MIN_VIEW_RANGE, (float) plugin.settings().worldGuardBorderVisualRange() + 4.0F);
    }

    private float pearlImpactViewRange(Player player, BorderVisualPanel panel) {
        double distance = player.getLocation().distance(panel.location());
        return Math.max(PEARL_IMPACT_MIN_VIEW_RANGE, (float) distance + plugin.settings().worldGuardBorderPearlImpactRadius() + 4.0F);
    }

    private void updateVisualDisplay(TextDisplay display, BorderVisualPanel panel, Color color) {
        updateVisualDisplay(display, panel, color, panel.alpha());
    }

    private void updateVisualDisplay(TextDisplay display, BorderVisualPanel panel, Color color, int alpha) {
        display.teleport(panel.location());
        display.setText(VISUAL_TEXT);
        display.setLineWidth(1);
        display.setTextOpacity((byte) 0);
        display.setBackgroundColor(Color.fromARGB(alpha, color.getRed(), color.getGreen(), color.getBlue()));
        display.setDefaultBackground(false);
        display.setSeeThrough(false);
        display.setBillboard(Display.Billboard.FIXED);
        display.setDisplayWidth(1.0F);
        display.setDisplayHeight(1.0F);
        display.setTransformation(VISUAL_TRANSFORM);
    }

    private void removeImpactDisplays(List<ImpactDisplay> displays) {
        for (ImpactDisplay impactDisplay : displays) {
            TextDisplay display = impactDisplay.display();
            temporaryVisualDisplays.remove(display);
            removeDisplay(display);
        }
        displays.clear();
    }

    private void removeVisualDisplays(UUID playerId) {
        Map<BorderVisualKey, BorderVisualDisplay> displays = visualDisplays.remove(playerId);
        if (displays == null) {
            return;
        }
        for (BorderVisualDisplay display : displays.values()) {
            removeDisplay(display.display());
        }
    }

    private void removeDisplay(TextDisplay display) {
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    private enum GuardMode {
        NO_PVP,
        ENCLOSED_REGION
    }

    private record BorderScanKey(UUID worldId, int x, int y, int z, int radius, int height, boolean onlyAir, GuardMode mode) {
    }

    private record BorderScan(BorderScanKey key, Set<BlockKey> blocks, long expiresAtMillis) {
    }

    private record CachedFlag(boolean value, long expiresAtMillis) {
    }

    private record CachedEscape(double x, double z, boolean present, long expiresAtMillis) {
        private static CachedEscape from(Vector direction, long expiresAtMillis) {
            if (direction == null || direction.lengthSquared() < 0.0001D) {
                return new CachedEscape(0.0D, 0.0D, false, expiresAtMillis);
            }
            Vector normalized = direction.clone();
            normalized.setY(0.0D);
            if (normalized.lengthSquared() < 0.0001D) {
                return new CachedEscape(0.0D, 0.0D, false, expiresAtMillis);
            }
            normalized.normalize();
            return new CachedEscape(normalized.getX(), normalized.getZ(), true, expiresAtMillis);
        }

        private BorderPush toBorderPush() {
            if (!present) {
                return null;
            }
            return new BorderPush(new Vector(x, 0.0D, z));
        }
    }

    private record BorderVisualKey(UUID worldId, int x, int y, int z, int faceX, int faceZ) {
    }

    private record BorderVisualPanel(BorderVisualKey key, Location location, int alpha) {
    }

    private record BorderVisualDisplay(TextDisplay display, int alpha, int colorKey) {
    }

    private record ImpactDisplay(TextDisplay display, BorderVisualPanel panel) {
    }

    private record PlayerVisualCenter(BlockKey key, int faceX, int faceZ, double distance) {
    }

    public record BorderPush(Vector direction) {
    }
}
