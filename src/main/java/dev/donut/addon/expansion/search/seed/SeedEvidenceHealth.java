package dev.donut.addon.expansion.search.seed;

import java.util.HashSet;
import java.util.Set;

/** Session-local evidence calibration; repeated rechecks never inflate seed-confidence counts. */
public final class SeedEvidenceHealth {
    private static final int MAX_IDENTITIES = 131_072;
    private record Identity(long owner, byte kind) {}
    public record Update(boolean seedMismatchNotice, boolean hiddenOreNotice, boolean alertsSuppressed) {}
    private final Set<Identity> counted = new HashSet<>();
    private int verified;
    private int mismatched;
    private int hidden;
    private boolean mismatchWarned;
    private boolean hiddenWarned;

    public Update observe(long owner, byte kind, SeedScanCore.Verdict verdict) {
        if (verdict != SeedScanCore.Verdict.CLEAN && verdict != SeedScanCore.Verdict.ALERT &&
            verdict != SeedScanCore.Verdict.MISPREDICTED && verdict != SeedScanCore.Verdict.HIDDEN) {
            return new Update(false, false, mismatchWarned);
        }
        if (counted.size() >= MAX_IDENTITIES || !counted.add(new Identity(owner, kind))) {
            return new Update(false, false, mismatchWarned);
        }
        switch (verdict) {
            case CLEAN, ALERT -> verified++;
            case MISPREDICTED -> mismatched++;
            case HIDDEN -> hidden++;
            default -> throw new AssertionError("Unexpected confidence verdict");
        }
        int comparable = verified + mismatched;
        boolean mismatchNotice = !mismatchWarned && comparable >= 40 && mismatched * 2 > comparable;
        boolean hiddenNotice = !hiddenWarned && hidden >= 10 && hidden > comparable;
        mismatchWarned |= mismatchNotice;
        hiddenWarned |= hiddenNotice;
        return new Update(mismatchNotice, hiddenNotice, mismatchWarned);
    }

    public boolean alertsSuppressed() { return mismatchWarned; }
    public int verifiedCount() { return verified; }
    public int mismatchedCount() { return mismatched; }
    public int hiddenCount() { return hidden; }

    public void reset() {
        counted.clear();
        verified = mismatched = hidden = 0;
        mismatchWarned = hiddenWarned = false;
    }
}
