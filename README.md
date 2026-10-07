# Minecraft Anticheat Test Kit

Automated testing for Minecraft client mods **against a real anticheat**.

Your gametests already prove a module does what you expected. They cannot tell you whether the server
objects, because an integrated test server has no opinion about whether a rotation was reachable or a
placement legal. This kit runs each scenario against a real dedicated server with **GrimAC** on it, reads
the anticheat's verbose output, and **fails the build on any flag**.

```
[11-example-bridge] connected as Player0
[11-example-bridge] travelled 39.4 blocks, ended at y=151.00
[11-example-bridge] anticheat clean — no verbose lines
```

Built for Minecraft **26.1.2** / Fabric. Requires **Java 25** and a JDK on `JAVA_HOME`. Works on Windows
and Linux. See [`CHANGELOG.md`](CHANGELOG.md) for what changed.

---

## Quick start

```bash
./gradlew runClientGameTest -Pscenario=smoke        # prove the harness works, first
./gradlew runClientGameTest                         # run everything
./gradlew runClientGameTest -Pscenario=walk,bridge  # several scenarios, one launch
./gradlew runClientGameTest -Pfailed                # only what failed last run
./gradlew runClientGameTest -Pnogrim                # same bodies, no anticheat on the server
./gradlew runTestServer                             # the same server, to join by hand
```

The smoke scenario deliberately cheats and **fails if the anticheat stays quiet**. Run it first on any
new machine: a missing anticheat does not error, it produces a run with no flags, which looks exactly
like a clean one.

### Running fast

A client launch plus a server boot costs about a minute before anything runs, so:

- **One failure does not end the run.** A failing scenario is recorded, the client goes back to the title
  screen, and the next one runs. The build still fails at the end, with every failure listed under
  `[suite]`.
- **`-Pfailed` re-runs only what failed.** The last run's failures are written to
  `build/gametest-failed.txt` and read back as the filter. Fix, `-Pfailed`, repeat.
- **`-Pscenario=a,b,c`** runs several scenarios in one launch. Each entry is a substring of a name.
- **A scenario that starts and never reaches its verdict fails the run** — it tested nothing, and it used to
  come out as a green build.
- **`-Pnogrim`** takes the anticheat off the server entirely, to separate "my module is broken" from "the
  anticheat objects". Nothing a `-Pnogrim` run prints is a verdict.

---

## Writing a scenario

```java
public class MyTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        Scenario.run(ctx, "20-my-thing",
            // 1. the map
            (server, scenario) -> TestMap.on(server)
                    .platform(0, 150, 0, 24)      // 49x49 pad, players stand at y=151
                    .walls(4)                     // barrier walls so nothing walks off
                    .catchFloor(140)              // survivable landing
                    .bridgeGap(3, 60)             // void past x=3
                    .survival()
                    .clearInventory()
                    .give("white_wool", 64)
                    .spawn(-3.5, 0.5, -90f)       // stand here, facing east
                    .build(),

            // 2. what happens
            (server, scenario) -> {
                ctx.getInput().holdKey(options -> options.keyUp);
                ctx.waitTicks(200);
                ctx.getInput().releaseKey(options -> options.keyUp);
                scenario.log("done");
            });
        // the anticheat's verdict is asserted for you
    }
}
```

Register the class in [`src/gametest/resources/fabric.mod.json`](src/gametest/resources/fabric.mod.json)
under `fabric-client-gametest`, then `./gradlew runClientGameTest -Pscenario=my-thing`.

A module that departs from vanilla movement **on purpose** — one that snaps or nudges the player — draws a
verbose line per correction by construction, and a scenario that asserts silence can never pass at any
tuning. Use `Scenario.runExpectingFlags` for those and assert on the lines instead: how many per action,
which check, what offset.

### Examples to read

Every one of these runs as-is. Copy the closest and change the body.

| file | scenarios |
| --- | --- |
| [`ExampleTest`](src/gametest/java/dev/testkit/gametest/ExampleTest.java) | walk, bridge, duel — start here |
| [`MovementExamples`](src/gametest/java/dev/testkit/gametest/MovementExamples.java) | sprint-jump with a speed assertion, long fall, obstacle course, ice/soul-sand/slabs, a per-second sampled run, a per-tick packet trace, a run over simulated latency |
| [`CombatExamples`](src/gametest/java/dev/testkit/gametest/CombatExamples.java) | knockback with a displacement assertion, frozen dummy, waypoint patrol, a crowd of four for target selection, an A/B armour comparison, a hopping target |
| [`PlacementExamples`](src/gametest/java/dev/testkit/gametest/PlacementExamples.java) | straight gap, diagonal gap, rising gap, tower, breaking a wall, a placement the server refuses |
| [`SeedWorldTest`](src/gametest/java/dev/testkit/gametest/SeedWorldTest.java) | benching on a saved world instead of a generated one |
| [`SmokeTest`](src/gametest/java/dev/testkit/gametest/SmokeTest.java) / [`ProofTest`](src/gametest/java/dev/testkit/gametest/ProofTest.java) | the harness testing itself |

