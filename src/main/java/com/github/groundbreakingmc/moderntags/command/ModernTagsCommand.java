package com.github.groundbreakingmc.moderntags.command;

import com.github.groundbreakingmc.moderntags.ModernTags;
import com.github.groundbreakingmc.mylib.command.CommandUtils;
import com.github.groundbreakingmc.mylib.updateschecker.UpdatesChecker;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.logging.Level;

/**
 * Handles configuration reloads and update downloads for ModernTags.
 */
public final class ModernTagsCommand implements TabExecutor {

    private final ModernTags plugin;
    private final UpdatesChecker updatesChecker;

    /**
     * Creates the command using the same checker as the startup update check.
     *
     * @param plugin         the plugin whose configuration and JAR are managed
     * @param updatesChecker the shared checker for update operations
     */
    public ModernTagsCommand(@NotNull ModernTags plugin, @NotNull UpdatesChecker updatesChecker) {
        this.plugin = plugin;
        this.updatesChecker = updatesChecker;
    }

    /**
     * Reloads configuration, or checks for and downloads an update asynchronously.
     *
     * @param sender  the command sender
     * @param command the registered command
     * @param label   the command name or alias used
     * @param args    the subcommand arguments
     * @return true after handling the command or displaying an error
     */
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission("moderntags.admin")) {
            sender.sendMessage(Bukkit.permissionMessage());
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("reload")) {
            this.plugin.reload().whenComplete((result, error) -> {
                if (error != null) {
                    sender.sendMessage("§cFailed to reload configuration. See the console for details.");
                    this.plugin.getLogger().log(Level.SEVERE, "Error reloading configuration", error);
                } else {
                    sender.sendMessage("§aModernTags configuration reloaded successfully!");
                }
            });
        } else if (args[0].equalsIgnoreCase("update")) {
            sender.sendMessage("§eChecking for updates...");
            this.updatesChecker.check(true).whenComplete((status, error) -> {
                if (error != null) {
                    sender.sendMessage("§cFailed to check or download the update. See the console for details.");
                    return;
                }
                sender.sendMessage(switch (status) {
                    case NO_UPDATE -> "§aNo updates were found.";
                    case DOWNLOADED -> "§aUpdate downloaded. Restart the server to install it.";
                    case BUSY -> "§eAn update check or download is already in progress. Try again shortly.";
                    default -> "§cThe update could not be downloaded. See the console for details.";
                });
            });
        } else {
            sender.sendMessage("§eUsage: /" + label + " <reload|update>");
        }

        return true;
    }

    /**
     * Suggests permitted subcommands using the current configuration.
     * The update suggestion is hidden when automatic downloads are enabled.
     *
     * @param sender  the command sender
     * @param command the registered command
     * @param label   the command name or alias used
     * @param args    the arguments being completed
     * @return matching subcommands, or an empty list for later arguments
     */
    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                               @NotNull String label, @NotNull String @NotNull [] args) {
        if (args.length != 1 || !sender.hasPermission("moderntags.admin")) return List.of();
        if (this.plugin.configValues().autoDownloadUpdates())
            return CommandUtils.tabComplete(args[0], List.of("reload"));
        return CommandUtils.tabComplete(args[0], List.of("reload", "update"));
    }
}
