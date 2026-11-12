package dev.donut.addon.mixin;

import dev.donut.addon.modules.ModuleGrimGroundVelocity;
import dev.donut.addon.modules.ModuleDonutNoJumpDelay;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity {
    // The vanilla jump rearm value is assigned once, immediately after a successful ground jump.
    @ModifyConstant(method = "aiStep", constant = @Constant(intValue = 10), require = 1, allow = 1)
    private int donut$chooseJumpCooldown(int vanilla) {
        return (Object) this == Minecraft.getInstance().player
            ? ModuleDonutNoJumpDelay.rearmCooldown(vanilla) : vanilla;
    }

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void donut$freezeTravel(Vec3 input, CallbackInfo ci) {
        if ((Object) this == Minecraft.getInstance().player && ModuleGrimGroundVelocity.shouldFreezeTravel()) {
            ci.cancel();
        }
    }
}
