package dev.testkit.harness;

import net.minecraft.network.chat.Component;

/**
 * The reason the server last dropped the client, kept because it is the only place some of them are ever said.
 *
 * <p>{@link ChatWatch} catches what a server says while you are on it. A disconnect reason is not that: it
 * arrives as a disconnect packet, is drawn on a screen, and is never logged. A test that waits for a world and
 * times out would otherwise report "timed out" when the server said something specific and useful — <i>"You are
 * not whitelisted"</i>, <i>"Failed to verify username"</i>, <i>"Outdated client"</i>.
 */
public final class Disconnects {

    private static volatile String last;

    private Disconnects() {
    }

    /** Called from the client packet listener for every disconnect, in every connection phase. */
    public static void record(Component reason) {
        last = reason == null ? "(no reason given)" : reason.getString();
    }

    /** The last disconnect reason, or null if the connection has not been dropped since {@link #clear()}. */
    public static String last() {
        return last;
    }

    public static void clear() {
        last = null;
    }
}
