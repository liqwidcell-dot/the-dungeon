# Journal — Make a Minecraft mod

Pixl project 1562 · <https://pixl.hackclub.com/project/1562>

Journal entries go here as you work. Pixl asks for roughly **100 characters of
writing per hour tracked**, so a couple of real sentences per session is
enough — the writeup writes itself.

Durations below are approximate wall-clock, reconstructed from file timestamps.
Hackatime records the actual tracked time; this file just has to cover it.

---

## 2026-10-09 · Tooling setup and research (~0.4h)

Started by auditing the machine and finding no code editor installed at all —
only Neovim and Vim. Chose VSCodium over Zed and VS Code because it is VS
Code without the telemetry, and because its extension registry (Open VSX) still
carries the Hackatime extension. Checked every extension against Open VSX's API
before installing rather than after, which is how I found that VSCodium now
ships its own Gradle and test-runner extensions that supersede the older
`fwcd.gradle` ones. Set up Java, Gradle, YAML and GitLens support, and pointed
the editor at the same JDK the build uses.

## 2026-10-09 · Paper plugin scaffold (~0.5h)

Researched current Paper tooling and found the platform has moved on from what
I remembered: Paper versions are now `26.2` rather than `1.21.x`, and the
platform requires Java 25 rather than 21. Since this machine only had JDK 27,
and class files from a newer JDK cannot load on an older server, the build would
have compiled fine and then failed at runtime. Solved it with a Gradle
toolchain pinned to 25 plus the foojay resolver, which fetches its own JDK 25.
Scaffolded the project and got a first green build producing a loadable jar.

## 2026-10-09 · Dungeon design (~0.3h)

Designed the game and made the calls that would otherwise have been guessed
later. Solo runs rather than parties, because party state is where this kind of
plugin quietly becomes three times the work. A fixed arena in the sky instead of
random coordinates, so runs do not generate new chunks every time and scatter
structures across the map. A custom boss rather than a Wither, because Wither
explosions break blocks and would fight the whole protection layer. And a
difficulty curve with an exponent rather than `wave * k`, since linear scaling
makes the first three waves trivial and the last three impossible.

## 2026-10-09 · Implementation (~0.8h)

Wrote the full plugin: arena construction with chunk tickets, a run manager that
polls for wave clears instead of trusting death events, procedural mob
generation, a boss that summons minions, tiered loot, the HUD, and the arena
protection listener. The death rule is the part worth writing down — the player
only ever loses dungeon loot, implemented by tagging granted items with a
PersistentDataContainer key and filtering only those out of the death drops, so
their own gear lands on the floor intact. Eight compile errors came from Paper's
split API: most of it has moved to Adventure `Component`, but a few methods like
`createBossBar` and `setDeathMessage` still take legacy strings. Verified the
output jar and confirmed the bytecode was Java 25 rather than 27.

## 2026-10-10 · Why Hackatime recorded nothing (~0.5h)

A setup problem that turned out not to be a setup problem. Hackatime showed zero
tracked time even though the extension was installed, signed in, and the API key
was valid. Checked the key against the API directly before touching anything —
it authenticated fine, so the credential was never the cause.

The answer came from reading the *tracker's own* log file rather than the
editor's. `wakatime-cli` was parsing AI assistant session transcripts on every
single heartbeat, timing out on those sqlite databases, and burning the entire
send budget before the heartbeat ever left the machine. So every send failed
silently while the editor reported nothing wrong. Turning that parsing off made
heartbeats start sending immediately. Also removed a leftover empty API key
setting in the editor config, which was stopping the extension from falling back
to the real key. The lesson worth keeping: the UI said nothing useful, and one
log file answered the whole question in two lines.

## 2026-10-11 · One file instead of thirteen (~0.4h)

Restructured the plugin from thirteen files into one, because keeping track of
which file holds what was costing more than it was worth. Java allows only one
*public* top-level class per file but any number of package-private ones, so
every supporting class now shares a file and a package — same classes, same jar,
no behaviour change.

The merge was done mechanically rather than by hand so nothing could be retyped
wrong: strip each file's `package` and import lines, collect the imports into one
deduplicated block, drop the `public` keyword from the twelve supporting classes,
and stack them with a banner between each. Added a contents block at the top
with real line numbers so a file this size stays navigable.

Verified by unzipping the built jar and counting: all thirteen classes plus the
nested ones are present, and both copies of the project build to byte-identical
jars.

## 2026-10-11 · Running it on a real server (~1.2h)

