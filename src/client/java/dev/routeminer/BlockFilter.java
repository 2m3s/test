package dev.routeminer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Which blocks count as minable, plus presets for SkyBlock mining areas. */
public final class BlockFilter {
	public static final Map<String, List<String>> PRESETS = new LinkedHashMap<>();

	static {
		// Gemstones are stained glass + panes in the matching colour.
		gem("ruby", "red");
		gem("amber", "orange");
		gem("sapphire", "light_blue");
		gem("jade", "lime");
		gem("amethyst", "purple");
		gem("topaz", "yellow");
		gem("jasper", "magenta");
		gem("opal", "white");
		gem("aquamarine", "blue");
		gem("citrine", "brown");
		gem("peridot", "green");
		gem("onyx", "black");
		List<String> all = new ArrayList<>();
		for (String g : List.of("ruby", "amber", "sapphire", "jade", "amethyst", "topaz", "jasper", "opal",
				"aquamarine", "citrine", "peridot", "onyx")) {
			all.addAll(PRESETS.get(g));
		}
		PRESETS.put("gemstone", all);

		PRESETS.put("glacite", List.of("minecraft:packed_ice"));
		PRESETS.put("umber", List.of("minecraft:terracotta", "minecraft:brown_terracotta", "minecraft:smooth_red_sandstone"));
		PRESETS.put("tungsten", List.of("minecraft:clay", "minecraft:cobblestone"));
		PRESETS.put("mithril", List.of("minecraft:gray_wool", "minecraft:cyan_terracotta", "minecraft:prismarine",
				"minecraft:prismarine_bricks", "minecraft:dark_prismarine", "minecraft:light_blue_wool"));
		PRESETS.put("titanium", List.of("minecraft:polished_diorite"));
		PRESETS.put("hardstone", List.of("minecraft:stone"));
	}

	private static void gem(String name, String colour) {
		PRESETS.put(name, List.of("minecraft:" + colour + "_stained_glass", "minecraft:" + colour + "_stained_glass_pane"));
	}

	public static List<String> preset(String name) {
		return PRESETS.getOrDefault(name, List.of());
	}

	private final Set<Block> blocks = new HashSet<>();

	public void rebuild(List<String> ids) {
		blocks.clear();
		for (String id : ids) {
			Block b = resolve(id);
			if (b != null) blocks.add(b);
		}
	}

	public boolean matches(BlockState state) {
		return !state.isAir() && blocks.contains(state.getBlock());
	}

	public boolean isEmpty() {
		return blocks.isEmpty();
	}

	/** Returns the block for an id like "packed_ice" or "minecraft:packed_ice", or null if unknown. */
	public static Block resolve(String id) {
		Identifier ident = Identifier.tryParse(id);
		if (ident == null || !BuiltInRegistries.BLOCK.containsKey(ident)) return null;
		return BuiltInRegistries.BLOCK.getValue(ident);
	}

	public static String normalize(String id) {
		Identifier ident = Identifier.tryParse(id);
		return ident == null ? id : ident.toString();
	}
}
