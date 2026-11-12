package dev.donut.addon.expansion.automation;

import static org.junit.jupiter.api.Assertions.*;

import dev.donut.addon.expansion.automation.core.InventoryStagePlan;
import dev.donut.addon.expansion.automation.core.InventoryStaging;
import dev.donut.addon.expansion.automation.core.InventorySlotValue;
import dev.donut.addon.expansion.automation.core.MenuSnapshot;
import dev.donut.addon.expansion.automation.core.SlotSnapshot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InventoryStagingPlanTest {
    @Test
    void plansOnlyTheNeededCountFromACompatiblePartialSource() {
        var slots = emptySlots();
        slots.set(9, item(9, "minecraft:diamond", 64, "plain"));
        var plan = InventoryStaging.INSTANCE.plan(menu(slots), "minecraft:diamond", 16);

        assertInstanceOf(InventoryStagePlan.Transfer.class, plan);
        var transfer = (InventoryStagePlan.Transfer) plan;
        assertEquals(9, transfer.getSourceMenuSlot());
        assertEquals(36, transfer.getTargetMenuSlot());
        assertEquals(16, transfer.getTransferCount());
        assertEquals(64, transfer.getSourceStack().getCount());
    }

    @Test
    void refusesInsufficientTotalOrIncompatibleComponentFamilies() {
        var insufficient = emptySlots();
        insufficient.set(9, item(9, "minecraft:diamond", 8, "plain"));
        assertSame(InventoryStagePlan.Missing.INSTANCE,
            InventoryStaging.INSTANCE.plan(menu(insufficient), "diamond", 16));

        var incompatible = emptySlots();
        incompatible.set(9, item(9, "minecraft:diamond", 8, "named-a"));
        incompatible.set(10, item(10, "minecraft:diamond", 8, "named-b"));
        assertSame(InventoryStagePlan.Missing.INSTANCE,
            InventoryStaging.INSTANCE.plan(menu(incompatible), "diamond", 16));
    }

    @Test
    void refusesPartialTransferWhenThereIsNoCompatibleHotbarOrEmptySlot() {
        var slots = emptySlots();
        slots.set(9, item(9, "minecraft:diamond", 32, "plain"));
        for (int slot = 36; slot < 45; slot++) slots.set(slot, item(slot, "minecraft:stone", 64, "stone"));
        assertSame(InventoryStagePlan.Missing.INSTANCE,
            InventoryStaging.INSTANCE.plan(menu(slots), "diamond", 16));
    }

    @Test
    void combinesACompatiblePartialHotbarStackWithoutTouchingAnotherItemVariant() {
        var slots = emptySlots();
        slots.set(9, item(9, "minecraft:diamond", 8, "plain"));
        slots.set(36, item(36, "minecraft:diamond", 8, "plain"));
        slots.set(37, item(37, "minecraft:diamond", 8, "named"));
        var plan = InventoryStaging.INSTANCE.plan(menu(slots), "diamond", 16);
        assertInstanceOf(InventoryStagePlan.Transfer.class, plan);
        var transfer = (InventoryStagePlan.Transfer) plan;
        assertEquals(36, transfer.getTargetMenuSlot());
        assertEquals(9, transfer.getSourceMenuSlot());
        assertEquals(8, transfer.getTransferCount());
    }

    @Test
    void exactStackSelectionUsesStoredEnchantmentsNotOnlyDisplayLore() {
        var slots = emptySlots();
        slots.set(9, new SlotSnapshot(9, "minecraft:diamond_sword", 16, "Diamond Sword", List.of(), "plain", java.util.Map.of()));
        slots.set(10, new SlotSnapshot(10, "minecraft:diamond_sword", 16, "Diamond Sword", List.of(), "enchanted",
            java.util.Map.of("sharpness", 5)));
        var plan = InventoryStaging.INSTANCE.plan(menu(slots), "minecraft:diamond_sword", 16,
            java.util.Map.of("sharpness", 5));
        assertInstanceOf(InventoryStagePlan.Swap.class, plan);
        assertEquals(10, ((InventoryStagePlan.Swap) plan).getSourceMenuSlot());
    }

    @Test
    void refusesToMergeStacksPastTheItemsEffectiveStackLimit() {
        var slots = emptySlots();
        slots.set(9, new SlotSnapshot(9, "minecraft:iron_sword", 1, "Iron Sword", List.of(), "plain",
            java.util.Map.of(), 1));
        slots.set(10, new SlotSnapshot(10, "minecraft:iron_sword", 1, "Iron Sword", List.of(), "plain",
            java.util.Map.of(), 1));
        assertSame(InventoryStagePlan.Missing.INSTANCE,
            InventoryStaging.INSTANCE.plan(menu(slots), "iron_sword", 2));
    }

    private static MenuSnapshot menu(List<SlotSnapshot> slots) {
        return new MenuSnapshot(1L, 0, "Player Inventory", slots, true, 6,
            new InventorySlotValue("", 0));
    }

    private static ArrayList<SlotSnapshot> emptySlots() {
        var slots = new ArrayList<SlotSnapshot>();
        for (int slot = 0; slot < 46; slot++) slots.add(item(slot, "", 0, ""));
        return slots;
    }

    private static SlotSnapshot item(int index, String itemId, int count, String components) {
        String name = itemId.isEmpty() ? "" : itemId.substring(itemId.indexOf(':') + 1);
        return new SlotSnapshot(index, itemId, count, name, List.of(), components);
    }
}
