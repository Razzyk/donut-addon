package dev.donut.addon.expansion.search.remote;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.ArrayDeque;

/** Tracks remote mob witnesses that have stopped receiving entity updates. */
public final class RemoteFrozenWitnessTracker {
    public record Witness(int entityId, int chunkX, int chunkZ, boolean eligible) {}

    private static final int MAX_TRACKED = 8192;
    private static final int MAX_PENDING = 8192;
    private final ArrayDeque<Activity> inbox = new ArrayDeque<>();
    private boolean overflowed;
    private final Map<Integer, Long> lastActivity = new HashMap<>();
    private final Map<Integer, Long> lastSeen = new HashMap<>();
    private final Map<Integer, Long> lastChunk = new HashMap<>();
    private Set<RemoteActivityLedger.Key> frozen = Set.of();

    /** Called on the client thread after ordered packet callbacks resolve an entity ID. */
    public synchronized boolean offerActivity(int entityId, long nowNanos) {
        if (entityId < 0) return false;
        if (this.inbox.size() >= MAX_PENDING) {
            this.overflowed = true;
            return false;
        }
        this.inbox.addLast(new Activity(entityId, nowNanos));
        return true;
    }

    /** Applies network observations on the client thread. Overflow resets ages to fail closed. */
    public synchronized int drainActivities(int maximum) {
        if (this.overflowed) {
            this.overflowed = false;
            this.lastActivity.clear();
            this.lastSeen.clear();
            this.lastChunk.clear();
            this.frozen = Set.of();
        }
        int drained = 0;
        while (drained < maximum) {
            Activity activity = this.inbox.pollFirst();
            if (activity == null) break;
            this.lastActivity.put(activity.entityId, activity.atNanos);
            drained++;
        }
        return drained;
    }

    /**
     * Builds a remote ring of frozen witness chunks. The caller supplies only non-player,
     * non-vehicle mobs that are alive, awake, and have AI; visible player chunks are omitted.
     */
    public synchronized Set<RemoteActivityLedger.Key> update(Collection<Witness> witnesses,
            int centerX, int centerZ, int ringStart, int viewDistance, long nowNanos,
            long frozenAfterNanos, Set<RemoteActivityLedger.Key> visiblePlayerChunks) {
        if (witnesses == null || visiblePlayerChunks == null) throw new IllegalArgumentException();
        HashSet<Integer> live = new HashSet<>();
        LinkedHashSet<RemoteActivityLedger.Key> next = new LinkedHashSet<>();
        for (Witness witness : witnesses) {
            if (witness == null) continue;
            int id = witness.entityId();
            live.add(id);
            if (!witness.eligible()) continue;
            long chunkKey = pack(witness.chunkX(), witness.chunkZ());
            Long priorChunk = this.lastChunk.put(id, chunkKey);
            if (priorChunk != null && priorChunk.longValue() != chunkKey) this.lastActivity.put(id, nowNanos);
            this.lastSeen.put(id, nowNanos);
            this.lastActivity.putIfAbsent(id, nowNanos);
            long activity = this.lastActivity.get(id);
            int distance = Math.max(Math.abs(witness.chunkX() - centerX), Math.abs(witness.chunkZ() - centerZ));
            RemoteActivityLedger.Key key = new RemoteActivityLedger.Key(witness.chunkX(), witness.chunkZ());
            if (distance >= Math.max(1, ringStart) && distance <= viewDistance
                && nowNanos - activity >= frozenAfterNanos && !visiblePlayerChunks.contains(key)) next.add(key);
        }
        this.lastActivity.keySet().removeIf(id -> !live.contains(id));
        this.lastSeen.keySet().removeIf(id -> !live.contains(id));
        this.lastChunk.keySet().removeIf(id -> !live.contains(id));
        while (this.lastActivity.size() > MAX_TRACKED) {
            Integer oldest = this.lastActivity.keySet().iterator().next();
            this.lastActivity.remove(oldest);
            this.lastSeen.remove(oldest);
            this.lastChunk.remove(oldest);
        }
        this.frozen = Set.copyOf(next);
        return this.frozen;
    }

    public synchronized Set<RemoteActivityLedger.Key> snapshot() { return this.frozen; }

    public synchronized void clear() {
        this.inbox.clear();
        this.overflowed = false;
        this.lastActivity.clear();
        this.lastSeen.clear();
        this.lastChunk.clear();
        this.frozen = Set.of();
    }

    public synchronized int trackedCount() { return this.lastActivity.size(); }
    public synchronized int pendingCount() { return this.inbox.size(); }

    private static long pack(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    private record Activity(int entityId, long atNanos) {}
}
