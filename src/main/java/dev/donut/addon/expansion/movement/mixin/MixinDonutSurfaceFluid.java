package dev.donut.addon.expansion.movement.mixin;

import dev.donut.addon.expansion.movement.DonutSurfaceWalk;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class MixinDonutSurfaceFluid {
    @Inject(method = "canStandOnFluid", at = @At("RETURN"), cancellable = true)
    private void donut$standOnConfiguredFluid(FluidState state, CallbackInfoReturnable<Boolean> result) {
        if (!result.getReturnValueZ() && DonutSurfaceWalk.fluidSupport((LivingEntity)(Object)this, state)) {
            result.setReturnValue(true);
        }
    }
}
