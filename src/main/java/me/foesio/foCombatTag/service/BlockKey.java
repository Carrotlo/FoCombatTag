package me.foesio.foCombatTag.service;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

public record BlockKey(UUID worldId, String worldName, int x, int y, int z) {
    public static BlockKey from(Location location) {
        World world = location.getWorld();
        return new BlockKey(world.getUID(), world.getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public Location toLocation() {
        World world = Bukkit.getWorld(worldId);
        if (world == null) {
            world = Bukkit.getWorld(worldName);
        }
        return world == null ? null : new Location(world, x, y, z);
    }
}
