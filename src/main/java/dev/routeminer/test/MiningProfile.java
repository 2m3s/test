package dev.routeminer.test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player Heart of the Mountain levels, powder and pickaxe ability state for the test world.
 * Read from both the client and server thread (same JVM in singleplayer), hence the concurrent map.
 */
public class MiningProfile {
	public enum Powder { MITHRIL("§2Mithril", "Mithril"), GEMSTONE("§dGemstone", "Gemstone");
		public final String label, plain;
		Powder(String label, String plain) { this.label = label; this.plain = plain; }
	}

	/** HOTM perks that matter for gem mining, with wiki cost and effect formulas. */
	public enum Perk {
		MINING_SPEED("Mining Speed", Powder.MITHRIL, 50, 3.0, "§a+%d Mining Speed", 20),
		MINING_FORTUNE("Mining Fortune", Powder.MITHRIL, 50, 3.05, "§a+%d Mining Fortune", 2),
		SPEEDY_MINEMAN("Speedy Mineman", Powder.GEMSTONE, 50, 3.2, "§a+%d Mining Speed", 40),
		FORTUNATE_MINEMAN("Fortunate Mineman", Powder.GEMSTONE, 50, 3.2, "§a+%d Mining Fortune", 3),
		POWDER_BUFF("Powder Buff", Powder.GEMSTONE, 50, 3.2, "§a+%d%% Powder", 1),
		MINING_SPEED_BOOST("Mining Speed Boost", Powder.MITHRIL, 3, 0, "", 0);

		public final String name;
		public final Powder powder;
		public final int max;
		final double exponent;
		final String effectFormat;
		final int perLevel;

		Perk(String name, Powder powder, int max, double exponent, String effectFormat, int perLevel) {
			this.name = name;
			this.powder = powder;
			this.max = max;
			this.exponent = exponent;
			this.effectFormat = effectFormat;
			this.perLevel = perLevel;
		}

		/** Powder to go from level-1 to level: floor((level + 1) ^ exponent). The ability costs a flat amount. */
		public long cost(int level) {
			if (this == MINING_SPEED_BOOST) return 50_000L * level;
			return (long) Math.floor(Math.pow(level + 1, exponent));
		}

