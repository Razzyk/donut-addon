package dev.donut.addon.expansion.movement.mixin;

import dev.donut.addon.expansion.movement.DonutMountControl;
import dev.donut.addon.expansion.movement.VehicleRuntime;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.PlayerRideableJumping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
public abstract class MixinDonutMountJump {
    @Inject(method = "getJumpRidingScale", at = @At("RETURN"), cancellable = true)
    private void donut$rideCharge(CallbackInfoReturnable<Float> result) {
        if (DonutMountControl.fullJump()) result.setReturnValue(1f);
    }
    @Inject(method = "jumpableVehicle", at = @At("RETURN"), cancellable = true)
    private void donut$flightMountJump(CallbackInfoReturnable<PlayerRideableJumping> result) {
        if (VehicleRuntime.suppressMountJump()) result.setReturnValue(null);
    }
}
