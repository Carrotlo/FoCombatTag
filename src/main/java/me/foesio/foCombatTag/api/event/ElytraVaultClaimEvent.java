package me.foesio.foCombatTag.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after at least one stored item is moved into a player's inventory. */
public final class ElytraVaultClaimEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final int transferredItems;
    private final int remainingStacks;
    private final boolean persisted;

    public ElytraVaultClaimEvent(Player player, int transferredItems, int remainingStacks, boolean persisted) {
        this.player = player;
        this.transferredItems = transferredItems;
        this.remainingStacks = remainingStacks;
        this.persisted = persisted;
    }

    public Player getPlayer() { return player; }
    /** Total item amount transferred; partial stack transfers count. */
    public int getTransferredItems() { return transferredItems; }
    public int getRemainingStacks() { return remainingStacks; }
    /** Whether the remaining vault contents were saved to YAML successfully. */
    public boolean isPersisted() { return persisted; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
