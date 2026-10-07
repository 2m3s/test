package dev.routeminer.test;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Server-side mechanics for the RouteMiner test world. They run on the built-in
 * singleplayer server and are only active in the world folder named LEVEL_ID:
 * /tools chest, Aspect of the Void teleports, mining bombs and gemstone respawning.
 */
public class TestServer implements ModInitializer {
	public static final String LEVEL_ID = "RouteMiner Test World";

	private static final double ETHERWARP_RANGE = 61;
	private static final double TRANSMISSION_RANGE = 12;

	private record Regen(ServerLevel level, BlockPos pos, BlockState state, int dueTick) {}

	private static final PriorityQueue<Regen> regens = new PriorityQueue<>(Comparator.comparingInt(Regen::dueTick));
	private static final Set<BlockPos> pending = new HashSet<>();
	private static int regenSeconds = 60;
	private static boolean active;
	private static boolean dirty;
	private static final Logger LOG = LoggerFactory.getLogger("routeminer");

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			active = isTestWorld(s);
			regens.clear();
			pending.clear();
			// Gems broken in a session that crashed or was force-closed come back now.
			if (active) {
				restoreSaved(s);
				MiningProfile.load(profilesFile(s));
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			// Put every gemstone back so the saved world stays intact.
			if (active) {
				regenAll();
				saveQueue(s);
				MiningProfile.save(profilesFile(s));
			}
			MiningProfile.clear();
			active = false;
		});
		ServerTickEvents.END_SERVER_TICK.register(TestServer::tick);
		UseItemCallback.EVENT.register(TestServer::onUse);
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
			if (!active || !(level instanceof ServerLevel sl)) return;
			if (isGemstone(state) && pending.add(pos.immutable())) {
				regens.add(new Regen(sl, pos.immutable(), state, sl.getServer().getTickCount() + regenSeconds * 20));
				dirty = true;
			}
			awardPowder(player, state);
		});
		CommandRegistrationCallback.EVENT.register((d, ctx, env) -> {
			d.register(Commands.literal("tools").executes(c -> openTools(c.getSource())));
			d.register(Commands.literal("hotm").executes(c -> {
				if (!requireActive(c.getSource())) return 0;
				HotmMenu.open(c.getSource().getPlayerOrException());
				return 1;
			}));
			d.register(Commands.literal("powder")
					.executes(c -> {
						if (!requireActive(c.getSource())) return 0;
						MiningProfile prof = MiningProfile.of(c.getSource().getPlayerOrException().getUUID());
						c.getSource().sendSuccess(() -> Component.literal("§2Mithril Powder: §f" + prof.powder(MiningProfile.Powder.MITHRIL)
								+ "  §dGemstone Powder: §f" + prof.powder(MiningProfile.Powder.GEMSTONE)), false);
						return 1;
					})
					.then(Commands.literal("give")
							.then(Commands.argument("type", StringArgumentType.word())
									.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("mithril", "gemstone"), b))
									.then(Commands.argument("amount", IntegerArgumentType.integer(1))
											.executes(c -> {
												if (!requireActive(c.getSource())) return 0;
												String type = StringArgumentType.getString(c, "type");
												MiningProfile.Powder pw = type.startsWith("m") ? MiningProfile.Powder.MITHRIL : MiningProfile.Powder.GEMSTONE;
												int amt = IntegerArgumentType.getInteger(c, "amount");
												MiningProfile.of(c.getSource().getPlayerOrException().getUUID()).addPowder(pw, amt);
												c.getSource().sendSuccess(() -> Component.literal("Gave " + amt + " " + pw.label + " Powder§r."), false);
												return 1;
											})))));
			d.register(Commands.literal("miningspeed")
					.then(Commands.argument("extra", IntegerArgumentType.integer(0, 50000)).executes(c -> {
						if (!requireActive(c.getSource())) return 0;
						int v = IntegerArgumentType.getInteger(c, "extra");
						MiningProfile.of(c.getSource().getPlayerOrException().getUUID()).baseSpeed = v;
						c.getSource().sendSuccess(() -> Component.literal("Extra Mining Speed (pets, other gear) set to " + v + "."), false);
						return 1;
					})));
			d.register(Commands.literal("stats").executes(c -> {
				if (!requireActive(c.getSource())) return 0;
				showStats(c.getSource().getPlayerOrException());
				return 1;
			}));
			d.register(Commands.literal("regencrystal").executes(c -> {
				if (!active) {
					c.getSource().sendFailure(Component.literal("/regencrystal only works in the " + LEVEL_ID + "."));
					return 0;
				}
				int n = regenAll();
				saveQueue(c.getSource().getServer());
				c.getSource().sendSuccess(() -> Component.literal("Regenerated " + n + " gemstone blocks."), false);
				return n;
			}));
			d.register(Commands.literal("gemregen")
					.executes(c -> {
						c.getSource().sendSuccess(() -> Component.literal("Gemstones respawn after " + regenSeconds + "s."), false);
						return 1;
					})
					.then(Commands.argument("seconds", IntegerArgumentType.integer(1, 3600)).executes(c -> {
						regenSeconds = IntegerArgumentType.getInteger(c, "seconds");
						c.getSource().sendSuccess(() -> Component.literal("Gemstones now respawn after " + regenSeconds + "s."), false);
						return 1;
					})));
		});
	}

	/** True while the RouteMiner test world is running on the local server. */
	public static boolean isActive() {
		return active;
	}

	private static boolean isTestWorld(MinecraftServer s) {
		if (s.isDedicatedServer()) return false;
		Path root = s.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		return root.getFileName() != null && root.getFileName().toString().equals(LEVEL_ID);
	}

	// ---------------------------------------------------------------- /tools

	private static int openTools(CommandSourceStack src) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		if (!active) {
			src.sendFailure(Component.literal("/tools only works in the " + LEVEL_ID + "."));
			return 0;
		}
		ServerPlayer p = src.getPlayerOrException();
		SimpleContainer box = new SimpleContainer(54);
		// Row 1: movement and terrain tools
		box.setItem(0, TestItems.aotv());
		box.setItem(2, TestItems.bomb());
		box.setItem(3, TestItems.megaBomb());
		box.setItem(5, new ItemStack(Items.COBBLESTONE, 64));
		box.setItem(6, new ItemStack(Items.COBBLESTONE, 64));
		// Rows 2-3: every drill, max parts and gems
		for (int i = 0; i < MiningData.DRILLS.size(); i++) box.setItem(9 + i, MiningData.drillItem(MiningData.DRILLS.get(i)));
		// Row 5: Armor of Divan with max gems
		box.setItem(36, MiningData.divanPiece(EquipmentSlot.HEAD));
		box.setItem(37, MiningData.divanPiece(EquipmentSlot.CHEST));
		box.setItem(38, MiningData.divanPiece(EquipmentSlot.LEGS));
		box.setItem(39, MiningData.divanPiece(EquipmentSlot.FEET));
		p.openMenu(new SimpleMenuProvider((id, inv, pl) -> ChestMenu.sixRows(id, inv, box),
				Component.literal("RouteMiner Test Tools")));
		return 1;
	}

	// ---------------------------------------------------------------- items

	private static InteractionResult onUse(Player player, Level level, net.minecraft.world.InteractionHand hand) {
		String kind = TestItems.kind(player.getItemInHand(hand));
		if (kind.isEmpty()) return InteractionResult.PASS;
		// Let the client send the use packet; the server does the work.
		if (level.isClientSide()) return InteractionResult.PASS;
		if (!active || !(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl)) return InteractionResult.PASS;

		switch (kind) {
			case TestItems.AOTV -> {
				if (sp.isShiftKeyDown()) etherwarp(sp, sl);
				else instantTransmission(sp, sl);
			}
			case TestItems.DRILL -> sp.sendSystemMessage(Component.literal(
					MiningProfile.of(sp.getUUID()).activateBoost(sl.getServer().getTickCount())));
			case TestItems.BOMB -> bomb(sp, sl, 4);
			case TestItems.MEGA_BOMB -> bomb(sp, sl, 8);
			default -> { return InteractionResult.PASS; }
		}
		return InteractionResult.SUCCESS;
	}

	/** Sneak + use: teleport on top of the looked-at block if two blocks of room are above it. */
	private static void etherwarp(ServerPlayer p, ServerLevel level) {
		BlockHitResult hit = look(p, level, ETHERWARP_RANGE);
		if (hit.getType() != HitResult.Type.BLOCK) {
			p.sendSystemMessage(Component.literal("§cNo block in range!"));
			return;
		}
		BlockPos pos = hit.getBlockPos();
		if (!passable(level, pos.above()) || !passable(level, pos.above(2))) {
			p.sendSystemMessage(Component.literal("§cThere are blocks in the way!"));
			return;
		}
		teleport(p, level, new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5));
	}

	/** Plain use: move up to 12 blocks forward, stopping before anything solid. */
	private static void instantTransmission(ServerPlayer p, ServerLevel level) {
		Vec3 dir = p.getViewVector(1f);
		AABB box = p.getBoundingBox();
		Vec3 best = null;
		for (double d = 0.5; d <= TRANSMISSION_RANGE; d += 0.5) {
			Vec3 off = dir.scale(d);
			if (!level.noCollision(p, box.move(off))) break;
			best = off;
		}
		if (best != null) teleport(p, level, p.position().add(best));
	}

	private static void teleport(ServerPlayer p, ServerLevel level, Vec3 to) {
		p.teleportTo(to.x, to.y, to.z);
		p.resetFallDistance();
		level.playSound(null, to.x, to.y, to.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1f, 0.6f);
	}

	private static void bomb(ServerPlayer p, ServerLevel level, int radius) {
		BlockHitResult hit = look(p, level, 40);
		BlockPos center = hit.getType() == HitResult.Type.BLOCK
				? hit.getBlockPos()
				: BlockPos.containing(p.getEyePosition().add(p.getViewVector(1f).scale(10)));
		int cleared = explode(level, center, radius);
		p.sendSystemMessage(Component.literal("§7Bomb cleared §f" + cleared + "§7 blocks."));
	}

	/** Clears a sphere of terrain, sparing gemstones, cobblestone (waypoints) and bedrock. */
	public static int explode(ServerLevel level, BlockPos center, int radius) {
		int cleared = 0;
		double r2 = (radius + 0.5) * (radius + 0.5);
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
			if (pos.distSqr(center) > r2) continue;
			BlockState st = level.getBlockState(pos);
			if (st.isAir() || isGemstone(st) || st.is(Blocks.COBBLESTONE) || st.is(Blocks.BEDROCK) || st.hasBlockEntity()) continue;
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
			cleared++;
		}
		Vec3 c = Vec3.atCenterOf(center);
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 1, 0, 0, 0, 0);
		level.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 2f, 1f);
		return cleared;
	}

	private static BlockHitResult look(ServerPlayer p, ServerLevel level, double range) {
		Vec3 eye = p.getEyePosition();
		Vec3 end = eye.add(p.getViewVector(1f).scale(range));
		return level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
	}

	private static boolean passable(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	// ---------------------------------------------------------------- mining stats

	private static Path profilesFile(MinecraftServer s) {
		return s.getWorldPath(LevelResource.ROOT).resolve("routeminer_profiles.json");
	}

	private static boolean requireActive(CommandSourceStack src) {
		if (active) return true;
		src.sendFailure(Component.literal("That command only works in the " + LEVEL_ID + "."));
		return false;
	}

	/** Powder per block (test-world approximation): gems give Gemstone Powder, mithril/hard stone give Mithril Powder. */
	private static void awardPowder(Player player, BlockState state) {
		MiningData.MiningBlock b = MiningData.block(state);
		if (b == null) return;
		MiningProfile prof = MiningProfile.of(player.getUUID());
		MiningProfile.Powder type = b.gemstone() ? MiningProfile.Powder.GEMSTONE : MiningProfile.Powder.MITHRIL;
		long amount = Math.max(1, Math.round(b.strength() / 50.0 * (1 + prof.powderBuffPercent() / 100.0)));
		prof.addPowder(type, amount);
		if (player instanceof ServerPlayer sp) {
			sp.sendOverlayMessage(Component.literal("§7" + b.name() + " §8| §a+" + amount + " " + type.label + " Powder §8| §7"
					+ prof.powder(type) + " total"));
		}
	}

	private static void showStats(ServerPlayer p) {
		MiningData.MiningBlock ruby = MiningData.gemTable().getFirst();
		MiningData.Speed s = MiningData.speed(p, ruby);
		MiningData.Drill d = MiningData.heldDrill(p);
		p.sendSystemMessage(Component.literal("§6§lMining Stats §7(on gemstones)"));
		p.sendSystemMessage(Component.literal("§7Drill: §f" + (d == null ? "§cnone held" : d.name() + " §8(BP " + d.breakingPower() + ")") + " §a+" + s.drill()));
		p.sendSystemMessage(Component.literal("§7Armor of Divan: §f" + MiningData.divanPieces(p) + "/4 §a+" + s.armor()));
		p.sendSystemMessage(Component.literal("§7HOTM: §a+" + s.hotm() + "§7   Extra: §a+" + s.base()));
		p.sendSystemMessage(Component.literal("§7Mining Speed Boost: " + (s.boostMultiplier() > 1 ? "§aACTIVE x" + s.boostMultiplier() : "§8off")));
		p.sendSystemMessage(Component.literal("§7Total: §6" + Math.round(s.total()) + " Mining Speed"));
		StringBuilder line = new StringBuilder("§7Break ticks: ");
		for (MiningData.MiningBlock b : MiningData.gemTable()) {
			boolean canBreak = d != null && d.breakingPower() >= b.breakingPower();
			int t = MiningData.breakTicks(b.strength(), s.total(), b.special());
			line.append("§f").append(b.name()).append(" ").append(canBreak ? "§a" + t : "§cBP" + b.breakingPower()).append("§8, ");
		}
		p.sendSystemMessage(Component.literal(line.substring(0, line.length() - 4)));
	}

	// ---------------------------------------------------------------- /testroute

	public record PrepareResult(int cobble, int cleared, int blockedByGems) {}

	/**
	 * Makes a route warpable: cobblestone on every waypoint (replacing stone etc. but never gemstones),
	 * a bomb blast of the given radius around each one, and a clear line of sight from the previous waypoint.
	 */
	public static PrepareResult prepareRoute(ServerLevel level, List<BlockPos> route, int radius) {
		Set<BlockPos> waypoints = new HashSet<>(route);
		int cobble = 0, cleared = 0, blocked = 0;

		for (BlockPos p : route) {
			BlockState st = level.getBlockState(p);
			if (!st.is(Blocks.COBBLESTONE) && !isGemstone(st)) {
				level.setBlock(p, Blocks.COBBLESTONE.defaultBlockState(), 3);
				cobble++;
			}
		}
		// Blast a bomb-sized pocket around every waypoint (the cobblestone itself survives).
		for (BlockPos p : route) cleared += explode(level, p, radius);
		for (int i = 0; i < route.size() && route.size() > 1; i++) {
			BlockPos from = route.get(Math.floorMod(i - 1, route.size()));
			BlockPos to = route.get(i);
			Vec3 eye = new Vec3(from.getX() + 0.5, from.getY() + 1 + 1.62, from.getZ() + 0.5);
			Vec3 top = new Vec3(to.getX() + 0.5, to.getY() + 1.0, to.getZ() + 0.5);
			Vec3 step = top.subtract(eye).normalize().scale(0.25);
			int steps = (int) (eye.distanceTo(top) / 0.25);
			Vec3 pt = eye;
			for (int s = 0; s < steps; s++, pt = pt.add(step)) {
				BlockPos q = BlockPos.containing(pt);
				if (q.equals(to) || q.equals(from)) continue;
				BlockState st = level.getBlockState(q);
				if (st.getCollisionShape(level, q).isEmpty() && st.isAir()) continue;
				if (isGemstone(st) || waypoints.contains(q)) { blocked++; break; }
				if (clearable(level, q, waypoints)) {
					level.setBlock(q, Blocks.AIR.defaultBlockState(), 2);
					cleared++;
				}
			}
		}
		return new PrepareResult(cobble, cleared, blocked);
	}

	/** Removes the cobblestone that prepareRoute placed on the waypoints. */
	public static int clearRouteCobble(ServerLevel level, List<BlockPos> route) {
		int n = 0;
		for (BlockPos p : route) {
			if (level.getBlockState(p).is(Blocks.COBBLESTONE)) {
				level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
				n++;
			}
		}
		return n;
	}

	private static boolean clearable(ServerLevel level, BlockPos q, Set<BlockPos> waypoints) {
		BlockState st = level.getBlockState(q);
		return !st.isAir() && !isGemstone(st) && !waypoints.contains(q) && !st.is(Blocks.BEDROCK) && !st.hasBlockEntity();
	}

	// ---------------------------------------------------------------- gemstone respawn

	/** Gemstones in SkyBlock are stained glass and stained glass panes. */
	public static boolean isGemstone(BlockState st) {
		String path = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
		return path.endsWith("_stained_glass") || path.endsWith("_stained_glass_pane");
	}

	private static void tick(MinecraftServer server) {
		if (!active) return;
		int now = server.getTickCount();
		MiningProfile.tickAll(now);
		for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
			if (MiningProfile.of(sp.getUUID()).consumeReady(now)) {
				sp.sendSystemMessage(Component.literal("§6Mining Speed Boost §ais now available!"));
			}
		}
		if (now % 1200 == 0) MiningProfile.save(profilesFile(server));
		while (!regens.isEmpty() && regens.peek().dueTick() <= now) {
			restore(regens.poll());
			dirty = true;
		}
		if (dirty && now % 40 == 0) saveQueue(server);
	}

	/** Restores every queued gemstone immediately. Returns how many blocks were put back. */
	private static int regenAll() {
		int n = 0;
		while (!regens.isEmpty()) if (restore(regens.poll())) n++;
		pending.clear();
		dirty = true;
		return n;
	}

	// The queue is mirrored to disk so a crash or force-close can't lose broken gems.
	private static Path queueFile(MinecraftServer s) {
		return s.getWorldPath(LevelResource.ROOT).resolve("routeminer_gems.dat");
	}

	private static void saveQueue(MinecraftServer s) {
		dirty = false;
		try {
			Path f = queueFile(s);
			if (regens.isEmpty()) {
				Files.deleteIfExists(f);
				return;
			}
			ListTag list = new ListTag();
			for (Regen r : regens) {
				CompoundTag t = new CompoundTag();
				t.putString("dim", r.level().dimension().identifier().toString());
				t.putInt("x", r.pos().getX());
				t.putInt("y", r.pos().getY());
				t.putInt("z", r.pos().getZ());
				t.put("state", NbtUtils.writeBlockState(r.state()));
				list.add(t);
			}
			CompoundTag root = new CompoundTag();
			root.put("gems", list);
			NbtIo.writeCompressed(root, f);
		} catch (IOException e) {
			LOG.warn("Couldn't save gemstone queue", e);
		}
	}

	private static void restoreSaved(MinecraftServer s) {
		Path f = queueFile(s);
		if (!Files.isRegularFile(f)) return;
		try {
			ListTag list = NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap()).getListOrEmpty("gems");
			var blocks = s.registryAccess().lookupOrThrow(Registries.BLOCK);
			int n = 0;
			for (int i = 0; i < list.size(); i++) {
				CompoundTag t = list.getCompoundOrEmpty(i);
				ServerLevel level = s.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(t.getStringOr("dim", "minecraft:overworld"))));
				if (level == null) continue;
				BlockPos pos = new BlockPos(t.getIntOr("x", 0), t.getIntOr("y", 0), t.getIntOr("z", 0));
				if (restore(new Regen(level, pos, NbtUtils.readBlockState(blocks, t.getCompoundOrEmpty("state")), 0))) n++;
			}
			Files.deleteIfExists(f);
			LOG.info("Restored {} gemstones left over from the last session", n);
		} catch (IOException e) {
			LOG.warn("Couldn't read gemstone queue", e);
		}
	}

	private static boolean restore(Regen r) {
		pending.remove(r.pos());
		if (!r.level().getBlockState(r.pos()).isAir()) return false;
		r.level().setBlock(r.pos(), r.state(), 3);
		Vec3 c = Vec3.atCenterOf(r.pos());
		r.level().playSound(null, c.x, c.y, c.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.6f, 1.2f);
		return true;
	}
}
