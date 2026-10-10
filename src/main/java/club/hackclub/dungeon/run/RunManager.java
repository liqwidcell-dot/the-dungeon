package club.hackclub.dungeon.run;

import club.hackclub.dungeon.DungeonPlugin;
import club.hackclub.dungeon.loot.LootTable;
import club.hackclub.dungeon.mob.DungeonLord;
import club.hackclub.dungeon.mob.MobFactory;
import club.hackclub.dungeon.ui.RunHud;
import club.hackclub.dungeon.wave.Difficulty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Random;
import java.util.UUID;

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
public final class RunManager {

    private final DungeonPlugin plugin;
    private final Random random = new Random();

    private final Difficulty difficulty;
    private final MobFactory mobFactory;
    private final DungeonLord boss;
    private final LootTable loot;

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
        this.active = new DungeonRun(player.getUniqueId(), finalWave);
        this.waveInProgress = false;
        this.awaitingNextWave = false;
        this.bossRef = null;

        // Tag the player so the listener can tell whether they are in a run
        // without having to reach into this class from outside.
        player.getPersistentDataContainer().set(runKey, PersistentDataType.STRING, active.runId().toString());

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
            plugin.hud().showBossBar(player, "The Dungeon Lord", bossRef.getHealth(), 400.0);
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

        // A breather reward, so clearing a wave always feels like something.
        int rolls = Math.max(1, plugin.getConfig().getInt("run.loot-per-wave", 2));
        List<org.bukkit.inventory.ItemStack> reward = loot.rollMany(run.wave(), run.runId(), rolls);
        player.getInventory().addItem(reward.toArray(new org.bukkit.inventory.ItemStack[0]));
        run.countLoot(rolls);

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

            player.teleport(player.getWorld().getSpawnLocation());
            player.setFireTicks(0);
        }

        run.finish(victory);
        stopTicker();
        bossRef = null;
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
     * Remove any dungeon mobs left in the world by a crash, a kill -9, or a
     * plugin reload.
     *
     * <p>Without this, one bad shutdown leaves a permanent army of glowing
     * skeletons inside a sealed box, and the next person to run /dungeon cannot
     * clear wave 1.
     */
    public void sweepOrphans() {
        if (plugin.arena().world() == null) {
            return;
        }
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
