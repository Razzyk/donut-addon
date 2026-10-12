package dev.mulveo.mvaddon.mixin;

import dev.mulveo.mvaddon.chunkscanner.light.PacketIntake;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({ClientPacketListener.class})
public abstract class ChunkScannerNetworkMixin {
   @Inject(
      method = {"handleLevelChunkWithLight"},
      at = {@At("HEAD")}
   )
   private void bypass$onChunkData(ClientboundLevelChunkWithLightPacket pkt, CallbackInfo ci) {
      PacketIntake.offer(pkt.x(), pkt.z(), pkt.lightData());
   }

   @Inject(
      method = {"handleLightUpdatePacket"},
      at = {@At("HEAD")}
   )
   private void bypass$onLightUpdate(ClientboundLightUpdatePacket pkt, CallbackInfo ci) {
      PacketIntake.offer(pkt.x(), pkt.z(), pkt.lightData());
   }
}
