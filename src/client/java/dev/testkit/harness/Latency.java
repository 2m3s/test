package dev.testkit.harness;

import dev.testkit.mixin.AccessorConnection;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/**
 * A round trip for a test server that has none of its own: every packet from the server reaches the client
 * {@code ms} later, in the order it was sent.
 *
 * <p>The test server runs on the same machine, so its round trip is well under a tick. The client handles
 * packets between frames, not only at tick boundaries, so anything the server answers arrives inside the tick
 * that asked — which is not what a player on a real connection sees. Anything whose behaviour depends on the gap
 * between an action and the server's answer to it (a prediction the server overrules, a correction, an
 * acknowledgement) needs this to be tested at all.
 *
 * <p><b>Inbound only, and the whole stream.</b> Delaying only chosen packets would build a client no network
 * produces — one that hears some answers late and everything else on time — and the anticheat's transactions,
 * which are how it knows what the client has seen, would then describe a different client from the one it is
 * judging. Delaying the whole inbound stream by {@code ms} adds {@code ms} to the round trip. The stage sits in
 * front of the connection's own handler ({@code packet_handler}), after decoding.
 *
 * <p><b>Strictly first in, first out, including on the way out.</b> Scheduling each packet on its own, and taking
 * the stage out of the pipeline the moment {@link #remove} is called, lets packets arriving after removal
 * overtake the ones still held; the client then answers a newer transaction before older ones and the anticheat
 * flags {@code TransactionOrder} — a harness flag that reads as the module's. So packets wait in one queue,
 * released in order as they come due, and {@link #remove} only stops adding delay: the stage takes itself out
 * once the queue has drained behind it.
 *
 * <pre>{@code
 * ctx.runOnClient(mc -> Latency.install(100));   // 100 ms more round trip
 * ...
 * ctx.runOnClient(mc -> Latency.remove());       // drains, then leaves
 * }</pre>
 *
 * <p>Call both on the client thread. The stage goes away with the connection, so a scenario that ends without
 * removing it leaves nothing behind for the next one.
 */
public final class Latency {

    private static final String NAME = "testkit_latency";

    /** The connection's own handler, the last stage of the client's inbound pipeline (26.1.2 bytecode). */
    private static final String BEFORE = "packet_handler";

    private Latency() {
    }

    /** Delay everything the server sends by {@code ms}. Calling it again changes the delay. */
    public static void install(int ms) {
        Channel channel = channel();
        if (channel == null) {
            throw new IllegalStateException("no connection to add latency to");
        }
        long delay = TimeUnit.MILLISECONDS.toNanos(Math.max(0, ms));
        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(NAME) instanceof Delay existing) {
                existing.delayNanos = delay;
                existing.closing = false;
                return;
            }
            channel.pipeline().addBefore(BEFORE, NAME, new Delay(delay));
        });
    }

    /**
     * Back to the server's own round trip. Packets already held are still delivered, in order, and anything
     * arriving meanwhile queues behind them; the stage leaves the pipeline once it is empty.
     */
    public static void remove() {
        Channel channel = channel();
        if (channel == null) {
            return;
        }
        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(NAME) instanceof Delay delay) {
                delay.delayNanos = 0L;
                delay.closing = true;
                delay.leaveIfEmpty(channel.pipeline().context(NAME));
            }
        });
    }

    private static Channel channel() {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        return listener == null ? null : ((AccessorConnection) listener.getConnection()).testkit$channel();
    }

    /** Everything below runs on the channel's event loop, so none of it needs a lock. */
    private static final class Delay extends ChannelInboundHandlerAdapter {
        private record Held(Object msg, long due) {
        }

        private final ArrayDeque<Held> held = new ArrayDeque<>();

        private long delayNanos;

        private boolean closing;

        private boolean scheduled;

        Delay(long delayNanos) {
            this.delayNanos = delayNanos;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            held.addLast(new Held(msg, System.nanoTime() + delayNanos));
            schedule(ctx);
        }

        private void schedule(ChannelHandlerContext ctx) {
            if (scheduled || held.isEmpty()) {
                return;
            }
            scheduled = true;
            long wait = Math.max(0L, held.peekFirst().due() - System.nanoTime());
            ctx.executor().schedule(() -> release(ctx), wait, TimeUnit.NANOSECONDS);
        }

        private void release(ChannelHandlerContext ctx) {
            scheduled = false;
            long now = System.nanoTime();
            while (!held.isEmpty() && held.peekFirst().due() <= now) {
                ctx.fireChannelRead(held.pollFirst().msg());
            }
            schedule(ctx);
            leaveIfEmpty(ctx);
        }

        void leaveIfEmpty(ChannelHandlerContext ctx) {
            if (closing && held.isEmpty() && ctx != null && !ctx.isRemoved()) {
                ctx.pipeline().remove(this);
            }
        }
    }
}
