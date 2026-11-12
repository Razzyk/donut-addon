package dev.donut.addon.expansion.search.seed;

import java.util.HashMap;
import java.util.Map;

/** Source-shaped placement envelope from generated ocean-floor and loaded motion-height evidence. */
final class SeedGroundGate {
    enum Decision { SKIP, CERTAIN, BRANCH }
    @FunctionalInterface interface ModelFloor { int height(int x, int z); }
    @FunctionalInterface interface TallTrees { boolean inArea(int chunkX, int chunkZ); }

    private final Map<Long, int[]> observedMotion;
    private final ModelFloor model;
    private final TallTrees tallTrees;
    private final Map<Long, Boolean> doubtfulChunks = new HashMap<>();
    private final Map<Long, SeedGroundEvidence.HeightRange> ranges = new HashMap<>();

    SeedGroundGate(Map<Long, int[]> observedMotion, ModelFloor model, TallTrees tallTrees) {
        this.observedMotion = Map.copyOf(observedMotion);
        this.model = model;
        this.tallTrees = tallTrees;
    }

    Decision placement(SeedOreRecipes.Recipe recipe, int x, int y, int z) {
        int lower = y - 2 - recipe.verticalReach();
        int half = recipe.horizontalMargin();
        boolean possible = false;
        for (int wx = x - half; wx <= x + half; wx++) {
            for (int wz = z - half; wz <= z + half; wz++) {
                var range = range(wx, wz);
                if (lower <= range.low()) return Decision.CERTAIN;
                if (lower <= range.high()) possible = true;
            }
        }
        return possible ? Decision.BRANCH : Decision.SKIP;
    }

    private SeedGroundEvidence.HeightRange range(int x, int z) {
        long position = SeedScanCore.packBlock(x, 0, z);
        return ranges.computeIfAbsent(position, ignored -> {
            int modelHeight = model.height(x, z);
            long chunk = SeedScanCore.packChunk(x >> 4, z >> 4);
            int[] motion = observedMotion.get(chunk);
            Integer shown = motion == null ? null : motion[(z & 15) * 16 + (x & 15)];
            boolean doubtful = motion != null && doubtful(chunk, motion);
            return SeedGroundEvidence.placementHeight(modelHeight, shown, doubtful,
                tallTrees.inArea(x >> 4, z >> 4));
        });
    }

    private boolean doubtful(long chunk, int[] motion) {
        return doubtfulChunks.computeIfAbsent(chunk, ignored -> {
            int[] modeled = new int[256];
            int chunkX = (int)(chunk >> 32), chunkZ = (int)chunk;
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                modeled[z * 16 + x] = model.height((chunkX << 4) + x, (chunkZ << 4) + z);
            return SeedGroundEvidence.compare(modeled, motion).doubtful();
        });
    }
}
