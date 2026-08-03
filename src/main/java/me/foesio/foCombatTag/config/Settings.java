package me.foesio.foCombatTag.config;

import me.foesio.core.config.ConfigValueLists;
import me.foesio.core.dialog.NativeDialogConfigDefaults;
import me.foesio.foCombatTag.FoCombatTag;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record Settings(
        int combatDurationSeconds,
        boolean killOnLogout,
        boolean disableElytraWhileTagged,
        boolean tagPlayerExplosions,
        PearlCooldownMode pearlCooldownMode,
        int pearlCooldownSeconds,
        Set<String> enabledWorlds,
        Set<String> disabledWorlds,
        Set<String> disabledWorldGuardRegions,
        boolean actionbarEnabled,
        boolean combatLogoutBroadcastEnabled,
        String combatLogoutBroadcastMessage,
        List<String> combatLogoutConsoleCommands,
        boolean blockCommandsWhileTagged,
        List<String> allowedCommandsWhileTagged,
        boolean worldGuardChecksEnabled,
        Set<String> enclosedWorldGuardRegions,
        boolean worldGuardBorderEnabled,
        boolean worldGuardBorderAdvancedVisualsEnabled,
        Material worldGuardBorderCollisionMaterial,
        Color worldGuardBorderVisualColor,
        int worldGuardBorderVisualRange,
        int worldGuardBorderRadius,
        int worldGuardBorderHeight,
        boolean worldGuardBorderOnlyAir,
        int worldGuardBorderCacheSeconds,
        double worldGuardBorderPushbackMultiplier,
        int worldGuardBorderPearlImpactRadius,
        int worldGuardBorderPearlImpactDurationTicks,
        boolean fileLogging
) {
    public static Settings load(FoCombatTag plugin) {
        FileConfiguration config = plugin.getConfig();
        FileConfiguration defaults = loadDefaults(plugin);
        Material borderCollisionMaterial = parseMaterial(plugin, getString(config, defaults, "worldguard.border.collision-material", "BARRIER"), Material.BARRIER);
        Color borderVisualColor = parseColor(plugin, getString(config, defaults, "worldguard.border.visual-color", "#ff3d5a"), Color.fromRGB(255, 61, 90));
        PearlCooldownMode pearlCooldownMode = getPearlCooldownMode(plugin, config, defaults);
        return new Settings(
                clamp(getInt(config, defaults, "combat.duration-seconds", 15), 1, 3600),
                getBoolean(config, defaults, "combat.kill-on-logout", true),
                getBoolean(config, defaults, "combat.disable-elytra-while-tagged", false),
                getBoolean(config, defaults, "combat.tag-player-explosions", true),
                pearlCooldownMode,
                clamp(getInt(config, defaults, "combat.pearl-cooldown.seconds", 5), 1, 3600),
                lowerStrings(config, defaults, "combat.enabled-worlds"),
                lowerStrings(config, defaults, "combat.disabled-worlds"),
                lowerStrings(config, defaults, "combat.disabled-worldguard-regions"),
                getBoolean(config, defaults, "actionbar.enabled", true),
                getBoolean(config, defaults, "combat-logout.broadcast-enabled", true),
                getString(config, defaults, "combat-logout.broadcast-message", "{bad}{player} logged out while combat tagged."),
                getStringList(config, defaults, "combat-logout.console-commands").stream()
                        .filter(command -> command != null && !command.isBlank())
                        .toList(),
                getBoolean(config, defaults, "commands.block-while-tagged", true),
                normalizedRules(getStringList(config, defaults, "commands.allowed-while-tagged")),
                getBoolean(config, defaults, "worldguard.check-no-pvp-regions", true),
                lowerStrings(config, defaults, "worldguard.enclosed-regions"),
                getBoolean(config, defaults, "worldguard.border.enabled", true),
                getBoolean(config, defaults, "worldguard.border.advanced-visuals-enabled", true),
                borderCollisionMaterial,
                borderVisualColor,
                clamp(getInt(config, defaults, "worldguard.border.visual-range", 4), 1, 16),
                clamp(getInt(config, defaults, "worldguard.border.radius", 3), 2, 16),
                clamp(getInt(config, defaults, "worldguard.border.height", 2), 1, 12),
                getBoolean(config, defaults, "worldguard.border.only-air", true),
                clamp(getInt(config, defaults, "worldguard.border.cache-seconds", 30), 0, 60),
                clamp(getDouble(config, defaults, "worldguard.border.pushback-multiplier", 1.0D), 0.0D, 3.0D),
                clamp(getInt(config, defaults, "worldguard.border.pearl-impact.radius", 2), 1, 8),
                clamp(getInt(config, defaults, "worldguard.border.pearl-impact.duration-ticks", 18), 1, 100),
                getBoolean(config, defaults, "file-logging", false)
        );
    }

    public static void migrateConfig(FoCombatTag plugin) {
        FileConfiguration config = plugin.getConfig();
        boolean changed = false;
        boolean nativeEnabledSet = config.isSet(NativeDialogConfigDefaults.ENABLED_PATH);
        boolean nativeWarnSet = config.isSet(NativeDialogConfigDefaults.WARN_ON_FALLBACK_PATH);
        boolean nativeCommentsMissing = config.getComments("native-dialogs").isEmpty()
                || config.getComments(NativeDialogConfigDefaults.ENABLED_PATH).isEmpty()
                || config.getComments(NativeDialogConfigDefaults.WARN_ON_FALLBACK_PATH).isEmpty();
        NativeDialogConfigDefaults.addDefaults(config);
        changed |= !nativeEnabledSet || !nativeWarnSet || nativeCommentsMissing;

        if (!config.isSet("combat.tag-player-explosions")) {
            config.set("combat.tag-player-explosions", true);
            changed = true;
        }

        String legacyPath = "combat.pearl-cooldown.enabled";
        if (config.isSet(legacyPath) && !config.isSet("combat.pearl-cooldown.mode")) {
            PearlCooldownMode mode = config.getBoolean(legacyPath, false)
                    ? PearlCooldownMode.WHEN_TAGGED
                    : PearlCooldownMode.NEVER;
            config.set("combat.pearl-cooldown.mode", mode.configValue());
            changed = true;
        }
        if (config.isSet(legacyPath)) {
            config.set(legacyPath, null);
            changed = true;
        }
        if (config.isSet("combat.untag-on-death")) {
            config.set("combat.untag-on-death", null);
            changed = true;
        }

        if (!config.isSet("worldguard.border.collision-material")) {
            config.set("worldguard.border.collision-material", config.getString("worldguard.border.material", "BARRIER"));
            changed = true;
        }
        changed |= setMissing(config, "worldguard.border.advanced-visuals-enabled", true);
        changed |= setMissing(config, "worldguard.border.visual-color", "#ff3d5a");
        changed |= setMissing(config, "worldguard.border.visual-range", 4);
        changed |= setMissing(config, "worldguard.border.radius", 3);
        changed |= setMissing(config, "worldguard.border.height", 2);
        changed |= setMissing(config, "worldguard.border.cache-seconds", 30);
        changed |= setMissing(config, "worldguard.border.pearl-impact.radius", 2);
        changed |= setMissing(config, "worldguard.border.pearl-impact.duration-ticks", 18);
        changed |= setMissing(config, "worldguard.enclosed-regions", List.of());

        if (changed) {
            saveMigratedConfig(plugin);
        }
    }

    private static boolean setMissing(FileConfiguration config, String path, Object value) {
        if (config.isSet(path)) {
            return false;
        }
        config.set(path, value);
        return true;
    }

    private static void saveMigratedConfig(FoCombatTag plugin) {
        try {
            plugin.saveConfig();
        } catch (RuntimeException exception) {
            plugin.logWarning("Failed to migrate config: " + exception.getMessage());
        }
    }

    public boolean pearlCooldownEnabled() {
        return pearlCooldownMode != PearlCooldownMode.NEVER;
    }

    public boolean pearlCooldownApplies(boolean tagged) {
        return switch (pearlCooldownMode) {
            case ALWAYS -> true;
            case WHEN_TAGGED -> tagged;
            case NEVER -> false;
        };
    }

    public boolean isWorldEnabled(World world) {
        if (world == null) {
            return false;
        }
        String worldName = world.getName().toLowerCase(Locale.ROOT);
        if (disabledWorlds.contains(worldName)) {
            return false;
        }
        return enabledWorlds.isEmpty() || enabledWorlds.contains(worldName);
    }

    public boolean hasEnclosedWorldGuardRegions() {
        return !enclosedWorldGuardRegions.isEmpty();
    }

    public boolean isCommandAllowedWhileTagged(String message) {
        List<String> commandTokens = tokens(message);
        if (commandTokens.isEmpty()) {
            return false;
        }
        for (String rule : allowedCommandsWhileTagged) {
            if (matchesRule(commandTokens, tokens(rule))) {
                return true;
            }
        }
        return false;
    }

    private static PearlCooldownMode getPearlCooldownMode(FoCombatTag plugin, FileConfiguration config, FileConfiguration defaults) {
        String modePath = "combat.pearl-cooldown.mode";
        String legacyPath = "combat.pearl-cooldown.enabled";
        if (config.isSet(modePath)) {
            return parsePearlCooldownMode(plugin, config.getString(modePath));
        }
        if (config.isSet(legacyPath)) {
            return config.getBoolean(legacyPath, false) ? PearlCooldownMode.WHEN_TAGGED : PearlCooldownMode.NEVER;
        }
        if (defaults.isSet(modePath)) {
            return parsePearlCooldownMode(plugin, defaults.getString(modePath));
        }
        return defaults.getBoolean(legacyPath, false) ? PearlCooldownMode.WHEN_TAGGED : PearlCooldownMode.NEVER;
    }

    private static PearlCooldownMode parsePearlCooldownMode(FoCombatTag plugin, String value) {
        PearlCooldownMode mode = PearlCooldownMode.fromConfig(value);
        if (mode != null) {
            return mode;
        }
        plugin.logWarning("Invalid combat.pearl-cooldown.mode '" + value + "', using never.");
        return PearlCooldownMode.NEVER;
    }

    private static Material parseMaterial(FoCombatTag plugin, String value, Material fallback) {
        Material material = Material.matchMaterial(value == null ? "" : value.trim());
        if (material == null || !material.isBlock()) {
            plugin.logWarning("Invalid worldguard.border.collision-material '" + value + "', using " + fallback.name() + ".");
            return fallback;
        }
        return material;
    }

    public static Color parseBorderVisualColor(FoCombatTag plugin, String value) {
        return parseColor(plugin, value, Color.fromRGB(255, 61, 90));
    }

    private static Color parseColor(FoCombatTag plugin, String value, Color fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "red" -> Color.RED;
            case "green" -> Color.LIME;
            case "blue" -> Color.BLUE;
            case "yellow" -> Color.YELLOW;
            case "orange" -> Color.ORANGE;
            case "purple" -> Color.PURPLE;
            case "pink" -> Color.FUCHSIA;
            case "aqua", "cyan" -> Color.AQUA;
            case "white" -> Color.WHITE;
            default -> parseHexColor(plugin, normalized, fallback);
        };
    }

    private static Color parseHexColor(FoCombatTag plugin, String value, Color fallback) {
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (hex.startsWith("0x")) {
            hex = hex.substring(2);
        }
        if (hex.length() != 6) {
            plugin.logWarning("Invalid worldguard.border.visual-color '" + value + "', using red.");
            return fallback;
        }
        try {
            return Color.fromRGB(Integer.parseInt(hex, 16));
        } catch (NumberFormatException exception) {
            plugin.logWarning("Invalid worldguard.border.visual-color '" + value + "', using red.");
            return fallback;
        }
    }

    private static Set<String> lowerStrings(FileConfiguration config, FileConfiguration defaults, String path) {
        return ConfigValueLists.lowerStrings(config.isSet(path) ? config : defaults, path);
    }

    private static List<String> normalizedRules(List<String> values) {
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(Settings::normalizeCommand)
                .distinct()
                .toList();
    }

    private static String normalizeCommand(String command) {
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.replaceAll("\\s+", " ");
    }

    private static List<String> tokens(String command) {
        String normalized = normalizeCommand(command);
        if (normalized.isBlank()) {
            return List.of();
        }
        return List.of(normalized.split("\\s+"));
    }

    private static boolean matchesRule(List<String> commandTokens, List<String> ruleTokens) {
        if (ruleTokens.isEmpty()) {
            return false;
        }
        if (ruleTokens.size() == 1 && ruleTokens.getFirst().equals("*")) {
            return true;
        }
        boolean trailingWildcard = ruleTokens.getLast().equals("*");
        int requiredTokens = trailingWildcard ? ruleTokens.size() - 1 : ruleTokens.size();
        if (commandTokens.size() < requiredTokens) {
            return false;
        }
        if (!trailingWildcard && ruleTokens.size() == 1 && commandTokens.isEmpty()) {
            return false;
        }
        for (int index = 0; index < requiredTokens; index++) {
            if (!ruleTokens.get(index).equals(commandTokens.get(index))) {
                return false;
            }
        }
        if (trailingWildcard) {
            return true;
        }
        return ruleTokens.size() == 1 || commandTokens.size() >= ruleTokens.size();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static FileConfiguration loadDefaults(FoCombatTag plugin) {
        try (InputStream stream = plugin.getResource("config.yml")) {
            if (stream == null) {
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            plugin.logWarning("Failed to load config defaults: " + exception.getMessage());
            return new YamlConfiguration();
        }
    }

    private static boolean getBoolean(FileConfiguration config, FileConfiguration defaults, String path, boolean fallback) {
        return config.isSet(path) ? config.getBoolean(path, fallback) : defaults.getBoolean(path, fallback);
    }

    private static int getInt(FileConfiguration config, FileConfiguration defaults, String path, int fallback) {
        return config.isSet(path) ? config.getInt(path, fallback) : defaults.getInt(path, fallback);
    }

    private static double getDouble(FileConfiguration config, FileConfiguration defaults, String path, double fallback) {
        return config.isSet(path) ? config.getDouble(path, fallback) : defaults.getDouble(path, fallback);
    }

    private static String getString(FileConfiguration config, FileConfiguration defaults, String path, String fallback) {
        return config.isSet(path) ? config.getString(path, fallback) : defaults.getString(path, fallback);
    }

    private static List<String> getStringList(FileConfiguration config, FileConfiguration defaults, String path) {
        return config.isSet(path) ? config.getStringList(path) : defaults.getStringList(path);
    }

    public enum PearlCooldownMode {
        ALWAYS("always", "Always"),
        WHEN_TAGGED("when-tagged", "When Tagged"),
        NEVER("never", "Never");

        private final String configValue;
        private final String displayName;

        PearlCooldownMode(String configValue, String displayName) {
            this.configValue = configValue;
            this.displayName = displayName;
        }

        public String configValue() {
            return configValue;
        }

        public String displayName() {
            return displayName;
        }

        public PearlCooldownMode next() {
            PearlCooldownMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        public static PearlCooldownMode fromConfig(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT)
                    .replace('_', '-')
                    .replace(' ', '-');
            return switch (normalized) {
                case "always", "enabled", "true", "on" -> ALWAYS;
                case "when-tagged", "tagged", "combat", "combat-tagged" -> WHEN_TAGGED;
                case "never", "disabled", "false", "off", "none" -> NEVER;
                default -> null;
            };
        }
    }
}
