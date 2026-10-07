package dev.testkit.gametest;

import dev.testkit.harness.Latency;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.core.BlockPos;

import java.util.Locale;

/**
 * Block placement and breaking — the maps a scaffold, tower or nuker needs.
 *
 * <ol>
 *   <li>{@link #straightGap} — the basic bridging map, and how to count what got built.</li>
 *   <li>{@link #diagonalGap} — a diagonal crossing, which is a different problem entirely.</li>
 *   <li>{@link #risingGap} — bridging that has to climb.</li>
 *   <li>{@link #towerUp} — vertical placement from a standing start.</li>
 *   <li>{@link #breakWall} — breaking rather than placing.</li>
 *   <li>{@link #refusedPlacement} — a placement the server turns down, with a control that it did not.</li>
 * </ol>
 *
 * <p>With no block-placing module installed these runs simply walk off the edge and land on the catch
 * floor — which is the correct result for an empty kit, and proves the map is right. Enable your module
 * in the body and the same scenario becomes a real test.
 */
public class PlacementExamples implements FabricClientGameTest {

    private static final int GROUND = TestMap.GROUND_Y;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        straightGap(ctx);
        diagonalGap(ctx);
        risingGap(ctx);
        towerUp(ctx);
        breakWall(ctx);
        refusedPlacement(ctx);
    }

    /** Count blocks that appeared in the gap during the run — not every solid block in it. */
    private static int builtInGap(ClientGameTestContext ctx, int y, int fromX, int toX) {
        return ctx.computeOnClient(mc -> {
            int count = 0;
            for (int x = fromX; x <= toX; x++) {
                for (int z = -12; z <= 40; z++) {
                    if (!mc.level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                        count++;
                    }
                }
            }
            return count;
        });
    }

    /**
     * A walkway that stops, with void beyond it. The map any bridging module needs.
     *
     * <p>Note the two assertions before the run: the player must be on the walkway, and there must be a
     * gap. A scenario on a map that did not build measures whatever the world generated instead, and
     * reports it as a result.
     */
    private void straightGap(ClientGameTestContext ctx) {
        Scenario.run(ctx, "40-place-straight-gap",
                (server, scenario) -> TestMap.on(server)
                        .platform(-6, 0, 8)
                        .catchFloor(140)
                        .bridgeGap(3, 60)
                        .survival()
                        .clearInventory()
                        .give("white_wool", 64)
                        .spawn(-3.5, 0.5, -90f)
                        .build(),
                (server, scenario) -> {
                    double[] start = scenario.playerPosition();
                    if (Math.abs(start[1] - (GROUND + 1)) > 0.2) {
                        throw new AssertionError("player at y=" + start[1] + " — the walkway did not build");
                    }
                    boolean gapExists = ctx.computeOnClient(mc ->
                            mc.level.getBlockState(new BlockPos(10, GROUND, 0)).isAir());
                    if (!gapExists) {
                        throw new AssertionError("there is no gap to bridge — this map cannot test anything");
                    }
                    int before = builtInGap(ctx, GROUND, 3, 60);

                    // >>> enable your bridging module here <<<
                    ctx.getInput().holdKey(options -> options.keyUp);
                    ctx.waitTicks(220);
                    ctx.getInput().releaseKey(options -> options.keyUp);

                    double[] end = scenario.playerPosition();
                    scenario.log(String.format(Locale.ROOT,
                            "built %d block(s), travelled %.1f, ended at y=%.2f",
                            builtInGap(ctx, GROUND, 3, 60) - before, end[0] - start[0], end[1]));
                });
    }

    /**
     * A gap crossed at 45 degrees.
     *
     * <p>Worth its own scenario because a diagonal is not a harder straight line, it is a different
     * problem: consecutive blocks share only an <i>edge</i>, and you cannot place against an edge, so the
     * bridge has to staircase. A module that handles straight bridging perfectly can place two blocks
     * here and walk into the void.
     */
    private void diagonalGap(ClientGameTestContext ctx) {
        Scenario.run(ctx, "41-place-diagonal-gap",
                (server, scenario) -> TestMap.on(server)
                        .platform(-6, -6, 8)
                        .catchFloor(140)
                        .diagonalGap(1, 1, 60)
                        .survival()
                        .clearInventory()
                        .give("white_wool", 64)
                        .spawn(-3.5, -3.5, -45f)
                        .build(),
                (server, scenario) -> {
                    double[] start = scenario.playerPosition();
                    int before = builtInGap(ctx, GROUND, 1, 60);

                    ctx.getInput().holdKey(options -> options.keyUp);
                    ctx.waitTicks(220);
                    ctx.getInput().releaseKey(options -> options.keyUp);

                    double[] end = scenario.playerPosition();
                    scenario.log(String.format(Locale.ROOT,
                            "built %d block(s), ended at (%.1f, %.2f, %.1f)",
                            builtInGap(ctx, GROUND, 1, 60) - before, end[0], end[1], end[2]));
                });
    }

    /**
     * A gap with the far side one block higher — bridging that has to climb.
     *
     * <p>The landing being above the take-off changes which face of the last block is clickable, which is
     * the whole difficulty.
     */
    private void risingGap(ClientGameTestContext ctx) {
        Scenario.run(ctx, "42-place-rising-gap",
                (server, scenario) -> TestMap.on(server)
                        .platform(-6, 0, 8)
                        .catchFloor(140)
                        .bridgeGap(3, 60)
                        .stairs(20, GROUND, 0, 8, 4)     // the far side, climbing
                        .survival()
                        .clearInventory()
                        .give("white_wool", 64)
                        .spawn(-3.5, 0.5, -90f)
                        .build(),
                (server, scenario) -> {
                    double[] start = scenario.playerPosition();
                    ctx.getInput().holdKey(options -> options.keyUp);
                    ctx.waitTicks(220);
                    ctx.getInput().releaseKey(options -> options.keyUp);
                    double[] end = scenario.playerPosition();
                    scenario.log(String.format(Locale.ROOT, "ended at (%.1f, %.2f) — started y=%.2f",
                            end[0], end[1], start[1]));
                });
    }

    /**
     * Standing still, placing upward — a tower.
     *
     * <p>The map is deliberately plain: what is being tested is entirely the module's timing, so the
     * world should contribute nothing.
     */
    private void towerUp(ClientGameTestContext ctx) {
        Scenario.run(ctx, "43-place-tower",
                (server, scenario) -> TestMap.on(server)
                        .platform(12)
                        .survival()
                        .clearInventory()
                        .give("white_wool", 64)
                        .spawn(0.5, 0.5, 0f, 90f)     // looking straight down
                        .build(),
                (server, scenario) -> {
                    double startY = scenario.playerPosition()[1];

                    // >>> enable your tower module here <<<
                    ctx.getInput().holdKey(options -> options.keyJump);
                    ctx.waitTicks(160);
                    ctx.getInput().releaseKey(options -> options.keyJump);
                    ctx.waitTicks(40);

                    double endY = scenario.playerPosition()[1];
                    scenario.log(String.format(Locale.ROOT, "climbed %.1f blocks (y %.1f -> %.1f)",
                            endY - startY, startY, endY));
                });
    }

    /**
     * Breaking rather than placing.
     *
     * <p>Counts the wall before and after, so the assertion is about blocks removed rather than about
     * the run having finished.
     */
    private void breakWall(ClientGameTestContext ctx) {
        Scenario.run(ctx, "44-place-break-wall",
                (server, scenario) -> TestMap.on(server)
                        .platform(16)
                        .survival()
                        .clearInventory()
                        .give("diamond_pickaxe", 1)
                        .fill(3, GROUND + 1, -3, 3, GROUND + 3, 3, "stone")
                        .spawn(0.5, 0.5, -90f)
                        .build(),
                (server, scenario) -> {
                    int before = ctx.computeOnClient(mc -> {
                        int count = 0;
                        for (int y = GROUND + 1; y <= GROUND + 3; y++) {
                            for (int z = -3; z <= 3; z++) {
                                if (!mc.level.getBlockState(new BlockPos(3, y, z)).isAir()) {
                                    count++;
                                }
                            }
                        }
                        return count;
                    });

                    ctx.getInput().holdKey(options -> options.keyAttack);
                    ctx.waitTicks(200);
                    ctx.getInput().releaseKey(options -> options.keyAttack);

                    int after = ctx.computeOnClient(mc -> {
                        int count = 0;
                        for (int y = GROUND + 1; y <= GROUND + 3; y++) {
                            for (int z = -3; z <= 3; z++) {
                                if (!mc.level.getBlockState(new BlockPos(3, y, z)).isAir()) {
                                    count++;
                                }
                            }
                        }
                        return count;
                    });
                    scenario.log("wall went from " + before + " to " + after + " blocks");
                });
    }

    /**
     * A placement the server refuses, the way a protected region refuses one.
     *
     * <p>The client predicts the block, the server sends the cell back as it was, and the block stands only
     * for the round trip in between. {@code /testkit protect} is what makes the server say no; nothing else
     * on the test server ever does. The same click is made first with no protection, as the control: if that
     * one does not place, the aim is wrong and the refused half would be measuring nothing.
     *
     * <p>The refused click is made over 150 ms of {@link Latency}. Without it the local server's answer lands
     * before the client's next tick and the predicted block is never up for a whole tick — which is not what
     * any player on a real connection sees.
     */
    private void refusedPlacement(ClientGameTestContext ctx) {
        BlockPos cell = new BlockPos(1, GROUND + 1, 0);
        Scenario.run(ctx, "45-place-refused",
                (server, scenario) -> TestMap.on(server)
                        .platform(12)
                        .survival()
                        .clearInventory()
                        .give("white_wool", 16)
                        // A block to click the west face of, from where the player stands, facing east.
                        .fill(2, GROUND + 1, 0, 2, GROUND + 1, 0, "stone")
                        .spawn(0.5, 0.5, -90f, 37f)
                        .build(),
                (server, scenario) -> {
                    ctx.getInput().pressKey(options -> options.keyUse);
                    ctx.waitTicks(10);
                    boolean placed = ctx.computeOnClient(mc -> !mc.level.getBlockState(cell).isAir());
                    if (!placed) {
                        throw new AssertionError("the control click placed nothing at " + cell.toShortString()
                                + " — the aim is wrong, so the refused half would test nothing");
                    }
                    scenario.log("control: placed at " + cell.toShortString());

                    server.command("setblock 1 " + (GROUND + 1) + " 0 minecraft:air");
                    server.command("testkit protect 1 " + (GROUND + 1) + " 0 1 " + (GROUND + 1) + " 0");
                    server.sync(10);
                    ctx.waitTicks(5);

                    ctx.runOnClient(mc -> Latency.install(150));
                    ctx.waitTicks(10);
                    ctx.getInput().pressKey(options -> options.keyUse);
                    int up = 0;
                    for (int tick = 0; tick < 20; tick++) {
                        ctx.waitTick();
                        if (ctx.computeOnClient(mc -> !mc.level.getBlockState(cell).isAir())) {
                            up++;
                        }
                    }
                    ctx.runOnClient(mc -> Latency.remove());
                    boolean standing = ctx.computeOnClient(mc -> !mc.level.getBlockState(cell).isAir());
                    scenario.log("refused over 150 ms: the predicted block was up for " + up + " tick(s), "
                            + (standing ? "and is STILL there" : "then the server put the cell back"));
                    if (standing) {
                        throw new AssertionError("the protected cell kept its block — the server did not refuse it");
                    }
                    if (up == 0) {
                        throw new AssertionError("the predicted block was never up for a tick over 150 ms of latency "
                                + "— either the client did not predict it or the latency stage is not in");
                    }
                });
    }
}
