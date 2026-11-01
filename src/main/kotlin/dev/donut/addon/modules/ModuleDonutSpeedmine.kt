package dev.donut.addon.modules

import dev.donut.addon.AddonCategories
import net.ccbluex.liquidbounce.features.module.ClientModule

object ModuleDonutSpeedmine : ClientModule("DonutSpeedmine", AddonCategories.UTILITY) {
    init { literalDescription { "Restart held mining immediately after a block breaks." } }
}
