package dev.routeminer.test;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SkyBlock mining numbers for the test world, taken from the community wiki
 * (hypixelskyblock.minecraft.wiki): block strength, breaking power, drills, Divan's armor,
 * gemstone stats and the break-time formula.
 */
public final class MiningData {
	public record MiningBlock(String name, int strength, int breakingPower, boolean gemstone, boolean special) {}

	public record Drill(String id, String name, String rarity, int rarityIndex, int speed, int gemSpeed, int breakingPower, int fortune) {}

	// ---------------------------------------------------------------- blocks

	private static final Map<String, MiningBlock> BLOCKS = new HashMap<>();

	static {
		gem("red", "Ruby", 2300, 6);
		gem("orange", "Amber", 3000, 7);
		gem("purple", "Amethyst", 3000, 7);
		gem("lime", "Jade", 3000, 7);
		gem("light_blue", "Sapphire", 3000, 8);
		gem("yellow", "Topaz", 3800, 8);
		gem("magenta", "Jasper", 4800, 9);
		gem("white", "Opal", 3000, 9);
		gem("blue", "Aquamarine", 5200, 9);
		gem("brown", "Citrine", 5200, 9);
		gem("green", "Peridot", 5200, 9);
		gem("black", "Onyx", 5200, 9);

		block("stone", "Hard Stone", 50, 4, false);
		block("gray_wool", "Mithril", 500, 4, true);
		block("cyan_terracotta", "Mithril", 500, 4, true);
		block("prismarine", "Mithril", 800, 4, true);
		block("prismarine_bricks", "Mithril", 800, 4, true);
		block("dark_prismarine", "Mithril", 800, 4, true);
		block("light_blue_wool", "Mithril", 1500, 4, true);
		block("polished_diorite", "Titanium", 2000, 5, true);
		block("packed_ice", "Glacite", 6000, 9, true);
		block("terracotta", "Umber", 5600, 9, true);
		block("brown_terracotta", "Umber", 5600, 9, true);
		block("smooth_red_sandstone", "Umber", 5600, 9, true);
		block("clay", "Tungsten", 5600, 9, true);
	}

	private static void gem(String colour, String name, int strength, int bp) {
		MiningBlock b = new MiningBlock(name, strength, bp, true, true);
		BLOCKS.put(colour + "_stained_glass", b);
		BLOCKS.put(colour + "_stained_glass_pane", b);
	}

	private static void block(String id, String name, int strength, int bp, boolean special) {
		BLOCKS.put(id, new MiningBlock(name, strength, bp, false, special));
	}

	public static MiningBlock block(BlockState st) {
		return BLOCKS.get(BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath());
	}

	/** Gem blocks in the order shown by /stats. */
	public static List<MiningBlock> gemTable() {
		Map<String, MiningBlock> seen = new LinkedHashMap<>();
		for (String c : List.of("red", "orange", "purple", "lime", "light_blue", "yellow", "magenta", "white", "blue", "brown", "green", "black")) {
			MiningBlock b = BLOCKS.get(c + "_stained_glass");
			seen.put(b.name(), b);
		}
		return new ArrayList<>(seen.values());
	}

	/**
	 * Ticks to break: ceil(strength * 30 / speed), never below the 4-tick softcap,
	 * except instamine (1 tick) once speed exceeds 60x (gemstones, ores, metals) or 30x strength.
	 */
	public static int breakTicks(int strength, double speed, boolean special) {
		if (speed <= 0) return Integer.MAX_VALUE;
		double instant = (special ? 60 : 30) * strength;
		if (speed > instant) return 1;
		return Math.max(4, (int) Math.ceil(strength * 30.0 / speed));
	}

	// ---------------------------------------------------------------- gear

	public static final String[] RARITIES = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE"};
	private static final String[] RARITY_COLOURS = {"§f", "§a", "§9", "§5", "§6", "§d", "§b"};
	/** Perfect Amber mining speed by item rarity. */
	private static final int[] PERFECT_AMBER = {20, 28, 40, 60, 80, 100, 120};
	/** Perfect Jade mining fortune by item rarity. */
	private static final int[] PERFECT_JADE = {10, 14, 20, 30, 40, 50, 60};

