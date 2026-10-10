package club.hackclub.dungeon.listener;

import club.hackclub.dungeon.DungeonPlugin;
import club.hackclub.dungeon.loot.LootTagger;
import club.hackclub.dungeon.run.DungeonRun;
import club.hackclub.dungeon.run.RunManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;

/**
 * The rules that only fire because something happened: a mob died, a player
 * died, a player disconnected.
 */
public final class RunListener implements Listener {

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
