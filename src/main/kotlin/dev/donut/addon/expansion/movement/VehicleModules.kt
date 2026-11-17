package dev.donut.addon.expansion.movement

import dev.donut.addon.compat.DonutEventListener

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.*
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.input.InputTracker.isPressedOnAny
import net.minecraft.client.Minecraft
import net.minecraft.core.PositionAndRotation
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.vehicle.boat.AbstractBoat
import net.minecraft.world.phys.Vec3

/** Shared motion hook runs at Entity.move, after the vehicle's own tick has applied gravity and steering. */
object VehicleRuntime : DonutEventListener {
    private val mc get() = Minecraft.getInstance()
    private val cadence = VehicleCadence()
    private var owner: Entity? = null
    private var internalPacket = false
    fun reset() { owner = null; cadence.reset(40) }
    override val running: Boolean get() = mc.player != null && mc.level != null && DonutBoatPassage.running
    private fun interval(): Int = DonutBoatPassage.interval
    private fun pulseEnabled(): Boolean = DonutBoatPassage.antiKick

    fun vertical(upSpeed: Double, fallSpeed: Double): Double {
        if (mc.gui.screen() != null) return -fallSpeed / 20.0
        return (if (mc.options.keyJump.isPressedOnAny) upSpeed else 0.0) / 20.0 -
            (if (mc.options.keySprint.isPressedOnAny) upSpeed else fallSpeed) / 20.0
    }
    private fun track(vehicle: Entity?) {
        if (owner === vehicle) return
        owner = vehicle
        cadence.reset(interval())
    }
    fun send(packet: ServerboundMoveVehiclePacket) {
        internalPacket = true
        try { mc.connection?.send(packet) } finally { internalPacket = false }
    }
    private val tick = handler<GameTickEvent> {
        val vehicle = mc.player?.vehicle
        track(vehicle)
        cadence.tick()
        if (vehicle != null) cadence.recovery(System.nanoTime())?.let { y ->
            val pos = vehicle.position()
            send(ServerboundMoveVehiclePacket(PositionAndRotation.of(Vec3(pos.x, y, pos.z), vehicle.yRot, vehicle.xRot),
                vehicle.onGround()))
        }
        DonutBoatPassage.refreshVehicle(vehicle)
    }
    private val packet = handler<PacketEvent> { event ->
        if (event.origin == TransferOrigin.INCOMING && event.packet is ClientboundMoveVehiclePacket) {
            cadence.reset(interval())
            DonutBoatPassage.corrected()
            return@handler
        }
        if (event.origin != TransferOrigin.OUTGOING || internalPacket) return@handler
        val packet = event.packet as? ServerboundMoveVehiclePacket ?: return@handler
        val vehicle = mc.player?.vehicle ?: return@handler
        track(vehicle)
        if (!pulseEnabled()) { cadence.reset(interval()); return@handler }
        val pos = packet.movingTo().position()
        val unsupported = mc.level?.getBlockStates(vehicle.boundingBox.inflate(.0625).expandTowards(0.0, -.55, 0.0))
            ?.allMatch { it.isAir } == true && !vehicle.isInWater
        when (cadence.accept(pos.y, unsupported, mc.isLocalServer, interval(), System.nanoTime())) {
            VehicleCadence.Action.HOLD -> event.cancelEvent()
            VehicleCadence.Action.DIP -> {
                event.cancelEvent()
                val moved = PositionAndRotation.of(pos.add(0.0, -.0313, 0.0), packet.movingTo().yRot(), packet.movingTo().xRot())
                send(ServerboundMoveVehiclePacket(moved, packet.onGround()))
            }
            VehicleCadence.Action.PASS -> Unit
        }
    }

    @JvmStatic
    fun steer(vehicle: Entity, type: MoverType, original: Vec3): Vec3 {
        val p = mc.player ?: return original
        if (type != MoverType.SELF || mc.level == null || p.vehicle !== vehicle || vehicle.controllingPassenger !== p)
            return original
        if (!DonutBoatPassage.running || vehicle !is AbstractBoat) return original
        val result = DonutBoatPassage.plan(vehicle, p)
        if (original === vehicle.deltaMovement) vehicle.deltaMovement = result
        return result
    }
    @JvmStatic fun lockBoat(boat: AbstractBoat): Boolean = boat.controllingPassenger === mc.player &&
        DonutBoatPassage.running
    fun holding(): Boolean = pulseEnabled() && cadence.isHolding(System.nanoTime())
}
