package me.foesio.foCombatTag.api.event;

import me.foesio.foCombatTag.api.CombatTagRemoveReason;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class CombatTagRemoveEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final CombatTagRemoveReason reason;

    public CombatTagRemoveEvent(Player player, CombatTagRemoveReason reason) {
        this.player = player;
        this.reason = reason;
    }

    public Player getPlayer() {
        return player;
    }

    public CombatTagRemoveReason getReason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
