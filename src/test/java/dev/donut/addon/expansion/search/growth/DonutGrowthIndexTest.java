package dev.donut.addon.expansion.search.growth;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DonutGrowthIndexTest {
    @Test void weightedCoverageAndThreeDirectionalStripsProducePrimaryCandidates() {
        DonutGrowthIndex index = new DonutGrowthIndex();
        index.update(DonutGrowthIndex.pack(0, -3), new DonutGrowthIndex.Source(true, false, 14), 2);
        index.update(DonutGrowthIndex.pack(0, 3), new DonutGrowthIndex.Source(true, false, 14), 2);
        index.update(DonutGrowthIndex.pack(3, 0), new DonutGrowthIndex.Source(true, false, 14), 2);
        long candidate = DonutGrowthIndex.pack(0, 0);
        index.update(candidate, new DonutGrowthIndex.Source(false, false, 0), 2);
        assertTrue(index.rebuildPrimary(40, false, 2, 2, ignored -> true).contains(candidate));
        assertFalse(index.rebuildPrimary(43, false, 2, 2, ignored -> true).contains(candidate));
    }

    @Test void clearCoverageAndHoleDistanceVetoOnlyTheConfiguredCandidates() {
        DonutGrowthIndex index = new DonutGrowthIndex();
        for (long key : new long[] {DonutGrowthIndex.pack(0, -3), DonutGrowthIndex.pack(0, 3), DonutGrowthIndex.pack(3, 0)})
            index.update(key, new DonutGrowthIndex.Source(true, false, 20), 2);
        long target = DonutGrowthIndex.pack(0, 0);
        index.update(target, new DonutGrowthIndex.Source(false, false, 0), 2);
        long hole = DonutGrowthIndex.pack(0, 0);
        index.update(hole, new DonutGrowthIndex.Source(false, true, 0), 2);
        assertFalse(index.rebuildPrimary(40, true, 0, 2, ignored -> true).contains(target));
        assertTrue(index.rebuildPrimary(40, true, 0, 2, key -> key != target).isEmpty());
        assertFalse(index.snapshot().contains(target));
    }

    @Test void rescansRetainOldClearAndHoleEvidenceUntilUnloadAndReplaceCoverage() {
        DonutGrowthIndex index = new DonutGrowthIndex();
        long source = DonutGrowthIndex.pack(4, -2);
        index.update(source, new DonutGrowthIndex.Source(true, true, 12), 3);
        index.update(source, new DonutGrowthIndex.Source(false, false, 1), 2);
        assertEquals(1, index.recordCount());
        assertFalse(index.rebuildPrimary(1, true, 0, 2, ignored -> true).contains(source));
        index.remove(source);
        assertEquals(0, index.recordCount());
    }

    @Test void snapshotsAreImmutableAndOutOfRangeRecordsArePruned() {
        DonutGrowthIndex index = new DonutGrowthIndex();
        long keep = DonutGrowthIndex.pack(0, 0);
        long far = DonutGrowthIndex.pack(30, 0);
        index.update(keep, new DonutGrowthIndex.Source(false, false, 0), 4);
        index.update(far, new DonutGrowthIndex.Source(false, false, 0), 4);
        index.pruneDistant(0, 0, 10);
        assertEquals(1, index.recordCount());
        Set<Long> snapshot = index.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(far));
    }
}
