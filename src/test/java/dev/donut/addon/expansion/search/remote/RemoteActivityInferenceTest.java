package dev.donut.addon.expansion.search.remote;

import dev.donut.addon.expansion.search.remote.RemoteActivityLedger.Entry;
import dev.donut.addon.expansion.search.remote.RemoteActivityLedger.Key;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemoteActivityInferenceTest {
    @Test void combinesOnlyRemoteRingEvidenceAndKeepsItsPeak() {
        long now = 20_000_000_000L;
        List<Entry> entries = List.of(
            new Entry(new Key(10, 0), 4, 3, now, 4),
            new Entry(new Key(10, 1), 4, 3, now, 4),
            new Entry(new Key(11, 0), 4, 3, now, 4),
            new Entry(new Key(2, 0), 20, 5, now, 10),
            new Entry(new Key(12, 0), 20, 5, now - 60_000_000_000L, 10)
        );
        List<RemoteActivityInference.Hotspot> found = RemoteActivityInference.infer(entries, 0, 0, 2, 20, 0, 8, 2, now, 45_000_000_000L);
        assertFalse(found.isEmpty());
        assertTrue(found.stream().allMatch(entry -> entry.sourceChunks() >= 2));
        assertTrue(found.stream().noneMatch(entry -> entry.key().chunkX() == 2 || entry.key().chunkX() == 12));
    }

    @Test void doesNotReturnAnythingWhenThereIsNoEvidenceInTheRemoteRing() {
        long now = 5_000_000_000L;
        assertTrue(RemoteActivityInference.infer(List.of(new Entry(new Key(3, 0), 10, 4, now, 8)), 0, 0, 3, 20, 0, 1, 1, now, 40_000_000_000L).isEmpty());
    }

    @Test void frozenWitnessChunkIsRemovedAndItsImmediateNeighborsAreDamped() {
        long now = 30_000_000_000L;
        List<Entry> entries = List.of(
            new Entry(new Key(10, 0), 6, 3, now, 6),
            new Entry(new Key(10, 1), 6, 3, now, 6),
            new Entry(new Key(11, 1), 6, 3, now, 6),
            new Entry(new Key(11, 0), 20, 5, now, 10)
        );
        var ordinary = RemoteActivityInference.infer(entries, 0, 0, 1, 20, 0, 0.5, 1, now, 60_000_000_000L);
        var damped = RemoteActivityInference.infer(entries, 0, 0, 1, 20, 0, 0.5, 1, now,
            60_000_000_000L, Set.of(new Key(11, 0)));

        assertTrue(ordinary.stream().anyMatch(row -> row.key().equals(new Key(11, 0))));
        assertTrue(damped.stream().noneMatch(row -> row.key().equals(new Key(11, 0))));
        List<Entry> neighborEvidence = List.of(
            new Entry(new Key(10, 0), 6, 3, now, 6),
            new Entry(new Key(11, 1), 6, 3, now, 6)
        );
        var ordinaryNeighbors = RemoteActivityInference.infer(neighborEvidence, 0, 0, 1, 20, 0, 0.5, 1,
            now, 60_000_000_000L);
        var dampedNeighbors = RemoteActivityInference.infer(neighborEvidence, 0, 0, 1, 20, 0, 0.5, 1,
            now, 60_000_000_000L, Set.of(new Key(11, 0)));
        var ordinaryNeighbor = ordinaryNeighbors.stream().filter(row -> row.key().equals(new Key(10, 0))).findFirst().orElseThrow();
        var dampedNeighbor = dampedNeighbors.stream().filter(row -> row.key().equals(new Key(10, 0))).findFirst().orElseThrow();
        assertTrue(dampedNeighbor.score() < ordinaryNeighbor.score());
    }
}
