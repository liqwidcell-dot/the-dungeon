/*
 * =========================================================================
 *  THE DUNGEON - the entire plugin in a single file.
 * =========================================================================
 *
 *  Why one file? Because it is easier to hold in your head when every
 *  class is in one place: press Ctrl+F, type a name, and you are there.
 *  The trade-off is that this file is long-ish, so the contents below
 *  is your map. In VSCodium, Ctrl+Shift+O also jumps straight to any
 *  class name listed here.
 *
 *  Java note: only ONE top-level class per file may be `public`, and it
 *  must match the filename. DungeonPlugin is that class. Every other
 *  class below is package-private and lives in the same package, which
 *  is why they can still see each other without any imports.
 *
 *  CONTENTS
 *        115  DungeonPlugin       The plugin itself. Startup, config loading, and wiring everything together.
 *        201  DungeonCommand      Handles /dungeon, /dungeon leave, /dungeon info, /dungeon build.
 *        305  Arena               The box in the sky: its coordinates, its lights, and resetting it.
 *        547  Difficulty          How tough each wave is. One curve, so wave 10 is not just 10x wave 1.
 *        613  DungeonRun          The state of one run: which wave, which player, is it over yet.
 *        761  RunManager          The wave loop. Starts waves, watches for clears, ends the run.
 *       1145  MobFactory          Builds the ordinary wave mobs and scales their health to the wave.
 *       1275  DungeonLord         The final boss and the minions he summons.
 *       1377  LootTable           What each wave and the boss are allowed to drop.
 *       1461  PrizeChest          The chest that appears in the middle of the arena after each wave.
 *       1564  LootTagger          Stamps dungeon loot so only it is lost if you die.
 *       1670  RunHud              The progress bar and wave counter along the top of the screen.
 *       1750  RunListener         Death, mob-kill and disconnect events during a run.
 *       1868  ProtectionListener  Stops wall-breaking, stops natural spawns inside the arena.
 *
 *  Everything is tuned in src/main/resources/config.yml - arena
 *  coordinates, wave count, difficulty curve, loot rates. To change how
 *  the dungeon plays, edit that file, not this one.
 */

package club.hackclub.dungeon;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

// =========================================================================
//  DungeonPlugin
//  The plugin itself. Startup, config loading, and wiring everything together.
// =========================================================================
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

// =========================================================================
//  DungeonCommand
//  Handles /dungeon, /dungeon leave, /dungeon info, /dungeon build.
// =========================================================================
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
final class DungeonCommand implements CommandExecutor, TabCompleter {

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

// =========================================================================
//  Arena
//  The box in the sky: its coordinates, its lights, and resetting it.
// =========================================================================
/**
 * The fixed arena in the sky: a sealed box the player is dropped into.
 *
 * <p>It is built once and reused. Rebuilding every run would mean generating new
 * chunks each time and scattering structures across the map; instead we build on
 * first start, keep the chunks loaded with a plugin chunk ticket, and just clear
 * the mobs between runs.
 *
 * <p>Every coordinate comes from config.yml so the arena can be moved without
 * touching Java.
 */
final class Arena {

    /** Block used to mark "the arena has already been built". */
    private static final Material FLOOR = Material.STONE;
    private static final Material WALL = Material.STONE;
    private static final Material ROOF = Material.STONE;

    private final DungeonPlugin plugin;
    private final World world;
    private final int centreX;
    private final int centreY;
    private final int centreZ;
    private final int radius;
    private final int wallHeight;

    /** Block used for the grid of lights set into the floor and the ceiling. */
    private final Material lightBlock;
    /** How many blocks apart those lights are. Smaller is brighter, and busier. */
    private final int lightSpacing;

    public Arena(DungeonPlugin plugin) {
        this.plugin = plugin;
        this.world = plugin.getServer().getWorld(plugin.getConfig().getString("arena.world", "world"));

        this.centreX = plugin.getConfig().getInt("arena.x", 0);
        this.centreY = plugin.getConfig().getInt("arena.y", 120);
        this.centreZ = plugin.getConfig().getInt("arena.z", 0);
        this.radius = plugin.getConfig().getInt("arena.radius", 12);
        this.wallHeight = plugin.getConfig().getInt("arena.wall-height", 6);
        this.lightBlock = readLightBlock(plugin.getConfig().getString("arena.light-block", "SEA_LANTERN"));
        // A spacing of 1 would be a solid slab of lanterns rather than a grid.
        this.lightSpacing = Math.max(2, plugin.getConfig().getInt("arena.light-spacing", 5));

        if (world == null) {
            plugin.getLogger().severe("Arena world '" +
                    plugin.getConfig().getString("arena.world", "world") + "' is not loaded.");
        }
    }

    /** Centre of the floor. */
    public Location centre() {
        return new Location(world, centreX, centreY, centreZ);
    }

    /** Where the player appears when they run /dungeon. Slightly above the floor. */
    public Location entryPoint() {
        return new Location(world, centreX + 0.5, centreY + 1, centreZ + 0.5);
    }

    /** True if the given location is inside the walls and above the floor. */
    public boolean contains(Location loc) {
        if (loc == null || loc.getWorld() == null || !loc.getWorld().equals(world)) {
            return false;
        }
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        return Math.abs(x - centreX) <= radius
                && Math.abs(z - centreZ) <= radius
                && y >= centreY
                && y <= centreY + wallHeight + 1;
    }

    /**
     * Build the arena if the floor is not already there.
     *
     * <p>Called on every plugin start, so it must be cheap when the arena exists
     * — it checks one block and returns.
     */
    public void buildIfNeeded() {
        if (world == null) {
            return;
        }
        // Force-load before building, or setBlock writes into chunks the server
        // is free to unload underneath us.
        loadChunks();
        if (new Location(world, centreX, centreY, centreZ).getBlock().getType() == FLOOR) {
            // The box is already standing — but it may predate the lighting, so
            // top that up rather than rebuilding the whole arena.
            ensureLighting();
            return;
        }
        build();
    }

    /** Build the box. Public so it can be forced from a command or on demand. */
    public void build() {
        plugin.getLogger().info("Building dungeon arena at " + centreX + "," + centreY + "," + centreZ);

        // Floor and roof.
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                setBlock(centreX + x, centreY, centreZ + z, FLOOR);
                setBlock(centreX + x, centreY + wallHeight + 1, centreZ + z, ROOF);
            }
        }

        // Four walls. The floor is already in place at y == centreY, so the wall
        // loop starts one block above it.
        for (int y = 1; y <= wallHeight + 1; y++) {
            for (int x = -radius; x <= radius; x++) {
                setBlock(centreX + x, centreY + y, centreZ - radius, WALL);
                setBlock(centreX + x, centreY + y, centreZ + radius, WALL);
            }
            for (int z = -radius; z <= radius; z++) {
                setBlock(centreX - radius, centreY + y, centreZ + z, WALL);
                setBlock(centreX + radius, centreY + y, centreZ + z, WALL);
            }
        }

