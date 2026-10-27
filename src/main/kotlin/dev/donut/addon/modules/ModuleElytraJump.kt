package dev.donut.addon.modules

import dev.donut.addon.Gliding
import net.ccbluex.liquidbounce.event.events.BlinkPacketEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PlayerTickEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.SprintEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.blink.BlinkManager
import net.ccbluex.liquidbounce.features.module.ClientModule
import dev.donut.addon.AddonCategories
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.phys.AABB
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.cos

/**
 * Jumps and starts an elytra glide whenever there is a nearby landing surface.
 * The optional steering and packet queue settings mirror SlimefunHelper's ElytraJump behavior.
 */
object ModuleElytraJump : ClientModule("ElytraJump", AddonCategories.DONUT) {

    private val pitch by float("Pitch", 80f, -90f..90f)
    private val sneak by boolean("Sneak", false)
    private val groundHeight by float("GroundHeight", 3f, 1f..8f)
    private val axisStrict by boolean("AxisStrict", false)
    private val autoMineObstacles by boolean("AutoMineObstacles", false)
    private val autoAvoidObstacles by boolean("AutoAvoidObstacles", false)
    private val avoidObstacleYawRange by int("AvoidObstacleYawRange", 50, 0..90)
    private val predictTicks by int("PredictTicks", 80, 1..120)
    private val avoidHoles by boolean("AvoidHoles", false)
    private val usePacketQueue by boolean("QueuePackets", false)
    private val packetQueueMaxTicks by int("PacketQueueMaxTicks", 8, 1..20)

    private var workThisTick = false
    private var landingY: Int? = null
    private var queuedAfterJump = false
    private var queuedTicks = 0
    private var lastMined: BlockPos? = null
    private var lastFallFlying = false

    @Suppress("unused")
    private val rotationHandler = handler<RotationUpdateEvent> {
        val landing = findLandingBlock() ?: return@handler
        val controlPitch = player.isFallFlying || lastFallFlying
        if (!controlPitch && !axisStrict && !autoAvoidObstacles && !avoidHoles) {
            return@handler
        }

        var yaw = player.yRot
        if (axisStrict) {
            yaw = snapToAxis(yaw)
        }
        if (autoAvoidObstacles || avoidHoles) {
            yaw = chooseClearYaw(yaw, landing.y)
        }

        // Submit before RotationManager's update so physics and packets use the same rotation.
        // STRICT keeps forward input following the steering yaw without changing the camera.
        RotationManager.setRotationTarget(
            RotationTarget(
                rotation = Rotation(yaw, if (controlPitch) pitch else player.xRot),
                ticksUntilReset = 1,
                movementCorrection = MovementCorrection.STRICT
            ),
            Priority.IMPORTANT_FOR_USAGE_1,
            this@ModuleElytraJump
        )
    }

    @Suppress("unused")
    private val tickHandler = handler<PlayerTickEvent> {
        val landing = findLandingBlock()
        landingY = landing?.y
        workThisTick = landing != null

        if (!workThisTick) {
            stopMining()
            if (queuedAfterJump) {
                flushQueuedPackets()
            }
            lastFallFlying = player.isFallFlying
            return@handler
        }

        if (queuedAfterJump && ++queuedTicks >= packetQueueMaxTicks) {
            flushQueuedPackets()
        }

        if (autoMineObstacles) {
            mineNearbyObstacle(landingY!!)
        } else {
            stopMining()
        }

        lastFallFlying = player.isFallFlying
    }

