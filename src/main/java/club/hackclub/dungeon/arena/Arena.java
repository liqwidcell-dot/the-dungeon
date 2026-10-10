package club.hackclub.dungeon.arena;

import club.hackclub.dungeon.DungeonPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

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
public final class Arena {

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

    public Arena(DungeonPlugin plugin) {
        this.plugin = plugin;
        this.world = plugin.getServer().getWorld(plugin.getConfig().getString("arena.world", "world"));

        this.centreX = plugin.getConfig().getInt("arena.x", 0);
        this.centreY = plugin.getConfig().getInt("arena.y", 120);
        this.centreZ = plugin.getConfig().getInt("arena.z", 0);
        this.radius = plugin.getConfig().getInt("arena.radius", 12);
        this.wallHeight = plugin.getConfig().getInt("arena.wall-height", 6);

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