        // A freshly built box has no light at all in it, so it gets its lamps
        // straight away rather than on the next start.
        ensureLighting();
    }

    /**
     * Set a grid of light sources into the floor and the ceiling.
     *
     * <p>This is the difference between a dungeon and a black box. The arena is
     * sealed stone with a roof on it, so no daylight reaches the inside and
     * nothing in the walls emits any: without lamps it is genuinely pitch dark.
     *
     * <p>It runs on every start, not just at build time, so an arena built by an
     * older version of this plugin picks up its lighting without being torn down
     * and rebuilt.
     *
     * <p>The very centre of the floor is deliberately skipped, because that one
     * block is the marker {@link #buildIfNeeded()} uses to tell whether the
     * arena is already standing. Lamplight it and the arena would be rebuilt on
     * every single restart.
     */
    private void ensureLighting() {
        int roofY = centreY + wallHeight + 1;

        // Offsets outward from the centre, mirrored, so the grid stays symmetric
        // even when the radius is not a whole multiple of the spacing.
        List<Integer> offsets = new ArrayList<>();
        for (int distance = 0; distance <= radius; distance += lightSpacing) {
            offsets.add(distance);
            if (distance != 0) {
                offsets.add(-distance);
            }
        }

        int placed = 0;
        for (int offsetX : offsets) {
            for (int offsetZ : offsets) {
                if (lightAt(centreX + offsetX, roofY, centreZ + offsetZ)) {
                    placed++;
                }
                // Floor lamps everywhere except the marker block at dead centre.
                if ((offsetX != 0 || offsetZ != 0)
                        && lightAt(centreX + offsetX, centreY, centreZ + offsetZ)) {
                    placed++;
                }
            }
        }

        if (placed > 0) {
            plugin.getLogger().info("Arena lighting: placed " + placed + " light source(s).");
        }
    }

    /** Place one lamp. Returns true only if that block was not already one. */
    private boolean lightAt(int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() == lightBlock) {
            return false;
        }
        setBlock(x, y, z, lightBlock);
        return true;
    }

    /**
     * Turn the configured block name into a {@link Material}.
     *
     * <p>Falling back to a known-good lamp rather than throwing means a typo in
     * config.yml costs a line in the log, not a server that will not start.
     */
    private Material readLightBlock(String name) {
        Material material = Material.matchMaterial(name == null ? "" : name.trim());
        if (material == null) {
            plugin.getLogger().warning("Unknown arena.light-block '" + name
                    + "', falling back to SEA_LANTERN.");
            return Material.SEA_LANTERN;
        }
        return material;
    }

    private void setBlock(int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(material, false);
    }

    /**
     * Keep the arena's chunks loaded for as long as the plugin is enabled.
     *
     * <p>A chunk ticket is the supported way to hold chunks; without it the
     * server unloads the arena the moment the player teleports away, and the box
     * silently vanishes.
     */
    private void loadChunks() {
        for (int cx = (centreX - radius) >> 4; cx <= (centreX + radius) >> 4; cx++) {
            for (int cz = (centreZ - radius) >> 4; cz <= (centreZ + radius) >> 4; cz++) {
                world.getChunkAt(cx, cz).addPluginChunkTicket(plugin);
            }
        }
    }

    /** Give the chunk tickets back so the server can unload those chunks again. */
    public void release() {
        if (world == null) {
            return;
        }
        for (int cx = (centreX - radius) >> 4; cx <= (centreX + radius) >> 4; cx++) {
            for (int cz = (centreZ - radius) >> 4; cz <= (centreZ + radius) >> 4; cz++) {
                world.getChunkAt(cx, cz).removePluginChunkTicket(plugin);
            }
        }
    }

    public World world() {
        return world;
    }
}

// =========================================================================
//  Difficulty
//  How tough each wave is. One curve, so wave 10 is not just 10x wave 1.
// =========================================================================
/**
 * The difficulty curve.
 *
 * <p>Every number here comes from config.yml, so the whole game can be
 * rebalanced by editing YAML and restarting — no recompiling while you are
 * tuning it.
 *
 * <p>Health is deliberately not {@code wave * k}. That makes waves 1-3 trivial
 * and waves 9-10 impossible. An exponent above 1 keeps early waves survivable
 * and makes the late ones spike, which is what makes a run feel like it is
 * climbing towards something.
 */
final class Difficulty {

    private final double baseHealth;
    private final double healthCoefficient;
    private final double healthExponent;
    private final int baseCount;
    private final double countPerWave;
    private final double baseDamage;
    private final double damagePerWave;

    public Difficulty(DoubleValues v) {
        this.baseHealth = v.baseHealth();
        this.healthCoefficient = v.healthCoefficient();
        this.healthExponent = v.healthExponent();
        this.baseCount = v.baseCount();
        this.countPerWave = v.countPerWave();
        this.baseDamage = v.baseDamage();
        this.damagePerWave = v.damagePerWave();
    }

    /** Max health for a mob on the given wave. */
    public double healthFor(int wave) {
        return Math.round(baseHealth * (1.0 + healthCoefficient * Math.pow(wave, healthExponent)) * 10.0) / 10.0;
    }

    /** How many mobs to spawn on the given wave. */
    public int countFor(int wave) {
        return baseCount + (int) Math.floor(countPerWave * wave);
    }

    /** Attack damage for a mob on the given wave. */
    public double damageFor(int wave) {
        return Math.round((baseDamage + damagePerWave * wave) * 10.0) / 10.0;
    }

    /**
     * The tuning numbers, as read from config.
     *
     * <p>A record rather than seven constructor parameters, because seven
     * adjacent same-typed doubles in a row is genuinely easy to get wrong.
     */
    public record DoubleValues(
            double baseHealth,
            double healthCoefficient,
            double healthExponent,
            int baseCount,
            double countPerWave,
            double baseDamage,
            double damagePerWave) {
    }
}

// =========================================================================
//  DungeonRun
//  The state of one run: which wave, which player, is it over yet.
// =========================================================================
/**
 * The state of one run: who is inside, which wave they are on, and which mobs
 * are still breathing.
 *
 * <p>One of these exists for the whole duration of a run and is thrown away at
 * the end. Nothing here is persisted — if the server restarts mid-run, the run
 * is gone and the player is outside. That is deliberate: a half-finished dungeon
 * is not worth saving, and persisting it would need far more code for a case
 * that should be rare.
 */
final class DungeonRun {

    private final UUID runId = UUID.randomUUID();
    private final UUID playerId;
    private final int finalWave;
    private final long startedAt = System.currentTimeMillis();

    /** Mobs belonging to the wave currently in progress. */
    private final Set<UUID> alive = new LinkedHashSet<>();

    /** Every mob this run has spawned, so they can all be cleaned up at the end. */
    private final Set<UUID> spawned = new LinkedHashSet<>();

