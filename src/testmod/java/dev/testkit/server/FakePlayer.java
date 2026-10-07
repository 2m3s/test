package dev.testkit.server;

import com.mojang.authlib.GameProfile;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A real {@link ServerPlayer} on the server, with no client behind it.
 *
 * <p>Test scenarios need an opponent that <b>is a player</b>, not a mob wearing a name tag. A mob is a
 * near-miss in the ways that matter: modules pick targets by entity type (an aura that filters for
 * players will not see a zombie at all), player hitboxes and eye heights differ, and knockback,
 * attack cooldown and reach all resolve against player attributes. Testing an aura against a zombie
 * measures the zombie.
 *
 * <p>The only awkward part is the connection. {@code PlayerList.placeNewPlayer} wants one and will write
 * the join sequence down it, so this supplies a {@link Connection} whose {@code send} goes nowhere and
 * which is backed by an {@link EmbeddedChannel} so the netty plumbing it touches is real rather than
 * null. Everything after that is an ordinary server player: it ticks, takes damage, takes knockback,
 * shows up in {@code @a}, and is visible to every other client on the server.
 */
public final class FakePlayer {

    /** Not a real socket, but the server asks for one. Loopback keeps anything that logs it sane. */
    private static final SocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", 0);

    private final MinecraftServer server;
    private final String name;
    private final Vec3 anchor;
    /** Reassigned on respawn: PlayerList.respawn returns a NEW ServerPlayer, not the old one revived. */
    private ServerPlayer player;

    private boolean frozen;
    private float attackReach;
    private int attackDelay;
    private float attackDamage = 1.0f;
    private int sinceAttack;
    private int notes;
    private int deaths;
    private float maxHealth = 20f;
    private double scale = 1.0;

    /** Hopping on {@link #HOP}'s arc; {@code hop} is how far above its footing that has put it this tick. */
    private boolean jumping;
    private int hopTick;
    private double hop;

    private double walkYaw;
    private double walkSpeed;
    private boolean walking;
    private boolean chasing;
    private double chaseStop = 2.0;

    private final List<Vec3> route = new ArrayList<>();
    private boolean repeatRoute = true;
    private int routeIndex;

    private FakePlayer(MinecraftServer server, ServerPlayer player, String name, Vec3 anchor) {
        this.server = server;
        this.player = player;
        this.name = name;
        this.anchor = anchor;
    }

