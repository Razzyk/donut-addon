package dev.donut.addon.mixin;

import dev.donut.addon.modules.ModuleGrimGroundVelocity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity {
    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void donut$freezeTravel(Vec3 input, CallbackInfo ci) {
        if ((Object) this == Minecraft.getInstance().player && ModuleGrimGroundVelocity.shouldFreezeTravel()) {
            ci.cancel();
        }
    }
}
