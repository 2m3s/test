package dev.routeminer.mixin;

import dev.routeminer.test.TestServer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla waits 5 ticks after each broken block. SkyBlock breaks blocks server-side, so there's
 * no such delay there; drop it in the test world so break times match the SkyBlock formula.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
	@Shadow private int destroyDelay;

	@Inject(method = "continueDestroyBlock", at = @At("HEAD"))
	private void routeminer$noDestroyDelay(BlockPos pos, Direction dir, CallbackInfoReturnable<Boolean> cir) {
		if (TestServer.isActive()) destroyDelay = 0;
	}
}
