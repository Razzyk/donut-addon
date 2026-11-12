package dev.donut.addon.expansion.utility

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.client.multiplayer.ClientLevel
import net.ccbluex.liquidbounce.config.types.Value
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class UtilitySessionLifecycleTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    @Test
    fun `world start gate waits for exact world and ready session`() {
        val target = Any()
        val gate = UtilityWorldStartGate<Any>()
        gate.request(target)

        assertNull(gate.poll(target, false))
        assertNull(gate.poll(Any(), true))
        assertSame(target, gate.poll(target, true))
        assertNull(gate.poll(target, true))

        gate.request(target)
        gate.cancel()
        assertNull(gate.poll(target, true))
    }

    @Test
    fun `cleanup resets transient state without changing module enabled value`() {
        val module = CleanupProbe()
        module.walkInit()
        seedEnabledWithoutCallbacks(module)

        module.onUtilityWorldChanging(null)
        assertTrue(module.enabled)
        assertEquals(1, module.cleanupCalls)

        module.onUtilityShutdown()
        assertTrue(module.enabled)
        assertEquals(2, module.cleanupCalls)

        module.enabled = false
        assertFalse(module.enabled)
        assertEquals(3, module.cleanupCalls)
    }

    private fun seedEnabledWithoutCallbacks(module: DonutUtilityModule) {
        val inner = Value::class.java.getDeclaredField("inner")
        inner.isAccessible = true
        inner.set(module.enabledValue, true)
    }

    private class CleanupProbe : DonutUtilityModule("Donut Utility Cleanup Probe", "Test module for session cleanup.") {
        var cleanupCalls = 0
            private set

        override fun onDisabled() {
            cleanupCalls++
        }

        override fun onUtilitySessionStarted(world: ClientLevel) = Unit
    }
}