    /**
     * Join a fake player to the server at the given position.
     *
     * @param name the player's name — the convention is {@code SicoKaleb<Module><detail>}
     */
    public static FakePlayer join(MinecraftServer server, ServerLevel level, String name, Vec3 position) {
        // A stable UUID per name, so re-running a scenario reuses the same profile rather than
        // accumulating one player file per run.
        UUID id = UUID.nameUUIDFromBytes(("TestKitPlayer:" + name).getBytes(StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(id, name);

        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        SilentConnection connection = new SilentConnection();
        // Gives the connection a live channel and sets it up as active.
        connection.embedded = new EmbeddedChannel(connection);

        server.getPlayerList().placeNewPlayer(connection, player,
                CommonListenerCookie.createInitial(profile, false));
        // Survival, explicitly. A new ServerPlayer inherits the server's default gamemode, and the test
        // server's default is creative -- which is invulnerable, so an aura would visibly connect and the
        // target would never take a point of damage or flash red.
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.teleportTo(position.x, position.y, position.z);

        return new FakePlayer(server, player, name, position);
    }

    // --------------------------------------------------------------- properties

    /** Immovable: full knockback resistance and no gravity. It still takes damage and still dies. */
    public void setFrozen(boolean value) {
        this.frozen = value;
        setAttribute(Attributes.KNOCKBACK_RESISTANCE, value ? 1.0 : 0.0);
        player.setNoGravity(value);
    }

    public void setHealth(float health) {
        this.maxHealth = health;
        setAttribute(Attributes.MAX_HEALTH, Math.max(1.0, health));
        player.setHealth(health);
    }

    /** How many times it has been killed and put back. */
    public int deaths() {
        return deaths;
    }

    /** Body scale, through the vanilla attribute — changes the hitbox, not just the model. */
    public void setScale(double value) {
        this.scale = value;
        setAttribute(Attributes.SCALE, value);
    }

    /** Hop continuously on a vanilla jump's arc, on top of whatever else it is doing. */
    public void setJumping(boolean value) {
        this.jumping = value;
        this.hopTick = 0;
    }

    private void setAttribute(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                              double value) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    public void walk(double yaw, boolean sprint) {
        this.walking = true;
        this.chasing = false;
        this.walkYaw = yaw;
        this.walkSpeed = sprint ? 0.28 : 0.21;
    }

    /** Follow the nearest real player, stopping {@code stopAt} blocks short. */
    public void chase(double stopAt) {
        this.chasing = true;
        this.walking = false;
        this.chaseStop = stopAt;
        this.walkSpeed = 0.24;
    }

    public void route(boolean repeat, List<Vec3> positions) {
        this.route.clear();
        this.route.addAll(positions);
        this.repeatRoute = repeat;
        this.routeIndex = 0;
        this.walking = false;
        this.chasing = false;
        this.walkSpeed = 0.21;
    }

    /** Hit any real player within {@code reach}, at most once every {@code delayTicks}. */
    public void attacking(float reach, int delayTicks, float damage) {
        this.attackReach = reach;
        this.attackDelay = Math.max(1, delayTicks);
        this.attackDamage = damage;
    }

    public ServerPlayer entity() {
        return player;
    }

    public String name() {
        return name;
    }

    public void remove() {
        // sweep takes a bot out by name before it clears the registry, so this can arrive for one already gone.
        if (!player.hasDisconnected()) {
            player.connection.disconnect(Component.literal("test over"));
        }
    }

    // ------------------------------------------------------------------- tick

    /**
     * Heights above its footing, tick by tick, for one vanilla jump: 0.42 up, then each tick 0.08 of gravity
     * and 0.98 drag, until it is back down — about twelve ticks. A fake player has no physics of its own (it
     * is moved by teleports), so the arc is replayed rather than simulated.
     */
    private static final double[] HOP = hopArc();

    private static double[] hopArc() {
        List<Double> heights = new ArrayList<>();
        double height = 0;
        double velocity = 0.42;
        while (true) {
            height += velocity;
            velocity = (velocity - 0.08) * 0.98;
            if (height <= 0) {
                break;
            }
            heights.add(height);
        }
        heights.add(0.0);
        double[] out = new double[heights.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = heights.get(i);
        }
        return out;
    }

    /** Called once per server tick by {@link FakePlayerManager}. */
    public void tick() {
        // isRemoved() must NOT short-circuit here. A dead player is a REMOVED player, so returning early
        // on it skipped the respawn branch entirely and the corpse stayed wherever it fell -- which is
        // exactly what "it killed the fake player and it never came back" looked like.
        if (player.isRemoved() || !player.isAlive()) {
            // Rejoin rather than PlayerList.respawn. respawn() writes the new-player packets down the
            // player's OWN connection, which for a fake player goes nowhere, so the server had it
            // standing at the anchor while every real client still saw an empty platform. A fresh join
            // goes through the normal broadcast path, which is the one observers actually receive.
            deaths++;
            java.util.EnumMap<EquipmentSlot, ItemStack> carried = new java.util.EnumMap<>(EquipmentSlot.class);
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                carried.put(slot, player.getItemBySlot(slot).copy());
            }
            player.connection.disconnect(Component.literal("respawning"));
            player = join(server, server.overworld(), name, anchor).player;
            for (java.util.Map.Entry<EquipmentSlot, ItemStack> entry : carried.entrySet()) {
                player.setItemSlot(entry.getKey(), entry.getValue());
            }
            // Everything the scenario configured goes back on the new body. Scale used to be left behind, so
            // a small target came back full size the first time it was killed and the rest of the run
            // measured a different hitbox.
            setHealth(maxHealth);
            setFrozen(frozen);
            setScale(scale);
            hop = 0;
            hopTick = 0;
            report("died and rejoined at anchor " + anchor + " (death " + deaths + ")");
            return;
        }
        // Where the body stands this tick, without last tick's hop.
        Vec3 footing = new Vec3(player.getX(), player.getY() - hop, player.getZ());
        Vec3 next = frozen ? anchor : move(footing);
        double nextHop = jumping ? HOP[hopTick++ % HOP.length] : 0;
        if (frozen) {
            player.setDeltaMovement(Vec3.ZERO);
        }
        if (frozen || !next.equals(footing) || nextHop != hop) {
            player.teleportTo(next.x, next.y + nextHop, next.z);
        }
        hop = nextHop;
        attack();
    }

    /** Where walking, chasing or a route takes the body from {@code at} this tick. Turns it to face the way. */
    private Vec3 move(Vec3 at) {
        if (walking) {
            double radians = Math.toRadians(walkYaw);
            face((float) walkYaw);
            return at.add(-Math.sin(radians) * walkSpeed, 0, Math.cos(radians) * walkSpeed);
        }
        if (chasing) {
            ServerPlayer target = nearestRealPlayer();
            if (target == null) {
                return at;
            }
            double dx = target.getX() - at.x;
            double dz = target.getZ() - at.z;
            double flat = Math.sqrt(dx * dx + dz * dz);
            if (flat <= chaseStop) {
                return at;
            }
            double step = Math.min(walkSpeed, flat - chaseStop);
            face((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
            return new Vec3(at.x + dx / flat * step, target.getY(), at.z + dz / flat * step);
        }
        if (!route.isEmpty()) {
            Vec3 goal = route.get(routeIndex);
            Vec3 delta = goal.subtract(at);
            double distance = delta.length();
            if (distance < walkSpeed) {
                routeIndex++;
                if (routeIndex >= route.size()) {
                    routeIndex = repeatRoute ? 0 : route.size() - 1;
                }
                return goal;
            }
            face((float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0));
            return at.add(delta.scale(walkSpeed / distance));
        }
        return at;
    }

    private void face(float yaw) {
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
    }

    private void attack() {
        if (attackReach <= 0) {
            return;
        }
        if (++sinceAttack < attackDelay) {
            return;
        }
        ServerPlayer target = nearestRealPlayer();
        if (target == null) {
            note("no real player found to attack");
            sinceAttack = 0;
            return;
        }
        double distance = target.distanceTo(player);
        if (distance > attackReach) {
            note(String.format(java.util.Locale.ROOT,
                    "target %s is %.2f away, reach is %.2f", target.getGameProfile().name(),
                    distance, attackReach));
            sinceAttack = 0;
            return;
        }
        // The player's own attack damage source, so knockback comes from this entity and lands exactly
        // as it would from a real opponent — which is the entire point for a velocity test.
        boolean hurt = target.hurtServer((net.minecraft.server.level.ServerLevel) player.level(),
                player.damageSources().playerAttack(player), attackDamage);
        // "hit" only for a blow that landed: scenarios count these lines, and a swing the server refused
        // (a creative or invulnerable client, pvp off, still in its hurt frames) delivered no damage and no
        // knockback, so counting it would pass a knockback test on a run that had none.
        if (hurt) {
            report(String.format(java.util.Locale.ROOT, "hit %s for %.1f at %.2f blocks",
                    target.getGameProfile().name(), attackDamage, distance));
        } else {
            report(String.format(java.util.Locale.ROOT,
                    "refused: swung at %s at %.2f blocks, no damage (creative or invulnerable, pvp off, "
                            + "or still in its hurt frames)", target.getGameProfile().name(), distance));
        }
        sinceAttack = 0;
    }

    /**
     * An event a scenario counts — a hit, a refusal, a death. Never capped: a cap here once meant a long run
     * quietly stopped counting and read as the module going quiet.
     */
    private void report(String message) {
        System.out.println("[testkit] " + name + ": " + message);
    }

    /** A diagnostic nobody counts. Capped, because an out-of-range attacker says so every swing. */
    private void note(String message) {
        if (++notes <= 400) {
            System.out.println("[testkit] " + name + ": " + message);
        }
    }

    /** The closest real (non-fake) player, which is the client under test. */
    private ServerPlayer nearestRealPlayer() {
        ServerPlayer best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ServerPlayer candidate : player.level().getServer().getPlayerList().getPlayers()) {
            if (candidate == player || FakePlayerManager.isFake(candidate)) {
                continue;
            }
            double distance = candidate.distanceTo(player);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * A connection that accepts everything and sends nothing. The server writes the whole join sequence
     * down this before it will treat the player as present; there is simply no client to receive it.
     */
    private static final class SilentConnection extends Connection {

        /** The channel this sits on, so a send can report itself finished the way a real one does. */
        private EmbeddedChannel embedded;

        private SilentConnection() {
            super(PacketFlow.SERVERBOUND);
        }

        @Override
        public void send(Packet<?> packet) {
        }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener listener) {
            finished(listener);
        }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
            finished(listener);
        }

        /**
         * Tell whoever is waiting on a send that it went out. Sending nothing is the point of this connection,
         * but vanilla hangs work on a send finishing: {@code ServerCommonPacketListenerImpl.disconnect} closes
         * the connection only once the disconnect packet has gone (26.1.2 bytecode), and only a closed
         * connection runs {@code onDisconnect}, which is what takes the player out of the world. With the
         * listener dropped, a fake player told to leave never left — {@code testkit sweep}, {@code remove} and
         * every respawn left the old one standing, and a killed bot's dead body stayed on the server as a
         * "real player" for the others to target.
         */
        private void finished(ChannelFutureListener listener) {
            if (listener == null || embedded == null) {
                return;
            }
            try {
                listener.operationComplete(embedded.newSucceededFuture());
            } catch (Exception e) {
                System.err.println("[testkit] a send listener failed: " + e);
            }
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return ADDRESS;
        }
    }
}
