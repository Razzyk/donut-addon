package dev.donut.addon.expansion.search.remote;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RemoteActivityInboxTest {
    @Test void clearIsIdempotentAndReleasesBoundedSlotsForNewSessionData() {
        RemoteActivityInbox inbox = new RemoteActivityInbox(1);
        var stale = new RemoteActivityInbox.Signal(1, 2, RemoteActivityLedger.Kind.BLOCK, 3L);
        assertTrue(inbox.offer(stale));
        assertFalse(inbox.offer(stale));
        inbox.clear();
        assertEquals(0, inbox.size());
        var current = new RemoteActivityInbox.Signal(4, 5, RemoteActivityLedger.Kind.SOUND, 6L, null, 7L);
        assertTrue(inbox.offer(current));
        assertEquals(1, inbox.size());
        assertEquals(7L, inbox.drain(1).get(0).sessionGeneration());
        assertEquals(0, inbox.size());
        inbox.clear();
        inbox.clear();
    }
}
