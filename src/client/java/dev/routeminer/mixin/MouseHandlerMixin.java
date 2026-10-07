package dev.routeminer.mixin;

import dev.routeminer.MinerMacro;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mouse lock: while the macro runs, real mouse movement doesn't turn the camera. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
	private void routeminer$mouseLock(double movementTime, CallbackInfo ci) {
		if (MinerMacro.mouseLocked) ci.cancel();
	}
}
