package dev.donut.addon.expansion.building

import net.minecraft.core.Direction

import kotlin.math.hypot

/** Bounded movement progress and source-shaped stand offsets for the tunnel runner. */
internal class TunnelWalkProgress(private val maxUnchangedTicks: Int = 60) {
    private var targetX = Double.NaN
    private var targetZ = Double.NaN
    private var bestDistance = Double.POSITIVE_INFINITY
    private var unchangedTicks = 0

    enum class State { MOVING, ARRIVED, STALLED }

    fun observe(
        destinationX: Double,
        destinationZ: Double,
        playerX: Double,
        playerZ: Double,
        arrivalRadius: Double = 0.3,
    ): State {
        require(maxUnchangedTicks > 0 && arrivalRadius >= 0.0)
        if (destinationX != targetX || destinationZ != targetZ) {
            targetX = destinationX
            targetZ = destinationZ
            bestDistance = Double.POSITIVE_INFINITY
            unchangedTicks = 0
        }

        val distance = hypot(destinationX - playerX, destinationZ - playerZ)
        if (distance <= arrivalRadius) {
            reset()
            return State.ARRIVED
        }
        if (bestDistance.isInfinite()) {
            bestDistance = distance
            unchangedTicks = 1
        } else if (distance < bestDistance - 0.05) {
            bestDistance = distance
            unchangedTicks = 0
        } else if (++unchangedTicks >= maxUnchangedTicks) {
            reset()
            return State.STALLED
        }
        return State.MOVING
    }

    fun reset() {
        targetX = Double.NaN
        targetZ = Double.NaN
        bestDistance = Double.POSITIVE_INFINITY
        unchangedTicks = 0
    }
}

internal object TunnelStandRules {
    data class Offset(val back: Double, val lateral: Double)

    /** Mirrors the reference's stand distance and side tolerance around the block being mined. */
    fun isPositioned(
        playerX: Double,
        playerZ: Double,
        targetX: Double,
        targetZ: Double,
        forwardX: Int,
        forwardZ: Int,
        sideX: Int,
        sideZ: Int,
        sideTolerance: Double,
    ): Boolean {
        val back = (targetX - playerX) * forwardX + (targetZ - playerZ) * forwardZ
        val lateral = (playerX - targetX) * sideX + (playerZ - targetZ) * sideZ
        return back in 1.0..2.6 && kotlin.math.abs(lateral) <= sideTolerance
    }

    /** Converts two normalized random samples into the reference's narrow or varied stand offset. */
    fun sampleOffset(room: Double, varied: Boolean, backSample: Double, sideSample: Double): Offset {
        require(room >= 0.0 && backSample in 0.0..<1.0 && sideSample in 0.0..<1.0)
        val back = 1.0 + backSample * if (varied) 0.6 else 0.3
        val lateral = if (varied) (sideSample * 2.0 - 1.0) * room else 0.0
        return Offset(back, lateral)
    }
}

internal object TunnelStreamRules {
    /** A straight 3x3 segment streams unless its next stop is a non-head block. */
    fun shouldStream(
        cellSize: Int,
        straight: Boolean,
        aligned: Boolean,
        targetIsNull: Boolean,
        targetIsHead: Boolean,
        feetPending: Boolean,
    ): Boolean = cellSize == 3 && straight && aligned && (targetIsNull || targetIsHead || feetPending)
}

internal object TunnelDropRules {
    fun shouldPause(horizontalSpeed: Double, dropDepth: Int, sampleMatchesGoal: Boolean, sampleMatchesPlayer: Boolean): Boolean =
        horizontalSpeed >= 0.02 && dropDepth > 3 && !sampleMatchesGoal && !sampleMatchesPlayer
}

internal object TunnelHopRules {
    enum class EmptyCellAction { ADVANCE, RETURN_TO_PREVIOUS, HOP_TO_NEXT }

