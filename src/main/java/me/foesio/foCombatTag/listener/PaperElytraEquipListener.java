package me.foesio.foCombatTag.listener;

import io.papermc.paper.event.player.PlayerSwapWithEquipmentSlotEvent;
import me.foesio.foCombatTag.FoCombatTag;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public final class PaperElytraEquipListener implements Listener {
    private final FoCombatTag plugin;

    public PaperElytraEquipListener(FoCombatTag plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapWithEquipmentSlot(PlayerSwapWithEquipmentSlotEvent event) {
        if (event.getSlot() != EquipmentSlot.CHEST || !plugin.elytraGuard().shouldBlock(event.getPlayer())) {
            return;
        }
        if (plugin.elytraGuard().isElytra(event.getItemInHand())) {
            event.setCancelled(true);
            storeBlockedHandElytra(event);
            plugin.elytraGuard().enforceNextTick(event.getPlayer());
        }
    }

    private void storeBlockedHandElytra(PlayerSwapWithEquipmentSlotEvent event) {
        PlayerInventory inventory = event.getPlayer().getInventory();
        ItemStack item = event.getItemInHand();
        ItemStack mainHand = inventory.getItemInMainHand();
        if (isSameElytra(mainHand, item)) {
            inventory.setItemInMainHand(null);
            plugin.elytraGuard().storeBlockedElytra(event.getPlayer(), mainHand);
            return;
        }
        ItemStack offHand = inventory.getItemInOffHand();
        if (isSameElytra(offHand, item)) {
            inventory.setItemInOffHand(null);
            plugin.elytraGuard().storeBlockedElytra(event.getPlayer(), offHand);
        }
    }

    private boolean isSameElytra(ItemStack source, ItemStack target) {
        return plugin.elytraGuard().isElytra(source) && source.isSimilar(target);
    }
}
