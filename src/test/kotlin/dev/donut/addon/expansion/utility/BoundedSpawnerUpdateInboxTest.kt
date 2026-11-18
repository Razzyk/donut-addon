package dev.donut.addon.expansion.utility

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BoundedSpawnerUpdateInboxTest {
    @Test
    fun `inbox bounds pending updates and stale tickets cannot consume later work`() {
        val inbox = BoundedSpawnerUpdateInbox<Int>(1)
        val oldTicket = inbox.offer(1)!!

        assertNull(inbox.offer(2))
        inbox.clear()
        val currentTicket = inbox.offer(3)!!

        assertNull(inbox.take(oldTicket))
        assertEquals(3, inbox.take(currentTicket))
        assertEquals(0, inbox.size())
    }
}
