package dev.mulveo.mvaddon.modules;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;

class StorageHitCacheTest {
   @Test void blockStateHitNeedsNoBlockEntityAndSnapshotReplacementRemovesStalePositions() {
      StorageHitCache<Integer, String, String> cache = new StorageHitCache<>();
      cache.replace(-1, Map.of("block-state-only", "chest", "old", "barrel"));
      assertEquals("chest", cache.flattened().get("block-state-only"));
      cache.replace(-1, Map.of("new", "vault"));
      assertEquals(Map.of("new", "vault"), cache.flattened());
      cache.replace(-1, Map.of());
      assertTrue(cache.flattened().isEmpty());
   }

   @Test void updatesUnloadAndWorldResetCannotRetainGhosts() {
      StorageHitCache<Integer, String, String> cache = new StorageHitCache<>();
      cache.replace(1, Map.of("a", "chest"));
      assertEquals("chest", cache.update(1, "a", "vault"));
      assertEquals("vault", cache.flattened().get("a"));
      assertEquals("vault", cache.update(1, "a", null));
      cache.update(2, "b", "chest");
      assertTrue(cache.remove(2));
      assertTrue(cache.flattened().isEmpty());
      cache.update(3, "c", "chest");
      cache.clear();
      assertTrue(cache.flattened().isEmpty());
   }

   @Test void crossChunkChestPairsMergeOnceAndSplitOnUnload() {
      StorageHitCache<Integer, String, String> cache = new StorageHitCache<>();
      cache.update(-1, "left", "chest");
      cache.update(0, "right", "chest");
      var pairs = StorageHitCache.mergePairs(cache.flattened(), "chest", p -> p.equals("left") ? "right" : "left");
      assertEquals(1, pairs.size());
      assertNotEquals(pairs.get(0).first(), pairs.get(0).second());
      cache.remove(0);
      pairs = StorageHitCache.mergePairs(cache.flattened(), "chest", p -> "right");
      assertEquals(List.of(new StorageHitCache.Hit<>("left", "left", "chest")), pairs);
   }
}
