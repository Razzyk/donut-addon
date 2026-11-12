package dev.donut.addon.expansion.automation;

import dev.donut.addon.expansion.automation.core.InventoryAckStatus;
import dev.donut.addon.expansion.automation.core.InventorySlotValue;
import dev.donut.addon.expansion.automation.core.InventorySyncFrame;
import dev.donut.addon.expansion.automation.core.MenuOpenAckTracker;
import dev.donut.addon.expansion.automation.core.MenuOpenExpectation;
import dev.donut.addon.expansion.automation.core.MenuPageAckTracker;
import dev.donut.addon.expansion.automation.core.MenuPageExpectation;
import dev.donut.addon.expansion.automation.core.MenuSlotChangeAckTracker;
import dev.donut.addon.expansion.automation.core.MenuSlotChangeExpectation;
import dev.donut.addon.expansion.automation.core.ListingLedger;
import dev.donut.addon.expansion.automation.core.ListingLedgerState;
import dev.donut.addon.expansion.automation.core.MenuSnapshot;
import dev.donut.addon.expansion.automation.core.OwnedListingRules;
import dev.donut.addon.expansion.automation.core.SlotSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OwnedListingAcknowledgementTest {
    @Test
    void collectClickRequiresChangedServerSlotInSameSessionAndMenu() {
        var before = new InventorySlotValue("minecraft:diamond", 1);
        var after = new InventorySlotValue("", 0);
        var tracker = new MenuSlotChangeAckTracker();
        tracker.begin(new MenuSlotChangeExpectation(7L, 12, 30, 5, before));

        assertEquals(InventoryAckStatus.PENDING, tracker.observe(
                new InventorySyncFrame(8L, 12, 31, Map.of(5, after), null, false)));
        assertEquals(InventoryAckStatus.PENDING, tracker.observe(
                new InventorySyncFrame(7L, 13, 31, Map.of(5, after), null, false)));
        assertEquals(InventoryAckStatus.PENDING, tracker.observe(
                new InventorySyncFrame(7L, 12, 30, Map.of(5, after), null, false)));
        assertEquals(InventoryAckStatus.ACKNOWLEDGED, tracker.observe(
                new InventorySyncFrame(7L, 12, 31, Map.of(5, after), null, false)));
        assertEquals(InventoryAckStatus.ACKNOWLEDGED, tracker.reconcileLocal(after, true));
        assertEquals(InventoryAckStatus.REJECTED, tracker.reconcileLocal(before, true));
    }

    @Test
    void ownListingsScanWaitsForFullContentOfNewMenu() {
        var tracker = new MenuOpenAckTracker();
        tracker.begin(new MenuOpenExpectation(4L, 0));
        assertFalse(tracker.observe(new InventorySyncFrame(4L, 2, 1, Map.of(), null, false)));
        assertFalse(tracker.observe(new InventorySyncFrame(4L, 0, 1, Map.of(), null, true)));
        assertFalse(tracker.observe(new InventorySyncFrame(5L, 2, 1, Map.of(), null, true)));
        assertTrue(tracker.observe(new InventorySyncFrame(4L, 2, 1, Map.of(), null, true)));
        assertEquals(2, tracker.openedMenuId());
        assertFalse(tracker.observe(new InventorySyncFrame(4L, 3, 2, Map.of(), null, true)));
    }

    @Test
    void authoritativeScanDistinguishesSalesFromRelistCollection() {
        var state = new ListingLedgerState(3, 3, 0);
        var afterSale = ListingLedger.INSTANCE.reconcileOwnedListings(state, 2, true);
        assertEquals(2, afterSale.getOpen());
        assertEquals(1, afterSale.getSoldLifetime());

        var afterCollection = ListingLedger.INSTANCE.reconcileOwnedListings(afterSale, 1, false);
        assertEquals(1, afterCollection.getOpen());
        assertEquals(1, afterCollection.getSoldLifetime());
        var relisted = ListingLedger.INSTANCE.listed(afterCollection, 1);
        assertEquals(2, relisted.getOpen());
        assertEquals(4, relisted.getListedLifetime());
    }

    @Test
    void productIdentityIncludesCanonicalRequiredEnchantments() {
        var sharpness = Map.of("sharpness", 5, "unbreaking", 3);
        var reversed = Map.of("unbreaking", 3, "sharpness", 5);
        var sharpnessOnly = Map.of("sharpness", 5);

        assertEquals(
                OwnedListingRules.INSTANCE.productKey("minecraft:diamond_sword", sharpness),
                OwnedListingRules.INSTANCE.productKey("DIAMOND_SWORD", reversed));
        assertNotEquals(
                OwnedListingRules.INSTANCE.productKey("diamond_sword", sharpness),
                OwnedListingRules.INSTANCE.productKey("diamond_sword", sharpnessOnly));
    }

    @Test
    void scanRequiresYourItemsIdentityAndACompleteUnpagedGrid() {
        var listing = new SlotSnapshot(0, "minecraft:diamond", 1, "Diamond", List.of());
        var empty = new SlotSnapshot(1, "", 0, "", List.of());

        assertTrue(OwnedListingRules.INSTANCE.isCompleteYourItemsScan(
                new MenuSnapshot(10L, 7, "Your Items (1/1)", List.of(listing, empty), true, 5), 2));
        assertFalse(OwnedListingRules.INSTANCE.isCompleteYourItemsScan(
                new MenuSnapshot(10L, 7, "Your Items (1/2)", List.of(listing, empty), true, 5), 2));
        assertFalse(OwnedListingRules.INSTANCE.isCompleteYourItemsScan(
                new MenuSnapshot(10L, 7, "Your Items", List.of(listing,
                        new SlotSnapshot(1, "minecraft:paper", 1, "Next Page", List.of())), true, 5), 2));
        assertFalse(OwnedListingRules.INSTANCE.isCompleteYourItemsScan(
                new MenuSnapshot(10L, 7, "Auction House", List.of(listing, empty), true, 5), 2));
        assertFalse(OwnedListingRules.INSTANCE.isCompleteYourItemsScan(
                new MenuSnapshot(10L, 7, "Your Items", List.of(listing,
                        new SlotSnapshot(1, "minecraft:diamond", 1, "Diamond", List.of())), true, 5), 2));
        assertFalse(OwnedListingRules.INSTANCE.isCompleteYourItemsScan(
                new MenuSnapshot(10L, 7, "Your Items", List.of(listing), true, 5), 2));
        assertFalse(OwnedListingRules.INSTANCE.isCompleteYourItemsScan(
                new MenuSnapshot(10L, 7, "Your Items", List.of(listing, listing), true, 5), 2));
    }

    @Test
    void yourItemsPageParserFindsBoundedNextAndLastPages() {
        var next = new SlotSnapshot(1, "minecraft:paper", 1, "Next Page", List.of());
        var pageOne = new MenuSnapshot(10L, 7, "Your Items (1/2)", List.of(
                new SlotSnapshot(0, "minecraft:diamond", 1, "Diamond", List.of()), next), true, 5);
        var pageTwo = new MenuSnapshot(10L, 7, "Your Items (2/2)", List.of(
                new SlotSnapshot(0, "", 0, "", List.of()),
                new SlotSnapshot(1, "minecraft:paper", 1, "Previous Page", List.of())), true, 6);
        var invalid = new MenuSnapshot(10L, 7, "Your Items (1/51)", pageOne.getSlots(), true, 5);

        var first = OwnedListingRules.INSTANCE.yourItemsPage(pageOne, 2);
        var last = OwnedListingRules.INSTANCE.yourItemsPage(pageTwo, 2);
        assertNotNull(first);
        assertEquals(1, first.getPageNumber());
        assertEquals(2, first.getTotalPages());
        assertEquals(1, first.getNextSlot());
        assertFalse(OwnedListingRules.INSTANCE.isTerminalYourItemsPage(pageOne, 2, first));
        assertNotNull(last);
        assertEquals(2, last.getPageNumber());
        assertNull(last.getNextSlot());
        assertTrue(OwnedListingRules.INSTANCE.isTerminalYourItemsPage(pageTwo, 2, last));
        assertNull(OwnedListingRules.INSTANCE.yourItemsPage(invalid, 2));
    }

    @Test
    void pageAdvanceRequiresSameMenuFullServerContentAndChangedPageIdentity() {
        var before0 = new InventorySlotValue("minecraft:diamond", 1, "Diamond");
        var before1 = new InventorySlotValue("minecraft:paper", 1, "Next Page");
        var after0 = new InventorySlotValue("minecraft:emerald", 1, "Emerald");
        var after1 = new InventorySlotValue("", 0);
        var tracker = new MenuPageAckTracker();
        assertTrue(tracker.begin(new MenuPageExpectation(4L, 12, 100L, 5, 2,
                "page:1/2", Map.of(0, before0, 1, before1))));

        assertEquals(InventoryAckStatus.PENDING, tracker.observe(
                new InventorySyncFrame(5L, 12, 6, Map.of(0, after0, 1, after1), null, true)));
        assertEquals(InventoryAckStatus.PENDING, tracker.observe(
                new InventorySyncFrame(4L, 13, 6, Map.of(0, after0, 1, after1), null, true)));
        assertEquals(InventoryAckStatus.PENDING, tracker.observe(
                new InventorySyncFrame(4L, 12, 5, Map.of(0, after0, 1, after1), null, true)));
        assertEquals(InventoryAckStatus.PENDING, tracker.observe(
                new InventorySyncFrame(4L, 12, 6, Map.of(0, after0, 1, after1), null, false)));
        assertEquals(InventoryAckStatus.PENDING, tracker.observe(
                new InventorySyncFrame(4L, 12, 6, Map.of(0, after0, 1, after1), null, true)));
        var notYetApplied = new MenuSnapshot(100L, 12, "Your Items (2/2)", List.of(
                new SlotSnapshot(0, "minecraft:diamond", 1, "Diamond", List.of()),
                new SlotSnapshot(1, "minecraft:paper", 1, "Next Page", List.of())), true, 6);
        assertEquals(InventoryAckStatus.PENDING, tracker.status(notYetApplied, "page:2/2"));
        var applied = new MenuSnapshot(100L, 12, "Your Items (2/2)", List.of(
                new SlotSnapshot(0, "minecraft:emerald", 1, "Emerald", List.of()),
                new SlotSnapshot(1, "", 0, "", List.of())), true, 6);
        assertEquals(InventoryAckStatus.ACKNOWLEDGED, tracker.status(applied, "page:2/2"));
    }

    @Test
    void listingLedgerOnlyAcceptsSourceStyleServerOutcomes() {
        assertEquals(TradeListingReplyKind.LISTED,
                TradeListingReplyRules.INSTANCE.classify("§aYou listed 1x Diamond for $1,000."));
        assertEquals(TradeListingReplyKind.FULL,
                TradeListingReplyRules.INSTANCE.classify("Too many listed items on the Auction House."));
        assertEquals(TradeListingReplyKind.REJECTED,
                TradeListingReplyRules.INSTANCE.classify("You need to wait another 5 seconds."));
        assertEquals(TradeListingReplyKind.UNRELATED,
                TradeListingReplyRules.INSTANCE.classify("Someone listed a Diamond."));
    }

    @Test
    void collectRequiresExactParsedAskAndVisibleOwnerWhenPresent() {
        var owned = new SlotSnapshot(0, "minecraft:diamond_sword", 1, "Diamond Sword",
                List.of("$1,000 each", "Seller: Razzy6"));
        var otherOwner = new SlotSnapshot(0, "minecraft:diamond_sword", 1, "Diamond Sword",
                List.of("$1,000 each", "Seller: AnotherPlayer"));
        var changedAsk = new SlotSnapshot(0, "minecraft:diamond_sword", 1, "Diamond Sword",
                List.of("$1,001 each", "Seller: Razzy6"));
        var sold = new SlotSnapshot(0, "minecraft:diamond_sword", 1, "Diamond Sword",
                List.of("$1,000 each", "Seller: Razzy6", "Sold"));

        assertTrue(OwnedListingRules.INSTANCE.isCollectable(owned, "Razzy6", new BigDecimal("1000")));
        assertFalse(OwnedListingRules.INSTANCE.isCollectable(otherOwner, "Razzy6", new BigDecimal("1000")));
        assertFalse(OwnedListingRules.INSTANCE.isCollectable(changedAsk, "Razzy6", new BigDecimal("1000")));
        assertFalse(OwnedListingRules.INSTANCE.isCollectable(sold, "Razzy6", new BigDecimal("1000")));
    }

    @Test
    void auctionOwnListingDetectionUsesSellerMarkerAndSourceAskTolerance() {
        var explicitOwner = new SlotSnapshot(0, "minecraft:diamond_sword", 1, "Diamond Sword",
                List.of("Seller: Razzy6", "$4,000 each"));
        var closeKnownAsk = new SlotSnapshot(0, "minecraft:diamond_sword", 2, "Diamond Sword",
                List.of("$2,001 total"));
        var exactToleranceBoundary = new SlotSnapshot(0, "minecraft:diamond_sword", 1, "Diamond Sword",
                List.of("$1,001 each"));

        assertTrue(OwnedListingRules.INSTANCE.isOwnAuctionListing(explicitOwner, "Razzy6", new BigDecimal("1000"), true));
        assertTrue(OwnedListingRules.INSTANCE.isOwnAuctionListing(closeKnownAsk, "Razzy6", new BigDecimal("1000"), true));
        assertFalse(OwnedListingRules.INSTANCE.isOwnAuctionListing(exactToleranceBoundary, "Razzy6", new BigDecimal("1000"), true));
        assertFalse(OwnedListingRules.INSTANCE.isOwnAuctionListing(closeKnownAsk, "Razzy6", new BigDecimal("1000"), false));
    }

    @Test
    void collectionCapacityOnlyCountsEmptySlots() {
        assertTrue(OwnedListingRules.INSTANCE.fitsUsingEmptySlots(32, 16, 2));
        assertFalse(OwnedListingRules.INSTANCE.fitsUsingEmptySlots(32, 16, 1));
        assertFalse(OwnedListingRules.INSTANCE.fitsUsingEmptySlots(1, 16, 0));
    }

    @Test
    void collectionCapacityUsesOnlyComponentCompatiblePartialStacks() {
        var listing = new SlotSnapshot(0, "minecraft:diamond", 32, "Diamond", List.of(),
                "components:plain", Map.of(), 16, "procurement:plain");
        var partial = new SlotSnapshot(9, "minecraft:diamond", 8, "Diamond", List.of(),
                "components:plain", Map.of(), 16, "procurement:plain");
        var incompatible = new SlotSnapshot(10, "minecraft:diamond", 15, "Named Diamond", List.of(),
                "components:named", Map.of(), 16, "procurement:named");
        var empty = new SlotSnapshot(11, "", 0, "", List.of(), "", Map.of(), 16, "");

        assertEquals(24L, OwnedListingRules.INSTANCE.compatibleInventoryCapacity(listing, List.of(partial, incompatible, empty)));
        assertFalse(OwnedListingRules.INSTANCE.compatibleInventoryCapacity(listing, List.of(partial, incompatible, empty)) >= listing.getCount());
        assertTrue(OwnedListingRules.INSTANCE.compatibleInventoryCapacity(listing, List.of(partial, incompatible, empty,
                new SlotSnapshot(12, "", 0, "", List.of(), "", Map.of(), 16, ""))) >= listing.getCount());
    }
}
