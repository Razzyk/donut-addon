package dev.donut.addon.navigation

import dev.donut.addon.compat.DonutEventListener
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class DonutNavigationRuntimeTest {
    @Test
    fun `runtime without the Navigator falls back and nightly constructs a walking-only Navigator`() {
        val navigatorPresent = javaClass.getResource(
            "/net/ccbluex/liquidbounce/utils/navigation/Navigator.class",
        ) != null
        assertEquals(navigatorPresent, DonutNavigationBridge.available)
        if (!navigatorPresent) return
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()

        val api = checkNotNull(DonutNavigationBridge.NativeNavigatorApi.resolve())
        val owner = object : DonutEventListener {
            override val running = false
        }
        val settings = ValueGroup("Navigation runtime test")
        val navigator = api.create(owner, settings)
        try {
            DonutNavigationBridge.configureSafeWalking(settings)
            val values = settings.containedValues.associateBy { it.name }
            for (name in listOf("AllowBreak", "AllowPlace", "AllowParkour", "AllowFly", "AllowElytra", "ElytraRockets")) {
                assertEquals(false, values.getValue(name).get(), name)
            }
            api.reset(navigator)
            assertFalse(api.isActing(navigator))
        } finally {
            api.unregisterTree(navigator, settings)
            owner.unregister()
        }
    }
}
