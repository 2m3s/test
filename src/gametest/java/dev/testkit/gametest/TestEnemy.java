package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A configurable opponent for a scenario to test against — built with a fluent description, spawned on
 * the server, and driven tick by tick.
 *
 * <pre>{@code
 * TestEnemy attacker = TestEnemy.named("Velocity", "1dps")
 *         .at(5, 151, 0)
 *         .health(20)
 *         .frozen(true)
 *         .heldItem("minecraft:stick")
 *         .attacking(3.0f, 20, 1.0f)     // reach, one hit per second, 1 damage
 *         .spawn(ctx, server);
 *
 * attacker.drive(60);                     // 60 ticks of it doing its thing
 * }</pre>
 *
 * <h2>A real player, not a mob</h2>
 * Every enemy is a genuine {@code ServerPlayer}, joined by the companion server mod with a connection that
 * goes nowhere. Modules pick targets by entity type, so an aura that filters for players cannot see a zombie
 * at all, and player hitboxes, eye heights, reach and knockback all resolve differently from a mob's. A
 * scenario that needs a mob summons one with {@code server.command("summon …")}.
 *
 * <h2>Why it is driven from the test rather than the server</h2>
 * The anticheat server runs as its own process and the harness talks to it through its console, so
 * everything here is expressed as commands rather than server-side code. That turns out to be a feature:
 * the behaviour is visible in the console log next to the anticheat's own output, so a flag and the thing
 * that caused it appear in the same place, in order.
 *
 * <h2>Naming</h2>
 * Every enemy is {@code SicoKaleb<Module><detail>} — {@code SicoKalebK1dps}, {@code SicoKalebKAStatic}. The name is the join
 * key between a scenario, a console log and a verbose line after the fact, so it is required rather than
 * optional. The {@code SicoKaleb} prefix is also how {@code testkit sweep} tells a leftover opponent from a real
 * tester.
 */
public final class TestEnemy {

    /** How the enemy moves under {@link #drive}. */
    public enum Walk { NONE, DIRECTION, WAYPOINTS, CHASE }

    private final String module;
    private final String detail;

    private double x;
    private double y;
    private double z;
    private double scale = 1.0;
    private int health = 20;
    private boolean frozen;
    private boolean jumping;
    private boolean baby;

    private String heldItem;
    private String offhandItem;
    private String helmet;
    private String chestplate;
    private String leggings;
    private String boots;

    private Walk walk = Walk.NONE;
    private double walkYaw;
    private boolean sprint;

    private final List<double[]> waypoints = new ArrayList<>();
    private boolean repeatRoute = true;

    private float reach;
    private int attackDelayTicks;
    private float damagePerHit = 1.0f;

    private ClientGameTestContext ctx;
    private TestServer server;
    private String name;


    private TestEnemy(String module, String detail) {
        this.module = module;
        this.detail = detail;
    }

    /**
     * Build the in-game name. The convention is {@code SicoKaleb<Module><detail>}, but Minecraft caps a player name
     * at <b>16 characters</b> and a longer one does not fail politely: the server throws
     * {@code EncoderException: String too big} while encoding {@code player_info_update} and kicks every real
     * client on the server. So the module is abbreviated to its capitals — Velocity to V, KillAura to KA — and
     * the result is truncated. The detail is kept, because that is the part that distinguishes one run from
     * another.
     */
    static String mcName(String module, String detail) {
        StringBuilder initials = new StringBuilder();
        for (char c : module.toCharArray()) {
            if (Character.isUpperCase(c)) {
                initials.append(c);
            }
        }
        String base = "SicoKaleb" + (initials.isEmpty() ? module : initials) + detail;
        return base.length() <= 16 ? base : base.substring(0, 16);
    }

    /** Start describing an enemy. The pair becomes its name: {@code SicoKaleb<Module><detail>}. */
    public static TestEnemy named(String module, String detail) {
        return new TestEnemy(module, detail);
    }

    // ------------------------------------------------------------------ shape

