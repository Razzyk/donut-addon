package dev.donut.addon.expansion.search.remote;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.LongPredicate;

/** Protects unflagged chunks in the player's newly entered 5-by-5 area after they load. */
public final class RemoteChunkEntryGuard {
    private static final int MAX_WAITING_CHUNKS = 25;
    private final Set<Long> waiting = new LinkedHashSet<>();
    private boolean insideWorld;
    private long playerChunk;
    private boolean waitingForEntryChunk;

    public void reset() {
        this.insideWorld = false;
        this.waitingForEntryChunk = false;
        this.waiting.clear();
    }

    public void update(long playerChunkKey, LongPredicate loaded, LongPredicate flaggedOrHeld,
                       RemoteKnownBaseStore knownBases) {
        if (loaded == null || flaggedOrHeld == null || knownBases == null) return;
        if (!this.insideWorld || playerChunkKey != this.playerChunk) {
            this.insideWorld = true;
            this.playerChunk = playerChunkKey;
            this.waitingForEntryChunk = true;
            this.waiting.clear();
        }

        if (this.waitingForEntryChunk) {
            if (!loaded.test(playerChunkKey)) return;
            this.waitingForEntryChunk = false;
            if (!flaggedOrHeld.test(playerChunkKey)) {
                RemoteKnownBaseStore.forEachInArea(chunkX(playerChunkKey), chunkZ(playerChunkKey), key -> {
                    if (!loaded.test(key)) {
                        if (this.waiting.size() < MAX_WAITING_CHUNKS) this.waiting.add(key);
                    } else if (!flaggedOrHeld.test(key)) {
                        knownBases.protect(key);
                    }
                });
            }
        } else if (!this.waiting.isEmpty()) {
            var iterator = this.waiting.iterator();
            while (iterator.hasNext()) {
                long key = iterator.next();
                if (!loaded.test(key)) continue;
                iterator.remove();
                if (!flaggedOrHeld.test(key)) knownBases.protect(key);
            }
        }
    }

    public int waitingCount() { return this.waiting.size(); }
    public boolean waitingForEntryChunk() { return this.waitingForEntryChunk; }

    private static int chunkX(long key) { return ChunkPosAccess.x(key); }
    private static int chunkZ(long key) { return ChunkPosAccess.z(key); }

    /** Mirrors ChunkPos.pack's low-x, high-z layout without a mutable level dependency. */
    private static final class ChunkPosAccess {
        private static int x(long key) { return (int) key; }
        private static int z(long key) { return (int) (key >> 32); }
    }
}
