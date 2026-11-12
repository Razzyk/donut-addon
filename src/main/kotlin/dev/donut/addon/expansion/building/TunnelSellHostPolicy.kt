package dev.donut.addon.expansion.building

import dev.donut.addon.expansion.automation.core.DonutSellCandidate
import dev.donut.addon.expansion.automation.core.DonutSellIdentity
import dev.donut.addon.expansion.automation.core.TextNormalization
import java.util.Locale

/** Host-side pacing, server rate-limit handling, and per-item failure memory for a sell workflow. */
internal class TunnelSellHostPolicy {
    enum class Phase { IDLE, OPENING, ACTIVE }

    data class Settlement(val newlySkippedItemIds: Set<String>, val successfulItemIds: Set<String>)

    private val strikes = HashMap<String, Int>()
    private val skipUntilNanos = HashMap<String, Long>()
    private var nextPollAtNanos = Long.MIN_VALUE
    private var lastCommandAtNanos = Long.MIN_VALUE
    private var startedAtNanos = Long.MIN_VALUE
    private var retryAtNanos = Long.MIN_VALUE
    private var phase = Phase.IDLE
    private var commandCount = 0
    private var candidateIdentities = emptyMap<String, Set<String>>()

    fun resetRun() {
        strikes.clear()
        skipUntilNanos.clear()
        nextPollAtNanos = Long.MIN_VALUE
        lastCommandAtNanos = Long.MIN_VALUE
        clearAttempt()
    }

    /** Rate checks and candidate scans are intentionally bounded to one check per 500 ms. */
    fun shouldCheck(nowNanos: Long): Boolean {
        if (nowNanos < nextPollAtNanos) return false
        nextPollAtNanos = nowNanos + CHECK_INTERVAL_NANOS
        expireSkips(nowNanos)
        return true
    }

    fun eligible(candidates: List<DonutSellCandidate>, nowNanos: Long): List<DonutSellCandidate> {
        expireSkips(nowNanos)
        return candidates.filter { it.stack.itemId !in skipUntilNanos }
    }

    fun canStart(nowNanos: Long): Boolean = phase == Phase.IDLE && commandCooldownPassed(nowNanos)

    fun begin(nowNanos: Long, candidates: List<DonutSellCandidate>): Boolean {
        if (candidates.isEmpty() || !canStart(nowNanos)) return false
        startedAtNanos = nowNanos
        phase = Phase.OPENING
        commandCount = 0
        retryAtNanos = Long.MIN_VALUE
        candidateIdentities = candidates.groupBy { it.stack.itemId }
            .mapValues { (_, stacks) -> stacks.mapTo(linkedSetOf()) { DonutSellIdentity.of(it.stack) } }
        return true
    }

    /** True for the first command after cadence, or a server-confirmed rate-limit retry. */
    fun maySendCommand(nowNanos: Long): Boolean {
        if (phase != Phase.OPENING || expired(nowNanos) || commandCount >= MAX_COMMANDS) return false
        if (commandCount == 0) {
            return shouldCheck(nowNanos) && commandCooldownPassed(nowNanos)
        }
        return retryAtNanos != Long.MIN_VALUE && nowNanos >= retryAtNanos && shouldCheck(nowNanos)
    }

    fun commandSent(nowNanos: Long): Boolean {
        if (phase != Phase.OPENING || commandCount >= MAX_COMMANDS || expired(nowNanos)) return false
        if (commandCount == 0 && !commandCooldownPassed(nowNanos)) return false
        if (commandCount > 0 && (retryAtNanos == Long.MIN_VALUE || nowNanos < retryAtNanos)) return false
        commandCount++
        lastCommandAtNanos = nowNanos
        retryAtNanos = Long.MIN_VALUE
        return true
    }

    /** Accept only the server's explicit command-rate-limit sentence while the menu is opening. */
    fun observeRateLimit(rawText: String, nowNanos: Long): Long? {
        if (phase != Phase.OPENING || commandCount <= 0 || commandCount >= MAX_COMMANDS) return null
        val normalized = normalizeServerText(rawText)
        val match = RATE_LIMIT.find(normalized) ?: return null
        val seconds = match.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        val retry = nowNanos + (seconds * NANOS_PER_SECOND).toLong() + RATE_LIMIT_PADDING_NANOS
        retryAtNanos = maxOf(retryAtNanos, retry)
        return retryAtNanos
    }

