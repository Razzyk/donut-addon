package dev.donut.addon.expansion.building

import dev.donut.addon.compat.DonutEventListener

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.ClientShutdownEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import org.slf4j.LoggerFactory

/** Always-on cleanup for held mining inputs and inventory acknowledgements across module/world teardown. */
object BuildingSessionLifecycle : DonutEventListener {
    @Volatile
    private var stopped = false

    private val worldChange = handler<WorldChangeEvent> {
        if (!stopped) endModules(disableModules = true)
    }

    private val clientShutdown = handler<ClientShutdownEvent> {
        shutdown()
    }

    fun shutdown() {
        if (stopped) return
        stopped = true
        endModules(disableModules = false)
    }

    private fun endModules(disableModules: Boolean) {
        BuildingFeatures.modules.filterIsInstance<BuildingSessionParticipant>().forEach { module ->
            runCatching { module.endBuildingSession(disableModules) }
                .onFailure { LOGGER.warn("Unable to clean up registered building module after a session change", it) }
        }
    }

    override fun unregister() {
        shutdown()
        super<DonutEventListener>.unregister()
    }

    private val LOGGER = LoggerFactory.getLogger("Donut Building Session")
}
