package dev.routeminer.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the macro run vanilla's hold-left-click logic even when the window isn't focused. */
@Mixin(Minecraft.class)
public interface MinecraftInvoker {
	@Invoker("continueAttack")
	void routeminer$continueAttack(boolean down);
}
