package dev.donut.addon.expansion.movement

import dev.donut.addon.compat.DonutEventListener

import dev.donut.addon.AddonCategories
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.*
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.input.InputTracker.isPressedOnAny
import net.minecraft.client.Minecraft
import net.minecraft.core.PositionAndRotation
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.vehicle.boat.AbstractBoat
import net.minecraft.world.phys.Vec3

object DonutMountPace : ClientModule("DonutMountPace", AddonCategories.UTILITY) {
    private val speed by float("HorizontalSpeed", 10f, 0f..50f)
    private val ground by boolean("OnlyOnGround", false)
    private val water by boolean("InWater", true)
    init { literalDescription { "Set ridden-entity speed with ground and water conditions." } }
    fun speedFor(vehicle: Entity): Double? = if (running && (!ground || vehicle.onGround() || vehicle.isInWater) &&
        (water || !vehicle.isInWater)) speed.toDouble() else null
}

object DonutMountControl : ClientModule("DonutMountControl", AddonCategories.UTILITY) {
    private val boats by boolean("Boats", true)
    private val horses by boolean("Horses", true)
    private val camels by boolean("Camels", true)
    private val pigs by boolean("Pigs", true)
    private val striders by boolean("Striders", true)
    private val nautiluses by boolean("Nautiluses", true)
    private val ghasts by boolean("HappyGhasts", true)
    private val saddle by boolean("SpoofSaddle", false)
    private val jump by boolean("MaxJump", true)
    private val yaw by boolean("LockYaw", true)
    private val corrections by boolean("CancelServerPackets", false)
    init { literalDescription { "Choose rideable families for yaw control, local saddle checks and full jump charge." } }

    fun affects(vehicle: Entity): Boolean {
        if (!running) return false
        val id = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.type).path
        return when {
            vehicle is AbstractBoat -> boats
            id in setOf("horse", "donkey", "mule", "zombie_horse", "skeleton_horse") -> horses
            id.contains("camel") -> camels
            id == "pig" -> pigs
            id == "strider" -> striders
            id.contains("nautilus") -> nautiluses
            id == "happy_ghast" -> ghasts
            else -> false
        }
    }
    fun locks(vehicle: Entity): Boolean = affects(vehicle) && yaw
    @JvmStatic fun saddleFor(vehicle: Entity): Boolean = saddle && affects(vehicle)
    @JvmStatic fun fullJump(): Boolean = jump && mc.player?.vehicle?.let(::affects) == true
    private val packets = handler<PacketEvent> { event ->
        if (corrections && event.origin == TransferOrigin.INCOMING && event.packet is ClientboundMoveVehiclePacket &&
            mc.player?.vehicle?.let(::affects) == true) event.cancelEvent()
    }
}

object DonutMountFlight : ClientModule("DonutMountFlight", AddonCategories.UTILITY, disableOnQuit = true) {
    private val speed by float("Speed", 10f, 0f..50f)
    private val vertical by float("VerticalSpeed", 6f, 0f..20f)
    private val fall by float("FallSpeed", 0f, 0f..10f)
    val antiKick by boolean("AntiFlyKick", true)
    val interval by int("Delay", 40, 1..80)
    private val corrections by boolean("CancelServerPackets", false)
    init { literalDescription { "Fly a ridden vehicle, with a timed descent pulse and optional correction filtering." } }
    override fun onEnabled() { DonutBoatPassage.enabled = false; VehicleRuntime.reset() }
    override fun onDisabled() { VehicleRuntime.reset() }
    fun horizontal(): Double = speed.toDouble()
    fun vertical(): Double = VehicleRuntime.vertical(vertical.toDouble(), fall.toDouble())
    private val packet = handler<PacketEvent> { event ->
        if (corrections && event.origin == TransferOrigin.INCOMING && event.packet is ClientboundMoveVehiclePacket &&
            mc.player?.vehicle != null) event.cancelEvent()
    }
}

/** Shared motion hook runs at Entity.move, after the vehicle's own tick has applied gravity and steering. */
object VehicleRuntime : DonutEventListener {
    private val mc get() = Minecraft.getInstance()
    private val cadence = VehicleCadence()
    private var owner: Entity? = null
    private var internalPacket = false
    fun reset() { owner = null; cadence.reset(40) }
    override val running: Boolean get() = mc.player != null && mc.level != null &&
        (DonutMountFlight.running || DonutBoatPassage.running)
    private fun interval(): Int = if (DonutBoatPassage.running) DonutBoatPassage.interval else DonutMountFlight.interval
    private fun pulseEnabled(): Boolean = if (DonutBoatPassage.running) DonutBoatPassage.antiKick else DonutMountFlight.antiKick

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
        if (DonutBoatPassage.running && vehicle is AbstractBoat) return DonutBoatPassage.plan(vehicle, p)
        val flight = DonutMountFlight.running
        val speed = DonutMountPace.speedFor(vehicle) ?: if (flight) DonutMountFlight.horizontal() else null
        if (flight || DonutMountControl.locks(vehicle)) vehicle.yRot = p.yRot
        if (speed == null && !flight) return original
        val flat = speed?.let { travelVector(p, it) }
        val result = Vec3(flat?.x ?: original.x, if (flight) DonutMountFlight.vertical() else original.y, flat?.z ?: original.z)
        if (original === vehicle.deltaMovement) vehicle.deltaMovement = result
        return result
    }
    @JvmStatic fun lockBoat(boat: AbstractBoat): Boolean = boat.controllingPassenger === mc.player &&
        (DonutBoatPassage.running || DonutMountFlight.running || DonutMountControl.locks(boat))
    @JvmStatic fun suppressMountJump(): Boolean = DonutMountFlight.running && mc.player?.vehicle != null
    fun holding(): Boolean = pulseEnabled() && cadence.isHolding(System.nanoTime())
}
