package dev.donut.addon.expansion.building

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TunnelPacketInboxTest {
    @Test fun `packet flood stays bounded and requests recovery without losing queued order`() {
        val inbox = TunnelPacketInbox<Int>(2)
        assertTrue(inbox.offer(1))
        assertTrue(inbox.offer(2))
        repeat(10_000) { assertFalse(inbox.offer(it + 3)) }
        assertTrue(inbox.consumeOverflow())
        assertFalse(inbox.consumeOverflow())
        assertEquals(1, inbox.poll())
        assertEquals(2, inbox.poll())
        assertNull(inbox.poll())
        assertTrue(inbox.offer(9))
        assertEquals(9, inbox.poll())
    }

    @Test fun `new world clears pending packets and previous overflow`() {
        val inbox = TunnelPacketInbox<String>(1)
        inbox.offer("old-world")
        inbox.offer("overflow")
        inbox.clear()
        assertNull(inbox.poll())
        assertFalse(inbox.consumeOverflow())
        assertTrue(inbox.offer("new-world"))
        assertEquals("new-world", inbox.poll())
    }
}
