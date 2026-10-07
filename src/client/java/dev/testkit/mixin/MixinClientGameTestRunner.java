package dev.testkit.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.testkit.harness.SuiteVerdict;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.impl.client.gametest.FabricClientGameTestRunner;

import net.minecraft.client.gui.screens.TitleScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Keeps the suite going past a failing test. See {@link SuiteVerdict} for why.
 *
 * <p>Fabric's loop ({@code lambda$start$0} in fabric-client-gametest-api-v1 5.1.0, read with javap) calls
 * {@code runTest} for each entrypoint inside a try whose only handler rethrows. Wrapping that one call is the
 * whole change: a failure is recorded, the client is taken back to the title screen the way a passing test
 * leaves it (Fabric's own end-of-test check then runs as normal), and the loop moves on. The run still fails, at
 * the end, with every failure listed.
 *
 * <p>This targets a Fabric implementation class, not an API. If a Fabric API update renames the loop, the mixin
 * fails to apply and the client refuses to start (the config sets {@code defaultRequire: 1}) — loudly, which is
 * the point; javap the new runner and move the target.
 */
@Mixin(value = FabricClientGameTestRunner.class, remap = false)
public class MixinClientGameTestRunner {

    @WrapOperation(method = "lambda$start$0", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/fabricmc/fabric/api/client/gametest/v1/FabricClientGameTest;runTest(Lnet/fabricmc/fabric/api/client/gametest/v1/context/ClientGameTestContext;)V"))
    private static void testkit$keepGoing(FabricClientGameTest test, ClientGameTestContext context,
                                          Operation<Void> original) {
        SuiteVerdict.beginTest();
        try {
            original.call(test, context);
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            SuiteVerdict.fail(test.getClass().getSimpleName(), failure);
            // A scenario's own finally has usually disconnected already; this covers a test that threw without
            // leaving its world.
            context.runOnClient(mc -> {
                if (mc.level != null) {
                    mc.disconnectWithProgressScreen();
                }
            });
            context.waitFor(mc -> mc.level == null, 600);
            context.setScreen(TitleScreen::new);
            context.waitTicks(10);
        }
    }

    @Inject(method = "lambda$start$0", remap = false, at = @At("RETURN"))
    private static void testkit$verdict(List<?> tests, CallbackInfo ci) {
        String summary = SuiteVerdict.finish();
        System.out.println(summary);
        if (SuiteVerdict.anyFailed()) {
            throw new AssertionError(summary);
        }
    }
}
