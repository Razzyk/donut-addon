package dev.donut.addon.expansion.search.seed;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeedObservationInboxTest {
    private final SeedScanCore.Token token = new SeedScanCore.Token(1L, 2L, 42L);

    @Test void sameChunkCoalescesWithoutLosingBlockChangesAndLatestLoadStateWins() {
        var inbox = new SeedObservationInbox(2);
        inbox.begin(token);
        assertTrue(inbox.offer(token, -7, 123, true, false));
        assertTrue(inbox.offer(token, -7, 123, false, true));
        assertTrue(inbox.offer(token, -7, 123, true, false));
        var change = inbox.drain(2).changes().getFirst();
        assertEquals(SeedScanCore.packChunk(-7, 123), change.chunk());
        assertTrue(change.blockChanged());
        assertTrue(change.unloaded());
    }

    @Test void boundedOverflowRequiresRescanAndCannotLeakAcrossRuns() {
        var inbox = new SeedObservationInbox(1);
        inbox.begin(token);
        assertTrue(inbox.offer(token, 1, 2, true, false));
        assertFalse(inbox.offer(token, 2, 3, true, false));
        assertTrue(inbox.drain(0).overflowed());
        assertFalse(inbox.drain(0).overflowed());
        var next = new SeedScanCore.Token(2L, 2L, 43L);
        inbox.begin(next);
        assertFalse(inbox.offer(token, 3, 4, false, false));
        assertTrue(inbox.drain(10).changes().isEmpty());
        inbox.begin(null);
        assertFalse(inbox.offer(next, 3, 4, false, false));
    }
}
