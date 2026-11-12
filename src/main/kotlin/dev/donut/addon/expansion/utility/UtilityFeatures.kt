package dev.donut.addon.expansion.utility

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.integration.theme.component.components.NativeHudComponent

/** Registration surface for Donut-authored utility functions and their optional native panels. */
object UtilityFeatures {
    val toolCycle = DonutToolCycle()
    val rhythm = DonutRhythm()
    val rapidUse = DonutRapidUse()
    val mealAssist = DonutMealAssist()
    val escapeAssist = DonutEscapeAssist()
    val holdPosition = DonutHoldPosition()
    val landingGuard = DonutLandingGuard()
    val swingRate = DonutSwingRate()
    val chatFaucet = DonutChatFaucet()
    val coordinateSnapper = DonutCoordinateSnapper()
    val weatherNotice = DonutWeatherNotice()
    val blockNotice = DonutBlockNotice()
    val blockSync = DonutBlockSync()
    val nameShield = DonutNameShield()
    val detachedCamera = DonutDetachedCamera()
    val skinWardrobe = DonutSkinWardrobe()
    val goliathRtp = DonutGoliathRtp()
    val spawnerProtect = DonutSpawnerProtect()
    val shareGuard = DonutShareGuard()
    val statusMirror = DonutStatusMirror()
    val discordStatus = DonutDiscordStatus()

    val modules: List<ClientModule> = listOf(
        toolCycle, rhythm, rapidUse, mealAssist, escapeAssist, holdPosition, landingGuard,
        swingRate, chatFaucet, coordinateSnapper, weatherNotice, blockNotice,
        blockSync, nameShield, detachedCamera, skinWardrobe, spawnerProtect,
        statusMirror, discordStatus
    )
    // The map picker and inventory sprite overlay still need their source behavior completed.
    // Their draft instances remain available to internal tests and lifecycle cleanup only.
    val listeners: List<EventListener> = listOf(UtilitySessionLifecycle)
    val hudComponents: List<NativeHudComponent> = emptyList()

    fun shutdown() = UtilitySessionLifecycle.shutdown()
}
