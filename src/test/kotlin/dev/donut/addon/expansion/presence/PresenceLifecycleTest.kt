package dev.donut.addon.expansion.presence

import net.ccbluex.liquidbounce.config.types.Value
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class PresenceLifecycleTest {
    companion object {
        @JvmStatic @BeforeAll fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    @Test fun `late responses and queued client announcements cannot enter a new run`() {
        val guard = PresenceRunGuard()
        val oldRun = guard.begin()
        var published = 0
        val queuedAnnouncement = { guard.apply(oldRun) { published++ } }
        guard.cancel()
        val newRun = guard.begin()

        assertFalse(queuedAnnouncement())
        assertFalse(guard.apply(oldRun) { published++ })
        assertTrue(guard.apply(newRun) { published++ })
        assertEquals(1, published)
        guard.cancel()
        assertFalse(guard.accepts(newRun))
    }

    @Test fun `out of world disable and teardown clean resources without erasing enabled settings`() {
        val probe = CleanupProbe()
        probe.walkInit()
        Value::class.java.getDeclaredField("inner").apply { isAccessible = true }.set(probe.enabledValue, true)

        probe.endPresenceSession()
        assertTrue(probe.enabled)
        assertEquals(1, probe.cleanups)
        probe.shutdownPresence()
        assertTrue(probe.enabled)
        probe.enabled = false
        assertFalse(probe.enabled)
        assertEquals(3, probe.cleanups)
    }

    @Test fun `response limit accepts complete exact bound and rejects truncated documents`() {
        assertEquals("abc", PresenceHttpBody.readUtf8(ByteArrayInputStream("abc".toByteArray()), 3))
        assertThrows(IllegalStateException::class.java) {
            PresenceHttpBody.readUtf8(ByteArrayInputStream("abcd".toByteArray()), 3)
        }
    }

    @Test fun `compressed live pages are bounded after decoding`() {
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(ByteArray(32_768) { 65 }) }
        }.toByteArray()
        assertTrue(compressed.size < 1024)
        GZIPInputStream(ByteArrayInputStream(compressed)).use { decoded ->
            assertThrows(IllegalStateException::class.java) { PresenceHttpBody.readUtf8(decoded, 1024) }
        }
    }

    private class CleanupProbe : DonutPresenceModule("Donut Presence Cleanup Probe") {
        var cleanups = 0
        override fun onDisabled() { cleanups++ }
    }
}
