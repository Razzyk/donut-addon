package dev.donut.addon.expansion.utility.mixin;

import dev.donut.addon.expansion.utility.DonutDetachedCamera;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MixinDonutDetachedScroll {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void donut$adjustDetachedSpeed(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (DonutDetachedCamera.wheel(vertical)) ci.cancel();
    }
}
