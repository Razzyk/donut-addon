package dev.donut.addon.hud.speedometer

import java.util.Locale
import kotlin.math.sqrt

/** Pure speed sampling and display helpers used by the native HUD element. */
class SpeedSampleTracker(
    private val maxTickDistance: Double = 64.0,
) {
    private var previousWorld: Any? = null
    private var previousPlayer: Any? = null
    private var previousX = 0.0
    private var previousY = 0.0
    private var previousZ = 0.0
    private var previousTickCount: Int? = null
    private var hasPreviousPosition = false

    /** Returns blocks per second from one player-tick sample, or zero after a reset/spike. */
    @JvmOverloads
    fun update(
        world: Any?,
        player: Any?,
        x: Double,
        y: Double,
        z: Double,
        threeDimensional: Boolean,
        tickCount: Int? = null,
    ): Double {
        if (world == null || player == null || !x.isFinite() || !y.isFinite() || !z.isFinite()) {
            reset()
            return 0.0
        }

        val tickGap = tickCount != null && previousTickCount?.let { tickCount != it + 1 } == true
        if (!hasPreviousPosition || world !== previousWorld || player !== previousPlayer || tickGap) {
            setPrevious(world, player, x, y, z, tickCount)
            return 0.0
        }

        val dx = x - previousX
        val dy = y - previousY
        val dz = z - previousZ
        val threeDimensionalDistance = sqrt(dx * dx + dy * dy + dz * dz)
        val tickDistance = if (threeDimensional) threeDimensionalDistance else sqrt(dx * dx + dz * dz)
        setPrevious(world, player, x, y, z, tickCount)

        if (!threeDimensionalDistance.isFinite() || threeDimensionalDistance > maxTickDistance) {
            return 0.0
        }

        return tickDistance * TICKS_PER_SECOND
    }

    fun reset() {
        previousWorld = null
        previousPlayer = null
        previousTickCount = null
        hasPreviousPosition = false
    }

    private fun setPrevious(world: Any, player: Any, x: Double, y: Double, z: Double, tickCount: Int?) {
        previousWorld = world
        previousPlayer = player
        previousX = x
        previousY = y
        previousZ = z
        previousTickCount = tickCount
        hasPreviousPosition = true
    }

    companion object {
        const val TICKS_PER_SECOND = 20.0
    }
}

object SpeedometerMath {
    fun convert(blocksPerSecond: Double, unit: SpeedometerHudComponent.Unit): Double = when (unit) {
        SpeedometerHudComponent.Unit.BLOCKS_PER_SECOND -> blocksPerSecond
        SpeedometerHudComponent.Unit.KILOMETERS_PER_HOUR -> blocksPerSecond * 3.6
        SpeedometerHudComponent.Unit.MILES_PER_HOUR -> blocksPerSecond * 2.2369362920544
    }

    fun unitLabel(unit: SpeedometerHudComponent.Unit): String = when (unit) {
        SpeedometerHudComponent.Unit.BLOCKS_PER_SECOND -> "blocks/s"
        SpeedometerHudComponent.Unit.KILOMETERS_PER_HOUR -> "km/h"
        SpeedometerHudComponent.Unit.MILES_PER_HOUR -> "mph"
    }

    fun format(value: Double, decimals: Int): String =
        String.format(Locale.ROOT, "%.${decimals.coerceIn(0, 3)}f", value)

    fun applyTemplate(template: String, speed: String, unit: String): String =
        template.replace("{speed}", speed).replace("{unit}", unit)
}
