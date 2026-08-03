package me.foesio.foCombatTag.command;

import me.foesio.core.command.FoAdminArguments;
import me.foesio.core.command.FoAdminCommand;
import me.foesio.core.command.FoAdminCommandContext;
import me.foesio.core.command.FoAdminMessages;
import me.foesio.core.command.FoAdminSubcommand;
import me.foesio.foCombatTag.FoCombatTag;
import me.foesio.foCombatTag.service.CombatTagService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AdminCommand {
    private static final List<String> TAG_TARGET_OPTIONS = List.of("list", "all");
    private static final List<String> UNTAG_TARGET_OPTIONS = List.of("all");

    private final FoCombatTag plugin;

    public AdminCommand(FoCombatTag plugin) {
        this.plugin = plugin;
    }

    public void register() {
        FoAdminCommand.builder(plugin, plugin.messages())
                .commandName("focombattagadmin")
                .permission(FoCombatTag.ADMIN_PERMISSION)
                .updates(plugin.updates())
                .adminMessages(FoAdminMessages.builder()
                        .generalNoPermission("no-permission", "{prefix}{bad}No permission.")
                        .generalPlayerOnly("player-only", "{prefix}{bad}Player only.")
                        .usage("admin-usage", "{prefix}{bad}Use: {theme}/{label} {usage}")
                        .commandMissing("admin-command-missing", "{prefix}{bad}Missing admin command in plugin.yml: {theme}{command}")
                        .commandFailed("admin-command-failed", "{prefix}{bad}Command failed. {muted}{error}")
                        .build())
                .defaultExecutor(context -> {
                    usage(context.sender(), context.label(), "<version|reload|editor|tag|untag|status>");
                    return true;
                })
                .addSubcommand(FoAdminSubcommand.builder("reload", this::reload)
                        .usage("reload")
                        .build())
                .addSubcommand(FoAdminSubcommand.builder("editor", this::openEditor)
                        .usage("editor")
                        .playerOnly()
                        .build())
                .addSubcommand(FoAdminSubcommand.builder("tag", this::handleTag)
                        .usage("tag <list|all|player>")
                        .tabCompleter(this::completeTag)
                        .build())
                .addSubcommand(FoAdminSubcommand.builder("untag", this::handleUntag)
                        .usage("untag <all|player>")
                        .tabCompleter(this::completeUntag)
                        .build())
                .addSubcommand(FoAdminSubcommand.builder("status", this::handleStatus)
                        .usage("status <player>")
                        .tabCompleter(context -> context.args().length == 2 ? FoAdminArguments.onlinePlayer().complete(context.arg(1)) : List.of())
                        .build())
                .register();
    }

    private boolean reload(FoAdminCommandContext context) {
        CommandSender sender = context.sender();
        plugin.messages().send(sender, "reload-started");
        try {
            plugin.reloadPlugin();
            plugin.messages().send(sender, "reload-complete");
        } catch (RuntimeException exception) {
            plugin.logError("Reload failed: " + exception.getMessage(), exception);
            plugin.messages().send(sender, "reload-failed", Map.of("{error}", exception.getMessage()));
        }
        return true;
    }

    private boolean openEditor(FoAdminCommandContext context) {
        Player player = context.playerOrNull();
        plugin.editorGui().open(player);
        plugin.messages().send(player, "editor-opened");
        return true;
    }

    private boolean handleTag(FoAdminCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        if (args.length < 2) {
            usage(sender, context.label(), "tag <list|all|player>");
            return true;
        }
        if (args[1].equalsIgnoreCase("list")) {
            sendTagList(sender);
            return true;
        }
        if (args[1].equalsIgnoreCase("all")) {
            int count = plugin.combatTags().tagAll(Bukkit.getOnlinePlayers());
            plugin.messages().send(sender, "tagged-all", Map.of("{count}", String.valueOf(count)));
            plugin.logInfo(sender.getName() + " tagged all online players.");
            return true;
        }
        Player target = FoAdminArguments.onlinePlayer().parse(args[1]).orElse(null);
        if (target == null) {
            plugin.messages().send(sender, "unknown-player");
            return true;
        }
        if (plugin.combatTags().tagManual(target)) {
            plugin.messages().send(sender, "tagged-player", Map.of(
                    "{player}", target.getName(),
                    "{seconds}", String.valueOf(plugin.settings().combatDurationSeconds())
            ));
            plugin.logInfo(sender.getName() + " tagged " + target.getName() + ".");
        } else {
            plugin.messages().send(sender, "tag-cancelled");
        }
        return true;
    }

    private boolean handleUntag(FoAdminCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        if (args.length < 2) {
            usage(sender, context.label(), "untag <all|player>");
            return true;
        }
        if (args[1].equalsIgnoreCase("all")) {
            int count = plugin.combatTags().untagAll(true);
            plugin.messages().send(sender, "untagged-all", Map.of("{count}", String.valueOf(count)));
            plugin.logInfo(sender.getName() + " untagged all players.");
            return true;
        }
        Player target = FoAdminArguments.onlinePlayer().parse(args[1]).orElse(null);
        if (target == null) {
            plugin.messages().send(sender, "unknown-player");
            return true;
        }
        plugin.combatTags().untag(target, true);
        plugin.messages().send(sender, "untagged-player", Map.of("{player}", target.getName()));
        plugin.logInfo(sender.getName() + " untagged " + target.getName() + ".");
        return true;
    }

    private boolean handleStatus(FoAdminCommandContext context) {
        CommandSender sender = context.sender();
        String[] args = context.args();
        if (args.length < 2) {
            usage(sender, context.label(), "status <player>");
            return true;
        }
        Player target = FoAdminArguments.onlinePlayer().parse(args[1]).orElse(null);
        if (target == null) {
            plugin.messages().send(sender, "unknown-player");
            return true;
        }
        CombatTagService.TagView view = plugin.combatTags().view(target);
        if (view == null) {
            plugin.messages().send(sender, "status-online-clear", Map.of("{player}", target.getName()));
            return true;
        }
        plugin.messages().send(sender, "status-online-tagged", Map.of(
                "{player}", target.getName(),
                "{seconds}", String.valueOf(view.remainingSeconds()),
                "{opponent}", view.opponentName(),
                "{cause}", view.cause().name().toLowerCase(Locale.ROOT)
        ));
        return true;
    }

    private List<String> completeTag(FoAdminCommandContext context) {
        if (context.args().length != 2) {
            return List.of();
        }
        List<String> options = new ArrayList<>(FoAdminArguments.completeOptions(TAG_TARGET_OPTIONS, context.arg(1)));
        options.addAll(FoAdminArguments.onlinePlayer().complete(context.arg(1)));
        return options;
    }

    private List<String> completeUntag(FoAdminCommandContext context) {
        if (context.args().length != 2) {
            return List.of();
        }
        List<String> options = new ArrayList<>(FoAdminArguments.completeOptions(UNTAG_TARGET_OPTIONS, context.arg(1)));
        options.addAll(FoAdminArguments.onlinePlayer().complete(context.arg(1)));
        return options;
    }

    private void sendTagList(CommandSender sender) {
        List<CombatTagService.TagView> tags = plugin.combatTags().snapshot();
        if (tags.isEmpty()) {
            plugin.messages().send(sender, "tag-list-empty");
            return;
        }
        plugin.messages().send(sender, "tag-list-header", Map.of("{count}", String.valueOf(tags.size())));
        for (CombatTagService.TagView tag : tags) {
            plugin.messages().send(sender, "tag-list-entry", Map.of(
                    "{player}", tag.playerName(),
                    "{seconds}", String.valueOf(tag.remainingSeconds())
            ));
        }
    }

    private void usage(CommandSender sender, String label, String usage) {
        plugin.messages().send(sender, "bad-syntax", Map.of("{usage}", "/" + label + " " + usage));
    }

}
