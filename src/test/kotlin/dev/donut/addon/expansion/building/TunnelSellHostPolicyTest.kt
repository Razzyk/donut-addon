package dev.donut.addon.expansion.building

import dev.donut.addon.expansion.automation.core.DonutSellCandidate
import dev.donut.addon.expansion.automation.core.DonutSellIdentity
import dev.donut.addon.expansion.automation.core.InventorySlotValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TunnelSellHostPolicyTest {
    @Test
    fun `host checks at 500ms and starts only after the four second command cooldown`() {
        val policy = TunnelSellHostPolicy()
        val stacks = candidates()

        assertTrue(policy.shouldCheck(0L))
        assertFalse(policy.shouldCheck(499_999_999L))
        assertTrue(policy.shouldCheck(500_000_000L))
        assertTrue(policy.begin(500_000_000L, stacks))
        assertFalse(policy.maySendCommand(999_999_999L))
        assertTrue(policy.maySendCommand(1_000_000_000L))
        assertTrue(policy.commandSent(1_000_000_000L))
        policy.finishWithoutOffer()

        assertFalse(policy.canStart(4_999_999_999L))
        assertTrue(policy.canStart(5_000_000_000L))
    }

    @Test
    fun `formatted rate limit schedules at most three retries with the stated delay and command gap`() {
        val policy = TunnelSellHostPolicy()
        assertTrue(policy.begin(0L, candidates()))
        assertTrue(policy.commandSent(0L))

        val retryAt = policy.observeRateLimit("§cYou need to wait another 2.5 seconds to execute a command", 100_000_000L)
        assertEquals(2_700_000_000L, retryAt)
        assertFalse(policy.maySendCommand(2_699_999_999L))
        assertTrue(policy.maySendCommand(2_700_000_000L))
        assertTrue(policy.commandSent(2_700_000_000L))

        repeat(2) { retry ->
            val sentAt = 2_800_000_000L + retry * 600_000_000L
            policy.observeRateLimit("You need to wait another 0 seconds", sentAt)
            assertTrue(policy.commandSent(sentAt + 500_000_000L))
        }
        assertEquals(4, policy.commandCount())
        assertNull(policy.observeRateLimit("You need to wait another 0 seconds", 4_000_000_000L))
        assertFalse(policy.maySendCommand(50_000_000_000L))
    }

    @Test
    fun `opening timeout still expires a long server rate-limit wait and ignores messages after menu open`() {
        val policy = TunnelSellHostPolicy()
        assertTrue(policy.begin(0L, candidates()))
        assertTrue(policy.commandSent(0L))
        assertEquals(30_100_000_000L, policy.observeRateLimit("You need to wait another 30 seconds", 0L))
        assertFalse(policy.openingTimedOut(10_000_000_000L))
        assertTrue(policy.openingTimedOut(10_000_000_001L))

        val active = TunnelSellHostPolicy()
        assertTrue(active.begin(0L, candidates()))
        assertTrue(active.commandSent(0L))
        active.menuOpened()
        assertNull(active.observeRateLimit("You need to wait another 30 seconds", 1L))
    }

    @Test
    fun `two unsold attempts skip the item for ten minutes while a confirmed sale clears strikes`() {
        val policy = TunnelSellHostPolicy()
        val candidate = candidates().first()
        val identity = DonutSellIdentity.of(candidate.stack)
        assertTrue(policy.begin(0L, candidates()))
        assertTrue(policy.commandSent(0L))
        val first = policy.settlement(emptyMap(), mapOf(identity to 64), 1_000L)
        assertTrue(first.newlySkippedItemIds.isEmpty())
        assertEquals(1, policy.strikeCount(candidate.stack.itemId))

        assertTrue(policy.begin(4_000_000_000L, candidates()))
        assertTrue(policy.commandSent(4_000_000_000L))
        val second = policy.settlement(emptyMap(), mapOf(identity to 64), 4_000_001_000L)
        assertEquals(setOf(candidate.stack.itemId), second.newlySkippedItemIds)
        assertEquals(0, policy.strikeCount(candidate.stack.itemId))
        assertTrue(policy.eligible(candidates(), 4_000_002_000L).none { it.stack.itemId == candidate.stack.itemId })
        assertTrue(policy.eligible(candidates(), 604_000_001_001L).any { it.stack.itemId == candidate.stack.itemId })

        assertTrue(policy.begin(604_000_002_000L, candidates()))
        assertTrue(policy.commandSent(604_000_002_000L))
        val success = policy.settlement(mapOf(identity to 64), emptyMap(), 604_000_003_000L)
        assertEquals(setOf(candidate.stack.itemId), success.successfulItemIds)
        assertEquals(0, policy.strikeCount(candidate.stack.itemId))
    }

    @Test
    fun `overall lifecycle and menu opening timeout are bounded`() {
        val policy = TunnelSellHostPolicy()
        assertTrue(policy.begin(0L, candidates()))
        assertTrue(policy.commandSent(0L))
        assertFalse(policy.openingTimedOut(10_000_000_000L))
        assertTrue(policy.openingTimedOut(10_000_000_001L))
        assertFalse(policy.expired(45_000_000_000L))
        assertTrue(policy.expired(45_000_000_001L))
        assertEquals(75_000_000_001L, policy.failCooldownUntilNanos(45_000_000_001L))
    }

    private fun candidates() = listOf(
        DonutSellCandidate(9, InventorySlotValue("minecraft:cobblestone", 64)),
        DonutSellCandidate(10, InventorySlotValue("minecraft:gravel", 32)),
    )
}
