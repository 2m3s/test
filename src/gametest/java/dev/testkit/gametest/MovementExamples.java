package dev.testkit.gametest;

import dev.testkit.harness.Latency;
import dev.testkit.harness.PacketTrace;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.List;
import java.util.Locale;

/**
 * Movement scenarios — the shapes you need for speed, flight, timer, no-fall and bhop work.
 *
 * <p>Read these in order. Each adds one idea:
 *
 * <ol>
 *   <li>{@link #sprintJump} — measure the thing you are changing, not just "did it flag".</li>
 *   <li>{@link #longFall} — a map feature (height) rather than an input drives the test.</li>
 *   <li>{@link #obstacleCourse} — build terrain and assert the player got through it.</li>
 *   <li>{@link #iceAndSlab} — surfaces change physics, and the anticheat models each one.</li>
 *   <li>{@link #sampledRun} — sample every second so a failure says *when* it went wrong.</li>
 *   <li>{@link #packetTrace} — read what went on the wire, tick by tick.</li>
 *   <li>{@link #withLatency} — give the local server a real round trip, and prove it has one.</li>
 * </ol>
 */
public class MovementExamples implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        sprintJump(ctx);
        longFall(ctx);
        obstacleCourse(ctx);
        iceAndSlab(ctx);
        sampledRun(ctx);
        packetTrace(ctx);
        withLatency(ctx);
    }

    /**
     * Sprint-jump in a straight line and measure the speed achieved.
     *
     * <p>The lesson here is the assertion. "The anticheat did not object" is only half a result — if the
     * player never moved, nothing objected either. Measuring blocks-per-tick means the run proves the
     * movement happened at all, and it gives you a number to compare against when you change something.
     */
    private void sprintJump(ClientGameTestContext ctx) {
        Scenario.run(ctx, "20-move-sprint-jump",
                (server, scenario) -> TestMap.on(server)
                        .platform(48)
                        .walls(4)
                        .catchFloor(140)
                        .survival()
                        .spawn(-40.5, 0.5, -90f)
                        .build(),
                (server, scenario) -> {
                    double[] start = scenario.playerPosition();
                    ctx.getInput().holdKey(options -> options.keyUp);
                    ctx.getInput().holdKey(options -> options.keySprint);
                    ctx.getInput().holdKey(options -> options.keyJump);
                    ctx.waitTicks(200);
                    ctx.getInput().releaseKey(options -> options.keyJump);
                    ctx.getInput().releaseKey(options -> options.keySprint);
                    ctx.getInput().releaseKey(options -> options.keyUp);

                    double[] end = scenario.playerPosition();
                    double travelled = Math.hypot(end[0] - start[0], end[2] - start[2]);
                    scenario.log(String.format(Locale.ROOT,
                            "travelled %.2f blocks in 200 ticks = %.4f blocks/tick",
                            travelled, travelled / 200.0));

                    // Vanilla sprint-jumping is about 0.35 blocks/tick. Anything far below means the run
                    // did not actually happen and the clean result means nothing.
                    if (travelled < 20) {
                        throw new AssertionError("only travelled " + travelled + " blocks — the player "
                                + "did not run, so this scenario tested nothing");
                    }
                });
    }

    /**
     * Walk off a high ledge and land.
     *
     * <p>Useful for no-fall work. Note the map does the work: there is no clever input here, just a
     * platform with nothing beyond it and a floor a long way down.
     */
    private void longFall(ClientGameTestContext ctx) {
        Scenario.run(ctx, "21-move-long-fall",
                (server, scenario) -> TestMap.on(server)
                        .platform(-8, 0, 8)          // a small ledge
                        .catchFloor(60)              // ninety blocks down
                        .survival()
                        .spawn(-6.5, 0.5, -90f)
                        .build(),
                (server, scenario) -> {
                    ctx.getInput().holdKey(options -> options.keyUp);
                    ctx.waitTicks(40);
                    ctx.getInput().releaseKey(options -> options.keyUp);
                    ctx.waitTicks(120);              // let the fall finish

                    double[] end = scenario.playerPosition();
                    float health = ctx.computeOnClient(mc -> mc.player.getHealth());
                    scenario.log(String.format(Locale.ROOT, "landed at y=%.1f with %.1f health",
                            end[1], health));

                    if (end[1] > 140) {
                        throw new AssertionError("never fell — the ledge did not end where expected");
                    }
                });
    }

    /**
     * A course with walls to go round and stairs to climb.
     *
     * <p>Shows {@link TestMap#wall} and {@link TestMap#stairs}, and the pattern of asserting that the
     * player <i>got somewhere</i> rather than merely that nothing complained.
     */
    private void obstacleCourse(ClientGameTestContext ctx) {
        Scenario.run(ctx, "22-move-obstacle-course",
                (server, scenario) -> TestMap.on(server)
                        .platform(32)
                        .walls(6)
                        .catchFloor(140)
                        // A wall with a gap at z>2, so the route has to bend round it.
                        .wall(-8, TestMap.GROUND_Y, -20, 2, 4)
                        // Then stairs to climb on the far side.
                        .stairs(4, TestMap.GROUND_Y + 1, 0, 6, 3)
                        .survival()
                        .spawn(-20.5, 6.5, -90f)
                        .build(),
                (server, scenario) -> {
                    double[] start = scenario.playerPosition();
                    ctx.getInput().holdKey(options -> options.keyUp);
                    ctx.getInput().holdKey(options -> options.keySprint);
                    ctx.waitTicks(200);
                    ctx.getInput().releaseKey(options -> options.keySprint);
                    ctx.getInput().releaseKey(options -> options.keyUp);

                    double[] end = scenario.playerPosition();
                    scenario.log(String.format(Locale.ROOT, "from x=%.1f to x=%.1f, y=%.1f",
                            start[0], end[0], end[1]));
                });
    }

    /**
     * Ice, soul sand and slabs — three surfaces with three different friction and speed models.
     *
     * <p>Worth a scenario of its own because an anticheat models each one, and a movement module that is
     * clean on stone can be wrong on ice.
     */
    private void iceAndSlab(ClientGameTestContext ctx) {
        Scenario.run(ctx, "23-move-surfaces",
                (server, scenario) -> TestMap.on(server)
                        .platform(32)
                        .walls(6)
                        .catchFloor(140)
                        .fill(-20, TestMap.GROUND_Y, -6, 0, TestMap.GROUND_Y, 6, "blue_ice")
                        .fill(2, TestMap.GROUND_Y, -6, 10, TestMap.GROUND_Y, 6, "soul_sand")
                        .fill(12, TestMap.GROUND_Y, -6, 20, TestMap.GROUND_Y, 6, "smooth_stone_slab")
                        .survival()
                        .spawn(-24.5, 0.5, -90f)
                        .build(),
                (server, scenario) -> {
                    ctx.getInput().holdKey(options -> options.keyUp);
                    ctx.getInput().holdKey(options -> options.keySprint);
                    ctx.waitTicks(220);
                    ctx.getInput().releaseKey(options -> options.keySprint);
                    ctx.getInput().releaseKey(options -> options.keyUp);
                    double[] end = scenario.playerPosition();
                    scenario.log(String.format(Locale.ROOT, "crossed all three, ended at x=%.1f", end[0]));
                });
    }

    /**
     * The same straight run, but sampled every second.
     *
     * <p>This is the single most useful habit in this suite. When a run fails, a per-second trace of
     * position, ground contact and whatever counter your module exposes tells you <i>which tick</i> it
     * went wrong on. Without it you are guessing from a single end state.
     */
    private void sampledRun(ClientGameTestContext ctx) {
        Scenario.run(ctx, "24-move-sampled",
                (server, scenario) -> TestMap.on(server)
                        .platform(48)
                        .walls(4)
                        .catchFloor(140)
                        .survival()
                        .spawn(-40.5, 0.5, -90f)
                        .build(),
                (server, scenario) -> {
                    ctx.getInput().holdKey(options -> options.keyUp);
                    ctx.getInput().holdKey(options -> options.keySprint);
                    for (int second = 0; second < 10; second++) {
                        ctx.waitTicks(20);
                        double[] at = ctx.computeOnClient(mc -> new double[]{
                                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                                mc.player.onGround() ? 1 : 0});
                        scenario.log(String.format(Locale.ROOT,
                                " t%2ds  (%7.2f, %6.2f, %6.2f) ground=%s",
                                second + 1, at[0], at[1], at[2], at[3] > 0 ? "Y" : "n"));
                    }
                    ctx.getInput().releaseKey(options -> options.keySprint);
                    ctx.getInput().releaseKey(options -> options.keyUp);
                });
    }

    /**
     * Sprint-jump with the packet trace on, and hold the wire to what a vanilla client sends.
     *
     * <p>When a packet-level check flags, the verbose line names the check but not what earned it; the trace
     * is that half. Here it also checks itself: every whole tick must end in exactly one
     * {@code client_tick_end} and carry at most one movement packet, which is what vanilla sends. A trace
     * that recorded nothing fails the first of those, so an empty table cannot pass for a clean one.
     */
    private void packetTrace(ClientGameTestContext ctx) {
        Scenario.run(ctx, "25-move-packet-trace",
                (server, scenario) -> TestMap.on(server)
                        .platform(48)
                        .walls(4)
                        .catchFloor(140)
                        .survival()
                        .spawn(-40.5, 0.5, -90f)
                        .build(),
                (server, scenario) -> {
                    ctx.runOnClient(mc -> PacketTrace.start());
                    ctx.getInput().holdKey(options -> options.keyUp);
                    ctx.getInput().holdKey(options -> options.keySprint);
                    ctx.getInput().holdKey(options -> options.keyJump);
                    ctx.waitTicks(60);
                    ctx.getInput().releaseKey(options -> options.keyJump);
                    ctx.getInput().releaseKey(options -> options.keySprint);
                    ctx.getInput().releaseKey(options -> options.keyUp);
                    ctx.runOnClient(mc -> PacketTrace.stop());

                    List<String> lines = PacketTrace.lines();
                    lines.stream().limit(8).forEach(scenario::log);
                    scenario.log("... " + lines.size() + " tick(s) in all");

                    // The first bucket is the part-tick before the first tick started and the last may be cut
                    // short by stop(), so only whole ticks are held to the rule.
                    List<String> movement = List.of("move_player_pos", "move_player_rot",
                            "move_player_pos_rot", "move_player_status_only");
                    int whole = 0;
                    for (int tick = 1; tick < PacketTrace.tickCount() - 1; tick++) {
                        List<String> sent = PacketTrace.sentOn(tick);
                        long ends = sent.stream().filter("client_tick_end"::equals).count();
                        long moves = sent.stream().filter(movement::contains).count();
                        if (ends != 1 || moves > 1) {
                            throw new AssertionError("tick " + tick + " sent " + sent + " — a vanilla client ends "
                                    + "every tick exactly once and moves at most once in it");
                        }
                        whole++;
                    }
                    if (whole < 50) {
                        throw new AssertionError("only " + whole + " whole tick(s) recorded in a 60-tick run — "
                                + "the trace is not seeing the connection");
                    }
                    scenario.log(whole + " whole ticks, each with one tick end and at most one movement packet; "
                            + PacketTrace.sent("move_player_pos_rot") + " move_player_pos_rot, "
                            + PacketTrace.received("player_position") + " setback(s)");
                });
    }

    /**
     * The same sprint-jump over a 100 ms round trip.
     *
     * <p>The local server answers inside the tick that asked, which no real connection does, so anything whose
     * behaviour depends on the gap between an action and the server's answer is untested without this. The
     * scenario proves the delay is real before it relies on it — a block set by the server takes measurably
     * longer to reach the client with the stage in — and then that taking it out again is clean too: a
     * naive delay pulled out mid-stream reorders the anticheat's transactions and flags the harness.
     */
    private void withLatency(ClientGameTestContext ctx) {
        Scenario.run(ctx, "26-move-latency",
                (server, scenario) -> TestMap.on(server)
                        .platform(48)
                        .walls(4)
                        .catchFloor(140)
                        .survival()
                        .spawn(-40.5, 0.5, -90f)
                        .build(),
                (server, scenario) -> {
                    int direct = arrival(ctx, server, "gold_block");
                    ctx.runOnClient(mc -> Latency.install(250));
                    ctx.waitTicks(10);
                    int delayed = arrival(ctx, server, "diamond_block");
                    scenario.log("a server-side block change reached the client in " + direct
                            + " tick(s) direct, " + delayed + " through 250 ms of latency");
                    if (delayed - direct < 3) {
                        throw new AssertionError("250 ms of latency added only " + (delayed - direct)
                                + " tick(s) — the delay stage is not in the pipeline");
                    }

                    ctx.runOnClient(mc -> Latency.install(100));
                    double[] start = scenario.playerPosition();
                    ctx.getInput().holdKey(options -> options.keyUp);
                    ctx.getInput().holdKey(options -> options.keySprint);
                    ctx.getInput().holdKey(options -> options.keyJump);
                    ctx.waitTicks(160);
                    ctx.getInput().releaseKey(options -> options.keyJump);
                    ctx.getInput().releaseKey(options -> options.keySprint);
                    ctx.getInput().releaseKey(options -> options.keyUp);
                    double[] end = scenario.playerPosition();
                    double travelled = Math.hypot(end[0] - start[0], end[2] - start[2]);
                    scenario.log(String.format(Locale.ROOT, "travelled %.1f blocks over 100 ms of latency",
                            travelled));
                    if (travelled < 20) {
                        throw new AssertionError("only travelled " + travelled + " blocks — the run did not happen");
                    }
                    // Out again, mid-stream, while the server is still talking. Must drain in order.
                    ctx.runOnClient(mc -> Latency.remove());
                    ctx.waitTicks(40);
                });
    }

    /** Ticks from the server setting a block to the client seeing it. */
    private static int arrival(ClientGameTestContext ctx, TestServer server, String block) {
        net.minecraft.core.BlockPos at = new net.minecraft.core.BlockPos(0, TestMap.GROUND_Y + 8, 20);
        server.command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:%s",
                at.getX(), at.getY(), at.getZ(), block));
        return ctx.waitFor(mc -> net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(mc.level.getBlockState(at).getBlock()).getPath().equals(block), 200);
    }
}
