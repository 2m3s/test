package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Worked examples. Copy one, change the map and the body, register it in
 * {@code src/gametest/resources/fabric.mod.json}, and run it.
 *
 * <p>Each shows a different shape of test:
 *
 * <ul>
 *   <li><b>walk</b> — the client does something and the anticheat must not object. The common case.</li>
 *   <li><b>bridge</b> — a map with a gap in it, for anything that places blocks.</li>
 *   <li><b>duel</b> — a fake player that fights back, for combat and knockback work.</li>
 * </ul>
 */
public class ExampleTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        walk(ctx);
        bridge(ctx);
        duel(ctx);
    }

    /** The simplest useful scenario: move for ten seconds, assert the anticheat stayed quiet. */
    private void walk(ClientGameTestContext ctx) {
        Scenario.run(ctx, "10-example-walk",
                (server, scenario) -> TestMap.on(server)
                        .platform(24)
                        .walls(4)
                        .catchFloor(140)
                        .survival()
                        .spawn(0.5, 0.5, 0f)
                        .build(),
                (server, scenario) -> {
                    ctx.getInput().holdKey(options -> options.keyUp);
                    ctx.getInput().holdKey(options -> options.keySprint);
                    ctx.waitTicks(200);
                    ctx.getInput().releaseKey(options -> options.keySprint);
                    ctx.getInput().releaseKey(options -> options.keyUp);

                    double[] end = scenario.playerPosition();
                    scenario.log(String.format("ended at (%.2f, %.2f, %.2f)", end[0], end[1], end[2]));
                });
    }

    /** A walkway that stops, with void beyond it — the shape any block-placing module needs. */
    private void bridge(ClientGameTestContext ctx) {
        Scenario.run(ctx, "11-example-bridge",
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
                    // Assert the map is what you think it is BEFORE blaming the module under test. A run
                    // on a walkway that was never built measures whatever the world generated instead.
                    double[] start = scenario.playerPosition();
                    if (Math.abs(start[1] - 151) > 0.2) {
                        throw new AssertionError("player is at y=" + start[1] + ", not on the walkway — "
                                + "the map did not build");
                    }
                    ctx.getInput().holdKey(options -> options.keyUp);
                    ctx.waitTicks(220);
                    ctx.getInput().releaseKey(options -> options.keyUp);

                    // With no block-placing module installed the player simply walks off the end and
                    // lands on the catch floor. That is the point of the example: it shows the map, and
                    // it shows the map working. Enable your own module in this body and the same
                    // scenario becomes a real bridging test.
                    double[] end = scenario.playerPosition();
                    scenario.log(String.format("travelled %.1f blocks, ended at y=%.2f (%s)",
                            end[0] - start[0], end[1],
                            end[1] > 149 ? "still up top — something bridged the gap"
                                    : "down on the catch floor, as expected with no module installed"));
                });
    }

    /** A real fake player that chases and hits, for combat and knockback work. */
    private void duel(ClientGameTestContext ctx) {
        Scenario.run(ctx, "12-example-duel",
                (server, scenario) -> TestMap.on(server)
                        .platform(24)
                        .walls(4)
                        .survival()
                        .clearInventory()
                        .give("diamond_sword", 1)
                        .spawn(0.5, 0.5, -90f)
                        .build(),
                (server, scenario) -> {
                    TestEnemy opponent = TestEnemy.named("Example", "Duel")
                            .at(4.5, 151, 0.5)
                            .health(20)
                            .armor("minecraft:iron_helmet", "minecraft:iron_chestplate",
                                    "minecraft:iron_leggings", "minecraft:iron_boots")
                            .heldItem("minecraft:stone_sword")
                            .attacking(3.5f, 20, 2.0f)
                            .chasing(true)
                            .spawn(ctx, server);

                    if (!opponent.visibleToClient()) {
                        throw new AssertionError("the fake player never appeared to the client");
                    }
                    // One press per recharged swing — holding the key swings at an entity only once.
                    scenario.attackFor(200);

                    scenario.log(opponent.hits() + " hit(s) taken, opponent died "
                            + opponent.deaths() + " time(s)");
                    opponent.remove();
                });
    }
}
