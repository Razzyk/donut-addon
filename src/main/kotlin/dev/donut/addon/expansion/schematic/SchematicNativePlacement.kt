package dev.donut.addon.expansion.schematic

import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.BlockItem
import net.minecraft.core.component.DataComponents
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.AABB
import net.minecraft.world.entity.player.Player
import kotlin.math.abs

/** A native BlockItem prediction that matches the requested schematic state from this stand. */
data class SchematicPlacementCandidate(
    val support: BlockPos,
    val hit: BlockHitResult,
    val rotation: Rotation,
    val predictedState: BlockState,
)

/**
 * Finds a real vanilla placement context which predicts the schematic state. The builder uses the
 * same BlockItem context transformation and Block.getStateForPlacement path used by vanilla item
 * placement, including hit position, face, player orientation, and BLOCK_STATE item overrides.
 */
object SchematicNativePlacement {
    private val faceSamples = doubleArrayOf(0.16, 0.5, 0.84)

    fun findCandidate(
        world: Level,
        player: Player,
        target: BlockPos,
        expected: BlockState,
        stack: ItemStack,
        eye: Vec3 = player.eyePosition,
        allowRelaxedOrientation: Boolean = false,
        allowProvisionalChest: Boolean = false,
        allowPartialDoubleSlab: Boolean = false,
        coarseSearch: Boolean = false,
        referenceRotation: Rotation = RotationManager.serverRotation,
        normalizeAim: (Rotation) -> Rotation = { it.normalize() },
    ): SchematicPlacementCandidate? {
        val blockItem = stack.item as? BlockItem ?: return null
        val expectedId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(expected.block).toString()
        val expectedProperties = stateProperties(expected)
        if (!expected.canSurvive(world, target)) return null
        val reach = player.blockInteractionRange().toDouble()
        val reachSquared = reach * reach
        var best: SchematicPlacementCandidate? = null
        var bestRotationCost = Float.POSITIVE_INFINITY
        val samples = if (coarseSearch) doubleArrayOf(0.18, 0.82) else faceSamples

        for (targetDirection in Direction.values()) {
            val support = target.relative(targetDirection)
            if (!world.hasChunkAt(support)) continue
            val face = targetDirection.opposite
            if (world.getBlockState(support).getShape(world, support).isEmpty) continue

            for (u in samples) for (v in samples) {
                val sample = faceSample(support, face, u, v)
                val aimedRotation = normalizeAim(Rotation.lookingAt(sample, eye))
                val rayEnd = eye.add(aimedRotation.directionVector.scale(reach))
                val ray = world.clip(ClipContext(eye, rayEnd, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
                    as? BlockHitResult ?: continue
                if (ray.type != HitResult.Type.BLOCK || ray.blockPos != support || ray.direction != face) continue
                if (eye.distanceToSqr(ray.location) > reachSquared) continue

                val predicted = predictWithNativeContext(player, blockItem, stack, ray, aimedRotation, target) ?: continue
                if (!playerClearOfPlacement(world, player, target, predicted, eye)) continue
                if (!matchesExpected(
                        expectedId, expectedProperties, predicted,
                        allowRelaxedOrientation, allowProvisionalChest, allowPartialDoubleSlab,
                    )) continue

                val cost = aimedRotation.rotationDeltaLengthTo(referenceRotation)
                if (cost < bestRotationCost) {
                    bestRotationCost = cost
                    best = SchematicPlacementCandidate(support.immutable(), ray, aimedRotation, predicted)
                }
            }
        }
        return best
    }

    /** Checks the predicted block against the body at the candidate stand without moving the player. */
    private fun playerClearOfPlacement(
        world: Level,
        player: Player,
        target: BlockPos,
        predicted: BlockState,
        candidateEye: Vec3,
    ): Boolean {
        val currentEye = player.eyePosition
        val bodyOffset = candidateEye.subtract(currentEye)
        val candidateBody = player.boundingBox.move(bodyOffset.x, bodyOffset.y, bodyOffset.z)
        val collision = predicted.getCollisionShape(world, target)
            .move(target.x.toDouble(), target.y.toDouble(), target.z.toDouble())
        // Vanilla's collision check ignores the player passed here, so keep an explicit check
        // against that player's translated body while still rejecting every other entity.
        return world.isUnobstructed(player, collision) &&
            collision.toAabbs().none { box: AABB -> box.intersects(candidateBody) }
    }

    private fun predictWithNativeContext(
        player: net.minecraft.world.entity.player.Player,
        blockItem: BlockItem,
        stack: ItemStack,
        hit: BlockHitResult,
        rotation: Rotation,
        target: BlockPos,
    ): BlockState? {
        val oldYaw = player.yRot
        val oldPitch = player.xRot
        try {
            // BlockPlaceContext reads the player's look direction. LiquidBounce's managed rotation
            // is not necessarily copied to player.yRot while STRICT movement correction is active.
            player.yRot = rotation.yaw
            player.xRot = rotation.pitch
            val rawContext = net.minecraft.world.item.context.BlockPlaceContext(
                player, InteractionHand.MAIN_HAND, stack, hit,
            )
            val context = blockItem.updatePlacementContext(rawContext) ?: return null
            if (context.clickedPos != target || !context.canPlace()) return null
            var state = blockItem.block.getStateForPlacement(context) ?: return null
            stack.get(DataComponents.BLOCK_STATE)?.let { state = it.apply(state) }
            return state
        } catch (_: RuntimeException) {
            return null
        } finally {
            player.yRot = oldYaw
            player.xRot = oldPitch
        }
    }

    internal fun matchesExpected(
        expectedId: String,
        expectedProperties: Map<String, String>,
        predicted: BlockState,
        allowRelaxedOrientation: Boolean,
        allowProvisionalChest: Boolean,
        allowPartialDoubleSlab: Boolean,
    ): Boolean {
        val actualId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(predicted.block).toString()
        val actualProperties = stateProperties(predicted)
        if (SchematicStateRules.matches(expectedId, expectedProperties, actualId, actualProperties, allowRelaxedOrientation)) {
            return true
        }
        if (allowProvisionalChest && SchematicStructureRules.isChestProvisional(
                expectedId, expectedProperties, actualId, actualProperties, partnerExpected = true,
            )) return true
        return allowPartialDoubleSlab && SchematicStructureRules.isDoubleSlabPending(
            expectedId, expectedProperties, actualId, actualProperties,
        )
    }

    internal fun matchesNativeState(
        expected: BlockState,
        predicted: BlockState,
        allowRelaxedOrientation: Boolean = false,
        allowProvisionalChest: Boolean = false,
        allowPartialDoubleSlab: Boolean = false,
    ): Boolean = matchesExpected(
        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(expected.block).toString(),
        stateProperties(expected),
        predicted,
        allowRelaxedOrientation,
        allowProvisionalChest,
        allowPartialDoubleSlab,
    )

    private fun stateProperties(state: BlockState): Map<String, String> =
        state.properties.associate { property -> property.name to state.getValue(property).toString().lowercase() }

    private fun faceSample(support: BlockPos, face: Direction, first: Double, second: Double): Vec3 = when (face.axis) {
        Direction.Axis.X -> Vec3(
            support.x + if (face == Direction.EAST) 0.999 else 0.001,
            support.y + first,
            support.z + second,
        )
        Direction.Axis.Y -> Vec3(
            support.x + first,
            support.y + if (face == Direction.UP) 0.999 else 0.001,
            support.z + second,
        )
        Direction.Axis.Z -> Vec3(
            support.x + first,
            support.y + second,
            support.z + if (face == Direction.SOUTH) 0.999 else 0.001,
        )
    }

    fun rotationAcknowledged(
        targetYaw: Float,
        targetPitch: Float,
        currentYaw: Float?,
        currentPitch: Float?,
        serverYaw: Float,
        serverPitch: Float,
        tolerance: Float = 0.1f,
    ): Boolean {
        if (currentYaw == null || currentPitch == null) return false
        return angleDistance(targetYaw, currentYaw) <= tolerance &&
            abs(targetPitch - currentPitch) <= tolerance &&
            angleDistance(targetYaw, serverYaw) <= tolerance &&
            abs(targetPitch - serverPitch) <= tolerance
    }

    private fun angleDistance(first: Float, second: Float): Float {
        val difference = (first - second) % 360f
        return abs(if (difference > 180f) difference - 360f else if (difference < -180f) difference + 360f else difference)
    }
}
