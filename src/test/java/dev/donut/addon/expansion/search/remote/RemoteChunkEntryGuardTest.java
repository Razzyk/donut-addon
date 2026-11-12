package dev.donut.addon.expansion.search.remote;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongPredicate;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class RemoteChunkEntryGuardTest {
    @Test void waitsForChunksAndSkipsExistingBaseSignals(@TempDir Path directory) {
        RemoteKnownBaseStore store = new RemoteKnownBaseStore(directory);
        store.select("example.org", "minecraft:overworld");
        RemoteChunkEntryGuard guard = new RemoteChunkEntryGuard();
        long center = ChunkPos.pack(2, -4);
        long knownSignal = ChunkPos.pack(3, -4);
        Set<Long> loaded = new HashSet<>(Set.of(center, knownSignal));
        LongPredicate arrived = loaded::contains;
        LongPredicate flagged = key -> key == knownSignal;

        guard.update(center, arrived, flagged, store);
        assertFalse(guard.waitingForEntryChunk());
        assertTrue(store.isProtected(center));
        assertFalse(store.isProtected(knownSignal));
        assertEquals(23, guard.waitingCount());

        long lateChunk = ChunkPos.pack(2, -3);
        loaded.add(lateChunk);
        guard.update(center, arrived, flagged, store);
        assertTrue(store.isProtected(lateChunk));
        assertEquals(22, guard.waitingCount());
        store.close();
    }

    @Test void defersProtectionWhenTheEntryChunkAlreadyHasEvidence(@TempDir Path directory) {
        RemoteKnownBaseStore store = new RemoteKnownBaseStore(directory);
        store.select("example.org", "minecraft:overworld");
        RemoteChunkEntryGuard guard = new RemoteChunkEntryGuard();
        long center = ChunkPos.pack(0, 0);
        guard.update(center, key -> true, key -> key == center, store);
        assertEquals(0, store.size());
        assertEquals(0, guard.waitingCount());
        store.close();
    }
}