    fun emptyCellAction(distanceToNext: Double, distanceToPrevious: Double): EmptyCellAction {
        require(distanceToNext >= 0.0 && distanceToPrevious >= 0.0)
        return when {
            distanceToNext <= 3.0 -> EmptyCellAction.ADVANCE
            distanceToPrevious > 1.2 -> EmptyCellAction.RETURN_TO_PREVIOUS
            else -> EmptyCellAction.HOP_TO_NEXT
        }
    }

    fun shouldJump(grounded: Boolean, floorRise: Double, horizontalCollision: Boolean, ahead: Double, sameCell: Boolean): Boolean =
        grounded && floorRise > 0.55 && (horizontalCollision || ahead > 0.15 || sameCell)
}

internal object TunnelMiningAimRules {
    data class FacePoint(val x: Double, val y: Double, val z: Double)

    fun variedAim(percentRoll: Int): Boolean {
        require(percentRoll in 0..99)
        return percentRoll < 20
    }

    fun aimCoordinate(sample: Double, varied: Boolean): Double {
        require(sample.isFinite())
        return if (varied) (0.5 + sample * 0.12).coerceIn(0.22, 0.78) else 0.5
    }

    fun facePoint(
        originX: Int,
        originY: Int,
        originZ: Int,
        face: Direction,
        minX: Double,
        minY: Double,
        minZ: Double,
        maxX: Double,
        maxY: Double,
        maxZ: Double,
        u: Double,
        v: Double,
    ): FacePoint {
        val alongU = u.coerceIn(0.0, 1.0)
        val alongV = v.coerceIn(0.0, 1.0)
        val x = when (face) {
            Direction.WEST -> minX
            Direction.EAST -> maxX
            else -> minX + (maxX - minX) * alongU
        }
        val y = when (face) {
            Direction.DOWN -> minY
            Direction.UP -> maxY
            else -> minY + (maxY - minY) * alongV
        }
        val z = when (face) {
            Direction.NORTH -> minZ
            Direction.SOUTH -> maxZ
            Direction.WEST, Direction.EAST -> minZ + (maxZ - minZ) * alongU
            else -> minZ + (maxZ - minZ) * alongV
        }
        return FacePoint(originX + x, originY + y, originZ + z)
    }

    fun reactionTicks(varied: Boolean, gaussianSample: Double): Int {
        require(gaussianSample.isFinite())
        if (!varied) return 2
        return kotlin.math.round(kotlin.math.exp(kotlin.math.ln(2.6) + gaussianSample * 0.35))
            .toInt().coerceIn(2, 6)
    }

    fun wobbleRotation(
        varied: Boolean,
        percentRoll: Int,
        yawGaussian: Double,
        pitchGaussian: Double,
    ): Pair<Float, Float>? {
        require(percentRoll in 0..99)
        require(yawGaussian.isFinite() && pitchGaussian.isFinite())
        if (!varied || percentRoll >= 30) return null
        return (yawGaussian * 0.35).toFloat() to (pitchGaussian * 0.35 * 0.7).toFloat()
    }
}

internal object TunnelDriftRules {
    fun shouldShift(wander: Boolean, hasFlaggedTarget: Boolean, tick: Long, nextShiftTick: Long): Boolean =
        wander && !hasFlaggedTarget && tick >= nextShiftTick

    fun shift(current: Int, threeWayRoll: Int): Int {
        require(current in -2..2 && threeWayRoll in 0..2)
        return (current + threeWayRoll - 1).coerceIn(-2, 2)
    }

    fun nextTick(currentTick: Long, sixtySecondRoll: Int): Long {
        require(currentTick >= 0L && sixtySecondRoll in 0..59)
        return currentTick + 20L * (30 + sixtySecondRoll)
    }
}

internal object TunnelRouteNoise {
    /** Stable low-amplitude route variation, disabled when Wander is off. */
    fun weight(x: Int, z: Int, seed: Int, wander: Boolean): Double {
        if (!wander) return 0.0
        var hash = x * 73_856_093 xor z * 19_349_663 xor seed
        hash = hash xor (hash ushr 13)
        hash *= 1_540_483_477
        hash = hash xor (hash ushr 15)
        return (hash and 1023) / 1023.0 * 0.7
    }
}
