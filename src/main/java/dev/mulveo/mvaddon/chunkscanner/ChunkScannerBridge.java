package dev.mulveo.mvaddon.chunkscanner;

import dev.mulveo.mvaddon.toast.MVToast;
import dev.donut.addon.mv.compat.MeteorClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChunkScannerBridge {
   public static final Logger LOG = LoggerFactory.getLogger("MV ChunkScanner");

   private ChunkScannerBridge() {
   }

   public static void onChunkFound(int chunkX, int chunkZ) {
      if (MeteorClient.mc != null) {
         int x = (chunkX << 4) + 8;
         int z = (chunkZ << 4) + 8;
         MeteorClient.mc.gui.toastManager().addToast(MVToast.chunkAlert("FlaggedChunk", x, 60, z));
      }
   }
}
