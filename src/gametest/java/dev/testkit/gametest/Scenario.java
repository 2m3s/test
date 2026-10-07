package dev.testkit.gametest;

import dev.testkit.harness.ChatWatch;
import dev.testkit.harness.Disconnects;
import dev.testkit.harness.PacketTrace;
import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * Runs a scenario against a <b>real dedicated server with a real anticheat on it</b>, and fails the
 * build if any check flags.
 *
 * <pre>{@code
 * public class MyTest implements FabricClientGameTest {
 *     public void runTest(ClientGameTestContext ctx) {
 *         Scenario.run(ctx, "my-thing",
 *             (server, s) -> TestMap.on(server)
 *                     .platform(0, 150, 0, 12)
 *                     .catchFloor(140)
 *                     .bridgeGap(1, 40)
 *                     .survival()
 *                     .give("white_wool", 64)
 *                     .spawn(-3.5, 0.5, -90f)
 *                     .build(),
 *             (server, s) -> {
 *                 ctx.getInput().holdKey(o -> o.keyUp);
 *                 ctx.waitTicks(200);
 *                 ctx.getInput().releaseKey(o -> o.keyUp);
 *             });
 *     }
 * }
 * }</pre>
 *
 * <p>Register the class in {@code src/gametest/resources/fabric.mod.json} under
 * {@code fabric-client-gametest}, then {@code ./gradlew runClientGameTest -Pscenario=my-thing}.
 *
 * <h2>What a scenario gets</h2>
 * A dedicated server process on a freshly deleted world, the client connected, the anticheat's verbose
 * channel reporting to the server console, and — after the body — an assertion that it said nothing. An
 * empty flag list is the only result that earns the words "does not flag".
 *
 * <h2>The harness proves itself</h2>
 * A suite whose job is reporting flags fails silently when it breaks: no anticheat and no flags look
 * identical from outside. {@link #assertDetectorWorks()} cheats deliberately and requires the anticheat
 * to notice. Call it in any scenario whose clean result you intend to quote.
 *
 * <h2>One failure does not end the run</h2>
 * A failing scenario is recorded, the client goes back to the title screen and the next test runs; the
 * build fails at the end with every failure listed under {@code [suite]}, and {@code -Pfailed} re-runs
 * just those. A scenario that starts and never reaches its verdict is a failure too — see
 * {@link SuiteVerdict}.
 */
public final class Scenario {

    /** Shapes an anticheat's verbose and alert lines take. */
    private static final String[] FLAG_MARKERS = {"failed", "[grim]", "grim »", "violation", " vl", "verbose"};
    /**
     * Looks like a flag, is not: enable confirmations, join noise, our own echoes.
     *
     * <p>{@code packetevents} is Grim's bundled packet library talking to itself — its update checker runs on
     * its own thread at some unfixed time after the server starts and logs {@code Failed to check for updates}
     * / {@code Failed to parse packetevents version!} when it cannot reach the network. That matches
     * {@code "failed"}, and because it races the measurement window it lands inside some scenario sooner or
     * later as a "flag" that names no check and no player. No check line carries the library's name, so this
     * exempts nothing real.
     */
    private static final String[] FLAG_EXEMPT = {"verbose enabled", "verbose is now", "alerts enabled",
            "alerts is now", "now receiving", "no longer receiving", "grimac", "logged in",
            "joined the game", "left the game", "made the advancement", "[testkit]", "packetevents"};

    private final ClientGameTestContext ctx;
    private final String name;
    private TestServer server;

    private Scenario(ClientGameTestContext ctx, String name) {
        this.ctx = ctx;
        this.name = name;
    }

    /** Set by {@code -Pnogrim}: the server runs without the anticheat, to isolate a failure. */
    public static boolean anticheatMuted() {
        return Boolean.getBoolean("testkit.nogrim");
    }

    /**
     * Honour {@code -Pscenario=…}: a substring of the name, or several separated by commas, any of which
     * selects it. {@code -Pfailed} arrives here as the same comma list.
     *
     * <p>Public so a test that does not go through {@link #run} — a singleplayer check, say — can be
     * filtered the same way. A test that cannot be filtered out is a test every other one depends on.
     */
    public static boolean skip(String name) {
        String filter = System.getProperty("testkit.scenario", "");
        if (filter.isBlank()) {
            SuiteVerdict.started(name);
            return false;
        }
        for (String part : filter.split(",")) {
            if (!part.isBlank() && name.contains(part.trim())) {
                // Remembered so a failure from here on is filed under this name, which is what -Pfailed
                // feeds back in as the filter.
                SuiteVerdict.started(name);
                return false;
            }
        }
        return true;
    }

    /**
     * Start the server, connect, arm the anticheat, build the world, run the body, then assert that
     * nothing flagged.
     */
    public static void run(ClientGameTestContext ctx, String name,
                           BiConsumer<TestServer, Scenario> build,
                           BiConsumer<TestServer, Scenario> body) {
        if (skip(name)) {
            return;
        }
        SuiteVerdict.expect(name);
        Scenario scenario = new Scenario(ctx, name);
        try (TestServer server = TestServer.start()) {
            scenario.server = server;
            scenario.connect();
            scenario.arm(server);
            // Every scenario starts in an empty world. Leftover entities from an earlier run wander into
            // whatever is being measured, and a fake player is a real player in the world save.
            server.command("testkit sweep");
            ctx.waitTicks(10);
            build.accept(server, scenario);
            ctx.waitTicks(60);          // let the world settle before anything is measured
            server.mark();
            ChatWatch.clear();
            body.accept(server, scenario);
            ctx.waitTicks(20);          // let a late verbose line land before looking
            scenario.assertClean();
            SuiteVerdict.finished(name);
        } finally {
            scenario.disconnect();
        }
    }

    /**
     * Like {@link #run}, but for a scenario meant to flag: the body decides the verdict.
     *
     * <p>Use it for a module that departs from vanilla movement by design — one that snaps, nudges or
     * corrects the player on purpose draws a verbose line per correction by construction, and a scenario
     * that asserts silence can never pass at any tuning. Measure the lines instead (how many per action,
     * which check, what offset) and assert on that.
     */
    public static void runExpectingFlags(ClientGameTestContext ctx, String name,
                                         BiConsumer<TestServer, Scenario> build,
                                         BiConsumer<TestServer, Scenario> body) {
        if (skip(name)) {
            return;
        }
        SuiteVerdict.expect(name);
        Scenario scenario = new Scenario(ctx, name);
        try (TestServer server = TestServer.start()) {
            scenario.server = server;
            scenario.connect();
            scenario.arm(server);
            server.command("testkit sweep");
            ctx.waitTicks(10);
            build.accept(server, scenario);
            ctx.waitTicks(60);
            server.mark();
            ChatWatch.clear();
            body.accept(server, scenario);
            SuiteVerdict.finished(name);
        } finally {
            scenario.disconnect();
        }
    }

    /**
     * Join the server, failing with the server's own words if it turns the client away.
     *
     * <p>A refused login otherwise surfaces as {@code Timed out waiting for predicate} a minute later, when the
     * server said exactly what was wrong the moment it happened — and put it on a screen nobody reads.
     */
    private void connect() {
        Disconnects.clear();
        ctx.runOnClient(mc -> {
            ServerData data = new ServerData("testkit", TestServer.address(), ServerData.Type.OTHER);
            ConnectScreen.startConnecting(new TitleScreen(), mc,
                    ServerAddress.parseString(TestServer.address()), data, false, null);
        });
        ctx.waitFor(mc -> (mc.player != null && mc.level != null) || Disconnects.last() != null, 1200);
        String refused = Disconnects.last();
        if (refused != null) {
            throw new AssertionError("[" + name + "] the test server refused the connection: \"" + refused
                    + "\". If it mentions a profile, a session or verifying a username, check that "
                    + "run/testserver/server.properties still says online-mode=false.");
        }
        // A scenario run opens a window and plays twenty minutes of Minecraft at whatever the machine's
        // volume happens to be.
        ctx.runOnClient(mc -> mc.options.getSoundSourceOptionInstance(
                net.minecraft.sounds.SoundSource.MASTER).set(0.0));
        ctx.waitTicks(40);
        System.out.println("[" + name + "] connected as "
                + ctx.computeOnClient(mc -> mc.player.getGameProfile().name()));
    }

    private void disconnect() {
        // Nothing a body turns on outlives its scenario. The latency stage goes with the connection; the
        // trace keeps what it recorded, readable after run() returns, but stops adding to it.
        PacketTrace.stop();
        ctx.runOnClient(mc -> {
            if (mc.level != null) {
                mc.disconnectWithProgressScreen();
            }
        });
        ctx.waitFor(mc -> mc.level == null, 600);
        ctx.waitTicks(20);
        ctx.setScreen(TitleScreen::new);
        ctx.waitTicks(10);
    }

    /**
     * Confirm the anticheat is running and reporting.
     *
     * <p>The test player is deliberately <b>not</b> opped. GrimAC treats operators as exempt, so an opped
     * client produces a spotless run for the worst possible reason — that was the first version of this
     * harness and its positive control caught it. Verbose goes to the server console instead, which needs
     * no permissions at all.
     */
    private void arm(TestServer server) {
        if (anticheatMuted()) {
            System.out.println("[" + name + "] anticheat removed by -Pnogrim — nothing this run says is a verdict");
            return;
        }
        if (!server.logged("GrimAC")) {
            throw new AssertionError("The anticheat never announced itself on the test server, so every "
                    + "'clean' result would be meaningless. Run ./gradlew prepareTestServer and check "
                    + "build/testserver-console.log.");
        }
        server.mark();
    }

    /** Check output produced since the last {@link TestServer#mark()}, read from the server console. */
    public List<String> flags() {
        List<String> out = new ArrayList<>();
        for (String line : server.since()) {
            String lower = line.toLowerCase(Locale.ROOT);
            boolean marked = false;
            for (String marker : FLAG_MARKERS) {
                if (lower.contains(marker)) {
                    marked = true;
                    break;
                }
            }
            if (!marked) {
                continue;
            }
            boolean exempt = false;
            for (String skip : FLAG_EXEMPT) {
                if (lower.contains(skip)) {
                    exempt = true;
                    break;
                }
            }
            if (!exempt) {
                out.add(line);
            }
        }
        return out;
    }

    private void assertClean() {
        List<String> flags = flags();
        if (flags.isEmpty()) {
            System.out.println("[" + name + "] " + (anticheatMuted()
                    ? "no anticheat on the server (-Pnogrim) — the body ran, nothing was judged"
                    : "anticheat clean — no verbose lines"));
            return;
        }
        StringBuilder message = new StringBuilder("[" + name + "] anticheat flagged "
                + flags.size() + " time(s):");
        for (String flag : flags) {
            message.append(System.lineSeparator()).append("    ").append(flag);
        }
        System.out.println(message);
        throw new AssertionError(message.toString());
    }

    /**
     * Move the player somewhere no legitimate client could reach and require the anticheat to notice.
     *
     * <p>This is the positive control and it is not optional. Without it, a harness that has lost its
     * anticheat — a bad classpath, a version bump, a permission change — reports every scenario as clean
     * and every module as safe. The failure mode of a silent detector is that it passes everything.
     *
     * <p>Anything the body itself drew is a real result and must not be what "proves" the detector. An
     * earlier version accepted any verbose line since the body's mark as proof, then marked past it — so a
     * module that flagged during the body printed "positive control OK" followed by "anticheat clean". A
     * flag from the body now fails the scenario here, the way {@link #run}'s own check would have.
     */
    public void assertDetectorWorks() {
        if (anticheatMuted()) {
            return;
        }
        List<String> fromBody = flags();
        if (!fromBody.isEmpty()) {
            StringBuilder message = new StringBuilder("[" + name + "] anticheat flagged "
                    + fromBody.size() + " time(s) during the body, before the positive control:");
            for (String flag : fromBody) {
                message.append(System.lineSeparator()).append("    ").append(flag);
            }
            System.out.println(message);
            throw new AssertionError(message.toString());
        }
        // Twice, because the client JVM is shared across scenarios and a mod under test can be left in a
        // state that swallows the first cheat (holding packets, say). A second attempt from a fresh mark
        // either produces a flag or fails the run honestly; it can never turn a dead detector green.
        if (!detectorSeesCheat()) {
            server.mark();
            if (!detectorSeesCheat()) {
                throw new AssertionError("Positive control FAILED twice: teleported six blocks sideways and "
                        + "the anticheat said nothing. Every 'clean' result below this is meaningless.");
            }
        }
        System.out.println("[" + name + "] positive control OK — detector saw: " + flags().get(0));
        server.mark();
    }

    /** Cheat once and report whether the anticheat noticed. */
    private boolean detectorSeesCheat() {
        ctx.runOnClient(mc -> mc.player.setPos(mc.player.getX() + 6.0, mc.player.getY(), mc.player.getZ()));
        ctx.waitTicks(60);
        return !flags().isEmpty();
    }

    /**
     * Attack whatever the crosshair is on, each time the swing has fully recharged, for {@code ticks} ticks.
     *
     * <p>Holding the attack key does not do this. Vanilla swings at an entity once per key <i>press</i> —
     * holding repeats only for breaking blocks — so a held key is one hit and then {@code ticks} of standing
     * there: a fight that never happened, reported as a clean one.
     *
     * @return how many swings were made
     */
    public int attackFor(int ticks) {
        int swings = 0;
        for (int tick = 0; tick < ticks; tick++) {
            if (ctx.computeOnClient(mc -> mc.player.getAttackStrengthScale(0f) >= 1.0f)) {
                ctx.getInput().pressKey(options -> options.keyAttack);
                swings++;
            }
            ctx.waitTick();
        }
        return swings;
    }

    /** Dump every entity and where it is relative to the client — the first question when a run is odd. */
    public void reportEntities() {
        server.command("testkit report");
        ctx.waitTicks(10);
    }

    /** Where the client is right now: {@code {x, y, z}}. */
    public double[] playerPosition() {
        return ctx.computeOnClient(mc ->
                new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
    }

    /** Print a labelled line into the run output, so a trace reads in order with the harness's own. */
    public void log(String message) {
        System.out.println("[" + name + "] " + message);
    }

    public String name() {
        return name;
    }

    public TestServer server() {
        return server;
    }

    public ClientGameTestContext ctx() {
        return ctx;
    }
}
