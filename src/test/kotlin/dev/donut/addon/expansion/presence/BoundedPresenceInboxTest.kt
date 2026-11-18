package dev.donut.addon.expansion.presence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BoundedPresenceInboxTest {
    @Test
    fun `packet inbox has a hard capacity and preserves accepted order`() {
        val inbox = BoundedPresenceInbox<Int>(2)
        inbox += 1
        inbox += 2
        inbox += 3

        assertEquals(2, inbox.size())
        assertEquals(1, inbox.poll())
        assertEquals(2, inbox.poll())
        assertNull(inbox.poll())
    }
}
