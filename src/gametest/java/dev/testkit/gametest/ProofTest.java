package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.List;

/**
 * Cheats on purpose and fails if the anticheat stays quiet.
 *
 * <p>Every other scenario asserts the anticheat said <i>nothing</i>. That is only worth something if it
 * is capable of saying something, and a harness that has silently lost its anticheat reports exactly the
 * same result as a module that is clean. This is the other half of the pair.
 */
public class ProofTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        Scenario.runExpectingFlags(ctx, "01-proof",
                (server, scenario) -> TestMap.on(server)
                        .platform(12)
                        .survival()
                        .spawn(0.5, 0.5, 0f)
                        .build(),
                (server, scenario) -> {
                    scenario.log("cheating deliberately…");
                    for (int i = 0; i < 6; i++) {
                        ctx.runOnClient(mc -> mc.player.setPos(
                                mc.player.getX() + 4.0, mc.player.getY(), mc.player.getZ()));
                        ctx.waitTicks(6);
                    }
                    for (int i = 0; i < 20; i++) {
                        ctx.runOnClient(mc -> mc.player.setPos(
                                mc.player.getX(), mc.player.getY() + 0.35, mc.player.getZ()));
                        ctx.waitTicks(2);
                    }
                    ctx.waitTicks(40);

                    List<String> flags = scenario.flags();
                    scenario.log("anticheat produced " + flags.size() + " line(s):");
                    for (String flag : flags) {
                        System.out.println("    " + flag);
                    }
                    if (flags.isEmpty()) {
                        throw new AssertionError("Flew and teleported across the map and the anticheat "
                                + "said NOTHING. The detector is not working, so every 'clean' result "
                                + "in this suite is meaningless.");
                    }
                    scenario.log("detector confirmed working");
                });
    }
}
