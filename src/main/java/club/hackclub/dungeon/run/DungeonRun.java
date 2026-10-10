package club.hackclub.dungeon.run;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

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
public final class DungeonRun {

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
