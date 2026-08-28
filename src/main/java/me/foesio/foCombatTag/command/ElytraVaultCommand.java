package me.foesio.foCombatTag.command;

import me.foesio.foCombatTag.FoCombatTag;
import me.foesio.foCombatTag.service.ElytraVaultService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

public final class ElytraVaultCommand implements CommandExecutor {
    private final FoCombatTag plugin;

    public ElytraVaultCommand(FoCombatTag plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        if (!plugin.elytraVault().hasItems(player)) {
            plugin.messages().send(player, "elytra-vault-empty");
            return true;
        }

        ElytraVaultService.ClaimResult result = plugin.elytraVault().claim(player);
        if (result.remaining() <= 0) {
            plugin.messages().send(player, "elytra-vault-claimed", Map.of(
                    "{count}", String.valueOf(result.claimed())
            ));
            if (result.claimed() > 0) {
                plugin.sounds().playWithPitchVariation(player, "elytra.claimed", 0.05f);
            }
            return true;
        }

        plugin.messages().send(player, "elytra-vault-inventory-full", Map.of(
                "{claimed}", String.valueOf(result.claimed()),
                "{remaining}", String.valueOf(result.remaining())
        ));
        if (result.claimed() > 0) {
            plugin.sounds().playWithPitchVariation(player, "elytra.claimed", 0.05f);
        }
        return true;
    }
}
