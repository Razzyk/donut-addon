package dev.donut.addon.expansion.automation.core

import kotlin.math.abs

/** Pure safety gates and cooldown accounting used by the staged combat macro rewrite. */
object CombatDamageRules {
    fun crystalAllowed(damagePoints: Double, healthPoints: Double, maxDamagePoints: Double, invulnerable: Boolean = false): Boolean =
        damagePoints.isFinite() && healthPoints.isFinite() && maxDamagePoints.isFinite() &&
            damagePoints >= 0.0 && healthPoints > 0.0 && damagePoints <= maxDamagePoints &&
            (invulnerable || damagePoints * 1.15 + 2.0 < healthPoints)

    fun anchorAllowed(damagePoints: Double, healthPoints: Double, hasTotem: Boolean, invulnerable: Boolean = false): Boolean =
        damagePoints.isFinite() && healthPoints.isFinite() && damagePoints >= 0.0 && healthPoints > 0.0 &&
            (invulnerable || hasTotem || damagePoints * 1.15 + 2.0 < healthPoints)
}

class CrystalAttemptLedger(private val attemptsBeforeRest: Int = 3, private val restTicks: Long = 100) {
    private val attempts = mutableMapOf<Int, Int>()
    private val blockedUntil = mutableMapOf<Int, Long>()

    fun canAttempt(entityId: Int, tick: Long): Boolean = tick >= (blockedUntil[entityId] ?: Long.MIN_VALUE)

    fun recordAttempt(entityId: Int, tick: Long) {
        val count = (attempts[entityId] ?: 0) + 1
        if (count >= attemptsBeforeRest) {
            attempts.remove(entityId)
            blockedUntil[entityId] = tick + restTicks
        } else attempts[entityId] = count
    }

    fun forgetMissing(activeIds: Set<Int>) {
        attempts.keys.removeIf { it !in activeIds }
        blockedUntil.keys.removeIf { it !in activeIds }
    }

    fun reset() { attempts.clear(); blockedUntil.clear() }
}

object AutomationCombatLock {
    private var owner: Any? = null

    @Synchronized fun tryAcquire(candidate: Any): Boolean {
        val current = owner
        if (current != null && current !== candidate) return false
        owner = candidate
        return true
    }

    @Synchronized fun release(candidate: Any) {
        if (owner === candidate) owner = null
    }

    @Synchronized fun isHeldBy(candidate: Any): Boolean = owner === candidate
}

data class WorldPoint(val x: Double, val y: Double, val z: Double) {
    fun distanceSquared(other: WorldPoint): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return dx * dx + dy * dy + dz * dz
    }
}

data class ObservedPlayerDeath(val entityId: Int, val position: WorldPoint, val dead: Boolean, val local: Boolean = false)

data class LootProtectionSpot(
    val dimension: String,
    val center: WorldPoint,
    val createdAtMs: Long,
    val expiresAtMs: Long,
)

/** Tracks nearby death sites while dropped items remain, with short grace and bounded expiry. */
class LootProtectionLedger {
    private val spots = mutableListOf<LootProtectionSpot>()
    private val seenDeadIds = mutableSetOf<Int>()
    private var dimension: String? = null

    fun update(
        nowMs: Long,
        currentDimension: String,
        localPlayerId: Int,
        playerPosition: WorldPoint,
        players: List<ObservedPlayerDeath>,
        droppedItems: List<WorldPoint>,
    ) {
        if (dimension != currentDimension) {
            spots.clear()
            seenDeadIds.clear()
            dimension = currentDimension
        }

        val presentIds = players.mapTo(mutableSetOf()) { it.entityId }
        seenDeadIds.removeIf { it !in presentIds }
        for (player in players) {
            if (!player.dead || !seenDeadIds.add(player.entityId)) continue
            val isLocal = player.local || player.entityId == localPlayerId
            val radius = if (isLocal) 0.0 else 16.0
            if (player.position.distanceSquared(playerPosition) > radius * radius) continue
            spots += LootProtectionSpot(
                currentDimension,
                player.position,
                nowMs,
                nowMs + if (isLocal) 300_000 else 60_000,
            )
        }

        spots.removeIf { spot ->
            if (nowMs > spot.expiresAtMs) return@removeIf true
            val closeEnoughToObserve = spot.center.distanceSquared(playerPosition) <= 32.0 * 32.0
            val gracePassed = nowMs - spot.createdAtMs > 3_000
            val lootRemains = droppedItems.any { isWithinSpot(spot.center, it) }
            closeEnoughToObserve && gracePassed && !lootRemains
        }
    }

    fun protects(currentDimension: String, blastCenter: WorldPoint): Boolean = spots.any { spot ->
        spot.dimension == currentDimension && isWithinSpot(spot.center, blastCenter)
    }

    fun clear() { spots.clear(); seenDeadIds.clear(); dimension = null }

    fun snapshot(): List<LootProtectionSpot> = spots.toList()

    private fun isWithinSpot(center: WorldPoint, point: WorldPoint): Boolean =
        abs(point.x - center.x) <= 8.0 && abs(point.y - center.y) <= 4.0 && abs(point.z - center.z) <= 8.0
}

data class ShieldPlacementCandidate(
    val position: Long,
    val supportPosition: Long,
    val supportFace: String,
    val unguardedDamage: Double,
    val guardedDamage: Double,
    val open: Boolean,
    val reachable: Boolean,
    val intersectsPlayer: Boolean,
)

/** Chooses the reachable placement with the largest measured blast reduction. */
object BlastShieldPlanner {
    fun choose(candidates: List<ShieldPlacementCandidate>, minimumReductionFraction: Double = 0.15): ShieldPlacementCandidate? {
        if (!minimumReductionFraction.isFinite() || minimumReductionFraction !in 0.0..1.0) return null
        return candidates.asSequence()
            .filter { it.open && it.reachable && !it.intersectsPlayer }
            .filter { it.unguardedDamage.isFinite() && it.guardedDamage.isFinite() && it.unguardedDamage > 0.0 }
            .filter { (it.unguardedDamage - it.guardedDamage) / it.unguardedDamage >= minimumReductionFraction }
            .maxWithOrNull(compareBy<ShieldPlacementCandidate> { it.unguardedDamage - it.guardedDamage }
                .thenBy { -it.guardedDamage })
    }
}
