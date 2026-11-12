package dev.donut.addon.expansion.automation;

import static org.junit.jupiter.api.Assertions.*;

import dev.donut.addon.expansion.automation.core.InventoryAckStatus;
import dev.donut.addon.expansion.automation.core.InventoryMutationAckTracker;
import dev.donut.addon.expansion.automation.core.InventoryMutationExpectation;
import dev.donut.addon.expansion.automation.core.InventorySlotValue;
import dev.donut.addon.expansion.automation.core.InventorySyncFrame;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InventoryMutationAckTrackerTest {
    @Test
    void cursorPickupRequiresRelevantSlotAndCarriedStackUpdates() {
        var tracker = new InventoryMutationAckTracker();
        var empty = new InventorySlotValue("", 0);
        var diamond = new InventorySlotValue("minecraft:diamond", 12, "Diamond");
        tracker.begin(new InventoryMutationExpectation(4, 0, 17, Map.of(9, empty), diamond));

        assertEquals(InventoryAckStatus.PENDING, tracker.observe(new InventorySyncFrame(
            4, 0, 18, Map.of(10, empty), null, false)));
        assertEquals(InventoryAckStatus.PENDING, tracker.observe(new InventorySyncFrame(
            4, 0, 18, Map.of(9, empty), null, false)));
        assertEquals(InventoryAckStatus.ACKNOWLEDGED, tracker.observe(new InventorySyncFrame(
            4, 0, 18, Map.of(), diamond, false)));
    }

    @Test
    void standaloneCursorPacketUsesSpecialMenuIdentityAndCanArriveBeforeSlotUpdate() {
        var tracker = new InventoryMutationAckTracker();
        var empty = new InventorySlotValue("", 0);
        var diamond = new InventorySlotValue("minecraft:diamond", 12, "Diamond");
        tracker.begin(new InventoryMutationExpectation(4, 0, 17, Map.of(9, empty), diamond));

        assertEquals(InventoryAckStatus.PENDING, tracker.observe(new InventorySyncFrame(
            4, -1, 18, Map.of(), diamond, false)));
        assertEquals(InventoryAckStatus.PENDING, tracker.observe(new InventorySyncFrame(
            4, 2, 19, Map.of(9, empty), null, false)));
        assertEquals(InventoryAckStatus.ACKNOWLEDGED, tracker.observe(new InventorySyncFrame(
            4, 0, 19, Map.of(9, empty), null, false)));
    }

    @Test
    void mismatchedOutOfOrderPartialUpdatesWaitForReconciliation() {
        var tracker = new InventoryMutationAckTracker();
        var oldTarget = new InventorySlotValue("minecraft:diamond", 8, "Diamond");
        var target = new InventorySlotValue("minecraft:diamond", 9, "Diamond");
        var expectedCursor = new InventorySlotValue("minecraft:diamond", 3, "Diamond");
        var wrongCursor = new InventorySlotValue("minecraft:diamond", 4, "Diamond");
        tracker.begin(new InventoryMutationExpectation(9, 0, 31,
            Map.of(36, target), expectedCursor));

        assertEquals(InventoryAckStatus.PENDING, tracker.observe(new InventorySyncFrame(
            9, 0, 32, Map.of(36, oldTarget), null, false)));
        assertEquals(InventoryAckStatus.PENDING, tracker.observe(new InventorySyncFrame(
            9, 0, 33, Map.of(), wrongCursor, false)));
        assertEquals(InventoryAckStatus.PENDING, tracker.observe(new InventorySyncFrame(
            9, 0, 34, Map.of(36, target), null, false)));
        assertEquals(InventoryAckStatus.ACKNOWLEDGED, tracker.observe(new InventorySyncFrame(
            9, 0, 35, Map.of(), expectedCursor, false)));
    }

    @Test
    void fullRejectedStateAndLaterManualInterferenceCannotBeUsedAsAStockAck() {
        var tracker = new InventoryMutationAckTracker();
        var empty = new InventorySlotValue("", 0);
        var diamond = new InventorySlotValue("minecraft:diamond", 12, "Diamond");
        var stone = new InventorySlotValue("minecraft:stone", 4, "Stone");
        tracker.begin(new InventoryMutationExpectation(2, 0, 7, Map.of(9, empty), diamond));
        assertEquals(InventoryAckStatus.REJECTED, tracker.observe(new InventorySyncFrame(
            2, 0, 8, Map.of(9, empty), stone, true)));

        tracker.begin(new InventoryMutationExpectation(2, 0, 8, Map.of(9, empty), diamond));
        assertEquals(InventoryAckStatus.ACKNOWLEDGED, tracker.observe(new InventorySyncFrame(
            2, 0, 9, Map.of(9, empty), diamond, true)));
        assertEquals(InventoryAckStatus.REJECTED, tracker.reconcileLocal(Map.of(9, empty), stone));
    }
}