    fun menuOpened() {
        if (phase == Phase.OPENING) phase = Phase.ACTIVE
    }

    fun phase(): Phase = phase

    fun expired(nowNanos: Long): Boolean = startedAtNanos != Long.MIN_VALUE &&
        nowNanos - startedAtNanos > MAX_LIFETIME_NANOS

    fun openingTimedOut(nowNanos: Long): Boolean = phase == Phase.OPENING && commandCount > 0 &&
        nowNanos - lastCommandAtNanos > OPEN_TIMEOUT_NANOS

    fun settlement(
        soldByIdentity: Map<String, Int>,
        remainingByIdentity: Map<String, Int>,
        nowNanos: Long,
    ): Settlement {
        val skipped = linkedSetOf<String>()
        val successful = linkedSetOf<String>()
        for ((itemId, identities) in candidateIdentities) {
            val sold = identities.sumOf { soldByIdentity[it] ?: 0 }
            val remaining = identities.sumOf { remainingByIdentity[it] ?: 0 }
            if (sold > 0) successful += itemId
            if (remaining > 0) {
                val count = (strikes[itemId] ?: 0) + 1
                if (count >= STRIKES_TO_SKIP) {
                    strikes.remove(itemId)
                    skipUntilNanos[itemId] = nowNanos + ITEM_SKIP_NANOS
                    skipped += itemId
                } else {
                    strikes[itemId] = count
                }
            } else {
                strikes.remove(itemId)
            }
        }
        clearAttempt()
        return Settlement(skipped, successful)
    }

    /** Opening failures do not penalize an item because the server did not inspect an offer. */
    fun finishWithoutOffer() {
        clearAttempt()
    }

    fun failCooldownUntilNanos(nowNanos: Long): Long = nowNanos + OPEN_FAILURE_COOLDOWN_NANOS

    fun strikeCount(itemId: String): Int = strikes[itemId] ?: 0
    fun skippedUntilNanos(itemId: String): Long? = skipUntilNanos[itemId]
    fun commandCount(): Int = commandCount

    private fun expireSkips(nowNanos: Long) {
        skipUntilNanos.entries.removeIf { nowNanos >= it.value }
    }

    private fun commandCooldownPassed(nowNanos: Long): Boolean =
        lastCommandAtNanos == Long.MIN_VALUE || nowNanos - lastCommandAtNanos >= COMMAND_COOLDOWN_NANOS

    private fun normalizeServerText(rawText: String): String {
        val formatted = TextNormalization.stripFormatting(rawText)
        val smallCaps = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘʀѕꜱᴛᴜᴠᴡʏᴢ"
        val plain = "abcdefghijklmnoprsstuvwyz"
        return buildString(formatted.length) {
            for (character in formatted) {
                val index = smallCaps.indexOf(character)
                append(when {
                    index >= 0 -> plain[index]
                    character == '➜' || character == '→' -> '-'
                    else -> character
                })
            }
        }.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    }

    private fun clearAttempt() {
        startedAtNanos = Long.MIN_VALUE
        retryAtNanos = Long.MIN_VALUE
        phase = Phase.IDLE
        commandCount = 0
        candidateIdentities = emptyMap()
    }

    private companion object {
        val RATE_LIMIT = Regex("you need to wait another ([0-9]+(?:\\.[0-9]+)?) seconds?")
        const val CHECK_INTERVAL_NANOS = 500_000_000L
        const val COMMAND_COOLDOWN_NANOS = 4_000_000_000L
        const val RATE_LIMIT_PADDING_NANOS = 100_000_000L
        const val OPEN_TIMEOUT_NANOS = 10_000_000_000L
        const val OPEN_FAILURE_COOLDOWN_NANOS = 30_000_000_000L
        const val MAX_LIFETIME_NANOS = 45_000_000_000L
        const val MAX_COMMANDS = 4 // one initial command and up to three source retries
        const val STRIKES_TO_SKIP = 2
        const val ITEM_SKIP_NANOS = 600_000_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
