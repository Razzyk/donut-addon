package dev.donut.addon.expansion.search.remote;

import dev.donut.addon.expansion.search.remote.RemoteLightClassifier.Evidence;
import dev.donut.addon.expansion.search.remote.RemoteLightClassifier.PacketSummary;
import dev.donut.addon.expansion.search.remote.RemoteLightClassifier.Snapshot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Bounded, dimension-scoped merged sky-light evidence for remote chunks. */
public final class RemoteLightTracker {
    public record ChunkState(int chunkX, int chunkZ, Evidence evidence, long updatedNanos,
                             boolean observedUnderground, boolean held) {}

    private static final int CAPACITY = 4096;
    private static final int MAX_PENDING = 4096;
    private final LinkedHashMap<Long, Mutable> chunks = new LinkedHashMap<>(256, 0.75f, true);
    private final LinkedHashMap<Long, Mutable> held = new LinkedHashMap<>(256, 0.75f, true);
    private final ArrayDeque<PendingKey> pending = new ArrayDeque<>();
    private final Set<PendingKey> queued = new HashSet<>();

    public synchronized void observe(int chunkX, int chunkZ, PacketSummary packet, long nowNanos, boolean underground) {
        long key = pack(chunkX, chunkZ);
        if (underground || this.held.containsKey(key)) this.update(this.held, key, chunkX, chunkZ, packet, nowNanos, true);
        if (!underground) this.update(this.chunks, key, chunkX, chunkZ, packet, nowNanos, false);
        trim(this.chunks, false);
        trim(this.held, true);
    }

    /** Processes a small caller-selected number of airless checks on the client thread. */
    public synchronized int processPending(int sectionCount, int bottomSectionY,
                                           AirlessAround airlessAround, int maximum) {
        int processed = 0;
        while (processed < maximum && !this.pending.isEmpty()) {
            PendingKey item = this.pending.removeFirst();
            this.queued.remove(item);
            Mutable current = (item.held ? this.held : this.chunks).get(item.key);
            if (current == null) continue;
            current.evidence = RemoteLightClassifier.classify(current.snapshot, sectionCount, bottomSectionY,
                sectionY -> airlessAround.isAirless(current.chunkX, current.chunkZ, sectionY));
            processed++;
        }
        return processed;
    }

    public synchronized List<ChunkState> snapshot(long nowNanos, long maxAgeNanos) {
        return this.snapshotMap(this.chunks, nowNanos, maxAgeNanos, false);
    }

    /** Retains low-height snapshots separately so they cannot be mistaken for ordinary active snapshots. */
    public synchronized List<ChunkState> heldSnapshot(long nowNanos, long maxAgeNanos) {
        return this.snapshotMap(this.held, nowNanos, maxAgeNanos, true);
    }

    /** Includes raw queued base signals, so entry protection cannot race light classification. */
    public synchronized boolean isFlaggedOrHeld(long chunkKey, int sectionCount, int bottomSectionY) {
        return hasBaseSignal(this.chunks.get(chunkKey), sectionCount, bottomSectionY)
            || hasBaseSignal(this.held.get(chunkKey), sectionCount, bottomSectionY);
    }

    /** Drops a newly observed protected chunk after its merged packet facts first form a base signal. */
    public synchronized void removeChunk(long chunkKey) {
        this.chunks.remove(chunkKey);
        this.held.remove(chunkKey);
        for (boolean heldSnapshot : new boolean[]{false, true}) {
            PendingKey key = new PendingKey(chunkKey, heldSnapshot);
            this.queued.remove(key);
            this.pending.remove(key);
        }
    }

    public synchronized void clear() {
        this.chunks.clear();
        this.held.clear();
        this.pending.clear();
        this.queued.clear();
    }

    public synchronized int size() { return this.chunks.size() + this.held.size(); }
    public synchronized int pendingCount() { return this.pending.size(); }

    private static long pack(int x, int z) { return ((long)x & 0xffffffffL) << 32 | ((long)z & 0xffffffffL); }

    private void update(LinkedHashMap<Long, Mutable> target, long key, int x, int z,
                       PacketSummary packet, long nowNanos, boolean heldSnapshot) {
        Mutable current = target.computeIfAbsent(key, ignored -> new Mutable(x, z));
        current.snapshot = RemoteLightClassifier.merge(current.snapshot, packet, nowNanos, heldSnapshot);
        current.updatedNanos = nowNanos;
        PendingKey pendingKey = new PendingKey(key, heldSnapshot);
        if (this.pending.size() < MAX_PENDING && this.queued.add(pendingKey)) this.pending.addLast(pendingKey);
    }

    private void trim(LinkedHashMap<Long, Mutable> target, boolean heldSnapshot) {
        while (target.size() > CAPACITY) {
            Long oldest = target.keySet().iterator().next();
            target.remove(oldest);
            this.queued.remove(new PendingKey(oldest, heldSnapshot));
        }
    }

    private List<ChunkState> snapshotMap(LinkedHashMap<Long, Mutable> source, long nowNanos,
                                         long maxAgeNanos, boolean heldSnapshot) {
        source.entrySet().removeIf(entry -> nowNanos - entry.getValue().updatedNanos > maxAgeNanos);
        ArrayList<ChunkState> result = new ArrayList<>();
        for (Mutable current : source.values()) {
            if (current.evidence != Evidence.NONE) result.add(new ChunkState(current.chunkX, current.chunkZ,
                current.evidence, current.updatedNanos, current.snapshot.observedUnderground(), heldSnapshot));
        }
        return List.copyOf(result);
    }

    private static boolean hasBaseSignal(Mutable current, int sectionCount, int bottomSectionY) {
        if (current == null || current.snapshot == null) return false;
        if (current.evidence != Evidence.NONE) return true;
        return RemoteLightClassifier.classify(current.snapshot, sectionCount, bottomSectionY, sectionY -> false)
            != Evidence.NONE;
    }

    @FunctionalInterface
    public interface AirlessAround { boolean isAirless(int chunkX, int chunkZ, int sectionY); }

    private static final class Mutable {
        private final int chunkX;
        private final int chunkZ;
        private Snapshot snapshot;
        private Evidence evidence = Evidence.NONE;
        private long updatedNanos;
        private Mutable(int chunkX, int chunkZ) { this.chunkX = chunkX; this.chunkZ = chunkZ; }
    }

    private record PendingKey(long key, boolean held) {}
}
