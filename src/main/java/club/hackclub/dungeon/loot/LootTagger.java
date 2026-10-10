package club.hackclub.dungeon.loot;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

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
public final class LootTagger {

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
