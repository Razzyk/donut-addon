package dev.donut.addon.expansion.utility.mixin;

import dev.donut.addon.expansion.utility.DonutBlockSync;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MixinDonutBlockSync {
    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void donut$rememberBeforePrediction(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        DonutBlockSync.beginBreak(Minecraft.getInstance().level, pos);
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void donut$restoreUntilServerReply(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        DonutBlockSync.finishBreak(Minecraft.getInstance().level, pos, cir.getReturnValue());
    }

    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void donut$waitOnConfirmedBlock(BlockPos pos, net.minecraft.core.Direction direction,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (DonutBlockSync.waitingForAnswer(pos)) cir.setReturnValue(false);
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void donut$avoidRepeatedPrediction(BlockPos pos, net.minecraft.core.Direction direction,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (DonutBlockSync.waitingForAnswer(pos)) cir.setReturnValue(false);
    }
}
