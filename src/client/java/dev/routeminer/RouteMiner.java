package dev.routeminer;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import dev.routeminer.test.TestServer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class RouteMiner implements ClientModInitializer {
	public static final String MOD_ID = "routeminer";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	private static final int COLOR_WAYPOINT = 0xFF00D0FF;
	private static final int COLOR_NEXT = 0xFFFFD000;
	private static final int COLOR_TARGET = 0xFFFF3050;
	private static final int COLOR_MISSING = 0xFFFF8000;
	private static final int COLOR_COBBLE = 0xFF30E030;

	Config cfg;
	final Route route = new Route();
	final BlockFilter filter = new BlockFilter();
	MinerMacro macro;
	private KeyMapping toggleKey, addKey, menuKey;
	private Screen pendingScreen;

	@Override
	public void onInitializeClient() {
		cfg = Config.load();
		filter.rebuild(cfg.blocks);
		if (!cfg.lastRoute.isEmpty()) {
			try { route.load(cfg.lastRoute); } catch (IOException e) { LOG.warn("Couldn't load route {}", cfg.lastRoute, e); }
		}
		macro = new MinerMacro(cfg, route, filter);

		KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.routeminer.toggle", InputConstants.KEY_J, cat));
		menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.routeminer.menu", InputConstants.KEY_RSHIFT, cat));
		addKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.routeminer.add", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), cat));

		TestWorld.register();
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		LevelRenderEvents.END_EXTRACTION.register(ctx -> macro.onFrame(Minecraft.getInstance()));
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "hud"), (g, delta) -> renderHud(g));
		ClientCommandRegistrationCallback.EVENT.register((d, ctx) -> registerCommands(d));
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> macro.onChat(message.getString()));
	}

	private void onTick(Minecraft mc) {
		if (pendingScreen != null) {
			mc.gui.setScreen(pendingScreen);
			pendingScreen = null;
		}
		while (toggleKey.consumeClick()) macro.toggle(mc);
		while (addKey.consumeClick()) MinerMacro.msg(mc, addWaypoint(mc, -1));
		while (menuKey.consumeClick()) if (mc.gui.screen() == null) mc.gui.setScreen(new RouteMinerScreen(this));
		macro.tick(mc);
		if (cfg.showRoute && mc.level != null && mc.player != null) drawRoute(mc);
	}

	// ---------------------------------------------------------------- actions (shared by commands and GUI)

	String addWaypoint(Minecraft mc, int at) {
		if (mc.player == null) return "§cNot in a world.";
		if (macro.running()) return "§cStop the macro before editing the route.";
		BlockPos feet = mc.player.blockPosition().below();
		if (mc.level.getBlockState(feet).isAir()) return "§cStand on a solid block.";
		int i = at < 0 || at > route.size() ? route.size() : at;
		route.points.add(i, feet);
		return "Added waypoint §f" + (i + 1) + "§r at " + feet.getX() + " " + feet.getY() + " " + feet.getZ();
	}

	String removeWaypoint(int n) {
		if (macro.running()) return "§cStop the macro before editing the route.";
		if (n < 1 || n > route.size()) return "§cNo waypoint " + n + ".";
		route.points.remove(n - 1);
		return "Removed waypoint " + n + ".";
	}

	String clearRoute() {
		if (macro.running()) return "§cStop the macro before editing the route.";
		route.points.clear();
		cfg.lastRoute = "";
		cfg.save();
		return "Route cleared.";
	}

	String saveRoute(String name) {
		if (name == null || name.isBlank()) return "§cEnter a route name.";
		try {
			route.save(name);
			cfg.lastRoute = name;
			cfg.save();
			return "Saved route §f" + name + "§r (" + route.size() + " waypoints).";
		} catch (IOException e) {
			return "§cSave failed: " + e.getMessage();
		}
	}

	String loadRoute(String name) {
		if (macro.running()) return "§cStop the macro before editing the route.";
		try {
			if (!route.load(name)) return "§cNo route named " + name;
			cfg.lastRoute = name;
			cfg.save();
			return "Loaded §f" + name + "§r (" + route.size() + " waypoints).";
		} catch (Exception e) {
			return "§cLoad failed: " + e.getMessage();
		}
	}

	String importRoute(Minecraft mc, int yOffset) {
		if (macro.running()) return "§cStop the macro before editing the route.";
		try {
			List<BlockPos> pts = RouteImporter.parse(mc.keyboardHandler.getClipboard());
			route.points.clear();
			for (BlockPos p : pts) route.points.add(p.offset(0, yOffset, 0));
			cfg.lastRoute = "";
			return "Imported §f" + route.size() + "§r waypoints. Place cobblestone on the orange boxes, then save.";
		} catch (IOException e) {
			return "§cImport failed: " + e.getMessage();
		}
	}

	String exportRoute(Minecraft mc) {
		if (route.size() == 0) return "§cRoute is empty.";
		mc.keyboardHandler.setClipboard(RouteImporter.export(route.points));
		return "Copied " + route.size() + " waypoints to clipboard (ColeWeight format).";
	}

	int missingCount(Minecraft mc) {
		if (mc.level == null) return 0;
		int n = 0;
		for (BlockPos p : route.points) if (mc.level.getBlockState(p).isAir()) n++;
		return n;
	}

	String setPreset(String name, boolean append) {
		List<String> ids = BlockFilter.preset(name);
		if (ids.isEmpty()) return "§cUnknown preset. Options: " + String.join(", ", BlockFilter.PRESETS.keySet());
		if (!append) cfg.blocks.clear();
		for (String id : ids) if (!cfg.blocks.contains(id)) cfg.blocks.add(id);
		return blocksChanged();
	}

	String blocksChanged() {
		cfg.save();
		filter.rebuild(cfg.blocks);
		return "Now mining " + cfg.blocks.size() + " block types.";
	}

	// ---------------------------------------------------------------- rendering

	private void drawRoute(Minecraft mc) {
		if (route.size() == 0 && macro.target() == null) return;
		int next = route.size() == 0 ? -1 : Math.floorMod(macro.index() + 1, route.size());
		try (var ignored = mc.collectPerTickGizmos()) {
			for (int i = 0; i < route.size(); i++) {
				BlockPos p = route.get(i);
				BlockState st = mc.level.getBlockState(p);
				int color = macro.running() && i == next ? COLOR_NEXT
						: st.isAir() ? COLOR_MISSING
						: st.is(Blocks.COBBLESTONE) ? COLOR_COBBLE
						: COLOR_WAYPOINT;
				Gizmos.cuboid(p, GizmoStyle.stroke(color, 2f)).setAlwaysOnTop();
				Gizmos.billboardText(String.valueOf(i + 1), Vec3.atCenterOf(p).add(0, 1.2, 0),
						TextGizmo.Style.forColorAndCentered(color)).setAlwaysOnTop();
				if (route.size() > 1) {
					Vec3 a = Vec3.atCenterOf(p).add(0, 0.5, 0);
					Vec3 b = Vec3.atCenterOf(route.get(i + 1)).add(0, 0.5, 0);
					Gizmos.line(a, b, 0x8000D0FF, 1.5f).setAlwaysOnTop();
				}
			}
			if (macro.target() != null) Gizmos.cuboid(macro.target(), GizmoStyle.stroke(COLOR_TARGET, 2f));
		}
	}

	private void renderHud(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		if (!macro.running() && route.size() == 0) return;
		var font = mc.font;
		boolean running = macro.running();

		// Lines below the header: label (grey) + value.
		List<String[]> rows = new java.util.ArrayList<>();
		rows.add(new String[]{"Route", cfg.lastRoute.isEmpty() ? "§7(unsaved)" : "§f" + cfg.lastRoute});
		if (running) {
			BlockPos t = macro.target();
			rows.add(new String[]{"Target", t == null ? "§7searching..." : "§f" + blockName(mc, t)});
			long elapsed = Math.max(1, System.currentTimeMillis() - macro.startedAt());
			double perMin = macro.blocksMined() * 60000.0 / elapsed;
			rows.add(new String[]{"Mined", "§f" + macro.blocksMined() + " §8(" + String.format("%.1f", perMin) + "/min)"});
			rows.add(new String[]{"Time", "§f" + formatTime(elapsed)});
			if (cfg.autoAbility) {
				long cd = macro.abilityCooldownMs();
				rows.add(new String[]{"Ability", cd == 0 ? "§aREADY" : "§e" + (cd / 1000 + 1) + "s"});
			}
		}

		int at = running && route.size() > 0 ? Math.floorMod(macro.index(), route.size()) + 1 : 0;
		if (route.size() > 0) rows.add(0, new String[]{"Waypoint", "§f" + at + "/" + route.size()});

		int pad = 6, lh = font.lineHeight + 3, labelW = 46;
		int width = 150;
		for (String[] r : rows) width = Math.max(width, pad * 2 + labelW + font.width(r[1]));
		int headerH = 16, barH = route.size() > 0 ? 9 : 0;
		int height = headerH + barH + rows.size() * lh + pad;
		int x = 6, y = 6;

		int accent = switch (macro.state()) {
			case MINING -> 0xFF4ADE80;
			case WARP_PREP, WARP_WAIT -> 0xFFC084FC;
			case ARRIVE -> 0xFF60A5FA;
			default -> 0xFF9CA3AF;
		};
		g.fill(x, y, x + width, y + height, 0xB0101418);                 // body
		g.fill(x, y, x + width, y + headerH, 0xD01A2230);                 // header
		g.fill(x, y, x + 2, y + height, accent);                          // accent edge
		g.fill(x, y + height - 1, x + width, y + height, 0x40FFFFFF);     // bottom hairline

		g.text(font, "§b§lRouteMiner", x + pad + 2, y + 4, 0xFFFFFFFF, false);
		String badge = running ? stateLabel(macro.state()) : "IDLE";
		int bw = font.width(badge) + 8;
		int bx = x + width - pad - bw;
		g.fill(bx, y + 3, bx + bw, y + headerH - 3, (accent & 0x00FFFFFF) | 0x50000000);
		g.text(font, badge, bx + 4, y + 4, accent, false);

		int cy = y + headerH + 3;
		if (barH > 0) {
			int bx0 = x + pad + 2, bx1 = x + width - pad;
			g.fill(bx0, cy, bx1, cy + 4, 0x60FFFFFF);
			g.fill(bx0, cy, bx0 + (int) ((bx1 - bx0) * (at / (double) route.size())), cy + 4, accent);
			cy += barH;
		}
		for (String[] r : rows) {
			g.text(font, "§7" + r[0], x + pad + 2, cy, 0xFFFFFFFF, false);
			g.text(font, r[1], x + pad + 2 + labelW, cy, 0xFFFFFFFF, false);
			cy += lh;
		}
	}

	private static String stateLabel(MinerMacro.State s) {
		return switch (s) {
			case MINING -> "MINING";
			case WARP_PREP, WARP_WAIT -> "WARPING";
			case ARRIVE -> "LANDING";
			default -> "IDLE";
		};
	}

	private static String blockName(Minecraft mc, BlockPos pos) {
		BlockState st = mc.level.getBlockState(pos);
		var mb = dev.routeminer.test.MiningData.block(st);
		return mb != null ? mb.name() : st.getBlock().getName().getString();
	}

	private static String formatTime(long ms) {
		long s = ms / 1000;
		return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
	}

	String statusLine() {
		return "RouteMiner: " + (macro.running() ? "§a" + macro.state() : "§7idle");
	}

	String routeLine() {
		return "§7Route: §f" + (cfg.lastRoute.isEmpty() ? "(unsaved)" : cfg.lastRoute)
				+ " §7- " + route.size() + " waypoints"
				+ (macro.running() && route.size() > 0 ? ", at " + (Math.floorMod(macro.index(), route.size()) + 1) : "");
	}

	// ---------------------------------------------------------------- commands

	/**
	 * Test world only: makes the current route warpable (cobblestone on each waypoint, a bomb blast
	 * around it, clear line of sight between waypoints), or with clear=true removes the cobblestone again.
	 */
	String testRoute(Minecraft mc, boolean clear, int radius) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null || !TestServer.isActive() || mc.level == null) return "§c/testroute only works in the " + TestServer.LEVEL_ID + ".";
		if (route.size() == 0) return "§cNo route selected. Import or load one first.";
		List<BlockPos> points = List.copyOf(route.points);
		var dim = mc.level.dimension();
		server.executeIfPossible(() -> {
			ServerLevel level = server.getLevel(dim);
			if (level == null) return;
			String result;
			if (clear) {
				result = "Removed cobblestone from §f" + TestServer.clearRouteCobble(level, points) + "§r waypoints.";
			} else {
				TestServer.PrepareResult r = TestServer.prepareRoute(level, points, radius);
				result = "Placed §f" + r.cobble() + "§r cobblestone, cleared §f" + r.cleared() + "§r blocks."
						+ (r.blockedByGems() > 0 ? " §6" + r.blockedByGems() + " warp(s) still blocked by gemstones or other waypoints." : "");
			}
			mc.execute(() -> MinerMacro.msg(mc, result));
		});
		return clear ? "Clearing route cobblestone..." : "Preparing route...";
	}

	private void registerCommands(CommandDispatcher<FabricClientCommandSource> d) {
		d.register(literal("testroute")
				.executes(c -> reply(c, testRoute(c.getSource().getClient(), false, 4)))
				.then(argument("radius", IntegerArgumentType.integer(1, 8))
						.executes(c -> reply(c, testRoute(c.getSource().getClient(), false, IntegerArgumentType.getInteger(c, "radius")))))
				.then(literal("clear").executes(c -> reply(c, testRoute(c.getSource().getClient(), true, 0)))));

		d.register(literal("rm")
				.executes(c -> { pendingScreen = new RouteMinerScreen(this); return 1; })
				.then(literal("help").executes(this::help))
				.then(literal("start").executes(c -> { macro.start(c.getSource().getClient()); return 1; }))
				.then(literal("stop").executes(c -> { macro.stop(c.getSource().getClient(), "Stopped."); return 1; }))
				.then(literal("add")
						.executes(c -> reply(c, addWaypoint(c.getSource().getClient(), -1)))
						.then(argument("position", IntegerArgumentType.integer(1))
								.executes(c -> reply(c, addWaypoint(c.getSource().getClient(), IntegerArgumentType.getInteger(c, "position") - 1)))))
				.then(literal("remove")
						.executes(c -> reply(c, removeWaypoint(route.size())))
						.then(argument("number", IntegerArgumentType.integer(1))
								.executes(c -> reply(c, removeWaypoint(IntegerArgumentType.getInteger(c, "number"))))))
				.then(literal("clear").executes(c -> reply(c, clearRoute())))
				.then(literal("list").executes(c -> {
					if (route.size() == 0) return reply(c, "Route is empty.");
					for (int i = 0; i < route.size(); i++) {
						BlockPos p = route.get(i);
						reply(c, "§7" + (i + 1) + ": §f" + p.getX() + " " + p.getY() + " " + p.getZ());
					}
					return 1;
				}))
				.then(literal("save").then(argument("name", StringArgumentType.word())
						.executes(c -> reply(c, saveRoute(StringArgumentType.getString(c, "name"))))))
				.then(literal("load").then(argument("name", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(Route.savedNames(), b))
						.executes(c -> reply(c, loadRoute(StringArgumentType.getString(c, "name"))))))
				.then(literal("import")
						.executes(c -> reply(c, importRoute(c.getSource().getClient(), 0)))
						.then(argument("yOffset", IntegerArgumentType.integer(-5, 5))
								.executes(c -> reply(c, importRoute(c.getSource().getClient(), IntegerArgumentType.getInteger(c, "yOffset"))))))
				.then(literal("export").executes(c -> reply(c, exportRoute(c.getSource().getClient()))))
				.then(literal("missing").executes(c -> {
					for (int i = 0; i < route.size(); i++) {
						BlockPos p = route.get(i);
						if (c.getSource().getLevel().getBlockState(p).isAir()) {
							reply(c, "§6" + (i + 1) + ": §f" + p.getX() + " " + p.getY() + " " + p.getZ() + " §7needs cobblestone");
						}
					}
					int n = missingCount(c.getSource().getClient());
					return reply(c, n == 0 ? "§aAll waypoints have a block." : n + " waypoint(s) still need cobblestone.");
				}))
				.then(literal("routes").executes(c -> {
					List<String> names = Route.savedNames();
					return reply(c, names.isEmpty() ? "No saved routes." : "Saved: §f" + String.join(", ", names));
				}))
				.then(literal("blocks")
						.then(literal("list").executes(c -> reply(c, cfg.blocks.isEmpty() ? "No target blocks." : "Targets: §f" + String.join(", ", cfg.blocks))))
						.then(literal("clear").executes(c -> { cfg.blocks.clear(); return reply(c, blocksChanged()); }))
						.then(literal("preset").then(argument("name", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(BlockFilter.PRESETS.keySet(), b))
								.executes(c -> reply(c, setPreset(StringArgumentType.getString(c, "name"), false)))))
						.then(literal("addpreset").then(argument("name", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(BlockFilter.PRESETS.keySet(), b))
								.executes(c -> reply(c, setPreset(StringArgumentType.getString(c, "name"), true)))))
						.then(literal("add").then(argument("id", StringArgumentType.greedyString()).executes(c -> {
							String id = BlockFilter.normalize(StringArgumentType.getString(c, "id").trim());
							if (BlockFilter.resolve(id) == null) return reply(c, "§cUnknown block " + id);
							if (!cfg.blocks.contains(id)) cfg.blocks.add(id);
							return reply(c, blocksChanged());
						})))
						.then(literal("remove").then(argument("id", StringArgumentType.greedyString())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(cfg.blocks, b))
								.executes(c -> {
									cfg.blocks.remove(BlockFilter.normalize(StringArgumentType.getString(c, "id").trim()));
									return reply(c, blocksChanged());
								}))))
				.then(literal("slot")
						.then(literal("mine").then(argument("slot", IntegerArgumentType.integer(1, 9)).executes(c -> {
							cfg.mineSlot = IntegerArgumentType.getInteger(c, "slot");
							cfg.save();
							return reply(c, "Mining tool slot: " + cfg.mineSlot);
						})))
						.then(literal("warp").then(argument("slot", IntegerArgumentType.integer(1, 9)).executes(c -> {
							cfg.warpSlot = IntegerArgumentType.getInteger(c, "slot");
							cfg.save();
							return reply(c, "Etherwarp item slot: " + cfg.warpSlot);
						}))))
				.then(literal("speed").then(argument("degPerSec", DoubleArgumentType.doubleArg(30, 2000)).executes(c -> {
					cfg.rotationSpeed = DoubleArgumentType.getDouble(c, "degPerSec");
					cfg.save();
					return reply(c, "Rotation speed: " + cfg.rotationSpeed + " deg/s");
				})))
				.then(literal("show").executes(c -> {
					cfg.showRoute = !cfg.showRoute;
					cfg.save();
					return reply(c, "Route rendering " + (cfg.showRoute ? "on" : "off") + ".");
				})));
	}

	private int help(CommandContext<FabricClientCommandSource> c) {
		reply(c, "§fCommands §7(/rm alone opens the menu, also Right Shift):");
		for (String s : List.of(
				"/rm add [pos] §7- add the block you stand on",
				"/rm remove [n] | clear | list",
				"/rm save <name> | load <name> | routes",
				"/rm import [yOffset] §7- route from clipboard§b | export | missing",
				"/rm blocks preset|addpreset <gemstone|ruby|glacite|umber|tungsten|mithril|titanium|...>",
				"/rm blocks add|remove <id> | list | clear",
				"/rm slot mine|warp <1-9>  ·  /rm speed <deg/s>",
				"/rm start | stop  (or press J)  ·  /rm show",
				"§7Test world: /tools, /gemregen <seconds>, /regencrystal, /testroute [radius|clear]")) {
			reply(c, "§b" + s);
		}
		return 1;
	}

	private static int reply(CommandContext<FabricClientCommandSource> c, String text) {
		c.getSource().sendFeedback(Component.literal("§b[RouteMiner] §r" + text));
		return 1;
	}
}
