package dev.donut.addon.mixin;

import dev.donut.addon.modules.ModuleDonutSpeedmine;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(Minecraft.class)
public abstract class MixinDonutSpeedmineInput {
    @ModifyArg(
        method = "handleKeybinds()V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;continueAttack(Z)V"
        ),
        index = 0
    )
    private boolean donut$releaseOneHeldAttackTick(boolean attackHeld) {
        return ModuleDonutSpeedmine.filterHeldAttack(attackHeld);
    }

    @Inject(method = "handleKeybinds()V", at = @At("TAIL"))
    private void donut$restartFreshTargetAfterRelease(CallbackInfo ci) {
        var mc = Minecraft.getInstance();
        if (!ModuleDonutSpeedmine.shouldRestart(mc.options.keyAttack.isDown()) ||
            mc.player == null || mc.level == null || mc.gameMode == null ||
            !mc.mouseHandler.isMouseGrabbed()) {
            return;
        }

        var hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult blockHit) ||
            mc.level.getBlockState(blockHit.getBlockPos()).isAir()) {
            return;
        }

        // This is the ordinary vanilla start path: it sends START and initializes fresh progress.
        if (mc.gameMode.startDestroyBlock(blockHit.getBlockPos(), blockHit.getDirection())) {
            ModuleDonutSpeedmine.onRestartStarted();
        }
    }
}
