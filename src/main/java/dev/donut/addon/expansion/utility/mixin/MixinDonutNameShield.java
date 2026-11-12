package dev.donut.addon.expansion.utility.mixin;

import dev.donut.addon.expansion.utility.DonutNameShield;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class MixinDonutNameShield {
    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void donut$aliasPlayerLabel(CallbackInfoReturnable<Component> cir) {
        if (!((Object) this instanceof Player)) return;
        Component original = cir.getReturnValue();
        Component protectedName = DonutNameShield.protectRenderedName(original);
        if (protectedName != original) cir.setReturnValue(protectedName);
    }
}
