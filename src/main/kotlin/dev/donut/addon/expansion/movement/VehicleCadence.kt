package dev.donut.addon.expansion.movement

/** Network-only descent pulse. The hold lasts one real tick even when the client timer is faster. */
class VehicleCadence {
    enum class Action { PASS, HOLD, DIP }
    private var remaining = 0
    private var normalY: Double? = null
    private var restoreAt: Long? = null
    fun reset(delay: Int) { remaining = delay; normalY = null; restoreAt = null }
    fun tick() { if (remaining > 0) remaining-- }
    fun isHolding(now: Long): Boolean = restoreAt?.let { now < it } ?: false
    fun recovery(now: Long): Double? {
        val due = restoreAt ?: return null
        if (now < due) return null
        restoreAt = null
        return normalY
    }
    fun observe(y: Double) { normalY = y }
    fun accept(y: Double, airborne: Boolean, localServer: Boolean, delay: Int, now: Long): Action {
        if (localServer) return Action.PASS
        if (isHolding(now)) { normalY = y; return Action.HOLD }
        val before = normalY
        normalY = y
        if (before != null && remaining == 0 && restoreAt == null && airborne && y > before - .0313) {
            remaining = delay
            restoreAt = now + 50_000_000L
            return Action.DIP
        }
        return Action.PASS
    }
}
