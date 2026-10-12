package dev.mulveo.mvaddon.chunkscanner.light;

import java.util.BitSet;
import java.util.List;

/** Applies one packet's sparse block and sky light masks to a retained snapshot. */
public final class LightSnapshotMerger {
   private LightSnapshotMerger() {
   }

   public static void apply(
      ChunkLightSnapshot snapshot,
      BitSet blockInitialized,
      BitSet blockEmpty,
      List<byte[]> blockPayloads,
      BitSet skyInitialized,
      BitSet skyEmpty,
      List<byte[]> skyPayloads,
      long updatedNanos
   ) {
      applyTrack(blockInitialized, blockEmpty, blockPayloads, snapshot.block, snapshot.blockValues, snapshot.blockUpdatedNanos, updatedNanos);
      applyTrack(skyInitialized, skyEmpty, skyPayloads, snapshot.sky, snapshot.skyValues, snapshot.skyUpdatedNanos, updatedNanos);
   }

   private static void applyTrack(
      BitSet initialized,
      BitSet empty,
      List<byte[]> payloads,
      SectionStatus[] statuses,
      NibbleSummary[] summaries,
      long[] updatedNanos,
      long timestamp
   ) {
      int payloadIndex = 0;
      for (int section = 0; section < statuses.length; section++) {
         if (initialized.get(section)) {
            // Payload lists follow ascending set-bit order, even when a payload is malformed.
            byte[] payload = payloadIndex < payloads.size() ? payloads.get(payloadIndex) : null;
            payloadIndex++;
            statuses[section] = LightDataClassifier.classifyPayload(payload);
            summaries[section] = NibbleSummary.fromPayload(payload);
            updatedNanos[section] = timestamp;
         } else if (empty.get(section)) {
            statuses[section] = SectionStatus.EMPTY_MASK;
            summaries[section] = NibbleSummary.emptyMask();
            updatedNanos[section] = timestamp;
         }
      }
   }
}
