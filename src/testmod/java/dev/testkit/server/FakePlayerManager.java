package dev.testkit.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;

/** Registry of the fake players currently in the world, ticked once per server tick. */
public final class FakePlayerManager {

    private static final Map<String, FakePlayer> PLAYERS = new LinkedHashMap<>();

    private FakePlayerManager() {
    }

    public static void add(FakePlayer player) {
        FakePlayer previous = PLAYERS.put(player.name(), player);
        if (previous != null) {
            previous.remove();
        }
    }

    public static FakePlayer get(String name) {
        return PLAYERS.get(name);
    }

    public static void remove(String name) {
        FakePlayer player = PLAYERS.remove(name);
        if (player != null) {
            player.remove();
        }
    }

    public static void clear() {
        for (FakePlayer player : PLAYERS.values()) {
            player.remove();
        }
        PLAYERS.clear();
    }

    /** Whether this server player is one of ours, so behaviour never targets another fake. */
    public static boolean isFake(ServerPlayer candidate) {
        for (FakePlayer player : PLAYERS.values()) {
            if (player.entity() == candidate) {
                return true;
            }
        }
        return false;
    }

    public static void tick(MinecraftServer server) {
        for (FakePlayer player : PLAYERS.values()) {
            try {
                player.tick();
            } catch (Exception e) {
                // A misbehaving test opponent must not take the server down mid-scenario; the scenario's
                // own assertions will notice that it stopped doing its job.
                System.err.println("[testkit] " + player.name() + " tick failed: " + e);
            }
        }
    }
}
