package dev.donut.addon.expansion.building

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class TunnelSessionLifecycleTest {
    companion object {
        @JvmStatic @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `off-world teardown abandons actions and stale discovery before next world`() {
        set("breaking", true)
        set("eating", true)
        set("mending", true)
        set("inventoryConnectionId", 42L)
        val visited = field("visited") as MutableSet<Long>
        visited.add(71L)

        DonutTunnelBaseFinder.endBuildingSession(disableModule = false)
        DonutTunnelBaseFinder.endBuildingSession(disableModule = false)

        assertNull(field("worldIdentity"))
        assertEquals(false, field("breaking"))
        assertEquals(false, field("eating"))
        assertEquals(false, field("mending"))
        assertEquals(0L, field("inventoryConnectionId"))
        assertTrue(visited.isEmpty())
    }

    private fun set(name: String, value: Any) {
        DonutTunnelBaseFinder::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(DonutTunnelBaseFinder, value)
        }
    }
    private fun field(name: String): Any? =
        DonutTunnelBaseFinder::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(DonutTunnelBaseFinder)
        }
}
