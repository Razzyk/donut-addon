package dev.donut.addon.expansion.search.remote;

import dev.donut.addon.expansion.search.remote.RemoteLightClassifier.Evidence;
import dev.donut.addon.expansion.search.remote.RemoteLightClassifier.PacketSummary;
import dev.donut.addon.expansion.search.remote.RemoteLightClassifier.Snapshot;
import dev.donut.addon.expansion.search.remote.RemoteLightClassifier.Status;
import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemoteLightClassifierTest {
    @Test void distinguishesZeroMaximumNormalAndMalformedPayloads() {
        byte[] maximum = new byte[2048];
        java.util.Arrays.fill(maximum, (byte) 0xff);
        byte[] mixed = new byte[2048];
        mixed[55] = 1;
        assertEquals(Status.ZEROED_PRESENT, RemoteLightClassifier.classifyPayload(new byte[2048]));
        assertEquals(Status.UNIFORM_MAX, RemoteLightClassifier.classifyPayload(maximum));
        assertEquals(Status.NORMAL, RemoteLightClassifier.classifyPayload(mixed));
        assertEquals(Status.UNKNOWN, RemoteLightClassifier.classifyPayload(new byte[2047]));
        assertEquals(Status.UNKNOWN, RemoteLightClassifier.classifyPayload(null));
    }

    @Test void summarizesSparsePayloadsInAscendingMaskOrderAndMarksNonDarkSections() {
        BitSet present = new BitSet();
        BitSet empty = new BitSet();
        present.set(1);
        present.set(4);
        empty.set(2);
        byte[] zero = new byte[2048];
        byte[] ordinary = new byte[2048];
        ordinary[0] = 3;
        PacketSummary summary = RemoteLightClassifier.summarize(present, empty, List.of(zero, ordinary));
        byte[] statuses = summary.skyStatuses();
        assertEquals(Status.ZEROED_PRESENT, RemoteLightClassifier.decode(statuses[1]));
        assertEquals(Status.EMPTY_MASK, RemoteLightClassifier.decode(statuses[2]));
        assertEquals(Status.NORMAL, RemoteLightClassifier.decode(statuses[4]));
        assertEquals((1 << 2) | (1 << 4), summary.sentNotDarkBits());
    }

    @Test void mergesPartialUpdatesAndPreservesUndergroundObservation() {
        byte[] zeros = new byte[2048];
        BitSet present = new BitSet();
        present.set(1);
        PacketSummary first = RemoteLightClassifier.summarize(present, new BitSet(), List.of(zeros));
        Snapshot earlier = RemoteLightClassifier.merge(null, first, 10L, true);
        PacketSummary later = RemoteLightClassifier.summarize(new BitSet(), new BitSet(), List.of());
        Snapshot merged = RemoteLightClassifier.merge(earlier, later, 20L, false);
        assertEquals(Status.ZEROED_PRESENT, RemoteLightClassifier.decode(merged.skyStatuses()[1]));
        assertEquals(20L, merged.updatedNanos());
        assertTrue(merged.observedUnderground());
    }

    @Test void classifiesLowZeroedSectionsUsingNonDarkAndAirlessEvidence() {
        byte[] statuses = new byte[RemoteLightClassifier.MAX_SECTION_SLOTS];
        java.util.Arrays.fill(statuses, (byte) Status.UNKNOWN.ordinal());
        statuses[1] = (byte) Status.ZEROED_PRESENT.ordinal();
        Snapshot ordinaryBase = new Snapshot(statuses, 0, 1L, false);
        assertEquals(Evidence.BASE_SIGNAL,
            RemoteLightClassifier.classify(ordinaryBase, 24, -4, ignored -> false));
        assertEquals(Evidence.SUSPECT_DARK,
            RemoteLightClassifier.classify(new Snapshot(statuses, 1 << 1, 1L, false), 24, -4, ignored -> false));
        assertEquals(Evidence.SUSPECT_AIRLESS,
            RemoteLightClassifier.classify(ordinaryBase, 24, -4, ignored -> true));

        // Section slot 7 maps to section Y=2, still below the strict Y=62 ceiling. One
        // uncorroborated low section makes the whole chunk ordinary base evidence, even when
        // another low section had a non-dark update.
        statuses[7] = (byte) Status.ZEROED_PRESENT.ordinal();
        assertEquals(Evidence.BASE_SIGNAL,
            RemoteLightClassifier.classify(new Snapshot(statuses, 1 << 1, 1L, false), 24, -4,
                sectionY -> sectionY == -4));

        // Slot 8 maps to section Y=3, whose top is Y=63, so the source tracker excludes it.
        // It cannot cancel the valid dark evidence from the lower section.
        statuses[7] = (byte) Status.UNKNOWN.ordinal();
        statuses[8] = (byte) Status.ZEROED_PRESENT.ordinal();
        assertEquals(Evidence.SUSPECT_DARK,
            RemoteLightClassifier.classify(new Snapshot(statuses, 1 << 1, 1L, false), 24, -4,
                sectionY -> sectionY == -4));
    }

    @Test void trackerBoundsStateAndWorldCleanupRemovesPendingAndClassifiedEntries() {
        RemoteLightTracker tracker = new RemoteLightTracker();
        BitSet present = new BitSet();
        present.set(1);
        PacketSummary summary = RemoteLightClassifier.summarize(present, new BitSet(), List.of(new byte[2048]));
        tracker.observe(7, -2, summary, 100L, false);
        assertEquals(1, tracker.pendingCount());
        assertEquals(1, tracker.processPending(24, -4, (x, z, sectionY) -> false, 1));
        assertEquals(Evidence.BASE_SIGNAL, tracker.snapshot(101L, 1_000L).getFirst().evidence());
        tracker.clear();
        assertEquals(0, tracker.size());
        assertEquals(0, tracker.pendingCount());
        assertTrue(tracker.snapshot(101L, 1_000L).isEmpty());
    }

    @Test void undergroundSnapshotsAreHeldSeparatelyUntilSessionCleanup() {
        RemoteLightTracker tracker = new RemoteLightTracker();
        BitSet present = new BitSet();
        present.set(1);
        PacketSummary summary = RemoteLightClassifier.summarize(present, new BitSet(), List.of(new byte[2048]));
        tracker.observe(-3, 4, summary, 100L, true);
        tracker.processPending(24, -4, (x, z, sectionY) -> false, 4);

        assertTrue(tracker.snapshot(101L, 1_000L).isEmpty());
        assertEquals(1, tracker.heldSnapshot(101L, 1_000L).size());
        assertTrue(tracker.heldSnapshot(101L, 1_000L).getFirst().held());

        // A later surface update feeds both the active and retained underground tracks.
        tracker.observe(-3, 4, summary, 200L, false);
        tracker.processPending(24, -4, (x, z, sectionY) -> false, 4);
        assertEquals(1, tracker.snapshot(201L, 1_000L).size());
        assertEquals(1, tracker.heldSnapshot(201L, 1_000L).size());

        tracker.clear();
        assertTrue(tracker.snapshot(201L, 1_000L).isEmpty());
        assertTrue(tracker.heldSnapshot(201L, 1_000L).isEmpty());
    }
}
