package club.hackclub.dungeon.mob;

import club.hackclub.dungeon.run.DungeonRun;
import club.hackclub.dungeon.wave.Difficulty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Random;

/**
 * Procedurally generates a mob for a given wave.
 *
 * <p>"Procedural" here means: the type, stats, gear, and name are all derived
 * from the wave number, so two runs of wave 7 are not identical. It does not
 * mean custom mobs — that would need NMS and a client-side resource pack, which
 * is a very different project.
 *
 * <p>Every mob is stamped with the run ID and its wave. That tag is what lets
 * {@link club.hackclub.dungeon.run.RunManager#sweepOrphans()} find and remove
 * mobs left behind by a crash or a plugin reload.
 */
public final class MobFactory {

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
