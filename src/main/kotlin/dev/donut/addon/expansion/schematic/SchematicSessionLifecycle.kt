package dev.donut.addon.expansion.schematic

import dev.donut.addon.compat.DonutEventListener

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.ClientShutdownEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.multiplayer.ClientLevel
import org.slf4j.LoggerFactory

/** Always-on cleanup and rehydration for schematic work that outlives module in-game hooks. */
internal object SchematicSessionLifecycle : DonutEventListener {
    @Volatile
    private var stopped = false
    private val worldStartGate = SchematicWorldStartGate<ClientLevel>()

    @Suppress("unused")
    private val worldChange = handler<WorldChangeEvent> { event ->
        if (!stopped) {
            SchematicFeatures.modules.filterIsInstance<DonutSchematicBuilder>()
                .forEach { module -> runCatching { module.onSchematicWorldChanging(event.world) }
                    .onFailure { LOGGER.warn("Unable to reset schematic builder for a world change", it) } }
            worldStartGate.request(event.world)
        }
    }

    @Suppress("unused")
    private val deferredWorldStart = handler<GameTickEvent> {
        if (stopped) return@handler
        val level = mc.level
        val ready = level != null && mc.player?.level() === level && mc.connection != null
        val world = worldStartGate.poll(level, ready) ?: return@handler
        SchematicFeatures.modules.filterIsInstance<DonutSchematicBuilder>()
            .filter { it.enabled }
            .forEach { module -> runCatching { module.onSchematicSessionStarted(world) }
                .onFailure { LOGGER.warn("Unable to resume schematic builder after a world change", it) } }
    }

    @Suppress("unused")
    private val disabledRestoration = handler<GameTickEvent> {
        if (stopped) return@handler
        SchematicFeatures.modules.filterIsInstance<DonutSchematicBuilder>()
            .filter { it.awaitingDisabledRestoration && !it.enabled }
            .forEach { module -> runCatching { module.onSchematicLifecycleTick() }
                .onFailure { LOGGER.warn("Unable to restore staged schematic inventory", it) } }
    }

    @Suppress("unused")
    private val restorationPackets = handler<PacketEvent> { event ->
        if (stopped || event.origin != TransferOrigin.INCOMING || !event.original) return@handler
        SchematicFeatures.modules.filterIsInstance<DonutSchematicBuilder>()
            .filter { it.awaitingDisabledRestoration }
            .forEach { module -> module.onSchematicLifecyclePacket(event.origin, event.original, event.packet) }
    }

    @Suppress("unused")
    private val clientShutdown = handler<ClientShutdownEvent> { shutdown() }

    @Synchronized
    fun shutdown() {
        if (stopped) return
        stopped = true
        worldStartGate.cancel()
        SchematicFeatures.modules.filterIsInstance<DonutSchematicBuilder>()
            .forEach { module -> runCatching { module.onSchematicShutdown() }
                .onFailure { LOGGER.warn("Unable to clean up schematic builder", it) } }
    }

    override fun unregister() {
        shutdown()
        super<DonutEventListener>.unregister()
    }

    private val LOGGER = LoggerFactory.getLogger("Donut Schematic Session")
}

/** Waits for the exact announced world and a usable player/connection before resuming. */
internal class SchematicWorldStartGate<W : Any> {
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
