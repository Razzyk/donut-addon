package dev.donut.addon.expansion.presence

import net.ccbluex.liquidbounce.integration.theme.component.components.NativeHudComponent
import net.ccbluex.liquidbounce.event.EventListener

/** Central registration list for Donut's presence and staff utilities. */
object PresenceFeatures {
    val presenceWatch = DonutPresenceWatch()
    val staffRoster = DonutStaffRoster()
    val staffPulse = DonutStaffPulse()
    val arrivalWatch = DonutArrivalWatch()

    val modules = listOf(presenceWatch, staffRoster, staffPulse, arrivalWatch)
    val listeners: List<EventListener> = listOf(PresenceSessionLifecycle)

    fun shutdown() = PresenceSessionLifecycle.shutdown()
    @Volatile private var hudComponentsReady = false
    val hudComponents: List<NativeHudComponent> by lazy {
        listOf(StaffRosterHudComponent, StaffPulseHudComponent, PresenceWatchHudComponent).also {
            hudComponentsReady = true
            syncHudState()
        }
    }

    /** Called by module toggles/settings only after the component collection has been materialized. */
    fun syncHudState() {
        if (!hudComponentsReady) return
        PresenceWatchHudComponent.enabled = presenceWatch.enabled && presenceWatch.shouldShowHud
        StaffRosterHudComponent.enabled = staffRoster.enabled && staffRoster.shouldShowHud
        StaffPulseHudComponent.enabled = staffPulse.enabled && staffPulse.shouldShowHud
    }
}
