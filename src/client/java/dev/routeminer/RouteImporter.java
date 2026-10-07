package dev.routeminer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Parses shared waypoint routes. Accepts:
 * - ColeWeight-style JSON (what most Mining Cult routes use):
 *   [{"x":1,"y":2,"z":3,"r":0,"g":1,"b":0,"options":{"name":1}}, ...]
 * - Skytils exports ("<Skytils-Waypoint-Data>(V1):" + base64 gzip JSON, or the raw JSON)
 * - Any JSON containing objects with x/y/z, in order
 * - Plain text with one "x y z" (or "x, y, z") per line
 * Coordinates are the waypoint blocks themselves, i.e. where the cobblestone goes.
 */
public final class RouteImporter {
	private static final Pattern XYZ = Pattern.compile("(-?\\d+(?:\\.\\d+)?)[ ,]+(-?\\d+(?:\\.\\d+)?)[ ,]+(-?\\d+(?:\\.\\d+)?)");

	private record Point(BlockPos pos, OptionalDouble order) {}

	public static List<BlockPos> parse(String text) throws IOException {
		String s = text.trim();
		if (s.isEmpty()) throw new IOException("clipboard is empty");

		if (s.startsWith("<Skytils-Waypoint-Data>")) {
			s = decode(s.substring(s.indexOf(':') + 1).trim());
		} else if (!s.startsWith("[") && !s.startsWith("{") && s.matches("[A-Za-z0-9+/=\\s]+") && !XYZ.matcher(s).find()) {
			s = decode(s);
		}

		List<Point> points = new ArrayList<>();
		if (s.startsWith("[") || s.startsWith("{")) {
			try {
				collect(JsonParser.parseString(s), points);
			} catch (RuntimeException e) {
				throw new IOException("invalid JSON: " + e.getMessage());
			}
		} else {
			for (String line : s.split("\\R")) {
				Matcher m = XYZ.matcher(line);
				if (m.find()) points.add(new Point(floor(m.group(1), m.group(2), m.group(3)), OptionalDouble.empty()));
			}
		}
		if (points.isEmpty()) throw new IOException("no waypoints found");

		// ColeWeight routes number their points in options.name; respect that order when every point has one.
		if (points.stream().allMatch(p -> p.order().isPresent())) {
			points.sort(Comparator.comparingDouble(p -> p.order().getAsDouble()));
		}
		return points.stream().map(Point::pos).toList();
	}

	private static void collect(JsonElement e, List<Point> out) {
		if (e.isJsonArray()) {
			for (JsonElement c : e.getAsJsonArray()) collect(c, out);
		} else if (e.isJsonObject()) {
			JsonObject o = e.getAsJsonObject();
			if (isNum(o, "x") && isNum(o, "y") && isNum(o, "z")) {
				BlockPos pos = BlockPos.containing(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble());
				out.add(new Point(pos, order(o)));
				return;
			}
			for (var entry : o.entrySet()) collect(entry.getValue(), out);
		}
	}

	private static OptionalDouble order(JsonObject o) {
		JsonElement name = null;
		if (o.has("options") && o.get("options").isJsonObject()) name = o.getAsJsonObject("options").get("name");
		if (name == null) name = o.get("name");
		if (name == null || !name.isJsonPrimitive()) return OptionalDouble.empty();
		try {
			return OptionalDouble.of(Double.parseDouble(name.getAsString().trim()));
		} catch (NumberFormatException ex) {
			return OptionalDouble.empty();
		}
	}

	private static boolean isNum(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isNumber();
	}

	private static BlockPos floor(String x, String y, String z) {
		return BlockPos.containing(Double.parseDouble(x), Double.parseDouble(y), Double.parseDouble(z));
	}

	private static String decode(String b64) throws IOException {
		byte[] raw;
		try {
			raw = Base64.getDecoder().decode(b64.replaceAll("\\s", ""));
		} catch (IllegalArgumentException e) {
			throw new IOException("not valid base64");
		}
		if (raw.length > 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B) {
			try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
				raw = in.readAllBytes();
			}
		}
		return new String(raw, StandardCharsets.UTF_8).trim();
	}

	/** Exports in ColeWeight format so the route can be shared or loaded into other mods. */
	public static String export(List<BlockPos> points) {
		JsonArray arr = new JsonArray();
		for (int i = 0; i < points.size(); i++) {
			BlockPos p = points.get(i);
			JsonObject o = new JsonObject();
			o.addProperty("x", p.getX());
			o.addProperty("y", p.getY());
			o.addProperty("z", p.getZ());
			o.addProperty("r", 0);
			o.addProperty("g", 1);
			o.addProperty("b", 0);
			JsonObject opts = new JsonObject();
			opts.addProperty("name", i + 1);
			o.add("options", opts);
			arr.add(o);
		}
		return arr.toString();
	}
}
