package me.foesio.foCombatTag.hook;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.foesio.foCombatTag.FoCombatTag;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class FoCombatTagPlaceholderExpansion extends PlaceholderExpansion {
    private final FoCombatTag plugin;

    public FoCombatTagPlaceholderExpansion(FoCombatTag plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "focombattag";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Carrotio";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        if (!(offlinePlayer instanceof Player player)) {
            return switch (params.toLowerCase()) {
                case "tagged" -> "false";
                case "remaining", "remaining_raw" -> "0";
                default -> null;
            };
        }
        boolean tagged = plugin.combatTags().isTagged(player);
        long remaining = tagged ? plugin.combatTags().remainingSeconds(player) : 0L;
        return switch (params.toLowerCase()) {
            case "tagged" -> String.valueOf(tagged);
            case "remaining" -> remaining + "s";
            case "remaining_raw" -> String.valueOf(remaining);
            default -> null;
        };
    }
}
