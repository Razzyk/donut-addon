package dev.donut.addon.mixin;

import dev.donut.addon.AntiLeakRender;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(BlockStateModelSet.class)
public abstract class MixinAntiLeakBlockModels {
    @ModifyVariable(method = "get(Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;", at = @At("HEAD"), argsOnly = true)
    private BlockState donut$deepslateModelState(BlockState state) {
        return AntiLeakRender.modelState(state);
    }
}
