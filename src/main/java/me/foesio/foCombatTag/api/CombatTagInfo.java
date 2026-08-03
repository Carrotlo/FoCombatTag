package me.foesio.foCombatTag.api;

import java.util.UUID;

public record CombatTagInfo(
        UUID playerId,
        String playerName,
        String opponentName,
        long remainingSeconds,
        CombatTagCause cause
) {
}
