package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Benching on a saved world rather than a generated one — and the harness proving that works.
 *
 * <p>The first half builds a marker, and the server saves the world as it stops; that save is copied into
 * {@code run/saves} the way a map of your own would sit there. The second half starts its server on the copy
 * with {@link TestServer#seedWorldFrom} and requires the marker to be there. Pass means the copy carried the
 * region files across; a copy that missed them starts the server on freshly generated terrain, and the
 * marker is not there.
 *
 * <p>For your own map, the second half is the whole pattern: {@code seedWorldFrom(save, spawn)} after the
 * filter has said the scenario runs, {@code Scenario.run}, and clear the seed in a {@code finally}.
 * {@code -Pworld=<folder under run/saves>} points a scenario that reads {@link TestServer#worldFromProperty}
 * at a different save.
 */
public class SeedWorldTest implements FabricClientGameTest {

    private static final BlockPos MARKER = new BlockPos(3, TestMap.GROUND_Y + 1, 3);
    private static final String SAVE = "testkit-seed-selftest";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        Path save = TestServer.worldFromProperty() != null
                ? TestServer.worldFromProperty()
                : TestServer.savesDir().resolve(SAVE);

        boolean built = false;
        if (!Scenario.skip("02-seed-world-build")) {
            Scenario.run(ctx, "02-seed-world-build",
                    (server, scenario) -> TestMap.on(server)
                            .platform(8)
                            .fill(MARKER.getX(), MARKER.getY(), MARKER.getZ(),
                                    MARKER.getX(), MARKER.getY(), MARKER.getZ(), "gold_block")
                            .survival()
                            .spawn(0.5, 0.5, 0f)
                            .build(),
                    (server, scenario) -> scenario.log("marker placed at " + MARKER.toShortString()));
            // The server has stopped and saved by now: Scenario.run closed it on the way out.
            copySave(TestServer.savesDir().resolveSibling("testserver").resolve("world"), save);
            built = true;
        }

        if (Scenario.skip("02-seed-world-load")) {
            return;
        }
        if (!built && !Files.isRegularFile(save.resolve("level.dat"))) {
            throw new AssertionError("no save at " + save + " — run both halves: -Pscenario=02-seed-world");
        }
        try {
            TestServer.seedWorldFrom(save, new int[]{0, TestMap.GROUND_Y + 1, 0});
            Scenario.run(ctx, "02-seed-world-load",
                    (server, scenario) -> TestMap.on(server)
                            .survival()
                            .spawn(0.5, 0.5, 0f)
                            .build(),
                    (server, scenario) -> {
                        boolean there = ctx.computeOnClient(mc ->
                                mc.level.getBlockState(MARKER).is(Blocks.GOLD_BLOCK));
                        if (!there) {
                            throw new AssertionError("the marker is not at " + MARKER.toShortString()
                                    + " — the server started on generated terrain, not the save");
                        }
                        scenario.log("the saved world's marker is at " + MARKER.toShortString());
                    });
        } finally {
            TestServer.seedWorldFrom(null, null);
        }
    }

    private static void copySave(Path world, Path save) {
        try {
            if (Files.exists(save)) {
                try (var walk = Files.walk(save)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
                }
            }
            try (var walk = Files.walk(world)) {
                for (Path source : (Iterable<Path>) walk::iterator) {
                    Path target = save.resolve(world.relativize(source).toString());
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else if (!source.getFileName().toString().equals("session.lock")) {
                        Files.copy(source, target);
                    }
                }
            }
        } catch (IOException e) {
            throw new AssertionError("could not copy the test server's world to " + save, e);
        }
    }
}