Read them roughly in that order — each adds one idea, and the comments say which.

### Maps

`TestMap` builds terrain through the companion server mod, which loads chunks as it goes. That matters:
vanilla `/fill` silently does nothing in unloaded chunks — it says "No blocks were filled" and carries on
— so a hand-rolled map can leave your player falling through a world that was never built.

| method | what it does |
| --- | --- |
| `platform(cx, y, cz, radius)` | square pad; sets the surface height for everything after it |
| `floor(x1, y, z1, x2, z2)` | rectangular slab |
| `walls(height)` | barrier walls around the last platform |
| `catchFloor(y)` | wide floor far below, so a fall is survivable |
| `bridgeGap(fromX, toX)` | carve void past `fromX` |
| `diagonalGap(fromX, fromZ, to)` | carve a diagonal void |
| `stairs(fromX, y, z, steps, width)` | staircase climbing in +X |
| `pillar(x, y, z, height)` / `wall(...)` | obstacles |
| `fill(x1,y1,z1,x2,y2,z2,block)` | anything else |
| `protect(x1,y1,z1,x2,y2,z2)` | the server refuses any block placed in this box, like spawn protection or a claim |
| `spawn(x, z, yaw)` | where the player starts — applied **after** the build |
| `survival()` / `creative()` / `give(...)` / `clearInventory()` | player setup |
| `command(...)` | any command the builder doesn't cover |

The client starts in **creative** unless you say `survival()`. That changes more than it looks like: a
creative player breaks blocks in one hit and kills an `Invulnerable` mob, and a creative client takes no
damage, so an opponent's hits are refused and no knockback arrives.

### Saved worlds

A scenario can start its server on a copy of a saved world — a map you built, or one downloaded from the
server your mod is for — instead of a generated one:

```java
Path save = TestServer.savesDir().resolve("my-map");        // run/saves/my-map
if (!Scenario.skip("50-on-my-map")) {
    try {
        TestServer.seedWorldFrom(save, new int[]{0, 70, 0});   // and where to put the world spawn
        Scenario.run(ctx, "50-on-my-map", build, body);
    } finally {
        TestServer.seedWorldFrom(null, null);                  // never leak into the next scenario
    }
}
```

The save is copied, never touched: `level.dat`, `data/` and `dimensions/`, without the player data, the
saved entities or the session lock. `-Pworld=<folder under run/saves>` reaches a scenario that reads
`TestServer.worldFromProperty()`. Set a spawn — a save's own is wherever it was made from, often over
nothing — and `forceload` the area you test in if it is far from spawn.

### Opponents

`TestEnemy` spawns a **real `ServerPlayer`**, not a named mob. That matters more than it sounds: modules
pick targets by entity type, so an aura filtering for players cannot see a zombie at all, and player
hitboxes, eye heights, reach and knockback all resolve differently.

```java
TestEnemy bot = TestEnemy.named("Velocity", "1dps")
        .at(4.5, 151, 0.5)
        .health(20)
        .size(1.0)                   // vanilla scale attribute — real hitbox, not just the model
        .baby(false)                 // true halves the scale: a baby-sized hitbox
        .frozen(true)                // immovable, still damageable, respawns at its anchor when killed
        .jumping(true)               // hop continuously on a vanilla jump arc, on top of anything else
        .heldItem("minecraft:stick")
        .armor("minecraft:iron_helmet", null, null, null)
        .shield(true)
        .walking(90f, true, false)   // direction, sprint, hop the whole way
        .route(true, new double[]{0,151,0}, new double[]{10,151,10})   // patrol, repeating
        .chasing(true)               // follow the client
        .attacking(3.5f, 20, 2.0f)   // reach, ticks between hits, damage
        .spawn(ctx, server);

bot.drive(200);                      // let it act
scenario.attackFor(200);             // or fight it: one click per recharged swing
bot.hits();                          // blows that landed on the client
bot.refused();                       // swings the server refused (client in creative, hurt frames…)
bot.deaths();                        // times it was killed and put back
bot.remove();
```

Bots are named `SicoKaleb<Module><detail>` — `SicoKalebK1dps` for a knockback bot — so a verbose line, a console log and a scenario can be tied together
afterwards, and so `testkit sweep` can tell a leftover bot from a real tester.

**Holding the attack key is one hit.** Vanilla swings at an entity once per key *press*; holding only
repeats for breaking blocks. A body that holds attack for ten seconds lands one blow and then stands there,
and the run reads as a clean fight. `scenario.attackFor(ticks)` clicks each time the swing has recharged. **Minecraft caps player
names at 16 characters**, and a longer one is not refused politely — the server throws while encoding
`player_info_update` and kicks every real client — so the name is abbreviated automatically. Want a mob
instead? `server.command("summon …")`.

