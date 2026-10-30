package me.foesio.foCombatTag.listener;

import me.foesio.foCombatTag.FoCombatTag;
import me.foesio.foCombatTag.api.event.CombatTaggedCommandBlockEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Map;

public final class CommandBlockListener implements Listener {
    private final FoCombatTag plugin;

    public CommandBlockListener(FoCombatTag plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.settings().blockCommandsWhileTagged()) {
            return;
        }
        if (!plugin.combatTags().isTagged(event.getPlayer())) {
            return;
        }
        if (event.getPlayer().hasPermission(FoCombatTag.ADMIN_PERMISSION)
                && event.getMessage().toLowerCase(java.util.Locale.ROOT).startsWith("/focombattagadmin ")) {
            return;
        }
        String command = event.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (command.equals("/elytravault") || command.startsWith("/elytravault ")) {
            return;
        }
        if (plugin.settings().isCommandAllowedWhileTagged(event.getMessage())) {
            return;
        }
        CombatTaggedCommandBlockEvent blockEvent = new CombatTaggedCommandBlockEvent(event.getPlayer(), event.getMessage());
        Bukkit.getPluginManager().callEvent(blockEvent);
        if (blockEvent.isCancelled()) {
            return;
        }
        event.setCancelled(true);
        plugin.messages().send(event.getPlayer(), "command-blocked", Map.of(
                "{seconds}", String.valueOf(plugin.combatTags().remainingSeconds(event.getPlayer()))
        ));
    }
}
