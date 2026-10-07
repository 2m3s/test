package dev.routeminer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Persistent settings, stored in config/routeminer/config.json. */
public class Config {
	static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("routeminer");
	private static final Path FILE = DIR.resolve("config.json");

	/** Hotbar slot (1-9) holding the drill/pickaxe. */
	public int mineSlot = 1;
	/** Hotbar slot (1-9) holding the Aspect of the Void / etherwarp item. */
	public int warpSlot = 2;
	/** Camera turn speed in degrees per second. */
	public double rotationSpeed = 360;
	/** Ticks to wait with no minable blocks before warping on. */
	public int emptyTicksBeforeWarp = 8;
	/** Block ids to mine, e.g. "minecraft:red_stained_glass". */
	public List<String> blocks = new ArrayList<>(BlockFilter.preset("gemstone"));
	public boolean showRoute = true;
	/** Ignore real mouse movement while the macro runs so it can't fight the aim. */
	public boolean mouseLock = true;
	/** Use the pickaxe ability (e.g. Mining Speed Boost) whenever it's ready while mining. */
	public boolean autoAbility = true;
	public String lastRoute = "";

	public static Config load() {
		if (Files.exists(FILE)) {
			try (Reader r = Files.newBufferedReader(FILE)) {
				Config c = GSON.fromJson(r, Config.class);
				if (c != null) return c;
			} catch (Exception e) {
				RouteMiner.LOG.warn("Failed to read config, using defaults", e);
			}
		}
		Config c = new Config();
		c.save();
		return c;
	}

	public void save() {
		try {
			Files.createDirectories(DIR);
			try (Writer w = Files.newBufferedWriter(FILE)) {
				GSON.toJson(this, w);
			}
		} catch (IOException e) {
			RouteMiner.LOG.error("Failed to save config", e);
		}
	}
}