### Reading the wire

When a check flags, the verbose line names the check, not what the client sent to earn it. `PacketTrace`
records both directions, bucketed by client tick:

```java
ctx.runOnClient(mc -> PacketTrace.start());
// … the part you care about …
ctx.runOnClient(mc -> PacketTrace.stop());
PacketTrace.lines().forEach(scenario::log);
```

```
t  41 > move_player_pos_rot client_tick_end   < set_time
t  42 > use_item_on swing move_player_pos client_tick_end   < block_update block_changed_ack
```

`maxPerTick(id)`, `sent(id)`, `received(id)` and `sentOn(tick)` turn it into assertions —
`received("player_position")` counts the server moving the client back.

### A real round trip

The test server is on the same machine, so it answers inside the tick that asked — which no real
connection does. Anything that depends on the gap between an action and the server's answer (a prediction
the server overrules, a correction, an acknowledgement) is untested without one:

```java
ctx.runOnClient(mc -> Latency.install(100));   // 100 ms more round trip, the whole inbound stream, in order
ctx.runOnClient(mc -> Latency.remove());       // drains what it holds, then leaves
```

It delays everything the server sends, strictly in order, and drains before it leaves. A naive delay that
drops out mid-stream lets newer packets overtake held ones, and the anticheat flags `TransactionOrder` —
the harness's flag, not your mod's.

### Driving the server directly

Anything the builders don't cover:

```java
server.command("effect give @p minecraft:jump_boost 60 3");
server.command("testkit report");        // dump every entity and where it is relative to you
server.command("testkit sweep");         // remove mobs, leftover bots and protected boxes
server.command("testkit arena 24");      // barrier-walled platform at y=150
server.command("testkit protect 1 151 0 1 151 0");   // refuse placements in a box; "testkit unprotect"
```

---

## Using it on your own mod

The kit is standalone. To test *your* mod, add it as a dependency of the gametest source set and
enable your modules from a scenario body:

```java
ctx.runOnClient(mc -> MyMod.MODULES.get(MyModule.class).setEnabled(true));
```

Nothing in the harness knows about any particular mod, so there is no integration to write beyond that.

**The client JVM is shared across every scenario in a run; only the server restarts.** Whatever your mod
holds — an enabled module, a setting, a buffer, a saved config — carries into the next scenario and into the
next run. So, at the top of every body:

- **Reset what you depend on.** A module left enabled by the last scenario never runs its enable hook again
  when you "enable" it, so whatever that hook resets is still the last scenario's state. Disable, then
  enable.
- **Pin every setting the scenario depends on.** If your mod saves its config, a setting one run changed is
  the default of the next — and a run killed halfway keeps whatever it set. Change settings with the module
  off, then turn it on.
- **Prove a comparison against nothing.** For an "A vs B" measurement, also check "B vs nothing" — that the
  thing measured is actually there — so a module that did nothing cannot pass by being compared with itself.

---

## What to know before you trust a result

Each of these fails **silently** — it produces a run that passes, or a failure that points at the wrong
thing. Each cost a debugging session to find, and they are why the positive control exists.

1. **The anticheat exempts operators.** Never op the test player. Verbose goes to the server console
   instead, which needs no permissions. An opped client produces a spotless run for the worst reason.
2. **GrimAC cannot run in the client's JVM.** A client gametest is `EnvType.CLIENT`, and there the
   PacketEvents build it bundles installs client hooks that are incomplete on 26.1.2 — the client's own
   connection dies with *Invalid player data*. The server is a separate process for this reason; do not
   "simplify" it back to an in-process one.
3. **A dead server can look ready.** Signalling readiness when the process output closes makes a crashed
   server report itself started, and the client then fails with a bare *connection refused*.
4. **`/fill` skips unloaded chunks.** Use `TestMap`, which goes through the companion mod.
5. **Every scenario gets a fresh world.** The server deletes its world folder before each run — otherwise
   each scenario runs on the leftovers of the last, and a module gets measured against someone else's
   arena.
6. **No anticheat and no flags look identical.** Hence `scenario.assertDetectorWorks()`.
7. **The positive control could be passed by the body's own flag.** It used to take any verbose line as
   proof the detector works and then mark past it — so a module that flagged during the body printed
   *positive control OK* and then *anticheat clean*. A flag from the body now fails the scenario before the
   control cheats.
8. **The anticheat's own library can look like a flag.** Grim's bundled PacketEvents logs *Failed to check
   for updates* on its own thread at an unfixed time; `"failed"` is a flag marker, so it landed inside
   scenarios as a "flag" naming no check. It is exempt now. The tell for the next one of these is a flag line
   with no check name and no player name in it.
