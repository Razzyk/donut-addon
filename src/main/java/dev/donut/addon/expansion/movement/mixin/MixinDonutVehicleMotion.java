package dev.donut.addon.expansion.movement.mixin;

import dev.donut.addon.expansion.movement.VehicleRuntime;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Entity.class)
public abstract class MixinDonutVehicleMotion {
    @ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true)
    private Vec3 donut$rideMotion(Vec3 movement, MoverType type) {
        return VehicleRuntime.steer((Entity) (Object) this, type, movement);
    }
}
