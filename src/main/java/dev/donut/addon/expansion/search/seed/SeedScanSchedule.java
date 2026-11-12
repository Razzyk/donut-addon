package dev.donut.addon.expansion.search.seed;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Client-thread change epochs and quiet periods shared by capture and result publication. */
public final class SeedScanSchedule {
    private static final int SETTLE_TICKS = 20;
    private final Map<Long, Long> revisions = new HashMap<>();
    private final Map<Long, Long> quietUntil = new HashMap<>();
    private long serial;

    public long revision(long chunk) { return revisions.getOrDefault(chunk, 0L); }

    /** Applied changes invalidate captured evidence, including captures of neighboring shells. */
    public void changed(int x, int z, long tick) {
        revisions.put(SeedScanCore.packChunk(x, z), ++serial);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (!SeedScanLimits.containsChunk(x + dx, z + dz)) continue;
            quietUntil.merge(SeedScanCore.packChunk(x + dx, z + dz), tick + SETTLE_TICKS, Math::max);
        }
    }

    public boolean settled(long chunk, long tick) { return tick >= quietUntil.getOrDefault(chunk, 0L); }

    public boolean unchanged(Map<Long, Long> captured) {
        for (var entry : captured.entrySet()) if (revision(entry.getKey()) != entry.getValue()) return false;
        return true;
    }

    /** Pruning never reuses an earlier nonzero revision within this run. */
    public void retain(Set<Long> area) {
        revisions.keySet().retainAll(area);
        quietUntil.keySet().retainAll(area);
    }

    public void clear() { revisions.clear(); quietUntil.clear(); serial = 0L; }
}