    /** 1-based. Equals {@link #finalWave} on the wave before the boss. */
    private int wave = 1;

    private boolean finished;
    private boolean victory;

    private int mobsKilled;
    private int lootFound;

    public DungeonRun(UUID playerId, int finalWave) {
        this.playerId = playerId;
        this.finalWave = finalWave;
    }

    public UUID runId() {
        return runId;
    }

    public UUID playerId() {
        return playerId;
    }

    public int wave() {
        return wave;
    }

    /** True when the next wave to start is the boss. */
    public boolean isBossWave() {
        return wave > finalWave;
    }

    /**
     * The wave number to use when rolling loot.
     *
     * <p>Capped at the final wave because the boss wave is numbered one past it.
     * Without the cap, every one of the Lord's endlessly respawning minions would
     * drop from the top loot tier instead of the wave-10 one.
     */
    public int lootWave() {
        return Math.min(wave, finalWave);
    }

    public int finalWave() {
        return finalWave;
    }

    public void advanceWave() {
        wave++;
    }

    // --- mob bookkeeping ----------------------------------------------------

    public void trackSpawned(UUID entityId) {
        spawned.add(entityId);
        alive.add(entityId);
    }

    public void forget(UUID entityId) {
        alive.remove(entityId);
    }

    public int aliveCount() {
        return alive.size();
    }

    public Set<UUID> aliveIds() {
        return Set.copyOf(alive);
    }

    public Set<UUID> spawnedIds() {
        return Set.copyOf(spawned);
    }

    public void countKill() {
        mobsKilled++;
    }

    public void countLoot(int amount) {
        lootFound += amount;
    }

    // --- lifecycle ----------------------------------------------------------

    public boolean isFinished() {
        return finished;
    }

    public void finish(boolean won) {
        this.finished = true;
        this.victory = won;
    }

    public boolean isVictory() {
        return victory;
    }

    public long elapsedSeconds() {
        return (System.currentTimeMillis() - startedAt) / 1000L;
    }

    /** One-line recap shown when the run ends. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(victory ? "Cleared" : "Fell at wave ").append(victory ? " the dungeon" : " " + wave);
        sb.append(" in ").append(formatDuration(elapsedSeconds()));
        sb.append(" — ").append(mobsKilled).append(mobsKilled == 1 ? " mob" : " mobs");
        sb.append(" killed, ").append(lootFound).append(" item").append(lootFound == 1 ? "" : "s").append(" found.");
        return sb.toString();
    }

    private static String formatDuration(long seconds) {
        long minutes = seconds / 60;
        long remainder = seconds % 60;
        if (minutes == 0) {
            return remainder + "s";
        }
        return minutes + "m " + remainder + "s";
    }
}

// =========================================================================
//  RunManager
//  The wave loop. Starts waves, watches for clears, ends the run.
// =========================================================================
/**
 * Runs the dungeon: starts runs, polls for wave clears, and ends runs.
 *
 * <p>There is at most one active run at a time. The game is solo, so a single
 * slot is simpler than a map and there is no queue to get wrong.
 *
 * <p>The wave loop is driven by a repeating task that checks every few ticks
 * rather than by entity-death events. That is deliberate: a mob can die in ways
 * that never fire a clean event for us (void, despawn, another plugin), and
 * asking "are any of my tracked mobs still alive?" cannot be fooled by that.
 */
final class RunManager {

    private final DungeonPlugin plugin;
    private final Random random = new Random();

    private final Difficulty difficulty;
    private final MobFactory mobFactory;
    private final DungeonLord boss;
    private final LootTable loot;
    private final PrizeChest chest;

    private final NamespacedKey runKey;

    private DungeonRun active;
    private BukkitTask ticker;

    /** Set once the current wave has actually spawned, so we do not advance
     *  through an empty wave before the first mob exists. */
    private boolean waveInProgress;

    /** Set during the breather pause, to avoid advancing twice. */
    private boolean awaitingNextWave;

    /** Kept so the HUD can mirror the boss's health. */
    private LivingEntity bossRef;

    public RunManager(DungeonPlugin plugin) {
        this.plugin = plugin;
        this.difficulty = new Difficulty(new Difficulty.DoubleValues(
                plugin.getConfig().getDouble("difficulty.base-health", 6.0),
                plugin.getConfig().getDouble("difficulty.health-coefficient", 0.30),
                plugin.getConfig().getDouble("difficulty.health-exponent", 1.35),
                plugin.getConfig().getInt("difficulty.base-count", 3),
                plugin.getConfig().getDouble("difficulty.count-per-wave", 0.9),
                plugin.getConfig().getDouble("difficulty.base-damage", 2.0),
                plugin.getConfig().getDouble("difficulty.damage-per-wave", 0.6)));
        this.mobFactory = new MobFactory(plugin);
        this.boss = new DungeonLord(plugin);
        this.loot = new LootTable(plugin);
        this.chest = new PrizeChest(plugin, loot);
        this.runKey = new NamespacedKey(plugin, "dungeon_run");
    }

    public LootTable loot() {
        return loot;
    }

    public DungeonRun active() {
        return active;
    }

    public boolean isInRun(Player player) {
        return active != null
                && !active.isFinished()
                && active.playerId().equals(player.getUniqueId());
    }

    // --- starting ------------------------------------------------------------

    public void start(Player player) {
        if (active != null && !active.isFinished()) {
            player.sendMessage(Component.text("A run is already in progress.", NamedTextColor.RED));
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            player.sendMessage(Component.text("Leave creative mode first.", NamedTextColor.RED));
            return;
        }

        int finalWave = plugin.getConfig().getInt("run.waves", 10);
        if (plugin.arena().world() == null) {
            player.sendMessage(Component.text(
                    "The dungeon arena world is not loaded. Check arena.world in config.yml.",
                    NamedTextColor.RED));
            return;
        }
        this.active = new DungeonRun(player.getUniqueId(), finalWave);
        this.waveInProgress = false;
        this.awaitingNextWave = false;
        this.bossRef = null;

        // Tag the player so the listener can tell whether they are in a run
        // without having to reach into this class from outside.
        player.getPersistentDataContainer().set(runKey, PersistentDataType.STRING, active.runId().toString());

        // The entry point is the middle of the floor, which is also where the
        // prize chest sits. Clear any survivor from a previous run first, or the
        // player lands inside a chest.
        chest.clear();

        player.teleport(plugin.arena().entryPoint());
        player.setHealth(player.getMaxHealth());
        player.getInventory().setHeldItemSlot(0);

        plugin.hud().title(player, "THE DUNGEON", finalWave + " waves stand between you and the Lord.", NamedTextColor.RED);
        player.sendMessage(Component.text("Type /dungeon leave to bail out at any time.", NamedTextColor.DARK_GRAY));

        startWave();
        startTicker();
    }

    // --- the wave loop -------------------------------------------------------

