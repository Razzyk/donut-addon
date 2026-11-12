package dev.donut.addon.expansion.building

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.features.module.ClientModule
import dev.donut.addon.expansion.search.SearchFeatures

/** Native registration for the independently implemented construction and mining tools. */
object BuildingFeatures {
    val modules: List<ClientModule> = listOf(DonutAreaMiner, DonutTunnelBaseFinder)
    val listeners: List<EventListener> = listOf(BuildingSessionLifecycle)

    init {
        TunnelChunkTargetProviders.susChunk = TunnelFlaggedChunkProvider {
            SearchFeatures.GROWTH_SURVEY.flaggedChunkSnapshot()
        }
        TunnelChunkTargetProviders.betterSusChunk = TunnelFlaggedChunkProvider {
            SearchFeatures.GROWTH_INSPECTOR.flaggedChunkSnapshot()
        }
    }

    fun shutdown() = BuildingSessionLifecycle.shutdown()
}
