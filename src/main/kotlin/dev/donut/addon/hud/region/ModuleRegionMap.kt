package dev.donut.addon.hud.region

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories

object ModuleRegionMap : ClientModule("RegionMap", ModuleCategories.RENDER) {
    init {
        literalDescription { "Shows the DonutSMP shard region map in the HUD editor." }
        enabledValue.onChanged { state ->
            if (RegionMapHudComponent.enabled != state) RegionMapHudComponent.enabled = state
        }
    }
}
