package dev.donut.addon.expansion.render

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp

enum class HealthAlertPattern { HEARTBEAT, BREATHING, STEADY }

data class HealthAlertFrame(val visibility: Float, val severity: Float, val beat: Float)

/** Frame-clock animation state, isolated from Minecraft and cleared when its owner changes. */
class HealthAlertEnvelope {
    private var visibility = 0f
    private var severity = 0f
    private var cycle = 0f
    private var previousNanos: Long? = null

    fun clear() {
        visibility = 0f
        severity = 0f
        cycle = 0f
        previousNanos = null
    }

    fun sample(nowNanos: Long, health: Float, threshold: Float, eligible: Boolean,
               dying: Boolean, pattern: HealthAlertPattern): HealthAlertFrame {
        val previous = previousNanos
        val gap = if (previous == null || nowNanos < previous) Long.MAX_VALUE else nowNanos - previous
        if (gap > 250_000_000L) {
            visibility = 0f
            cycle = 0f
        }
        previousNanos = nowNanos
        val seconds = if (gap == Long.MAX_VALUE) 0f else (gap / 1_000_000_000.0).coerceIn(0.0, 0.1).toFloat()
        val warning = eligible && !dying && health.isFinite() && threshold.isFinite() && threshold > 0f && health < threshold
        val destination = if (warning) 1f else 0f
        if (warning) severity = ((threshold - health) / threshold).coerceIn(0f, 1f)
        val response = if (dying) 12f else if (warning) 5f else 2.6f
        visibility += (destination - visibility) * (1f - exp(-response * seconds))
        if (!warning && visibility < 0.003f) {
            visibility = 0f
            cycle = 0f
            return HealthAlertFrame(0f, severity, 0f)
        }
        val frequency = (64f + severity * 86f) / 60f * if (pattern == HealthAlertPattern.BREATHING) 0.5f else 1f
        cycle = (cycle + seconds * frequency) % 1f
        val oscillation = when (pattern) {
            HealthAlertPattern.STEADY -> 0.35f
            HealthAlertPattern.BREATHING -> (0.5 - cos(Math.PI * 2 * cycle) * 0.5).toFloat()
            HealthAlertPattern.HEARTBEAT -> (pulseAt(0f) + pulseAt(0.2f) * 0.62f).coerceAtMost(1f)
        }
        return HealthAlertFrame(visibility * visibility * (3f - 2f * visibility), severity, oscillation)
    }

    private fun pulseAt(center: Float): Float {
        val offset = abs(cycle - center)
        val distance = minOf(offset, 1f - offset) / 0.065f
        return exp(-distance * distance)
    }
}
