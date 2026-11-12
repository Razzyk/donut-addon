package dev.donut.addon.expansion.search.amethyst;

/** Palette evidence is a candidate signal, not proof that a complete geode is hidden. */
public final class GeodePaletteEvidence {
    private GeodePaletteEvidence() {}

    public static boolean candidate(int paletteHasBudOrCluster, int visibleBudOrCluster, int minimumPaletteCells) {
        return paletteHasBudOrCluster >= minimumPaletteCells && visibleBudOrCluster > 0;
    }

    public static boolean shouldInvalidate(boolean chunkOrLightUpdate, boolean blockUpdate, boolean sectionUpdate) {
        return chunkOrLightUpdate || blockUpdate || sectionUpdate;
    }

    public static boolean isGrowthLight(int level) {
        return level == 1 || level == 2 || level == 4 || level == 5;
    }

    public static boolean isLocalMaximum(int center, int... neighbours) {
        if (center <= 0) return false;
        for (int value : neighbours) if (value >= center) return false;
        return true;
    }

    public static boolean isGlowEdge(int currentLight, boolean openCell, int brightestNeighbour,
                                     boolean hasLitOpenNeighbour) {
        return currentLight == 0 && openCell && brightestNeighbour == 4 && hasLitOpenNeighbour;
    }
}
