# Instructions for AI agents working in this repo

> Read this before writing a scenario or interpreting a result. It is short because the rules that matter
> are few, and every one of them exists because breaking it produced a **passing** test that meant nothing.

This repo tests Minecraft client mods against a **real anticheat** (GrimAC) on a **real dedicated
server**. `README.md` covers the API. This file covers how to behave.

---

## 1. Never state an anticheat result you have not run

- A source-level reading of a check is a **hypothesis**. A scenario result is **evidence**. Do not report
  "should pass", "is safe" or "has no anticheat surface" as if it were a run.
- `./gradlew build` passing says nothing about whether a module flags. Neither does a scenario that never
  reached the code you changed.
- Quote the actual line: `anticheat clean — no verbose lines`, with the scenario name and what the module
  was doing. If you did not run it, say so plainly.
- A flag with a check name is a **better** outcome than an untested guess. Report it as a result, not a
  setback.

## 2. Make every scenario prove its own premise

A scenario that measures the wrong thing passes just as easily as one that measures the right thing.
Before asserting anything about a module, assert the situation exists:

- Is the player standing where the map put them, at the height it built? (`playerPosition()`)
- Is there actually a gap to bridge / an opponent in range / a block to break?
- Did the thing under test do anything at all — a placement count, a hit count, a displacement?

Real examples from this codebase's history: a bridge scenario scoring 812 blocks that were a previous
scenario's platform; a knockback scenario landing ten hits on a player who never moved because the
damage was resisted; an aura scenario passing against a target that was invulnerable.

**If a run passes and you cannot say which numbers prove it, it did not pass.**

A floor loose enough for a broken run to clear is not a floor. If a module should produce thirty packets and
the assertion is "at least twenty", check that a run where the module stopped halfway cannot reach twenty.

### The client is not fresh

Only the server restarts between scenarios; the client JVM, and everything your mod holds in it, carries
over — into the next scenario and, through a saved config, into the next run. A module "enabled" while
already enabled never runs its enable hook, so it keeps the last scenario's state. At the top of every body,
disable and re-enable what you test, and set every setting the scenario depends on, with the module off.
Never trust a declared default.

## 3. Do not weaken the detector to make a test pass

- Do not add to `FLAG_EXEMPT` or loosen `FLAG_MARKERS` in `Scenario` unless the line is provably not
  check output.
- Do not delete or skip `assertDetectorWorks()` because it is slow.
- Do not "fix" the positive control by letting it accept a flag the body drew. That exact version once
  reported a flagged module as clean.
- Do not op the test player. GrimAC exempts operators; an opped client flags nothing ever.
- Do not lower an assertion threshold to turn a red run green. Fix the module, or report the failure.

## 4. Read the artifacts before guessing

When a run fails, the answer is usually already written down:

- `build/testserver-console.log` — the entire server console. Startup failures explain themselves
  hundreds of lines above wherever a tail begins.
- `testkit report` — every entity and where it is relative to the client.
- `PacketTrace` — what the client sent and received, tick by tick. When a packet-level check flags, this is
  the other half of the verbose line.
- The `[suite]` lines at the end of the run list every failure. A refused login fails with the server's own
  reason; read it before suspecting the module.
- Print position, ground contact and whatever counter the module exposes **every second** during a run.
  A module that stops has usually stopped for a reason visible in three numbers.

## 5. Scope every claim

- A clean GrimAC run says nothing about behavioural/server-side detection that leaves no verbose and acts
  on a delay. Say which one your result covers.
- One clean run is not a durable claim. Say how long, on what map, doing what.
- The local server has no round trip unless you give it one (`Latency`). A result about anything that
  depends on the server's answer arriving later than the client's action says whether it was measured with
  latency, and how much.
- A `-Pnogrim` run is not a clean run. It has no anticheat on it at all.
- Do not generalise from one movement pattern to "the module is safe".

## 6. Conventions

- Scenario names are `NN-slug`, so `-Pscenario=slug` selects one. `-Pscenario=a,b` selects several in one
  launch; `-Pfailed` selects what failed last run. Run only the scenarios your change touches; the full suite
  is for before a release.
- A test that does not go through `Scenario.run` must still call `Scenario.skip(name)`, or no filter can
  leave it out and every other test depends on it.
- Bots are named `SicoKaleb<Module><detail>` via `TestEnemy.named(...)`. Names are capped at 16 characters
  automatically — a longer one kicks every real client off the server.
- Build maps with `TestMap`, not raw `/fill`. Vanilla fill skips unloaded chunks and says so in a line
  nobody reads.
- New scenario classes must be registered in `src/gametest/resources/fabric.mod.json`.

## 7. When you change the harness

Run `-Pscenario=smoke,proof,02-seed` afterwards, every time. Those are the tests that check the harness
rather than a module, and a broken harness reports everything as clean. If you touched `PacketTrace`,
`Latency` or `testkit protect`, add `25-move-packet-trace,26-move-latency,45-place-refused` — each of those
proves its tool did something before it relies on it.

`MixinClientGameTestRunner` targets a Fabric implementation class. After a Fabric API update, if the client
refuses to start on that mixin, javap the new `FabricClientGameTestRunner` and move the target; do not drop
the mixin, or the first failure ends the run again.