    public TestEnemy at(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    /** Body scale, via the vanilla {@code minecraft:scale} attribute — the hitbox, not just the model. */
    public TestEnemy size(double scale) {
        this.scale = scale;
        return this;
    }

    /**
     * Half size. There are no baby players, so this halves the scale attribute, which is what a baby's
     * hitbox amounts to: a smaller box to aim at, lower eyes, shorter reach to its body. Combines with
     * {@link #size}.
     */
    public TestEnemy baby(boolean isBaby) {
        this.baby = isBaby;
        return this;
    }

    /** 1–20, in half-hearts as usual. Also raises max health so the value sticks. */
    public TestEnemy health(int hearts) {
        this.health = Math.max(1, Math.min(20, hearts));
        return this;
    }

    /**
     * Immovable: full knockback resistance, no gravity, no AI. It still takes damage and still dies —
     * and {@link #drive} puts it straight back at its anchor when it does, so a scenario measuring hits
     * never quietly loses its target halfway through.
     */
    public TestEnemy frozen(boolean isFrozen) {
        this.frozen = isFrozen;
        return this;
    }

    // ----------------------------------------------------------------- gear

    public TestEnemy heldItem(String item) {
        this.heldItem = item;
        return this;
    }

    /** A shield in the off hand is the usual reason to set this. */
    public TestEnemy offhand(String item) {
        this.offhandItem = item;
        return this;
    }

    public TestEnemy armor(String helmetItem, String chestItem, String legsItem, String bootsItem) {
        this.helmet = helmetItem;
        this.chestplate = chestItem;
        this.leggings = legsItem;
        this.boots = bootsItem;
        return this;
    }

    public TestEnemy shield(boolean withShield) {
        return withShield ? offhand("minecraft:shield") : this;
    }

    // -------------------------------------------------------------- movement

    /**
     * Walk in a fixed compass direction.
     *
     * @param yaw            direction of travel, in Minecraft yaw degrees
     * @param doSprint       sprint speed rather than walk speed
     * @param jumpConstantly hop the whole way, as {@link #jumping} does
     */
    public TestEnemy walking(double yaw, boolean doSprint, boolean jumpConstantly) {
        this.walk = Walk.DIRECTION;
        this.walkYaw = yaw;
        this.sprint = doSprint;
        if (jumpConstantly) {
            this.jumping = true;
        }
        return this;
    }

    /**
     * Follow the client, stopping just inside {@code reach}. Without this an attacker knocks its target
     * out of its own range on the first hit and then stands there — the first run of the knockback
     * scenario landed three hits and called it a day.
     */
    public TestEnemy chasing(boolean doChase) {
        if (doChase) {
            this.walk = Walk.CHASE;
        }
        return this;
    }

    /**
     * Hop continuously, on a vanilla jump's arc (0.42 up, 0.08 gravity, 0.98 drag, about twelve ticks a hop),
     * on top of whatever else it is doing — standing, frozen at its anchor, walking or chasing. For checking
     * that a module tracks a target whose height changes.
     */
    public TestEnemy jumping(boolean isJumping) {
        this.jumping = isJumping;
        return this;
    }

    /**
     * Follow a route. The enemy walks each position in turn at walk speed; with {@code repeat} it loops back
     * to the first, otherwise it stops at the last.
     */
    public TestEnemy route(boolean repeat, double[]... positions) {
        this.walk = Walk.WAYPOINTS;
        this.repeatRoute = repeat;
        this.waypoints.clear();
        for (double[] position : positions) {
            this.waypoints.add(position);
        }
        return this;
    }

    // -------------------------------------------------------------- attacking

    /**
     * Hit the client whenever it comes within {@code reach}, no more often than {@code delayTicks}.
     *
     * @param reachBlocks how close the client has to be
     * @param delayTicks  ticks between hits — 20 is one per second
     * @param damage      damage per hit, in half-hearts
     */
    public TestEnemy attacking(float reachBlocks, int delayTicks, float damage) {
        this.reach = reachBlocks;
        this.attackDelayTicks = Math.max(1, delayTicks);
        this.damagePerHit = damage;
        return this;
    }

    // ----------------------------------------------------------------- spawn

    /** Put it in the world. Returns itself so a scenario can go straight on to driving it. */
    public TestEnemy spawn(ClientGameTestContext context, TestServer testServer) {
        this.ctx = context;
        this.server = testServer;
        this.name = mcName(module, detail);

        server.command(String.format(Locale.ROOT, "testkit spawn %s %.2f %.2f %.2f", name, x, y, z));
        ctx.waitTicks(10);

        server.command(String.format(Locale.ROOT, "testkit health %s %d", name, health));
        server.command(String.format(Locale.ROOT, "testkit scale %s %.3f", name, baby ? scale * 0.5 : scale));
        if (frozen) {
            server.command("testkit frozen " + name + " true");
        }
        if (jumping) {
            server.command("testkit jump " + name + " true");
        }
        gear("mainhand", heldItem);
        gear("offhand", offhandItem);
        gear("head", helmet);
        gear("chest", chestplate);
        gear("legs", leggings);
        gear("feet", boots);

        switch (walk) {
            case DIRECTION -> server.command(String.format(Locale.ROOT,
                    "testkit walk %s %.1f %b", name, walkYaw, sprint));
            case CHASE -> server.command(String.format(Locale.ROOT,
                    "testkit chase %s %.2f", name, Math.max(1.0, reach * 0.6)));
            case WAYPOINTS -> {
                StringBuilder route = new StringBuilder();
                for (double[] point : waypoints) {
                    if (route.length() > 0) {
                        route.append(';');
                    }
                    route.append(String.format(Locale.ROOT, "%.2f,%.2f,%.2f", point[0], point[1], point[2]));
                }
                server.command(String.format(Locale.ROOT, "testkit route %s %b %s",
                        name, repeatRoute, route));
            }
            default -> { }
        }
        if (reach > 0) {
            server.command(String.format(Locale.ROOT, "testkit attack %s %.2f %d %.2f",
                    name, reach, attackDelayTicks, damagePerHit));
        }
        ctx.waitTicks(10);

        System.out.println("[enemy] " + name + " at "
                + String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", x, y, z)
                + (frozen ? " frozen" : "") + (jumping ? " jumping" : "")
                + (reach > 0 ? " attacking r=" + reach : ""));
        return this;
    }

    private void gear(String slot, String item) {
        if (item != null) {
            server.command("testkit gear " + name + " " + slot + " " + item);
        }
    }

    // ------------------------------------------------------------------ drive

    /**
     * Run the enemy for {@code ticks} client ticks. The behaviour itself runs on the server, once per
     * server tick, so this only has to let time pass.
     */
    public void drive(int ticks) {
        ctx.waitTicks(ticks);
    }

    /** Where the enemy is now, as the client sees it. */
    public double[] position() {
        return ctx.computeOnClient(mc -> {
            for (var candidate : mc.level.players()) {
                if (candidate.getGameProfile().name().equals(name)) {
                    return new double[]{candidate.getX(), candidate.getY(), candidate.getZ()};
                }
            }
            return new double[]{Double.NaN, Double.NaN, Double.NaN};
        });
    }

    /**
     * The enemy's health as the client sees it. Living-entity health is synced to every client, so this
     * is how a scenario proves an aura actually connected rather than merely pointed at something.
     */
    public float health() {
        return ctx.computeOnClient(mc -> {
            for (var candidate : mc.level.players()) {
                if (candidate.getGameProfile().name().equals(name)) {
                    return candidate.getHealth();
                }
            }
            return Float.NaN;
        });
    }

    /** Whether the client can actually see it as a player entity — the thing a module targets. */
    public boolean visibleToClient() {
        return !Double.isNaN(position()[0]);
    }

    /**
     * How many times this enemy has actually damaged the client.
     *
     * <p>Counted from the <b>server's own log</b>, not from client-side health. Health looked untouched
     * across a run in which the server was demonstrably landing a hit a second, so a client-side count
     * reported zero while the scenario was working perfectly — measuring the wrong end of the
     * connection. The server is where the hit happens and where it is worth counting.
     *
     * <p>Only blows that landed. A swing the server refused — the client in creative, invulnerable, or
     * still in its hurt frames — is counted by {@link #refused()} instead, because it delivered no damage
     * and no knockback, and a knockback test that counted it would pass on a run that had none.
     */
    public int hits() {
        return server.countSince(name + ": hit ");
    }

    /** Swings in reach that the server refused to let land. Non-zero usually means the client is in creative. */
    public int refused() {
        return server.countSince(name + ": refused");
    }

    public String name() {
        return name;
    }

    /**
     * How many times this enemy has been killed and put back, counted from the server's own log.
     *
     * <p>Necessary because a respawned enemy is back at full health, so comparing health before and
     * after a run reports "never damaged" for a target that was killed outright.
     */
    public int deaths() {
        return server.countSince(name + ": died and rejoined");
    }

    /** Distance from the client right now — for asserting a module kept or lost its target. */
    public double distanceToPlayer() {
        double[] here = position();
        if (Double.isNaN(here[0])) {
            return Double.NaN;
        }
        double[] player = ctx.computeOnClient(mc ->
                new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()});
        return Math.sqrt(Math.pow(player[0] - here[0], 2) + Math.pow(player[1] - here[1], 2)
                + Math.pow(player[2] - here[2], 2));
    }

    /** Remove it. Scenarios that spawn several should clean up so the next one starts from an empty world. */
    public void remove() {
        server.command("testkit remove " + name);
    }
}
