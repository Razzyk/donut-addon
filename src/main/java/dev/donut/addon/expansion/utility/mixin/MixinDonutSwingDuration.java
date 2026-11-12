package dev.donut.addon.expansion.utility.mixin;

import dev.donut.addon.expansion.utility.UtilityRuntime;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.SwingAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class MixinDonutSwingDuration {
    @Inject(method = "getModifiedSwingDuration(Lnet/minecraft/world/item/component/SwingAnimation;)I", at = @At("RETURN"), cancellable = true)
    private void donut$adjustHandAnimation(SwingAnimation animation, CallbackInfoReturnable<Integer> callback) {
        callback.setReturnValue(UtilityRuntime.swingDuration(callback.getReturnValue()));
    }
}
