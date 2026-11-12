package dev.donut.addon.expansion.movement.mixin;

import dev.donut.addon.expansion.movement.DonutFlightControl;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class MixinDonutAirAcceleration {
    @Inject(method = "getFlyingSpeed", at = @At("RETURN"), cancellable = true)
    private void donut$airControl(CallbackInfoReturnable<Float> result) {
        var player = Minecraft.getInstance().player;
        if ((Object) this == player && player != null && DonutFlightControl.controlsLocalMotion()) {
            result.setReturnValue(DonutFlightControl.airAcceleration(player.isSprinting()));
        }
    }
}
