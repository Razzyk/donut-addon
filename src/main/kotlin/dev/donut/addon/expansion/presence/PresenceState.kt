package dev.donut.addon.expansion.presence

import java.util.UUID

/** Tick-driven confirmation logic, separated from Minecraft packet classes for deterministic review. */
class DonutVanishState(
    private val trustDelayMs: Long = 1_500,
    private val removalDelayTicks: Int = 20,
) {
    data class Candidate(val id: UUID, val name: String, val spectator: Boolean = false)
    data class Alert(val id: UUID, val name: String, val detail: String, val weight: Int)
    private data class Seen(val candidate: Candidate, val firstListedAt: Long)
    private data class Pending(val candidate: Candidate, val expiresAtTick: Int)

    private val seen = linkedMapOf<UUID, Seen>()
    private val pending = linkedMapOf<UUID, Pending>()
    private val recentLeaves = linkedMapOf<UUID, Long>()
    private val spells = linkedMapOf<UUID, Candidate>()

    fun observeListed(candidates: Collection<Candidate>, nowMs: Long) {
        candidates.forEach { candidate ->
            val previous = seen[candidate.id]
            seen[candidate.id] = Seen(candidate, previous?.firstListedAt ?: nowMs)
        }
        if (seen.size > 1_024) {
            val active = candidates.mapTo(hashSetOf()) { it.id }
            seen.entries.removeIf { (id, record) -> id !in active && nowMs - record.firstListedAt > 600_000 && id !in pending && id !in spells }
        }
    }

    fun observeHidden(candidateId: UUID, nowMs: Long): Alert? {
        val record = seen[candidateId] ?: return null
        if (!trusted(record, nowMs) || !PresenceText.plausiblePlayerName(record.candidate.name)) return null
        pending.remove(candidateId)
        if (candidateId in spells) return null
        spells[candidateId] = record.candidate
        return Alert(candidateId, record.candidate.name, "A listed player stopped appearing in the tab list", 100)
    }

    fun observeRemoval(candidateId: UUID, tick: Int, nowMs: Long, bulkCount: Int = 1) {
        val record = seen[candidateId] ?: return
        if (bulkCount >= 4 || !trusted(record, nowMs) || !PresenceText.plausiblePlayerName(record.candidate.name)) return
        pending[candidateId] = Pending(record.candidate, tick + removalDelayTicks)
    }

    fun observeLeaveMessage(text: String?, nowMs: Long): Set<UUID> {
        val matched = seen.values.filter { PresenceText.looksLikeDeparture(text, it.candidate.name) }.map { it.candidate.id }
        matched.forEach { id ->
            recentLeaves[id] = nowMs + 5_000
            pending.remove(id)
            spells.remove(id)
        }
        recentLeaves.entries.removeIf { it.value <= nowMs }
        return matched.toSet()
    }

    fun observeGamemode(candidateId: UUID, isSpectator: Boolean, tick: Int, nowMs: Long): Alert? {
        val record = seen[candidateId] ?: return null
        if (!trusted(record, nowMs)) return null
        pending.remove(candidateId)
        if (isSpectator) {
            spells[candidateId] = record.candidate.copy(spectator = true)
            return Alert(candidateId, record.candidate.name, "A listed player switched to spectator mode", 100)
        }
        spells.remove(candidateId)
        return null
    }

    fun advance(tick: Int, nowMs: Long, stillInPlayerList: (UUID) -> Boolean,
                stillInWorld: (UUID) -> Boolean): List<Alert> {
        recentLeaves.entries.removeIf { it.value <= nowMs }
        val alerts = ArrayList<Alert>()
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val row = iterator.next()
            val id = row.key
            val request = row.value
            if (request.expiresAtTick > tick) continue
            iterator.remove()
            if (stillInPlayerList(id) || recentLeaves[id]?.let { it > nowMs } == true) continue
            if (stillInWorld(id)) {
                spells[id] = request.candidate
                alerts += Alert(id, request.candidate.name, "A player remained in the world after leaving tab", 100)
            } else {
                alerts += Alert(id, request.candidate.name, "A player disappeared from tab without a leave message", 30)
            }
        }
        return alerts
    }

    fun visibleAgain(candidateId: UUID): Candidate? = spells.remove(candidateId)

    fun hasTentativeRemoval(candidateId: UUID): Boolean = candidateId in pending

    fun clear() {
        seen.clear(); pending.clear(); recentLeaves.clear(); spells.clear()
    }

    fun isTrusted(candidateId: UUID, nowMs: Long): Boolean = seen[candidateId]?.let { trusted(it, nowMs) } == true

    private fun trusted(seen: Seen, nowMs: Long) = nowMs - seen.firstListedAt >= trustDelayMs
}

/** Detects every world player-set transition, including a departure that changes the remaining set. */
class NearbyPlayerEdges {
    private var lastWorld: Any? = null
    private val previousNames = linkedSetOf<String>()

    fun update(worldIdentity: Any?, currentNames: Collection<String>): List<String> {
        if (worldIdentity !== lastWorld) {
            lastWorld = worldIdentity
            previousNames.clear()
        }
        val current = currentNames.distinctBy { it.lowercase() }.toCollection(linkedSetOf())
        val normalizedPrevious = previousNames.mapTo(hashSetOf()) { it.lowercase() }
        val normalizedCurrent = current.mapTo(hashSetOf()) { it.lowercase() }
        val changed = normalizedPrevious != normalizedCurrent
        previousNames.clear()
        previousNames.addAll(current)
        return if (changed) current.toList() else emptyList()
    }

    fun clear() { lastWorld = null; previousNames.clear() }
}

/** Scores dissimilar evidence and enforces per-sensor quiet periods. */
class PresenceEvidenceLedger {
    enum class Kind { HIDDEN_PLAYER, INVISIBLE_BODY, SOUND, PARTICLE }
    data class Evidence(val kind: Kind, val subject: String, val reason: String, val weight: Int,
                        val atMs: Long, val nearbyId: UUID? = null)
    data class Alert(val evidence: Evidence, val criticalSubject: String? = null)

    private val cooldowns = hashMapOf<String, Long>()
    private val evidence = ArrayDeque<Evidence>()
    private var lastCriticalAt = 0L

    fun add(item: Evidence, nowMs: Long, cooldownMs: Long): Alert? {
        val key = item.kind.name + "|" + item.subject.lowercase()
        if (nowMs - (cooldowns[key] ?: 0L) < cooldownMs) return null
        cooldowns[key] = nowMs
        evidence.addLast(item)
        prune(nowMs)
        val strongestByKind = evidence.groupBy(Evidence::kind).mapValues { (_, rows) -> rows.maxOf(Evidence::weight) }
        val proof = evidence.lastOrNull { it.nearbyId != null }
        val total = strongestByKind.values.sum()
        val critical = if (proof != null && strongestByKind.size >= 2 && total >= 35 && nowMs - lastCriticalAt >= 12_000) {
            lastCriticalAt = nowMs
            proof.subject
        } else null
        return Alert(item, critical)
    }

    fun prune(nowMs: Long) {
        while (evidence.isNotEmpty() && nowMs - evidence.first().atMs > 15_000) evidence.removeFirst()
        cooldowns.entries.removeIf { nowMs - it.value > 60_000 }
    }

    fun clear() { cooldowns.clear(); evidence.clear(); lastCriticalAt = 0L }
}
