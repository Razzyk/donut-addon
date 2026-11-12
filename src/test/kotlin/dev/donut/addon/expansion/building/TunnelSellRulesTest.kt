package dev.donut.addon.expansion.building

import dev.donut.addon.expansion.automation.core.InventorySlotValue
import dev.donut.addon.expansion.automation.core.InventorySyncFrame
import dev.donut.addon.expansion.automation.core.MenuSnapshot
import dev.donut.addon.expansion.automation.core.SlotSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TunnelSellRulesTest {
    @Test
    fun junkWhitelistExcludesSelectedHotbarAndLimitsBatchToFour() {
        val slots = (0..5).map { index -> SlotSnapshot(index, "minecraft:cobblestone", 64, "Cobblestone", emptyList()) } +
            SlotSnapshot(9, "minecraft:diamond", 64, "Diamond", emptyList())
        val menu = menu(slots)
        val map = (0..5).associate { it to (if (it < 3) 9 + it else 36 + it - 3) } + (9 to 15)

        val candidates = TunnelSellRules.candidates(menu, map, selectedHotbarSlot = 2)

        assertEquals(4, candidates.size)
        assertFalse(candidates.any { map[it.index] == 38 })
        assertTrue(candidates.all { it.itemId == "minecraft:cobblestone" })
    }

    @Test
    fun approvedStackSelectionKeepsDuplicateExactStacksUpToFour() {
        val stacks = listOf(
            35 to InventorySlotValue("minecraft:cobblestone", 64, componentFingerprint = "same"),
            34 to InventorySlotValue("minecraft:cobblestone", 32, componentFingerprint = "same"),
            33 to InventorySlotValue("minecraft:cobblestone", 16, componentFingerprint = "same"),
            32 to InventorySlotValue("minecraft:dirt", 64),
            31 to InventorySlotValue("minecraft:gravel", 64),
        )

        val candidates = TunnelSellRules.approvedStacks(stacks, selectedHotbarSlot = 33)

        assertEquals(listOf(35, 34, 32, 31), candidates.map { it.inventorySlot })
    }

    @Test
    fun approvedStackSelectionSkipsAmbiguousSameItemVariants() {
        val stacks = listOf(
            35 to InventorySlotValue("minecraft:stone", 64, componentFingerprint = "custom-a"),
            34 to InventorySlotValue("minecraft:stone", 64, componentFingerprint = "custom-b"),
            33 to InventorySlotValue("minecraft:andesite", 64),
        )

        val candidates = TunnelSellRules.approvedStacks(stacks, selectedHotbarSlot = 8)

        assertEquals(listOf(35, 33), candidates.map { it.inventorySlot })
    }

    @Test
    fun sellTransferNeedsServerEvidenceForSourceAndContainerDelta() {
        val tracker = tracker()
        val target = InventorySlotValue("minecraft:cobblestone", 64)
        val source = InventorySlotValue("minecraft:cobblestone", 64)
        assertTrue(tracker.begin(
            connectionId = 22L,
            menuId = 5,
            baselineStateId = 10,
            menuMapEpoch = 3L,
            activeInventorySlotMap = mapOf(60 to 15),
            baselineSlots = mapOf(0 to InventorySlotValue("", 0), 60 to source),
            sourceActiveSlot = 60,
            containerSlotCount = 54,
        ))

        assertEquals(TunnelSellTransferStatus.PENDING,
            tracker.observe(update(11, mapOf(60 to InventorySlotValue("", 0))), 3L))
        assertEquals(TunnelSellTransferStatus.ACKNOWLEDGED,
            tracker.observe(update(12, mapOf(0 to target)), 3L))
        assertEquals(TunnelSellTransferStatus.ACKNOWLEDGED, tracker.status())
    }

    @Test
    fun staleMenuEpochAndChangedStateDoNotConfirmSellOffer() {
        val tracker = tracker()
        tracker.begin(
            connectionId = 22L,
            menuId = 5,
            baselineStateId = 10,
            menuMapEpoch = 3L,
            activeInventorySlotMap = mapOf(60 to 15),
            baselineSlots = mapOf(0 to InventorySlotValue("", 0), 60 to InventorySlotValue("minecraft:cobblestone", 64)),
            sourceActiveSlot = 60,
            containerSlotCount = 54,
        )

        assertEquals(TunnelSellTransferStatus.PENDING,
            tracker.observe(update(11, mapOf(60 to InventorySlotValue("", 0))), 4L))
        assertEquals(TunnelSellTransferStatus.PENDING, tracker.status())
    }

    @Test
    fun saleResultOnlyAcceptsRemovalOfTheExactAcknowledgedOffer() {
        val offered = mapOf("minecraft:cobblestone" to 64, "minecraft:gravel" to 32)
        val baseline = mapOf("minecraft:lime_stained_glass_pane" to 1)

        assertEquals(offered, TunnelSellRules.remainingOffer(baseline, baseline + offered, offered))
        assertEquals(mapOf("minecraft:gravel" to 32), TunnelSellRules.remainingOffer(
            baseline, baseline + mapOf("minecraft:gravel" to 32), offered,
        ))
        assertEquals(emptyMap<String, Int>(), TunnelSellRules.remainingOffer(baseline, baseline, offered))
        assertEquals(null, TunnelSellRules.remainingOffer(
            baseline, baseline + offered + mapOf("minecraft:diamond" to 1), offered,
        ))
        assertEquals(null, TunnelSellRules.remainingOffer(
            baseline, baseline + mapOf("minecraft:cobblestone" to 65), offered,
        ))
    }

    @Test
    fun `fixed source sell slot accepts a blank native label but rejects explicit cancel controls`() {
        assertTrue(TunnelSellRules.isConfirmSaleButton(
            SlotSnapshot(53, "minecraft:lime_stained_glass_pane", 1, "", emptyList()),
        ))
        assertTrue(TunnelSellRules.isConfirmSaleButton(
            SlotSnapshot(53, "minecraft:lime_stained_glass_pane", 1, "Sell", emptyList()),
        ))
        assertFalse(TunnelSellRules.isConfirmSaleButton(
            SlotSnapshot(53, "minecraft:barrier", 1, "Cancel sale", emptyList()),
        ))
        val unlabeledMenu = menu((0..53).map { index ->
            if (index == 53) SlotSnapshot(index, "minecraft:lime_stained_glass_pane", 1, "", emptyList())
            else SlotSnapshot(index, "minecraft:air", 0, "", emptyList())
        })
        assertTrue(TunnelSellRules.isSellMenu(unlabeledMenu, containerSlotCount = 54))
        assertFalse(TunnelSellRules.isSellMenu(unlabeledMenu, containerSlotCount = 53))
    }

    private fun tracker() = TunnelSellTransferAckTracker()

    private fun update(state: Int, slots: Map<Int, InventorySlotValue>) = InventorySyncFrame(
        connectionId = 22L,
        menuId = 5,
        stateId = state,
        slots = slots,
        cursor = InventorySlotValue("", 0),
        fullContents = false,
    )

    private fun menu(slots: List<SlotSnapshot>) = MenuSnapshot(1L, 2, "Sell Items", slots, true, 1)
}
