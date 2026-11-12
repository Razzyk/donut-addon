package dev.donut.addon.expansion.movement.mixin;

import dev.donut.addon.expansion.movement.DonutFlightControl;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
public abstract class MixinDonutFlightCrouch {
    @Inject(method = "isShiftKeyDown", at = @At("RETURN"), cancellable = true)
    private void donut$flightCrouch(CallbackInfoReturnable<Boolean> result) {
        if (DonutFlightControl.suppressesCrouch()) result.setReturnValue(false);
    }
}
