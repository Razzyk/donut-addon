package dev.donut.addon.expansion.search.seed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Coalesced packet coordinates; a run change rejects late arrivals and discards old overflow. */
public final class SeedObservationInbox {
    public record Change(long chunk, boolean blockChanged, boolean unloaded) {}
    public record Batch(List<Change> changes, boolean overflowed) {}
    private final int capacity;
    private final LinkedHashMap<Long, Change> changes = new LinkedHashMap<>();
    private volatile SeedScanCore.Token token;
    private boolean overflowed;

    public SeedObservationInbox(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Positive capacity required");
        this.capacity = capacity;
    }

    public SeedScanCore.Token token() { return token; }

    public synchronized void begin(SeedScanCore.Token next) {
        token = next;
        changes.clear();
        overflowed = false;
    }

    public synchronized boolean offer(SeedScanCore.Token observed, int x, int z,
                                       boolean blockChanged, boolean unloaded) {
        if (observed == null || !observed.equals(token) || !SeedScanLimits.containsChunk(x, z)) return false;
        long key = SeedScanCore.packChunk(x, z);
        Change before = changes.get(key);
        if (before == null && changes.size() >= capacity) {
            overflowed = true;
            return false;
        }
        boolean loadState = blockChanged && before != null ? before.unloaded() : unloaded;
        changes.put(key, new Change(key, blockChanged || before != null && before.blockChanged(), loadState));
        return true;
    }

    public synchronized Batch drain(int maximum) {
        List<Change> taken = new ArrayList<>();
        var iterator = changes.entrySet().iterator();
        while (maximum-- > 0 && iterator.hasNext()) {
            taken.add(iterator.next().getValue());
            iterator.remove();
        }
        boolean overflow = overflowed;
        overflowed = false;
        return new Batch(List.copyOf(taken), overflow);
    }
}