    @Suppress("unused")
    private val movementInputHandler = handler<MovementInputEvent> { event ->
        // Input can be requested before PlayerTickEvent in a client tick, so sample the ground
        // here as well to avoid steering from stale landing data.
        landingY = findLandingBlock()?.y
        workThisTick = landingY != null
        if (!workThisTick) {
            return@handler
        }

        event.directionalInput = DirectionalInput.FORWARDS
        event.sneak = sneak

        if (player.onGround()) {
            event.jump = true
            player.isSprinting = true
            if (queuedAfterJump) {
                flushQueuedPackets()
            }
            queuedAfterJump = usePacketQueue
            queuedTicks = 0
        } else {
            event.jump = false
            player.isSprinting = true

            val chest = player.getItemBySlot(EquipmentSlot.CHEST)
            if (!player.isFallFlying && Gliding.isUsable(chest) && Gliding.canContinue(player)) {
                Gliding.start(player)
            }
        }
    }

    @Suppress("unused")
    private val sprintHandler = handler<SprintEvent> { event ->
        if (workThisTick) {
            event.sprint = true
        }
    }

    @Suppress("unused")
    private val blinkPacketHandler = handler<BlinkPacketEvent> { event ->
        if (usePacketQueue && queuedAfterJump && event.origin == TransferOrigin.OUTGOING) {
            // BlinkManager keeps its built-in pass rules for chat, keepalive, and session packets.
            // Bound this module's queued burst even if the player stays inside GroundHeight.
            if (BlinkManager.packetQueue.size < MAX_QUEUED_PACKETS) {
                event.action = BlinkManager.Action.QUEUE
            } else {
                flushQueuedPackets()
            }
        }
    }

    override fun onDisabled() {
        flushQueuedPackets()
        stopMining()
        workThisTick = false
        landingY = null
        lastFallFlying = false
        super.onDisabled()
    }

    private fun flushQueuedPackets() {
        if (queuedAfterJump) {
            queuedAfterJump = false
            queuedTicks = 0
            if (inGame) {
                // BlinkManager is shared across modules, but this module only starts queuing during
                // its own low-altitude jump/glide window, then asks it to release outgoing packets.
                BlinkManager.flush(TransferOrigin.OUTGOING)
            }
        }
    }

    /** Returns the highest colliding block under the player within GroundHeight. */
    private fun findLandingBlock(): BlockPos? {
        val playerBox = player.boundingBox
        val searchBox = playerBox.expandTowards(0.0, -groundHeight.toDouble(), 0.0)
        val minX = floor(searchBox.minX).toInt()
        val maxX = ceil(searchBox.maxX).toInt()
        val minY = floor(searchBox.minY).toInt()
        val maxY = ceil(playerBox.minY).toInt()
        val minZ = floor(searchBox.minZ).toInt()
        val maxZ = ceil(searchBox.maxZ).toInt()
        var best: BlockPos? = null

        for (x in minX..maxX) for (y in minY..maxY) for (z in minZ..maxZ) {
            val pos = BlockPos(x, y, z)
            val state = world.getBlockState(pos)
            val shape = state.getCollisionShape(world, pos)
            if (shape.isEmpty) continue

            val shapeBox = shape.bounds().move(pos)
            if (shapeBox.intersects(searchBox) && (best == null || pos.y > best.y)) {
                best = pos
            }
        }
        return best
    }

    private fun chooseClearYaw(currentYaw: Float, baseY: Int): Float {
        val maxYaw = avoidObstacleYawRange
        var bestYaw = currentYaw
        var bestDistance = -1

        for (offset in 0..maxYaw) {
            val candidates = if (offset == 0) listOf(currentYaw) else listOf(currentYaw + offset, currentYaw - offset)
            for (candidate in candidates) {
                val clearDistance = clearDistance(candidate, baseY)
                if (clearDistance > bestDistance) {
                    bestDistance = clearDistance
                    bestYaw = candidate
                }
                if (clearDistance >= predictTicks) return candidate
            }
        }
        return bestYaw
    }

