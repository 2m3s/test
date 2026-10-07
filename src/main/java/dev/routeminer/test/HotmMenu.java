package dev.routeminer.test;

import dev.routeminer.test.MiningProfile.Perk;
import dev.routeminer.test.MiningProfile.Powder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * /hotm, laid out like SkyBlock's Heart of the Mountain menu. Slot positions, icons, names and lore
 * come from the community wiki's copy of the menu (resources/routeminer/hotm_ui.txt). Perks that
 * affect gem mining are live (levels, powder costs, enable/disable); the rest are display only.
 * Two pages like the real menu: Tiers 1-5 and Tiers 6-10.
 */
public class HotmMenu extends ChestMenu {
	/** One slot of the wiki template: row 1-11, column 1-9. */
	private record Entry(String item, int count, String name, List<String> lore) {}

	private static final Map<Long, Entry> TEMPLATE = loadTemplate();
	private static final Map<String, Perk> LIVE = Map.of(
			"Mining Speed", Perk.MINING_SPEED, "Mining Fortune", Perk.MINING_FORTUNE,
			"Speedy Mineman", Perk.SPEEDY_MINEMAN, "Fortunate Mineman", Perk.FORTUNATE_MINEMAN,
			"Powder Buff", Perk.POWDER_BUFF, "Mining Speed Boost", Perk.MINING_SPEED_BOOST);

	private static final int CLOSE = 45, MAX_ALL = 46, GO_BACK = 48, HEART = 49, RESET = 52, SCROLL = 53;
	private static final NumberFormat NUM = NumberFormat.getIntegerInstance(Locale.US);

	private final SimpleContainer box;
	private final ServerPlayer player;
	private int page; // 0 = tiers 1-5, 1 = tiers 6-10
	private final Map<Integer, Perk> livePerkSlots = new HashMap<>();

