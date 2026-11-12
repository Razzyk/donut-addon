package dev.donut.addon.expansion.search.nether;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded candidate cache with chunk replacement and reveal invalidation. */
public final class AncientOreSectionIndex {
    public record Candidate(int chunkX, int chunkZ, int sectionY) {
        public long chunkKey() { return chunkKey(chunkX, chunkZ); }
        public static long chunkKey(int chunkX, int chunkZ) { return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL); }
    }

    private final int capacity;
    private final LinkedHashMap<Candidate, Boolean> candidates = new LinkedHashMap<>(16, 0.75f, true);

    public AncientOreSectionIndex(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public synchronized void replaceChunk(int chunkX, int chunkZ, Set<Integer> sectionYs) {
        long key = Candidate.chunkKey(chunkX, chunkZ);
        candidates.keySet().removeIf(candidate -> candidate.chunkKey() == key);
        for (int sectionY : sectionYs) candidates.put(new Candidate(chunkX, chunkZ, sectionY), Boolean.TRUE);
        trim();
    }

    public synchronized boolean reveal(int blockX, int blockY, int blockZ) {
        return candidates.remove(new Candidate(blockX >> 4, blockZ >> 4, blockY >> 4)) != null;
    }

    public synchronized void removeChunk(int chunkX, int chunkZ) {
        long key = Candidate.chunkKey(chunkX, chunkZ);
        candidates.keySet().removeIf(candidate -> candidate.chunkKey() == key);
    }

    public synchronized void retainChunkKeys(Set<Long> liveChunkKeys) {
        candidates.keySet().removeIf(candidate -> !liveChunkKeys.contains(candidate.chunkKey()));
    }

    public synchronized List<Candidate> snapshot() { return List.copyOf(candidates.keySet()); }
    public synchronized int size() { return candidates.size(); }
    public synchronized void clear() { candidates.clear(); }

    private void trim() {
        while (candidates.size() > capacity) {
            Candidate oldest = candidates.keySet().iterator().next();
            candidates.remove(oldest);
        }
    }
}
