package dev.routeminer;

import dev.routeminer.mixin.MinecraftInvoker;
import dev.routeminer.test.TestServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

/**
 * Route mining state machine.
 * MINING: aim at and break every matching block in reach of the current waypoint.
 * WARP_PREP / WARP_WAIT: sneak + use the etherwarp item on the next waypoint.
 * ARRIVE: settle and swap back to the mining tool.
 */
public class MinerMacro {
	public enum State { IDLE, MINING, WARP_PREP, WARP_WAIT, ARRIVE }

	private static final int WARP_TIMEOUT_TICKS = 40;
	private static final int MAX_WARP_RETRIES = 3;
	/** Ticks aimed at a target without the crosshair landing on it before skipping it. */
	private static final int STUCK_TICKS = 15;
	/** Ticks of walking toward a gem before checking that we actually got closer. */
	private static final int APPROACH_CHECK_TICKS = 10;
	/** Longest we hold attack on one block without it breaking (2s) before giving up on it. */
	private static final int MAX_MINING_TICKS = 40;
	/** How far past reach a gem can be for us to walk toward it. */
	private static final double APPROACH = 1.5;
	/** Max horizontal distance to walk away from where we landed on the waypoint. */
	private static final double MAX_DRIFT = 1.2;

	private static final Pattern COOLDOWN = Pattern.compile("cooldown (?:for |\\()?(\\d+)s");
	/** If chat never says the ability is back, try again after the standard 120s cooldown. */
	private static final long ABILITY_FALLBACK_MS = 121_000;
	private static final long ABILITY_RETRY_MS = 3_000;
	private long abilityReadyAt; // 0 = assume ready
	private int blocksMined;
	private long startedAt;

	/** Read by the mouse mixin: ignore real mouse movement while the macro is aiming. */
	public static volatile boolean mouseLocked;

	private final Config cfg;
	private final Route route;
	private final BlockFilter filter;

	private State state = State.IDLE;
	private int index;          // waypoint we are standing on
	private int ticks;          // ticks spent in the current state
	private int emptyTicks;
	private int warpRetries;
	private int targetTicks;
	private BlockPos target;
	private BlockPos lastMined; // last block we broke, to keep working along the same vein
	private Vec3 aim;
	private final Set<BlockPos> skipped = new HashSet<>();
	private ClientLevel startLevel;
	private Vec3 lastPos;
	private boolean warnedVisibility;
	private boolean savedPauseOnLostFocus = true;
	private Vec3 anchor;   // where we landed on the current waypoint
	private BlockPos approachTarget;
	private Vec3 approachAim;
	private boolean approaching;
	private double approachStartDist;
	private int miningTicks;
	private Vec3 warpAim;  // visible point on the next waypoint's cobble

	// Rotation target, applied per frame in onFrame().
	private float wantYaw, wantPitch;
	private boolean rotating;
	private long lastFrameNanos;

	public MinerMacro(Config cfg, Route route, BlockFilter filter) {
		this.cfg = cfg;
		this.route = route;
		this.filter = filter;
	}

	public State state() { return state; }
	public boolean running() { return state != State.IDLE; }
	public int index() { return index; }
	public BlockPos target() { return target; }
	public int blocksMined() { return blocksMined; }
	public long startedAt() { return startedAt; }
	/** Milliseconds until the pickaxe ability is ready (0 = ready, as far as chat has told us). */
	public long abilityCooldownMs() { return Math.max(0, abilityReadyAt - System.currentTimeMillis()); }

	public void toggle(Minecraft mc) {
		if (running()) stop(mc, "Stopped.");
		else start(mc);
	}

	public void start(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) return;
		if (route.size() == 0) { msg(mc, "§cRoute is empty. Stand on a spot and use /rm add."); return; }
		if (filter.isEmpty()) { msg(mc, "§cNo target blocks. Try /rm blocks preset gemstone."); return; }

