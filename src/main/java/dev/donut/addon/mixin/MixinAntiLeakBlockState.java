package dev.donut.addon.mixin;

import dev.donut.addon.AntiLeakRender;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class MixinAntiLeakBlockState {
    @ModifyReturnValue(method = "getSeed(Lnet/minecraft/core/BlockPos;)J", at = @At("RETURN"))
    private long donut$constantDeepslateSeed(long original, BlockPos pos) {
        return AntiLeakRender.seed((BlockState) (Object) this, original);
    }
}
