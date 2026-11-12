package dev.donut.addon.expansion.movement.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.donut.addon.expansion.movement.DonutSurfaceWalk;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockCollisions.class)
public abstract class MixinDonutSurfaceCollisions {
    @WrapOperation(method = "computeNext", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/world/phys/shapes/CollisionContext;getCollisionShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
    private VoxelShape donut$liquidSurface(CollisionContext context, BlockState state, CollisionGetter level,
                                         BlockPos pos, Operation<VoxelShape> collision) {
        return DonutSurfaceWalk.surfaceShape(level, state, pos, collision.call(context, state, level, pos));
    }
}
