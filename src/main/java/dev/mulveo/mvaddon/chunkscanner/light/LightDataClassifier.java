package dev.mulveo.mvaddon.chunkscanner.light;

import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;

public final class LightDataClassifier {
   private static final int NIBBLES_PER_SECTION = 2048;

   private LightDataClassifier() {
   }

   public static SectionStatus classifyPayload(byte[] nibbles) {
      if (nibbles != null && nibbles.length == 2048) {
         boolean allZero = true;
         boolean allFf = true;

         for (byte b : nibbles) {
            if (b != 0) {
               allZero = false;
            }

            if (b != -1) {
               allFf = false;
            }

            if (!allZero && !allFf) {
               return SectionStatus.NORMAL;
            }
         }

         if (allZero) {
            return SectionStatus.ZEROED_PRESENT;
         } else {
            return allFf ? SectionStatus.UNIFORM_MAX : SectionStatus.NORMAL;
         }
      } else {
         return SectionStatus.UNKNOWN;
      }
   }

   public static void apply(ClientboundLightUpdatePacketData data, ChunkLightSnapshot snap, long nowNanos) {
      LightSnapshotMerger.apply(
         snap,
         data.blockYMask(), data.emptyBlockYMask(), data.blockUpdates(),
         data.skyYMask(), data.emptySkyYMask(), data.skyUpdates(),
         nowNanos
      );
   }
}
