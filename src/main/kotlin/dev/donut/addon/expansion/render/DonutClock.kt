package dev.donut.addon.expansion.render

import dev.donut.addon.AddonCategories
import net.ccbluex.liquidbounce.features.module.ClientModule

/** Shows a chosen time without replacing the authoritative clock maintained by Minecraft. */
object DonutClock : ClientModule("Donut Clock", AddonCategories.DONUT) {
    private val displayedTicks = int("Displayed Ticks", 0, -20_000..20_000)

    init {
        literalDescription { "Shows a custom world time while the server clock continues normally." }
        displayedTicks.onChanged { refreshAttributes() }
        enabledValue.onChanged { refreshAttributes() }
    }

    @JvmStatic
    fun resolveTicks(serverTicks: Long, overworldClock: Boolean): Long =
        ClockDisplayPolicy.resolve(serverTicks, displayedTicks.get().toLong(), running, overworldClock)

    private fun refreshAttributes() {
        val world = mc.level ?: return
        if (mc.isSameThread) {
            world.environmentAttributes().invalidateTickCache()
        } else {
            mc.execute {
                if (mc.level === world) world.environmentAttributes().invalidateTickCache()
            }
        }
    }
}

object ClockDisplayPolicy {
    @JvmStatic
    fun resolve(serverTicks: Long, displayedTicks: Long, enabled: Boolean, overworldClock: Boolean): Long =
        if (enabled && overworldClock) displayedTicks else serverTicks
}
