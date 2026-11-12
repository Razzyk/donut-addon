package dev.donut.addon.expansion.movement.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.donut.addon.expansion.movement.VehicleRuntime;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractBoat.class)
public abstract class MixinDonutBoatSteering {
    @ModifyExpressionValue(method = "controlBoat", at = @At(value = "FIELD",
        target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;inputLeft:Z", opcode = Opcodes.GETFIELD))
    private boolean donut$turnLeft(boolean value) {
        return value && !VehicleRuntime.lockBoat((AbstractBoat) (Object) this);
    }
    @ModifyExpressionValue(method = "controlBoat", at = @At(value = "FIELD",
        target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;inputRight:Z", opcode = Opcodes.GETFIELD))
    private boolean donut$turnRight(boolean value) {
        return value && !VehicleRuntime.lockBoat((AbstractBoat) (Object) this);
    }
}
