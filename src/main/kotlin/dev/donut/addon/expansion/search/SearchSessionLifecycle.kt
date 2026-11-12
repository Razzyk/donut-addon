package dev.donut.addon.expansion.search

import dev.donut.addon.compat.DonutEventListener

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.ClientShutdownEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.multiplayer.ClientLevel
import org.slf4j.LoggerFactory

/** Releases world-scoped scan evidence even when a native module callback is paused. */
object SearchSessionLifecycle : DonutEventListener {
    private var stopped = false
    private var announced = false
    private var expectedWorld: ClientLevel? = null

    private val worlds = handler<WorldChangeEvent> { event ->
        if (stopped) return@handler
        announced = true
        expectedWorld = event.world
        SearchFeatures.GROWTH_INSPECTOR.endInspectorSession()
        SearchFeatures.GROWTH_SURVEY.endSearchSession()
        SearchFeatures.SEED_INSPECTOR.endSeedSession()
        dev.donut.addon.expansion.search.remote.RemoteActivityCapture.onWorldChanging(event.world)
        SearchFeatures.SPAWNER_SIGNPOSTS.endSearchSession()
    }

    private val tick = handler<GameTickEvent> {
        if (stopped) return@handler
        val level = mc.level
        val ready = level != null && mc.player?.level() === level && mc.connection != null &&
            (!announced || expectedWorld === level)
        SearchFeatures.GROWTH_INSPECTOR.onInspectorLifecycleTick(ready)
        SearchFeatures.SEED_INSPECTOR.seedLifecycleTick(ready)
    }

    private val stopping = handler<ClientShutdownEvent> { shutdown() }

    fun shutdown() {
        if (stopped) return
        stopped = true
        expectedWorld = null
        dev.donut.addon.expansion.search.remote.RemoteActivityCapture.shutdown()
        SearchFeatures.modules.forEach { module ->
            runCatching { module.onDeactivate() }
                .onFailure { LOGGER.warn("Unable to release search module {}", module.name, it) }
        }
        SearchFeatures.REMOTE_ACTIVITY.shutdown()
        dev.donut.addon.expansion.search.remote.RemoteKnownBaseStore.shutdownShared()
        SearchFeatures.SPAWNER_SIGNPOSTS.endSearchSession()
        SearchFeatures.clearPendingEvidence()
    }

    override fun unregister() {
        shutdown()
        super<DonutEventListener>.unregister()
    }

    private val LOGGER = LoggerFactory.getLogger("Donut Search Session")
}
