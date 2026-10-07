package dev.testkit.mixin;

import dev.testkit.harness.Disconnects;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.DisconnectionDetails;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records why the server dropped the client.
 *
 * <p>On the common listener rather than {@code ClientPacketListener} on purpose: it is the one funnel every
 * disconnect passes through, in the configuration phase as well as in play, so a rejection during login — the
 * interesting kind — is caught too.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public class MixinClientCommonPacketListener {

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void testkit$onDisconnect(DisconnectionDetails details, CallbackInfo ci) {
        Disconnects.record(details.reason());
    }
}
