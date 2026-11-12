package dev.donut.addon.expansion.movement.mixin;

import dev.donut.addon.expansion.movement.DonutBoatPassage;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractBoat.class)
public abstract class MixinDonutBoatCollision {
    @Inject(method = "canBeCollidedWith", at = @At("RETURN"), cancellable = true)
    private void donut$localBoatCollision(Entity other, CallbackInfoReturnable<Boolean> result) {
        if (DonutBoatPassage.hidesCollision((AbstractBoat) (Object) this)) result.setReturnValue(false);
    }
}
