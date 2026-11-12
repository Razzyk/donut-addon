package dev.donut.addon.compat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DonutEventListenerTest {
    private class Listener : DonutEventListener

    @Test
    fun `diagnostic getters run without client supplied defaults`() {
        val listener = Listener()
        assertEquals(Listener::class.java.name, listener.debugOwnerId)
        val rendered = StringBuilder()
        listener.debugDisplayName.accept { _, _, codePoint ->
            rendered.appendCodePoint(codePoint)
            true
        }
        assertEquals("Listener", rendered.toString())
    }
}
