package dev.mulveo.mvaddon.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.donut.addon.mv.compat.systems.modules.Modules;
import dev.mulveo.mvaddon.modules.TridentUtil;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.TridentItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(TridentItem.class)
public abstract class TridentItemMixin {
    @ModifyExpressionValue(
        method = "use",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;isInWaterOrRain()Z"
        )
    )
    private boolean mvAddon$allowRiptideWithoutWater(boolean original) {
        TridentUtil module = Modules.get().get(TridentUtil.class);
        return original || module != null && module.allowsNoWater();
    }

    @ModifyConstant(method = "releaseUsing", constant = @Constant(intValue = 10))
    private int mvAddon$adjustMinimumCharge(int vanillaMinimum) {
        TridentUtil module = Modules.get().get(TridentUtil.class);
        return module == null ? vanillaMinimum : module.adjustedMinimumCharge(vanillaMinimum);
    }
}
