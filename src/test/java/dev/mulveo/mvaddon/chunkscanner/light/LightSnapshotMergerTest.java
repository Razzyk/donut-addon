package dev.mulveo.mvaddon.chunkscanner.light;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class LightSnapshotMergerTest {
   @Test
   void mergesSparseMasksInPacketOrderAndLeavesUnmentionedSectionsAlone() {
      ChunkLightSnapshot snapshot = new ChunkLightSnapshot(4, -2, 4, -4);

      BitSet blockInitialized = bits(1, 3);
      BitSet blockEmpty = bits(2);
      BitSet skyInitialized = bits(0, 1);
      BitSet skyEmpty = bits(1, 4);
      LightSnapshotMerger.apply(
         snapshot,
         blockInitialized,
         blockEmpty,
         List.of(full(), zero()),
         skyInitialized,
         skyEmpty,
         List.of(normal(), full()),
         100L
      );

      assertEquals(SectionStatus.UNIFORM_MAX, snapshot.block[1]);
      assertEquals(SectionStatus.EMPTY_MASK, snapshot.block[2]);
      assertEquals(SectionStatus.ZEROED_PRESENT, snapshot.block[3]);
      assertEquals(SectionStatus.NORMAL, snapshot.sky[0]);
      // Initialized payload wins when both packet masks contain the same section.
      assertEquals(SectionStatus.UNIFORM_MAX, snapshot.sky[1]);
      assertEquals(SectionStatus.EMPTY_MASK, snapshot.sky[4]);
      assertEquals(100L, snapshot.blockUpdatedNanos[3]);
      assertEquals(100L, snapshot.skyUpdatedNanos[1]);

      // A later packet is merged after the first one; unmentioned sections keep their prior state.
      LightSnapshotMerger.apply(
         snapshot,
         bits(1),
         bits(1, 3),
         List.of(zero()),
         bits(2),
         bits(),
         List.of(full()),
         200L
      );

      assertEquals(SectionStatus.ZEROED_PRESENT, snapshot.block[1]);
      assertEquals(SectionStatus.EMPTY_MASK, snapshot.block[3]);
      assertEquals(SectionStatus.EMPTY_MASK, snapshot.block[2]);
      assertEquals(SectionStatus.UNIFORM_MAX, snapshot.sky[2]);
      assertEquals(SectionStatus.NORMAL, snapshot.sky[0]);
      assertEquals(SectionStatus.UNIFORM_MAX, snapshot.sky[1]);
      assertEquals(200L, snapshot.blockUpdatedNanos[1]);
      assertEquals(200L, snapshot.blockUpdatedNanos[3]);
      assertEquals(100L, snapshot.blockUpdatedNanos[2]);
      assertEquals(100L, snapshot.skyUpdatedNanos[0]);
      assertEquals(100L, snapshot.skyUpdatedNanos[1]);
      assertEquals(200L, snapshot.skyUpdatedNanos[2]);
   }

   @Test
   void missingPayloadConsumesItsMaskEntryAndMarksThatSectionUnknown() {
      ChunkLightSnapshot snapshot = new ChunkLightSnapshot(0, 0, 3, 0);
      LightSnapshotMerger.apply(snapshot, bits(1, 2), bits(), List.of(full()), bits(), bits(), List.of(), 9L);

      assertEquals(SectionStatus.UNIFORM_MAX, snapshot.block[1]);
      assertEquals(SectionStatus.UNKNOWN, snapshot.block[2]);
      assertEquals("INVALID (missing nibble payload)", snapshot.blockValues[2].describe());
      assertEquals(9L, snapshot.blockUpdatedNanos[2]);
   }

   private static BitSet bits(int... positions) {
      BitSet result = new BitSet();
      for (int position : positions) result.set(position);
      return result;
   }

   private static byte[] zero() {
      return new byte[2048];
   }

   private static byte[] full() {
      byte[] result = new byte[2048];
      java.util.Arrays.fill(result, (byte)0xFF);
      return result;
   }

   private static byte[] normal() {
      byte[] result = new byte[2048];
      result[0] = 1;
      return result;
   }
}
