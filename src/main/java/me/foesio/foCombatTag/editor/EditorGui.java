package me.foesio.foCombatTag.editor;

import me.foesio.core.dialog.DialogButton;
import me.foesio.core.dialog.NativeDialogAvailability;
import me.foesio.core.dialog.TextDialogRequest;
import me.foesio.core.editor.CycleOption;
import me.foesio.core.editor.CycleOptions;
import me.foesio.core.editor.EditorDialogInputs;
import me.foesio.core.editor.EditorItemFactory;
import me.foesio.core.gui.EntryBrowserClick;
import me.foesio.core.gui.EntryBrowserHolder;
import me.foesio.core.gui.EntryBrowserMenus;
import me.foesio.core.gui.EntryBrowserRequest;
import me.foesio.core.gui.GuiButtonConfig;
import me.foesio.core.gui.GuiSlots;
import me.foesio.core.gui.GuiTitles;
import me.foesio.core.material.MaterialChooserActionType;
import me.foesio.core.material.MaterialChooserClick;
import me.foesio.core.material.MaterialChooserHolder;
import me.foesio.core.material.MaterialChooserMenus;
import me.foesio.core.material.MaterialChooserMode;
import me.foesio.core.material.MaterialChooserRequest;
import me.foesio.core.material.MaterialTypes;
import me.foesio.core.message.FoStyle;
import me.foesio.core.number.NumberFormatters;
import me.foesio.core.selector.StringSelectionEntries;
import me.foesio.core.selector.TriStateSelectionActionType;
import me.foesio.core.selector.TriStateSelectionClick;
import me.foesio.core.selector.TriStateSelectionEntry;
import me.foesio.core.selector.TriStateSelectionHolder;
import me.foesio.core.selector.TriStateSelectionMenus;
import me.foesio.core.selector.TriStateSelectionRequest;
import me.foesio.core.selector.TriStateSelectionState;
import me.foesio.foCombatTag.FoCombatTag;
import me.foesio.foCombatTag.config.Settings.PearlCooldownMode;
import me.foesio.foCombatTag.config.Settings.WindChargeCooldownMode;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EditorGui implements Listener {
    private static final int SIZE = 36;
    private static final int ROWS = SIZE / GuiSlots.ROW_SIZE;
    private static final int BACK_SLOT = GuiSlots.bottomMiddleSlot(ROWS);
    private static final String VARIATION_SELECTOR_TEXT = "\uFE0E";
    private static final String LEGACY_COLOR_FORMAT_BREAK = "\u200B";
    private static final String VISIBLE_COLOR_RESTORE_TOKEN = "{white}";
    private static final List<CycleOption> PEARL_COOLDOWN_OPTIONS = List.of(
            new CycleOption(PearlCooldownMode.ALWAYS.configValue(), PearlCooldownMode.ALWAYS.displayName()),
            new CycleOption(PearlCooldownMode.WHEN_TAGGED.configValue(), PearlCooldownMode.WHEN_TAGGED.displayName()),
            new CycleOption(PearlCooldownMode.NEVER.configValue(), PearlCooldownMode.NEVER.displayName())
    );
    private static final List<CycleOption> WIND_CHARGE_COOLDOWN_OPTIONS = List.of(
            new CycleOption(WindChargeCooldownMode.ALWAYS.configValue(), WindChargeCooldownMode.ALWAYS.displayName()),
            new CycleOption(WindChargeCooldownMode.WHEN_TAGGED.configValue(), WindChargeCooldownMode.WHEN_TAGGED.displayName()),
            new CycleOption(WindChargeCooldownMode.NEVER.configValue(), WindChargeCooldownMode.NEVER.displayName())
    );

    private final FoCombatTag plugin;
    private final GuiButtonConfig buttons = GuiButtonConfig.defaults();
    private final Set<UUID> warnedNativeFallback = ConcurrentHashMap.newKeySet();
    private final Map<UUID, View> materialChooserReturnViews = new ConcurrentHashMap<>();

    public EditorGui(FoCombatTag plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        openMain(player);
        plugin.editorSounds().open(player);
    }

    private void openMain(Player player) {
        EditorHolder holder = new EditorHolder(View.MAIN, 0);
        Inventory inventory = Bukkit.createInventory(holder, SIZE, GuiTitles.format("ᴄᴏᴍʙᴀᴛ ᴛᴀɢ ᴇᴅɪᴛᴏʀ"));
        holder.setInventory(inventory);
        fill(inventory);

        inventory.setItem(10, button(player, FoStyle.THEME, Material.CLOCK, "Combat Timer", List.of(
                "{white}Current: {theme}" + plugin.settings().combatDurationSeconds() + "s",
                "{white}Click to edit."
        ), "edit combat duration"));
        inventory.setItem(11, button(player, FoStyle.THEME, Material.IRON_BARS, "Combat Wall Settings", List.of(
                "{white}Status: " + (plugin.settings().worldGuardBorderEnabled() ? "{good}Enabled" : "{bad}Disabled"),
                "{white}Advanced visuals: " + (plugin.settings().worldGuardBorderAdvancedVisualsEnabled() ? "{good}Enabled" : "{bad}Disabled"),
                "{white}Click to configure."
        ), "configure combat wall"));
        boolean tagExplosions = plugin.settings().tagPlayerExplosions();
        inventory.setItem(12, button(player, tagExplosions ? FoStyle.GOOD : FoStyle.BAD, Material.END_CRYSTAL,
                "Explosion Combat Tag", List.of(
                        "{white}State: " + (plugin.settings().tagPlayerExplosions() ? "{good}Enabled" : "{bad}Disabled"),
                        "{white}Player-caused explosions tag",
                        "{white}affected players in combat.",
                        "{white}Click to toggle."
                ), "toggle explosion combat tagging"));
        inventory.setItem(13, toggleItem(player, Material.ELYTRA, "Disable Elytra", plugin.settings().disableElytraWhileTagged()));
        inventory.setItem(14, toggleItem(player, Material.COMMAND_BLOCK, "Block Commands", plugin.settings().blockCommandsWhileTagged()));
        inventory.setItem(15, button(player, FoStyle.THEME, Material.WRITABLE_BOOK, "Allowed Commands", List.of(
                "{white}Entries: {theme}" + configuredAllowedCommands().size(),
                "{white}Click to browse."
        ), "browse allowed commands"));
        inventory.setItem(16, toggleItem(player, Material.SKELETON_SKULL, "Kill On Logout", plugin.settings().killOnLogout()));
        inventory.setItem(19, button(player, FoStyle.THEME, Material.ENDER_EYE, "Enclosed Regions", List.of(
                "{white}Selected: {theme}" + plugin.settings().enclosedWorldGuardRegions().size(),
                "{white}State: " + (plugin.settings().hasEnclosedWorldGuardRegions() ? "{good}Enabled" : "{bad}Disabled"),
                "{white}Click to select WorldGuard regions."
        ), "configure enclosed regions"));
        inventory.setItem(20, pearlCooldownModeItem(player));
        inventory.setItem(21, button(player, FoStyle.THEME, Material.CLOCK, "Pearl Cooldown Timer", List.of(
                "{white}Current: {theme}" + plugin.settings().pearlCooldownSeconds() + "s",
                "{white}Click to edit."
        ), "edit pearl cooldown"));
        boolean pearlExtends = plugin.settings().pearlExtendsCombat();
        inventory.setItem(22, button(player, pearlExtends ? FoStyle.GOOD : FoStyle.BAD, Material.ENDER_PEARL,
                "Pearls Extend Combat", List.of(
                        "{white}State: " + (plugin.settings().pearlExtendsCombat() ? "{good}Enabled" : "{bad}Disabled"),
                        "{white}A pearl landing while tagged",
                        "{white}resets the combat timer.",
                        "{white}Click to toggle."
                ), "toggle pearl combat extension"));
        inventory.setItem(23, windChargeCooldownModeItem(player));
        inventory.setItem(24, button(player, FoStyle.THEME, Material.CLOCK, "Wind Charge Cooldown Timer", List.of(
                "{white}Current: {theme}" + plugin.settings().windChargeCooldownSeconds() + "s",
                "{white}Click to edit."
        ), "edit wind charge cooldown"));
        boolean windChargeExtends = plugin.settings().windChargeExtendsCombat();
        inventory.setItem(25, button(player, windChargeExtends ? FoStyle.GOOD : FoStyle.BAD, Material.WIND_CHARGE,
                "Wind Charges Extend Combat", List.of(
                        "{white}State: " + (windChargeExtends ? "{good}Enabled" : "{bad}Disabled"),
                        "{white}Launching a wind charge while tagged",
                        "{white}resets the combat timer.",
                        "{white}Click to toggle."
                ), "toggle wind charge combat extension"));
        player.openInventory(inventory);
    }

    private void openEnclosedRegions(Player player, int page) {
        openEnclosedRegions(player, page, "");
    }

    private void openEnclosedRegions(Player player, int page, String filter) {
        TriStateSelectionMenus.open(player, enclosedRegionsRequest(page, filter));
    }

    private void openEnclosedRegionsScreen(Player player) {
        openEnclosedRegions(player, 0);
        plugin.editorSounds().open(player);
    }

    private void openCommands(Player player, int page) {
        openCommands(player, page, "");
    }

    private void openCommands(Player player, int page, String filter) {
        List<CommandEntry> commandEntries = filteredCommandEntries(filter);
        List<EntryBrowserRequest.Entry> entries = commandEntries.stream()
                .map(entry -> EntryBrowserRequest.Entry.of(
                        String.valueOf(entry.index()),
                        button(player, FoStyle.THEME, Material.PAPER, entry.command(), List.of(
                                "{white}Shift right-click to remove.",
                                "{white}Allowed while tagged."
                        ), "remove the command")
                ))
                .toList();
        EntryBrowserRequest request = EntryBrowserRequest.builder()
                .title("ᴀʟʟᴏᴡᴇᴅ ᴄᴏᴍᴍᴀɴᴅꜱ")
                .entries(entries)
                .page(page)
                .filter(filter)
                .buttons(buttons)
                .showBack(true)
                .addButton(button(player, FoStyle.GOOD, Material.ANVIL, "Add Command", List.of(
                        "{white}Click to add a pattern.",
                        "{white}Examples: {theme}team chat{white}, {theme}team *"
                ), "add a command"))
                .context(new CommandBrowserContext())
                .build();
        EntryBrowserMenus.open(player, request);
    }

    private void openCommandsScreen(Player player) {
        openCommands(player, 0);
        plugin.editorSounds().open(player);
    }

    private void openCombatWallSettings(Player player) {
        EditorHolder holder = new EditorHolder(View.COMBAT_WALL_SETTINGS, 0);
        Inventory inventory = Bukkit.createInventory(holder, SIZE, GuiTitles.format("ᴄᴏᴍʙᴀᴛ ᴡᴀʟʟ ꜱᴇᴛᴛɪɴɢꜱ"));
        holder.setInventory(inventory);
        fill(inventory);

        inventory.setItem(10, toggleItem(player, Material.IRON_BARS, "Show Combat Wall", plugin.settings().worldGuardBorderEnabled()));
        inventory.setItem(11, button(player, FoStyle.THEME, plugin.settings().worldGuardBorderCollisionMaterial(), "Combat Wall Block", List.of(
                "{white}Current: {theme}" + plugin.settings().worldGuardBorderCollisionMaterial().name(),
                "{white}Click to choose block."
        ), "choose the wall block"));
        inventory.setItem(12, toggleItem(player, Material.TINTED_GLASS, "Advanced Visuals", plugin.settings().worldGuardBorderAdvancedVisualsEnabled()));
        inventory.setItem(13, button(player, FoStyle.THEME, Material.RED_DYE, "Visual Color", List.of(
                "{white}Current: {theme}" + visibleColorValue(),
                "{white}Click to edit."
        ), "edit visual color"));
        inventory.setItem(14, button(player, FoStyle.THEME, Material.SPYGLASS, "Visual Range", List.of(
                "{white}Current: {theme}" + plugin.settings().worldGuardBorderVisualRange(),
                "{white}Click to edit."
        ), "edit visual range"));
        inventory.setItem(15, button(player, FoStyle.THEME, Material.COMPASS, "Scan Radius", List.of(
                "{white}Current: {theme}" + plugin.settings().worldGuardBorderRadius(),
                "{white}Click to edit."
        ), "edit scan radius"));
        inventory.setItem(16, button(player, FoStyle.THEME, Material.SCAFFOLDING, "Combat Wall Height", List.of(
                "{white}Current: {theme}" + plugin.settings().worldGuardBorderHeight(),
                "{white}Click to edit."
        ), "edit wall height"));
        inventory.setItem(19, toggleItem(player, Material.FEATHER, "Empty Blocks Only", plugin.settings().worldGuardBorderOnlyAir()));
        inventory.setItem(20, button(player, FoStyle.THEME, Material.HOPPER, "Combat Wall Cache", List.of(
                "{white}Current: {theme}" + plugin.settings().worldGuardBorderCacheSeconds() + "s",
                "{white}Click to edit."
        ), "edit wall cache"));
        inventory.setItem(21, button(player, FoStyle.THEME, Material.PISTON, "Pushback Multiplier", List.of(
                "{white}Current: {theme}" + NumberFormatters.money(plugin.settings().worldGuardBorderPushbackMultiplier()) + "x",
                "{white}Click to edit."
        ), "edit pushback multiplier"));
        inventory.setItem(22, button(player, FoStyle.THEME, Material.ENDER_PEARL, "Pearl Impact Radius", List.of(
                "{white}Current: {theme}" + plugin.settings().worldGuardBorderPearlImpactRadius(),
                "{white}Click to edit."
        ), "edit pearl impact radius"));
        inventory.setItem(23, button(player, FoStyle.THEME, Material.CLOCK, "Pearl Impact Duration", List.of(
                "{white}Current: {theme}" + plugin.settings().worldGuardBorderPearlImpactDurationTicks() + " ticks",
                "{white}Click to edit."
        ), "edit pearl impact duration"));

        inventory.setItem(BACK_SLOT, buttons.back(player));
        player.openInventory(inventory);
    }

    private void openCombatWallSettingsScreen(Player player) {
        openCombatWallSettings(player);
        plugin.editorSounds().open(player);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof EntryBrowserHolder holder) {
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            event.setCancelled(true);
            if (!player.hasPermission(FoCombatTag.ADMIN_PERMISSION)) {
                player.closeInventory();
                plugin.messages().send(player, "no-permission");
                return;
            }
            if (event.getClickedInventory() == event.getView().getTopInventory()) {
                handleEntryBrowserClick(event, holder, player);
            }
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof MaterialChooserHolder holder) {
            handleMaterialChooserClick(event, holder);
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof TriStateSelectionHolder holder) {
            handleCoreSelectorClick(event, holder);
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof EditorHolder holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!player.hasPermission(FoCombatTag.ADMIN_PERMISSION)) {
            event.setCancelled(true);
            player.closeInventory();
            plugin.messages().send(player, "no-permission");
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot >= event.getView().getTopInventory().getSize()) {
            return;
        }
        event.setCancelled(true);
        if (holder.view() == View.MAIN) {
            handleMainClick(player, rawSlot);
        } else {
            handleCombatWallSettingsClick(player, rawSlot);
        }
    }

    private void handleEntryBrowserClick(InventoryClickEvent event, EntryBrowserHolder holder, Player player) {
        EntryBrowserClick click = EntryBrowserMenus.handleClick(event.getRawSlot(), holder, event.getClick());
        switch (click.action()) {
            case ENTRY -> {
                if (click.clickType() != ClickType.SHIFT_RIGHT) {
                    return;
                }
                int commandIndex;
                try {
                    commandIndex = Integer.parseInt(click.entryId());
                } catch (NumberFormatException exception) {
                    plugin.editorSounds().error(player);
                    return;
                }
                List<String> commands = configuredAllowedCommands();
                if (commandIndex < 0 || commandIndex >= commands.size()) {
                    plugin.editorSounds().error(player);
                    openCommands(player, holder.request().page(), holder.request().filter());
                    return;
                }
                String removed = commands.remove(commandIndex);
                if (saveList(player, "commands.allowed-while-tagged", commands, "Allowed commands", commands.size() + " entries")) {
                    plugin.editorSounds().delete(player);
                    plugin.messages().send(player, "editor-command-removed", Map.of("{command}", removed));
                    openCommands(player, holder.request().page(), holder.request().filter());
                }
            }
            case ADD -> startCommandPrompt(player);
            case BACK -> { plugin.editorSounds().back(player); openMain(player); }
            case SEARCH -> { plugin.editorSounds().search(player); openCommandSearch(player, holder.request().filter()); }
            case CLEAR_SEARCH -> { plugin.editorSounds().clearSearch(player); openCommands(player, 0, ""); }
            case PREVIOUS_PAGE -> { plugin.editorSounds().previousPage(player); openCommands(player, holder.request().page() - 1, holder.request().filter()); }
            case NEXT_PAGE -> { plugin.editorSounds().nextPage(player); openCommands(player, holder.request().page() + 1, holder.request().filter()); }
            case EXTRA, NONE -> {
            }
        }
    }

    private void openCommandSearch(Player player, String currentFilter) {
        TextDialogRequest request = new TextDialogRequest(
                "Search",
                List.of("Search allowed command patterns."),
                "Search",
                currentFilter,
                "command pattern",
                DialogButton.search(),
                DialogButton.cancel(),
                320,
                300,
                64,
                true,
                true,
                false
        );
        openTextInput(player, request,
                input -> openCommands(player, 0, input),
                () -> openCommands(player, 0, currentFilter),
                "command search");
    }

    private void handleMainClick(Player player, int slot) {
        switch (slot) {
            case 10 -> startNumberPrompt(player, PromptType.DURATION);
            case 11 -> openCombatWallSettingsScreen(player);
            case 12 -> toggle(player, "combat.tag-player-explosions", "Explosion combat tag");
            case 13 -> toggle(player, "combat.disable-elytra-while-tagged", "Disable elytra");
            case 14 -> toggle(player, "commands.block-while-tagged", "Command blocking");
            case 15 -> openCommandsScreen(player);
            case 16 -> toggle(player, "combat.kill-on-logout", "Kill on logout");
            case 19 -> openEnclosedRegionsScreen(player);
            case 20 -> cyclePearlCooldownMode(player);
            case 21 -> startNumberPrompt(player, PromptType.PEARL_COOLDOWN);
            case 22 -> toggle(player, "combat.pearl-extends-combat", "Pearl combat extension");
            case 23 -> cycleWindChargeCooldownMode(player);
            case 24 -> startNumberPrompt(player, PromptType.WIND_CHARGE_COOLDOWN);
            case 25 -> toggle(player, "combat.wind-charge-extends-combat", "Wind charge combat extension");
            default -> {
            }
        }
    }

    private void handleCombatWallSettingsClick(Player player, int slot) {
        if (slot == BACK_SLOT) {
            plugin.editorSounds().back(player);
            openMain(player);
            return;
        }
        switch (slot) {
            case 10 -> save(player, "worldguard.border.enabled", !plugin.settings().worldGuardBorderEnabled(),
                    "Combat wall", !plugin.settings().worldGuardBorderEnabled() ? "enabled" : "disabled", View.COMBAT_WALL_SETTINGS);
            case 11 -> openBorderMaterialChooserScreen(player, View.COMBAT_WALL_SETTINGS);
            case 12 -> save(player, "worldguard.border.advanced-visuals-enabled", !plugin.settings().worldGuardBorderAdvancedVisualsEnabled(),
                    "Advanced visuals", !plugin.settings().worldGuardBorderAdvancedVisualsEnabled() ? "enabled" : "disabled", View.COMBAT_WALL_SETTINGS);
            case 13 -> startTextPrompt(player, PromptType.BORDER_VISUAL_COLOR);
            case 14 -> startNumberPrompt(player, PromptType.BORDER_VISUAL_RANGE);
            case 15 -> startNumberPrompt(player, PromptType.BORDER_RADIUS);
            case 16 -> startNumberPrompt(player, PromptType.BORDER_HEIGHT);
            case 19 -> save(player, "worldguard.border.only-air", !plugin.settings().worldGuardBorderOnlyAir(),
                    "Empty blocks only", !plugin.settings().worldGuardBorderOnlyAir() ? "enabled" : "disabled", View.COMBAT_WALL_SETTINGS);
            case 20 -> startNumberPrompt(player, PromptType.BORDER_CACHE);
            case 21 -> startNumberPrompt(player, PromptType.PUSHBACK_MULTIPLIER);
            case 22 -> startNumberPrompt(player, PromptType.PEARL_IMPACT_RADIUS);
            case 23 -> startNumberPrompt(player, PromptType.PEARL_IMPACT_DURATION);
            default -> {
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        warnedNativeFallback.remove(event.getPlayer().getUniqueId());
        materialChooserReturnViews.remove(event.getPlayer().getUniqueId());
        plugin.clearDialogInput(event.getPlayer());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof EntryBrowserHolder)
                && !(holder instanceof EditorHolder)
                && !(holder instanceof TriStateSelectionHolder)
                && !(holder instanceof MaterialChooserHolder)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void startNumberPrompt(Player player, PromptType promptType) {
        startNumberPrompt(player, promptType, promptType.reopenView());
    }

    private void startNumberPrompt(Player player, PromptType promptType, View reopenView) {
        Prompt prompt = Prompt.number(promptType, reopenView);
        openNumberDialog(player, prompt);
    }

    private void startTextPrompt(Player player, PromptType promptType) {
        Prompt prompt = Prompt.text(promptType, promptType.reopenView());
        openTextDialog(player, prompt);
    }

    private void startCommandPrompt(Player player) {
        Prompt prompt = Prompt.command();
        openCommandDialog(player, prompt);
    }

    private void handlePrompt(Player player, Prompt prompt, String input) {
        if (!player.hasPermission(FoCombatTag.ADMIN_PERMISSION)) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        if (input.equalsIgnoreCase("cancel")) {
            plugin.messages().send(player, "editor-prompt-cancelled");
            plugin.editorSounds().back(player);
            reopenPromptPage(player, prompt);
            return;
        }
        if (input.isBlank()) {
            plugin.editorSounds().error(player);
            plugin.messages().send(player, "editor-save-failed", Map.of("{error}", "Empty input"));
            reopenPromptPage(player, prompt);
            return;
        }
        if (prompt.type() == PromptType.ADD_COMMAND) {
            addAllowedCommand(player, input);
            return;
        }
        if (prompt.type().text()) {
            saveText(player, prompt, input);
            return;
        }
        saveNumber(player, prompt, input);
    }

    private void cancelPrompt(Player player, Prompt prompt) {
        plugin.messages().send(player, "editor-prompt-cancelled");
        reopenPromptPage(player, prompt);
    }

    private void saveNumber(Player player, Prompt prompt, String input) {
        PromptType type = prompt.type();
        if (type.decimal()) {
            saveDecimalNumber(player, prompt, input);
            return;
        }
        int value;
        try {
            value = Integer.parseInt(input);
        } catch (NumberFormatException exception) {
            plugin.editorSounds().error(player);
            plugin.messages().send(player, "editor-invalid-number", Map.of(
                    "{min}", type.minText(),
                    "{max}", type.maxText()
            ));
            reopenPromptPage(player, prompt);
            return;
        }
        if (value < type.min() || value > type.max()) {
            plugin.editorSounds().error(player);
            plugin.messages().send(player, "editor-invalid-number", Map.of(
                    "{min}", type.minText(),
                    "{max}", type.maxText()
            ));
            reopenPromptPage(player, prompt);
            return;
        }
        save(player, type.path(), value, type.setting(), type.displayValue(value), prompt.reopenView());
    }

    private void saveDecimalNumber(Player player, Prompt prompt, String input) {
        PromptType type = prompt.type();
        double value;
        try {
            value = Double.parseDouble(stripMultiplierSuffix(input));
        } catch (NumberFormatException exception) {
            plugin.editorSounds().error(player);
            plugin.messages().send(player, "editor-invalid-number", Map.of(
                    "{min}", type.minText(),
                    "{max}", type.maxText()
            ));
            reopenPromptPage(player, prompt);
            return;
        }
        if (!Double.isFinite(value) || value < type.min() || value > type.max()) {
            plugin.editorSounds().error(player);
            plugin.messages().send(player, "editor-invalid-number", Map.of(
                    "{min}", type.minText(),
                    "{max}", type.maxText()
            ));
            reopenPromptPage(player, prompt);
            return;
        }
        save(player, type.path(), value, type.setting(), type.displayValue(value), prompt.reopenView());
    }

    private void saveText(Player player, Prompt prompt, String input) {
        PromptType type = prompt.type();
        String value = stripColorFormatBreak(input).trim();
        if (type == PromptType.BORDER_VISUAL_COLOR) {
            if (!isValidColorValue(value)) {
                plugin.editorSounds().error(player);
                plugin.messages().send(player, "editor-save-failed", Map.of("{error}", "Use #RRGGBB or a supported color name"));
                openCombatWallSettings(player);
                return;
            }
            save(player, type.path(), normalizeColorValue(value), type.setting(), normalizeColorValue(value), prompt.reopenView());
        }
    }

    private String currentNumber(PromptType type) {
        return switch (type) {
            case DURATION -> String.valueOf(plugin.settings().combatDurationSeconds());
            case BORDER_CACHE -> String.valueOf(plugin.settings().worldGuardBorderCacheSeconds());
            case PUSHBACK_MULTIPLIER -> NumberFormatters.money(plugin.settings().worldGuardBorderPushbackMultiplier());
            case PEARL_COOLDOWN -> String.valueOf(plugin.settings().pearlCooldownSeconds());
            case WIND_CHARGE_COOLDOWN -> String.valueOf(plugin.settings().windChargeCooldownSeconds());
            case BORDER_VISUAL_RANGE -> String.valueOf(plugin.settings().worldGuardBorderVisualRange());
            case BORDER_RADIUS -> String.valueOf(plugin.settings().worldGuardBorderRadius());
            case BORDER_HEIGHT -> String.valueOf(plugin.settings().worldGuardBorderHeight());
            case PEARL_IMPACT_RADIUS -> String.valueOf(plugin.settings().worldGuardBorderPearlImpactRadius());
            case PEARL_IMPACT_DURATION -> String.valueOf(plugin.settings().worldGuardBorderPearlImpactDurationTicks());
            default -> "0";
        };
    }

    private String currentText(PromptType type) {
        return switch (type) {
            case BORDER_VISUAL_COLOR -> colorValue();
            default -> "";
        };
    }

    private void addAllowedCommand(Player player, String input) {
        String normalized = normalizeCommand(input);
        List<String> commands = configuredAllowedCommands();
        boolean exists = commands.stream().map(this::normalizeCommand).anyMatch(normalized::equals);
        if (exists) {
            plugin.editorSounds().error(player);
            plugin.messages().send(player, "editor-save-failed", Map.of("{error}", "That command is already allowed"));
            openCommands(player, 0);
            return;
        }
        commands.add(normalized);
        if (saveList(player, "commands.allowed-while-tagged", commands, "Allowed commands", commands.size() + " entries")) {
            plugin.editorSounds().add(player);
            openCommands(player, 0);
        }
    }

    private void save(Player player, String path, Object value, String setting, String displayValue) {
        save(player, path, value, setting, displayValue, View.MAIN);
    }

    private void save(Player player, String path, Object value, String setting, String displayValue, View reopenView) {
        try {
            FileConfiguration config = plugin.getConfig();
            config.set(path, value);
            plugin.saveConfig();
            plugin.reloadPluginQuietly();
            if (value instanceof Boolean enabled) {
                plugin.editorSounds().toggle(player, enabled);
            } else {
                plugin.editorSounds().save(player);
            }
            plugin.messages().send(player, "editor-saved", Map.of("{setting}", setting, "{value}", displayValue));
            openView(player, reopenView);
        } catch (RuntimeException exception) {
            plugin.logError("Editor save failed: " + exception.getMessage(), exception);
            plugin.editorSounds().error(player);
            plugin.messages().send(player, "editor-save-failed", Map.of("{error}", exception.getMessage()));
        }
    }

    private boolean saveList(Player player, String path, List<String> value, String setting, String displayValue) {
        try {
            FileConfiguration config = plugin.getConfig();
            config.set(path, value);
            plugin.saveConfig();
            plugin.reloadPluginQuietly();
            plugin.messages().send(player, "editor-saved", Map.of("{setting}", setting, "{value}", displayValue));
            return true;
        } catch (RuntimeException exception) {
            plugin.logError("Editor save failed: " + exception.getMessage(), exception);
            plugin.editorSounds().error(player);
            plugin.messages().send(player, "editor-save-failed", Map.of("{error}", exception.getMessage()));
            return false;
        }
    }

    private void toggle(Player player, String path, String displayName) {
        boolean next = !plugin.getConfig().getBoolean(path);
        save(player, path, next, displayName, next ? "enabled" : "disabled");
    }

    private void cyclePearlCooldownMode(Player player) {
        String nextValue = CycleOptions.nextValue(
                plugin.settings().pearlCooldownMode().configValue(),
                PEARL_COOLDOWN_OPTIONS
        );
        PearlCooldownMode next = PearlCooldownMode.fromConfig(nextValue);
        if (next == null) {
            plugin.editorSounds().error(player);
            plugin.messages().send(player, "editor-save-failed", Map.of("{error}", "Invalid pearl cooldown option"));
            openMain(player);
            return;
        }
        try {
            FileConfiguration config = plugin.getConfig();
            config.set("combat.pearl-cooldown.mode", next.configValue());
            config.set("combat.pearl-cooldown.enabled", null);
            plugin.saveConfig();
            plugin.reloadPluginQuietly();
            plugin.editorSounds().cycle(player);
            plugin.messages().send(player, "editor-saved", Map.of(
                    "{setting}", "Pearl cooldown",
                    "{value}", next.displayName()
            ));
            openMain(player);
        } catch (RuntimeException exception) {
            plugin.logError("Editor save failed: " + exception.getMessage(), exception);
            plugin.editorSounds().error(player);
            plugin.messages().send(player, "editor-save-failed", Map.of("{error}", exception.getMessage()));
        }
    }

    private void cycleWindChargeCooldownMode(Player player) {
        String nextValue = CycleOptions.nextValue(
                plugin.settings().windChargeCooldownMode().configValue(),
                WIND_CHARGE_COOLDOWN_OPTIONS
        );
        WindChargeCooldownMode next = WindChargeCooldownMode.fromConfig(nextValue);
        if (next == null) {
            plugin.editorSounds().error(player);
            plugin.messages().send(player, "editor-save-failed", Map.of("{error}", "Invalid wind charge cooldown option"));
            openMain(player);
            return;
        }
        try {
            FileConfiguration config = plugin.getConfig();
            config.set("combat.wind-charge-cooldown.mode", next.configValue());
            plugin.saveConfig();
            plugin.reloadPluginQuietly();
            plugin.editorSounds().cycle(player);
            plugin.messages().send(player, "editor-saved", Map.of(
                    "{setting}", "Wind charge cooldown",
                    "{value}", next.displayName()
            ));
            openMain(player);
        } catch (RuntimeException exception) {
            plugin.logError("Editor save failed: " + exception.getMessage(), exception);
            plugin.editorSounds().error(player);
            plugin.messages().send(player, "editor-save-failed", Map.of("{error}", exception.getMessage()));
        }
    }

    private void reopenPromptPage(Player player, Prompt prompt) {
        if (prompt.type() == PromptType.ADD_COMMAND) {
            openCommands(player, 0);
        } else if (prompt.reopenView() == View.COMBAT_WALL_SETTINGS) {
            openCombatWallSettings(player);
        } else if (prompt.reopenView() == View.ENCLOSED_REGIONS) {
            openEnclosedRegions(player, 0);
        } else {
            openMain(player);
        }
    }

    private void openBorderMaterialChooser(Player player, View returnView) {
        openBorderMaterialChooser(player, returnView, 0, "");
    }

    private void openBorderMaterialChooserScreen(Player player, View returnView) {
        openBorderMaterialChooser(player, returnView);
        plugin.editorSounds().open(player);
    }

    private void openBorderMaterialChooser(Player player, View returnView, int page, String filter) {
        materialChooserReturnViews.put(player.getUniqueId(), returnView == null ? View.COMBAT_WALL_SETTINGS : returnView);
        MaterialChooserMenus.open(player, borderMaterialRequest(page, filter));
    }

    private MaterialChooserRequest borderMaterialRequest(int page, String filter) {
        Material current = plugin.settings().worldGuardBorderCollisionMaterial();
        return MaterialChooserRequest.builder()
                .title("ᴄᴏᴍʙᴀᴛ ᴡᴀʟʟ ʙʟᴏᴄᴋ")
                .mode(MaterialChooserMode.SINGLE_SELECT)
                .availableMaterials(MaterialTypes.allBlocks())
                .selectedMaterials(Set.of(current))
                .page(page)
                .filter(filter)
                .showBack(true)
                .showSearch(true)
                .buttons(buttons)
                .extraLore(material -> List.of(FoStyle.WHITE + "Config value: " + FoStyle.THEME + material.name()))
                .build();
    }

    private void handleMaterialChooserClick(InventoryClickEvent event, MaterialChooserHolder holder) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        if (!player.hasPermission(FoCombatTag.ADMIN_PERMISSION)) {
            player.closeInventory();
            plugin.messages().send(player, "no-permission");
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot >= event.getView().getTopInventory().getSize()) {
            return;
        }
        MaterialChooserClick click = MaterialChooserMenus.handleClick(rawSlot, holder);
        if (click.action() == MaterialChooserActionType.NONE) {
            return;
        }
        switch (click.action()) {
            case BACK -> { plugin.editorSounds().back(player); openMaterialReturnView(player); }
            case PREVIOUS_PAGE -> { plugin.editorSounds().previousPage(player); MaterialChooserMenus.open(player, click.nextRequest()); }
            case NEXT_PAGE -> { plugin.editorSounds().nextPage(player); MaterialChooserMenus.open(player, click.nextRequest()); }
            case CLEAR_SEARCH -> { plugin.editorSounds().clearSearch(player); MaterialChooserMenus.open(player, click.nextRequest()); }
            case SEARCH -> { plugin.editorSounds().search(player); startMaterialSearchPrompt(player, holder.request().filter()); }
            case SELECT, TOGGLE -> saveBorderMaterialChoice(player, click.material());
            default -> {
            }
        }
    }

    private void openMaterialReturnView(Player player) {
        View returnView = materialChooserReturnViews.remove(player.getUniqueId());
        openView(player, returnView == null ? View.COMBAT_WALL_SETTINGS : returnView);
    }

    private void saveBorderMaterialChoice(Player player, Material material) {
        if (material == null || !material.isBlock()) {
            plugin.editorSounds().error(player);
            plugin.messages().send(player, "editor-save-failed", Map.of("{error}", "Unknown block material"));
            openMaterialReturnView(player);
            return;
        }
        View returnView = materialChooserReturnViews.remove(player.getUniqueId());
        save(player, "worldguard.border.collision-material", material.name(), "Combat wall block", material.name(),
                returnView == null ? View.COMBAT_WALL_SETTINGS : returnView);
    }

    private void startMaterialSearchPrompt(Player player, String currentFilter) {
        View returnView = materialChooserReturnViews.getOrDefault(player.getUniqueId(), View.COMBAT_WALL_SETTINGS);
        TextDialogRequest request = new TextDialogRequest(
                "Search",
                List.of("Enter a block material search term."),
                "Search",
                currentFilter,
                "glass",
                DialogButton.search(),
                DialogButton.cancel(),
                320,
                300,
                64,
                true,
                true,
                false
        );
        openTextInput(player, request,
                input -> openBorderMaterialChooser(player, returnView, 0, input),
                () -> openBorderMaterialChooser(player, returnView, 0, currentFilter),
                "material search");
    }

    private void openView(Player player, View view) {
        if (view == View.COMBAT_WALL_SETTINGS) {
            openCombatWallSettings(player);
        } else if (view == View.COMMANDS) {
            openCommands(player, 0);
        } else {
            openMain(player);
        }
    }

    private List<String> configuredAllowedCommands() {
        if (plugin.getConfig().isSet("commands.allowed-while-tagged")) {
            return new ArrayList<>(plugin.getConfig().getStringList("commands.allowed-while-tagged"));
        }
        return new ArrayList<>(plugin.settings().allowedCommandsWhileTagged());
    }

    private List<CommandEntry> filteredCommandEntries(String filter) {
        String query = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        List<String> commands = configuredAllowedCommands();
        List<CommandEntry> entries = new ArrayList<>();
        for (int index = 0; index < commands.size(); index++) {
            String command = commands.get(index);
            if (query.isBlank() || command.toLowerCase(Locale.ROOT).contains(query)) {
                entries.add(new CommandEntry(index, command));
            }
        }
        return entries;
    }

    private List<String> configuredEnclosedRegions() {
        List<String> regions = plugin.getConfig().isSet("worldguard.enclosed-regions")
                ? plugin.getConfig().getStringList("worldguard.enclosed-regions")
                : new ArrayList<>(plugin.settings().enclosedWorldGuardRegions());
        return regions.stream()
                .filter(region -> region != null && !region.isBlank())
                .map(region -> region.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private TriStateSelectionRequest enclosedRegionsRequest(int page, String filter) {
        List<String> selected = configuredEnclosedRegions();
        List<TriStateSelectionEntry> entries = StringSelectionEntries.fromKeys(plugin.worldGuardHook().regionIds(), selected);
        Map<String, TriStateSelectionState> states = new LinkedHashMap<>();
        for (String region : selected) {
            states.put(region, TriStateSelectionState.ENABLED);
        }
        return TriStateSelectionRequest.builder()
                .title("ᴇɴᴄʟᴏꜱᴇᴅ ʀᴇɢɪᴏɴꜱ")
                .entries(entries)
                .states(states)
                .cycleOrder(List.of(TriStateSelectionState.NEUTRAL, TriStateSelectionState.ENABLED))
                .page(page)
                .filter(filter)
                .showBack(true)
                .showSearch(true)
                .enabledLabel("Selected")
                .neutralLabel("Not Selected")
                .clickHint("Click to toggle.")
                .emptyTitle("No WorldGuard Regions")
                .emptyLore(List.of("Create WorldGuard regions, then reopen this selector."))
                .build();
    }

    private void handleCoreSelectorClick(InventoryClickEvent event, TriStateSelectionHolder holder) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        if (!player.hasPermission(FoCombatTag.ADMIN_PERMISSION)) {
            player.closeInventory();
            plugin.messages().send(player, "no-permission");
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot >= event.getView().getTopInventory().getSize()) {
            return;
        }
        TriStateSelectionClick click = TriStateSelectionMenus.handleClick(rawSlot, holder);
        if (click.action() == TriStateSelectionActionType.NONE) {
            return;
        }
        switch (click.action()) {
            case BACK -> { plugin.editorSounds().back(player); openMain(player); }
            case PREVIOUS_PAGE -> { plugin.editorSounds().previousPage(player); TriStateSelectionMenus.open(player, click.nextRequest()); }
            case NEXT_PAGE -> { plugin.editorSounds().nextPage(player); TriStateSelectionMenus.open(player, click.nextRequest()); }
            case CLEAR_SEARCH -> { plugin.editorSounds().clearSearch(player); TriStateSelectionMenus.open(player, click.nextRequest()); }
            case SEARCH -> { plugin.editorSounds().search(player); startRegionSearchPrompt(player, holder.request().filter()); }
            case TOGGLE -> saveEnclosedRegionStates(player, click.nextRequest());
            default -> {
            }
        }
    }

    private void saveEnclosedRegionStates(Player player, TriStateSelectionRequest request) {
        List<String> selected = request.states().entrySet().stream()
                .filter(entry -> entry.getValue() == TriStateSelectionState.ENABLED)
                .map(Map.Entry::getKey)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        if (saveList(player, "worldguard.enclosed-regions", selected, "Enclosed regions", selected.size() + " selected")) {
            plugin.editorSounds().cycle(player);
            openEnclosedRegions(player, request.page(), request.filter());
        }
    }

    private void startRegionSearchPrompt(Player player, String currentFilter) {
        TextDialogRequest request = new TextDialogRequest(
                "Search",
                List.of("Enter a WorldGuard region search term."),
                "Search",
                currentFilter,
                "spawn",
                DialogButton.search(),
                DialogButton.cancel(),
                320,
                300,
                64,
                true,
                true,
                false
        );
        openTextInput(player, request, input -> openEnclosedRegions(player, 0, input), () -> openEnclosedRegions(player, 0, currentFilter), "search");
    }

    private void fill(Inventory inventory) {
        ItemStack filler = EditorItemFactory.filler();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private ItemStack toggleItem(Player player, Material material, String name, boolean enabled) {
        return button(player, enabled ? FoStyle.GOOD : FoStyle.BAD, material, name, List.of(
                "{white}State: " + (enabled ? "{good}Enabled" : "{bad}Disabled"),
                "{white}Click to toggle."
        ), "toggle " + name.toLowerCase(Locale.ROOT));
    }

    private ItemStack pearlCooldownModeItem(Player player) {
        return EditorItemFactory.cycle(
                player,
                plugin.messages(),
                "Pearl Cooldown",
                plugin.settings().pearlCooldownMode().configValue(),
                PEARL_COOLDOWN_OPTIONS
        );
    }

    private ItemStack windChargeCooldownModeItem(Player player) {
        return EditorItemFactory.cycle(
                player,
                plugin.messages(),
                "Wind Charge Cooldown",
                plugin.settings().windChargeCooldownMode().configValue(),
                WIND_CHARGE_COOLDOWN_OPTIONS
        );
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.messages().renderTemplate(name, Map.of()));
            List<String> coloredLore = new ArrayList<>();
            for (String line : lore) {
                coloredLore.add(plugin.messages().renderTemplate(line, Map.of()));
            }
            meta.setLore(coloredLore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack button(Player player, String color, Material material, String name, List<String> information, String action) {
        String renderedName = plugin.messages().renderTemplate(name, Map.of());
        List<String> renderedInformation = information == null ? List.of() : information.stream()
                .map(line -> plugin.messages().renderTemplate(line, Map.of()))
                .toList();
        return EditorItemFactory.button(player, material, color, renderedName, renderedInformation, action);
    }

    private String normalizeCommand(String command) {
        String normalized = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.replaceAll("\\s+", " ");
    }

    private String stripMultiplierSuffix(String input) {
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith("x") ? normalized.substring(0, normalized.length() - 1).trim() : normalized;
    }

    private String colorValue() {
        return String.format(Locale.ROOT, "#%02x%02x%02x",
                plugin.settings().worldGuardBorderVisualColor().getRed(),
                plugin.settings().worldGuardBorderVisualColor().getGreen(),
                plugin.settings().worldGuardBorderVisualColor().getBlue());
    }

    private String visibleColorValue() {
        return displayColorLiteral(colorValue());
    }

    private String currentTextMessageValue(PromptType promptType) {
        if (promptType == PromptType.BORDER_VISUAL_COLOR) {
            return visibleColorValue();
        }
        return currentText(promptType);
    }

    private String normalizeColorValue(String input) {
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        if (isNamedColor(normalized)) {
            return normalized;
        }
        String hex = normalized.startsWith("#") ? normalized.substring(1) : normalized;
        if (hex.startsWith("0x")) {
            hex = hex.substring(2);
        }
        return "#" + hex;
    }

    private boolean isValidColorValue(String input) {
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        if (isNamedColor(normalized)) {
            return true;
        }
        String hex = normalized.startsWith("#") ? normalized.substring(1) : normalized;
        if (hex.startsWith("0x")) {
            hex = hex.substring(2);
        }
        return hex.matches("[0-9a-f]{6}");
    }

    private boolean isNamedColor(String value) {
        return switch (value) {
            case "red", "green", "blue", "yellow", "orange", "purple", "pink", "aqua", "cyan", "white" -> true;
            default -> false;
        };
    }

    private void warnNativeFallback(Player player) {
        NativeDialogAvailability availability = plugin.core().nativeDialogs().availability();
        if (!availability.configEnabled()
                || !plugin.core().nativeDialogs().warnOnFallback()
                || plugin.core().nativeDialogs().canUseNativeDialogs()) {
            return;
        }
        if (warnedNativeFallback.add(player.getUniqueId())) {
            plugin.messages().send(player, "native-dialog-fallback");
        }
    }

    private void openCommandDialog(Player player, Prompt prompt) {
        TextDialogRequest request = TextDialogRequest.command(List.of(
                "Enter a command pattern.",
                "Examples: team chat, team *"
        ), "", "team *");
        openTextInput(player, request, input -> handlePrompt(player, prompt, input), () -> cancelPrompt(player, prompt), "command");
    }

    private void openNumberDialog(Player player, Prompt prompt) {
        PromptType promptType = prompt.type();
        String current = currentNumber(promptType);
        TextDialogRequest request = TextDialogRequest.number(List.of(
                "Enter " + promptType.setting() + ".",
                "Range: " + promptType.minText() + " to " + promptType.maxText() + ".",
                "Current: " + current
        ), current, promptType.decimal() ? "1.25" : promptType.minText());
        openTextInput(player, request, input -> handlePrompt(player, prompt, input), () -> cancelPrompt(player, prompt), "number");
    }

    private void openTextDialog(Player player, Prompt prompt) {
        PromptType promptType = prompt.type();
        String current = currentText(promptType);
        TextDialogRequest request = TextDialogRequest.text(promptLines(
                "Enter " + promptType.setting() + ".",
                "Current: " + currentTextMessageValue(promptType) + ".",
                promptType.hint()
        ), current, textPlaceholder(promptType));
        openTextInput(player, request, input -> handlePrompt(player, prompt, input), () -> cancelPrompt(player, prompt), "text");
    }

    private void openTextInput(Player player, TextDialogRequest request, java.util.function.Consumer<String> onSubmit, Runnable onCancel, String type) {
        try {
            Runnable cancelAction = () -> {
                plugin.editorSounds().back(player);
                if (onCancel != null) {
                    onCancel.run();
                }
            };
            boolean nativeOpened = EditorDialogInputs.openTextFromInventory(
                    plugin,
                    plugin.inventoryCloseSuppressor(),
                    plugin.dialogService(),
                    player,
                    request,
                    input -> onSubmit.accept(input == null ? "" : input.trim()),
                    cancelAction
            );
            if (nativeOpened) {
                plugin.editorSounds().open(player);
            } else {
                warnNativeFallback(player);
            }
        } catch (RuntimeException | LinkageError exception) {
            plugin.logWarning("Editor " + type + " input failed: " + exception.getMessage());
            plugin.editorSounds().error(player);
            plugin.messages().send(player, "editor-save-failed", Map.of("{error}", "Input failed"));
            if (onCancel != null) {
                onCancel.run();
            }
        }
    }

    private List<String> promptLines(String... lines) {
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                result.add(line);
            }
        }
        return result;
    }

    private String textPlaceholder(PromptType promptType) {
        return switch (promptType) {
            case BORDER_VISUAL_COLOR -> "#ff3d5a";
            default -> "";
        };
    }

    private static String displayColorLiteral(String value) {
        if (value == null || !value.startsWith("#")) {
            return value == null ? "" : value;
        }
        String hex = value.substring(1);
        return "##" + hex + VISIBLE_COLOR_RESTORE_TOKEN + hex;
    }

    private static String stripColorFormatBreak(String value) {
        return value == null ? "" : value
                .replace(VARIATION_SELECTOR_TEXT, "")
                .replace(LEGACY_COLOR_FORMAT_BREAK, "");
    }

    private enum View {
        MAIN,
        COMMANDS,
        COMBAT_WALL_SETTINGS,
        ENCLOSED_REGIONS
    }

    private enum PromptType {
        ADD_COMMAND("Allowed command", "", 0.0D, 0.0D, "", InputKind.COMMAND, View.COMMANDS, ""),
        DURATION("Combat timer", "combat.duration-seconds", 1.0D, 3600.0D, "s", InputKind.INTEGER, View.MAIN, ""),
        BORDER_CACHE("Combat wall cache", "worldguard.border.cache-seconds", 0.0D, 60.0D, "s", InputKind.INTEGER, View.COMBAT_WALL_SETTINGS, ""),
        PUSHBACK_MULTIPLIER("Pushback multiplier", "worldguard.border.pushback-multiplier", 0.0D, 3.0D, "x", InputKind.DECIMAL, View.COMBAT_WALL_SETTINGS, ""),
        PEARL_COOLDOWN("Pearl cooldown", "combat.pearl-cooldown.seconds", 1.0D, 3600.0D, "s", InputKind.INTEGER, View.MAIN, ""),
        WIND_CHARGE_COOLDOWN("Wind charge cooldown", "combat.wind-charge-cooldown.seconds", 1.0D, 3600.0D, "s", InputKind.INTEGER, View.MAIN, ""),
        BORDER_VISUAL_COLOR("Visual color", "worldguard.border.visual-color", 0.0D, 0.0D, "", InputKind.COLOR, View.COMBAT_WALL_SETTINGS, "Use #RRGGBB or names like red, blue, green, yellow, purple."),
        BORDER_VISUAL_RANGE("Visual range", "worldguard.border.visual-range", 1.0D, 16.0D, "", InputKind.INTEGER, View.COMBAT_WALL_SETTINGS, ""),
        BORDER_RADIUS("Scan radius", "worldguard.border.radius", 2.0D, 16.0D, "", InputKind.INTEGER, View.COMBAT_WALL_SETTINGS, ""),
        BORDER_HEIGHT("Combat wall height", "worldguard.border.height", 1.0D, 12.0D, "", InputKind.INTEGER, View.COMBAT_WALL_SETTINGS, ""),
        PEARL_IMPACT_RADIUS("Pearl impact radius", "worldguard.border.pearl-impact.radius", 1.0D, 8.0D, "", InputKind.INTEGER, View.COMBAT_WALL_SETTINGS, ""),
        PEARL_IMPACT_DURATION("Pearl impact duration", "worldguard.border.pearl-impact.duration-ticks", 1.0D, 100.0D, " ticks", InputKind.INTEGER, View.COMBAT_WALL_SETTINGS, "");

        private final String setting;
        private final String path;
        private final double min;
        private final double max;
        private final String suffix;
        private final InputKind inputKind;
        private final View reopenView;
        private final String hint;

        PromptType(String setting, String path, double min, double max, String suffix, InputKind inputKind, View reopenView, String hint) {
            this.setting = setting;
            this.path = path;
            this.min = min;
            this.max = max;
            this.suffix = suffix;
            this.inputKind = inputKind;
            this.reopenView = reopenView;
            this.hint = hint;
        }

        private String setting() {
            return setting;
        }

        private String path() {
            return path;
        }

        private double min() {
            return min;
        }

        private double max() {
            return max;
        }

        private boolean decimal() {
            return inputKind == InputKind.DECIMAL;
        }

        private boolean text() {
            return inputKind == InputKind.COLOR;
        }

        private View reopenView() {
            return reopenView;
        }

        private String hint() {
            return hint;
        }

        private String minText() {
            return decimal() ? NumberFormatters.money(min) : String.valueOf((int) min);
        }

        private String maxText() {
            return decimal() ? NumberFormatters.money(max) : String.valueOf((int) max);
        }

        private String displayValue(double value) {
            return (decimal() ? NumberFormatters.money(value) : String.valueOf((int) value)) + suffix;
        }

    }

    private enum InputKind {
        COMMAND,
        INTEGER,
        DECIMAL,
        COLOR
    }

    private record Prompt(PromptType type, View reopenView) {
        private static Prompt command() {
            return new Prompt(PromptType.ADD_COMMAND, View.COMMANDS);
        }

        private static Prompt number(PromptType type, View reopenView) {
            return new Prompt(type, reopenView);
        }

        private static Prompt text(PromptType type, View reopenView) {
            return new Prompt(type, reopenView);
        }
    }

    private record CommandEntry(int index, String command) {
    }

    private record CommandBrowserContext() {
    }

    private static final class EditorHolder implements InventoryHolder {
        private final View view;
        private final int page;
        private Inventory inventory;

        private EditorHolder(View view, int page) {
            this.view = view;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        private View view() {
            return view;
        }

        private int page() {
            return page;
        }
    }
}
