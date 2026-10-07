package dev.routeminer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import dev.routeminer.test.TestServer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Local practice world: a "Test World" button on the title screen that unpacks
 * config/routeminer/testworld.zip into saves/ and opens it in singleplayer.
 * Use /tools in that world for the Aspect of the Void, drill, bombs and cobblestone.
 */
public final class TestWorld {
	public static final String LEVEL_ID = TestServer.LEVEL_ID;
	public static final Path ZIP = Config.DIR.resolve("testworld.zip");

	public static void register() {
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (!(screen instanceof TitleScreen)) return;
			boolean installed = mc.getLevelSource().levelExists(LEVEL_ID);
			boolean available = installed || Files.isRegularFile(ZIP);
			Button b = Button.builder(Component.literal("Test World"), btn -> open(mc, screen))
					.bounds(w / 2 + 104, h / 4 + 48, 80, 20)
					.tooltip(Tooltip.create(Component.literal(available
							? "Open the local mining test world (RouteMiner)"
							: "Put a world .zip at " + ZIP + " to enable")))
					.build();
			b.active = available;
			Screens.getWidgets(screen).add(b);

			Button reset = Button.builder(Component.literal("Reset World"), btn -> confirmReset(mc, screen))
					.bounds(w / 2 + 104, h / 4 + 72, 80, 20)
					.tooltip(Tooltip.create(Component.literal("Delete the test world and unpack a fresh copy (all gems back, terrain restored)")))
					.build();
			reset.active = installed && Files.isRegularFile(ZIP);
			Screens.getWidgets(screen).add(reset);
		});
	}

	private static void open(Minecraft mc, Screen parent) {
		if (!mc.getLevelSource().levelExists(LEVEL_ID)) {
			try {
				extract(ZIP, mc.getLevelSource().getBaseDir().resolve(LEVEL_ID));
			} catch (IOException e) {
				RouteMiner.LOG.error("Failed to unpack test world", e);
				return;
			}
		}
		mc.createWorldOpenFlows().openWorld(LEVEL_ID, () -> mc.gui.setScreen(parent));
	}

	private static void confirmReset(Minecraft mc, Screen parent) {
		mc.gui.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				try {
					Path dir = mc.getLevelSource().getBaseDir().resolve(LEVEL_ID);
					deleteTree(dir);
					extract(ZIP, dir);
				} catch (IOException e) {
					RouteMiner.LOG.error("Failed to reset test world", e);
				}
			}
			mc.gui.setScreen(new TitleScreen());
		}, Component.literal("Reset the RouteMiner Test World?"),
				Component.literal("This deletes the world and unpacks a fresh copy from testworld.zip. Your saved routes are kept.")));
	}

	private static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir)) return;
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
		}
	}

	/** Unzips the world, stripping whatever folder level.dat sits in. */
	private static void extract(Path zip, Path dest) throws IOException {
		try (ZipFile zf = new ZipFile(zip.toFile())) {
			String prefix = zf.stream()
					.map(ZipEntry::getName)
					.filter(n -> n.equals("level.dat") || n.endsWith("/level.dat"))
					.min((a, b) -> a.length() - b.length())
					.map(n -> n.substring(0, n.length() - "level.dat".length()))
					.orElseThrow(() -> new IOException("no level.dat in " + zip));

			Path root = dest.toAbsolutePath().normalize();
			Files.createDirectories(root);
			for (ZipEntry e : zf.stream().toList()) {
				if (!e.getName().startsWith(prefix)) continue;
				String rel = e.getName().substring(prefix.length());
				if (rel.isEmpty()) continue;
				Path out = root.resolve(rel).normalize();
				if (!out.startsWith(root)) throw new IOException("unsafe path in zip: " + e.getName());
				if (e.isDirectory()) {
					Files.createDirectories(out);
				} else {
					Files.createDirectories(out.getParent());
					try (InputStream in = zf.getInputStream(e)) {
						Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
		}
	}
}
