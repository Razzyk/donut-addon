package dev.donut.addon.expansion.render

import net.ccbluex.liquidbounce.features.module.ClientModule

/** Only complete, independently implemented render features enter this list. */
object RenderFeatures {
    val modules: List<ClientModule> = listOf(DonutBrightness, DonutHealthAlert, DonutClock)
}
