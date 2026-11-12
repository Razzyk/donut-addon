package dev.donut.addon.expansion.utility.mixin;

import dev.donut.addon.expansion.utility.DonutSkinWardrobe;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
public abstract class MixinDonutSkinWardrobe {
    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void donut$resolveSelectedSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        AbstractClientPlayer player = (AbstractClientPlayer) (Object) this;
        PlayerSkin replacement = DonutSkinWardrobe.skinFor(player.getUUID(), cir.getReturnValue());
        if (replacement != cir.getReturnValue()) cir.setReturnValue(replacement);
    }
}