    private void startWave() {
        DungeonRun run = active;
        if (run == null || run.isFinished()) {
            return;
        }
        Player player = Bukkit.getPlayer(run.playerId());
        if (player == null) {
            end(false);
            return;
        }

        Location centre = plugin.arena().centre();
        int radius = plugin.getConfig().getInt("arena.radius", 12);

        if (run.isBossWave()) {
            plugin.hud().title(player, "BOSS", "The Dungeon Lord awakens.", NamedTextColor.DARK_RED);
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_WITHER_SPAWN, 1f, 1f);
            bossRef = boss.spawn(centre.clone().add(0, 1, 0), run);
            // Read the maximum off the entity rather than repeating 400.0 here,
            // so the bar stays correct if the boss's stats are ever retuned.
            var bossMax = bossRef.getAttribute(Attribute.MAX_HEALTH);
            plugin.hud().showBossBar(player, "The Dungeon Lord", bossRef.getHealth(),
                    bossMax != null ? bossMax.getValue() : 400.0);
        } else {
            int count = difficulty.countFor(run.wave());
            for (int i = 0; i < count; i++) {
                Location spot = randomSpot(centre, radius);
                mobFactory.spawn(spot, run.wave(), difficulty, run);
            }
            plugin.hud().title(player,
                    run.wave() == run.finalWave() ? "FINAL WAVE" : "WAVE " + run.wave(),
                    count + " incoming.",
                    NamedTextColor.GOLD);
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ZOMBIE_AMBIENT, 1f, 0.8f);
        }

        waveInProgress = true;
        awaitingNextWave = false;
        plugin.hud().update(player, run);
    }

    private Location randomSpot(Location centre, int radius) {
        // Keep spawns away from the very middle so the player is not instantly
        // surrounded on the first wave.
        int x = random.nextInt(radius * 2) - radius;
        int z = random.nextInt(radius * 2) - radius;
        return centre.clone().add(x + 0.5, 1, z + 0.5);
    }

    private void startTicker() {
        long period = Math.max(1, plugin.getConfig().getInt("run.poll-ticks", 10));
        this.ticker = Bukkit.getScheduler().runTaskTimer(
                plugin, this::tick, period, period);
    }

    private void stopTicker() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    private void tick() {
        DungeonRun run = active;
        if (run == null || run.isFinished()) {
            stopTicker();
            return;
        }

        Player player = Bukkit.getPlayer(run.playerId());
        if (player == null || !player.isOnline()) {
            // The player logged out. Nobody is going to finish the run.
            end(false);
            return;
        }
        if (player.isDead()) {
            // Died mid-wave. PlayerDeathEvent already ended the run; this is the
            // window between the death event and that handler firing, where the
            // alive set is being torn down underneath us.
            return;
        }

        // Prune the alive set. Bukkit.getEntity returns null once an entity is
        // gone for any reason, which is exactly what we want to detect.
        for (UUID id : run.aliveIds()) {
            Entity entity = Bukkit.getEntity(id);
            if (entity == null || entity.isDead() || !entity.isValid()) {
                run.forget(id);
            }
        }

        if (bossRef != null && (bossRef.isDead() || !bossRef.isValid())) {
            bossRef = null;
        }
        if (bossRef != null) {
            plugin.hud().updateBossBar(player, bossRef);
        }

        plugin.hud().update(player, run);

        if (waveInProgress && !awaitingNextWave && run.aliveCount() == 0) {
            waveInProgress = false;
            onWaveCleared(player, run);
        }
    }

    private void onWaveCleared(Player player, DungeonRun run) {
        if (run.isBossWave()) {
            end(true);
            return;
        }

        int breather = Math.max(0, plugin.getConfig().getInt("run.breather-seconds", 5));
        awaitingNextWave = true;

        // The reward for clearing a wave is the prize chest: a chest in the
        // middle of the floor, stocked with that wave's loot tier. It gets
        // bigger and better as the run goes on, which is the whole reason to
        // push one wave further than feels safe.
        run.countLoot(chest.restock(run.wave(), run.runId()));

        // Top the player up between waves. Without this, a long run is decided
        // entirely by who found a golden apple first.
        player.setHealth(player.getMaxHealth());

        run.advanceWave();

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            DungeonRun current = active;
            if (current == run && !current.isFinished()) {
                startWave();
            }
        }, breather * 20L);
    }

    // --- ending --------------------------------------------------------------

    /** End the active run. Safe to call when nothing is running. */
    public void end(boolean victory) {
        DungeonRun run = active;
        if (run == null || run.isFinished()) {
            return;
        }

        Player player = Bukkit.getPlayer(run.playerId());

        // Kill everything this run spawned, so the arena is clean for next time.
        for (UUID id : run.spawnedIds()) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null && !(entity instanceof Player)) {
                entity.remove();
            }
        }

        // And take the prize chest away, loot still sitting in it included.
        chest.clear();

        if (player != null && player.isOnline()) {
            // The whole point of the loss rule: their own gear is untouched,
            // only dungeon loot goes.
            int lost = plugin.tagger().stripFrom(player);
            player.getPersistentDataContainer().remove(runKey);

            plugin.hud().hideBossBar();

            if (victory) {
                var reward = loot.bossReward(run.runId());
                player.getInventory().addItem(reward);
                player.sendMessage(Component.text("Dungeon cleared! You keep the Lord\'s ingot.", NamedTextColor.GREEN));
            } else {
                player.sendMessage(lost == 0
                        ? Component.text("You escaped empty-handed.", NamedTextColor.GRAY)
                        : Component.text("You escaped, but lost " + lost + " dungeon item"
                                + (lost == 1 ? "" : "s") + ".", NamedTextColor.YELLOW));
            }

            plugin.hud().title(player,
                    victory ? "VICTORY" : "DEFEATED",
                    run.summary(),
                    victory ? NamedTextColor.GREEN : NamedTextColor.RED);

            // A dead player cannot be teleported — it throws. This path is
            // reached from endOnDeath one tick after PlayerDeathEvent, and the
            // death animation is still running, so skipping the teleport is the
            // normal case rather than an edge case. Their respawn is unaffected
            // because the arena is a sealed sky box and never contains a bed.
            if (!player.isDead()) {
                player.teleport(player.getWorld().getSpawnLocation());
            }
            player.setFireTicks(0);
        }

        run.finish(victory);
        stopTicker();
        bossRef = null;

        // Clear the slot. Leaving a finished run here means runs.active() keeps
        // handing out a stale run, and anything that reads it has to remember to
        // check isFinished().
        active = null;
    }

    /** Called on quit. The player is already gone, so just tidy up. */
    public void endOnQuit(Player player) {
        DungeonRun run = active;
        if (run != null && !run.isFinished() && run.playerId().equals(player.getUniqueId())) {
            // No teleporting a player who is logging out.
            for (UUID id : run.spawnedIds()) {
                Entity entity = Bukkit.getEntity(id);
                if (entity != null && !(entity instanceof Player)) {
                    entity.remove();
                }
            }
            chest.clear();
            run.finish(false);
            stopTicker();
            bossRef = null;
            active = null;
        }
    }

    /** Called on death. Keeps the gear rule and ends the run. */
    public void endOnDeath(Player player) {
        if (isInRun(player)) {
            end(false);
        }
    }

    public void endAll() {
        end(false);
        stopTicker();
    }

    // --- maintenance ---------------------------------------------------------

    /**
     * Remove anything a previous run left in the world after a crash, a kill -9,
     * or a plugin reload.
     *
     * <p>Without this, one bad shutdown leaves a permanent army of glowing
     * skeletons inside a sealed box, and the next person to run /dungeon cannot
     * clear wave 1. The prize chest is tidied up here for the same reason: it is
     * a block rather than an entity, so an interrupted run can leave one behind.
     */
    public void sweepOrphans() {
        if (plugin.arena().world() == null) {
            return;
        }
        chest.clear();
        int removed = 0;
        for (Entity entity : plugin.arena().world().getEntities()) {
            if (entity instanceof Player) {
                continue;
            }
            if (entity.getPersistentDataContainer().has(runKey, PersistentDataType.STRING)) {
                entity.remove();
                removed++;
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("Swept " + removed + " orphaned dungeon mob(s).");
        }
    }
}

