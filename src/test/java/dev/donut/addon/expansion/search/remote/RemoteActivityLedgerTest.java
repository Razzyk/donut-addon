package dev.donut.addon.expansion.search.remote;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RemoteActivityLedgerTest {
    @Test
    void mergeDecayAgeAndCapacityAreBounded() {
        var ledger = new RemoteActivityLedger(2);
        ledger.observe(32, 64, RemoteActivityLedger.Kind.LIGHT, 1_000_000_000L);
        ledger.observe(35, 68, RemoteActivityLedger.Kind.BLOCK, 2_000_000_000L);
        var merged = ledger.snapshot(2_000_000_000L, 60_000_000_000L).getFirst();
        assertEquals(2, merged.sourceKinds());
        assertEquals(2, merged.events());
        assertTrue(merged.score() > 3.9);
        assertTrue(ledger.snapshot(47_000_000_000L, 60_000_000_000L).getFirst().score() < merged.score());
        ledger.observe(160, 192, RemoteActivityLedger.Kind.SOUND, 48_000_000_000L);
        ledger.observe(320, 320, RemoteActivityLedger.Kind.ENTITY, 49_000_000_000L);
        assertEquals(2, ledger.size());
        ledger.pruneOlderThan(120_000_000_000L, 60_000_000_000L);
        assertTrue(ledger.snapshot(120_000_000_000L, 60_000_000_000L).isEmpty());
    }

    @Test
    void inboxEnforcesCapacityAndDrainsInOrder() {
        var inbox = new RemoteActivityInbox(2);
        var first = new RemoteActivityInbox.Signal(0, 0, RemoteActivityLedger.Kind.LIGHT, 1L);
        var second = new RemoteActivityInbox.Signal(16, 0, RemoteActivityLedger.Kind.SOUND, 2L);
        assertTrue(inbox.offer(first));
        assertTrue(inbox.offer(second));
        assertFalse(inbox.offer(new RemoteActivityInbox.Signal(32, 0, RemoteActivityLedger.Kind.BLOCK, 3L)));
        assertEquals(java.util.List.of(first, second), inbox.drain(2));
        inbox.clear();
        assertEquals(0, inbox.size());
    }
}
