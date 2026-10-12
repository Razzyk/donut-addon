package dev.donut.addon.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.donut.addon.modules.ModuleGrimGroundVelocity;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener {
    @ModifyExpressionValue(method = "handleExplosion", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/game/ClientboundExplodePacket;playerKnockback()Ljava/util/Optional;"))
    private Optional<Vec3> donut$groundedExplosion(Optional<Vec3> knockback) {
        return knockback.isPresent() && ModuleGrimGroundVelocity.cancelExplosionKnockback(knockback.get())
            ? Optional.empty() : knockback;
    }

}
