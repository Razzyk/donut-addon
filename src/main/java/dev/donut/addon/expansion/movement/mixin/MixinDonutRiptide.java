package dev.donut.addon.expansion.movement.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.donut.addon.expansion.movement.DonutRiptideBoost;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.TridentItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TridentItem.class)
public abstract class MixinDonutRiptide {
    @WrapOperation(method = "releaseUsing", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/player/Player;push(DDD)V"))
    private void donut$scaleRiptideImpulse(Player player, double x, double y, double z, Operation<Void> push) {
        double factor = DonutRiptideBoost.impulseFactor(player);
        push.call(player, x * factor, y * factor, z * factor);
    }

    @WrapOperation(method = {"use", "releaseUsing"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/player/Player;isInWaterOrRain()Z"))
    private boolean donut$riptideDryCheck(Player player, Operation<Boolean> check) {
        return check.call(player) || DonutRiptideBoost.permitsDryUse(player);
    }
}