// =========================================================================
//  MobFactory
//  Builds the ordinary wave mobs and scales their health to the wave.
// =========================================================================
/**
 * Procedurally generates a mob for a given wave.
 *
 * <p>"Procedural" here means: the type, stats, gear, and name are all derived
 * from the wave number, so two runs of wave 7 are not identical. It does not
 * mean custom mobs — that would need NMS and a client-side resource pack, which
 * is a very different project.
 *
 * <p>Every mob is stamped with the run ID and its wave. That tag is what lets
 * {@link RunManager#sweepOrphans()} find and remove
 * mobs left behind by a crash or a plugin reload.
 */
final class MobFactory {

    private static final String[] NAME_PREFIXES = {
            "Ancient", "Feral", "Graveborn", "Hollow", "Bloodied", "Shrouded", "Ashen", "Withered"
    };

    private static final String[] NAME_SUFFIXES = {
            "Stalker", "Husk", "Warden", "Crawler", "Revenant", "Gnawer", "Shade", "Thrall"
    };

    private final Random random = new Random();

    private final org.bukkit.NamespacedKey runKey;
    private final org.bukkit.NamespacedKey waveKey;

    public MobFactory(Plugin plugin) {
        this.runKey = new org.bukkit.NamespacedKey(plugin, "dungeon_run");
        this.waveKey = new org.bukkit.NamespacedKey(plugin, "dungeon_wave");
    }

    /**
     * Spawn one procedural mob.
     *
     * @param loc where it appears
     * @param wave 1-based wave number, used for type and stats
     * @param diff the curve
     * @param run  the run this mob belongs to
     */
    public LivingEntity spawn(Location loc, int wave, Difficulty diff, DungeonRun run) {
        LivingEntity mob = create(loc, wave);

        applyStats(mob, wave, diff);
        decorate(mob, wave);

        mob.getPersistentDataContainer().set(runKey, PersistentDataType.STRING, run.runId().toString());
        mob.getPersistentDataContainer().set(waveKey, PersistentDataType.INTEGER, wave);

        run.trackSpawned(mob.getUniqueId());
        return mob;
    }

    /** Pick a type appropriate to the wave. Early waves stay simple. */
    private LivingEntity create(Location loc, int wave) {
        int roll = random.nextInt(100);

        if (wave <= 2) {
            // Waves 1-2: zombies only. A beginner should be able to win these
            // while still learning the controls.
            return loc.getWorld().spawn(loc, Zombie.class);
        }
        if (wave <= 4) {
            if (roll < 60) return loc.getWorld().spawn(loc, Zombie.class);
            return loc.getWorld().spawn(loc, Skeleton.class);
        }
        if (wave <= 7) {
            if (roll < 35) return loc.getWorld().spawn(loc, Zombie.class);
            if (roll < 65) return loc.getWorld().spawn(loc, Skeleton.class);
            if (roll < 85) return loc.getWorld().spawn(loc, Spider.class);
            return loc.getWorld().spawn(loc, Pillager.class);
        }
        if (roll < 30) return loc.getWorld().spawn(loc, Zombie.class);
        if (roll < 55) return loc.getWorld().spawn(loc, Skeleton.class);
        if (roll < 75) return loc.getWorld().spawn(loc, Spider.class);
        if (roll < 92) return loc.getWorld().spawn(loc, Pillager.class);
        return loc.getWorld().spawn(loc, Creeper.class);
    }

    /** Scale the mob to the wave. */
    private void applyStats(LivingEntity mob, int wave, Difficulty diff) {
        double maxHealth = diff.healthFor(wave);

        set(mob, Attribute.MAX_HEALTH, maxHealth);
        set(mob, Attribute.ATTACK_DAMAGE, diff.damageFor(wave));
        set(mob, Attribute.MOVEMENT_SPEED, 0.26);
        // Later waves get tougher to shove around, which stops the whole wave
        // being knocked into a corner by one good sword sweep.
        set(mob, Attribute.KNOCKBACK_RESISTANCE, Math.min(0.8, wave * 0.08));

        // Raising MAX_HEALTH does NOT raise current health. A mob spawned with 20
        // health and given a 90 health maximum still has 20 current, so a wave 10
        // zombie would die to the first sword swing and the difficulty curve would
        // be silently inverted. setHealth is what actually fills the new bar.
        mob.setHealth(maxHealth);
    }

    private void set(LivingEntity mob, Attribute attribute, double value) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    /** Name, glow, and a little gear. */
    private void decorate(LivingEntity mob, int wave) {
        String name = NAME_PREFIXES[random.nextInt(NAME_PREFIXES.length)]
                + " " + NAME_SUFFIXES[random.nextInt(NAME_SUFFIXES.length)];

        mob.customName(Component.text(name, NamedTextColor.RED));
        mob.setCustomNameVisible(true);
        mob.setRemoveWhenFarAway(false);
        mob.setPersistent(false);

        if (mob instanceof Skeleton skeleton) {
            skeleton.getEquipment().setItemInMainHand(new ItemStack(Material.BOW));
            skeleton.getEquipment().setItemInMainHandDropChance(0f);
        } else if (mob instanceof Zombie zombie) {
            if (wave >= 5) {
                // Wave 5+ zombies turn up in armour, which is the visual signal
                // that the run has got serious.
                zombie.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SWORD));
                zombie.getEquipment().setItemInMainHandDropChance(0f);
            }
        } else if (mob instanceof Creeper creeper) {
            creeper.setPowered(wave >= 8);
        }
    }
}