    /** Counts clear flight steps until a wall or a missing support surface is found. */
    private fun clearDistance(yaw: Float, baseY: Int): Int {
        val velocity = player.deltaMovement.horizontalDistance().coerceAtLeast(0.12)
        val radians = Math.toRadians(yaw.toDouble())
        val dirX = -sin(radians)
        val dirZ = cos(radians)

        for (step in 1..predictTicks) {
            val x = player.x + dirX * velocity * step
            val z = player.z + dirZ * velocity * step
            if (autoAvoidObstacles && collidesAt(x, z, baseY + 1, 3)) return step - 1
            if (avoidHoles && !hasGroundAt(x, z, baseY, yaw)) return step - 1
        }
        return predictTicks
    }

    private fun collidesAt(x: Double, z: Double, baseY: Int, height: Int): Boolean {
        val box = AABB(x - 0.4, baseY.toDouble(), z - 0.4, x + 0.4, baseY + height.toDouble(), z + 0.4)
        val minX = floor(box.minX).toInt()
        val maxX = ceil(box.maxX).toInt()
        val minY = floor(box.minY).toInt()
        val maxY = ceil(box.maxY).toInt()
        val minZ = floor(box.minZ).toInt()
        val maxZ = ceil(box.maxZ).toInt()
        for (blockX in minX..maxX) for (y in minY..maxY) for (blockZ in minZ..maxZ) {
            val pos = BlockPos(blockX, y, blockZ)
            val shape = world.getBlockState(pos).getCollisionShape(world, pos)
            if (!shape.isEmpty && shape.bounds().move(pos).intersects(box)) return true
        }
        return false
    }

    private fun hasGroundAt(x: Double, z: Double, y: Int, yawDegrees: Float): Boolean {
        // Sample the center and both sides of the player's 0.8 block footprint.
        val yaw = Math.toRadians(yawDegrees.toDouble())
        val sideX = cos(yaw) * 0.3
        val sideZ = sin(yaw) * 0.3
        return listOf(0.0 to 0.0, sideX to sideZ, -sideX to -sideZ).any { (offsetX, offsetZ) ->
            val pos = BlockPos.containing(x + offsetX, y.toDouble(), z + offsetZ)
            val shape = world.getBlockState(pos).getCollisionShape(world, pos)
            !shape.isEmpty && shape.bounds().move(pos).maxY >= y + 0.99
        }
    }

    private fun mineNearbyObstacle(baseY: Int) {
        val yaw = Math.toRadians(RotationManager.movementYaw.toDouble())
        val x = player.x - sin(yaw) * 1.5
        val z = player.z + cos(yaw) * 1.5
        val candidates = buildList {
            for (blockX in floor(x - 0.4).toInt()..ceil(x + 0.4).toInt()) {
                for (y in baseY + 1..baseY + 3) {
                    for (blockZ in floor(z - 0.4).toInt()..ceil(z + 0.4).toInt()) {
                        val pos = BlockPos(blockX, y, blockZ)
                        val state = world.getBlockState(pos)
                        val hardness = state.getDestroySpeed(world, pos)
                        if (!state.isAir && state.fluidState.isEmpty && hardness >= 0f && hardness < 3f) {
                            add(pos)
                        }
                    }
                }
            }
        }.distinct().take(5)

        val target = lastMined?.takeIf(candidates::contains) ?: candidates.firstOrNull()
        if (target == null) {
            stopMining()
            return
        }

        if (target == lastMined) {
            interaction.continueDestroyBlock(target, Direction.UP)
        } else {
            stopMining()
            interaction.startDestroyBlock(target, Direction.UP)
            lastMined = target
        }
    }

    private fun stopMining() {
        if (lastMined != null) {
            if (inGame) {
                interaction.stopDestroyBlock()
            }
            lastMined = null
        }
    }

    private fun snapToAxis(yaw: Float): Float {
        val normalized = ((yaw % 360f) + 360f) % 360f
        val nearest = round(normalized / 45f) * 45f % 360f
        var difference = kotlin.math.abs(normalized - nearest)
        if (difference > 180f) difference = 360f - difference
        return if (difference <= 10f) {
            if (nearest > 180f) nearest - 360f else nearest
        } else yaw
    }

    private const val MAX_QUEUED_PACKETS = 256
}
