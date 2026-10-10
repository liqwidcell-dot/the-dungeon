# The Dungeon

A wave-based survival dungeon, built as a **Bukkit/Paper server plugin**.

> **This is not a Forge or Fabric mod.** It is a Paper plugin written in Java,
> so it runs on a Paper server rather than being installed into a Minecraft
> client. Server owners drop one `.jar` into `plugins/`; players join normally
> and never install anything.

A Hack Club project.

## Play it

You need a Paper server (or the fork of this trial). It is not playable in
vanilla Minecraft on its own.

```bash
./gradlew runPaper
```

Starts a real Paper server with the plugin installed. In the console:

```
op YourName
```

Join on `localhost`, then:

| Command | |
|---|---|
| `/dungeon` | Start a run |
| `/dungeon leave` | Bail out. You keep your gear |
| `/dungeon info` | How the run is going |
| `/dungeon build` | Rebuild the arena (op only) |

**The run:** you are dropped into a sealed box high in the sky. Ten waves of
procedurally generated mobs, then the Dungeon Lord. Every wave clears into a
short breather where you heal and get a small reward.

**You only ever lose dungeon loot.** Dying drops your own gear on the floor
where you can pick it back up, but anything the dungeon gave you is gone. The
arena being sealed means the stakes are your time, not your inventory.

## How it is built

```
src/main/java/club/hackclub/dungeon/
├── DungeonPlugin.java          entry point
├── arena/Arena.java            the box: builds it, holds its bounds
├── run/DungeonRun.java         one run: player, wave, mobs alive
│   └──RunManager.java          the wave loop, endings, orphan sweep
├── wave/Difficulty.java        the scaling curve
├── mob/MobFactory.java         procedural mob generation
│   └──DungeonLord.java         the boss
├── loot/LootTable.java         tiered drops
│   └──LootTagger.java          tags loot so it can be taken back
├── ui/RunHud.java              action bar, titles, boss bar
├── command/DungeonCommand.java
└── listener/
    ├──ProtectionListener.java  walls survive; no wild spawns
    └──RunListener.java         mob death loot, death rule, disconnect
```

`RunManager` is the file to read first. Everything about the wave loop lives
there.

## Things worth knowing

**Tuning is config, not code.** Waves, mob counts, health and damage curves,
loot rates, breather length, and the arena position are all in `config.yml`.
Rebalancing never needs a recompile — edit, restart, play.

**Mobs are tagged, not tracked in a list.** Every spawned mob carries its run ID
in a `PersistentDataContainer`. That is what lets `RunManager.sweepOrphans()`
clean up after a crash, and it is why a server lag can't desync the wave counter.

**Natural spawns are cancelled inside the arena.** Otherwise phantoms and stray
mobs wander in and you can't tell what's yours.

**The arena holds chunk tickets.** Without them the server unloads the chunks
the moment you leave and the box silently disappears.

**The boss is not a Wither.** A Wither explodes blocks, which fights the whole
protection layer. The Dungeon Lord is a single controllable entity that calls
for minions.

## Building

```bash
./gradlew build
```

Output: `build/libs/hackclub-plugin-0.1.0.jar` — that JAR is the whole plugin.
Drop it in a Paper server's `plugins/` folder and restart.
