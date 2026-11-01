package dev.donut.addon.mixin;

import dev.donut.addon.anubis.AnubisEventBridge;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class MixinMacroPackets {
    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void donut$macroBlockApplied(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        AnubisEventBridge.packetApplied(packet);
    }
    @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
    private void donut$macroSectionApplied(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        AnubisEventBridge.packetApplied(packet);
    }
}
