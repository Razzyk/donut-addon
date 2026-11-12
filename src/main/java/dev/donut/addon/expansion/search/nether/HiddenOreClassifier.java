package dev.donut.addon.expansion.search.nether;

/** Classifies a section whose serialized palette mentions debris but whose actual cells do not. */
public final class HiddenOreClassifier {
    private HiddenOreClassifier() {}

    public static boolean isPaletteOnlyTarget(boolean paletteContainsTarget, int actualTargetCount) {
        if (actualTargetCount < 0) throw new IllegalArgumentException("actualTargetCount must be non-negative");
        return paletteContainsTarget && actualTargetCount == 0;
    }
}
