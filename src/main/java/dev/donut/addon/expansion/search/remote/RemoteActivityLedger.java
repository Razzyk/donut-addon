package dev.donut.addon.expansion.search.remote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, decaying packet-activity summary. Evidence means remote updates, not proof of a player. */
public final class RemoteActivityLedger {
    public enum Kind { LIGHT, BLOCK, SOUND, ENTITY, PARTICLE, CHUNK }
    public record Key(int chunkX, int chunkZ) {}
    public record Entry(Key key, double score, int sourceKinds, long lastSeenNanos, int events) {}

    private static final double[] WEIGHTS = {2.0, 2.0, 0.8, 1.5, 0.8, 1.0};
    private final int capacity;
    private final LinkedHashMap<Key, MutableEntry> entries = new LinkedHashMap<>(16, 0.75f, true);

    public RemoteActivityLedger(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public synchronized void observe(int blockX, int blockZ, Kind kind, long nowNanos) {
        Key key = new Key(blockX >> 4, blockZ >> 4);
        MutableEntry entry = entries.computeIfAbsent(key, ignored -> new MutableEntry());
        decay(entry, nowNanos);
        entry.score = Math.min(50.0, entry.score + WEIGHTS[kind.ordinal()]);
        entry.sourceKinds |= 1 << kind.ordinal();
        entry.lastSeenNanos = nowNanos;
        entry.scoreAtNanos = nowNanos;
        entry.events = Math.min(100_000, entry.events + 1);
        trim();
    }

    public synchronized List<Entry> snapshot(long nowNanos, long maximumAgeNanos) {
        pruneOlderThan(nowNanos, maximumAgeNanos);
        List<Entry> result = new ArrayList<>(entries.size());
        entries.forEach((key, mutable) -> {
            decay(mutable, nowNanos);
            if (mutable.score > 0.05) result.add(new Entry(key, mutable.score, Integer.bitCount(mutable.sourceKinds), mutable.lastSeenNanos, mutable.events));
        });
        return List.copyOf(result);
    }

    public synchronized void pruneOlderThan(long nowNanos, long maximumAgeNanos) {
        entries.entrySet().removeIf(entry -> nowNanos - entry.getValue().lastSeenNanos > maximumAgeNanos);
    }

    public synchronized void clear() { entries.clear(); }
    public synchronized int size() { return entries.size(); }

    private void trim() {
        while (entries.size() > capacity) entries.remove(entries.keySet().iterator().next());
    }

    private static void decay(MutableEntry entry, long nowNanos) {
        if (entry.scoreAtNanos == 0L) return;
        double ageSeconds = Math.max(0.0, (nowNanos - entry.scoreAtNanos) / 1_000_000_000.0);
        entry.score *= Math.exp(-ageSeconds / 45.0);
        entry.scoreAtNanos = nowNanos;
    }

    private static final class MutableEntry {
        private double score;
        private int sourceKinds;
        private long lastSeenNanos;
        private long scoreAtNanos;
        private int events;
    }
}
