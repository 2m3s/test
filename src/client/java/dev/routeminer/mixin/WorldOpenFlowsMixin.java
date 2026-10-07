package dev.routeminer.mixin;

import com.mojang.serialization.Lifecycle;
import dev.routeminer.test.TestServer;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The downloaded Hypixel map has non-standard world generation settings, which makes vanilla
 * show the "experimental settings" / backup warning on every load. Skip it for the test world only.
 */
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsMixin {
	@Redirect(method = "openWorldCheckWorldStemCompatibility",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/WorldData;worldGenSettingsLifecycle()Lcom/mojang/serialization/Lifecycle;"))
	private Lifecycle routeminer$stableForTestWorld(WorldData data, LevelStorageSource.LevelStorageAccess access,
			WorldStem stem, PackRepository packs, Runnable onCancel) {
		return isTestWorld(access) ? Lifecycle.stable() : data.worldGenSettingsLifecycle();
	}

	@Redirect(method = "openWorldCheckWorldStemCompatibility",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/WorldOptions;isOldCustomizedWorld()Z"))
	private boolean routeminer$notCustomizedForTestWorld(WorldOptions options, LevelStorageSource.LevelStorageAccess access,
			WorldStem stem, PackRepository packs, Runnable onCancel) {
		return !isTestWorld(access) && options.isOldCustomizedWorld();
	}

	private static boolean isTestWorld(LevelStorageSource.LevelStorageAccess access) {
		return TestServer.LEVEL_ID.equals(access.getLevelId());
	}
}
