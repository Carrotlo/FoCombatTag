package me.foesio.foCombatTag.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired for a tagged player before FoCombatTag applies its logout kill, broadcast, or commands. */
public final class CombatLogoutPenaltyEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final boolean killConfigured;
    private boolean cancelled;

    public CombatLogoutPenaltyEvent(Player player, boolean killConfigured) {
        this.player = player;
        this.killConfigured = killConfigured;
    }

    public Player getPlayer() { return player; }

    /** Whether FoCombatTag is configured to kill this player on combat logout. */
    public boolean isKillConfigured() { return killConfigured; }

    /** Cancelling skips all FoCombatTag logout penalties, but the player's tag is still removed. */
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
