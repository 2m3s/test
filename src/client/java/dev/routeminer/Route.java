package dev.routeminer;

import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * An ordered loop of waypoints. Each waypoint is the block you stand on;
 * the macro etherwarps onto it, mines everything in reach, then moves on.
 */
public class Route {
	private static final Path DIR = Config.DIR.resolve("routes");

	public final List<BlockPos> points = new ArrayList<>();

	public int size() {
		return points.size();
	}

	public BlockPos get(int i) {
		return points.get(Math.floorMod(i, points.size()));
	}

	/** Index of the waypoint the player is standing on (within 1.5 blocks), or -1. */
	public int indexAt(BlockPos feetBlock) {
		int best = -1;
		double bestDist = 2.25;
		for (int i = 0; i < points.size(); i++) {
			double d = points.get(i).distSqr(feetBlock);
			if (d <= bestDist) {
				bestDist = d;
				best = i;
			}
		}
		return best;
	}

	public void save(String name) throws IOException {
		Files.createDirectories(DIR);
		List<int[]> raw = new ArrayList<>();
		for (BlockPos p : points) raw.add(new int[]{p.getX(), p.getY(), p.getZ()});
		try (Writer w = Files.newBufferedWriter(file(name))) {
			Config.GSON.toJson(raw, w);
		}
	}

	public boolean load(String name) throws IOException {
		Path f = file(name);
		if (!Files.exists(f)) return false;
		try (Reader r = Files.newBufferedReader(f)) {
			List<int[]> raw = Config.GSON.fromJson(r, new TypeToken<List<int[]>>() {}.getType());
			points.clear();
			if (raw != null) for (int[] p : raw) points.add(new BlockPos(p[0], p[1], p[2]));
		}
		return true;
	}

	public static List<String> savedNames() {
		if (!Files.isDirectory(DIR)) return List.of();
		try (Stream<Path> s = Files.list(DIR)) {
			return s.map(p -> p.getFileName().toString())
					.filter(n -> n.endsWith(".json"))
					.map(n -> n.substring(0, n.length() - 5))
					.sorted()
					.toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	private static Path file(String name) {
		return DIR.resolve(name.replaceAll("[^A-Za-z0-9_-]", "_") + ".json");
	}
}
