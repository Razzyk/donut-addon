package dev.donut.addon.expansion.movement.mixin;

import dev.donut.addon.expansion.movement.DonutMountControl;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Mob.class)
public abstract class MixinDonutMountSaddle {
    @Inject(method = "isSaddled", at = @At("RETURN"), cancellable = true)
    private void donut$rideSaddle(CallbackInfoReturnable<Boolean> result) {
        var rider = Minecraft.getInstance().player;
        var vehicle = (Mob) (Object) this;
        if (rider != null && rider.getVehicle() == vehicle && DonutMountControl.saddleFor(vehicle)) {
            result.setReturnValue(true);
        }
    }
}
