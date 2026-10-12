package dev.donut.addon

import dev.donut.addon.modules.ModuleDonutNofall
import dev.donut.addon.modules.ModuleGrimGroundVelocity
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.features.addon.LiquidBounceAddon
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import dev.donut.addon.mv.MVFeatures
import dev.donut.addon.mv.MVEventBridge
import dev.mulveo.mvaddon.chunkscanner.light.LightCache
import dev.donut.addon.stash.ModuleGrimAirPlace
import dev.donut.addon.hud.region.ModuleRegionMap
import dev.donut.addon.hud.region.RegionMapHudComponent
import dev.donut.addon.hud.speedometer.SpeedometerHudComponent
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleHud
import net.ccbluex.liquidbounce.integration.theme.component.HudComponentManager
import net.ccbluex.liquidbounce.integration.theme.component.components.NativeHudComponent

class DonutAddon : LiquidBounceAddon() {
    override val categories: List<ModuleCategory> = listOf(
        AddonCategories.DONUT, AddonCategories.BASEFINDING, AddonCategories.UTILITY
    )
    override fun onInitialize() {
        registerModules(
            ModuleDonutNofall,
            ModuleGrimGroundVelocity,
            ModuleGrimAirPlace,
            ModuleRegionMap
        )
        registerModules(*MVFeatures.modules.toTypedArray())
        LightCache.register()
        registerListeners(ServerFlightPermissions, MVEventBridge)
        registerHud(RegionMapHudComponent)
        registerHud(SpeedometerHudComponent)
        logger.info("Donut Addon by Razzy loaded for LiquidBounce 0.41.0 / Minecraft 26.3")
    }

    override fun onStarted() {
        RegionMapHudComponent.enabled = ModuleRegionMap.enabled
        HudComponentManager.updateComponents()
    }

    private fun registerHud(component: NativeHudComponent) {
        // The editor saves modulesConfig after edits, so these must be in HUD's config tree.
        registerListeners(HudRegistration(component))
        ModuleHud.components.tree(component)
        component.walkInit()
        component.walkKeyPath()
        HudComponentManager.register(component)
    }
}

/** Withdraw the editor entry and settings as well as its event hooks if addon loading fails. */
private class HudRegistration(private val component: NativeHudComponent) : EventListener {
    override fun unregister() {
        HudComponentManager.unregister(component)
        if (component.base === ModuleHud.components) ModuleHud.components.drop(component)
        component.unregister()
        super.unregister()
    }
}
