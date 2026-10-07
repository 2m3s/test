package dev.testkit.server;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side companion to the client gametests: real fake players, driven by commands.
 *
 * <p>The anticheat test server is a separate process and the harness only reaches it through its
 * console, so anything that needs genuine server-side objects has to live here. Which is most of what
 * a believable opponent is: a real {@link net.minecraft.server.level.ServerPlayer}, real damage sources,
 * real knockback, real attributes.
 *
 * <p>Every command is {@code /testkit …} and takes the player's name first, so a scenario can build
 * an opponent up a property at a time:
 *
 * <pre>
 * /testkit spawn SicoKalebV1dps 2.5 151 0.5
 * /testkit frozen SicoKalebV1dps true
 * /testkit attack SicoKalebV1dps 4.0 20 1.0
 * </pre>
 */
public class TestKitServer implements DedicatedServerModInitializer {

    /** High enough that nothing the world generated can reach it. */
    private static final int ARENA_Y = 150;

    /**
     * Boxes a block may not be placed into, the way spawn protection or a claim plugin refuses one.
     *
     * <p>Nothing else on this server ever says no to a placement: spawn protection is off and nobody is an
     * operator, and adventure mode is no substitute, because it stops the client predicting the placement at
     * all. So without this a scenario could only ever measure a placement that landed — and how a client
     * behaves when the server turns one down (the predicted block, the correction, the acknowledgement) is
     * half of what placement code has to get right.
     *
     * <p>Refused through Fabric's {@code UseBlockCallback} with {@code FAIL}, which stops the server's
     * {@code useItemOn} before the item is used and so before anything is placed; the packet handler still
     * answers with the block updates and the acknowledgement it sends after every click. Only block items are
     * refused — a lever or a chest in the box still works.
     */
    private static final List<net.minecraft.world.phys.AABB> PROTECTED = new ArrayList<>();


