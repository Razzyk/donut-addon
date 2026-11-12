package dev.donut.addon.expansion.presence

import dev.donut.addon.compat.DonutEventListener

import dev.donut.addon.AddonCategories
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.ClientShutdownEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.client.mc

/** Invalidates both in-flight responses and work already queued on the client thread. */
internal class PresenceRunGuard {
    @Volatile private var current: Any? = null

    @Synchronized fun begin(): Any = Any().also { current = it }
    @Synchronized fun cancel() { current = null }
    fun accepts(token: Any): Boolean = current === token

    @Synchronized fun apply(token: Any, action: () -> Unit): Boolean {
        if (!accepts(token)) return false
        action()
        return true
    }
}

/** ClientModule skips toggle callbacks outside a world; owned workers must still stop. */
abstract class DonutPresenceModule(name: String) : ClientModule(name, AddonCategories.UTILITY) {
    init {
        enabledValue.onChanged { state ->
            if (!state && !inGame) endPresenceSession()
        }
    }

    internal open fun endPresenceSession() = onDisabled()
    internal open fun beginPresenceSession() = onEnabled()
    internal open fun shutdownPresence() = endPresenceSession()
}

internal object PresenceSessionLifecycle : DonutEventListener {
    private var stopped = false
    private var pendingWorld: Any? = null

    private val worldChange = handler<WorldChangeEvent> { event ->
        if (!stopped) {
            PresenceFeatures.modules.forEach { it.endPresenceSession() }
            pendingWorld = event.world
        }
    }

    private val tick = handler<GameTickEvent> {
        val expected = pendingWorld ?: return@handler
        if (stopped || mc.level !== expected || mc.player == null || mc.connection == null) return@handler
        pendingWorld = null
        PresenceFeatures.modules.filter { it.enabled }.forEach { it.beginPresenceSession() }
    }

    private val shutdown = handler<ClientShutdownEvent> { shutdown() }

    fun shutdown() {
        if (stopped) return
        stopped = true
        pendingWorld = null
        PresenceFeatures.modules.forEach { it.shutdownPresence() }
    }

    override fun unregister() {
        shutdown()
        super<DonutEventListener>.unregister()
    }
}
