# Changelog

## 2026-09-27

### Fixed — results you may have been trusting

- **The positive control could be passed by the body's own flag.** `assertDetectorWorks()` accepted any
  verbose line since the body's mark as proof the detector works, then marked past it — so a module that
  flagged during the body printed *positive control OK* and then *anticheat clean*. A flag from the body now
  fails the scenario before the control cheats. If you quoted a clean result from a scenario that calls
  `assertDetectorWorks()` after its body, re-run it.
- **`TestEnemy.deaths()` always returned 0.** It looked for `died and respawned`; the server prints
  `died and rejoined`. Every "killed it N times" in the combat examples read zero.
- **The combat examples never fought.** They held the attack key, and vanilla swings at an entity once per
  press — so each "fight" was one blow and then standing still. They use the new `scenario.attackFor(ticks)`,
  which clicks each time the swing has recharged, and the frozen-dummy example now fails if nothing died.
- **`TestEnemy.hits()` counted swings the server refused.** A creative or invulnerable client, or one still
  in its hurt frames, took no damage and no knockback, and the swing still counted as a hit. `hits()` is now
  blows that landed; `refused()` counts the rest.
- **Hit and death lines stopped after 400 per bot.** The cap meant for diagnostics also silenced the lines
  `hits()` and `deaths()` count, so a long run quietly stopped counting. Only diagnostics are capped now.
- **`-Pnogrim` left the anticheat running.** It skipped the startup check but GrimAC stayed installed and kept
  correcting movement, and `assertClean` still failed on its flags. `-Pnogrim` now takes the jar out of the
  staged server for that run and puts it back after.
- **Grim's bundled PacketEvents could fail a clean scenario.** Its update checker logs *Failed to check for
  updates* on its own thread at an unfixed time, which matched the `"failed"` flag marker.
- **A killed bot came back at full size.** Scale was not restored on respawn, so a small target changed
  hitbox the first time it died.
- **A bot told to leave never left.** Vanilla closes a connection only once its disconnect packet has been sent,
  and the fake player's connection sends nothing, so `testkit sweep`, `bot.remove()` and every respawn left the
  old player on the server — a killed bot's dead body stayed behind as a "real player" other bots could target.
  The fake connection now reports each send as finished, and vanilla's own leave path runs. `sweep` also
  looked for names starting `Bot` while every bot is `SicoKaleb…`; it matches `SicoKaleb` now.
- **Builder methods that did nothing.** `TestEnemy.jumping()`, `baby()` and `walking(…, jumpConstantly)` were
  accepted and ignored; they work now (a vanilla jump arc; half scale). `type()` is gone — the enemy was
  always a real player whatever it said, so a test "against a husk" was a test against a player. Summon mobs
  with `server.command("summon …")`.
- **`build.gradle` contained two literal NUL bytes**, so git treated it as a binary file: no diffs, no
  readable history. Written as the `\u0000` escape now.
- Stale docs: `TestEnemy` claimed its opponents were mobs; `ChatWatch` linked a class that does not exist;
  a compass direction in `TestMap` was backwards (yaw −90 faces east).

### New

- **One failure no longer ends the run.** Each failing test is recorded, the client returns to the title
  screen, and the run carries on; the build fails at the end with every failure listed under `[suite]`.
- **`-Pfailed`** re-runs only what failed last time (`build/gametest-failed.txt`).
- **`-Pscenario=a,b,c`** runs several scenarios in one launch.
- **A scenario that starts and never reaches its verdict fails the run.** It used to come out green.
- **`PacketTrace`** — what the client sent and received, bucketed by client tick, with `maxPerTick`, `sent`,
  `received` and `sentOn` for assertions. Example: `25-move-packet-trace`.
- **`Latency`** — a real round trip for the local server: delays the whole inbound stream, strictly in order,
  and drains before it leaves. Example: `26-move-latency`.
- **`/testkit protect` and `TestMap.protect(…)`** — the server refuses placements in a box, like spawn
  protection or a claim. Example: `45-place-refused`.
- **Saved worlds** — `TestServer.seedWorldFrom(save, spawn)` starts the next server on a copy of a save;
  `-Pworld=<name>` picks one under `run/saves`. Example and self-test: `SeedWorldTest`.
- **A refused login fails with the server's reason**, not a one-minute timeout.
- **The port is read from `run/testserver/server.properties`**, so a second checkout or a server of your own
  on 25565 is one edit away; a port already in use says so.
- **`online-mode` and `enforce-secure-profile` are put back to false on every start** — an online-mode test
  server rejects every run, with nothing in either log saying why.
- **`build/testserver-console.log` starts fresh each run** instead of growing forever.
- Examples: `35-combat-hopping-target`.
- Docs: running fast, reading a run's output, measuring frame time in a gametest, keeping scenarios
  independent when the client JVM is shared, and five more silent traps.

### Changed

- **GrimAC 2.3.74-2614909 → 2.3.74-8eb5f28** (Modrinth, 2026-09-10). `prepareTestServer` now removes a
  superseded Grim jar from the staged server, so two builds can never load side by side.
