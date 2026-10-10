# The Dungeon — project context

A **Bukkit/Paper server plugin** written in Java. This is a **Hack Club project**.

## Hard constraints (do not drift on these)

- **This is NOT going on THE NEST.** THE NEST is a Better MC BMC6 NeoForge
  26.1.2 server with 186 mods. It has no Bukkit/Paper on it at all. Do not
  design for it, do not test against it, do not reference its mod list as a
  constraint. This project is self-contained.
- Keep it **beginner-appropriate**. The author is still learning to code. Prefer
  a small number of clear files over a layered architecture. Explain things in
  comments rather than assuming knowledge.
- It is a **portfolio/club project**. It should be understandable and
  explainable to a person reading it cold — not maximally clever.

## What the game is

A solo wave-based dungeon. `/dungeon` teleports the player into a fixed
box arena high in the sky, spawns procedurally generated mobs in 10 escalating
waves, then a boss. Loot improves each wave.

**The loss rule:** dying costs you dungeon loot only — your own inventory is
never at risk. This is implemented by tagging dungeon items with a
`PersistentDataContainer` key and removing only tagged items from
`PlayerDeathEvent#getDrops()`. Do not replace this with a separate bookkeeping
list; the tag travels with the item and cannot drift out of sync.

## Design decisions already made

- **Solo**, one run at a time (a single `active` field in `RunManager`, not a map).
- **Fixed arena position** in config.yml. Built once, reused. Random coordinates
  would generate new chunks every run and scatter structures across the map.
- **Boss is a custom "Dungeon Lord"**, deliberately not a Wither — Wither
  explosions break blocks and would fight every rule in `ProtectionListener`.
- **Difficulty is a curve, not `wave * k`** — see `Difficulty` and the
  `difficulty` block in config.yml. Linear scaling makes early waves trivial and
  late waves impossible.
- **All tuning lives in config.yml.** Rebalancing should never require a recompile.

## Structure

```
club.hackclub.dungeon/
├── DungeonPlugin.java      entry point; wires everything together
├── arena/Arena.java        the fixed box: build, bounds, chunk tickets
├── command/DungeonCommand.java
├── run/  DungeonRun.java   state of one run
│       RunManager.java     the wave loop, endings, orphan sweep
│       └── ← the core file; most changes land here
├── wave/ Difficulty.java   the scaling curve
│        WaveRunner.java    (folded into RunManager for now)
├── mob/  MobFactory.java   procedural mobs
│        DungeonLord.java   the boss
├── loot/ LootTable.java    tiered drops
│        LootTagger.java    PDC tagging + stripping on exit
├── ui/   RunHud.java       action bar, titles, boss bar
└── listener/
    ProtectionListener.java  walls survive; natural spawns blocked
    RunListener.java         mob death loot, the death rule, quit handling
```

## Machine notes

- Local JDK is **27**. Paper 26.2 needs **Java 25**, and a class file from 27
  cannot load on a 25 server. `build.gradle.kts` pins the toolchain to 25 and
  `settings.gradle.kts` uses the foojay resolver so Gradle fetches its own JDK 25
  at `~/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2`. **Do not remove the
  toolchain block** — it compiles fine and then fails at runtime.
- **No Gradle installed.** Always use `./gradlew`.
- Paper versions are **not** `1.21.x` any more — the scheme is now `26.x`.
- VSCodium has no top-level `editor.defaultFormatter`; Prettier is scoped to web
  languages only because it cannot parse Java.

## Second copy

`~/projects/dungeon-sandbox/` is a buildable duplicate of this project with the
docs and agent files removed — a clean copy for experimenting in without
touching the main working copy.
