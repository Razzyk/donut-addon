package dev.donut.addon.expansion.search.evidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded, Minecraft-free ledger for immutable search observations. */
public final class BlockSignalLedger {
    public enum Source { ENTITY_PACKET, BLOCK_EVENT, SOUND, LIGHT }

    public record Key(int chunkX, int chunkZ, int x, int y, int z) {}

    public record Evidence(Key key, String typeId, String payloadPreview, long firstSeenNanos, long lastSeenNanos,
                           int packetCount, int clueMask) {
        public boolean has(Source source) {
            return (clueMask & (1 << source.ordinal())) != 0;
        }
    }

    private final int capacity;
    private final LinkedHashMap<Key, Evidence> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long worldEpoch;

    public BlockSignalLedger(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public synchronized long worldEpoch() { return worldEpoch; }

    public synchronized void beginWorld() {
        worldEpoch++;
        entries.clear();
    }

    public synchronized Evidence observe(int x, int y, int z, String typeId, Source source, long nowNanos) {
        return observe(x, y, z, typeId, "", source, nowNanos);
    }

    public synchronized Evidence observe(int x, int y, int z, String typeId, String payloadPreview,
                                         Source source, long nowNanos) {
        Objects.requireNonNull(source, "source");
        String normalizedType = typeId == null ? "" : typeId.trim();
        String preview = payloadPreview == null ? "" : payloadPreview.trim();
        Key key = new Key(Math.floorDiv(x, 16), Math.floorDiv(z, 16), x, y, z);
        Evidence previous = entries.get(key);
        long firstSeen = previous == null ? nowNanos : previous.firstSeenNanos();
        int count = previous == null ? 0 : previous.packetCount();
        if (source == Source.ENTITY_PACKET && count < Integer.MAX_VALUE) count++;
        int clues = (previous == null ? 0 : previous.clueMask()) | (1 << source.ordinal());
        if (normalizedType.isEmpty() && previous != null) normalizedType = previous.typeId();
        if (preview.isEmpty() && previous != null) preview = previous.payloadPreview();
        if (preview.length() > 240) preview = preview.substring(0, 240);
        Evidence next = new Evidence(key, normalizedType, preview, firstSeen, nowNanos, count, clues);
        entries.put(key, next);
        trim();
        return next;
    }

    public synchronized int pruneOlderThan(long nowNanos, long maxAgeNanos) {
        if (maxAgeNanos < 0) throw new IllegalArgumentException("max age must be non-negative");
        int removed = 0;
        var iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Evidence evidence = iterator.next();
            if (nowNanos >= evidence.lastSeenNanos() && nowNanos - evidence.lastSeenNanos() > maxAgeNanos) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    public synchronized int removeChunk(int chunkX, int chunkZ) {
        int removed = 0;
        var iterator = entries.keySet().iterator();
        while (iterator.hasNext()) {
            Key key = iterator.next();
            if (key.chunkX() == chunkX && key.chunkZ() == chunkZ) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    public synchronized List<Evidence> snapshot() {
        return List.copyOf(new ArrayList<>(entries.values()));
    }

    public synchronized int size() { return entries.size(); }

    private void trim() {
        while (entries.size() > capacity) {
            Map.Entry<Key, Evidence> eldest = entries.entrySet().iterator().next();
            entries.remove(eldest.getKey());
        }
    }
}
