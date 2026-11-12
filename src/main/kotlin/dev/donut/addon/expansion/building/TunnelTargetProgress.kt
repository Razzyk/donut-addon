package dev.donut.addon.expansion.building

/** Tracks the source walker's three-plan no-progress cutoff for a flagged destination. */
internal class TunnelTargetProgress(private val maxStalls: Int = 3) {
    private var bestRemaining = Int.MAX_VALUE
    private var bestCurrent = Int.MAX_VALUE
    private var stalls = 0

    init {
        require(maxStalls > 0)
    }

    fun reset() {
        bestRemaining = Int.MAX_VALUE
        bestCurrent = Int.MAX_VALUE
        stalls = 0
    }

    /** Returns true once repeated plans have failed to get closer to the target. */
    fun recordPlan(currentDistance: Int, bestRouteDistance: Int): Boolean {
        require(currentDistance >= 0 && bestRouteDistance >= 0)
        if (bestRouteDistance == 0) {
            reset()
            return false
        }
        if (bestRouteDistance >= bestRemaining && currentDistance >= bestCurrent) {
            stalls++
        } else {
            bestRemaining = minOf(bestRemaining, bestRouteDistance)
            bestCurrent = minOf(bestCurrent, currentDistance)
            stalls = 0
        }
        return stalls >= maxStalls
    }
}
