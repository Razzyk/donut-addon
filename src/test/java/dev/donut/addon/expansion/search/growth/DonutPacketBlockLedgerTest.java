package dev.donut.addon.expansion.search.growth;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DonutPacketBlockLedgerTest {
    @Test void onlyAnAppliedStateMatchingAnUnexpiredInboundUpdateCorrelates() {
        DonutPacketBlockLedger correlation = new DonutPacketBlockLedger();
        correlation.record(10L, "kelp-age-3", 20L);
        assertTrue(correlation.hasPending(10L));
        assertFalse(correlation.consumeApplied(10L, "kelp-age-2", 21L, 40L));
        assertFalse(correlation.hasPending(10L));
        assertFalse(correlation.consumeApplied(10L, "kelp-age-3", 21L, 40L));
        correlation.record(11L, "kelp-age-3", 20L);
        assertFalse(correlation.consumeApplied(11L, "kelp-age-3", 61L, 40L));
        correlation.record(12L, "kelp-age-3", 20L);
        assertTrue(correlation.hasPending(12L));
        assertTrue(correlation.consumeApplied(12L, "kelp-age-3", 60L, 40L));
        assertEquals(0, correlation.pendingCount());
    }

    @Test void oldestEvidenceIsTrimmedAndExpirationIsBounded() {
        DonutPacketBlockLedger correlation = new DonutPacketBlockLedger();
        correlation.record(1L, "first", 1L);
        correlation.record(2L, "second", 2L);
        correlation.record(3L, "third", 3L);
        correlation.trimToSize(2);
        assertEquals(2, correlation.pendingCount());
        assertFalse(correlation.consumeApplied(1L, "first", 3L, 10L));
        correlation.prune(20L, 5L);
        assertEquals(0, correlation.pendingCount());
    }
}
