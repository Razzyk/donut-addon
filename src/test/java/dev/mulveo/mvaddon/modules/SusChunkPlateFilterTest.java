package dev.mulveo.mvaddon.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SusChunkPlateFilterTest {
   @Test
   void keepsSmallComponentsUnchangedIncludingNegativeCoordinates() {
      Set<Long> plates = Set.of(pack(-7, 3), pack(-6, 4), pack(20, -10));

      assertEquals(plates, SusChunkPlateFilter.findOverlapCores(plates));
   }

   @Test
   void keepsOnlyTheDenseCoreOfLargeOverlappingComponents() {
      Set<Long> denseGrid = new HashSet<>();
      for (int x = 0; x < 10; x++) {
         for (int z = 0; z < 10; z++) denseGrid.add(pack(x, z));
      }
      long separatePlate = pack(30, -4);
      denseGrid.add(separatePlate);

      Set<Long> expected = new HashSet<>();
      for (int x = 1; x <= 8; x++) {
         for (int z = 1; z <= 8; z++) {
            // The four inner corners score 16; the source algorithm's 20-point
            // cutoff keeps the rest of this 10x10 component.
            if ((x != 1 && x != 8) || (z != 1 && z != 8)) expected.add(pack(x, z));
         }
      }
      expected.add(separatePlate);

      assertEquals(61, expected.size());
      assertEquals(expected, SusChunkPlateFilter.findOverlapCores(denseGrid));
   }

   private static long pack(int x, int z) {
      return x & 0xFFFFFFFFL | (z & 0xFFFFFFFFL) << 32;
   }
}
