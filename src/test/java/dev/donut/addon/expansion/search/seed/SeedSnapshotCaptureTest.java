package dev.donut.addon.expansion.search.seed;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeedSnapshotCaptureTest {
    private static final SeedScanCore.Token TOKEN = new SeedScanCore.Token(1L, 2L, 42L);

    private static SeedScanCore.PredictedCluster candidate() {
        long[] cells = new long[8];
        for (int x = 0; x < 8; x++) cells[x] = SeedScanCore.packBlock(x, 10, 8);
        return new SeedScanCore.PredictedCluster(SeedScanCore.GRANITE, cells[0], cells,
            new long[]{SeedScanCore.packBlock(8, 10, 8)}, new byte[]{SeedScanCore.GRANITE}, true);
    }

    @Test void eachTickIsBoundedAndCapturedShellIncludesNeededNeighborEvidence() {
        var capture = new SeedSnapshotCapture(TOKEN, List.of(candidate()));
        var reads = new AtomicInteger();
        for (int tick = 0; tick < 100 && capture.state() != SeedSnapshotCapture.State.COMPLETE; tick++) {
            int before = reads.get();
            capture.step(TOKEN, 7, 100L, () -> 0L, pos -> {
                reads.incrementAndGet();
                return pos == SeedScanCore.packBlock(8, 10, 8) ? SeedScanCore.DIORITE : SeedScanCore.GRANITE;
            }, chunk -> 1L);
            assertTrue(reads.get() - before <= 7);
        }
        assertEquals(SeedSnapshotCapture.State.COMPLETE, capture.state());
        assertTrue(reads.get() <= 35, "capture reads sparse evidence rather than every block in surrounding chunks");
        var snapshot = capture.snapshot();
        assertNotNull(snapshot);
        assertEquals(java.util.Map.of(SeedScanCore.packChunk(0, 0), 1L), capture.capturedRevisions());
        assertEquals(SeedScanCore.GRANITE, snapshot.at(9, 11, 9));
        assertEquals(SeedScanCore.Verdict.ALERT, SeedScanCore.judge(candidate(), snapshot, 1));
    }

    @Test void chunkChangeDuringCaptureRejectsMixedTimeEvidence() {
        var capture = new SeedSnapshotCapture(TOKEN, List.of(candidate()));
        var reads = new AtomicInteger();
        var revision = new AtomicLong(1L);
        while (reads.get() == 0) capture.step(TOKEN, 1, 100L, () -> 0L, pos -> {
            reads.incrementAndGet(); return SeedScanCore.GRANITE;
        }, chunk -> revision.get());
        revision.incrementAndGet();
        capture.step(TOKEN, 100, 100L, () -> 0L, pos -> SeedScanCore.DIORITE, chunk -> revision.get());
        assertEquals(SeedSnapshotCapture.State.STALE, capture.state());
        assertNull(capture.snapshot());
    }

    @Test void deadlineAndSessionChangesPreventReadingWorldState() {
        var capture = new SeedSnapshotCapture(TOKEN, List.of(candidate()));
        var reads = new AtomicInteger();
        capture.step(TOKEN, 1000, 100L, () -> 100L, pos -> { reads.incrementAndGet(); return SeedScanCore.GRANITE; }, chunk -> 1L);
        assertEquals(0, reads.get());
        var replacement = new SeedScanCore.Token(2L, 3L, 42L);
        capture.step(replacement, 1000, 100L, () -> 0L, pos -> { reads.incrementAndGet(); return SeedScanCore.GRANITE; }, chunk -> 1L);
        assertEquals(SeedSnapshotCapture.State.STALE, capture.state());
        assertEquals(0, reads.get());
    }

    @Test void completedCaptureCannotBeReusedAfterSessionReplacement() {
        var capture = new SeedSnapshotCapture(TOKEN, List.of(candidate()));
        capture.step(TOKEN, 1000, 100L, () -> 0L, pos -> SeedScanCore.GRANITE, chunk -> 1L);
        assertNotNull(capture.snapshot());
        capture.step(new SeedScanCore.Token(2L, 2L, 43L), 1000, 100L, () -> 0L,
            pos -> SeedScanCore.GRANITE, chunk -> 1L);
        assertNull(capture.snapshot());
        assertEquals(SeedSnapshotCapture.State.STALE, capture.state());
    }
}
