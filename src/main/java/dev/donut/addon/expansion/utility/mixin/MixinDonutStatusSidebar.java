package dev.donut.addon.expansion.utility.mixin;

import dev.donut.addon.expansion.utility.DonutStatusMirror;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Scoreboard.class)
public abstract class MixinDonutStatusSidebar {
    @Inject(method = "setDisplayObjective", at = @At("HEAD"), cancellable = true)
    private void donut$keepLocalSidebar(DisplaySlot slot, Objective objective, CallbackInfo ci) {
        if (DonutStatusMirror.keepSidebar((Scoreboard) (Object) this, slot, objective)) ci.cancel();
    }
}
