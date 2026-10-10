package club.hackclub.dungeon.loot;

import club.hackclub.dungeon.DungeonPlugin;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Loot, tiered by wave.
 *
 * <p>The promise of the dungeon is that later waves are worth more, so the table
 * is simply indexed by wave and gets better as the number rises. Five tiers,
 * which is few enough to balance by hand.
 */
public final class LootTable {

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
