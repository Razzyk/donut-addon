package dev.donut.addon.expansion.automation;

import static org.junit.jupiter.api.Assertions.*;

import dev.donut.addon.expansion.automation.core.InventoryAckStatus;
import dev.donut.addon.expansion.automation.core.InventoryAckTracker;
import dev.donut.addon.expansion.automation.core.InventorySlotValue;
import dev.donut.addon.expansion.automation.core.InventorySwapExpectation;
import dev.donut.addon.expansion.automation.core.InventorySyncFrame;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InventoryAckTrackerTest {
    @Test
    void delayedServerSlotUpdatesMustAcknowledgeBothSidesOfTheSwap() {
        var tracker = new InventoryAckTracker();
        var empty = new InventorySlotValue("", 0);
        var diamond = new InventorySlotValue("minecraft:diamond", 4, "Diamond");
        tracker.begin(new InventorySwapExpectation(7, 0, 12, 9, 36, empty, diamond));

        assertEquals(InventoryAckStatus.PENDING, tracker.observe(
            new InventorySyncFrame(8, 0, 13, Map.of(9, empty), null, false)));
        assertEquals(InventoryAckStatus.PENDING, tracker.observe(
            new InventorySyncFrame(7, 2, 13, Map.of(9, empty), null, false)));
        assertEquals(InventoryAckStatus.PENDING, tracker.observe(
            new InventorySyncFrame(7, 0, 13, Map.of(9, diamond), null, false)));
        assertEquals(InventoryAckStatus.ACKNOWLEDGED, tracker.observe(
            new InventorySyncFrame(7, 0, 13, Map.of(36, empty), null, false)));
    }

    @Test
    void rejectedOrCursorOccupiedServerStateNeverAcknowledgesStock() {
        var tracker = new InventoryAckTracker();
        var empty = new InventorySlotValue("", 0);
        var diamond = new InventorySlotValue("minecraft:diamond", 4, "Diamond");
        var unrelated = new InventorySlotValue("minecraft:stone", 64, "Stone");
        tracker.begin(new InventorySwapExpectation(3, 0, 2, 9, 36, empty, diamond));
        assertEquals(InventoryAckStatus.REJECTED, tracker.observe(new InventorySyncFrame(
            3, 0, 3, Map.of(9, unrelated, 36, empty), null, true)));

        tracker.begin(new InventorySwapExpectation(3, 0, 3, 9, 36, empty, diamond));
        assertEquals(InventoryAckStatus.REJECTED, tracker.observe(new InventorySyncFrame(
            3, 0, 4, Map.of(9, diamond, 36, empty), new InventorySlotValue("minecraft:diamond", 1, "Diamond"), true)));
    }

    @Test
    void missingRelevantUpdatesCanBeAbandonedWithoutRetryingTheClick() {
        var tracker = new InventoryAckTracker();
        var empty = new InventorySlotValue("", 0);
        var diamond = new InventorySlotValue("minecraft:diamond", 1, "Diamond");
        tracker.begin(new InventorySwapExpectation(1, 0, 9, 9, 36, empty, diamond));
        tracker.observe(new InventorySyncFrame(1, 0, 10, Map.of(36, diamond), null, false));
        assertEquals(InventoryAckStatus.ABANDONED, tracker.abandon());
        assertEquals(InventoryAckStatus.ABANDONED, tracker.observe(new InventorySyncFrame(
            1, 0, 11, Map.of(9, empty), null, false)));
    }

    @Test
    void manualInventoryChangeAfterAcknowledgementInvalidatesTheStagedPair() {
        var tracker = new InventoryAckTracker();
        var empty = new InventorySlotValue("", 0);
        var diamond = new InventorySlotValue("minecraft:diamond", 4, "Diamond");
        var stone = new InventorySlotValue("minecraft:stone", 8, "Stone");
        tracker.begin(new InventorySwapExpectation(7, 0, 12, 9, 36, empty, diamond));
        assertEquals(InventoryAckStatus.ACKNOWLEDGED, tracker.observe(new InventorySyncFrame(
            7, 0, 13, Map.of(9, diamond, 36, empty), null, true)));

        assertEquals(InventoryAckStatus.REJECTED, tracker.reconcileLocal(stone, empty, true));
        assertEquals(InventoryAckStatus.REJECTED, tracker.observe(new InventorySyncFrame(
            7, 0, 14, Map.of(9, diamond, 36, empty), null, true)));
    }

    @Test
    void stateIdWrapAndOutOfOrderMismatchedSlotUpdatesRemainPendingUntilReconciled() {
        var tracker = new InventoryAckTracker();
        var empty = new InventorySlotValue("", 0);
        var diamond = new InventorySlotValue("minecraft:diamond", 4, "Diamond");
        var stone = new InventorySlotValue("minecraft:stone", 8, "Stone");
        tracker.begin(new InventorySwapExpectation(9, 0, 32767, 9, 36, empty, diamond));

        assertEquals(InventoryAckStatus.PENDING, tracker.observe(new InventorySyncFrame(
            9, 0, 0, Map.of(9, stone), null, false)));
        assertEquals(InventoryAckStatus.PENDING, tracker.observe(new InventorySyncFrame(
            9, 0, 32767, Map.of(36, diamond), null, false)));
        assertEquals(InventoryAckStatus.PENDING, tracker.observe(new InventorySyncFrame(
            9, 0, 1, Map.of(36, empty), null, false)));
        assertEquals(InventoryAckStatus.ACKNOWLEDGED, tracker.observe(new InventorySyncFrame(
            9, 0, 2, Map.of(9, diamond), null, false)));
    }

    @Test
    void absoluteDestinationSlotSupportsThePlayerOffhandSlot() {
        var tracker = new InventoryAckTracker();
        var empty = new InventorySlotValue("", 0);
        var shield = new InventorySlotValue("minecraft:shield", 1, "Shield");
        tracker.begin(new InventorySwapExpectation(6, 0, 20, 12, 45, empty, shield));
        assertEquals(InventoryAckStatus.ACKNOWLEDGED, tracker.observe(new InventorySyncFrame(
            6, 0, 21, Map.of(12, shield, 45, empty), null, true)));
    }
}
