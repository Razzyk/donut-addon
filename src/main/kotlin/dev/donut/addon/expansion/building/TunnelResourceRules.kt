package dev.donut.addon.expansion.building

internal object TunnelResourceRules {
    fun hungry(foodLevel: Int, health: Float, maxHealth: Float): Boolean =
        foodLevel <= 14 || health < maxHealth && foodLevel < 18

    fun shouldStopPickaxe(remainingDurability: Int, mendingEnabled: Boolean, hasMending: Boolean): Boolean =
        remainingDurability < 10 && !(mendingEnabled && hasMending)

    fun calm(physicalMovement: Boolean, grounded: Boolean, horizontalDistanceSquared: Double): Boolean =
        !physicalMovement && grounded && horizontalDistanceSquared < 0.0025
}
