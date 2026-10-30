package me.foesio.foCombatTag.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/** Fired after an item is added to FoCombatTag's elytra vault and a disk save is attempted. */
public final class ElytraVaultStoreEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final ItemStack item;
    private final int totalStacks;
    private final boolean persisted;

    public ElytraVaultStoreEvent(Player player, ItemStack item, int totalStacks, boolean persisted) {
        this.player = player;
        this.item = item.clone();
        this.totalStacks = totalStacks;
        this.persisted = persisted;
    }

    public Player getPlayer() { return player; }
    /** Returns a defensive copy. */
    public ItemStack getItem() { return item.clone(); }
    public int getTotalStacks() { return totalStacks; }
    /** Whether the vault YAML save completed successfully. */
    public boolean isPersisted() { return persisted; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
