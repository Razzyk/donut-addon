package dev.donut.addon.anubis

import dev.donut.addon.compat.DonutEventListener

import dev.donut.addon.anubis.event.impl.*
import dev.donut.addon.anubis.module.ClientModule
import dev.donut.addon.anubis.util.CompletionChannel
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.*
import net.ccbluex.liquidbounce.event.handler
import net.minecraft.network.protocol.Packet
import java.util.concurrent.CopyOnWriteArrayList

object AnubisEventBridge : DonutEventListener {
    private val modules = CopyOnWriteArrayList<ClientModule>()
    @JvmStatic fun register(module: ClientModule) { modules.addIfAbsent(module) }
    @JvmStatic fun clear() {
        modules.forEach { if (it.isEnabled) it.requestDisable() }
        modules.clear()
        CompletionChannel.INSTANCE.reset()
    }
    override fun unregister() { clear(); super.unregister() }
    private fun post(event: Any) { modules.forEach { it.dispatch(event) } }
    @JvmStatic fun packetApplied(packet: Packet<*>) { post(PacketAppliedEvent(packet)) }

    @Suppress("unused")
    private val tick = handler<GameTickEvent> { post(TickEvent()) }
    @Suppress("unused")
    private val packet = handler<PacketEvent> {
        if (it.origin == TransferOrigin.INCOMING) {
            CompletionChannel.INSTANCE.receive(it.packet)
            post(PacketReceiveNettyEvent(it.packet))
        }
    }
    @Suppress("unused")
    private val key = handler<KeyEvent> { post(KeyInputEvent(it.key.value, it.action)) }
    @Suppress("unused")
    private val mouse = handler<MouseButtonEvent> { post(MouseInputEvent(it.button, it.action)) }
    @Suppress("unused")
    private val attack = handler<AttackEntityEvent> { post(PlayerAttackEntityEvent(it.entity)) }
    @Suppress("unused")
    private val world = handler<WorldChangeEvent> { event ->
        if (event.world == null) {
            modules.forEach { module -> if (module.isEnabled) module.requestDisable() }
        }
        CompletionChannel.INSTANCE.reset()
    }
}
