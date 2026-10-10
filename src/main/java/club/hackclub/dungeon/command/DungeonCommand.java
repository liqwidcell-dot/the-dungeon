package club.hackclub.dungeon.command;

import club.hackclub.dungeon.DungeonPlugin;
import club.hackclub.dungeon.arena.Arena;
import club.hackclub.dungeon.run.RunManager;
import club.hackclub.dungeon.ui.RunHud;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@code /dungeon} — the only entry point to the game.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code /dungeon} — start a run</li>
 *   <li>{@code /dungeon leave} — bail out, keeping your gear</li>
 *   <li>{@code /dungeon info} — what the run currently looks like</li>
 *   <li>{@code /dungeon build} — rebuild the arena (op only)</li>
 * </ul>
 */
public final class DungeonCommand implements CommandExecutor, TabCompleter {

    private final DungeonPlugin plugin;
    private final RunManager runs;
    private final Arena arena;
    private final RunHud hud;

    public DungeonCommand(DungeonPlugin plugin, RunManager runs, Arena arena, RunHud hud) {
        this.plugin = plugin;
        this.runs = runs;
        this.arena = arena;
        this.hud = hud;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Everything here happens in the world, so the console has no use for it.
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can run a dungeon.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            runs.start(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "leave", "quit", "exit" -> leave(player);
            case "info" -> info(player);
            case "build" -> build(player);
            default -> help(player);
        }
        return true;
    }

    private void leave(Player player) {
        if (!runs.isInRun(player)) {
            player.sendMessage(Component.text("You are not in a dungeon.", NamedTextColor.GRAY));
            return;
        }
        player.sendMessage(Component.text("You back out of the dungeon.", NamedTextColor.YELLOW));
        runs.end(false);
    }

    private void info(Player player) {
        var run = runs.active();
        if (run == null || run.isFinished() || !runs.isInRun(player)) {
            player.sendMessage(Component.text("No run in progress.", NamedTextColor.GRAY));
            return;
        }
        player.sendMessage(Component.text(run.summary(), NamedTextColor.AQUA));
    }

    private void build(Player player) {
        if (!player.hasPermission("dungeon.admin")) {
            player.sendMessage(Component.text("You do not have permission to do that.", NamedTextColor.RED));
            return;
        }
        if (runs.isInRun(player)) {
            player.sendMessage(Component.text("Leave the dungeon before rebuilding it.", NamedTextColor.RED));
            return;
        }
        runs.sweepOrphans();
        arena.build();
        player.sendMessage(Component.text("Arena rebuilt at " + arena.centre().getBlockX()
                + ", " + arena.centre().getBlockY()
                + ", " + arena.centre().getBlockZ(), NamedTextColor.GREEN));
    }

    private void help(Player player) {
        player.sendMessage(Component.text("--- The Dungeon ---", NamedTextColor.GOLD));
        player.sendMessage(Component.text("/dungeon", NamedTextColor.YELLOW)
                .append(Component.text("  start a run", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("/dungeon leave", NamedTextColor.YELLOW)
                .append(Component.text("  bail out, keep your gear", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("/dungeon info", NamedTextColor.YELLOW)
                .append(Component.text("  how your run is going", NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return List.of("leave", "info", "help");
        }
        return List.of();
    }
}
