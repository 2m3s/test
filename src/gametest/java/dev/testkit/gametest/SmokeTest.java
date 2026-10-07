package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Proves the harness before anything relies on it: the server starts, the client joins, and — the one
 * that matters — a real violation is actually SEEN.
 *
 * <p>Run this first on a new machine. Every failure mode it covers is otherwise silent: a missing
 * anticheat does not error, it produces a run with no flags, which is indistinguishable from a clean one.
 */
public class SmokeTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        Scenario.run(ctx, "00-smoke",
                (server, scenario) -> TestMap.on(server)
                        .platform(8)
                        .catchFloor(140)
                        .survival()
                        .spawn(0.5, 0.5, 0f)
                        .build(),
                (server, scenario) -> {
                    scenario.assertDetectorWorks();
                    scenario.log("harness OK — anticheat is live and detecting");
                });
    }
}