9. **An online-mode server rejects every run.** The gametest client has no Mojang session, so with
   `online-mode=true` the login drops a second in, and nothing on either side says why. The harness puts
   `online-mode` and `enforce-secure-profile` back to `false` on every start, and a refused login now fails
   with the server's own reason instead of a timeout.
10. **A vanilla server says yes where a real one says no.** A block you punch breaks, a placement lands, a
    swing connects. Where your mod is built for a server that refuses those, make the test server refuse
    them too — `protect(...)`, adventure mode, a target with a thousand health — or the packet stream you
    measure is not the one the real server sees.
11. **A setup that sleeps measures the server's reaction, not the setup.** Put a player inside a block and
    wait twenty ticks, and vanilla has already pushed them out. Where the server reacts, sample every tick
    and act inside the window — and write the assertion so a fixed outcome (the same number for two
    different inputs) fails.

**A clean run is a statement about GrimAC only.** Server-side behavioural detection leaves no verbose,
bans on a delay, and catches input-less automation with regular timing that Grim allows. Passing here is
necessary, not sufficient — say which one your result covers.

---

## Reading a run

```bash
./gradlew runClientGameTest -Pscenario=walk > run.log 2>&1
sed -e 's/\x1b\[[0-9;]*m//g' -e 's/.*STDOUT\]: //' run.log | grep -E '^\[|anticheat|FAILED'
```

- **Capture everything, filter afterwards.** A `grep` on the live stream drops the rows you wanted, and a
  `tail` drops the beginning, which is where a per-tick trace puts the interesting part.
- **Strip the logger prefix before matching.** Minecraft wraps the *first* line of each print with
  `[HH:MM:SS] [Test thread/INFO] (Minecraft) [STDOUT]: ` and passes continuation lines through raw, so a
  pattern anchored at the start of the line matches some of a table and silently drops the rest.
- **Read the scenario's own lines, not only the exit code.** An empty or partial table reads exactly like a
  scenario that measured nothing. If a scenario never printed `connected as`, it never ran.

### Measuring frame time

A gametest client never gets input, so after a minute vanilla's **inactivity limit** caps it at 30 fps, and
the test config may leave **vsync** on, which locks frame intervals to the panel's refresh. Either one makes
a frame reading that does not move when the work does. Before measuring, on the client thread:
`options.framerateLimit().set(260)`, `options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED)`,
`options.enableVsync().set(false)` — and discard the first measurement window, which the JIT has not warmed.

### Other things that bite in a body

- `Minecraft.getInstance()` throws on the test thread. Go through `ctx.runOnClient` / `computeOnClient`.
- `ctx.getInput().setCursorPos` takes **window pixels**; screens work in GUI units. Multiply by
  `mc.getWindow().getGuiScale()`.
- `"tp @s " + x + ".5"` with a negative `x` lands half a block the wrong way. Use `(x + 0.5)`.
- A server `tp` moves the client, but a client-side move is `mc.player.setPos(...)`, one step a tick — and
  the anticheat will judge it.

---

## Artifacts

| file | what it is |
| --- | --- |
| `build/testserver-console.log` | this run's whole server console, every scenario in order, including every verbose line |
| `build/testserver-launch.properties` | how the server was launched |
| `build/gametest-failed.txt` | the scenarios that failed last run, for `-Pfailed` |
| `run/testserver/` | the staged server: mods, config, world |
| `run/saves/` | saved worlds for `seedWorldFrom` |

The server listens on the `server-port` in `run/testserver/server.properties` (25565 unless you change it).
Change it if something else on the machine holds 25565 — a server of your own, or a second checkout
running its suite at the same time; the harness reads it from there.

---

## Updating the anticheat

Pinned in [`gradle.properties`](gradle.properties) as `grim_version` / `grim_url`. GrimAC's Fabric jar
does not bundle everything it needs, and the build supplies the rest:

- **Cloud** (`org.incendo:cloud-fabric`) — its command framework. Version matters: `2.0.0-beta.17`
  declares `minecraft >=26.2` and will not load on 26.1.x; **`beta.16`** declares `>=26.1` and does.
- **SQLite** (`org.xerial:sqlite-jdbc`) — its violation store. Without it Grim refuses to start.

After changing the version, run `./gradlew prepareTestServer` (it removes the old jar from the staged
server) and then the smoke scenario. If the new build changes its config layout or startup requirements,
the positive control is what tells you.

**Experimental checks** are off by default (`experimental-checks: false` in
`run/testserver/config/GrimAC/config.yml`, Grim's own default). Turning them on for a one-off run can find a
check whose description is exactly what your module does — but flip it back afterwards, and record which
setting a result was measured under, or later results stop being comparable.

---

## Licence

MIT. GrimAC is downloaded at build time and is not redistributed here; it is licensed separately
(GPL-3.0) by its authors.
