package dev.donut.addon.expansion.search.growth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

/** Donut's compact loaded-chunk clear/support coverage model and primary selection. */
public final class DonutGrowthIndex {
    public record Source(boolean clears, boolean hole, int weight) {
        public Source { if (weight < 0) throw new IllegalArgumentException("negative evidence weight"); }
    }

    private record Record(boolean clears, int radius, boolean hole, int weight) {}

    private final Map<Long, Record> records = new LinkedHashMap<>();
    private final Map<Long, Integer> clearCoverage = new HashMap<>();
    private final Map<Long, Integer> support = new HashMap<>();
    private final Set<Long> primary = new HashSet<>();

    /** Merge a fresh chunk scan with source-retained evidence, replacing its old coverage. */
    public void update(long key, Source fresh, int simulationDistance) {
        if (fresh == null || simulationDistance < 2 || simulationDistance > 16) throw new IllegalArgumentException();
        Record old = this.removeRecord(key);
        Record merged = new Record(
            fresh.clears() || old != null && old.clears(), simulationDistance,
            fresh.hole() || old != null && old.hole(), Math.max(fresh.weight(), old == null ? 0 : old.weight())
        );
        this.records.put(key, merged);
        this.addCoverage(key, merged);
    }

    public void remove(long key) { this.removeRecord(key); this.primary.remove(key); }

    public void clear() {
        this.records.clear(); this.clearCoverage.clear(); this.support.clear(); this.primary.clear();
    }

    public int recordCount() { return this.records.size(); }

    public void pruneDistant(int playerChunkX, int playerChunkZ, int reach) {
        ArrayList<Long> stale = new ArrayList<>();
        for (long key : this.records.keySet()) {
            if (Math.abs(chunkX(key) - playerChunkX) > reach || Math.abs(chunkZ(key) - playerChunkZ) > reach) stale.add(key);
        }
        for (long key : stale) this.remove(key);
    }

    /** Rebuilds the primary set at the source's twenty-tick cadence. */
    public Set<Long> rebuildPrimary(int sensitivity, boolean holeFilter, int holeDistance, int simulationDistance,
                                    LongPredicate currentlyLoaded) {
        if (sensitivity < 1 || sensitivity > 500 || holeDistance < 0 || holeDistance > 8 ||
            simulationDistance < 2 || simulationDistance > 16 || currentlyLoaded == null) throw new IllegalArgumentException();
        int reach = 2 * simulationDistance + 2;
        HashSet<Long> next = new HashSet<>();
        for (long key : this.records.keySet()) {
            int cx = chunkX(key), cz = chunkZ(key);
            if (!currentlyLoaded.test(key) || this.clearCoverage.containsKey(key)) continue;
            if (holeFilter && this.holeNearby(cx, cz, holeDistance)) continue;
            if (this.support.getOrDefault(key, 0) < sensitivity) continue;
            if (this.coveredSides(cx, cz, reach) >= 3) next.add(key);
        }
        this.primary.clear();
        this.primary.addAll(next);
        return Set.copyOf(this.primary);
    }

    public Set<Long> snapshot() { return Set.copyOf(this.primary); }

    private boolean holeNearby(int cx, int cz, int distance) {
        for (int dx = -distance; dx <= distance; dx++) for (int dz = -distance; dz <= distance; dz++) {
            Record record = this.records.get(pack(cx + dx, cz + dz));
            if (record != null && record.hole()) return true;
        }
        return false;
    }

    private int coveredSides(int cx, int cz, int reach) {
        int sides = 0;
        if (stripCleared(cx, cz, 1, 0, reach)) sides++;
        if (stripCleared(cx, cz, -1, 0, reach)) sides++;
        if (stripCleared(cx, cz, 0, 1, reach)) sides++;
        if (stripCleared(cx, cz, 0, -1, reach)) sides++;
        return sides;
    }

    private boolean stripCleared(int cx, int cz, int dx, int dz, int reach) {
        for (int depth = 1; depth <= reach; depth++) for (int lateral = -reach; lateral <= reach; lateral++) {
            long key = pack(cx + dx * depth + dz * lateral, cz + dz * depth + dx * lateral);
            if (this.clearCoverage.containsKey(key)) return true;
        }
        return false;
    }

    private Record removeRecord(long key) {
        Record old = this.records.remove(key);
        if (old == null) return null;
        if (old.clears()) {
            int cx = chunkX(key), cz = chunkZ(key), reach = 2 * old.radius() + 2;
            for (int dx = -old.radius(); dx <= old.radius(); dx++) for (int dz = -old.radius(); dz <= old.radius(); dz++) {
                subtract(this.clearCoverage, pack(cx + dx, cz + dz), 1);
            }
            for (int dx = -reach; dx <= reach; dx++) for (int dz = -reach; dz <= reach; dz++) {
                subtract(this.support, pack(cx + dx, cz + dz), old.weight());
            }
        }
        return old;
    }

    private void addCoverage(long key, Record record) {
        if (!record.clears()) return;
        int cx = chunkX(key), cz = chunkZ(key), reach = 2 * record.radius() + 2;
        for (int dx = -record.radius(); dx <= record.radius(); dx++) for (int dz = -record.radius(); dz <= record.radius(); dz++) {
            this.clearCoverage.merge(pack(cx + dx, cz + dz), 1, Integer::sum);
        }
        for (int dx = -reach; dx <= reach; dx++) for (int dz = -reach; dz <= reach; dz++) {
            this.support.merge(pack(cx + dx, cz + dz), record.weight(), Integer::sum);
        }
    }

    private static void subtract(Map<Long, Integer> values, long key, int amount) {
        Integer present = values.get(key);
        if (present == null) return;
        if (present <= amount) values.remove(key);
        else values.put(key, present - amount);
    }

    public static long pack(int x, int z) { return ((long) z & 0xffffffffL) << 32 | ((long) x & 0xffffffffL); }
    public static int chunkX(long key) { return (int) key; }
    public static int chunkZ(long key) { return (int) (key >>> 32); }
}
