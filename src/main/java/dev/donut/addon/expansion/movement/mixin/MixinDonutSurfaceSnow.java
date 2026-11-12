package dev.donut.addon.expansion.movement.mixin;

import dev.donut.addon.expansion.movement.DonutSurfaceWalk;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.PowderSnowBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PowderSnowBlock.class)
public abstract class MixinDonutSurfaceSnow {
    @Inject(method = "canEntityWalkOnPowderSnow", at = @At("RETURN"), cancellable = true)
    private static void donut$powderSnowSupport(Entity entity, CallbackInfoReturnable<Boolean> result) {
        if (!result.getReturnValueZ() && DonutSurfaceWalk.snowSupport(entity)) result.setReturnValue(true);
    }
}
