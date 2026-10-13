package dev.donut.addon

import net.ccbluex.liquidbounce.features.module.ModuleCategory

/** Shared identities: LiquidBounce validates category identity when registering modules. */
object AddonCategories {
    @JvmField val DONUT = ModuleCategory("Donut")
    @JvmField val BASEFINDING = ModuleCategory("Basefinding")
    @JvmField val UTILITY = ModuleCategory("Donut Utility")
}
