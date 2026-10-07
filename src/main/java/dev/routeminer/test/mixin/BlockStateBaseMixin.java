package dev.routeminer.test.mixin;

import dev.routeminer.test.MiningData;
import dev.routeminer.test.TestServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** In the test world, mining blocks break at SkyBlock speeds (runs on both client and server). */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {
	@Inject(method = "getDestroyProgress", at = @At("HEAD"), cancellable = true)
	private void routeminer$skyblockSpeed(Player player, BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
		if (!TestServer.isActive()) return;
		float f = MiningData.destroyProgress(player, (BlockState) (Object) this);
		if (f >= 0) cir.setReturnValue(f);
	}
}
