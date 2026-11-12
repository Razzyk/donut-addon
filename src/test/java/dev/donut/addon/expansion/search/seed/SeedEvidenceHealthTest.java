package dev.donut.addon.expansion.search.seed;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeedEvidenceHealthTest {
    @Test void confidenceNeedsFortyDistinctComparableClustersAndStrictMajorityMismatch() {
        var health = new SeedEvidenceHealth();
        for (int i = 0; i < 20; i++) health.observe(i, SeedScanCore.GRANITE, SeedScanCore.Verdict.MISPREDICTED);
        for (int i = 20; i < 40; i++) health.observe(i, SeedScanCore.GRANITE, SeedScanCore.Verdict.CLEAN);
        assertFalse(health.alertsSuppressed(), "exactly half mismatched is not a majority");
        var update = health.observe(40L, SeedScanCore.GRANITE, SeedScanCore.Verdict.MISPREDICTED);
        assertTrue(update.seedMismatchNotice());
        assertTrue(update.alertsSuppressed());
        assertFalse(health.observe(41L, SeedScanCore.GRANITE, SeedScanCore.Verdict.MISPREDICTED).seedMismatchNotice());
    }

    @Test void rechecksAndInconclusiveVerdictsCannotManufactureEvidence() {
        var health = new SeedEvidenceHealth();
        for (int i = 0; i < 100; i++) health.observe(1L, SeedScanCore.GRANITE, SeedScanCore.Verdict.MISPREDICTED);
        for (int i = 0; i < 100; i++) health.observe(i, SeedScanCore.GRANITE, SeedScanCore.Verdict.INCOMPLETE);
        assertEquals(1, health.mismatchedCount());
        assertFalse(health.alertsSuppressed());
        health.observe(1L, SeedScanCore.DIORITE, SeedScanCore.Verdict.ALERT);
        assertEquals(1, health.verifiedCount(), "distinct material at same anchor is independent evidence");
    }

    @Test void hiddenOreWarningIsSeparateFromDisablingMismatchAlertsAndResetsPerRun() {
        var health = new SeedEvidenceHealth();
        for (int i = 0; i < 9; i++) assertFalse(health.observe(i, SeedScanCore.GRANITE, SeedScanCore.Verdict.HIDDEN).hiddenOreNotice());
        var tenth = health.observe(9L, SeedScanCore.GRANITE, SeedScanCore.Verdict.HIDDEN);
        assertTrue(tenth.hiddenOreNotice());
        assertFalse(tenth.alertsSuppressed());
        assertFalse(health.observe(10L, SeedScanCore.GRANITE, SeedScanCore.Verdict.HIDDEN).hiddenOreNotice());
        health.reset();
        assertEquals(0, health.hiddenCount());
        assertFalse(health.alertsSuppressed());
    }
}
