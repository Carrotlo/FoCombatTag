package me.foesio.foCombatTag.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired only for a tagged player's command that FoCombatTag would otherwise block. */
public final class CombatTaggedCommandBlockEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final String command;
    private boolean cancelled;

    public CombatTaggedCommandBlockEvent(Player player, String command) {
        this.player = player;
        this.command = command;
    }

    public Player getPlayer() { return player; }
    /** The original command line, including its leading slash and arguments. */
    public String getCommand() { return command; }
    /** Cancelling exempts this command from FoCombatTag's restriction. */
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
