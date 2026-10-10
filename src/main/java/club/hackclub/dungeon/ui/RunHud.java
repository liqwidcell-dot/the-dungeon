package club.hackclub.dungeon.ui;

import club.hackclub.dungeon.DungeonPlugin;
import club.hackclub.dungeon.run.DungeonRun;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.time.Duration;

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
public final class RunHud {

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
