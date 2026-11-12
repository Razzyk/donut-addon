package dev.donut.addon.expansion.automation;

import static org.junit.jupiter.api.Assertions.*;

import dev.donut.addon.expansion.automation.core.InventoryAckStatus;
import dev.donut.addon.expansion.automation.core.InventoryPickupAction;
import dev.donut.addon.expansion.automation.core.InventorySlotValue;
import dev.donut.addon.expansion.automation.core.InventoryStagePlan;
import dev.donut.addon.expansion.automation.core.InventoryTransferMachine;
import dev.donut.addon.expansion.automation.core.InventoryTransferProgress;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InventoryTransferMachineTest {
    @Test
    void wholePartialStackMergesWithOneAcknowledgedCursorTransaction() {
        var machine = new InventoryTransferMachine();
        var source = new InventorySlotValue("minecraft:diamond", 12, "Diamond");
        var target = new InventorySlotValue("minecraft:diamond", 50, "Diamond");
        assertTrue(machine.begin(new InventoryStagePlan.Transfer(
            9, 36, source.getFingerprint(), target.getFingerprint(), source, target, 12, 64)));

        var take = issue(machine);
        assertEquals(9, take.getMenuSlot());
        assertEquals(0, take.getButton());
        assertEquals(source, take.getExpectedBeforeSlots().get(9));
        assertEquals(new InventorySlotValue("", 0), take.getExpectedBeforeCursor());
        assertEquals(new InventorySlotValue("", 0), take.getExpectedLocalSlots().get(9));
        assertEquals(source, take.getExpectedCursor());
        assertEquals(InventoryTransferProgress.WAITING, machine.acknowledge(
            InventoryAckStatus.ACKNOWLEDGED,
            Map.of(9, new InventorySlotValue("", 0), 36, target), source));

        var merge = issue(machine);
        assertEquals(36, merge.getMenuSlot());
        assertEquals(0, merge.getButton());
        assertEquals(62, merge.getExpectedLocalSlots().get(36).getCount());
        assertEquals(InventoryTransferProgress.READY, machine.acknowledge(
            InventoryAckStatus.ACKNOWLEDGED, merge.getExpectedLocalSlots(), merge.getExpectedCursor()));
        assertNull(machine.nextAction());
    }

    @Test
    void largerSourceSplitsOnlyTheNeededItemsThenReturnsRemainder() {
        var machine = new InventoryTransferMachine();
        var source = new InventorySlotValue("minecraft:diamond", 64, "Diamond");
        var empty = new InventorySlotValue("", 0);
        assertTrue(machine.begin(new InventoryStagePlan.Transfer(
            12, 38, source.getFingerprint(), empty.getFingerprint(), source, empty, 16, 16)));

        var take = issue(machine);
        assertEquals(InventoryTransferProgress.WAITING, machine.acknowledge(
            InventoryAckStatus.ACKNOWLEDGED, take.getExpectedLocalSlots(), take.getExpectedCursor()));
        for (int item = 1; item <= 16; item++) {
            var addOne = issue(machine);
            assertEquals(1, addOne.getButton());
            assertEquals(item, addOne.getExpectedLocalSlots().get(38).getCount());
            assertEquals(64 - item, addOne.getExpectedCursor().getCount());
        var expectedTargetBefore = item == 1 ? empty : new InventorySlotValue("minecraft:diamond", item - 1, "Diamond");
        assertEquals(expectedTargetBefore, addOne.getExpectedBeforeSlots().get(38));
        assertEquals(65 - item, addOne.getExpectedBeforeCursor().getCount());
            assertEquals(InventoryTransferProgress.WAITING, machine.acknowledge(
                InventoryAckStatus.ACKNOWLEDGED, addOne.getExpectedLocalSlots(), addOne.getExpectedCursor()));
        }

        var returnRemainder = issue(machine);
        assertEquals(12, returnRemainder.getMenuSlot());
        assertEquals(48, returnRemainder.getExpectedLocalSlots().get(12).getCount());
        assertEquals(new InventorySlotValue("", 0), returnRemainder.getExpectedCursor());
        assertEquals(InventoryTransferProgress.READY, machine.acknowledge(
            InventoryAckStatus.ACKNOWLEDGED, returnRemainder.getExpectedLocalSlots(), returnRemainder.getExpectedCursor()));
    }

    @Test
    void rejectOrManualChangeStopsBeforeNextPickup() {
        var machine = new InventoryTransferMachine();
        var source = new InventorySlotValue("minecraft:diamond", 4, "Diamond");
        var empty = new InventorySlotValue("", 0);
        assertTrue(machine.begin(new InventoryStagePlan.Transfer(
            9, 36, source.getFingerprint(), empty.getFingerprint(), source, empty, 1, 1)));
        var take = issue(machine);
        assertEquals(InventoryTransferProgress.FAILED, machine.acknowledge(
            InventoryAckStatus.REJECTED, take.getExpectedLocalSlots(), take.getExpectedCursor()));
        assertNull(machine.nextAction());

        assertTrue(machine.begin(new InventoryStagePlan.Transfer(
            9, 36, source.getFingerprint(), empty.getFingerprint(), source, empty, 1, 1)));
        take = issue(machine);
        assertEquals(InventoryTransferProgress.FAILED, machine.acknowledge(
            InventoryAckStatus.ACKNOWLEDGED,
            Map.of(9, empty, 36, new InventorySlotValue("minecraft:stone", 1, "Stone")), source));
        assertNull(machine.nextAction());
    }

    @Test
    void acknowledgedCursorCanReturnOnlyToItsOwnedUnchangedSourceSlot() {
        var machine = new InventoryTransferMachine();
        var source = new InventorySlotValue("minecraft:diamond", 64, "Diamond");
        var empty = new InventorySlotValue("", 0);
        assertTrue(machine.begin(new InventoryStagePlan.Transfer(
            12, 38, source.getFingerprint(), empty.getFingerprint(), source, empty, 16, 16)));

        var take = issue(machine);
        assertEquals(InventoryTransferProgress.WAITING, machine.acknowledge(
            InventoryAckStatus.ACKNOWLEDGED, take.getExpectedLocalSlots(), take.getExpectedCursor()));
        var addOne = issue(machine);
        assertEquals(InventoryTransferProgress.WAITING, machine.acknowledge(
            InventoryAckStatus.ACKNOWLEDGED, addOne.getExpectedLocalSlots(), addOne.getExpectedCursor()));

        var safeReturn = machine.safeCursorReturnAction(addOne.getExpectedLocalSlots(), addOne.getExpectedCursor());
        assertNotNull(safeReturn);
        assertEquals(12, safeReturn.getMenuSlot());
        assertEquals(63, safeReturn.getExpectedLocalSlots().get(12).getCount());
        assertEquals(1, safeReturn.getExpectedLocalSlots().get(38).getCount());
        assertEquals(new InventorySlotValue("", 0), safeReturn.getExpectedCursor());

        var manualChange = new java.util.HashMap<>(addOne.getExpectedLocalSlots());
        manualChange.put(38, new InventorySlotValue("minecraft:stone", 1, "Stone"));
        assertNull(machine.safeCursorReturnAction(manualChange, addOne.getExpectedCursor()));
    }

    @Test
    void uncertainInFlightClickIsNeverRetriedAsCursorRecovery() {
        var machine = new InventoryTransferMachine();
        var source = new InventorySlotValue("minecraft:diamond", 4, "Diamond");
        var empty = new InventorySlotValue("", 0);
        assertTrue(machine.begin(new InventoryStagePlan.Transfer(
            9, 36, source.getFingerprint(), empty.getFingerprint(), source, empty, 1, 1)));
        var take = issue(machine);
        assertEquals(InventoryTransferProgress.WAITING, machine.acknowledge(
            InventoryAckStatus.ACKNOWLEDGED, take.getExpectedLocalSlots(), take.getExpectedCursor()));
        var move = issue(machine);
        machine.abandon();
        assertNull(machine.safeCursorReturnAction(take.getExpectedLocalSlots(), take.getExpectedCursor()));
        assertNotNull(move);
    }

    @Test
    void issuedRecoveryCannotSendASecondCursorPickup() {
        var machine = new InventoryTransferMachine();
        var source = new InventorySlotValue("minecraft:diamond", 4, "Diamond");
        var empty = new InventorySlotValue("", 0);
        assertTrue(machine.begin(new InventoryStagePlan.Transfer(
            9, 36, source.getFingerprint(), empty.getFingerprint(), source, empty, 1, 1)));
        var take = issue(machine);
        assertEquals(InventoryTransferProgress.WAITING, machine.acknowledge(
            InventoryAckStatus.ACKNOWLEDGED, take.getExpectedLocalSlots(), take.getExpectedCursor()));
        assertNotNull(machine.safeCursorReturnAction(take.getExpectedLocalSlots(), take.getExpectedCursor()));

        machine.recoveryIssued();
        assertNull(machine.safeCursorReturnAction(take.getExpectedLocalSlots(), take.getExpectedCursor()));
    }

    private InventoryPickupAction issue(InventoryTransferMachine machine) {
        var action = machine.nextAction();
        assertNotNull(action);
        assertTrue(machine.actionIssued(action));
        return action;
    }
}