	// Max parts: Amber-Polished Drill Engine (+600 speed, +100 fortune), Starfall Seasoning (+25 speed, +10 fortune).
	public static final int ENGINE_SPEED = 600, ENGINE_FORTUNE = 100;
	public static final int MODULE_SPEED = 25, MODULE_FORTUNE = 10;
	/** Speed gems in a drill: its Amber slot plus the universal Mining slot, both Perfect Amber. */
	public static final int DRILL_AMBER_GEMS = 2;

	public static final List<Drill> DRILLS = List.of(
			new Drill("mithril_226", "Mithril Drill SX-R226", "RARE", 2, 450, 0, 5, 15),
			new Drill("mithril_326", "Mithril Drill SX-R326", "EPIC", 3, 600, 0, 6, 20),
			new Drill("ruby", "Ruby Drill TX-15", "RARE", 2, 150, 800, 7, 0),
			new Drill("gemstone", "Gemstone Drill LT-522", "EPIC", 3, 300, 800, 8, 0),
			new Drill("topaz", "Topaz Drill KGR-12", "LEGENDARY", 4, 450, 800, 9, 0),
			new Drill("jasper", "Jasper Drill X", "LEGENDARY", 4, 600, 800, 9, 0),
			new Drill("titanium_355", "Titanium Drill DR-X355", "RARE", 2, 700, 0, 7, 25),
			new Drill("titanium_455", "Titanium Drill DR-X455", "EPIC", 3, 900, 0, 8, 40),
			new Drill("titanium_555", "Titanium Drill DR-X555", "LEGENDARY", 4, 1200, 0, 9, 70),
			new Drill("titanium_655", "Titanium Drill DR-X655", "MYTHIC", 5, 1600, 0, 9, 120),
			new Drill("divan", "Divan's Drill", "MYTHIC", 5, 1800, 0, 10, 150));

	public static Drill drill(String id) {
		for (Drill d : DRILLS) if (d.id().equals(id)) return d;
		return null;
	}

	public static int drillSpeed(Drill d, boolean onGem) {
		return d.speed() + (onGem ? d.gemSpeed() : 0) + ENGINE_SPEED + MODULE_SPEED + DRILL_AMBER_GEMS * PERFECT_AMBER[d.rarityIndex()];
	}

	// Armor of Divan: Legendary, +80 speed / +30 fortune each; slots 2x Amber, 2x Jade, 1x Topaz (all Perfect).
	public static final int DIVAN_PIECE_SPEED = 80, DIVAN_PIECE_FORTUNE = 30;
	public static final int DIVAN_PIECE_GEM_SPEED = 2 * PERFECT_AMBER[4];
	public static final int DIVAN_PIECE_GEM_FORTUNE = 2 * PERFECT_JADE[4];

	// ---------------------------------------------------------------- items

	public static ItemStack drillItem(Drill d) {
		int amber = PERFECT_AMBER[d.rarityIndex()];
		List<String> lore = new ArrayList<>();
		lore.add("§7Breaking Power " + d.breakingPower());
		lore.add("");
		lore.add("§7Mining Speed: §a+" + d.speed() + (d.gemSpeed() > 0 ? " §8(+" + d.gemSpeed() + " on Gemstones)" : ""));
		if (d.fortune() > 0) lore.add("§7Mining Fortune: §a+" + d.fortune());
		lore.add("");
		lore.add("§7Drill Engine: §6Amber-Polished §8(+" + ENGINE_SPEED + " speed)");
		lore.add("§7Upgrade Module: §dStarfall Seasoning §8(+" + MODULE_SPEED + " speed)");
		lore.add("§7Fuel Tank: §aPerfectly-Cut Fuel Tank");
		lore.add("§7Gems: §6" + DRILL_AMBER_GEMS + "x Perfect Amber §8(+" + amber + " each)");
		lore.add("");
		lore.add("§7Total on gemstones: §6" + drillSpeed(d, true) + " §7Mining Speed");
		lore.add("§7Right-click: §eMining Speed Boost");
		lore.add("");
		lore.add(RARITY_COLOURS[d.rarityIndex()] + "§l" + d.rarity() + " DRILL");
		CompoundTag tag = new CompoundTag();
		tag.putString("routeminer_tool", "drill");
		tag.putString("drill", d.id());
		return named(Items.PRISMARINE_SHARD, RARITY_COLOURS[d.rarityIndex()] + d.name(), lore, tag);
	}

