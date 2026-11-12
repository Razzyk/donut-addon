package dev.donut.addon.expansion.search.remote;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RemoteFrozenWitnessTrackerTest {
    @Test void onlyStaleEligibleRemoteWitnessesAreFrozenAndActivityResetsTheAge() {
        RemoteFrozenWitnessTracker tracker = new RemoteFrozenWitnessTracker();
        var witness = new RemoteFrozenWitnessTracker.Witness(7, 10, 0, true);
        assertTrue(tracker.update(List.of(witness), 0, 0, 4, 20, 1L, 40L, Set.of()).isEmpty());
        assertEquals(Set.of(new RemoteActivityLedger.Key(10, 0)),
            tracker.update(List.of(witness), 0, 0, 4, 20, 42L, 40L, Set.of()));

        assertTrue(tracker.offerActivity(7, 50L));
        assertEquals(1, tracker.drainActivities(8));
        assertTrue(tracker.update(List.of(witness), 0, 0, 4, 20, 80L, 40L, Set.of()).isEmpty());
        assertEquals(Set.of(new RemoteActivityLedger.Key(10, 0)),
            tracker.update(List.of(witness), 0, 0, 4, 20, 91L, 40L, Set.of()));
    }

    @Test void excludesIneligibleVisiblePlayerAndOutOfRingWitnessesAndClearsSession() {
        RemoteFrozenWitnessTracker tracker = new RemoteFrozenWitnessTracker();
        List<RemoteFrozenWitnessTracker.Witness> witnesses = List.of(
            new RemoteFrozenWitnessTracker.Witness(1, 10, 0, true),
            new RemoteFrozenWitnessTracker.Witness(2, 11, 0, true),
            new RemoteFrozenWitnessTracker.Witness(3, 2, 0, true),
            new RemoteFrozenWitnessTracker.Witness(4, 30, 0, true)
        );
        Set<RemoteActivityLedger.Key> players = Set.of(new RemoteActivityLedger.Key(10, 0));
        tracker.update(witnesses, 0, 0, 4, 20, 1L, 0L, players);
        assertEquals(Set.of(new RemoteActivityLedger.Key(11, 0)),
            tracker.update(witnesses, 0, 0, 4, 20, 2L, 0L, players));

        tracker.clear();
        assertTrue(tracker.snapshot().isEmpty());
        assertEquals(0, tracker.trackedCount());
        assertEquals(0, tracker.pendingCount());
    }

    @Test void boundedInboxOverflowResetsAgesBeforeReclassifying() {
        RemoteFrozenWitnessTracker tracker = new RemoteFrozenWitnessTracker();
        var witness = new RemoteFrozenWitnessTracker.Witness(7, 10, 0, true);
        tracker.update(List.of(witness), 0, 0, 4, 20, 1L, 0L, Set.of());
        for (int i = 0; i < 9000; i++) tracker.offerActivity(7, 2L + i);
        tracker.drainActivities(9000);
        assertTrue(tracker.update(List.of(witness), 0, 0, 4, 20, 20_000L, 40_000L, Set.of()).isEmpty());
        assertTrue(tracker.pendingCount() <= 8192);
    }

    @Test void offerAfterClearStartsFreshAndClearIsIdempotent() {
        RemoteFrozenWitnessTracker tracker = new RemoteFrozenWitnessTracker();
        assertTrue(tracker.offerActivity(7, 10L));
        tracker.clear();
        assertEquals(0, tracker.pendingCount());
        assertTrue(tracker.offerActivity(8, 20L));
        assertEquals(1, tracker.pendingCount());
        assertEquals(1, tracker.drainActivities(1));

        var witness = new RemoteFrozenWitnessTracker.Witness(8, 10, 0, true);
        assertTrue(tracker.update(List.of(witness), 0, 0, 4, 20, 40L, 30L, Set.of()).isEmpty());
        tracker.clear();
        tracker.clear();
        assertEquals(0, tracker.pendingCount());
        assertEquals(0, tracker.trackedCount());
        assertTrue(tracker.snapshot().isEmpty());
    }
}
