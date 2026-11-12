package dev.donut.addon.expansion.search.seed;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SeedScanScheduleTest {
    @Test void appliedChangesHoldEveryCandidateWithinTwoChunksForTwentyTicks() {
        var state = new SeedScanSchedule();
        state.changed(4, -7, 100);
        assertFalse(state.settled(SeedScanCore.packChunk(6, -5), 119));
        assertTrue(state.settled(SeedScanCore.packChunk(6, -5), 120));
        assertTrue(state.settled(SeedScanCore.packChunk(7, -5), 100));
        state.changed(5, -6, 115);
        assertFalse(state.settled(SeedScanCore.packChunk(6, -5), 134));
        assertTrue(state.settled(SeedScanCore.packChunk(6, -5), 135));
    }

    @Test void shellChangesAfterCaptureRejectTheEntireWorkerVerdict() {
        var state = new SeedScanSchedule();
        long owner = SeedScanCore.packChunk(1, 1), shell = SeedScanCore.packChunk(2, 1);
        var capture = Map.of(owner, state.revision(owner), shell, state.revision(shell));
        assertTrue(state.unchanged(capture));
        state.changed(2, 1, 20);
        assertFalse(state.unchanged(capture));
    }

    @Test void travelingAwayAndBackCannotReuseAnOldRevision() {
        var state = new SeedScanSchedule();
        long chunk = SeedScanCore.packChunk(-5, -8);
        state.changed(-5, -8, 10);
        long old = state.revision(chunk);
        state.retain(Set.of());
        assertFalse(state.unchanged(Map.of(chunk, old)));
        state.changed(-5, -8, 80);
        assertNotEquals(old, state.revision(chunk));
        assertFalse(state.unchanged(Map.of(chunk, old)));
    }

    @Test void clearingAWorldDropsItsQuietPeriodsAndRevisions() {
        var state = new SeedScanSchedule();
        long chunk = SeedScanCore.packChunk(0, 0);
        state.changed(0, 0, 100);
        state.clear();
        assertTrue(state.settled(chunk, 0));
        assertEquals(0, state.revision(chunk));
    }
}
