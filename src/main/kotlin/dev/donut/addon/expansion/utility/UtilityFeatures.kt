package dev.donut.addon.expansion.utility

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.integration.theme.component.components.NativeHudComponent

/** Registration surface for Donut-authored utility functions and their optional native panels. */
object UtilityFeatures {
    val escapeAssist = DonutEscapeAssist()
    val coordinateSnapper = DonutCoordinateSnapper()
    val blockNotice = DonutBlockNotice()
    val blockSync = DonutBlockSync()
    val goliathRtp = DonutGoliathRtp()
    val spawnerProtect = DonutSpawnerProtect()
    val shareGuard = DonutShareGuard()
    val statusMirror = DonutStatusMirror()

    val modules: List<ClientModule> = listOf(
        escapeAssist, coordinateSnapper, blockNotice, blockSync, spawnerProtect, statusMirror
    )
    // These unique drafts stay constructed for internal work but are not in the public module list
    // until their map picker and inventory sprite overlay behavior is complete.
    val listeners: List<EventListener> = listOf(UtilitySessionLifecycle)
    val hudComponents: List<NativeHudComponent> = emptyList()

    fun shutdown() = UtilitySessionLifecycle.shutdown()
}