// =========================================================================
//  DungeonLord
//  The final boss and the minions he summons.
// =========================================================================
/**
 * The boss of wave 11.
 *
 * <p>Deliberately not a Wither. A Wither's explosions break blocks, which fights
 * every protection rule the arena relies on — and a boss that can tear its own
 * arena open is a bug waiting to happen. This is a single controllable entity
 * that hits hard and calls for help.
 */
final class DungeonLord {

    private final Plugin plugin;
    private final Random random = new Random();

    private final org.bukkit.NamespacedKey runKey;
    private final org.bukkit.NamespacedKey waveKey;

    public DungeonLord(Plugin plugin) {
        this.plugin = plugin;
        this.runKey = new org.bukkit.NamespacedKey(plugin, "dungeon_run");
        this.waveKey = new org.bukkit.NamespacedKey(plugin, "dungeon_wave");
    }

    /** Spawn the boss and start its minion timer. */
    public LivingEntity spawn(Location loc, DungeonRun run) {
        Zombie boss = loc.getWorld().spawn(loc, Zombie.class);

        boss.customName(Component.text("The Dungeon Lord", NamedTextColor.DARK_RED));
        boss.setCustomNameVisible(true);
        boss.setGlowing(true);
        boss.setRemoveWhenFarAway(false);
        boss.setPersistent(false);

        // Same rule as the regular mobs: raising the maximum alone leaves the
        // entity at its default 20 health, and the whole fight would be over
        // before the bar appeared. setHealth is what fills the new bar.
        final double maxHealth = 400.0;
        set(boss, Attribute.MAX_HEALTH, maxHealth);
        set(boss, Attribute.ATTACK_DAMAGE, 12.0);
        set(boss, Attribute.KNOCKBACK_RESISTANCE, 1.0);
        set(boss, Attribute.MOVEMENT_SPEED, 0.3);
        boss.setHealth(maxHealth);

        boss.getEquipment().setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));
        boss.getEquipment().setItemInMainHandDropChance(0f);

        boss.getPersistentDataContainer().set(runKey, PersistentDataType.STRING, run.runId().toString());
        boss.getPersistentDataContainer().set(waveKey, PersistentDataType.INTEGER, 999);
        run.trackSpawned(boss.getUniqueId());

        startMinions(boss, run);
        return boss;
    }

    /**
     * Summon two skeletons every 10 seconds while the boss lives.
     *
     * <p>A boss that stands still and trades hits is not a fight, it is a
     * DPS check. The minions are what force you to move.
     */
    private void startMinions(LivingEntity boss, DungeonRun run) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (boss.isDead() || !boss.isValid() || run.isFinished()) {
                    cancel();
                    return;
                }
                for (int i = 0; i < 2; i++) {
                    Location spot = boss.getLocation().clone().add(
                            random.nextInt(5) - 2, 0,
                            random.nextInt(5) - 2);

                    Skeleton minion = boss.getWorld().spawn(spot, Skeleton.class);
                    minion.customName(Component.text("Lord's Minion", NamedTextColor.DARK_GRAY));
                    minion.setRemoveWhenFarAway(false);
                    minion.getEquipment().setItemInMainHand(new ItemStack(Material.BOW));

                    set(minion, Attribute.MAX_HEALTH, 30.0);
                    set(minion, Attribute.ATTACK_DAMAGE, 6.0);
                    minion.setHealth(30.0); // fill the new bar, see spawn() above

                    minion.getPersistentDataContainer()
                            .set(runKey, PersistentDataType.STRING, run.runId().toString());
                    minion.getPersistentDataContainer()
                            .set(waveKey, PersistentDataType.INTEGER, 999);
                    run.trackSpawned(minion.getUniqueId());
                }
            }
        }.runTaskTimer(plugin, 200L, 200L); // 10 seconds
    }

    private void set(LivingEntity entity, Attribute attribute, double value) {
        var instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }
}

// =========================================================================
//  LootTable
//  What each wave and the boss are allowed to drop.
// =========================================================================
/**
 * Loot, tiered by wave.
 *
 * <p>The promise of the dungeon is that later waves are worth more, so the table
 * is simply indexed by wave and gets better as the number rises. Five tiers,
 * which is few enough to balance by hand.
 */
final class LootTable {

    /** Drops are random, but we want them varied across a run. */
    private final Random random = new Random();

    private final LootTagger tagger;

    public LootTable(DungeonPlugin plugin) {
        this.tagger = plugin.tagger();
    }

    /** Which tier a wave falls into. */
    private int tierFor(int wave) {
        if (wave <= 2) return 0;
        if (wave <= 4) return 1;
        if (wave <= 7) return 2;
        if (wave <= 10) return 3;
        return 4;
    }

    /**
     * Roll one item for the given wave, already tagged as dungeon loot.
     *
     * @param runId the run to stamp the item with
     */
    public ItemStack roll(int wave, java.util.UUID runId) {
        List<Material> pool = poolFor(tierFor(wave));
        Material chosen = pool.get(random.nextInt(pool.size()));
        return tagger.tag(new ItemStack(chosen), runId);
    }

    /**
     * Roll a handful of items — one per kill.
     *
     * @param count how many to roll
     */
    public List<ItemStack> rollMany(int wave, java.util.UUID runId, int count) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(roll(wave, runId));
        }
        return out;
    }

    /**
     * The boss reward. Fixed rather than rolled, because a boss that gives
     * random junk feels like a bug.
     */
    public ItemStack bossReward(java.util.UUID runId) {
        return tagger.tag(new ItemStack(Material.NETHERITE_INGOT), runId);
    }

    private List<Material> poolFor(int tier) {
        return switch (tier) {
            case 0 -> List.of(Material.BREAD, Material.IRON_INGOT, Material.APPLE, Material.COAL);
            case 1 -> List.of(Material.GOLDEN_APPLE, Material.IRON_SWORD, Material.BONE,
                    Material.STRING, Material.ARROW);
            case 2 -> List.of(Material.ENCHANTED_GOLDEN_APPLE, Material.DIAMOND, Material.GOLDEN_SWORD,
                    Material.DIAMOND_SWORD, Material.BOW, Material.POTION);
            case 3 -> List.of(Material.DIAMOND_SWORD, Material.DIAMOND_CHESTPLATE, Material.ENCHANTED_GOLDEN_APPLE,
                    Material.ENDER_PEARL, Material.TNT);
            default -> List.of(Material.NETHERITE_INGOT, Material.NETHERITE_SCRAP, Material.DIAMOND_BLOCK,
                    Material.ENCHANTED_GOLDEN_APPLE);
        };
    }
}

// =========================================================================
//  PrizeChest
//  The chest that appears in the middle of the arena after each wave.
// =========================================================================
/**
 * The prize chest: one chest, in the middle of the arena floor, restocked at
 * the end of every wave.
 *
 * <p>It replaces handing loot straight into the player's inventory. The
 * difference is the point of it: items that appear in your bag are easy to miss,
 * while a chest you have to walk over and open is a moment in the run.
 *
 * <p>What goes in comes from {@link LootTable}, so the chest follows the same
 * wave tiers as every other drop and gets better the deeper you go. Each item is
 * tagged as dungeon loot, which means it obeys the same rule as everything else
 * the dungeon gave you: lose it if you die, keep it if you leave alive.
 */
