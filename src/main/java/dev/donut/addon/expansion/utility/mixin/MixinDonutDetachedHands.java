package dev.donut.addon.expansion.utility.mixin;

import dev.donut.addon.expansion.utility.DonutDetachedCamera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class MixinDonutDetachedHands {
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void donut$hideFirstPersonHands(CallbackInfo ci) {
        if (!DonutDetachedCamera.shouldShowHands()) ci.cancel();
    }
}
