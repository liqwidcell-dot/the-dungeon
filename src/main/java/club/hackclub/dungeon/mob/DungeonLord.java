package club.hackclub.dungeon.mob;

import club.hackclub.dungeon.run.DungeonRun;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Random;

/**
 * The boss of wave 11.
 *
 * <p>Deliberately not a Wither. A Wither's explosions break blocks, which fights
 * every protection rule the arena relies on — and a boss that can tear its own
 * arena open is a bug waiting to happen. This is a single controllable entity
 * that hits hard and calls for help.
 */
public final class DungeonLord {

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
