package dev.donut.addon.mixin;

import dev.donut.addon.modules.ModuleDonutSpeedmine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MixinDonutSpeedmine {
    @Shadow private int destroyDelay;
    @Shadow public abstract void stopDestroyBlock();

    // The third write is the survival completion path, after vanilla predicts and sends STOP.
    @Inject(method = "continueDestroyBlock", at = @At(value = "FIELD", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;destroyDelay:I", opcode = Opcodes.PUTFIELD, ordinal = 2, shift = At.Shift.AFTER))
    private void donut$releaseAfterBreak(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        var mc = Minecraft.getInstance();
        if (ModuleDonutSpeedmine.INSTANCE.getEnabled() && mc.player != null && mc.options.keyAttack.isDown()) {
            stopDestroyBlock();
            // The next vanilla held-attack tick starts the next target, including its START packet.
            destroyDelay = 0;
        }
    }
}
