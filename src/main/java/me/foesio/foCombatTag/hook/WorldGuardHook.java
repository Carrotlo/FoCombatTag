package me.foesio.foCombatTag.hook;

import me.foesio.foCombatTag.FoCombatTag;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class WorldGuardHook {
    private final FoCombatTag plugin;
    private boolean available;
    private boolean warnedQueryFailure;
    private Method adaptMethod;
    private Method adaptWorldMethod;
    private Method testStateMethod;
    private Method applicableRegionsMethod;
    private Method regionContainerGetMethod;
    private Method regionGetFlagMethod;
    private Method regionGetIdMethod;
    private Method regionGetMinimumPointMethod;
    private Method regionGetMaximumPointMethod;
    private Class<?> flagClass;
    private Object regionContainer;
    private Object query;
    private Object stateFlagArray;
    private Object pvpFlag;

    public WorldGuardHook(FoCombatTag plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        reload(true);
    }

    public void reload(boolean logStatus) {
        available = false;
        warnedQueryFailure = false;
        regionContainer = null;
        query = null;
        stateFlagArray = null;
        pvpFlag = null;
        flagClass = null;
        adaptWorldMethod = null;
        regionContainerGetMethod = null;
        regionGetFlagMethod = null;
        regionGetIdMethod = null;
        regionGetMinimumPointMethod = null;
        regionGetMaximumPointMethod = null;
        if (!plugin.settings().worldGuardChecksEnabled()) {
            return;
        }
        Plugin worldGuard = plugin.getServer().getPluginManager().getPlugin("WorldGuard");
        if (worldGuard == null || !worldGuard.isEnabled()) {
            if (logStatus) {
                plugin.logWarning("WorldGuard not found. No-PVP region guard disabled.");
            }
            return;
        }
        try {
            Class<?> worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Class<?> bukkitAdapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Class<?> flagsClass = Class.forName("com.sk89q.worldguard.protection.flags.Flags");
            Class<?> stateFlagClass = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag");
            Class<?> protectedRegionClass = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion");
            flagClass = Class.forName("com.sk89q.worldguard.protection.flags.Flag");

            Object worldGuardInstance = worldGuardClass.getMethod("getInstance").invoke(null);
            Object platform = worldGuardInstance.getClass().getMethod("getPlatform").invoke(worldGuardInstance);
            regionContainer = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            query = regionContainer.getClass().getMethod("createQuery").invoke(regionContainer);

            Field pvpField = flagsClass.getField("PVP");
            pvpFlag = pvpField.get(null);
            stateFlagArray = Array.newInstance(stateFlagClass, 1);
            Array.set(stateFlagArray, 0, pvpFlag);

            adaptMethod = bukkitAdapterClass.getMethod("adapt", Location.class);
            adaptWorldMethod = bukkitAdapterClass.getMethod("adapt", World.class);
            regionContainerGetMethod = regionContainer.getClass().getMethod("get", adaptWorldMethod.getReturnType());
            testStateMethod = findTestStateMethod(query.getClass());
            applicableRegionsMethod = findApplicableRegionsMethod(query.getClass());
            regionGetFlagMethod = protectedRegionClass.getMethod("getFlag", flagClass);
            regionGetIdMethod = protectedRegionClass.getMethod("getId");
            regionGetMinimumPointMethod = protectedRegionClass.getMethod("getMinimumPoint");
            regionGetMaximumPointMethod = protectedRegionClass.getMethod("getMaximumPoint");
            available = true;
            if (logStatus) {
                plugin.logInfo("WorldGuard hook enabled.");
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (logStatus) {
                plugin.logWarning("Failed to hook WorldGuard: " + exception.getMessage());
            }
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isNoPvp(Location location) {
        if (!available || location == null || location.getWorld() == null) {
            return false;
        }
        if (!plugin.settings().isWorldEnabled(location.getWorld())) {
            return false;
        }
        try {
            Object worldEditLocation = adaptMethod.invoke(null, location);
            Boolean pvpAllowed = (Boolean) testStateMethod.invoke(query, worldEditLocation, null, stateFlagArray);
            return Boolean.FALSE.equals(pvpAllowed) && hasExplicitPvpDeny(worldEditLocation);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (!warnedQueryFailure) {
                plugin.logWarning("WorldGuard PVP flag query failed: " + exception.getMessage());
                warnedQueryFailure = true;
            }
            return false;
        }
    }

    public Location noPvpRegionCenter(Location location) {
        if (!available || location == null || location.getWorld() == null) {
            return null;
        }
        try {
            Object worldEditLocation = adaptMethod.invoke(null, location);
            Object regions = applicableRegionsMethod.invoke(query, worldEditLocation);
            if (!(regions instanceof Iterable<?> iterable)) {
                return null;
            }
            Location best = null;
            double bestDistanceSquared = Double.MAX_VALUE;
            for (Object region : iterable) {
                if (!isExplicitPvpDeny(region)) {
                    continue;
                }
                Location center = regionCenter(location, region);
                double distanceSquared = horizontalDistanceSquared(location, center);
                if (best == null || distanceSquared < bestDistanceSquared) {
                    best = center;
                    bestDistanceSquared = distanceSquared;
                }
            }
            return best;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (!warnedQueryFailure) {
                plugin.logWarning("WorldGuard region center query failed: " + exception.getMessage());
                warnedQueryFailure = true;
            }
            return null;
        }
    }

    public Location regionCenter(Location location, Collection<String> regionIds) {
        if (!available || location == null || location.getWorld() == null || regionIds == null || regionIds.isEmpty()) {
            return null;
        }
        try {
            Object worldEditLocation = adaptMethod.invoke(null, location);
            Object regions = applicableRegionsMethod.invoke(query, worldEditLocation);
            if (!(regions instanceof Iterable<?> iterable)) {
                return null;
            }
            Location best = null;
            double bestDistanceSquared = Double.MAX_VALUE;
            for (Object region : iterable) {
                Object id = regionGetIdMethod.invoke(region);
                if (id == null || !regionIds.contains(id.toString().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                Location center = regionCenter(location, region);
                double distanceSquared = horizontalDistanceSquared(location, center);
                if (best == null || distanceSquared < bestDistanceSquared) {
                    best = center;
                    bestDistanceSquared = distanceSquared;
                }
            }
            return best;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (!warnedQueryFailure) {
                plugin.logWarning("WorldGuard selected region center query failed: " + exception.getMessage());
                warnedQueryFailure = true;
            }
            return null;
        }
    }

    public Vector noPvpEscapeDirection(Location location) {
        if (!available || location == null || location.getWorld() == null) {
            return null;
        }
        try {
            Object worldEditLocation = adaptMethod.invoke(null, location);
            Object regions = applicableRegionsMethod.invoke(query, worldEditLocation);
            if (!(regions instanceof Iterable<?> iterable)) {
                return null;
            }

            boolean found = false;
            double east = 0.0D;
            double west = 0.0D;
            double south = 0.0D;
            double north = 0.0D;
            for (Object region : iterable) {
                if (!isExplicitPvpDeny(region)) {
                    continue;
                }
                RegionBounds bounds = regionBounds(region);
                east = Math.max(east, Math.max(0.0D, bounds.maxX() - location.getX()));
                west = Math.max(west, Math.max(0.0D, location.getX() - bounds.minX()));
                south = Math.max(south, Math.max(0.0D, bounds.maxZ() - location.getZ()));
                north = Math.max(north, Math.max(0.0D, location.getZ() - bounds.minZ()));
                found = true;
            }
            if (!found) {
                return null;
            }

            double nearest = east;
            Vector direction = new Vector(1.0D, 0.0D, 0.0D);
            if (west < nearest) {
                nearest = west;
                direction = new Vector(-1.0D, 0.0D, 0.0D);
            }
            if (south < nearest) {
                nearest = south;
                direction = new Vector(0.0D, 0.0D, 1.0D);
            }
            if (north < nearest) {
                direction = new Vector(0.0D, 0.0D, -1.0D);
            }
            return direction;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (!warnedQueryFailure) {
                plugin.logWarning("WorldGuard region escape query failed: " + exception.getMessage());
                warnedQueryFailure = true;
            }
            return null;
        }
    }

    private boolean hasExplicitPvpDeny(Object worldEditLocation) throws ReflectiveOperationException {
        Object regions = applicableRegionsMethod.invoke(query, worldEditLocation);
        if (!(regions instanceof Iterable<?> iterable)) {
            return true;
        }
        for (Object region : iterable) {
            if (isExplicitPvpDeny(region)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExplicitPvpDeny(Object region) throws ReflectiveOperationException {
        Object value = regionGetFlagMethod.invoke(region, pvpFlag);
        return value != null && value.toString().equalsIgnoreCase("DENY");
    }

    private Location regionCenter(Location base, Object region) throws ReflectiveOperationException {
        RegionBounds bounds = regionBounds(region);
        double x = (bounds.minX() + bounds.maxX()) / 2.0D;
        double y = (bounds.minY() + bounds.maxY()) / 2.0D;
        double z = (bounds.minZ() + bounds.maxZ()) / 2.0D;
        return new Location(base.getWorld(), x, y, z);
    }

    private RegionBounds regionBounds(Object region) throws ReflectiveOperationException {
        Object minimum = regionGetMinimumPointMethod.invoke(region);
        Object maximum = regionGetMaximumPointMethod.invoke(region);
        return new RegionBounds(
                coordinate(minimum, "X"),
                coordinate(maximum, "X") + 1.0D,
                coordinate(minimum, "Y"),
                coordinate(maximum, "Y") + 1.0D,
                coordinate(minimum, "Z"),
                coordinate(maximum, "Z") + 1.0D
        );
    }

    private double coordinate(Object vector, String axis) throws ReflectiveOperationException {
        for (String methodName : new String[]{"getBlock" + axis, "get" + axis, axis.toLowerCase(Locale.ROOT)}) {
            try {
                Object value = vector.getClass().getMethod(methodName).invoke(vector);
                if (value instanceof Number number) {
                    return number.doubleValue();
                }
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException("BlockVector3 coordinate " + axis);
    }

    private double horizontalDistanceSquared(Location left, Location right) {
        double x = left.getX() - right.getX();
        double z = left.getZ() - right.getZ();
        return x * x + z * z;
    }

    public boolean hasAnyRegion(Location location, Collection<String> regionIds) {
        if (!available || location == null || location.getWorld() == null || regionIds == null || regionIds.isEmpty()) {
            return false;
        }
        try {
            Object worldEditLocation = adaptMethod.invoke(null, location);
            Object regions = applicableRegionsMethod.invoke(query, worldEditLocation);
            if (!(regions instanceof Iterable<?> iterable)) {
                return false;
            }
            for (Object region : iterable) {
                Object id = regionGetIdMethod.invoke(region);
                if (id != null && regionIds.contains(id.toString().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (!warnedQueryFailure) {
                plugin.logWarning("WorldGuard region query failed: " + exception.getMessage());
                warnedQueryFailure = true;
            }
            return false;
        }
    }

    public Set<String> regionIdsAt(Location location) {
        if (!available || location == null || location.getWorld() == null) {
            return Set.of();
        }
        try {
            Object worldEditLocation = adaptMethod.invoke(null, location);
            Object regions = applicableRegionsMethod.invoke(query, worldEditLocation);
            if (!(regions instanceof Iterable<?> iterable)) {
                return Set.of();
            }
            Set<String> ids = new TreeSet<>();
            for (Object region : iterable) {
                Object id = regionGetIdMethod.invoke(region);
                if (id != null && !id.toString().isBlank()) {
                    ids.add(id.toString().toLowerCase(Locale.ROOT));
                }
            }
            return ids;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (!warnedQueryFailure) {
                plugin.logWarning("WorldGuard region ID query failed: " + exception.getMessage());
                warnedQueryFailure = true;
            }
            return Set.of();
        }
    }

    public Set<String> regionIds() {
        if (!available || regionContainer == null || adaptWorldMethod == null || regionContainerGetMethod == null) {
            return Set.of();
        }
        Set<String> ids = new TreeSet<>();
        try {
            for (World world : plugin.getServer().getWorlds()) {
                Object worldEditWorld = adaptWorldMethod.invoke(null, world);
                Object manager = regionContainerGetMethod.invoke(regionContainer, worldEditWorld);
                if (manager == null) {
                    continue;
                }
                Object regions = manager.getClass().getMethod("getRegions").invoke(manager);
                if (regions instanceof Map<?, ?> map) {
                    for (Object id : map.keySet()) {
                        if (id != null && !id.toString().isBlank()) {
                            ids.add(id.toString().toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (!warnedQueryFailure) {
                plugin.logWarning("WorldGuard region list query failed: " + exception.getMessage());
                warnedQueryFailure = true;
            }
        }
        return ids;
    }

    private Method findTestStateMethod(Class<?> queryClass) throws NoSuchMethodException {
        for (Method method : queryClass.getMethods()) {
            if (!method.getName().equals("testState")) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 3 && parameters[2].isArray()) {
                return method;
            }
        }
        throw new NoSuchMethodException("RegionQuery#testState");
    }

    private Method findApplicableRegionsMethod(Class<?> queryClass) throws NoSuchMethodException {
        for (Method method : queryClass.getMethods()) {
            if (method.getName().equals("getApplicableRegions") && method.getParameterTypes().length == 1) {
                return method;
            }
        }
        throw new NoSuchMethodException("RegionQuery#getApplicableRegions");
    }

    private record RegionBounds(double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
    }
}
