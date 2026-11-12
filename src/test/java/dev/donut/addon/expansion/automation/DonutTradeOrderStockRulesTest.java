package dev.donut.addon.expansion.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.donut.addon.expansion.automation.core.InventorySlotValue;
import dev.donut.addon.expansion.automation.core.MenuSnapshot;
import dev.donut.addon.expansion.automation.core.SlotSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DonutTradeOrderStockRulesTest {
    @Test
    void sourceOwnOrderLookupUsesItemTypeAndIgnoresConfiguredEnchantmentVariant() {
        var slots = emptySlots();
        slots.set(3, new SlotSnapshot(3, "minecraft:diamond_sword", 1, "Diamond Sword", List.of("Sharpness IV"),
            "sharpness-4", Map.of("sharpness", 4)));
        slots.set(6, new SlotSnapshot(6, "minecraft:diamond_sword", 1, "Diamond Sword", List.of("Sharpness V"),
            "sharpness-5", Map.of("sharpness", 5)));

        var found = DonutTradeOrderStockRules.INSTANCE.firstOwnOrderItem(menu(slots), 54, "minecraft:diamond_sword");
        assertNotNull(found);
        assertEquals(3, found.getIndex());
    }

    @Test
    void ownOrderLookupIsBoundedToFirstFortyFiveContainerSlots() {
        var slots = emptySlots();
        slots.set(45, item(45, "minecraft:diamond_sword", 1, "offhand"));
        assertNull(DonutTradeOrderStockRules.INSTANCE.firstOwnOrderItem(menu(slots), 54, "minecraft:diamond_sword"));
        slots.set(44, item(44, "minecraft:diamond_sword", 1, "inside"));
        assertEquals(44, DonutTradeOrderStockRules.INSTANCE.firstOwnOrderItem(
            menu(slots), 54, "minecraft:diamond_sword").getIndex());
    }

    @Test
    void sourceCollectionMatchesAnyNonemptyStackOfTheOrderItemType() {
        var higherVariant = new SlotSnapshot(4, "minecraft:diamond_sword", 1, "Diamond Sword", List.of("Sharpness V"),
            "sharpness-5", Map.of("sharpness", 5));
        assertTrue(DonutTradeOrderStockRules.INSTANCE.isCollectedOrderItem("minecraft:diamond_sword", higherVariant));
        assertFalse(DonutTradeOrderStockRules.INSTANCE.isCollectedOrderItem("minecraft:diamond_sword",
            item(5, "minecraft:iron_sword", 1, "other")));
        assertFalse(DonutTradeOrderStockRules.INSTANCE.isCollectedOrderItem("minecraft:diamond_sword",
            item(4, "minecraft:diamond_sword", 0, "empty")));
    }

    @Test
    void topOrderUsesTheFirstMatchingContainerItemAndIgnoresLaterHigherPricesAndPlayerInventory() {
        var slots = emptySlots();
        slots.set(2, new SlotSnapshot(2, "minecraft:diamond_sword", 1, "Diamond Sword",
            List.of("Pay $12 each"), "sharpness-5", Map.of("sharpness", 5)));
        slots.set(5, new SlotSnapshot(5, "minecraft:diamond_sword", 1, "Diamond Sword",
            List.of("Pay $30 each"), "sharpness-5", Map.of("sharpness", 5)));
        slots.set(45, new SlotSnapshot(45, "minecraft:diamond_sword", 1, "Diamond Sword",
            List.of("Pay $999 each"), "sharpness-5", Map.of("sharpness", 5)));

        assertEquals(new java.math.BigDecimal("12"), DonutTradeOrderStockRules.INSTANCE.firstTopOrderPrice(
            menu(slots), 45, "minecraft:diamond_sword", Map.of("sharpness", 4)));
    }

    @Test
    void topOrderUsesSourceAtLeastEnchantmentMatchingAndDoesNotSkipFirstMatchingUnpricedOrder() {
        var slots = emptySlots();
        slots.set(1, new SlotSnapshot(1, "minecraft:diamond_sword", 1, "Diamond Sword",
            List.of("Orders: none"), "sharpness-5", Map.of("sharpness", 5, "unbreaking", 3)));
        slots.set(4, new SlotSnapshot(4, "minecraft:diamond_sword", 1, "Diamond Sword",
            List.of("Pay $40 each"), "sharpness-4", Map.of("sharpness", 4)));

        assertNull(DonutTradeOrderStockRules.INSTANCE.firstTopOrderPrice(
            menu(slots), 45, "minecraft:diamond_sword", Map.of("sharpness", 4)));
    }

    @Test
    void sourceOrderPriceParserUsesMinimumQualifyingMoneyAndNormalizesSmallCapsAndArrows() {
        assertEquals(new java.math.BigDecimal("12"), DonutTradeOrderStockRules.INSTANCE.sourceOrderPrice(
            List.of("Pay $12 each; total $768 for 64")));
        assertEquals(0, new java.math.BigDecimal("2500").compareTo(
            DonutTradeOrderStockRules.INSTANCE.sourceOrderPrice(List.of("ᴘʀɪᴄᴇ ➤ $2.5k"))));
        assertEquals(new java.math.BigDecimal("2000000000"), DonutTradeOrderStockRules.INSTANCE.sourceOrderPrice(
            List.of("Current $2b")));
    }

    @Test
    void countsAllThirtySixPlayerSlotsAndExcludesContainerAndOffhandSlots() {
        var slots = emptySlots();
        slots.set(8, item(8, "minecraft:diamond", 32, "container"));
        slots.set(9, item(9, "minecraft:diamond", 4, "main"));
        slots.set(36, item(36, "minecraft:diamond", 5, "hotbar"));
        slots.set(45, item(45, "minecraft:diamond", 8, "offhand"));

        assertEquals(9, DonutTradeOrderStockRules.INSTANCE.countMatching(
            menu(slots), "minecraft:diamond", Map.of()));
    }

    @Test
    void followsSourceProductPredicateAcrossComponentAndHigherEnchantmentVariants() {
        var slots = emptySlots();
        slots.set(9, new SlotSnapshot(9, "minecraft:diamond_sword", 2, "custom blade", List.of(),
            "custom-name-a", Map.of("sharpness", 3)));
        slots.set(37, new SlotSnapshot(37, "minecraft:diamond_sword", 7, "other blade", List.of(),
            "custom-name-b", Map.of("sharpness", 5, "unbreaking", 2)));
        slots.set(38, new SlotSnapshot(38, "minecraft:diamond_sword", 16, "plain", List.of(),
            "plain", Map.of("unbreaking", 3)));

        // Product.matches accepts the requested enchantment level and counts matching item variants,
        // even when safe hotbar staging cannot merge their different components.
        assertEquals(9, DonutTradeOrderStockRules.INSTANCE.countMatching(
            menu(slots), "minecraft:diamond_sword", Map.of("sharpness", 3)));
    }

    @Test
    void createsAnotherOrderOnlyWhenMatchingStockIsBelowOneListing() {
        assertTrue(DonutTradeOrderStockRules.INSTANCE.shouldCreateRestockOrder(15, 16, true, true));
        assertFalse(DonutTradeOrderStockRules.INSTANCE.shouldCreateRestockOrder(16, 16, true, true));
        assertFalse(DonutTradeOrderStockRules.INSTANCE.shouldCreateRestockOrder(48, 16, true, true));
        assertFalse(DonutTradeOrderStockRules.INSTANCE.shouldCreateRestockOrder(-1, 16, true, true));
        assertFalse(DonutTradeOrderStockRules.INSTANCE.shouldCreateRestockOrder(0, 0, true, true));
        assertFalse(DonutTradeOrderStockRules.INSTANCE.shouldCreateRestockOrder(0, 16, false, true));
        assertFalse(DonutTradeOrderStockRules.INSTANCE.shouldCreateRestockOrder(0, 16, true, false));
    }

    private static MenuSnapshot menu(List<SlotSnapshot> slots) {
        return new MenuSnapshot(1L, 0, "Player Inventory", slots, true, 0,
            new InventorySlotValue("", 0));
    }

    private static ArrayList<SlotSnapshot> emptySlots() {
        var slots = new ArrayList<SlotSnapshot>();
        for (int slot = 0; slot < 46; slot++) slots.add(item(slot, "", 0, ""));
        return slots;
    }

    private static SlotSnapshot item(int index, String itemId, int count, String component) {
        return new SlotSnapshot(index, itemId, count, itemId.isEmpty() ? "" : itemId, List.of(), component);
    }
}
