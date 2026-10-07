package dev.testkit.gametest;

import java.util.Locale;

/**
 * Builds the world a scenario runs in, one readable line at a time.
 *
 * <pre>{@code
 * TestMap.on(server)
 *         .platform(0, 150, 0, 12)        // 25x25 stone pad centred on x=0,z=0, surface at y=151
 *         .walls(4)                       // barrier walls so nothing walks off by accident
 *         .catchFloor(140)                // somewhere survivable to land
 *         .bridgeGap(1, 40)               // carve everything past x=1 into void
 *         .spawn(-3.5, 0.5, -90f)         // stand here, facing east (+X)
 *         .build();
 * }</pre>
 *
 * <h2>Why every block goes through the companion mod</h2>
 * Vanilla {@code /fill} silently does nothing in chunks the server has not loaded. It reports "No blocks
 * were filled" and carries on, so a scenario builds its arena into a void and then measures a module
 * against terrain that was never there — a player spawning at y=147 already falling, landing on
 * generated hillside, and the run scoring whatever happened down there. Force-loading first mostly works
 * and intermittently does not. {@code /testkit fill} goes through {@code setBlockAndUpdate}, which loads
 * what it needs and cannot half-build.
 *
 * <p>Every method returns {@code this}; nothing is sent until {@link #build()}.
 */
public final class TestMap {

    private final TestServer server;
    private final StringBuilder plan = new StringBuilder();

    /** Every platform is built at this layer unless told otherwise, so players stand at {@code Y + 1}. */
    public static final int GROUND_Y = 150;

    private int surfaceY = GROUND_Y + 1;
    // Remembered from the last platform() so walls() can enclose it rather than guessing.
    private int lastCx;
    private int lastCz;
    private int lastRadius = 12;
    private double spawnX = 0.5;
    private double spawnZ = 0.5;
    private float spawnYaw;
    private float spawnPitch;
    private boolean hasSpawn;

    private TestMap(TestServer server) {
        this.server = server;
    }

    public static TestMap on(TestServer server) {
        return new TestMap(server);
    }

    /** The y a player stands at. Set automatically by {@link #platform}; override if you build by hand. */
    public TestMap surface(int y) {
        this.surfaceY = y;
        return this;
    }

    public int surfaceY() {
        return surfaceY;
    }

    // ------------------------------------------------------------------ shapes

    /** A square platform at the standard {@link #GROUND_Y} layer. Players stand at {@code GROUND_Y + 1}. */
    public TestMap platform(int cx, int cz, int radius) {
        return platform(cx, GROUND_Y, cz, radius);
    }

    /** A square platform centred on the origin at the standard layer. */
    public TestMap platform(int radius) {
        return platform(0, GROUND_Y, 0, radius);
    }

    /**
     * A square platform. {@code y} is the block layer, so players stand at {@code y + 1}.
     *
     * @param radius half-width in blocks; 12 gives a 25x25 pad
     */
    public TestMap platform(int cx, int y, int cz, int radius) {
        surfaceY = y + 1;
        lastCx = cx;
        lastCz = cz;
        lastRadius = radius;
        return fill(cx - radius, y, cz - radius, cx + radius, y, cz + radius, "smooth_stone")
                .fill(cx - radius, y + 1, cz - radius, cx + radius, y + 4, cz + radius, "air");
    }

    /** A rectangular slab of floor at one layer. */
    public TestMap floor(int x1, int y, int z1, int x2, int z2) {
        return fill(x1, y, z1, x2, y, z2, "smooth_stone");
    }

    /**
     * Barrier walls around the last {@link #platform}, so a test that goes wrong stops at the edge
     * instead of falling out of the world.
     *
     * <p>Uses the last platform's own centre and radius. It used to assume radius 12 whatever the
     * platform was, which walled a 48-block pad at 12 and left the player spawning <i>outside</i> the
     * walls — a sprint-jump example then measured 0.14 blocks/tick, less than half vanilla, because it
     * spent the run against a barrier.
     */
    public TestMap walls(int height) {
        return walls(lastCx, surfaceY - 1, lastCz, lastRadius, height);
    }

    public TestMap walls(int cx, int y, int cz, int radius, int height) {
        fill(cx - radius, y + 1, cz - radius, cx - radius, y + height, cz + radius, "barrier");
        fill(cx + radius, y + 1, cz - radius, cx + radius, y + height, cz + radius, "barrier");
        fill(cx - radius, y + 1, cz - radius, cx + radius, y + height, cz - radius, "barrier");
        fill(cx - radius, y + 1, cz + radius, cx + radius, y + height, cz + radius, "barrier");
        return this;
    }

