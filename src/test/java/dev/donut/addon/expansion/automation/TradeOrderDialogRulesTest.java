package dev.donut.addon.expansion.automation;

import dev.donut.addon.expansion.automation.core.DialogSnapshot;
import dev.donut.addon.expansion.automation.core.InventorySlotValue;
import dev.donut.addon.expansion.automation.core.OrderAcknowledgementRules;
import dev.donut.addon.expansion.automation.core.OrderReviewRules;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TradeOrderDialogRulesTest {
    private static DialogSnapshot review(String itemId, Map<String, Integer> enchants, List<String> body) {
        return new DialogSnapshot(9L, "Review Order", List.of(), body, List.of(
                new InventorySlotValue(itemId, 1, "Diamond Sword", List.of(), "full", enchants, "procurement")
        ));
    }

    @Test
    void reviewUsesNativeItemAndBodyAmountAndRoundedPriceEvidence() {
        var dialog = review("minecraft:diamond_sword", Map.of("sharpness", 5),
                List.of("Amount: 64", "Price: $1.2k each"));
        assertTrue(OrderReviewRules.INSTANCE.matches(dialog, "minecraft:diamond_sword",
                Map.of("sharpness", 5), 64, new BigDecimal("1234")));
    }

    @Test
    void reviewRejectsWrongItemEnchantAmountOrPrice() {
        var body = List.of("Amount: 64", "Price: $1.2k each");
        assertFalse(OrderReviewRules.INSTANCE.matches(review("minecraft:golden_sword", Map.of("sharpness", 5), body),
                "minecraft:diamond_sword", Map.of("sharpness", 5), 64, new BigDecimal("1234")));
        assertFalse(OrderReviewRules.INSTANCE.matches(review("minecraft:diamond_sword", Map.of("sharpness", 4), body),
                "minecraft:diamond_sword", Map.of("sharpness", 5), 64, new BigDecimal("1234")));
        assertFalse(OrderReviewRules.INSTANCE.matches(review("minecraft:diamond_sword", Map.of("sharpness", 5),
                List.of("Amount: 63", "Price: $1.2k each")), "minecraft:diamond_sword",
                Map.of("sharpness", 5), 64, new BigDecimal("1234")));
        assertFalse(OrderReviewRules.INSTANCE.matches(review("minecraft:diamond_sword", Map.of("sharpness", 5),
                List.of("Amount: 64", "Price: $1.0k each")), "minecraft:diamond_sword",
                Map.of("sharpness", 5), 64, new BigDecimal("1234")));
    }

    @Test
    void reviewRequiresBothAmountAndPriceBodyLines() {
        assertFalse(OrderReviewRules.INSTANCE.matches(review("minecraft:diamond_sword", Map.of(),
                List.of("Amount: 64")), "minecraft:diamond_sword", Map.of(), 64, new BigDecimal("1000")));
    }

    @Test
    void textOnlyReviewMustShowConfiguredEnchantments() {
        var withEnchant = new DialogSnapshot(9L, "Review Order", List.of(),
                List.of("Diamond Sword", "Sharpness V", "Amount: 64", "Price: $100 each"), List.of());
        var withoutEnchant = new DialogSnapshot(9L, "Review Order", List.of(),
                List.of("Diamond Sword", "Amount: 64", "Price: $100 each"), List.of());
        assertTrue(OrderReviewRules.INSTANCE.matches(withEnchant, "minecraft:diamond_sword",
                Map.of("minecraft:sharpness", 5), 64, new BigDecimal("100")));
        assertFalse(OrderReviewRules.INSTANCE.matches(withoutEnchant, "minecraft:diamond_sword",
                Map.of("minecraft:sharpness", 5), 64, new BigDecimal("100")));
    }

    @Test
    void reviewUsesFirstAmountAndPriceValuesWithSourceRoundingBoundaries() {
        var exactStepAmount = review("minecraft:diamond_sword", Map.of(),
                List.of("Amount: 65", "Price: $100 each"));
        assertFalse(OrderReviewRules.INSTANCE.matches(exactStepAmount, "minecraft:diamond_sword", Map.of(),
                64, new BigDecimal("100")));

        var exactStepPrice = review("minecraft:diamond_sword", Map.of(),
                List.of("Amount: 64", "Price: $99 each"));
        assertTrue(OrderReviewRules.INSTANCE.matches(exactStepPrice, "minecraft:diamond_sword", Map.of(),
                64, new BigDecimal("100")));

        var misleadingTotal = review("minecraft:diamond_sword", Map.of(),
                List.of("Amount: 64", "Price: $100 each; total $6400"));
        assertFalse(OrderReviewRules.INSTANCE.matches(misleadingTotal, "minecraft:diamond_sword", Map.of(),
                64, new BigDecimal("6400")));
    }

    @Test
    void orderAcknowledgementMustStartAfterTheCreateAttempt() {
        assertFalse(OrderAcknowledgementRules.INSTANCE.isSuccess("You ordered 64x Diamond Sword", 99L, 100L));
        assertTrue(OrderAcknowledgementRules.INSTANCE.isSuccess("§aYou ordered 64x Diamond Sword", 101L, 100L));
        assertTrue(OrderAcknowledgementRules.INSTANCE.isSuccess("You ordered", -99L, -100L));
        assertFalse(OrderAcknowledgementRules.INSTANCE.isSuccess("You ordered", -101L, -100L));
        assertFalse(OrderAcknowledgementRules.INSTANCE.isSuccess("Someone ordered 64x Diamond Sword", 101L, 100L));
        assertTrue(OrderAcknowledgementRules.INSTANCE.isFailure("Order failed: not enough room"));
        assertFalse(OrderAcknowledgementRules.INSTANCE.isFailure("Player placed an order"));
    }
}
