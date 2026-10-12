package dev.donut.addon

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket

/** Tracks server permission independently of Fly modes that change the local abilities object. */
object ServerFlightPermissions : EventListener {
    @Volatile private var seenPacket = false
    @Volatile private var mayFly = false

    fun allowsFlying(player: LocalPlayer): Boolean = if (seenPacket) mayFly else player.abilities.mayfly

    @Suppress("unused")
    private val packet = handler<PacketEvent> { event ->
        val incoming = event.packet
        if (event.origin == TransferOrigin.INCOMING && incoming is ClientboundPlayerAbilitiesPacket) {
            mayFly = incoming.canFly()
            seenPacket = true
        }
    }

    @Suppress("unused")
    private val world = handler<WorldChangeEvent> {
        if (it.world == null) {
            seenPacket = false
            mayFly = false
        }
    }
}
