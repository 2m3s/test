package dev.routeminer;

import dev.routeminer.test.TestServer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/** Tabbed settings and route menu, opened with /rm or Right Shift. */
public class RouteMinerScreen extends Screen {
	private enum Tab { ROUTE("Route"), BLOCKS("Blocks"), SETTINGS("Settings");
		final String label;
		Tab(String label) { this.label = label; }
	}

	private static final int PANEL_W = 330, PANEL_H = 250;
	private static final int ROUTES_PER_PAGE = 9;
	private static Tab lastTab = Tab.ROUTE;

	private final RouteMiner mod;
	private Tab tab = lastTab;
	private String message = "";
	private String nameDraft;
	private int routePage;

	private int px, py; // panel origin

	public RouteMinerScreen(RouteMiner mod) {
		super(Component.literal("RouteMiner"));
		this.mod = mod;
		this.nameDraft = mod.cfg.lastRoute;
	}

	@Override
	protected void init() {
		px = width / 2 - PANEL_W / 2;
		py = Math.max(4, height / 2 - PANEL_H / 2);

		// Tabs
		int tw = (PANEL_W - 20) / Tab.values().length;
		for (int i = 0; i < Tab.values().length; i++) {
			Tab t = Tab.values()[i];
			Button b = button(px + 10 + i * tw, py + 30, tw - 2, t == tab ? "§e§l" + t.label : "§7" + t.label, x -> switchTab(t));
			b.active = t != tab;
		}

		int top = py + 58;
		switch (tab) {
			case ROUTE -> initRoute(top);
			case BLOCKS -> initBlocks(top);
			case SETTINGS -> initSettings(top);
		}

		// Footer
		int fy = py + PANEL_H - 28;
		button(px + 10, fy, 150, mod.macro.running() ? "§c■ Stop macro" : "§a▶ Start macro", b -> {
			if (mod.macro.running()) {
				mod.macro.stop(minecraft, "Stopped.");
				rebuildWidgets();
			} else {
				onClose();
				mod.macro.start(minecraft);
			}
		});
		button(px + PANEL_W - 160, fy, 150, "Done", b -> onClose());
	}

	// ---------------------------------------------------------------- tabs

	private void initRoute(int y) {
		int x = px + 10, full = PANEL_W - 20, half = (full - 4) / 2;

		button(x, y, half, "Import from clipboard", b -> { message = mod.importRoute(minecraft, 0); rebuildWidgets(); })
				.setTooltip(Tooltip.create(Component.literal("ColeWeight / Mining Cult JSON, Skytils exports or x y z lines")));
		button(x + half + 4, y, half, "Export to clipboard", b -> message = mod.exportRoute(minecraft));
		y += 24;

		boolean testWorld = TestServer.isActive() && minecraft.getSingleplayerServer() != null;
		int missing = mod.missingCount(minecraft);
		Button prep = button(x, y, half, "Prepare route (/testroute)", b -> { message = mod.testRoute(minecraft, false, 4); });
		prep.active = testWorld && mod.route.size() > 0;
		prep.setTooltip(Tooltip.create(Component.literal(testWorld
				? "Places cobblestone on every waypoint and blasts space around them"
				: "Only available in the RouteMiner Test World")));
		button(x + half + 4, y, half, missing == 0 ? "Missing blocks: §a0" : "Missing blocks: §6" + missing, b -> {
			int n = mod.missingCount(minecraft);
			message = n == 0 ? "§aEvery waypoint has a block." : "§6" + n + " waypoint(s) need cobblestone (orange boxes).";
			rebuildWidgets();
		});
		y += 30;

		EditBox name = new EditBox(font, x, y, full - 84, 20, Component.literal("Route name"));
		name.setMaxLength(40);
		name.setHint(Component.literal("§8name this route..."));
		name.setValue(nameDraft);
		name.setResponder(v -> nameDraft = v);
		addRenderableWidget(name);
		button(x + full - 80, y, 80, "§aSave", b -> { message = mod.saveRoute(nameDraft.trim()); rebuildWidgets(); });
		y += 26;

		// Saved routes as a grid: click to load.
		List<String> saved = Route.savedNames();
		int pages = Math.max(1, (saved.size() + ROUTES_PER_PAGE - 1) / ROUTES_PER_PAGE);
		routePage = Mth.clamp(routePage, 0, pages - 1);
		int cw = (full - 8) / 3;
		if (saved.isEmpty()) {
			Button none = button(x, y, full, "§8No saved routes yet", b -> {});
			none.active = false;
		}
		for (int i = 0; i < ROUTES_PER_PAGE; i++) {
			int idx = routePage * ROUTES_PER_PAGE + i;
			if (idx >= saved.size()) break;
			String n = saved.get(idx);
			boolean current = n.equals(mod.cfg.lastRoute);
			Button b = button(x + (i % 3) * (cw + 4), y + (i / 3) * 22, cw, current ? "§a● " + n : n, btn -> {
				message = mod.loadRoute(n);
				nameDraft = mod.cfg.lastRoute;
				rebuildWidgets();
			});
			b.setTooltip(Tooltip.create(Component.literal(current ? "Currently loaded" : "Click to load " + n)));
		}
		int gridBottom = y + 3 * 22;
		if (pages > 1) {
			Button prev = button(x, gridBottom, 20, "◀", b -> { routePage--; rebuildWidgets(); });
			prev.active = routePage > 0;
			Button next = button(x + 24, gridBottom, 20, "▶", b -> { routePage++; rebuildWidgets(); });
			next.active = routePage < pages - 1;
		}
		button(x + full - 100, gridBottom, 100, "§cClear route", b -> { message = mod.clearRoute(); nameDraft = ""; rebuildWidgets(); });
	}

