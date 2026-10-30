package me.foesio.foCombatTag.api;

import java.util.UUID;

/** A snapshot of one active, in-memory combat tag at the time it was queried. */
public record CombatTagDetails(
        UUID playerId,
        String playerName,
        UUID opponentId,
        String opponentName,
        String sourceName,
        long expiresAtMillis,
        long remainingSeconds,
        CombatTagCause cause
) {
}