	private HotmMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer player) {
		super(MenuType.GENERIC_9x6, id, inv, box, 6);
		this.box = box;
		this.player = player;
		refresh();
	}

	public static void open(ServerPlayer p) {
		SimpleContainer box = new SimpleContainer(54);
		p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new HotmMenu(id, inv, box, p), Component.literal("Heart of the Mountain")));
	}

	// ---------------------------------------------------------------- clicks

	@Override
	public void clicked(int slot, int button, ContainerInput input, Player who) {
		// Every click is a button; nothing can be taken out of this menu.
		MiningProfile prof = MiningProfile.of(player.getUUID());
		Perk perk = livePerkSlots.get(slot);
		if (perk != null) {
			if (button == 1 && input == ContainerInput.PICKUP) {
				if (prof.level(perk) == 0) {
					player.sendSystemMessage(Component.literal("§cYou must unlock this perk first!"));
				} else {
					boolean on = prof.toggle(perk);
					player.sendSystemMessage(Component.literal(on ? "§aEnabled " + perk.name + "!" : "§cDisabled " + perk.name + "!"));
				}
			} else {
				int times = input == ContainerInput.QUICK_MOVE ? 10 : 1;
				String err = null;
				int bought = 0;
				for (int i = 0; i < times; i++) {
					err = prof.upgrade(perk);
					if (err != null) break;
					bought++;
				}
				if (bought > 0) {
					player.sendSystemMessage(Component.literal("§aYou have upgraded §e" + perk.name + "§a to level §e" + prof.level(perk) + "§a!"));
				} else if (err != null) {
					player.sendSystemMessage(Component.literal(err));
				}
			}
		} else if (slot == SCROLL) {
			page = 1 - page;
		} else if (slot == MAX_ALL) {
			prof.maxAll();
			player.sendSystemMessage(Component.literal("§aMaxed every simulated perk."));
		} else if (slot == RESET) {
			prof.resetAll();
			player.sendSystemMessage(Component.literal("§aReset your §5Heart of the Mountain§a! Your powder has been refunded."));
		} else if (slot == CLOSE || slot == GO_BACK) {
			player.closeContainer();
			return;
		}
		refresh();
	}

	@Override
	public ItemStack quickMoveStack(Player p, int slot) {
		return ItemStack.EMPTY;
	}

	// ---------------------------------------------------------------- drawing

	private void refresh() {
		MiningProfile prof = MiningProfile.of(player.getUUID());
		livePerkSlots.clear();
		ItemStack filler = item(Items.STAINED_GLASS_PANE.black(), 1, " ", List.of(), false);
		for (int i = 0; i < 54; i++) box.setItem(i, filler.copy());

		// Rows 1-5 of the chest show five tiers, highest at the top.
		int firstTemplateRow = page == 0 ? 6 : 1;
		for (int r = 0; r < 5; r++) {
			for (int c = 1; c <= 9; c++) {
				Entry e = TEMPLATE.get(key(firstTemplateRow + r, c));
				if (e == null) continue;
				int slot = r * 9 + (c - 1);
				Perk perk = LIVE.get(stripColour(e.name()));
				if (perk != null) {
					livePerkSlots.put(slot, perk);
					box.setItem(slot, perkItem(prof, perk));
				} else {
					box.setItem(slot, templateItem(e, c == 1 ? null : "§8(Display only in the test world)"));
				}
			}
		}

		// Bottom row.
		box.setItem(CLOSE, item(Items.BARRIER, 1, "§cClose", List.of(), false));
		box.setItem(MAX_ALL, item(Items.EMERALD_BLOCK, 1, "§aMax All Perks", List.of(
				"§7Test world only: instantly maxes", "§7every simulated perk.", "", "§eClick to max!"), true));
		Entry slotEntry = TEMPLATE.get(key(11, 3));
		if (slotEntry != null) box.setItem(47, templateItem(slotEntry, null));
		box.setItem(GO_BACK, item(Items.ARROW, 1, "§aGo Back", List.of("§7To Mining Skill"), false));
		box.setItem(HEART, heartItem(prof));
		Entry crystals = TEMPLATE.get(key(11, 6));
		if (crystals != null) box.setItem(50, templateItem(crystals, null));
		Entry rng = TEMPLATE.get(key(11, 7));
		if (rng != null) box.setItem(51, templateItem(rng, null));
		box.setItem(RESET, resetItem(prof));
		box.setItem(SCROLL, item(Items.ARROW, 1, page == 0 ? "§aScroll Up" : "§aScroll Down",
				List.of(page == 0 ? "§7View Tiers 6 - 10" : "§7View Tiers 1 - 5", "", "§eClick to scroll!"), false));
		broadcastChanges();
	}

	/** A simulated perk, drawn the way SkyBlock draws it. */
	private static ItemStack perkItem(MiningProfile prof, Perk p) {
		int lvl = prof.level(p);
		boolean maxed = lvl >= p.max;
		boolean enabled = prof.enabled(p);
		List<String> lore = new ArrayList<>();

		if (p == Perk.MINING_SPEED_BOOST) {
			int shown = Math.max(1, lvl);
			lore.add("§6Pickaxe Ability: Mining Speed Boost");
			lore.add("§7Grants §6+" + MiningProfile.boostPercentAt(shown) + "% §6Mining Speed §7for");
			lore.add("§a" + MiningProfile.boostSecondsAt(shown) + "s§7.");
			lore.add("§8Cooldown: §a120s");
			lore.add("");
			lore.add("§8Pickaxe Abilities apply to all of your");
			lore.add("§8Pickaxes. Right-click a drill to use it.");
			lore.add("");
			if (lvl == 0) {
				lore.add("§7Cost");
				lore.add("§2" + NUM.format(p.cost(1)) + " Mithril Powder");
				lore.add("");
				lore.add("§eClick to unlock!");
				return item(Items.COAL_BLOCK, 1, "§cMining Speed Boost", lore, false);
			}
			lore.add("§7Level §e" + lvl + "§8/" + p.max);
			if (!maxed) {
				lore.add("§7Next: §6+" + MiningProfile.boostPercentAt(lvl + 1) + "% §7for §a" + MiningProfile.boostSecondsAt(lvl + 1) + "s");
				lore.add("§7Cost: §2" + NUM.format(p.cost(lvl + 1)) + " Mithril Powder");
			}
			lore.add("");
			lore.add("§a§lSELECTED");
			return item(Items.EMERALD_BLOCK, 1, "§aMining Speed Boost", lore, true);
		}

		lore.add(maxed ? "§7Level " + lvl : "§7Level " + Math.max(lvl, 1) + "§8/" + p.max);
		lore.add("");
		lore.add(describe(p, Math.max(lvl, 1)));
		lore.add("");
		if (lvl > 0 && !maxed) {
			lore.add("§a=====[ §a§lUPGRADE §a]=====");
			lore.add("§7Level " + (lvl + 1) + "§8/" + p.max);
			lore.add("");
			lore.add(describe(p, lvl + 1));
			lore.add("");
		}
		if (!maxed) {
			long cost = p.cost(lvl + 1);
			lore.add("§7Cost");
			lore.add(powderColour(p.powder) + NUM.format(cost) + " " + p.powder.plain + " Powder");
			lore.add("");
		}
		if (lvl > 0) {
			lore.add(enabled ? "§a§lENABLED" : "§c§lDISABLED");
			lore.add("");
			lore.add("§eRight-click to " + (enabled ? "§cdisable" : "§aenable") + "§e!");
		}
		if (!maxed) {
			lore.add(lvl == 0 ? "§eClick to unlock!" : "§eLeft-click to upgrade!");
			if (lvl > 0) lore.add("§eShift Left-click to upgrade 10 levels!");
		}

		Item icon = lvl == 0 ? Items.COAL : maxed ? Items.DIAMOND : Items.EMERALD;
		String colour = lvl == 0 ? "§c" : maxed ? "§a" : "§e";
		return item(icon, 1, colour + p.name, lore, false);
	}

	private static String describe(Perk p, int level) {
		return switch (p) {
			case MINING_SPEED -> "§7Grants §6+" + NUM.format(level * 20L) + " Mining Speed§7.";
			case MINING_FORTUNE -> "§7Grants §6+" + NUM.format(level * 2L) + " Mining Fortune§7.";
			case SPEEDY_MINEMAN -> "§7Grants §6+" + NUM.format(level * 40L) + " Mining Speed§7.";
			case FORTUNATE_MINEMAN -> "§7Grants §6+" + NUM.format(level * 3L) + " Mining Fortune§7.";
			case POWDER_BUFF -> "§7Gain §a+" + level + "% §7more Powder from any source.";
			default -> "";
		};
	}

	private static ItemStack heartItem(MiningProfile prof) {
		List<String> lore = new ArrayList<>();
		lore.add("§7Token of the Mountain: §5∞ §8(test world)");
		lore.add("");
		lore.add("§7Mithril Powder: §2" + NUM.format(prof.powder(Powder.MITHRIL)));
		lore.add("§5  §8(§2+" + prof.powderBuffPercent() + "% §7more powder§8)");
		lore.add("§7Gemstone Powder: §d" + NUM.format(prof.powder(Powder.GEMSTONE)));
		lore.add("§5  §8(§d+" + prof.powderBuffPercent() + "% §7more powder§8)");
		lore.add("");
		lore.add("§7Obtain §2Mithril Powder §7by mining");
		lore.add("§2Mithril §7and §7Hard Stone§7.");
		lore.add("§7Obtain §dGemstone Powder §7by mining");
		lore.add("§dGemstones§7.");
		lore.add("");
		lore.add("§7HOTM Mining Speed: §6+" + NUM.format(prof.hotmMiningSpeed()));
		return item(Items.NETHER_STAR, 1, "§5Heart of the Mountain", lore, false);
	}

	private static ItemStack resetItem(MiningProfile prof) {
		long mithril = 0, gem = 0;
		for (Perk p : Perk.values()) {
			for (int l = 1; l <= prof.level(p); l++) {
				if (p.powder == Powder.MITHRIL) mithril += p.cost(l);
				else gem += p.cost(l);
			}
		}
		List<String> lore = List.of(
				"§7Resets the Perks and Abilities of",
				"§7your §5Heart of the Mountain§7, locking",
				"§7them and resetting their levels.",
				"",
				"§7You will be reimbursed with:",
				"§5  §8- §2" + NUM.format(mithril) + " Mithril Powder",
				"§5  §8- §d" + NUM.format(gem) + " Gemstone Powder",
				"",
				"§eClick to reset!");
		return item(Items.TNT, 1, "§cReset Heart of the Mountain", lore, false);
	}

	// ---------------------------------------------------------------- template

	private static ItemStack templateItem(Entry e, String extraLine) {
		List<String> lore = new ArrayList<>(e.lore());
		if (extraLine != null) {
			lore.add("");
			lore.add(extraLine);
		}
		boolean glint = e.item().startsWith("Enchanted");
		return item(itemFor(e.item()), Math.max(1, e.count()), e.name(), lore, glint);
	}

	private static Item itemFor(String wikiName) {
		return switch (wikiName) {
			case "Lime Stained Glass Pane" -> Items.STAINED_GLASS_PANE.lime();
			case "Coal" -> Items.COAL;
			case "Block of Coal" -> Items.COAL_BLOCK;
			case "Diamond" -> Items.DIAMOND;
			case "Emerald" -> Items.EMERALD;
			case "Block of Redstone" -> Items.REDSTONE_BLOCK;
			case "Enchanted Block of Emerald" -> Items.EMERALD_BLOCK;
			case "Chest" -> Items.CHEST;
			case "Amber Crystal" -> Items.STAINED_GLASS.orange();
			case "Ruby Crystal" -> Items.STAINED_GLASS.red();
			default -> Items.PAPER;
		};
	}

	private static Map<Long, Entry> loadTemplate() {
		Map<Long, Entry> map = new HashMap<>();
		Pattern line = Pattern.compile("^\\|(\\d+), (\\d+)=([^,]+), a, ([^,]+), (.*)$");
		try (InputStream in = HotmMenu.class.getResourceAsStream("/routeminer/hotm_ui.txt")) {
			if (in == null) return map;
			String text = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"));
			for (String l : text.split("\n")) {
				Matcher m = line.matcher(l);
				if (!m.matches()) continue;
				String[] itemAndCount = m.group(3).split(";");
				int count = itemAndCount.length > 1 ? Integer.parseInt(itemAndCount[1].trim()) : 1;
				map.put(key(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))),
						new Entry(itemAndCount[0].trim(), count, wikiText(m.group(4)), wikiLore(m.group(5))));
			}
		} catch (Exception e) {
			org.slf4j.LoggerFactory.getLogger("routeminer").warn("Couldn't load HOTM layout", e);
		}
		return map;
	}

	/** Wiki UI lore: '/' separates lines, '\/' and '\,' are literal, '&5&o' alone is a blank line. */
	private static List<String> wikiLore(String raw) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		for (int i = 0; i < raw.length(); i++) {
			char ch = raw.charAt(i);
			if (ch == '\\' && i + 1 < raw.length()) {
				cur.append(raw.charAt(++i));
			} else if (ch == '/') {
				out.add(cur.toString());
				cur.setLength(0);
			} else {
				cur.append(ch);
			}
		}
		out.add(cur.toString());
		return out.stream().map(s -> s.equals("&5&o") ? "" : wikiText(s)).toList();
	}

	private static String wikiText(String s) {
		return s.replace("{{=}}", "=").replace("\\,", ",").replace('&', '§');
	}

	private static String stripColour(String s) {
		return s.replaceAll("§.", "");
	}

	private static long key(int row, int col) {
		return row * 100L + col;
	}

	private static String powderColour(Powder p) {
		return p == Powder.MITHRIL ? "§2" : "§d";
	}

	static ItemStack item(Item item, int count, String name, List<String> lore, boolean glint) {
		ItemStack s = new ItemStack(item, count);
		s.set(DataComponents.ITEM_NAME, Component.literal(name));
		s.set(DataComponents.LORE, MiningData.lore(lore));
		if (glint) s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		return s;
	}
}