	private void initBlocks(int y) {
		int x = px + 10, full = PANEL_W - 20;
		int cols = 4, cw = (full - (cols - 1) * 4) / cols;
		List<String> presets = new ArrayList<>(BlockFilter.PRESETS.keySet());
		for (int i = 0; i < presets.size(); i++) {
			String p = presets.get(i);
			boolean on = mod.cfg.blocks.containsAll(BlockFilter.preset(p));
			Button b = button(x + (i % cols) * (cw + 4), y + (i / cols) * 22, cw, (on ? "§a✔ " : "§7") + cap(p), btn -> {
				togglePreset(p);
				rebuildWidgets();
			});
			b.setTooltip(Tooltip.create(Component.literal((on ? "Click to stop mining " : "Click to also mine ") + p
					+ " §7(" + String.join(", ", BlockFilter.preset(p).stream().map(s -> s.replace("minecraft:", "")).toList()) + ")")));
		}
		int rows = (presets.size() + cols - 1) / cols;
		int by = y + rows * 22 + 6;
		button(x, by, (full - 4) / 2, "Only gemstones", b -> { message = mod.setPreset("gemstone", false); rebuildWidgets(); });
		button(x + (full - 4) / 2 + 4, by, (full - 4) / 2, "§cClear all", b -> {
			mod.cfg.blocks.clear();
			message = mod.blocksChanged();
			rebuildWidgets();
		});
	}

	private void initSettings(int y) {
		Config cfg = mod.cfg;
		int x = px + 10, full = PANEL_W - 20;
		int labelW = 86, sw = (full - labelW - 8 * 2) / 9;

		slotRow(x, y, labelW, sw, "Mining tool:", cfg.mineSlot, s -> { cfg.mineSlot = s; cfg.save(); });
		y += 24;
		slotRow(x, y, labelW, sw, "Aspect of Void:", cfg.warpSlot, s -> { cfg.warpSlot = s; cfg.save(); });
		y += 30;

		int half = (full - 4) / 2;
		addRenderableWidget(new Slider(x, y, half, "Turn speed", 90, 1080, 10, cfg.rotationSpeed,
				v -> (int) v + "°/s", v -> { cfg.rotationSpeed = v; cfg.save(); }));
		addRenderableWidget(new Slider(x + half + 4, y, half, "Wait before warp", 0, 40, 1, cfg.emptyTicksBeforeWarp,
				v -> (int) v + " ticks", v -> { cfg.emptyTicksBeforeWarp = (int) v; cfg.save(); }));
		y += 24;
		button(x, y, half, "Route boxes: " + (cfg.showRoute ? "§aON" : "§cOFF"), b -> {
			cfg.showRoute = !cfg.showRoute;
			cfg.save();
			rebuildWidgets();
		});
		button(x + half + 4, y, half, "Mouse lock: " + (cfg.mouseLock ? "§aON" : "§cOFF"), b -> {
			cfg.mouseLock = !cfg.mouseLock;
			cfg.save();
			rebuildWidgets();
		}).setTooltip(Tooltip.create(Component.literal("Ignore your mouse while the macro is running")));
		y += 24;
		button(x, y, half, "Auto ability: " + (cfg.autoAbility ? "§aON" : "§cOFF"), b -> {
			cfg.autoAbility = !cfg.autoAbility;
			cfg.save();
			rebuildWidgets();
		}).setTooltip(Tooltip.create(Component.literal("Use your pickaxe ability (Mining Speed Boost) whenever it's ready")));
	}