Up to this point the plugin had only ever been compiled — nobody had joined it.
Stood up a Crafty instance on a VM so it could actually be played: a new Paper
26.2 server, plugin copied into its plugins folder. Two things are worth
recording. First, the server was unreachable from outside even with the host
firewall open, because traffic to a cloud VM is filtered a second time at the
provider's network layer — the fix is an ingress rule in the cloud console, not
anything on the machine, and there is no CLI on the box to add it with. Second,
moving the server onto the port that already worked instead of fighting the
firewall was the right call, and that only took remembering the machine's own
convention: one Minecraft server runs at a time, so the dungeon and the existing
survival server take turns rather than competing for memory.

Also flipped the server to offline mode so cracked launchers can connect, which
turns out to need two settings rather than one. `online-mode=false` on its own
still leaves secure chat profiles switched on, and a modern client without a
Mojang account gets kicked or silently loses chat until
`enforce-secure-profile` is off as well.

## 2026-10-11 · Playtest feedback: lighting and the prize chest (~0.8h)

Played it, and two things came back — one of which was a real oversight rather
than tuning.

The arena was pitch black. It is a sealed stone box floating at y=120, so no
daylight reaches the inside, and nothing I put in it emitted any light either: I
had built the walls and never once thought about the inside being lit. Fixed with
a grid of sea lanterns set into both the floor and the ceiling, and made it run
on every start rather than only when the arena is first built, so the box already
standing in the world picked up its lighting without being torn down and
rebuilt. I did not want to take "the block is there" as proof, so I copied the
saved chunk out of the world file and read the block-light numbers straight out
of its NBT: 9 to 14 across the whole interior, with the lamp grid clearly visible
in the numbers.

The second thing: clearing a wave handed you items straight into your inventory,
which is easy to miss and reads as nothing happening. Replaced with a prize chest
in the middle of the floor, restocked at the end of every wave and stocked from
the same wave-tiered loot table, so it improves the deeper you get. Filling it
had a trap in it. Bukkit block states are snapshots, so the obvious code — write
into the state's inventory and then call `update()` — pushes the stale snapshot
back over the top and leaves the chest empty. The correct call is
`Chest#getBlockInventory()`, which returns the live inventory for a block that is
actually placed, and then not calling `update()` at all.

## 2026-10-11 · Writing down the real numbers (~0.4h)

Went back through the source to write the loot and mob tables down properly, and
found that two of my own comments had drifted away from the code. The config
comment advertises "wave 10 -> ~90 hp"; the curve actually produces 46.3, and
wave 5 is 21.8 rather than the ~28 it claims. I had tuned by feel and never
re-checked the numbers the comment was asserting, so the dungeon was easier than
its own documentation said.

The second find was structural. The netherite loot tier can never drop: the tier
is picked from the wave number, but the number handed to the loot table is capped
at the last normal wave, so the highest tier reachable is the one below it. Waves
8, 9 and 10 therefore all draw from the same pool — the chest stops improving
exactly where it should be getting exciting, and the only netherite in the whole
run is the boss's single fixed ingot.

Also noticed the boss spawns inside the wave-10 prize chest. The chest is
restocked on every wave clear but only removed when the run ends, so wave 10's
chest is still sitting in the middle of the arena when the Lord appears in that
exact block. Harmless, because collision pushes him out, but it reads as a bug.

Method, since it is the reusable part: none of this needed the server. Proving
the lighting meant reading block-light straight out of the saved chunk NBT, and
proving the balance just meant copying the tuning formulas into a script and
printing the table. Re-deriving from the code is the only way to catch a comment
that has gone stale — reading the comment would only have confirmed the comment.

None of the four are fixed yet. The live server still runs 0.2.0 and is mid
playtest, so the plan is to let it stand and roll the fixes into 0.2.1 once the
run has been played through.

---

## Template for the next session

Copy this block, keep the same shape:

```markdown
## YYYY-MM-DD · What you did (~X.Xh)

One or two sentences on what you built and why. Write what you actually did, not
what you planned — the review reads this to judge whether the time was real.
If you got stuck on something and worked out why, that is worth a sentence on its
own; the problems you solved are usually more interesting than the features.
```

## Reminders

- Keep each build in its own folder. Two projects in one directory means their
  hours land on the same submission.
- `.wakatime-project` must read exactly `Make a Minecraft mod` or the time will
  not roll up to project 1562.
- A sandbox copy lives at `~/projects/dungeon-sandbox/` and deliberately reports
  under a different name, so playtesting there does not inflate this project.
