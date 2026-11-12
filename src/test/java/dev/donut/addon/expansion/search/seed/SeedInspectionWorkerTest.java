package dev.donut.addon.expansion.search.seed;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeedInspectionWorkerTest {
    @Test void immutableEvidenceRunsWithoutWorldReferencesAndRetainsCaptureRevisions() throws Exception {
        try (var worker = new SeedPredictionWorker()) {
            var token = worker.begin(1L, 42L);
            long[] cells = java.util.stream.IntStream.range(0, 8)
                .mapToLong(x -> SeedScanCore.packBlock(x, 10, 7)).toArray();
            var cluster = new SeedScanCore.PredictedCluster(SeedScanCore.GRANITE, cells[0], cells, new long[0]);
            var capture = new SeedSnapshotCapture(token, List.of(cluster));
            capture.step(token, 100, Long.MAX_VALUE, System::nanoTime, p -> SeedScanCore.GRANITE, c -> 1L);
            var ground = new SeedGroundEvidence.Comparison(256, 0, false);
            assertTrue(worker.submitInspection(new SeedPredictionWorker.Inspection(token,
                SeedScanCore.packChunk(-3, 21), capture.clusters(), capture.snapshot(), Map.of(99L, 4L), 1, ground)));
            List<SeedPredictionWorker.Evidence> results = List.of();
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (results.isEmpty() && System.nanoTime() < deadline) {
                results = worker.drainEvidence(1);
                Thread.yield();
            }
            assertEquals(1, results.size());
            assertEquals(SeedScanCore.packChunk(-3, 21), results.getFirst().chunk());
            assertEquals(Map.of(99L, 4L), results.getFirst().revisions());
            assertEquals(ground, results.getFirst().ground());
            assertEquals(SeedScanCore.Verdict.CLEAN, results.getFirst().findings().getFirst().verdict());
        }
    }

    @Test void canceledRunCannotSubmitOrPublishEvidenceIntoNewSeedRun() {
        try (var worker = new SeedPredictionWorker()) {
            var old = worker.begin(1L, 42L);
            var next = worker.begin(1L, 43L);
            assertFalse(worker.submitInspection(new SeedPredictionWorker.Inspection(old, 0L,
                List.of(), new SeedScanCore.Snapshot(Map.of()), Map.of(), 1)));
            worker.invalidate();
            assertFalse(worker.submitInspection(new SeedPredictionWorker.Inspection(next, 0L,
                List.of(), new SeedScanCore.Snapshot(Map.of()), Map.of(), 1)));
            assertTrue(worker.drainEvidence(8).isEmpty());
        }
    }
}