final class PrizeChest {

    private final DungeonPlugin plugin;
    private final LootTable loot;

    public PrizeChest(DungeonPlugin plugin, LootTable loot) {
        this.plugin = plugin;
        this.loot = loot;
    }

    /**
     * Where the chest sits: the block directly above the middle of the floor.
     *
     * <p>One block up, not on the floor itself, because the centre floor block
     * is the marker the arena uses to tell whether it has already been built.
     */
    public Location spot() {
        Location centre = plugin.arena().centre();
        return new Location(centre.getWorld(),
                centre.getBlockX(), centre.getBlockY() + 1, centre.getBlockZ());
    }

    /**
     * Put the chest in place and fill it with this wave's loot.
     *
     * <p>Called at the end of every wave, so it has to cope with a chest already
     * standing there from last time — hence the clear before the fill.
     *
     * @return how many items went in, so the run can count them as loot found
     */
    public int restock(int wave, UUID runId) {
        Location location = spot();
        if (location.getWorld() == null) {
            return 0;
        }

        Block block = location.getBlock();
        block.setType(Material.CHEST, false);
        if (!(block.getState() instanceof Chest chest)) {
            // Cannot normally happen: the block was just set to a chest. Bail out
            // rather than throw, because a chest problem must not end a run.
            return 0;
        }

        // getBlockInventory() is the chest's live inventory rather than a copy,
        // so writing to it is all that is needed. The usual `update()` call would
        // push a stale snapshot back over the top and wipe what we just added.
        Inventory inventory = chest.getBlockInventory();
        inventory.clear();

        int base = plugin.getConfig().getInt("run.chest-items-base", 2);
        double perWave = plugin.getConfig().getDouble("run.chest-items-per-wave", 0.5);
        int count = Math.max(1, base + (int) Math.floor(perWave * wave));
        // Never ask for more items than there are slots, or some would be lost.
        count = Math.min(count, inventory.getSize());

        List<ItemStack> items = loot.rollMany(wave, runId, count);

        // Scatter the items across random slots so the chest does not look like a
        // tidy row of loot. Slots are shuffled rather than picked at random,
        // because picking twice can land on the same slot and lose an item.
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inventory.getSize(); i++) {
            slots.add(i);
        }
        Collections.shuffle(slots);
        for (int i = 0; i < items.size(); i++) {
            inventory.setItem(slots.get(i), items.get(i));
        }

        return items.size();
    }

    /** Take the chest away. Whatever is still inside goes with it. */
    public void clear() {
        Location location = spot();
        if (location.getWorld() == null) {
            return;
        }
        Block block = location.getBlock();
        if (!(block.getState() instanceof Chest chest)) {
            return;
        }
        chest.getBlockInventory().clear();
        block.setType(Material.AIR, false);
    }
}

// =========================================================================
//  LootTagger
//  Stamps dungeon loot so only it is lost if you die.
// =========================================================================
/**
 * Marks items the dungeon gave you, so they can be taken away later.
 *
 * <p>This is the whole implementation of the "you only lose dungeon loot" rule,
 * and it is deliberately just a hidden tag rather than a separate inventory
 * ledger. A ledger has to stay in sync with the inventory and will eventually
 * not; a tag travels with the item, so it cannot drift.
 *
 * <p>The tag lives in the item's {@link PersistentDataContainer} under a
 * namespaced key. Players cannot see or forge it in survival.
 */
final class LootTagger {

    private final Plugin plugin;

    /** Key holding the run that produced the item. */
    private final org.bukkit.NamespacedKey runKey;
    /** Presence of this key is what makes an item a dungeon item. */
    private final org.bukkit.NamespacedKey dungeonKey;

    public LootTagger(Plugin plugin) {
        this.plugin = plugin;
        this.runKey = new org.bukkit.NamespacedKey(plugin, "dungeon_run");
        this.dungeonKey = new org.bukkit.NamespacedKey(plugin, "dungeon_loot");
    }

    /** Stamp a single item as dungeon loot. Returns the same stack for chaining. */
    public ItemStack tag(ItemStack item, UUID runId) {
        if (item == null || item.getType().isAir()) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(runKey, PersistentDataType.STRING, runId.toString());
        pdc.set(dungeonKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** True if this stack came out of the dungeon. */
    public boolean isDungeonLoot(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        return meta.getPersistentDataContainer().has(dungeonKey, PersistentDataType.BYTE);
    }

    /**
     * Remove every dungeon item from a player's inventory, armour, and offhand.
     *
     * <p>Used when the player leaves a run alive. Slots are rebuilt into a new
     * array rather than removed in place, because you cannot set a slot to null
     * — you set it to air.
     *
     * @return how many items were removed
     */
    public int stripFrom(Player player) {
        int removed = 0;

        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isDungeonLoot(contents[i])) {
                contents[i] = null;
                removed++;
            }
        }
        player.getInventory().setContents(contents);

        ItemStack[] armour = player.getInventory().getArmorContents();
        for (int i = 0; i < armour.length; i++) {
            if (isDungeonLoot(armour[i])) {
                armour[i] = null;
                removed++;
            }
        }
        player.getInventory().setArmorContents(armour);

        if (isDungeonLoot(player.getInventory().getItemInOffHand())) {
            player.getInventory().setItemInOffHand(null);
            removed++;
        }

        return removed;
    }
}

// =========================================================================
//  RunHud
//  The progress bar and wave counter along the top of the screen.
// =========================================================================
/**
 * Everything the player sees: the action bar, the big titles, and the boss bar.
 *
 * <p>These are three different tools and mixing them up is the usual reason a
 * HUD looks wrong:
 *
 * <ul>
 *   <li><b>Action bar</b> — the thin strip above the hotbar. Continuous
 *       information: which wave you are on, how many mobs are left.</li>
 *   <li><b>Title</b> — the large centred text. Punctuation. Use it for "WAVE 5",
 *       "FINAL WAVE", "VICTORY". Never for anything you update every second.</li>
 *   <li><b>Boss bar</b> — the purple bar at the top. That is for the boss's
 *       actual health; using it to show "7/10" wastes it.</li>
 * </ul>
 *
 * <p>Text is built with Adventure's {@link Component}. The older Bukkit way,
 * {@code player.spigot().sendMessage(ChatMessageType.ACTION_BAR, ...)}, is
 * deprecated and much harder to colour — so if you copy that from a tutorial,
 * translate it rather than copying it.
 */
final class RunHud {

    private final DungeonPlugin plugin;

    private BossBar bossBar;

    public RunHud(DungeonPlugin plugin) {
        this.plugin = plugin;
    }

