package dev.donut.addon

import dev.donut.addon.modules.ModuleDonutNofall
import dev.donut.addon.modules.ModuleAntiLeak
import dev.donut.addon.modules.ModuleElytraJump
import dev.donut.addon.modules.ModuleGrimGroundVelocity
import dev.donut.addon.modules.ModuleUnbreakableElytra
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.PlayerTickEvent
import net.ccbluex.liquidbounce.event.handler
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
import net.fabricmc.loader.api.FabricLoader

class DonutAddon : LiquidBounceAddon() {
    override val categories: List<ModuleCategory> = listOf(
        AddonCategories.DONUT, AddonCategories.BASEFINDING, AddonCategories.UTILITY
    )
    override fun onInitialize() {
        registerModules(
            ModuleAntiLeak,
            ModuleDonutNofall,
            ModuleElytraJump,
            ModuleUnbreakableElytra,
            ModuleGrimGroundVelocity,
            ModuleGrimAirPlace,
            ModuleRegionMap
        )
        registerModules(*MVFeatures.modules.toTypedArray())
        LightCache.register()
        registerListeners(ElytraRestoreListener, ServerFlightPermissions, MVEventBridge)
        registerHud(RegionMapHudComponent)
        registerHud(SpeedometerHudComponent)
        val loader = FabricLoader.getInstance()
        logger.info("Donut addon loaded for LiquidBounce {} / Minecraft {}",
            loader.getModContainer("liquidbounce").orElseThrow().metadata.version.friendlyString,
            loader.getModContainer("minecraft").orElseThrow().metadata.version.friendlyString)
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

/** Finish an inventory transaction even if the user disables its module mid-swap. */
private object ElytraRestoreListener : EventListener {
    @Suppress("unused")
    private val tick = handler<PlayerTickEvent>(priority = 100) {
        ModuleUnbreakableElytra.cleanupTick()
    }
}
