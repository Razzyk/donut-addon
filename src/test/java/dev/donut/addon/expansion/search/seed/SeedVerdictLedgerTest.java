package dev.donut.addon.expansion.search.seed;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeedVerdictLedgerTest {
    private static SeedVerdictLedger.Finding finding(long owner, SeedScanCore.Verdict verdict) {
        return new SeedVerdictLedger.Finding(owner, SeedScanCore.GRANITE, verdict);
    }

    @Test void unloadedEvidencePreservesKnownAlertsButConfirmedCleanRemovesThem() {
        var ledger = new SeedVerdictLedger(10);
        ledger.reconcile(42L, List.of(finding(7L, SeedScanCore.Verdict.ALERT)));
        var retained = ledger.reconcile(42L, List.of(finding(7L, SeedScanCore.Verdict.INCOMPLETE)));
        assertTrue(retained.flagged());
        ledger.reconcile(42L, List.of(finding(7L, SeedScanCore.Verdict.CLEAN)));
        assertFalse(ledger.get(42L).flagged());
    }

    @Test void newUnknownClustersCannotErasePriorEvidenceAndFlaggedResultsSurviveTravel() {
        var ledger = new SeedVerdictLedger(10);
        ledger.reconcile(42L, List.of(finding(7L, SeedScanCore.Verdict.ALERT)));
        assertNull(ledger.reconcile(42L, List.of(finding(8L, SeedScanCore.Verdict.INCOMPLETE))));
        ledger.reconcile(43L, List.of(finding(8L, SeedScanCore.Verdict.CLEAN)));
        ledger.retainArea(Set.of());
        assertNotNull(ledger.get(42L));
        assertNull(ledger.get(43L));
    }

    @Test void evidenceStorageIsBoundedAndDropsCleanEntriesBeforeAlerts() {
        var ledger = new SeedVerdictLedger(2);
        ledger.reconcile(1L, List.of(finding(1L, SeedScanCore.Verdict.ALERT)));
        ledger.reconcile(2L, List.of(finding(2L, SeedScanCore.Verdict.CLEAN)));
        ledger.reconcile(3L, List.of(finding(3L, SeedScanCore.Verdict.ALERT)));
        assertNotNull(ledger.get(1L));
        assertNull(ledger.get(2L));
        assertNotNull(ledger.get(3L));
    }
}
