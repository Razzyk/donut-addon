package dev.donut.addon.expansion.building

import dev.donut.addon.expansion.automation.core.AuctionPurchaseReceiptKind
import dev.donut.addon.expansion.automation.core.AuctionPurchaseConfirmationAction
import dev.donut.addon.expansion.automation.core.DialogSnapshot
import dev.donut.addon.expansion.automation.core.DialogWidgetSnapshot
import dev.donut.addon.expansion.automation.core.InventorySlotValue
import dev.donut.addon.expansion.automation.core.MenuSnapshot
import dev.donut.addon.expansion.automation.core.SlotSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class TunnelAuctionRulesTest {
    @Test
    fun sourcePurchaseReceiptKeepsReportedNameAndWholeListingCount() {
        val parsed = TunnelAuctionRules.parseNotice("You bought 64x Golden Apples for $125k")!!

        assertEquals(AuctionPurchaseReceiptKind.PURCHASED, parsed.kind)
        assertEquals("Golden Apples", parsed.reportedName)
        assertEquals("minecraft:golden_apple", parsed.itemId)
        assertEquals(64, parsed.count)
        assertEquals(BigDecimal("125000"), parsed.totalPrice)
    }

    @Test
    fun sourceDialogUsesCapturedItemAndUniquePositiveButton() {
        val expected = listing("minecraft:golden_apple", 64, "Golden Apple")
        val dialog = DialogSnapshot(
            screenIdentity = 54L,
            title = "Confirm Purchase",
            widgets = listOf(
                DialogWidgetSnapshot(0, "button", "Yes, buy"),
                DialogWidgetSnapshot(1, "button", "Cancel"),
            ),
            bodyText = listOf("You are buying this listing", "Total price: $125k"),
            itemStacks = listOf(InventorySlotValue(
                expected.itemId, expected.count, expected.name, emptyList(), "full-components",
                emptyMap(), "procurement-match",
            )),
        )

        val confirmation = TunnelAuctionRules.confirmationFromDialog(dialog, expected)

        assertNotNull(confirmation)
        assertEquals(BigDecimal("125000"), confirmation!!.totalPrice)
        assertEquals(expected.itemId, confirmation.itemId)
        assertEquals(expected.count, confirmation.count)
        val action = confirmation.action as AuctionPurchaseConfirmationAction.DialogWidget
        assertEquals(0, action.widgetIndex)
    }

    @Test
    fun sourceDialogTextFallbackRequiresItemAndUnambiguousPrice() {
        val expected = listing("minecraft:experience_bottle", 64, "Bottle o' Enchanting")
        val valid = DialogSnapshot(
            55L, "Purchase Confirmation", listOf(DialogWidgetSnapshot(2, "button", "Confirm")),
            bodyText = listOf("Buy 64 Bottle o' Enchanting for $1.25b"),
        )
        val ambiguous = valid.copy(bodyText = valid.bodyText + "Total: $1b")
        val wrongItem = valid.copy(bodyText = listOf("Buy 64 Golden Apples for $1.25b"))

        assertEquals(BigDecimal("1250000000"), TunnelAuctionRules.confirmationFromDialog(valid, expected)?.totalPrice)
        assertNull(TunnelAuctionRules.confirmationFromDialog(ambiguous, expected))
        assertNull(TunnelAuctionRules.confirmationFromDialog(wrongItem, expected))
    }

    @Test
    fun missingReceiptPriceRemainsIncompleteEvidence() {
        val parsed = TunnelAuctionRules.parseNotice("You bought Totem of Undying")!!

        assertEquals(1, parsed.count)
        assertNull(parsed.totalPrice)
    }

    @Test
    fun exactMoneyParserSupportsAllSourceSuffixes() {
        assertEquals(BigDecimal("1250000000"), TunnelAuctionRules.parseMoney("$1.25b"))
        assertEquals(BigDecimal("1000000"), TunnelAuctionRules.parseMoney("1m"))
        assertNull(TunnelAuctionRules.parseMoney("$1 trillion"))
    }

    @Test
    fun rejectionNoticeDoesNotInventClickedItemName() {
        val parsed = TunnelAuctionRules.parseNotice("You don't have enough money for that listing")!!

        assertEquals(AuctionPurchaseReceiptKind.INSUFFICIENT_FUNDS, parsed.kind)
        assertEquals("", parsed.reportedName)
        assertNull(parsed.itemId)
    }

    @Test
    fun forwardPageControlMustBeUnambiguousAndOutsideListingGrid() {
        val menu = menu(
            SlotSnapshot(50, "minecraft:arrow", 1, "Previous Page", emptyList()),
            SlotSnapshot(51, "minecraft:arrow", 1, "Next Page", emptyList()),
        )
        val action = TunnelAuctionRules.nextPageAction(menu, 54)

        assertNotNull(action)
        assertEquals(51, action!!.slotIndex)
    }

    @Test
    fun confirmationNeedsOnePositiveActionAndOneTotalPrice() {
        val menu = menu(
            SlotSnapshot(0, "minecraft:golden_apple", 64, "Golden Apple", listOf("Total: $125k")),
            SlotSnapshot(2, "minecraft:lime_stained_glass_pane", 1, "Confirm Purchase", emptyList()),
            SlotSnapshot(3, "minecraft:red_stained_glass_pane", 1, "Cancel", emptyList()),
        )

        assertEquals(2, TunnelAuctionRules.positiveConfirmationAction(menu, 27)?.index)
        assertEquals(BigDecimal("125000"), TunnelAuctionRules.confirmationTotal(menu, 27))
        assertNull(TunnelAuctionRules.positiveConfirmationAction(
            menu.copy(slots = menu.slots + SlotSnapshot(4, "minecraft:lime_stained_glass_pane", 1, "Yes", emptyList())), 27,
        ))
    }

    private fun menu(vararg slots: SlotSnapshot) = MenuSnapshot(
        screenIdentity = 99L,
        menuId = 7,
        title = "Confirm Purchase",
        slots = slots.toList(),
        cursorEmpty = true,
        stateId = 12,
    )

    private fun listing(itemId: String, count: Int, name: String) = SlotSnapshot(
        index = 4,
        itemId = itemId,
        count = count,
        name = name,
        lore = emptyList(),
        componentFingerprint = "full-components",
        actualEnchantments = emptyMap(),
        procurementFingerprint = "procurement-match",
    )
}
