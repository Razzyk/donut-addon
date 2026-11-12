package dev.donut.addon.expansion.movement.mixin;

import dev.donut.addon.expansion.movement.DonutFlightControl;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class MixinDonutFlightMedium {
    @Inject(method = {"isInWater", "isInLava", "isUnderWater"}, at = @At("RETURN"), cancellable = true)
    private void donut$dryFlight(CallbackInfoReturnable<Boolean> result) {
        if ((Object) this == Minecraft.getInstance().player && DonutFlightControl.INSTANCE.getRunning()) {
            result.setReturnValue(false);
        }
    }
}
