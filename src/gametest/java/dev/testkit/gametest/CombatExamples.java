package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.Locale;

/**
 * Combat scenarios — opponents that hit back, follow routes, and come in crowds.
 *
 * <ol>
 *   <li>{@link #knockback} — measure displacement, or the run proves nothing.</li>
 *   <li>{@link #frozenTarget} — an immovable dummy, for aura and reach work.</li>
 *   <li>{@link #patrol} — a bot on a waypoint route, for tracking and prediction.</li>
 *   <li>{@link #crowd} — several bots at once, for target selection.</li>
 *   <li>{@link #armouredVsNaked} — the same fight twice, to isolate one variable.</li>
 *   <li>{@link #hoppingTarget} — a target whose height keeps changing.</li>
 * </ol>
 */
public class CombatExamples implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        knockback(ctx);
        frozenTarget(ctx);
        patrol(ctx);
        crowd(ctx);
        armouredVsNaked(ctx);
        hoppingTarget(ctx);
    }

    /** A flat arena with nothing on it, so only the scenario influences the result. */
    private static void arena(TestServer server, double spawnX, double spawnZ, float yaw) {
        TestMap.on(server)
                .platform(24)
                .walls(5)
                .catchFloor(140)
                .survival()
                .clearInventory()
                .spawn(spawnX, spawnZ, yaw)
                .build();
    }

    /**
     * A bot that chases and hits once a second, for velocity and knockback work.
     *
     * <p><b>The important line is the displacement assertion.</b> Ten hits that move the client nowhere
     * means no knockback was delivered — and a "clean" anticheat result would then be a statement about
     * nothing at all. That exact mistake produced a green run for a whole afternoon during this kit's
     * development, so the check is not optional.
     */
    private void knockback(ClientGameTestContext ctx) {
        Scenario.run(ctx, "30-combat-knockback",
                (server, scenario) -> arena(server, 0.5, 0.5, -90f),
                (server, scenario) -> {
                    double[] start = scenario.playerPosition();

                    TestEnemy attacker = TestEnemy.named("Knockback", "1dps")
                            .at(3.5, TestMap.GROUND_Y + 1, 0.5)
                            .health(20)
                            .heldItem("minecraft:stick")
                            .attacking(4.0f, 20, 1.0f)   // reach, one hit per second, half a heart
                            .chasing(true)               // or it knocks the client out of its own range
                            .spawn(ctx, server);

                    attacker.drive(200);

                    double[] end = scenario.playerPosition();
                    double moved = Math.hypot(end[0] - start[0], end[2] - start[2]);
                    scenario.log(String.format(Locale.ROOT, "%d hit(s), client displaced %.2f blocks",
                            attacker.hits(), moved));

                    if (attacker.hits() < 5) {
                        throw new AssertionError("only " + attacker.hits() + " hits — the bot never "
                                + "reached the client, so nothing about knockback was tested");
                    }
                    if (moved < 0.5) {
                        throw new AssertionError("client moved " + moved + " blocks under "
                                + attacker.hits() + " hits — no knockback was delivered");
                    }
                    attacker.remove();
                });
    }

    /**
     * An immovable dummy that still takes damage, for aura and reach work.
     *
     * <p>{@code frozen(true)} gives it full knockback resistance and no gravity, and it respawns at its
     * anchor when killed — so a scenario counting hits never quietly loses its target halfway through.
     */
    private void frozenTarget(ClientGameTestContext ctx) {
        Scenario.run(ctx, "31-combat-frozen-target",
                (server, scenario) -> {
                    arena(server, 0.5, 0.5, -90f);
                    server.command("give @p minecraft:diamond_sword");
                },
                (server, scenario) -> {
                    TestEnemy dummy = TestEnemy.named("Aura", "Dummy")
                            .at(2.5, TestMap.GROUND_Y + 1, 0.5)
                            .health(20)
                            .frozen(true)
                            .armor("minecraft:iron_helmet", "minecraft:iron_chestplate",
                                    "minecraft:iron_leggings", "minecraft:iron_boots")
                            .spawn(ctx, server);

                    if (!dummy.visibleToClient()) {
                        throw new AssertionError("the bot never appeared to the client — a module that "
                                + "filters for players would have nothing to find");
                    }
                    int swings = scenario.attackFor(200);
                    if (dummy.deaths() == 0) {
                        throw new AssertionError(swings + " swings and the dummy never died — the crosshair "
                                + "is not on it, so nothing about combat was tested");
                    }

                    scenario.log(swings + " swings, killed it " + dummy.deaths() + " time(s), it is "
                            + String.format(Locale.ROOT, "%.2f", dummy.distanceToPlayer())
                            + " blocks away and back at its anchor");
                    dummy.remove();
                });
    }

    /**
     * A bot walking a repeating route, for anything that tracks or predicts a moving target.
     *
     * <p>A route is more useful than a chase here: it is deterministic, so two runs are comparable.
     */
    private void patrol(ClientGameTestContext ctx) {
        Scenario.run(ctx, "32-combat-patrol",
                (server, scenario) -> arena(server, 0.5, 0.5, -90f),
                (server, scenario) -> {
                    int y = TestMap.GROUND_Y + 1;
                    TestEnemy runner = TestEnemy.named("Track", "Patrol")
                            .at(8.5, y, 8.5)
                            .health(20)
                            .route(true,                                  // repeat forever
                                    new double[]{8.5, y, 8.5},
                                    new double[]{8.5, y, -8.5},
                                    new double[]{-8.5, y, -8.5},
                                    new double[]{-8.5, y, 8.5})
                            .spawn(ctx, server);

                    for (int second = 0; second < 10; second++) {
                        runner.drive(20);
                        double[] at = runner.position();
                        scenario.log(String.format(Locale.ROOT, " t%2ds bot at (%6.2f, %6.2f) %.2f away",
                                second + 1, at[0], at[2], runner.distanceToPlayer()));
                    }
                    runner.remove();
                });
    }

    /**
     * Four bots at once, for target selection.
     *
     * <p>Which one a module picks — nearest, most damaged, in front — is exactly the kind of thing that
     * looks right in code and is wrong in play.
     */
    private void crowd(ClientGameTestContext ctx) {
        Scenario.run(ctx, "33-combat-crowd",
                (server, scenario) -> {
                    arena(server, 0.5, 0.5, -90f);
                    server.command("give @p minecraft:diamond_sword");
                },
                (server, scenario) -> {
                    int y = TestMap.GROUND_Y + 1;
                    TestEnemy near = TestEnemy.named("Crowd", "Near")
                            .at(2.5, y, 0.5).health(20).frozen(true).spawn(ctx, server);
                    TestEnemy far = TestEnemy.named("Crowd", "Far")
                            .at(9.5, y, 0.5).health(20).frozen(true).spawn(ctx, server);
                    TestEnemy left = TestEnemy.named("Crowd", "Left")
                            .at(0.5, y, 5.5).health(20).frozen(true).spawn(ctx, server);
                    TestEnemy small = TestEnemy.named("Crowd", "Small")
                            .at(-3.5, y, 0.5).health(6).size(0.6).frozen(true).spawn(ctx, server);

                    scenario.attackFor(160);

                    scenario.log("deaths — near " + near.deaths() + ", far " + far.deaths()
                            + ", left " + left.deaths() + ", small " + small.deaths());
                    scenario.reportEntities();

                    near.remove();
                    far.remove();
                    left.remove();
                    small.remove();
                });
    }

    /**
     * The same fight twice, changing exactly one thing.
     *
     * <p>The pattern worth copying: two bots, identical but for armour, and a single number compared
     * between them. One scenario that changes two variables at once tells you nothing about either.
     */
    private void armouredVsNaked(ClientGameTestContext ctx) {
        Scenario.run(ctx, "34-combat-armour-ab",
                (server, scenario) -> {
                    arena(server, 0.5, 0.5, -90f);
                    server.command("give @p minecraft:diamond_sword");
                },
                (server, scenario) -> {
                    int y = TestMap.GROUND_Y + 1;

                    TestEnemy naked = TestEnemy.named("AB", "Naked")
                            .at(2.5, y, 0.5).health(20).frozen(true).spawn(ctx, server);
                    scenario.attackFor(120);
                    int nakedDeaths = naked.deaths();
                    naked.remove();
                    ctx.waitTicks(20);

                    TestEnemy armoured = TestEnemy.named("AB", "Armoured")
                            .at(2.5, y, 0.5).health(20).frozen(true)
                            .armor("minecraft:diamond_helmet", "minecraft:diamond_chestplate",
                                    "minecraft:diamond_leggings", "minecraft:diamond_boots")
                            .spawn(ctx, server);
                    scenario.attackFor(120);
                    int armouredDeaths = armoured.deaths();
                    armoured.remove();

                    scenario.log("kills in 120 ticks — naked " + nakedDeaths
                            + ", diamond-armoured " + armouredDeaths);
                });
    }

    /**
     * A dummy that hops on the spot, for anything that aims at a target whose height changes.
     *
     * <p>The height range is asserted before anything else is: a "moving target" test against a target that
     * never left the ground is a static-target test with a misleading name.
     */
    private void hoppingTarget(ClientGameTestContext ctx) {
        Scenario.run(ctx, "35-combat-hopping-target",
                (server, scenario) -> arena(server, 0.5, 0.5, -90f),
                (server, scenario) -> {
                    TestEnemy hopper = TestEnemy.named("Aim", "Hop")
                            .at(3.5, TestMap.GROUND_Y + 1, 0.5)
                            .health(20)
                            .frozen(true)
                            .jumping(true)
                            .spawn(ctx, server);

                    double low = Double.MAX_VALUE;
                    double high = -Double.MAX_VALUE;
                    for (int tick = 0; tick < 60; tick++) {
                        hopper.drive(1);
                        double y = hopper.position()[1];
                        low = Math.min(low, y);
                        high = Math.max(high, y);
                    }
                    scenario.log(String.format(Locale.ROOT, "the bot's feet ranged over y %.2f to %.2f", low, high));
                    if (high - low < 0.8) {
                        throw new AssertionError("the bot rose only " + (high - low) + " blocks — it is not hopping");
                    }
                    hopper.remove();
                });
    }
}
