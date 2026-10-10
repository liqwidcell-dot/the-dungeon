package club.hackclub.dungeon.listener;

import club.hackclub.dungeon.arena.Arena;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;

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
public final class ProtectionListener implements Listener {

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

    /** No flying out of the arena. Also stops flight being left on afterwards. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (arena.contains(player.getLocation()) && player.getAllowFlight()) {
            event.setCancelled(true);
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }
}
