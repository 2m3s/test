package dev.routeminer.test;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ItemLike;

import java.util.Arrays;

/** Test-world stand-ins for the SkyBlock items the macro uses, tagged with custom data. */
public final class TestItems {
	private static final String TAG = "routeminer_tool";

	public static final String AOTV = "aotv";
	public static final String DRILL = "drill";
	public static final String BOMB = "bomb";
	public static final String MEGA_BOMB = "mega_bomb";

	// Base items have no vanilla right-click action, so only our handlers run.
	public static ItemStack aotv() {
		return make(Items.DIAMOND_SWORD, AOTV, "§5Aspect of the Void",
				"§7Right-click: §fInstant Transmission §7(12 blocks)",
				"§7Sneak + right-click: §fEther Transmission",
				"§7Teleports onto the block you look at",
				"§7within §a61 §7blocks.",
				"",
				"§8RouteMiner test item");
	}

	public static ItemStack drill() {
		return make(Items.PRISMARINE_SHARD, DRILL, "§6Test Drill",
				"§7Hold this to mine gemstones.",
				"",
				"§8RouteMiner test item");
	}

	public static ItemStack bomb() {
		return make(Items.FIREWORK_STAR, BOMB, "§cMining Bomb",
				"§7Right-click: clears terrain in a",
				"§7radius of §c4 §7where you look.",
				"§7Gemstones and cobblestone are spared.",
				"",
				"§8RouteMiner test item");
	}

	public static ItemStack megaBomb() {
		return make(Items.FIRE_CHARGE, MEGA_BOMB, "§4Mega Mining Bomb",
				"§7Right-click: clears terrain in a",
				"§7radius of §c8 §7where you look.",
				"§7Gemstones and cobblestone are spared.",
				"",
				"§8RouteMiner test item");
	}

	public static String kind(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? "" : data.copyTag().getStringOr(TAG, "");
	}

	private static ItemStack make(ItemLike item, String kind, String name, String... lore) {
		ItemStack s = new ItemStack(item, 1);
		CompoundTag tag = new CompoundTag();
		tag.putString(TAG, kind);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		s.set(DataComponents.ITEM_NAME, Component.literal(name));
		s.set(DataComponents.LORE, MiningData.lore(Arrays.asList(lore)));
		s.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
		return s;
	}
}