    /**
     * A floor far below, so a fall is survivable and the run still finishes. Without one a missed
     * placement ends the scenario in the void and the result says nothing about the module.
     *
     * <p>Sized to the play area rather than the world: every block goes through {@code setBlockAndUpdate}
     * on the server thread, and a 193x193 slab is thirty-seven thousand of them.
     */
    public TestMap catchFloor(int y) {
        return catchFloor(y, 64);
    }

    public TestMap catchFloor(int y, int radius) {
        return fill(-radius, y, -radius, radius, y, radius, "stone");
    }

    /** Carve the void a bridge has to cross: everything at the surface layer past {@code fromX}. */
    public TestMap bridgeGap(int fromX, int toX) {
        return fill(fromX, surfaceY - 1, -32, toX, surfaceY - 1, 32, "air");
    }

    /** Carve a diagonal void: past {@code fromX} AND past {@code fromZ}. */
    public TestMap diagonalGap(int fromX, int fromZ, int to) {
        return fill(fromX, surfaceY - 1, fromZ, to, surfaceY - 1, to, "air");
    }

    /** A staircase climbing in +X, one block per step — for upward-bridging scenarios. */
    public TestMap stairs(int fromX, int y, int z, int steps, int width) {
        for (int i = 0; i < steps; i++) {
            fill(fromX + i, y + i, z - width, fromX + i, y + i, z + width, "smooth_stone");
        }
        return this;
    }

    /** A pillar to tower up, or to bridge away from. */
    public TestMap pillar(int x, int y, int z, int height) {
        return fill(x, y, z, x, y + height, z, "smooth_stone");
    }

    /** A wall to run into — for testing what a module does when it is blocked. */
    public TestMap wall(int x, int y, int z1, int z2, int height) {
        return fill(x, y + 1, z1, x, y + height, z2, "smooth_stone");
    }

    /**
     * Make the server refuse any block placed into this box, the way spawn protection or a claim does: the
     * client predicts the block and the server puts the cell back. Lasts for the scenario — every scenario's
     * server starts with none — or until {@code testkit unprotect} or {@code testkit sweep}.
     */
    public TestMap protect(int x1, int y1, int z1, int x2, int y2, int z2) {
        plan.append(String.format(Locale.ROOT, "testkit protect %d %d %d %d %d %d%n", x1, y1, z1, x2, y2, z2));
        return this;
    }

    /** Anything else. Block ids may be given with or without the {@code minecraft:} prefix. */
    public TestMap fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        String id = block.contains(":") ? block : "minecraft:" + block;
        plan.append(String.format(Locale.ROOT, "testkit fill %d %d %d %d %d %d %s%n",
                x1, y1, z1, x2, y2, z2, id));
        return this;
    }

    // ------------------------------------------------------------------ player

    /** Where the player starts. Applied after the world is built, so it cannot race the fills. */
    public TestMap spawn(double x, double z, float yaw) {
        return spawn(x, z, yaw, 0f);
    }

    public TestMap spawn(double x, double z, float yaw, float pitch) {
        this.spawnX = x;
        this.spawnZ = z;
        this.spawnYaw = yaw;
        this.spawnPitch = pitch;
        this.hasSpawn = true;
        return this;
    }

    /** Empty the hotbar and hand the player something. Repeatable. */
    public TestMap give(String item, int count) {
        String id = item.contains(":") ? item : "minecraft:" + item;
        plan.append(String.format(Locale.ROOT, "give @p %s %d%n", id, count));
        return this;
    }

    public TestMap clearInventory() {
        plan.append("clear @p").append(System.lineSeparator());
        return this;
    }

    public TestMap survival() {
        plan.append("gamemode survival @p").append(System.lineSeparator());
        return this;
    }

    public TestMap creative() {
        plan.append("gamemode creative @p").append(System.lineSeparator());
        return this;
    }

    /** Any command, if the builder does not cover it. */
    public TestMap command(String command) {
        plan.append(command).append(System.lineSeparator());
        return this;
    }

    // ------------------------------------------------------------------- build

    /**
     * Send everything, then place the player and let it settle.
     *
     * <p>The player is moved <b>after</b> the world exists, deliberately. Teleporting during the build
     * races the fills, and a run that measured the player at y=150.54 — sunk into the pad it was standing
     * on — reported the pad as missing.
     */
    public void build() {
        for (String line : plan.toString().split("\\R")) {
            if (!line.isBlank()) {
                server.command(line.trim());
            }
        }
        // Wait for every block to actually be placed before the player is put on it.
        server.sync(120);
        if (hasSpawn) {
            server.command(String.format(Locale.ROOT, "tp @p %.2f %d %.2f %.1f %.1f",
                    spawnX, surfaceY, spawnZ, spawnYaw, spawnPitch));
            server.sync(30);
        }
    }
}