    /** The little status line above the hotbar. */
    public void update(Player player, DungeonRun run) {
        int finalWave = run.finalWave();
        String waveLabel = run.isBossWave()
                ? "BOSS"
                : "Wave " + run.wave() + "/" + finalWave;

        Component text = Component.text()
                .append(Component.text(waveLabel, NamedTextColor.GOLD))
                .append(Component.text("  •  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(run.aliveCount() + " left", NamedTextColor.WHITE))
                .build();

        player.sendActionBar(text);
    }

    /** Big centred text with a smaller line under it. */
    public void title(Player player, String headline, String subline, NamedTextColor colour) {
        Title title = Title.title(
                Component.text(headline, colour, TextDecoration.BOLD),
                Component.text(subline, NamedTextColor.GRAY),
                Title.Times.times(
                        Duration.ofMillis(200),   // fade in
                        Duration.ofMillis(1400),  // stay
                        Duration.ofMillis(400))); // fade out
        player.showTitle(title);
    }

    /**
     * Show the boss's health. Safe to call repeatedly; creates the bar once.
     *
     * <p>Note the asymmetry: {@code createBossBar} only accepts a legacy String,
     * not an Adventure {@link Component}, so the title is written with the §
     * colour escape. Everything else on the HUD uses Components.
     */
    public void showBossBar(Player player, String name, double health, double maxHealth) {
        String title = "§c" + name;
        if (bossBar == null) {
            bossBar = plugin.getServer().createBossBar(title, BarColor.RED, BarStyle.SEGMENTED_10);
        }
        bossBar.setTitle(title);
        bossBar.setProgress(Math.max(0.0, Math.min(1.0, health / maxHealth)));
        bossBar.addPlayer(player);
    }

    /** Update an existing boss bar from a living entity's current health. */
    public void updateBossBar(Player player, org.bukkit.entity.LivingEntity boss) {
        if (bossBar != null) {
            var health = boss.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            double max = health != null ? health.getValue() : 1.0;
            bossBar.setProgress(Math.max(0.0, Math.min(1.0, boss.getHealth() / max)));
        }
    }

    public void hideBossBar() {
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
    }
}

// =========================================================================
//  RunListener
//  Death, mob-kill and disconnect events during a run.
// =========================================================================
/**
 * The rules that only fire because something happened: a mob died, a player
 * died, a player disconnected.
 */
final class RunListener implements Listener {

    private final RunManager runs;
    private final LootTagger tagger;
    private final DungeonPlugin plugin;
    private final org.bukkit.NamespacedKey runKey;

    public RunListener(RunManager runs, LootTagger tagger, DungeonPlugin plugin) {
        this.runs = runs;
        this.tagger = tagger;
        this.plugin = plugin;
        this.runKey = new org.bukkit.NamespacedKey(plugin, "dungeon_run");
    }

    /**
     * A dungeon mob died: count it, and drop some loot.
     *
     * <p>Loot goes into the killer's inventory directly rather than spawning as
     * a dropped item, so it cannot be lost, picked up by someone else, or blown
     * up by a creeper.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!event.getEntity().getPersistentDataContainer()
                .has(runKey, PersistentDataType.STRING)) {
            return;
        }

        DungeonRun run = runs.active();
        if (run == null || run.isFinished()) {
            return;
        }

        run.countKill();

        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }

        // Loot tiers come from the wave, but the boss wave is numbered one past
        // the final wave, so capping it stops the Lord's endless minions from
        // handing out the top-tier table.
        int rolls = plugin.getConfig().getInt("run.loot-per-kill", 1);
        List<ItemStack> drops = runs.loot().rollMany(run.lootWave(), run.runId(), rolls);
        killer.getInventory().addItem(drops.toArray(new ItemStack[0]));
        run.countLoot(rolls);
    }

    /**
     * The death rule, in full.
     *
     * <p>Bukkit puts everything the player was carrying into {@code getDrops()},
     * which then falls to the ground. Left alone, that means dying in the
     * dungeon costs you your own gear too — which is not the rule we want.
     *
     * <p>So we remove only the tagged dungeon items from the drop list. Whatever
     * remains is the player's own gear, which lands on the floor and is waiting
     * for them on respawn. They lose the dungeon's loot and nothing else.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!runs.isInRun(player)) {
            return;
        }

        List<ItemStack> drops = new ArrayList<>(event.getDrops());
        drops.removeIf(tagger::isDungeonLoot);
        event.getDrops().clear();
        event.getDrops().addAll(drops);

        // setDeathMessage only takes a legacy String, not a Component, so this
        // one line uses the § colour escape rather than the Adventure API.
        event.setDeathMessage("§7" + player.getName() + " fell in the dungeon.");

        // End the run after the death is fully processed, so the mob cleanup and
        // the summary run against a run that is still marked active.
        new BukkitRunnable() {
            @Override
            public void run() {
                runs.endOnDeath(player);
            }
        }.runTaskLater(plugin, 1L);

        // Belt and braces: if the server has keepInventory on, nothing reaches
        // the drops list at all, so strip the tagged items from the inventory
        // directly once they are back.
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    tagger.stripFrom(player);
                }
            }
        }.runTaskLater(plugin, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        runs.endOnQuit(event.getPlayer());
    }
}

// =========================================================================
//  ProtectionListener
//  Stops wall-breaking, stops natural spawns inside the arena.
// =========================================================================
/**
 * Keeps the arena intact and the fight honest.
 *
 * <p>Without this the player can simply mine out of the dungeon, and a creeper
 * on wave 8 can blow a hole in the wall and let the whole thing drain away.
 *
 * <p>Every handler here answers the same question — "is this location inside
 * the arena?" — and acts if so. The check itself lives in
 * {@link Arena#contains}, so there is one definition of the arena, not eight.
 */
final class ProtectionListener implements Listener {

    private final Arena arena;

    public ProtectionListener(Arena arena) {
        this.arena = arena;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (arena.contains(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (arena.contains(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        // Cancel outright rather than filtering the block list. A boss that can
        // partially breach its own arena is worse than one that cannot breach it
        // at all, and the filtering version is easy to get subtly wrong.
        for (var block : event.blockList()) {
            if (arena.contains(block.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** Stops falling sand, creepers, and endermen rearranging the floor. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent event) {
        if (arena.contains(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * Stop the world adding its own monsters to the fight.
     *
     * <p>Only natural spawns are cancelled. Our own mobs arrive with reason
     * CUSTOM, and cancelling those would break the entire wave system.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) {
            return;
        }
        if (arena.contains(event.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (arena.contains(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (arena.contains(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * No flying out of the arena.
     *
     * <p>Only the toggle itself is cancelled. Clearing {@code allowFlight} here
     * would strip the player's ability permanently — creative players never get
     * it back, because nothing re-grants it after they leave.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        if (event.isFlying() && arena.contains(event.getPlayer().getLocation())) {
            event.setCancelled(true);
        }
    }
}
