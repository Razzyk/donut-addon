package dev.donut.addon.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.donut.addon.modules.ModuleGrimGroundVelocity;
import dev.donut.addon.modules.ModuleUnbreakableElytra;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.List;
import java.util.Optional;

@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener {
    @ModifyExpressionValue(method = "handleExplosion", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/game/ClientboundExplodePacket;playerKnockback()Ljava/util/Optional;"))
    private Optional<Vec3> donut$groundedExplosion(Optional<Vec3> knockback) {
        return knockback.isPresent() && ModuleGrimGroundVelocity.cancelExplosionKnockback(knockback.get())
            ? Optional.empty() : knockback;
    }

    @ModifyArg(method = "handleSetEntityData", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/syncher/SynchedEntityData;assignValues(Ljava/util/List;)V"), index = 0)
    private List<SynchedEntityData.DataValue<?>> donut$preserveGlide(
        List<SynchedEntityData.DataValue<?>> values,
        @Local(argsOnly = true) ClientboundSetEntityDataPacket packet
    ) {
        return ModuleUnbreakableElytra.preserveGlideFlag(packet.id(), values);
    }
}