		public String effect(int level) {
			if (this == MINING_SPEED_BOOST) {
				return level == 0 ? "§7Locked" : "§a+" + boostPercentAt(level) + "% Mining Speed for " + boostSecondsAt(level) + "s §8(120s cooldown)";
			}
			return String.format(effectFormat, level * perLevel);
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<UUID, MiningProfile> PROFILES = new ConcurrentHashMap<>();
	public static final int BOOST_COOLDOWN_TICKS = 120 * 20;

	public final EnumMap<Perk, Integer> levels = new EnumMap<>(Perk.class);
	public final EnumMap<Powder, Long> powder = new EnumMap<>(Powder.class);
	/** Perks switched off with right-click in /hotm. */
	public final java.util.EnumSet<Perk> disabled = java.util.EnumSet.noneOf(Perk.class);
	/** Extra mining speed from things not modelled (pets, other gear), set with /miningspeed. */
	public int baseSpeed = 0;

	// Runtime only.
	transient volatile long boostUntil, boostReadyAt;
	transient volatile long gameTime;
	transient volatile boolean announcedReady = true;

	/** True once, when the cooldown has just finished (for the "is now available" message). */
	public boolean consumeReady(long now) {
		if (announcedReady || boostReadyAt == 0 || now < boostReadyAt) return false;
		announcedReady = true;
		return true;
	}

	public static MiningProfile of(UUID id) {
		return PROFILES.computeIfAbsent(id, k -> new MiningProfile());
	}

	public int level(Perk p) {
		return levels.getOrDefault(p, 0);
	}

	public boolean enabled(Perk p) {
		return !disabled.contains(p);
	}

	/** Flips a perk on/off. Returns true if it is now enabled. */
	public boolean toggle(Perk p) {
		if (!disabled.remove(p)) disabled.add(p);
		return enabled(p);
	}

	/** Level that actually applies (0 when the perk is disabled). */
	private int active(Perk p) {
		return enabled(p) ? level(p) : 0;
	}

	public long powder(Powder p) {
		return powder.getOrDefault(p, 0L);
	}

	public void addPowder(Powder p, long amount) {
		powder.put(p, Math.max(0, powder(p) + amount));
	}

	/** Tries to buy one level. Returns an error message or null on success. */
	public String upgrade(Perk p) {
		int lvl = level(p);
		if (lvl >= p.max) return "§c" + p.name + " is already maxed.";
		long cost = p.cost(lvl + 1);
		if (powder(p.powder) < cost) return "§cNeed " + cost + " " + p.powder.label + " Powder§c (have " + powder(p.powder) + ").";
		addPowder(p.powder, -cost);
		levels.put(p, lvl + 1);
		return null;
	}

	public void maxAll() {
		for (Perk p : Perk.values()) levels.put(p, p.max);
	}

	/** Resets every perk and refunds the powder spent on it. */
	public void resetAll() {
		for (Perk p : Perk.values()) {
			for (int l = 1; l <= level(p); l++) addPowder(p.powder, p.cost(l));
			levels.put(p, 0);
		}
		disabled.clear();
	}

	public int hotmMiningSpeed() {
		return active(Perk.MINING_SPEED) * Perk.MINING_SPEED.perLevel + active(Perk.SPEEDY_MINEMAN) * Perk.SPEEDY_MINEMAN.perLevel;
	}

	public int powderBuffPercent() {
		return active(Perk.POWDER_BUFF);
	}

	// ---------------------------------------------------------------- Mining Speed Boost

	static int boostPercentAt(int level) { return switch (level) { case 1 -> 200; case 2 -> 250; default -> 300; }; }
	static int boostSecondsAt(int level) { return switch (level) { case 1 -> 10; case 2 -> 15; default -> 20; }; }

	public int boostPercent() {
		return boostPercentAt(level(Perk.MINING_SPEED_BOOST));
	}

	public boolean boostActive() {
		return gameTime < boostUntil;
	}

	/** Activates the ability at the given game time. Returns a message for the player. */
	public String activateBoost(long now) {
		gameTime = now;
		int lvl = level(Perk.MINING_SPEED_BOOST);
		if (lvl == 0) return "§cUnlock Mining Speed Boost in /hotm first.";
		if (now < boostReadyAt) return "§cThis ability is on cooldown for " + ((boostReadyAt - now) / 20 + 1) + "s.";
		boostUntil = now + boostSecondsAt(lvl) * 20L;
		boostReadyAt = now + BOOST_COOLDOWN_TICKS;
		announcedReady = false;
		return "§aYou used your §6Mining Speed Boost §aPickaxe Ability!";
	}

	public static void tickAll(long now) {
		for (MiningProfile p : PROFILES.values()) p.gameTime = now;
	}

	// ---------------------------------------------------------------- persistence (world folder)

	public static void load(Path file) {
		PROFILES.clear();
		if (!Files.isRegularFile(file)) return;
		try (Reader r = Files.newBufferedReader(file)) {
			Map<UUID, MiningProfile> m = GSON.fromJson(r, new TypeToken<Map<UUID, MiningProfile>>() {}.getType());
			if (m != null) PROFILES.putAll(m);
		} catch (Exception e) {
			org.slf4j.LoggerFactory.getLogger("routeminer").warn("Couldn't read mining profiles", e);
		}
	}

	public static void save(Path file) {
		try (Writer w = Files.newBufferedWriter(file)) {
			GSON.toJson(PROFILES, w);
		} catch (IOException e) {
			org.slf4j.LoggerFactory.getLogger("routeminer").warn("Couldn't save mining profiles", e);
		}
	}

	public static void clear() {
		PROFILES.clear();
	}
}
