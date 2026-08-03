package me.foesio.foCombatTag;

import me.foesio.core.FoCoreContext;
import me.foesio.core.FoPluginCore;
import me.foesio.core.dialog.DialogService;
import me.foesio.core.inventory.InventoryCloseSuppressor;
import me.foesio.core.logging.FoFileLogger;
import me.foesio.core.message.FoMessageMigrations;
import me.foesio.core.message.FoMessageService;
import me.foesio.core.reload.FoReloadRegistry;
import me.foesio.core.reload.FoReloadResult;
import me.foesio.core.update.UpdateNoticeService;
import me.foesio.foCombatTag.api.FoCombatTagApi;
import me.foesio.foCombatTag.command.AdminCommand;
import me.foesio.foCombatTag.command.ElytraVaultCommand;
import me.foesio.foCombatTag.config.Settings;
import me.foesio.foCombatTag.editor.EditorGui;
import me.foesio.foCombatTag.hook.FoCombatTagPlaceholderExpansion;
import me.foesio.foCombatTag.hook.WorldGuardHook;
import me.foesio.foCombatTag.listener.CommandBlockListener;
import me.foesio.foCombatTag.listener.CombatListener;
import me.foesio.foCombatTag.listener.PlayerStateListener;
import me.foesio.foCombatTag.service.BorderService;
import me.foesio.foCombatTag.service.CombatTagService;
import me.foesio.foCombatTag.service.ElytraGuardService;
import me.foesio.foCombatTag.service.ElytraVaultService;
import org.bukkit.entity.Player;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class FoCombatTag extends JavaPlugin {
    public static final String DISPLAY_NAME = "FoCombatTag";
    public static final String ADMIN_PERMISSION = "focombattag.admin";
    public static final String MODRINTH_PROJECT_ID = "focombattag";
    private static final int BSTATS_PLUGIN_ID = 32526;

    private FoCoreContext core;
    private Settings settings;
    private FoMessageService messages;
    private FoFileLogger fileLogService;
    private UpdateNoticeService updates;
    private boolean dialogInputsCreated;
    private WorldGuardHook worldGuardHook;
    private BorderService borderService;
    private ElytraVaultService elytraVaultService;
    private ElytraGuardService elytraGuardService;
    private CombatTagService combatTagService;
    private EditorGui editorGui;
    private FoCombatTagPlaceholderExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Settings.migrateConfig(this);

        settings = Settings.load(this);
        fileLogService = FoFileLogger.create(this);
        fileLogService.configure(settings.fileLogging(), true);

        messages = FoMessageService.load(this, messageMigrations());
        refreshCore(true);
        worldGuardHook = new WorldGuardHook(this);
        worldGuardHook.reload();
        borderService = new BorderService(this, worldGuardHook);
        elytraVaultService = new ElytraVaultService(this);
        elytraGuardService = new ElytraGuardService(this);
        combatTagService = new CombatTagService(this, borderService);
        editorGui = new EditorGui(this);

        registerCommands();
        registerListeners();

        FoCombatTagApi.install(this);
        registerPlaceholders();
        elytraVaultService.start();
        combatTagService.start();
        logInfo("Enabled.");
    }

    @Override
    public void onDisable() {
        if (combatTagService != null) {
            combatTagService.shutdown();
        }
        if (elytraVaultService != null) {
            elytraVaultService.shutdown();
        }
        FoCombatTagApi.uninstall();
        unregisterPlaceholders();
        HandlerList.unregisterAll(this);
        updates = null;
        logInfo("Disabled.");
        if (fileLogService != null) {
            fileLogService.shutdown();
        }
        if (core != null) {
            core.close();
            core = null;
        }
    }

    public void reloadPlugin() {
        reloadRuntime(true);
    }

    public void reloadPluginQuietly() {
        reloadRuntime(false);
    }

    private void reloadRuntime(boolean logStatus) {
        FoReloadResult result = FoReloadRegistry.create()
                .addConfig(this)
                .add("config-defaults", () -> Settings.migrateConfig(this))
                .add("settings", () -> settings = Settings.load(this))
                .addMessages(messages)
                .add("file-logging", () -> fileLogService.configure(settings.fileLogging(), false))
                .add("core", () -> refreshCore(false))
                .add("worldguard", () -> worldGuardHook.reload(logStatus))
                .add("border", borderService::reload)
                .add("combat-tags", combatTagService::reload)
                .add("elytra-vault", elytraVaultService::reload)
                .reload();
        if (!result.successful()) {
            throw new IllegalStateException("Reload failed at " + result.failedStep() + ": " + result.errorMessage(), result.error());
        }
        if (logStatus) {
            unregisterPlaceholders();
            registerPlaceholders();
            logInfo("Reloaded config and messages.");
        }
    }

    private void refreshCore(boolean startup) {
        if (updates != null) {
            HandlerList.unregisterAll(updates);
            updates = null;
        }
        if (core != null) {
            core.close();
            core = null;
        }
        dialogInputsCreated = false;
        core = FoPluginCore.create(this);
        core.metrics(BSTATS_PLUGIN_ID);
        updates = core.createUpdateNotices(messages, MODRINTH_PROJECT_ID);
        if (startup) {
            updates.start();
            core.warnIfNativeDialogsUnavailable();
        } else {
            updates.registerJoinListener();
        }
    }

    private void registerCommands() {
        new AdminCommand(this).register();
        ElytraVaultCommand elytraVaultCommand = new ElytraVaultCommand(this);
        PluginCommand vaultCommand = getCommand("elytravault");
        if (vaultCommand != null) {
            vaultCommand.setExecutor(elytraVaultCommand);
        } else {
            logWarning("Missing elytravault command in plugin.yml.");
        }
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new CombatListener(this), this);
        getServer().getPluginManager().registerEvents(new CommandBlockListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerStateListener(this), this);
        registerPaperElytraEquipListener();
        getServer().getPluginManager().registerEvents(editorGui, this);
    }

    private void registerPaperElytraEquipListener() {
        try {
            Class.forName("io.papermc.paper.event.player.PlayerSwapWithEquipmentSlotEvent");
            Class<?> listenerClass = Class.forName("me.foesio.foCombatTag.listener.PaperElytraEquipListener");
            Object listener = listenerClass.getConstructor(FoCombatTag.class).newInstance(this);
            getServer().getPluginManager().registerEvents((org.bukkit.event.Listener) listener, this);
        } catch (ClassNotFoundException ignored) {
            // Bukkit inventory events still cover the standard equip paths.
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            logWarning("Failed to register Paper elytra equip listener: " + exception.getMessage());
        }
    }

    private void registerPlaceholders() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            placeholderExpansion = null;
            return;
        }
        placeholderExpansion = new FoCombatTagPlaceholderExpansion(this);
        placeholderExpansion.register();
        logInfo("PlaceholderAPI hook enabled.");
    }

    private void unregisterPlaceholders() {
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
    }

    private FoMessageMigrations messageMigrations() {
        return FoMessageMigrations.create()
                .remove("update.checking")
                .remove("update.available")
                .remove("update.up-to-date")
                .remove("update.failed")
                .build();
    }

    public Settings settings() {
        return settings;
    }

    public FoMessageService messages() {
        return messages;
    }

    public UpdateNoticeService updates() {
        return updates;
    }

    public WorldGuardHook worldGuardHook() {
        return worldGuardHook;
    }

    public BorderService borderService() {
        return borderService;
    }

    public ElytraVaultService elytraVault() {
        return elytraVaultService;
    }

    public ElytraGuardService elytraGuard() {
        return elytraGuardService;
    }

    public CombatTagService combatTags() {
        return combatTagService;
    }

    public FoCoreContext core() {
        return core;
    }

    public DialogService dialogService() {
        dialogInputsCreated = true;
        return core.dialogService();
    }

    public InventoryCloseSuppressor inventoryCloseSuppressor() {
        return core.inventoryCloseSuppressor();
    }

    public void clearDialogInput(Player player) {
        if (dialogInputsCreated && core != null) {
            core.dialogInputs().clear(player);
        }
    }

    public EditorGui editorGui() {
        return editorGui;
    }

    public void logInfo(String message) {
        getLogger().info("[" + DISPLAY_NAME + "] " + message);
        if (fileLogService != null) {
            fileLogService.info(message);
        }
    }

    public void logWarning(String message) {
        getLogger().warning("[" + DISPLAY_NAME + "] " + message);
        if (fileLogService != null) {
            fileLogService.warn(message);
        }
    }

    public void logError(String message, Throwable throwable) {
        if (throwable == null) {
            getLogger().severe("[" + DISPLAY_NAME + "] " + message);
        } else {
            getLogger().log(Level.SEVERE, "[" + DISPLAY_NAME + "] " + message, throwable);
        }
        if (fileLogService != null) {
            fileLogService.error(message, throwable);
        }
    }
}
