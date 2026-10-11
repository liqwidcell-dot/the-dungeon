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
