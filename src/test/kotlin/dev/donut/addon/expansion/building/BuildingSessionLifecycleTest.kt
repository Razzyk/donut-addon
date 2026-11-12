package dev.donut.addon.expansion.building

import dev.donut.addon.expansion.automation.core.InventorySlotValue
import net.ccbluex.liquidbounce.config.types.Value
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class BuildingSessionLifecycleTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    @AfterEach
    fun resetModule() {
        seedEnabled(false)
        DonutAreaMiner.endBuildingSession(disableModule = false)
    }

    @Test
    fun `off-world disable clears owned leases and active controls`() {
        seedOwnedState()
        seedEnabled(true)

        DonutAreaMiner.enabled = false

        assertFalse(DonutAreaMiner.enabled)
        assertClean()
    }

    @Test
    fun `shutdown cleanup preserves the module enabled value`() {
        seedOwnedState()
        seedEnabled(true)

        DonutAreaMiner.endBuildingSession(disableModule = false)

        assertTrue(DonutAreaMiner.enabled)
        assertClean()
    }

    private fun seedOwnedState() {
        val lease = AreaInventoryLease(
            Any(), Any(), Any(), Any(), 1L, 0, 9, 36, 0,
            InventorySlotValue("minecraft:diamond_pickaxe", 1),
            InventorySlotValue("minecraft:stone_pickaxe", 1),
        )
        setField("worldIdentity", Any())
        setField("breaking", true)
        setField("eating", true)
        setField("mending", true)
        setField("toolInventoryLease", lease)
        setField("foodInventoryLease", lease)
        setField("xpInventoryLease", lease)
    }

    private fun assertClean() {
        assertNull(getNullableField("worldIdentity"))
        assertFalse(getField<Boolean>("breaking"))
        assertFalse(getField<Boolean>("eating"))
        assertFalse(getField<Boolean>("mending"))
        assertNull(getNullableField("toolInventoryLease"))
        assertNull(getNullableField("foodInventoryLease"))
        assertNull(getNullableField("xpInventoryLease"))
    }

    private fun seedEnabled(enabled: Boolean) {
        val inner = Value::class.java.getDeclaredField("inner")
        inner.isAccessible = true
        inner.set(DonutAreaMiner.enabledValue, enabled)
    }

    private fun setField(name: String, value: Any?) {
        DonutAreaMiner::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(DonutAreaMiner, value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> getField(name: String): T = DonutAreaMiner::class.java.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(DonutAreaMiner) as T
    }

    private fun getNullableField(name: String): Any? = DonutAreaMiner::class.java.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(DonutAreaMiner)
    }
}
