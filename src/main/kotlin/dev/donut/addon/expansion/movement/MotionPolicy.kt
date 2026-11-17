package dev.donut.addon.expansion.movement

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Pure geometry shared by player and vehicle motion; speeds are blocks per second. */
object MotionPolicy {
    data class Horizontal(val x: Double, val z: Double)

    @JvmStatic
    fun horizontal(yaw: Float, forward: Int, sideways: Int, blocksPerSecond: Double): Horizontal {
        val length = hypot(forward.toDouble(), sideways.toDouble())
        if (length == 0.0 || !blocksPerSecond.isFinite()) return Horizontal(0.0, 0.0)
        val radians = Math.toRadians(yaw.toDouble())
        val scale = blocksPerSecond.coerceAtLeast(0.0) / (20.0 * length)
        return Horizontal(
            (-sin(radians) * forward + cos(radians) * sideways) * scale,
            (cos(radians) * forward + sin(radians) * sideways) * scale
        )
    }

    @JvmStatic
    fun jumpCooldown(vanilla: Int, chosen: Int, variance: Int, sample: Int): Int =
        minOf(vanilla, chosen.coerceIn(0, 9) + sample.coerceIn(0, variance.coerceIn(0, 4)))

}
