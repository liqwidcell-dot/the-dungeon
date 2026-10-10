package club.hackclub.dungeon;

import club.hackclub.dungeon.arena.Arena;
import club.hackclub.dungeon.command.DungeonCommand;
import club.hackclub.dungeon.listener.ProtectionListener;
import club.hackclub.dungeon.listener.RunListener;
import club.hackclub.dungeon.loot.LootTagger;
import club.hackclub.dungeon.run.RunManager;
import club.hackclub.dungeon.ui.RunHud;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point.
 *
 * <p>Paper creates this class, then calls {@link #onEnable()} once the server has
 * finished starting. Everything in this plugin hangs off an instance built here.
 *
 * <p>Wiring order matters: the arena and the run manager both need config, the
 * command needs both of them, and the orphan sweep needs the arena to exist.
 */
public final class DungeonPlugin extends JavaPlugin {

    private Arena arena;
    private RunManager runs;
    private LootTagger tagger;
    private RunHud hud;

    @Override
    public void onEnable() {
        // Copies config.yml out of the JAR on first run only, so edits a server
        // owner has made are never clobbered.
        saveDefaultConfig();

        this.tagger = new LootTagger(this);
        this.hud = new RunHud(this);
        this.runs = new RunManager(this);
        this.arena = new Arena(this);

        // getCommand returns null if plugin.yml and this file disagree about the
        // name. Without this check that becomes a NullPointerException during
        // startup, and the stack trace hides the real cause.
        PluginCommand command = getCommand("dungeon");
        if (command == null) {
            getLogger().severe("Could not find command 'dungeon'. Check plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(new DungeonCommand(this, runs, arena, hud));

        getServer().getPluginManager().registerEvents(new ProtectionListener(arena), this);
        getServer().getPluginManager().registerEvents(new RunListener(runs, tagger, this), this);

        // Build the arena if it is not already standing, then clear out anything
        // a previous crash or reload left behind. Doing the sweep on every start
        // is the only reliable way to avoid a permanent zombie army.
        arena.buildIfNeeded();
        runs.sweepOrphans();

        getLogger().info("DungeonPlugin enabled. Arena at " + arena.centre());
    }

    @Override
    public void onDisable() {
        // End any run in progress so nobody is left stuck in the arena, then
        // drop the chunk tickets this plugin holds.
        runs.endAll();
        if (arena != null) {
            arena.release();
        }
        getLogger().info("DungeonPlugin disabled.");
    }

    // --- accessors, so the other classes can reach shared state ---------------

    public Arena arena() {
        return arena;
    }

    public RunManager runs() {
        return runs;
    }

    public LootTagger tagger() {
        return tagger;
    }

    public RunHud hud() {
        return hud;
    }
}
