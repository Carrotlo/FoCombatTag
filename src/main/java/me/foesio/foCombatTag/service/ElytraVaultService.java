package me.foesio.foCombatTag.service;

import me.foesio.core.config.ResourceFiles;
import me.foesio.core.item.FoItemStacks;
import me.foesio.foCombatTag.FoCombatTag;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ElytraVaultService {
    private static final long REMINDER_INTERVAL_TICKS = 20L * 60L * 5L;

    private final FoCombatTag plugin;
    private final File file;
    private FileConfiguration vault;
    private BukkitTask reminderTask;

    public ElytraVaultService(FoCombatTag plugin) {
        this.plugin = plugin;
        this.file = ResourceFiles.dataFile(plugin, "elytra-vault.yml");
    }

    public void start() {
        reload();
        if (reminderTask != null) {
            reminderTask.cancel();
        }
        reminderTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sendReminders, REMINDER_INTERVAL_TICKS, REMINDER_INTERVAL_TICKS);
    }

    public void shutdown() {
        if (reminderTask != null) {
            reminderTask.cancel();
            reminderTask = null;
        }
        save();
    }

    public void reload() {
        vault = YamlConfiguration.loadConfiguration(file);
    }

    public boolean hasItems(Player player) {
        return itemCount(player.getUniqueId()) > 0;
    }

    public int itemCount(UUID playerId) {
        return items(playerId).size();
    }

    public void store(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        List<ItemStack> items = items(playerId);
        items.add(FoItemStacks.cloneItem(item));
        setItems(playerId, player.getName(), items);
        save();
        plugin.messages().send(player, "elytra-vault-stored", Map.of(
                "{count}", String.valueOf(items.size())
        ));
        plugin.sounds().play(player, "elytra.stored");
    }

    public ClaimResult claim(Player player) {
        UUID playerId = player.getUniqueId();
        List<ItemStack> stored = items(playerId);
        if (stored.isEmpty()) {
            return new ClaimResult(0, 0);
        }

        int claimed = 0;
        List<ItemStack> remaining = new ArrayList<>();
        for (ItemStack item : stored) {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(FoItemStacks.cloneItem(item));
            if (leftovers.isEmpty()) {
                claimed++;
                continue;
            }
            remaining.addAll(FoItemStacks.cloneItems(leftovers.values()));
        }

        setItems(playerId, player.getName(), remaining);
        save();
        player.updateInventory();
        return new ClaimResult(claimed, remaining.size());
    }

    private void sendReminders() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            int count = itemCount(player.getUniqueId());
            if (count <= 0) {
                continue;
            }
            plugin.messages().send(player, "elytra-vault-reminder", Map.of(
                    "{count}", String.valueOf(count)
            ));
        }
    }

    private List<ItemStack> items(UUID playerId) {
        List<?> raw = vault.getList(path(playerId) + ".items", List.of());
        List<ItemStack> items = new ArrayList<>();
        for (Object value : raw) {
            if (value instanceof ItemStack item && !item.getType().isAir()) {
                items.add(FoItemStacks.cloneItem(item));
            }
        }
        return items;
    }

    private void setItems(UUID playerId, String playerName, List<ItemStack> items) {
        String path = path(playerId);
        if (items.isEmpty()) {
            vault.set(path, null);
            return;
        }
        vault.set(path + ".name", playerName);
        vault.set(path + ".items", FoItemStacks.cloneItems(items));
    }

    private String path(UUID playerId) {
        return "players." + playerId;
    }

    private void save() {
        try {
            vault.save(file);
        } catch (IOException exception) {
            plugin.logWarning("Failed to save elytra vault: " + exception.getMessage());
        }
    }

    public record ClaimResult(int claimed, int remaining) {
    }
}
