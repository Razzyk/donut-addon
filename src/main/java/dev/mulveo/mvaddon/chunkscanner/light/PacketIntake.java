package dev.mulveo.mvaddon.chunkscanner.light;

import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;

public final class PacketIntake {
   public static final ConcurrentLinkedQueue<PacketIntake.PendingLight> QUEUE = new ConcurrentLinkedQueue<>();

   private PacketIntake() {
   }

   public static void offer(int var0, int var1, ClientboundLightUpdatePacketData var2) {
      if (var2 != null) {
         QUEUE.offer(new PacketIntake.PendingLight(var0, var1, var2, System.nanoTime()));
      }
   }

   public record PendingLight(int chunkX, int chunkZ, ClientboundLightUpdatePacketData data, long arrivalNanos) {
   }
}
