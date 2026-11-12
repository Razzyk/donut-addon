package dev.donut.addon.expansion.search.seed;

/** The recovered scanner's world-height, coordinate and render-distance bounds. */
public final class SeedScanLimits {
    private SeedScanLimits() {}
    public static boolean supportsHeight(int minimumY, int height) { return minimumY == -64 && height == 384; }
    public static boolean containsChunk(int x, int z) { return x >= -15_625 && x < 15_625 && z >= -15_625 && z < 15_625; }
    public static int effectiveRadius(int requested, int renderDistance) {
        return Math.min(8, Math.max(0, Math.min(requested, renderDistance)));
    }
}
