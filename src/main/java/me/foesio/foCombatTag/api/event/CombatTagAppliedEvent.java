package me.foesio.foCombatTag.api.event;

import me.foesio.foCombatTag.api.CombatTagDetails;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired after a tag is stored and scheduled, before player notifications. */
public final class CombatTagAppliedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final CombatTagDetails tag;
    private final boolean refresh;

    public CombatTagAppliedEvent(Player player, CombatTagDetails tag, boolean refresh) {
        this.player = player;
        this.tag = tag;
        this.refresh = refresh;
    }

    public Player getPlayer() { return player; }
    /** Immutable snapshot of the tag as applied. */
    public CombatTagDetails getTag() { return tag; }
    public boolean isRefresh() { return refresh; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
