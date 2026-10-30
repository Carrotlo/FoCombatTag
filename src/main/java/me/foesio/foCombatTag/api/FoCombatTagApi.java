package me.foesio.foCombatTag.api;

import me.foesio.foCombatTag.FoCombatTag;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class FoCombatTagApi {
    private static FoCombatTagApi instance;

    private final FoCombatTag plugin;

    private FoCombatTagApi(FoCombatTag plugin) {
        this.plugin = plugin;
    }

    public static Optional<FoCombatTagApi> get() {
        return Optional.ofNullable(instance);
    }

    public static boolean isAvailable() {
        return instance != null;
    }

    public static void install(FoCombatTag plugin) {
        instance = new FoCombatTagApi(plugin);
    }

    public static void uninstall() {
        instance = null;
    }

    public boolean isTagged(Player player) {
        return plugin.combatTags().isTagged(player);
    }

    public boolean isTagged(UUID playerId) {
        return plugin.combatTags().isTagged(playerId);
    }

    public long getRemainingSeconds(Player player) {
        return plugin.combatTags().remainingSeconds(player);
    }

    public long getRemainingSeconds(UUID playerId) {
        return plugin.combatTags().remainingSeconds(playerId);
    }

    public boolean tag(Player player) {
        return tag(player, "API", plugin.settings().combatDurationSeconds());
    }

    public boolean tag(Player player, String sourceName) {
        return tag(player, sourceName, plugin.settings().combatDurationSeconds());
    }

    public boolean tag(Player player, String sourceName, int durationSeconds) {
        return plugin.combatTags().tagApi(player, sourceName, durationSeconds);
    }

    public boolean tagCombat(Player attacker, Player victim) {
        return plugin.combatTags().tagCombat(attacker, victim) > 0;
    }

    public boolean untag(Player player) {
        return plugin.combatTags().untag(player, CombatTagRemoveReason.API, true);
    }

    public List<CombatTagInfo> getActiveTags() {
        return plugin.combatTags().apiSnapshot();
    }

    /** Returns a snapshot of an active tag, or empty when none exists. Call from the server gameplay thread. */
    public Optional<CombatTagDetails> getTag(UUID playerId) {
        return plugin.combatTags().getTag(playerId);
    }

    public boolean isNoPvpRegion(Location location) {
        return plugin.worldGuardHook().isNoPvp(location);
    }

    public FoCombatTag plugin() {
        return plugin;
    }
}
