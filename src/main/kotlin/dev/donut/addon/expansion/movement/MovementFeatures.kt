package dev.donut.addon.expansion.movement

import dev.donut.addon.compat.DonutEventListener

import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.events.ClientShutdownEvent
import net.ccbluex.liquidbounce.event.handler

object MovementFeatures {
    val modules: List<ClientModule> = listOf(DonutTravelKeys, DonutHopAssist, DonutWallClimb, DonutCrouch, DonutPointTravel,
        DonutStride, DonutFlightControl, DonutMountPace, DonutMountControl, DonutMountFlight, DonutBoatPassage,
        DonutRiptideBoost, DonutSurfaceWalk)
    val listeners: List<EventListener> = listOf(VehicleRuntime, MovementSessionLifecycle)
    fun shutdown() {
        DonutFlightControl.endFlightSession()
        DonutSurfaceWalk.resetSurfaceSession()
    }
}

private object MovementSessionLifecycle : DonutEventListener {
    private val worldChange = handler<WorldChangeEvent> { MovementFeatures.shutdown() }
    private val shutdown = handler<ClientShutdownEvent> { MovementFeatures.shutdown() }

    override fun unregister() {
        MovementFeatures.shutdown()
        super<DonutEventListener>.unregister()
    }
}
