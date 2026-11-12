package dev.donut.addon.expansion.utility

import dev.donut.addon.compat.DonutEventListener

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.ClientShutdownEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.multiplayer.ClientLevel
import org.slf4j.LoggerFactory

/** Owns utility resources across disconnects and addon teardown, outside module in-game hooks. */
internal object UtilitySessionLifecycle : DonutEventListener {
    @Volatile
    private var stopped = false
    private val worldStartGate = UtilityWorldStartGate<ClientLevel>()

    @Suppress("unused")
    private val worldChange = handler<WorldChangeEvent> { event ->
        if (!stopped) {
            UtilityFeatures.modules.filterIsInstance<DonutUtilityModule>()
                .forEach { module -> runWorldCleanup(module, event.world) }
            worldStartGate.request(event.world)
        }
    }

    @Suppress("unused")
    private val deferredWorldStart = handler<GameTickEvent> {
        if (stopped) return@handler
        val level = mc.level
        val ready = level != null && mc.player?.level() === level && mc.connection != null
        val world = worldStartGate.poll(level, ready) ?: return@handler
        UtilityFeatures.modules.filterIsInstance<DonutUtilityModule>()
            .filter { it.enabled }
            .forEach { module ->
                runCatching { module.onUtilitySessionStarted(world) }
                    .onFailure { LOGGER.warn("Unable to resume utility module {} after a world change", module.name, it) }
            }
    }

    @Suppress("unused")
    private val clientShutdown = handler<ClientShutdownEvent> {
        shutdown()
    }

    @Synchronized
    fun shutdown() {
        if (stopped) return
        stopped = true
        worldStartGate.cancel()
        UtilityFeatures.modules.filterIsInstance<DonutUtilityModule>()
            .forEach(::runCleanup)
    }

    private fun runCleanup(module: DonutUtilityModule) {
        runCatching { module.onUtilityShutdown() }
            .onFailure { LOGGER.warn("Unable to clean up utility module {}", module.name, it) }
    }

    private fun runWorldCleanup(module: DonutUtilityModule, world: ClientLevel?) {
        runCatching { module.onUtilityWorldChanging(world) }
            .onFailure { LOGGER.warn("Unable to reset utility module {} for a world change", module.name, it) }
    }

    override fun unregister() {
        shutdown()
        super<DonutEventListener>.unregister()
    }

    private val LOGGER = LoggerFactory.getLogger("Donut Utility Session")
}

/** Waits until the exact world announced by an early engine event is installed and usable. */
internal class UtilityWorldStartGate<W : Any> {
    private var pending = false
    private var expected: W? = null

    fun request(world: W?) {
        expected = world
        pending = world != null
    }

    fun poll(currentWorld: W?, sessionReady: Boolean): W? {
        val target = expected ?: return null
        if (!pending || currentWorld !== target || !sessionReady) return null
        pending = false
        expected = null
        return target
    }

    fun cancel() {
        pending = false
        expected = null
    }
}
