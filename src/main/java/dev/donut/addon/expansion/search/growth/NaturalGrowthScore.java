package dev.donut.addon.expansion.search.growth;

/** Pure score and sample-threshold logic for a chunk's growth evidence. */
public final class NaturalGrowthScore {
    private NaturalGrowthScore() {}

    public static int total(int[] counts, boolean[] enabled) {
        if (counts == null || enabled == null || counts.length != enabled.length) {
            throw new IllegalArgumentException("evidence arrays must have matching lengths");
        }
        int total = 0;
        for (int i = 0; i < counts.length; i++) {
            if (enabled[i]) total = Math.addExact(total, counts[i]);
        }
        return total;
    }

    public static boolean meetsThreshold(int[] counts, boolean[] enabled, int minimum) {
        return minimum > 0 && total(counts, enabled) >= minimum;
    }
}
