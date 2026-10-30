package me.foesio.foCombatTag.api.event;

import me.foesio.foCombatTag.api.CombatTagCause;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** Final cancellable decision after CombatTagApplyEvent, immediately before a tag is stored. */
public final class CombatTagCommitEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final UUID opponentId;
    private final String opponentName;
    private final CombatTagCause cause;
    private final int durationSeconds;
    private final boolean refresh;
    private boolean cancelled;

    public CombatTagCommitEvent(Player player, UUID opponentId, String opponentName,
                                CombatTagCause cause, int durationSeconds, boolean refresh) {
        this.player = player;
        this.opponentId = opponentId;
        this.opponentName = opponentName;
        this.cause = cause;
        this.durationSeconds = durationSeconds;
        this.refresh = refresh;
    }

    public Player getPlayer() { return player; }
    public UUID getOpponentId() { return opponentId; }
    public String getOpponentName() { return opponentName; }
    public CombatTagCause getCause() { return cause; }
    public int getDurationSeconds() { return durationSeconds; }
    public boolean isRefresh() { return refresh; }
    /** Cancelling leaves the previous tag, if any, unchanged. */
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
