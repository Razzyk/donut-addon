package dev.donut.addon.expansion.search.seed;

import java.util.HashSet;
import java.util.Set;

/** Surface-height calibration is separate from underground cluster confidence. */
public final class SeedGroundEvidence {
    public static final int UNKNOWN = Integer.MIN_VALUE;
    public record Comparison(int columns, int different, boolean doubtful) {}
    public record HeightRange(int low, int high) {}
    private final Set<Long> counted = new HashSet<>();
    private int comparable;
    private int mismatched;
    private boolean checked;

    public static Comparison compare(int[] model, int[] shown) {
        if (model.length != 256 || shown.length != 256) throw new IllegalArgumentException("A chunk has 256 columns");
        int columns = 0, different = 0;
        for (int i = 0; i < 256; i++) {
            if (model[i] == UNKNOWN || shown[i] == UNKNOWN) continue;
            columns++;
            if (Math.abs((long)model[i] - shown[i]) > 2) different++;
        }
        return new Comparison(columns, different, columns >= 64 && different * 256 > 26 * columns);
    }

    /** A one-time terrain warning never disables otherwise useful underground inspection. */
    public boolean observe(long chunk, Comparison evidence) {
        if (evidence.columns() < 64 || !counted.add(chunk)) return false;
        if (counted.size() > 4096) { counted.remove(chunk); return false; }
        comparable++;
        if (evidence.doubtful()) mismatched++;
        if (checked || comparable < 20) return false;
        checked = true;
        return mismatched * 2 > comparable;
    }

    public static HeightRange placementHeight(int model, Integer motionTop, boolean doubtful, boolean tallTrees) {
        int low, high;
        if (motionTop == null) { low = model - 8; high = model + 40; }
        else {
            low = Math.min(model, motionTop);
            high = Math.max(model, motionTop);
            if (model >= 57) high = Math.max(high, model + (tallTrees ? 34 : 20));
        }
        int extra = doubtful ? 8 : 0;
        return new HeightRange(Math.max(Short.MIN_VALUE, low - extra), Math.min(Short.MAX_VALUE, high + extra));
    }

    public void clear() { counted.clear(); comparable = mismatched = 0; checked = false; }
}
