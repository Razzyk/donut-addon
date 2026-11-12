package dev.donut.addon.expansion.utility.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.donut.addon.expansion.utility.DonutStatusMirror;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerTabOverlay.class)
public abstract class MixinDonutStatusTab {
    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void donut$maskLocalTabName(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        Component actual = cir.getReturnValue();
        Component shown = DonutStatusMirror.tabName(info.getProfile().id(), actual);
        if (shown != actual) cir.setReturnValue(shown);
    }

    @ModifyArg(
        method = "extractTablistScore",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"),
        index = 1
    )
    private Component donut$maskLocalTabScore(Component shown, @Local(argsOnly = true) java.util.UUID player) {
        return DonutStatusMirror.tabScore(player, shown);
    }
}
