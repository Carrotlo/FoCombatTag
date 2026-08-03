package me.foesio.foCombatTag.api.event;

import me.foesio.foCombatTag.api.CombatTagCause;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class CombatTagApplyEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String opponentName;
    private final CombatTagCause cause;
    private final boolean refresh;
    private int durationSeconds;
    private boolean cancelled;

    public CombatTagApplyEvent(Player player, String opponentName, CombatTagCause cause, int durationSeconds, boolean refresh) {
        this.player = player;
        this.opponentName = opponentName;
        this.cause = cause;
        this.durationSeconds = durationSeconds;
        this.refresh = refresh;
    }

    public Player getPlayer() {
        return player;
    }

    public String getOpponentName() {
        return opponentName;
    }

    public CombatTagCause getCause() {
        return cause;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(int durationSeconds) {
        this.durationSeconds = Math.max(1, durationSeconds);
    }

    public boolean isRefresh() {
        return refresh;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
