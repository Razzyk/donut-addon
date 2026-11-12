package dev.donut.addon.expansion.automation;

import static org.junit.jupiter.api.Assertions.*;

import dev.donut.addon.expansion.automation.core.ActionGate;
import dev.donut.addon.expansion.automation.core.ActionTicket;
import dev.donut.addon.expansion.automation.core.AutomationSnapshot;
import dev.donut.addon.expansion.automation.core.MenuSnapshot;
import dev.donut.addon.expansion.automation.core.PlannedEffect;
import dev.donut.addon.expansion.automation.core.SessionStamp;
import dev.donut.addon.expansion.automation.core.SlotSnapshot;
import dev.donut.addon.expansion.automation.core.TicketDecision;
import dev.donut.addon.expansion.automation.core.MarketRules;
import dev.donut.addon.expansion.automation.core.ListingData;
import dev.donut.addon.expansion.automation.core.ExplosionSafety;
import dev.donut.addon.expansion.automation.core.InventoryStaging;
import dev.donut.addon.expansion.automation.core.InventoryStagePlan;
import dev.donut.addon.expansion.automation.core.ListingLedger;
import dev.donut.addon.expansion.automation.core.ListingLedgerState;
import dev.donut.addon.expansion.automation.core.ListingPriceReconciler;
import dev.donut.addon.expansion.automation.core.PriceAction;
import dev.donut.addon.expansion.automation.core.InventoryStaging;
import dev.donut.addon.expansion.automation.core.InventoryStagePlan;
import dev.donut.addon.expansion.automation.core.ListingLedger;
import dev.donut.addon.expansion.automation.core.ListingLedgerState;
import dev.donut.addon.expansion.automation.core.ListingPriceReconciler;
import dev.donut.addon.expansion.automation.core.PriceAction;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutomationProtocolTest {
    @Test
    void amountParsingIsExactAndRejectsAmbiguousInput() {
        assertEquals(new BigDecimal("500000000"), MarketRules.INSTANCE.parseAmount("500m"));
        assertEquals(new BigDecimal("12500"), MarketRules.INSTANCE.parseAmount("$12.5k"));
        assertEquals(new BigDecimal("1200"), MarketRules.INSTANCE.parseAmount("1,200"));
        assertNull(MarketRules.INSTANCE.parseAmount("12k;pay other"));
        assertNull(MarketRules.INSTANCE.parseAmount("-40"));
    }

    @Test
    void listingPredicatesRespectPriceHoursAndEnchantMode() {
        var listing = new ListingData("minecraft:diamond_sword", 1,
            new BigDecimal("50"), new BigDecimal("2.5"), Map.of("sharpness", 5, "mending", 1), "SellerOne");
        var wanted = Map.of("sharpness", 5);
        assertTrue(MarketRules.INSTANCE.listingMatches(listing, "diamond_sword", new BigDecimal("60"),
            new BigDecimal("2"), wanted, true));
        assertFalse(MarketRules.INSTANCE.listingMatches(listing, "diamond_sword", new BigDecimal("40"),
            BigDecimal.ZERO, wanted, true));
        assertFalse(MarketRules.INSTANCE.listingMatches(listing, "diamond_sword", null,
            new BigDecimal("3"), wanted, true));
        assertFalse(MarketRules.INSTANCE.listingMatches(listing, "diamond_sword", null,
            BigDecimal.ZERO, wanted, false));
    }

    @Test
    void actionGateRejectsWrongSessionExpiredMenusAndChangedSlots() {
        var session = new SessionStamp(7, 11, 12, 13);
        var slot = new SlotSnapshot(3, "minecraft:diamond", 2, "Diamond", List.of("$5 each"));
        var menu = new MenuSnapshot(99, 4, "Auction", List.of(slot), true);
        var snapshot = new AutomationSnapshot(session, 20, menu, java.util.Set.of(), java.util.Set.of(), "", null);
        var gate = new ActionGate();

        var changed = new ActionTicket(1, session, 21, new PlannedEffect.Click(4, 3, 0, false),
            99L, 4, 3, "changed", null, null, null);
        assertTrue(gate.offer(changed));
        assertNull(gate.consume(snapshot));
        assertEquals(0, gate.getSize());

        var expiring = new ActionTicket(2, session, 19, new PlannedEffect.Command("ah"),
            null, null, null, null, null, null, null);
        assertTrue(gate.offer(expiring));
        assertNull(gate.consume(snapshot));

        var otherSession = new ActionTicket(3, new SessionStamp(8, 11, 12, 13), 30,
            new PlannedEffect.Command("ah"), null, null, null, null, null, null, null);
        assertTrue(gate.offer(otherSession));
        assertNull(gate.consume(snapshot));
    }

    @Test
    void actionGateAllowsAtMostOneCommitPerTick() {
        var session = new SessionStamp(1, 2, 3, 4);
        var snapshot = new AutomationSnapshot(session, 5, null, java.util.Set.of(), java.util.Set.of(), "", null);
        var gate = new ActionGate();
        assertTrue(gate.offer(new ActionTicket(1, session, 5, new PlannedEffect.Command("ah"),
            null, null, null, null, null, null, null)));
        assertEquals(1, gate.consume(snapshot).getId());
        assertTrue(gate.offer(new ActionTicket(2, session, 6, new PlannedEffect.Command("ah"),
            null, null, null, null, null, null, null)));
        assertNull(gate.consume(snapshot));
    }

    @Test
    void userInputParsersRejectCommandInjectionAndInvalidNames() {
        assertEquals("Razzy_1", MarketRules.INSTANCE.safePlayerName("Razzy_1"));
        assertNull(MarketRules.INSTANCE.safePlayerName("/pay victim"));
        assertNull(MarketRules.INSTANCE.safeCommandFragment("stone; pay victim"));
        assertEquals("mending", MarketRules.INSTANCE.parseEnchantments("Mending").keySet().iterator().next());
        assertNull(MarketRules.INSTANCE.parseEnchantments("sharpness 5; /pay victim"));
    }

    @Test
    void explosionGuardUsesUnoccludedUpperBoundAndRejectsInvalidDistances() {
        assertTrue(ExplosionSafety.INSTANCE.upperBoundDamageHearts(12.0, 6.0) == 0.0);
        assertTrue(ExplosionSafety.INSTANCE.upperBoundDamageHearts(0.0, 6.0) > 8.0);
        assertFalse(ExplosionSafety.INSTANCE.withinHeartLimit(Double.NaN, 6.0, 8.0));
        assertFalse(ExplosionSafety.INSTANCE.withinHeartLimit(0.0, 6.0, 8.0));
    }

    @Test
    void inventoryStagingUsesExactStackAndCursorFreeFingerprintGuard() {
        var stock = new SlotSnapshot(9, "minecraft:diamond", 4, "Diamond", List.of());
        var emptySource = new SlotSnapshot(9, "", 0, "", List.of());
        var emptyBar = new SlotSnapshot(36, "", 0, "", List.of());
        var menu = new MenuSnapshot(1, 0, "Player Inventory", List.of(stock, emptyBar), true);
        var plan = InventoryStaging.INSTANCE.plan(menu, "diamond", 4);
        assertInstanceOf(InventoryStagePlan.Swap.class, plan);
        var swap = (InventoryStagePlan.Swap) plan;
        assertEquals(9, swap.getSourceMenuSlot());
        assertEquals(0, swap.getHotbarSlot());
        assertFalse(InventoryStaging.INSTANCE.swapObserved(menu, swap));

        var swapped = new MenuSnapshot(1, 0, "Player Inventory", List.of(
            emptySource, new SlotSnapshot(36, "minecraft:diamond", 4, "Diamond", List.of())), true);
        assertTrue(InventoryStaging.INSTANCE.swapObserved(swapped, swap));
        var carried = new MenuSnapshot(1, 0, "Player Inventory", List.of(stock, emptyBar), false);
        assertEquals(InventoryStagePlan.Unsafe.INSTANCE, InventoryStaging.INSTANCE.plan(carried, "diamond", 4));
        var splitPlan = InventoryStaging.INSTANCE.plan(menu, "diamond", 3);
        assertInstanceOf(InventoryStagePlan.Transfer.class, splitPlan);
        var transfer = (InventoryStagePlan.Transfer) splitPlan;
        assertEquals(3, transfer.getTransferCount());
        assertEquals(3, transfer.getRequiredCount());
    }

    @Test
    void listingPriceReconciliationKeepsDeepDropsAndRelistsAgainstRealQuotes() {
        var ignored = ListingPriceReconciler.INSTANCE.decide(
            new BigDecimal("10000"), new BigDecimal("7000"), true, true,
            new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("3"), new BigDecimal("20"), 2);
        assertEquals(PriceAction.IGNORE_DEEP_DROP, ignored.getAction());
        assertEquals(new BigDecimal("10000"), ignored.getNextUnitPrice());

        var undercut = ListingPriceReconciler.INSTANCE.decide(
            new BigDecimal("1000"), new BigDecimal("900"), true, true,
            new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("3"), new BigDecimal("20"), 1);
        assertEquals(PriceAction.RELIST, undercut.getAction());
        assertEquals(new BigDecimal("850"), undercut.getNextUnitPrice());

        var noMarket = ListingPriceReconciler.INSTANCE.decide(
            BigDecimal.ZERO, null, true, false,
            new BigDecimal("500"), BigDecimal.ZERO, new BigDecimal("3"), new BigDecimal("20"), 0);
        assertEquals(new BigDecimal("1000"), noMarket.getNextUnitPrice());
        assertEquals(PriceAction.WAIT, ListingPriceReconciler.INSTANCE.decide(
            BigDecimal.ZERO, null, false, false, BigDecimal.ONE, BigDecimal.ZERO,
            BigDecimal.ONE, BigDecimal.ONE, 0).getAction());
    }

    @Test
    void listingLedgerSeparatesServerAcknowledgementAndOwnMenuReconciliation() {
        var initial = new ListingLedgerState();
        var afterAck = ListingLedger.INSTANCE.listed(initial, 2);
        assertEquals(2, afterAck.getOpen());
        assertEquals(2, afterAck.getListedLifetime());
        assertEquals(0, afterAck.getSoldLifetime());
        var afterOwnedMenuRefresh = ListingLedger.INSTANCE.reconcileOpenCount(afterAck, 1);
        assertEquals(1, afterOwnedMenuRefresh.getOpen());
        assertEquals(0, afterOwnedMenuRefresh.getSoldLifetime());
        var afterSale = ListingLedger.INSTANCE.sold(afterOwnedMenuRefresh, 1);
        assertEquals(0, afterSale.getOpen());
        assertEquals(1, afterSale.getSoldLifetime());
    }

}
