package dev.donut.addon.expansion.movement

/** Stateful hop acceleration. Resetting after a server correction prevents reusing stale distance. */
class StrideState {
    private var phase = 0
    private var velocity = .2873
    data class Step(val horizontal: Double, val vertical: Double)

    fun reset() { phase = 0; velocity = .2873 }

    fun advance(moving: Boolean, grounded: Boolean, verticalCollision: Boolean, obstructed: Boolean,
                measuredDistance: Double, base: Double, boost: Double, hop: Double,
                currentY: Double, limit: Double): Step {
        var y = currentY
        if (phase == 0 && moving) {
            phase = 1
            velocity = 1.18f.toDouble() * base - .01
        }
        when (phase) {
            1 -> if (moving && grounded) { y = hop; velocity *= boost; phase = 2 }
            2 -> { velocity = measuredDistance - .76 * (measuredDistance - base); phase = 3 }
            3 -> {
                if (obstructed || verticalCollision) phase = 0
                velocity = measuredDistance * (158.0 / 159.0)
            }
        }
        velocity = maxOf(base, velocity).coerceAtMost(limit)
        return Step(if (moving) velocity else 0.0, y)
    }
}

class FlightCadence {
    private var elapsed = 0
    var dipping: Boolean = false
        private set
    var boundary: Boolean = false
        private set
    fun reset() { elapsed = 0; dipping = false; boundary = false }
    fun tick(delay: Int, offTicks: Int) {
        val was = dipping
        val total = delay.coerceAtLeast(1) + offTicks.coerceAtLeast(1)
        elapsed = (elapsed + 1) % total
        dipping = elapsed >= delay.coerceAtLeast(1)
        boundary = was != dipping
    }
}
