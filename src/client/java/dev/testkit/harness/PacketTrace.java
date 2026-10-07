package dev.testkit.harness;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the client put on the wire, and what came back, one line per client tick.
 *
 * <p>When a check flags, the verbose line names the check; it does not show what the client sent to earn it.
 * Most packet-level checks — packet order, bad packets, timer, multi-actions — are about what happens
 * <i>inside one tick</i>, so the useful record is not a total but the sequence, bucketed by tick:
 *
 * <pre>
 * t  41 &gt; move_player_pos_rot client_tick_end                  &lt; set_time
 * t  42 &gt; use_item_on swing move_player_pos client_tick_end    &lt; block_update block_changed_ack
 * </pre>
 *
 * <p>A tick is the span from one start of {@code Minecraft.tick} to the next, which is where vanilla sends
 * {@code client_tick_end} (26.1.2 bytecode), so an ordinary tick ends with exactly one of them. Outbound packets
 * are recorded as the client hands them to the connection, on the thread that sent them; inbound as the
 * connection's handler receives them, on the network thread, so an inbound entry's tick is only as exact as
 * when it happened to arrive. Names are the protocol ids, with the {@code minecraft:} namespace dropped.
 *
 * <pre>{@code
 * ctx.runOnClient(mc -> PacketTrace.start());
 * ...
 * ctx.runOnClient(mc -> PacketTrace.stop());
 * PacketTrace.lines().forEach(scenario::log);
 * int most = PacketTrace.maxPerTick("move_player_pos_rot");
 * int setbacks = PacketTrace.received("player_position");
 * }</pre>
 *
 * <p>Costs one volatile read per packet while stopped. Recording is capped at {@link #MAX_TICKS} ticks, so one
 * left running does not grow without bound.
 */
public final class PacketTrace {

    /** Two minutes of ticks. */
    public static final int MAX_TICKS = 2400;

    private static final List<Tick> ticks = new ArrayList<>();

    private static volatile boolean recording;

    private static boolean hooked;

    private PacketTrace() {
    }

    private record Tick(List<String> out, List<String> in) {
        Tick() {
            this(new ArrayList<>(), new ArrayList<>());
        }
    }

    /** Clear the trace and start recording. Call on the client thread. */
    public static void start() {
        if (!hooked) {
            ClientTickEvents.START_CLIENT_TICK.register(client -> newTick());
            hooked = true;
        }
        synchronized (ticks) {
            ticks.clear();
            ticks.add(new Tick());
        }
        recording = true;
    }

    /** Stop recording. What was recorded stays readable until the next {@link #start}. */
    public static void stop() {
        recording = false;
    }

    private static void newTick() {
        if (!recording) {
            return;
        }
        synchronized (ticks) {
            if (ticks.size() >= MAX_TICKS) {
                recording = false;
                return;
            }
            ticks.add(new Tick());
        }
    }

    /** From the connection, for every packet it is asked to send. */
    public static void onSent(Packet<?> packet) {
        // Only what the client sends. An integrated server in the same JVM has connections too, and what they
        // send is clientbound.
        if (recording && packet.type().flow() == PacketFlow.SERVERBOUND) {
            String name = name(packet);
            synchronized (ticks) {
                if (!ticks.isEmpty()) {
                    ticks.getLast().out().add(name);
                }
            }
        }
    }

    /** From the connection, for every packet its handler receives. */
    public static void onReceived(Packet<?> packet) {
        if (recording && packet.type().flow() == PacketFlow.CLIENTBOUND) {
            String name = name(packet);
            synchronized (ticks) {
                if (!ticks.isEmpty()) {
                    ticks.getLast().in().add(name);
                }
            }
        }
    }

    private static String name(Packet<?> packet) {
        Identifier id = packet.type().id();
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }

    /** One line per recorded tick, oldest first: {@code t  N > sent… < received…}. */
    public static List<String> lines() {
        List<String> out = new ArrayList<>();
        synchronized (ticks) {
            for (int i = 0; i < ticks.size(); i++) {
                Tick tick = ticks.get(i);
                StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "t%4d >", i));
                for (String name : tick.out()) {
                    line.append(' ').append(name);
                }
                if (!tick.in().isEmpty()) {
                    line.append("   <");
                    for (String name : tick.in()) {
                        line.append(' ').append(name);
                    }
                }
                out.add(line.toString());
            }
        }
        return out;
    }

    /** How many ticks were recorded, including the one in progress. */
    public static int tickCount() {
        synchronized (ticks) {
            return ticks.size();
        }
    }

    /** How many times the client sent {@code id} (e.g. {@code "swing"}) across the whole trace. */
    public static int sent(String id) {
        int count = 0;
        synchronized (ticks) {
            for (Tick tick : ticks) {
                count += (int) tick.out().stream().filter(id::equals).count();
            }
        }
        return count;
    }

    /**
     * How many times the server sent {@code id}. {@code received("player_position")} counts the server moving
     * the client — a teleport, or an anticheat setback.
     */
    public static int received(String id) {
        int count = 0;
        synchronized (ticks) {
            for (Tick tick : ticks) {
                count += (int) tick.in().stream().filter(id::equals).count();
            }
        }
        return count;
    }

    /** The most times the client sent {@code id} inside one tick. */
    public static int maxPerTick(String id) {
        int most = 0;
        synchronized (ticks) {
            for (Tick tick : ticks) {
                most = Math.max(most, (int) tick.out().stream().filter(id::equals).count());
            }
        }
        return most;
    }

    /** What the client sent on tick {@code index} (0 is the part-tick before the first tick started). */
    public static List<String> sentOn(int index) {
        synchronized (ticks) {
            return index >= 0 && index < ticks.size() ? List.copyOf(ticks.get(index).out()) : List.of();
        }
    }
}