	public static ItemStack divanPiece(EquipmentSlot slot) {
		Item item = switch (slot) {
			case HEAD -> Items.GOLDEN_HELMET;
			case CHEST -> Items.GOLDEN_CHESTPLATE;
			case LEGS -> Items.GOLDEN_LEGGINGS;
			default -> Items.GOLDEN_BOOTS;
		};
		String name = switch (slot) {
			case HEAD -> "Helmet of Divan";
			case CHEST -> "Chestplate of Divan";
			case LEGS -> "Leggings of Divan";
			default -> "Boots of Divan";
		};
		List<String> lore = List.of(
				"§7Mining Speed: §a+" + DIVAN_PIECE_SPEED + " §6(+" + DIVAN_PIECE_GEM_SPEED + ")",
				"§7Mining Fortune: §a+" + DIVAN_PIECE_FORTUNE + " §6(+" + DIVAN_PIECE_GEM_FORTUNE + ")",
				"",
				"§7Gems: §62x Perfect Amber§7, §a2x Perfect Jade§7, §e1x Perfect Topaz",
				"",
				"§6§lLEGENDARY " + (slot == EquipmentSlot.HEAD ? "HELMET" : slot == EquipmentSlot.CHEST ? "CHESTPLATE" : slot == EquipmentSlot.LEGS ? "LEGGINGS" : "BOOTS"));
		CompoundTag tag = new CompoundTag();
		tag.putString("routeminer_armor", "divan");
		return named(item, "§6" + name, lore, tag);
	}

	/** SkyBlock-style lore: no italics. */
	public static ItemLore lore(List<String> lines) {
		return new ItemLore(lines.stream().<Component>map(l -> Component.literal(l).withStyle(st -> st.withItalic(false))).toList());
	}

	private static ItemStack named(Item item, String name, List<String> lore, CompoundTag tag) {
		ItemStack s = new ItemStack(item, 1);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		s.set(DataComponents.ITEM_NAME, Component.literal(name));
		s.set(DataComponents.LORE, lore(lore));
		s.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
		return s;
	}

	private static String tagString(ItemStack s, String key) {
		CustomData data = s.get(DataComponents.CUSTOM_DATA);
		return data == null ? "" : data.copyTag().getStringOr(key, "");
	}

	public static Drill heldDrill(Player p) {
		ItemStack s = p.getMainHandItem();
		return "drill".equals(tagString(s, "routeminer_tool")) ? drill(tagString(s, "drill")) : null;
	}

	public static int divanPieces(Player p) {
		int n = 0;
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			if ("divan".equals(tagString(p.getItemBySlot(slot), "routeminer_armor"))) n++;
		}
		return n;
	}

	// ---------------------------------------------------------------- totals

	public record Speed(int drill, int armor, int hotm, int base, double boostMultiplier, double total) {}

	/** Current mining speed for a block, from held drill, Divan's armor, HOTM perks and the speed boost. */
	public static Speed speed(Player p, MiningBlock block) {
		MiningProfile prof = MiningProfile.of(p.getUUID());
		Drill d = heldDrill(p);
		int drill = d == null ? 0 : drillSpeed(d, block != null && block.gemstone());
		int armor = divanPieces(p) * (DIVAN_PIECE_SPEED + DIVAN_PIECE_GEM_SPEED);
		int hotm = prof.hotmMiningSpeed();
		int base = prof.baseSpeed;
		double mult = prof.boostActive() ? 1 + prof.boostPercent() / 100.0 : 1;
		return new Speed(drill, armor, hotm, base, mult, (drill + armor + hotm + base) * mult);
	}

	/** Destroy progress per tick for the test world, or -1 to fall back to vanilla. */
	public static float destroyProgress(Player p, BlockState st) {
		MiningBlock b = block(st);
		if (b == null) return -1;
		Drill d = heldDrill(p);
		int bp = d == null ? 0 : d.breakingPower();
		if (bp < b.breakingPower()) return 0f; // can't break it at all
		int ticks = breakTicks(b.strength(), speed(p, b).total(), b.special());
		return ticks == 1 ? 1f : 1f / ticks;
	}
}
