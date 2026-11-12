package dev.donut.addon.expansion.building

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class AreaMinerSettingsTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    @Test
    fun `selection actions are present transient and reset by default`() {
        val module = DonutAreaMiner
        module.walkInit()
        val actionNames = listOf(
            "Select Area With Clicks",
            "Corner 1 At Feet",
            "Corner 1 At Crosshair",
            "Corner 2 At Feet",
            "Corner 2 At Crosshair",
        )

        for (name in actionNames) {
            val action = module.settings[name]
            assertTrue(action != null, "missing action: $name")
            assertFalse(action!!.get() as Boolean, "$name must default to idle")
            assertFalse(action.isPersistent, "$name must not be loaded or saved")
        }
    }
}
