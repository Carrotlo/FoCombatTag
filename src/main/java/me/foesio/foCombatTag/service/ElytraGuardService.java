package me.foesio.foCombatTag.service;

import me.foesio.core.item.FoItemStacks;
import me.foesio.foCombatTag.FoCombatTag;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public final class ElytraGuardService {
    private static final int MAIN_INVENTORY_FIRST_SLOT = 9;
    private static final int MAIN_INVENTORY_LAST_SLOT = 35;

    private final FoCombatTag plugin;

    public ElytraGuardService(FoCombatTag plugin) {
        this.plugin = plugin;
    }

    public void enforce(Player player) {
        if (!shouldBlock(player)) {
            return;
        }
        if (player.isGliding()) {
            player.setGliding(false);
        }
        unequip(player);
    }

    public void enforceNextTick(Player player) {
        plugin.core().scheduler().runForPlayer(player, () -> {
            if (player.isOnline()) {
                enforce(player);
            }
        });
    }

    public boolean shouldBlock(Player player) {
        return plugin.settings().disableElytraWhileTagged() && plugin.combatTags().isTagged(player);
    }

    public boolean isElytra(ItemStack item) {
        return item != null && item.getType() == Material.ELYTRA;
    }

    public void storeBlockedElytra(Player player, ItemStack item) {
        if (!isElytra(item)) {
            return;
        }
        ItemStack elytra = FoItemStacks.cloneItem(item);
        if (!moveToMainInventory(player.getInventory(), elytra)) {
            plugin.elytraVault().store(player, elytra);
        }
        player.updateInventory();
    }

    private void unequip(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack chestplate = inventory.getChestplate();
        if (!isElytra(chestplate)) {
            return;
        }

        ItemStack elytra = FoItemStacks.cloneItem(chestplate);
        inventory.setChestplate(null);
        storeBlockedElytra(player, elytra);
    }

    private boolean moveToMainInventory(PlayerInventory inventory, ItemStack item) {
        for (int slot = MAIN_INVENTORY_FIRST_SLOT; slot <= MAIN_INVENTORY_LAST_SLOT; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current == null || current.getType().isAir()) {
                inventory.setItem(slot, FoItemStacks.cloneItem(item));
                return true;
            }
        }
        return false;
    }
}
