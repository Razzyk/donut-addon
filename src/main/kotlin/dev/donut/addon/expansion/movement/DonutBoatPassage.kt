package dev.donut.addon.expansion.movement

import dev.donut.addon.AddonCategories
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.PositionAndRotation
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket
import net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket
import net.minecraft.tags.FluidTags
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.vehicle.boat.AbstractBoat
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import kotlin.math.floor
import kotlin.math.max

/** Plans short server-replayable steps before enabling local boat collision passthrough. */
object DonutBoatPassage : ClientModule("DonutBoatPassage", AddonCategories.UTILITY, disableOnQuit = true) {
    private val speed by float("Speed", 10f, 0f..320f)
    private val enclosedSpeed by float("SpeedInsideBlocks", 5f, 0f..320f)
    private val vertical by float("VerticalSpeed", 6f, 0f..320f)
    private val falling by float("FallSpeed", 0f, 0f..10f)
    private val avoidLava by boolean("LavaProtection", true)
    val antiKick by boolean("AntiFlyKick", true)
    val interval by int("Delay", 40, 1..80)
    private var boat: AbstractBoat? = null
    private var hadNoPhysics = false
    private var lastBase: Vec3? = null
    private var serverPosition: Vec3? = null
    private var stale = 0
    private var movingTicks = 0
    private var burst = 4.0
    private var correctionCap = Double.POSITIVE_INFINITY
    private var movedLastTick = false
    private var cullBefore: Boolean? = null
    init { literalDescription { "Move a boat through thin obstructions using bounded collision replay and packet steps." } }

    override fun onEnabled() { DonutMountFlight.enabled = false; VehicleRuntime.reset() }
    override fun onDisabled() { release(); VehicleRuntime.reset() }
    private fun release() {
        boat?.noPhysics = hadNoPhysics
        boat = null
        lastBase = null
        serverPosition = null
        stale = 0
        movingTicks = 0
        burst = 4.0
        correctionCap = Double.POSITIVE_INFINITY
        cullBefore?.let { mc.smartCull = it }
        cullBefore = null
    }
    fun refreshVehicle(current: Entity?) {
        if (!running || current !is AbstractBoat) { if (boat != null) release(); return }
        if (boat !== current) {
            release()
            boat = current
            hadNoPhysics = current.noPhysics
            cullBefore = mc.smartCull
        }
        if (mc.level != null) {
            val cameraBlock = BlockPos.containing(mc.gameRenderer.mainCamera().position())
            mc.smartCull = if (world.getBlockState(cameraBlock).isSolidRender) false else cullBefore ?: true
        }
    }
    fun corrected() {
        serverPosition = boat?.position()
        correctionCap = .5
    }

    private fun budget(current: AbstractBoat): Double {
        val base = current.positionCodec.base
        if (base != lastBase) {
            burst = max(burst, (movingTicks + 1).toDouble())
            movingTicks = 0
            stale = 0
            lastBase = base
            serverPosition = base
        } else {
            if (movedLastTick) movingTicks++
            if (++stale >= 40) serverPosition = current.position()
        }
        burst = max(2.0, burst - .025)
        val anchor = serverPosition ?: current.position().also { serverPosition = it }
        return max(0.0, minOf(19.0 / burst, 28.5 - current.position().distanceTo(anchor)))
    }

    fun plan(current: AbstractBoat, rider: LocalPlayer): Vec3 {
        refreshVehicle(current)
        current.noPhysics = true
        current.yRot = rider.yRot
        mc.connection?.send(ServerboundPaddleBoatPacket(!current.getPaddleState(0), current.getPaddleState(1)))
        if (VehicleRuntime.holding()) return Vec3.ZERO
        val enclosed = world.getBlockCollisions(current, current.boundingBox.deflate(.05)).iterator().hasNext() ||
            world.getEntities(current, current.boundingBox.deflate(.05)) { it !== rider && it.isAlive }.isNotEmpty()
        val flat = travelVector(rider, (if (enclosed) enclosedSpeed else speed).toDouble())
        var wanted = Vec3(flat.x, VehicleRuntime.vertical(vertical.toDouble(), falling.toDouble()), flat.z)
        val allowance = minOf(budget(current), correctionCap)
        correctionCap *= 1.25
        if (wanted.length() > allowance) wanted = wanted.normalize().scale(allowance)
        val start = current.position()
        var result = Vec3.ZERO
        val stops = ArrayList<Vec3>(16)
        val lava = if (avoidLava) lavaNear(current.boundingBox.expandTowards(wanted).inflate(4.0)) else emptyList()
        for (stepIndex in 0 until 16) {
            val left = wanted.subtract(result)
            if (left.lengthSqr() < 1e-8) break
            val trial = if (left.length() > 1.0) left.normalize() else left
            val box = current.boundingBox.move(result)
            val entities = world.getEntities(current, box.expandTowards(trial).inflate(1e-7)) {
                !it.isSpectator && it !== rider && AbstractBoat.canVehicleCollide(current, it)
            }.map { Shapes.create(it.boundingBox) }
            fun acceptable(fraction: Double): Boolean {
                val step = trial.scale(fraction)
                val replay = Entity.collideBoundingBox(current, step, box, world, entities)
                return kotlin.math.hypot(step.x - replay.x, step.z - replay.z) <= .24
            }
            var fraction = 1.0
            if (!acceptable(fraction)) {
                var low = 0.0
                var high = 1.0
                repeat(12) {
                    val middle = (low + high) * .5
                    if (acceptable(middle)) low = middle else high = middle
                }
                fraction = low
            }
            if (fraction < 1e-7) break
            val step = trial.scale(fraction)
            val swept = box.expandTowards(step)
            if (!chunksLoaded(swept)) break
            if (lava.isNotEmpty()) {
                val before = lava.minOf { distance(box, it) }
                val after = lava.minOf { distance(box.move(step), it) }
                if (after < 3.0 && after < before) break
            }
            result = result.add(step)
            stops.add(start.add(result))
        }
        stops.dropLast(1).forEach { at ->
            VehicleRuntime.send(ServerboundMoveVehiclePacket(PositionAndRotation.of(at, current.yRot, current.xRot), false))
        }
        movedLastTick = result.length() > .01
        current.deltaMovement = result
        return result
    }
    private fun chunksLoaded(box: AABB): Boolean {
        for (x in floor(box.minX / 16).toInt()..floor(box.maxX / 16).toInt())
            for (z in floor(box.minZ / 16).toInt()..floor(box.maxZ / 16).toInt())
                if (!world.hasChunk(x, z)) return false
        return true
    }
    private fun lavaNear(box: AABB): List<AABB> = BlockPos.betweenClosed(
        floor(box.minX).toInt(), floor(box.minY).toInt(), floor(box.minZ).toInt(),
        floor(box.maxX).toInt(), floor(box.maxY).toInt(), floor(box.maxZ).toInt())
        .filter { world.getFluidState(it).`is`(FluidTags.LAVA) }.map { AABB(it) }
    private fun distance(a: AABB, b: AABB): Double {
        val x = max(0.0, max(b.minX - a.maxX, a.minX - b.maxX))
        val y = max(0.0, max(b.minY - a.maxY, a.minY - b.maxY))
        val z = max(0.0, max(b.minZ - a.maxZ, a.minZ - b.maxZ))
        return kotlin.math.sqrt(x * x + y * y + z * z)
    }
    @JvmStatic fun hidesCollision(current: AbstractBoat): Boolean = running && current.controllingPassenger === mc.player
}
