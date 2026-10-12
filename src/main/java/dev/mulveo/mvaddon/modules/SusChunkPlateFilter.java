package dev.mulveo.mvaddon.modules;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Pure chunk-grid overlap filter used by SusChunkFinder's optional smart adjustment. */
public final class SusChunkPlateFilter {
   private SusChunkPlateFilter() {
   }

   public static Set<Long> findOverlapCores(Set<Long> plates) {
      Set<Long> remaining = new HashSet<>(plates);
      Set<Long> result = new HashSet<>();
      ArrayDeque<Long> queue = new ArrayDeque<>();

      while (!remaining.isEmpty()) {
         long start = remaining.iterator().next();
         remaining.remove(start);
         queue.add(start);
         Set<Long> component = new HashSet<>();

         while (!queue.isEmpty()) {
            long current = queue.removeFirst();
            component.add(current);
            int x = unpackX(current);
            int z = unpackZ(current);

            for (int dx = -1; dx <= 1; dx++) {
               for (int dz = -1; dz <= 1; dz++) {
                  if (dx != 0 || dz != 0) {
                     long neighbour = pack(x + dx, z + dz);
                     if (remaining.remove(neighbour)) queue.addLast(neighbour);
                  }
               }
            }
         }

         int maximumOverlap = 0;
         for (long plate : component) maximumOverlap = Math.max(maximumOverlap, overlapScore(plate, component));

         if (component.size() >= 16 && maximumOverlap >= 10) {
            int cutoff = Math.max(10, (int)Math.ceil(maximumOverlap * 0.8));
            for (long plate : component) {
               if (overlapScore(plate, component) >= cutoff) result.add(plate);
            }
         } else {
            result.addAll(component);
         }
      }

      return result;
   }

   private static int overlapScore(long plate, Set<Long> component) {
      int x = unpackX(plate);
      int z = unpackZ(plate);
      int score = 0;

      for (int dx = -2; dx <= 2; dx++) {
         for (int dz = -2; dz <= 2; dz++) {
            if (component.contains(pack(x + dx, z + dz))) score++;
         }
      }

      return score;
   }

   private static long pack(int x, int z) {
      return x & 0xFFFFFFFFL | (z & 0xFFFFFFFFL) << 32;
   }

   private static int unpackX(long packed) {
      return (int)packed;
   }

   private static int unpackZ(long packed) {
      return (int)(packed >> 32);
   }
}