		startLevel = mc.level;
		// Keep running when the window loses focus instead of opening the pause menu.
		savedPauseOnLostFocus = mc.options.pauseOnLostFocus;
		mc.options.pauseOnLostFocus = false;
		lastPos = p.position();
		skipped.clear();
		warpRetries = 0;
		// Sneak the whole time: SkyBlock players mine crouched, and it stops us walking off the cobble.
		mc.options.keyShift.setDown(true);
		mouseLocked = cfg.mouseLock;
		startedAt = System.currentTimeMillis();
		blocksMined = 0;
		int here = route.indexAt(p.blockPosition().below());
		if (here >= 0) {
			index = here;
			enter(State.ARRIVE);
		} else {
			// Not on the route: warp to the nearest waypoint first.
			int nearest = 0;
			for (int i = 1; i < route.size(); i++) {
				if (route.get(i).distSqr(p.blockPosition()) < route.get(nearest).distSqr(p.blockPosition())) nearest = i;
			}
			index = nearest - 1;
			beginWarp(mc);
		}
		msg(mc, "§aStarted §7(" + route.size() + " waypoints)");
	}

	public void stop(Minecraft mc, String reason) {
		if (state == State.IDLE) return;
		state = State.IDLE;
		rotating = false;
		target = null;
		stopBreaking(mc);
		mc.options.keyShift.setDown(false);
		mc.options.keyUp.setDown(false);
		mouseLocked = false;
		mc.options.pauseOnLostFocus = savedPauseOnLostFocus;
		msg(mc, "§e" + reason);
	}

	// ---------------------------------------------------------------- tick

	public void tick(Minecraft mc) {
		if (state == State.IDLE) return;
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) { stop(mc, "Disconnected."); return; }
		if (mc.level != startLevel) { stop(mc, "World changed - stopped."); return; }
		// Chat is fine (and so is tabbing out); any other screen stops the macro.
		Screen screen = mc.gui.screen();
		if (screen != null && !isChat(screen)) {
			stop(mc, "A screen opened (" + screen.getClass().getSimpleName() + ") - stopped.");
			return;
		}
		if (state != State.WARP_WAIT && p.position().distanceTo(lastPos) > 5) {
			stop(mc, "Unexpected teleport - stopped.");
			return;
		}
		lastPos = p.position();
		ticks++;
		mc.options.keyShift.setDown(true);

		switch (state) {
			case MINING -> tickMining(mc, p);
			case WARP_PREP -> tickWarpPrep(mc, p);
			case WARP_WAIT -> tickWarpWait(mc, p);
			case ARRIVE -> {
				select(p, cfg.mineSlot);
				anchor = p.position();
				if (ticks >= 4) enter(State.MINING);
			}
			default -> {}
		}
	}

	private void tickMining(Minecraft mc, LocalPlayer p) {
		select(p, cfg.mineSlot);

		if (target != null && !filter.matches(mc.level.getBlockState(target))) {
			lastMined = target;
			blocksMined++;
			target = null;
		}
		if (target != null && !isValid(mc, p, target)) target = null;
		if (target == null) {
			findTarget(mc, p);
			targetTicks = 0;
			miningTicks = 0;
			approaching = false;
			if (target == null && approachTarget != null) {
				// Nothing in reach. If the next waypoint is nearer to what's left, mine it from there.
				if (nextWaypointCloser(p, approachAim)) {
					beginWarp(mc);
					return;
				}
				target = approachTarget;
				aim = approachAim;
				approaching = true;
				approachStartDist = p.getEyePosition().distanceTo(aim);
			}
		}
		if (target == null) {
			stopBreaking(mc);
			mc.options.keyUp.setDown(false);
			rotating = false;
			if (++emptyTicks >= cfg.emptyTicksBeforeWarp && route.size() > 1) beginWarp(mc);
			return;
		}
		emptyTicks = 0;
		targetTicks++;
		lookAt(p, aim);

		// Slightly out of reach: walk (crouched) toward it, but never stray far from the waypoint,
		// and give up quickly if walking isn't getting us closer.
		double dist = p.getEyePosition().distanceTo(aim);
		if (approaching && dist <= reach(p) - 0.3) approaching = false;
		if (approaching) {
			boolean tooFar = anchor != null && horizontal(p.position(), anchor) > MAX_DRIFT;
			boolean noProgress = targetTicks >= APPROACH_CHECK_TICKS && dist > approachStartDist - 0.2;
			if (tooFar || noProgress) {
				giveUp(mc);
				return;
			}
			mc.options.keyUp.setDown(aimError(p) < 15f);
		} else {
			mc.options.keyUp.setDown(false);
		}

		// Only hold attack once the crosshair is actually on the target.
		HitResult hit = mc.hitResult;
		boolean onTarget = hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK
				&& bhr.getBlockPos().equals(target);
		// Drive vanilla's hold-attack logic directly so mining continues while tabbed out or in chat.
		// With a screen open vanilla puts attacking on cooldown every tick; clear it first.
		if (onTarget) mc.missTime = 0;
		((MinecraftInvoker) mc).routeminer$continueAttack(onTarget);

		if (onTarget) {
			targetTicks = 0;
			tryUseAbility(mc, p);
			// No progress for 2s (block still there): give up on it.
			if (++miningTicks > MAX_MINING_TICKS) giveUp(mc);
		} else if (!approaching && targetTicks > STUCK_TICKS) {
			// Aimed at it but something else is in the way.
			giveUp(mc);
		}
	}

	/** Right-clicks the drill to use the pickaxe ability when it's ready (tracked from chat). */
	private void tryUseAbility(Minecraft mc, LocalPlayer p) {
		if (!cfg.autoAbility || System.currentTimeMillis() < abilityReadyAt) return;
		mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		// Until chat confirms (used / on cooldown / available), don't spam right-click.
		abilityReadyAt = System.currentTimeMillis() + ABILITY_RETRY_MS;
	}

	/**
	 * Watches SkyBlock's ability messages:
	 * "You used your Mining Speed Boost Pickaxe Ability!", "This ability is on cooldown for 37s.",
	 * "Mining Speed Boost is now available!".
	 */
	public void onChat(String plain) {
		String s = plain.toLowerCase(Locale.ROOT);
		long now = System.currentTimeMillis();
		if (s.contains("is now available")) {
			abilityReadyAt = 0;
		} else if (s.contains("unlock") && s.contains("hotm")) {
			abilityReadyAt = now + 600_000; // ability not unlocked yet; don't keep right-clicking
		} else if (s.contains("you used your") && s.contains("ability")) {
			abilityReadyAt = now + ABILITY_FALLBACK_MS; // corrected by "is now available"
		} else {
			Matcher m = COOLDOWN.matcher(s);
			if (m.find()) abilityReadyAt = now + Long.parseLong(m.group(1)) * 1000 + 250;
		}
	}

	/** Skips the current target for the rest of this waypoint. */
	private void giveUp(Minecraft mc) {
		if (target != null) skipped.add(target);
		target = null;
		approaching = false;
		mc.options.keyUp.setDown(false);
		stopBreaking(mc);
	}

	/** Reach is always the game's own block interaction range (4.5 in survival). */
	private static double reach(LocalPlayer p) {
		return p.blockInteractionRange();
	}

	/**
	 * Whether walking (crouched) far enough toward the point is possible: it stays within MAX_DRIFT of
	 * where we landed, there's ground to stand on there, and nothing solid at feet or head height.
	 * Rejecting these up front stops the camera flicking at gems we'd immediately give up on.
	 */
	private boolean canWalkToward(Minecraft mc, LocalPlayer p, Vec3 point, double outOfReach) {
		Vec3 flat = new Vec3(point.x - p.getX(), 0, point.z - p.getZ());
		if (flat.lengthSqr() < 1.0e-4) return false; // straight up/down: walking won't help
		Vec3 stand = p.position().add(flat.normalize().scale(outOfReach + 0.3));
		if (anchor != null && horizontal(stand, anchor) > MAX_DRIFT) return false;
		BlockPos feet = BlockPos.containing(stand.x, stand.y + 0.1, stand.z);
		BlockPos ground = BlockPos.containing(stand.x, stand.y - 0.2, stand.z);
		return !mc.level.getBlockState(ground).getCollisionShape(mc.level, ground).isEmpty()
				&& mc.level.getBlockState(feet).getCollisionShape(mc.level, feet).isEmpty()
				&& mc.level.getBlockState(feet.above()).getCollisionShape(mc.level, feet.above()).isEmpty();
	}

	/** True if the next waypoint's standing eye position is clearly closer to the point than we are. */
	private boolean nextWaypointCloser(LocalPlayer p, Vec3 point) {
		if (route.size() < 2) return false;
		BlockPos next = route.get(index + 1);
		Vec3 nextEye = new Vec3(next.getX() + 0.5, next.getY() + 1 + 1.62, next.getZ() + 0.5);
		return nextEye.distanceTo(point) + 0.5 < p.getEyePosition().distanceTo(point);
	}

	private void beginWarp(Minecraft mc) {
		stopBreaking(mc);
		mc.options.keyUp.setDown(false);
		target = null;
		lastMined = null;
		skipped.clear();
		warpRetries = 0;
		warnedVisibility = false;
		warpAim = null;
		enter(State.WARP_PREP);
	}

	private void tickWarpPrep(Minecraft mc, LocalPlayer p) {
		BlockPos next = route.get(index + 1);
		if (mc.level.getBlockState(next).isAir()) {
			stop(mc, "Waypoint " + (Math.floorMod(index + 1, route.size()) + 1) + " at " + next.getX() + " " + next.getY()
					+ " " + next.getZ() + " has no block - place cobblestone there.");
			return;
		}
		select(p, cfg.warpSlot);
		mc.options.keyShift.setDown(true);

		if (ticks == 1 || warpAim == null) warpAim = warpPoint(mc, p, next);
		boolean visible = warpAim != null;
		lookAt(p, visible ? warpAim : new Vec3(next.getX() + 0.5, next.getY() + 0.9, next.getZ() + 0.5));

		if (!visible && !warnedVisibility) {
			msg(mc, "§6Waypoint " + (Math.floorMod(index + 1, route.size()) + 1)
					+ " is not in line of sight from here - etherwarp may fail.");
			warnedVisibility = true;
		}

		// Fire only once the crosshair is really on the cobble, not just near it.
		boolean onCobble = raycast(mc, p, p.getEyePosition().add(p.getViewVector(1f)), 61) instanceof BlockHitResult bhr
				&& bhr.getType() == HitResult.Type.BLOCK && bhr.getBlockPos().equals(next);
		if (ticks >= 4 && p.isShiftKeyDown() && aimError(p) < 1.0f && (onCobble || !visible)) {
			mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
			enter(State.WARP_WAIT);
		}
	}

	private void tickWarpWait(Minecraft mc, LocalPlayer p) {
		BlockPos next = route.get(index + 1);
		if (p.blockPosition().below().distSqr(next) <= 2.25) {
			index = Math.floorMod(index + 1, route.size());
			rotating = false;
			lastPos = p.position();
			enter(State.ARRIVE);
			return;
		}
		if (ticks > WARP_TIMEOUT_TICKS) {
			if (++warpRetries > MAX_WARP_RETRIES) {
				stop(mc, "Etherwarp to waypoint " + (Math.floorMod(index + 1, route.size()) + 1) + " failed - stopped.");
				return;
			}
			lastPos = p.position();
			enter(State.WARP_PREP);
		}
	}

	/**
	 * A point on the waypoint block that a ray from the eyes actually hits: the top face if it can
	 * be seen (inset so we never aim at the edge or above it), otherwise the most open side face.
	 */
	private Vec3 warpPoint(Minecraft mc, LocalPlayer p, BlockPos b) {
		double x = b.getX(), y = b.getY(), z = b.getZ();
		Vec3[] candidates = {
				new Vec3(x + 0.5, y + 0.95, z + 0.5),                                      // top centre
				new Vec3(x + 0.3, y + 0.95, z + 0.5), new Vec3(x + 0.7, y + 0.95, z + 0.5),
				new Vec3(x + 0.5, y + 0.95, z + 0.3), new Vec3(x + 0.5, y + 0.95, z + 0.7),
				new Vec3(x + 0.05, y + 0.6, z + 0.5), new Vec3(x + 0.95, y + 0.6, z + 0.5),   // sides, upper half
				new Vec3(x + 0.5, y + 0.6, z + 0.05), new Vec3(x + 0.5, y + 0.6, z + 0.95),
				new Vec3(x + 0.05, y + 0.3, z + 0.5), new Vec3(x + 0.95, y + 0.3, z + 0.5),   // sides, lower half
				new Vec3(x + 0.5, y + 0.3, z + 0.05), new Vec3(x + 0.5, y + 0.3, z + 0.95),
		};
		for (Vec3 pt : candidates) {
			BlockHitResult hit = raycast(mc, p, pt, 61);
			if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(b)) return pt;
		}
		return null;
	}

	private static double horizontal(Vec3 a, Vec3 b) {
		double dx = a.x - b.x, dz = a.z - b.z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	private void enter(State s) {
		state = s;
		ticks = 0;
		emptyTicks = 0;
	}

	// ---------------------------------------------------------------- targeting

	private boolean isValid(Minecraft mc, LocalPlayer p, BlockPos pos) {
		return filter.matches(mc.level.getBlockState(pos)) && aim != null
				&& p.getEyePosition().distanceTo(aim) <= reach(p) + APPROACH;
	}

	/** Picks the visible matching block that needs the least camera movement. */
	private void findTarget(Minecraft mc, LocalPlayer p) {
		Vec3 eye = p.getEyePosition();
		double reach = reach(p);
		int r = (int) Math.ceil(reach + APPROACH) + 1;
		BlockPos center = BlockPos.containing(eye);
		double bestScore = Double.MAX_VALUE, bestApproach = Double.MAX_VALUE;
		target = null;
		aim = null;
		approachTarget = null;
		approachAim = null;

		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
			BlockState st = mc.level.getBlockState(pos);
			// Never mine the route's own blocks (the cobblestone you etherwarp onto).
			if (!filter.matches(st) || skipped.contains(pos) || route.points.contains(pos)) continue;
			// In the test world we know if the held drill can break it at all (breaking power).
			if (TestServer.isActive() && st.getDestroyProgress(p, mc.level, pos) <= 0) continue;
			Vec3 point = visiblePoint(mc, p, pos, st);
			if (point == null) continue;

			float[] rot = rotationTo(eye, point);
			double angle = Math.abs(Mth.wrapDegrees(rot[0] - p.getYRot())) + Math.abs(rot[1] - p.getXRot());
			// Anything we'd have to walk to ranks after everything already in reach.
			double outOfReach = Math.max(0, eye.distanceTo(point) - reach);
			// Prefer the block next to the one we just broke so a vein is cleared in order,
			// falling back to the smallest camera turn when starting a new vein.
			double score = lastMined != null
					? lastMined.distSqr(pos) * 8 + angle * 0.3 + eye.distanceTo(point) * 2
					: angle + eye.distanceTo(point) * 4;
			if (outOfReach > 0) {
				if (outOfReach < bestApproach && canWalkToward(mc, p, point, outOfReach)) {
					bestApproach = outOfReach;
					approachTarget = pos.immutable();
					approachAim = point;
				}
			} else if (score < bestScore) {
				bestScore = score;
				target = pos.immutable();
				aim = point;
			}
		}
	}

	/** A point on the block that can be seen from the eyes and is in reach, or null. */
	private Vec3 visiblePoint(Minecraft mc, LocalPlayer p, BlockPos pos, BlockState st) {
		VoxelShape shape = st.getShape(mc.level, pos);
		AABB box = shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
		Vec3 c = box.getCenter();
		double hx = box.getXsize() / 2 - 0.05, hy = box.getYsize() / 2 - 0.05, hz = box.getZsize() / 2 - 0.05;
		Vec3[] candidates = {
				c,
				c.add(0, hy, 0), c.add(0, -hy, 0),
				c.add(hx, 0, 0), c.add(-hx, 0, 0),
				c.add(0, 0, hz), c.add(0, 0, -hz),
		};
		Vec3 eye = p.getEyePosition();
		double max = reach(p) + APPROACH;
		for (Vec3 pt : candidates) {
			if (eye.distanceTo(pt) > max) continue;
			BlockHitResult hit = raycast(mc, p, pt, max + 1);
			if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)
					&& hit.getLocation().distanceTo(eye) <= max) {
				return pt;
			}
		}
		return null;
	}

	private static BlockHitResult raycast(Minecraft mc, LocalPlayer p, Vec3 toward, double range) {
		Vec3 eye = p.getEyePosition();
		Vec3 end = eye.add(toward.subtract(eye).normalize().scale(range));
		return mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
	}

	// ---------------------------------------------------------------- rotation

	private void lookAt(LocalPlayer p, Vec3 point) {
		float[] rot = rotationTo(p.getEyePosition(), point);
		wantYaw = rot[0];
		wantPitch = rot[1];
		rotating = true;
	}

	private float aimError(LocalPlayer p) {
		return Math.abs(Mth.wrapDegrees(wantYaw - p.getYRot())) + Math.abs(wantPitch - p.getXRot());
	}

	static float[] rotationTo(Vec3 from, Vec3 to) {
		double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
		float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
		float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG);
		return new float[]{yaw, Mth.clamp(pitch, -90f, 90f)};
	}

	/** Called every frame: eases the camera toward the wanted rotation. */
	public void onFrame(Minecraft mc) {
		long now = System.nanoTime();
		float dt = lastFrameNanos == 0 ? 0f : Math.min((now - lastFrameNanos) / 1e9f, 0.1f);
		lastFrameNanos = now;
		LocalPlayer p = mc.player;
		if (!rotating || p == null || dt == 0f) return;

		float dYaw = Mth.wrapDegrees(wantYaw - p.getYRot());
		float dPitch = wantPitch - p.getXRot();
		float dist = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);
		if (dist < 0.05f) return;

		// Ease out near the target, capped at the configured speed.
		float step = Math.min(dist, Math.max(dist * Math.min(1f, dt * 12f), (float) cfg.rotationSpeed * dt * 0.25f));
		step = Math.min(step, (float) cfg.rotationSpeed * dt);
		float k = step / dist;
		// turn() takes mouse units (x0.15) and updates the previous-rotation fields like real mouse input.
		p.turn(dYaw * k / 0.15, dPitch * k / 0.15);
	}

	// ---------------------------------------------------------------- util

	/** Vanilla chat, or a mod's replacement chat screen. */
	private static boolean isChat(Screen s) {
		if (s instanceof ChatScreen) return true;
		for (Class<?> c = s.getClass(); c != null && c != Screen.class; c = c.getSuperclass()) {
			if (c.getSimpleName().toLowerCase().contains("chat")) return true;
		}
		return false;
	}

	private static void stopBreaking(Minecraft mc) {
		mc.options.keyAttack.setDown(false);
		if (mc.gameMode != null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
	}

	private static void select(LocalPlayer p, int slot1to9) {
		int s = Mth.clamp(slot1to9, 1, 9) - 1;
		if (p.getInventory().getSelectedSlot() != s) p.getInventory().setSelectedSlot(s);
	}

	static void msg(Minecraft mc, String text) {
		if (mc.player != null) mc.player.sendSystemMessage(Component.literal("§b[RouteMiner] §r" + text));
	}
}