	private void slotRow(int x, int y, int labelW, int sw, String label, int current, java.util.function.IntConsumer set) {
		addRenderableOnly((g, mx, my, d) -> g.text(font, "§7" + label, x, y + 6, 0xFFFFFFFF, false));
		for (int s = 1; s <= 9; s++) {
			int slot = s;
			Button b = button(x + labelW + (s - 1) * (sw + 2), y, sw, s == current ? "§a§l" + s : "§7" + s, btn -> {
				set.accept(slot);
				rebuildWidgets();
			});
			b.setTooltip(Tooltip.create(Component.literal("Hotbar slot " + s)));
		}
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractBackground(g, mouseX, mouseY, delta);
		g.fill(px - 1, py - 1, px + PANEL_W + 1, py + PANEL_H + 1, 0xFF3A7BD5); // border
		g.fill(px, py, px + PANEL_W, py + PANEL_H, 0xE0101418);
		g.fill(px, py, px + PANEL_W, py + 26, 0xFF1A2230);                         // header strip
		g.fill(px + 10, py + 54, px + PANEL_W - 10, py + 55, 0x40FFFFFF);          // under tabs
		g.fill(px + 10, py + PANEL_H - 34, px + PANEL_W - 10, py + PANEL_H - 33, 0x40FFFFFF);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.text(font, "§b§lRouteMiner", px + 10, py + 9, 0xFFFFFFFF, false);
		String status = (mod.macro.running() ? "§a● " + mod.macro.state() : "§7○ idle") + "  §8|  §f" + mod.route.size() + " §7waypoints"
				+ "  §8|  §f" + mod.cfg.blocks.size() + " §7blocks";
		g.text(font, status, px + PANEL_W - 10 - font.width(status), py + 9, 0xFFFFFFFF, false);
		if (!message.isEmpty()) g.centeredText(font, message, width / 2, py + PANEL_H - 44, 0xFFFFFFFF);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ---------------------------------------------------------------- helpers

	private void switchTab(Tab t) {
		tab = t;
		lastTab = t;
		message = "";
		rebuildWidgets();
	}

	private void togglePreset(String p) {
		List<String> ids = BlockFilter.preset(p);
		if (mod.cfg.blocks.containsAll(ids)) mod.cfg.blocks.removeAll(ids);
		else for (String id : ids) if (!mod.cfg.blocks.contains(id)) mod.cfg.blocks.add(id);
		message = mod.blocksChanged();
	}

	private Button button(int x, int y, int w, String label, Button.OnPress onPress) {
		return addRenderableWidget(Button.builder(Component.literal(label), onPress).bounds(x, y, w, 20).build());
	}

	private static String cap(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static final class Slider extends AbstractSliderButton {
		private final String name;
		private final double min, max, step;
		private final DoubleFunction<String> fmt;
		private final DoubleConsumer onChange;

		Slider(int x, int y, int w, String name, double min, double max, double step, double current,
				DoubleFunction<String> fmt, DoubleConsumer onChange) {
			super(x, y, w, 20, Component.empty(), (Mth.clamp(current, min, max) - min) / (max - min));
			this.name = name;
			this.min = min;
			this.max = max;
			this.step = step;
			this.fmt = fmt;
			this.onChange = onChange;
			updateMessage();
		}

		private double actual() {
			return Math.round((min + value * (max - min)) / step) * step;
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(name + ": " + fmt.apply(actual())));
		}

		@Override
		protected void applyValue() {
			onChange.accept(actual());
		}
	}
}