    @Override
    public void onInitializeServer() {
        ServerTickEvents.END_SERVER_TICK.register(FakePlayerManager::tick);
        // Set the rules in code rather than through /gamerule. Their ids went snake_case in 26.1.2
        // (fall_damage, not fallDamage), so every camelCase gamerule command in every scenario had been
        // failing silently -- which meant natural regeneration was quietly healing the client back up
        // and a hit counter based on health drops read zero.
        ServerLifecycleEvents.SERVER_STARTED.register(TestKitServer::applyTestRules);
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register(TestKitServer::refuseProtected);
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("testkit")
                        // Deliberately open: this is a local, disposable test server, and being able to
                        // drive an opponent by hand while watching a module is half the point of it.
                        .then(Commands.literal("spawn")
                                .then(arg("name")
                                        .then(Commands.argument("x", FloatArgumentType.floatArg())
                                                .then(Commands.argument("y", FloatArgumentType.floatArg())
                                                        .then(Commands.argument("z", FloatArgumentType.floatArg())
                                                                .executes(TestKitServer::spawn))))))
                        .then(Commands.literal("remove")
                                .then(arg("name").executes(context -> {
                                    FakePlayerManager.remove(name(context));
                                    return say(context, "removed");
                                })))
                        .then(Commands.literal("arena")
                                .executes(context -> buildArena(context, 24))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(4, 64))
                                        .executes(TestKitServer::arena)))
                        .then(Commands.literal("fill")
                                .then(Commands.argument("x1", IntegerArgumentType.integer())
                                        .then(Commands.argument("y1", IntegerArgumentType.integer())
                                                .then(Commands.argument("z1", IntegerArgumentType.integer())
                                                        .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                                .then(Commands.argument("y2", IntegerArgumentType.integer())
                                                                        .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                                                .then(Commands.argument("block", StringArgumentType.greedyString())
                                                                                        .executes(TestKitServer::fill)))))))))
                        // A sentinel a scenario can wait for. Commands are processed in order, so once
                        // this replies every fill sent before it has finished — which is the only
                        // reliable way to know a map is actually built. Waiting a fixed number of ticks
                        // instead is a race: a catch floor is sixteen thousand setBlockAndUpdate calls,
                        // and a scenario that starts measuring early reports the player on generated
                        // terrain with no map anywhere.
                        .then(Commands.literal("ping")
                                .then(Commands.argument("token", StringArgumentType.string())
                                        .executes(context -> say(context, "pong "
                                                + StringArgumentType.getString(context, "token")))))
                        .then(Commands.literal("protect")
                                .then(Commands.argument("x1", IntegerArgumentType.integer())
                                        .then(Commands.argument("y1", IntegerArgumentType.integer())
                                                .then(Commands.argument("z1", IntegerArgumentType.integer())
                                                        .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                                .then(Commands.argument("y2", IntegerArgumentType.integer())
                                                                        .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                                                .executes(TestKitServer::protect))))))))
                        .then(Commands.literal("unprotect").executes(context -> {
                            PROTECTED.clear();
                            return say(context, "unprotected");
                        }))
                        .then(Commands.literal("sweep").executes(TestKitServer::sweep))
                        .then(Commands.literal("report").executes(TestKitServer::report))
                        .then(Commands.literal("clear")
                                .executes(context -> {
                                    FakePlayerManager.clear();
                                    return say(context, "cleared");
                                }))
                        .then(Commands.literal("frozen")
                                .then(arg("name")
                                        .then(Commands.argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                                .executes(context -> with(context, player -> player.setFrozen(
                                                        com.mojang.brigadier.arguments.BoolArgumentType.getBool(context, "value")))))))
                        .then(Commands.literal("health")
                                .then(arg("name")
                                        .then(Commands.argument("hp", FloatArgumentType.floatArg(1, 20))
                                                .executes(context -> with(context, player -> player.setHealth(
                                                        FloatArgumentType.getFloat(context, "hp")))))))
                        .then(Commands.literal("scale")
                                .then(arg("name")
                                        .then(Commands.argument("value", FloatArgumentType.floatArg(0.1f, 10f))
                                                .executes(context -> with(context, player -> player.setScale(
                                                        FloatArgumentType.getFloat(context, "value")))))))
                        .then(Commands.literal("gear")
                                .then(arg("name")
                                        .then(arg("slot")
                                                // greedyString, because an item id contains a colon and
                                                // neither word() nor unquoted string() will read past one.
                                                .then(Commands.argument("item", StringArgumentType.greedyString())
                                                        .executes(TestKitServer::gear)))))
                        .then(Commands.literal("walk")
                                .then(arg("name")
                                        .then(Commands.argument("yaw", FloatArgumentType.floatArg())
                                                .then(Commands.argument("sprint", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                                        .executes(context -> with(context, player -> player.walk(
                                                                FloatArgumentType.getFloat(context, "yaw"),
                                                                com.mojang.brigadier.arguments.BoolArgumentType.getBool(context, "sprint"))))))))
                        .then(Commands.literal("jump")
                                .then(arg("name")
                                        .then(Commands.argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                                .executes(context -> with(context, player -> player.setJumping(
                                                        com.mojang.brigadier.arguments.BoolArgumentType.getBool(context, "value")))))))
                        .then(Commands.literal("chase")
                                .then(arg("name")
                                        .then(Commands.argument("stopAt", FloatArgumentType.floatArg(0.5f, 16f))
                                                .executes(context -> with(context, player -> player.chase(
                                                        FloatArgumentType.getFloat(context, "stopAt")))))))
                        .then(Commands.literal("attack")
                                .then(arg("name")
                                        .then(Commands.argument("reach", FloatArgumentType.floatArg(0.5f, 8f))
                                                .then(Commands.argument("delay", IntegerArgumentType.integer(1, 200))
                                                        .then(Commands.argument("damage", FloatArgumentType.floatArg(0f, 20f))
                                                                .executes(context -> with(context, player -> player.attacking(
                                                                        FloatArgumentType.getFloat(context, "reach"),
                                                                        IntegerArgumentType.getInteger(context, "delay"),
                                                                        FloatArgumentType.getFloat(context, "damage")))))))))
                        .then(Commands.literal("route")
                                .then(arg("name")
                                        .then(Commands.argument("repeat", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                                .then(arg("positions").executes(TestKitServer::route)))))));
    }

    /** Permanent daylight, no fall damage, no regeneration, no wandering mobs. */
    private static void applyTestRules(net.minecraft.server.MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            GameRules rules = level.getGameRules();
            rules.set(GameRules.ADVANCE_TIME, false, server);
            rules.set(GameRules.ADVANCE_WEATHER, false, server);
            rules.set(GameRules.FALL_DAMAGE, false, server);
            rules.set(GameRules.NATURAL_HEALTH_REGENERATION, false, server);
            rules.set(GameRules.IMMEDIATE_RESPAWN, true, server);
            rules.set(GameRules.KEEP_INVENTORY, true, server);
            rules.set(GameRules.SPAWN_MOBS, false, server);
            rules.set(GameRules.SHOW_DEATH_MESSAGES, false, server);
        }
        // The clock is stopped by advance_time above, so a single "time set noon" from the console or a
        // scenario sticks for the whole session.
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        System.out.println("[testkit] test rules applied: permanent noon, no fall damage, "
                + "no regen, no mob spawning");
    }

    /**
     * Build a platform to test on: high in the air, well clear of the world's own terrain, walled with
     * barriers so nothing walks off the edge by accident, and swept of any leftover entities from an
     * earlier run.
     */
    private static int arena(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        return buildArena(context, IntegerArgumentType.getInteger(context, "radius"));
    }

    private static int buildArena(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                                  int radius) {
        ServerLevel level = context.getSource().getLevel();
        int y = ARENA_Y;

        // Sweep first, and thoroughly: leftover opponents from a previous run wander over and attack
        // whoever is testing, and a fake player from a previous run is a real player in the world save.
        sweep(context);

        BlockState floor = Blocks.SMOOTH_STONE.defaultBlockState();
        BlockState barrier = Blocks.BARRIER.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                boolean edge = Math.abs(x) == radius || Math.abs(z) == radius;
                level.setBlockAndUpdate(cursor.set(x, y, z), floor);
                for (int dy = 1; dy <= 4; dy++) {
                    level.setBlockAndUpdate(cursor.set(x, y + dy, z), edge ? barrier : air);
                }
            }
        }
        // Only if a player ran it. From the console getPlayer() is null, and the resulting NPE aborted
        // the command halfway -- so the platform was never built and everything in the scenario fell to
        // the terrain below, which looked like a module losing its target.
        net.minecraft.server.level.ServerPlayer caller = context.getSource().getPlayer();
        if (caller != null) {
            caller.teleportTo(0.5, y + 1, 0.5);
        }
        return say(context, "arena built at y=" + y + ", radius " + radius + ", barrier walls, swept");
    }

    /**
     * Fill a box, loading chunks as it goes.
     *
     * <p>Vanilla {@code /fill} silently does nothing in chunks the server has not loaded — it reports
     * "No blocks were filled" and carries on — so a scenario builds its walkway into a void and the
     * player spawns already falling. Force-loading first mostly works and intermittently does not; one
     * run in three still started the player at y=98 with no pad under them. {@code setBlockAndUpdate}
     * goes through the chunk source, which loads what it needs, so this cannot half-build.
     */
    private static int fill(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        int x1 = IntegerArgumentType.getInteger(context, "x1");
        int y1 = IntegerArgumentType.getInteger(context, "y1");
        int z1 = IntegerArgumentType.getInteger(context, "z1");
        int x2 = IntegerArgumentType.getInteger(context, "x2");
        int y2 = IntegerArgumentType.getInteger(context, "y2");
        int z2 = IntegerArgumentType.getInteger(context, "z2");
        String id = StringArgumentType.getString(context, "block").trim();
        BlockState state = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id)).defaultBlockState();

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int count = 0;
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    level.setBlockAndUpdate(cursor.set(x, y, z), state);
                    count++;
                }
            }
        }
        return say(context, "filled " + count + " block(s) with " + id);
    }

    /**
     * Remove everything that is not a real player: mobs, items, any fake player still logged in from an
     * earlier run — and any protected box.
     *
     * <p>Fake players are real players, so they persist in the world save and come back on the next
     * start; leftover mobs wander over and attack whoever is testing. Both have to go before a scenario
     * measures anything, or the thing under test is sharing the world with the debris of every run
     * before it.
     */
    private static int sweep(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        int removed = 0;
        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
            // Snapshot first. Discarding while iterating the level's live entity list throws, and a
            // command that throws halfway prints nothing at all -- which is exactly how this went
            // unnoticed while the arena silently never got built.
            List<net.minecraft.world.entity.Entity> present = new ArrayList<>();
            for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                present.add(entity);
            }
            for (net.minecraft.world.entity.Entity entity : present) {
                if (entity instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                    // Ours are the ones named by the convention; a real tester is never called that. It must match
                    // TestEnemy's prefix exactly: it once looked for "Bot" while every bot was called SicoKaleb…,
                    // so no leftover bot was ever swept by name.
                    if (serverPlayer.getGameProfile().name().startsWith("SicoKaleb")) {
                        serverPlayer.connection.disconnect(Component.literal("swept"));
                        removed++;
                    }
                    continue;
                }
                entity.discard();
                removed++;
            }
        }
        FakePlayerManager.clear();
        // A protected box outliving its scenario would refuse the next one's placements for no reason anybody
        // reading that scenario could see.
        PROTECTED.clear();
        return say(context, "swept " + removed + " entit(ies)");
    }

    private static int protect(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        BlockPos from = new BlockPos(IntegerArgumentType.getInteger(context, "x1"),
                IntegerArgumentType.getInteger(context, "y1"), IntegerArgumentType.getInteger(context, "z1"));
        BlockPos to = new BlockPos(IntegerArgumentType.getInteger(context, "x2"),
                IntegerArgumentType.getInteger(context, "y2"), IntegerArgumentType.getInteger(context, "z2"));
        PROTECTED.add(net.minecraft.world.phys.AABB.encapsulatingFullBlocks(from, to));
        return say(context, "protected " + from.toShortString() + " to " + to.toShortString());
    }

    /** Refuse a block placed into a protected box. Anything else — a lever, a chest — is left alone. */
    private static net.minecraft.world.InteractionResult refuseProtected(
            net.minecraft.world.entity.player.Player player, net.minecraft.world.level.Level level,
            net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
        if (level.isClientSide() || PROTECTED.isEmpty()
                || !(player.getItemInHand(hand).getItem() instanceof net.minecraft.world.item.BlockItem)) {
            return net.minecraft.world.InteractionResult.PASS;
        }
        BlockPos cell = hit.getBlockPos().relative(hit.getDirection());
        for (net.minecraft.world.phys.AABB box : PROTECTED) {
            if (box.contains(cell.getCenter())) {
                return net.minecraft.world.InteractionResult.FAIL;
            }
        }
        return net.minecraft.world.InteractionResult.PASS;
    }

    /**
     * Dump every entity in the world with its offset from the nearest real player. The first question
     * when a scenario behaves oddly is "what else is in here", and the answer has repeatedly been
     * something left over that nobody knew about.
     */
    private static int report(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        net.minecraft.server.level.ServerPlayer viewer = context.getSource().getPlayer();
        StringBuilder out = new StringBuilder("entity report:");
        int count = 0;
        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
            for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                count++;
                String label = entity.getType().toShortString();
                String named = entity.getCustomName() != null
                        ? entity.getCustomName().getString()
                        : (entity instanceof net.minecraft.server.level.ServerPlayer p
                                ? p.getGameProfile().name() : "");
                String where = viewer == null
                        ? String.format(java.util.Locale.ROOT, "at (%.1f, %.1f, %.1f)",
                                entity.getX(), entity.getY(), entity.getZ())
                        : String.format(java.util.Locale.ROOT,
                                "d=%.2f offset (%+.1f, %+.1f, %+.1f)",
                                entity.distanceTo(viewer),
                                entity.getX() - viewer.getX(),
                                entity.getY() - viewer.getY(),
                                entity.getZ() - viewer.getZ());
                out.append(System.lineSeparator()).append("  ")
                        .append(level.dimension().identifier()).append(' ')
                        .append(label).append(named.isEmpty() ? "" : " '" + named + "'")
                        .append(' ').append(where);
            }
        }
        String message = count == 0 ? "entity report: world is empty" : out.toString();
        System.out.println("[testkit] " + message);
        return say(context, message);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> arg(String name) {
        // string(), not word(): word() stops at a colon, so "minecraft:stick" failed to parse with
        // "Expected whitespace to end one argument, but found trailing data".
        return Commands.argument(name, StringArgumentType.string());
    }

    private static String name(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, "name");
    }

    private static int spawn(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        Vec3 position = new Vec3(
                FloatArgumentType.getFloat(context, "x"),
                FloatArgumentType.getFloat(context, "y"),
                FloatArgumentType.getFloat(context, "z"));
        FakePlayer player = FakePlayer.join(context.getSource().getServer(), level, name(context), position);
        FakePlayerManager.add(player);
        return say(context, "spawned " + player.name() + " at " + position);
    }

    /** {@code /testkit gear <name> <slot> <item>} — slot is head/chest/legs/feet/mainhand/offhand. */
    private static int gear(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        return with(context, player -> {
            String slotName = StringArgumentType.getString(context, "slot");
            String itemId = StringArgumentType.getString(context, "item");
            EquipmentSlot slot = switch (slotName) {
                case "head" -> EquipmentSlot.HEAD;
                case "chest" -> EquipmentSlot.CHEST;
                case "legs" -> EquipmentSlot.LEGS;
                case "feet" -> EquipmentSlot.FEET;
                case "offhand" -> EquipmentSlot.OFFHAND;
                default -> EquipmentSlot.MAINHAND;
            };
            ItemStack stack = itemId.equals("none") || itemId.equals("empty")
                    ? ItemStack.EMPTY
                    : new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId)));
            player.entity().setItemSlot(slot, stack);
        });
    }

    /** {@code /testkit route <name> <repeat> x,y,z;x,y,z;…} */
    private static int route(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        return with(context, player -> {
            boolean repeat = com.mojang.brigadier.arguments.BoolArgumentType.getBool(context, "repeat");
            List<Vec3> positions = new ArrayList<>();
            for (String part : StringArgumentType.getString(context, "positions").split(";")) {
                String[] xyz = part.split(",");
                if (xyz.length == 3) {
                    positions.add(new Vec3(Double.parseDouble(xyz[0]), Double.parseDouble(xyz[1]),
                            Double.parseDouble(xyz[2])));
                }
            }
            player.route(repeat, positions);
        });
    }

    private static int with(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                            java.util.function.Consumer<FakePlayer> action) {
        FakePlayer player = FakePlayerManager.get(name(context));
        if (player == null) {
            return say(context, "no such test player: " + name(context));
        }
        action.accept(player);
        return say(context, "ok");
    }

    private static int say(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal("[testkit] " + message), false);
        return 1;
    }
}
