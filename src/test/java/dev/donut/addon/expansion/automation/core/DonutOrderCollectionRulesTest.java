package dev.donut.addon.expansion.automation.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DonutOrderCollectionRulesTest {
    @Test
    void redTerracottaMarksOrderStillFillingBeforeCollectControl() {
        var menu = menu(20, slot(2, "minecraft:lime_dye", 1, "Collect"),
                slot(4, "minecraft:red_terracotta", 1, "Order is still filling"));
        var result = DonutOrderCollectionRules.collectionControl(menu, 20);
        assertEquals(DonutOrderDetailState.STILL_FILLING, result.getState());
        assertNull(result.getSlotIndex());
    }

    @Test
    void cancelNameMarksOrderStillFillingRegardlessOfOtherButtons() {
        var menu = menu(20, slot(1, "minecraft:lime_dye", 1, "Collect"),
                slot(8, "minecraft:barrier", 1, "Cancel Order"));
        assertEquals(DonutOrderDetailState.STILL_FILLING,
                DonutOrderCollectionRules.collectionControl(menu, 20).getState());
    }

    @Test
    void firstNamedCollectControlWinsBeforeTheSlotThirteenFallback() {
        var menu = menu(20, slot(13, "minecraft:paper", 1, "Next"),
                slot(5, "minecraft:lime_dye", 1, "Collect Order"));
        var result = DonutOrderCollectionRules.collectionControl(menu, 20);
        assertEquals(DonutOrderDetailState.COLLECT, result.getState());
        assertEquals(5, result.getSlotIndex());
    }

    @Test
    void usesNonemptySlotThirteenWhenTheCollectNameIsMissing() {
        var menu = menu(20, slot(13, "minecraft:paper", 1, "Order Item"));
        var result = DonutOrderCollectionRules.collectionControl(menu, 20);
        assertEquals(DonutOrderDetailState.COLLECT, result.getState());
        assertEquals(13, result.getSlotIndex());
    }

    @Test
    void refusesEmptyMissingOrOutOfContainerFallbackSlots() {
        var emptyThirteen = menu(20, slot(13, "", 0, ""));
        var missingThirteen = menu(13, slot(13, "minecraft:paper", 1, "Order Item"));
        assertEquals(DonutOrderDetailState.NO_CONTROL,
                DonutOrderCollectionRules.collectionControl(emptyThirteen, 20).getState());
        assertEquals(DonutOrderDetailState.NO_CONTROL,
                DonutOrderCollectionRules.collectionControl(missingThirteen, 13).getState());
    }

    @Test
    void ignoresCollectLabelsOutsideTheActiveContainerSlotRange() {
        var menu = menu(18, slot(16, "minecraft:lime_dye", 1, "Collect"));
        assertEquals(DonutOrderDetailState.NO_CONTROL,
                DonutOrderCollectionRules.collectionControl(menu, 16).getState());
    }

    private static MenuSnapshot menu(int containerSlots, SlotSnapshot... supplied) {
        var byIndex = new java.util.HashMap<Integer, SlotSnapshot>();
        for (var slot : supplied) byIndex.put(slot.getIndex(), slot);
        var slots = new ArrayList<SlotSnapshot>();
        for (int index = 0; index < containerSlots; index++) {
            slots.add(byIndex.getOrDefault(index, slot(index, "", 0, "")));
        }
        return new MenuSnapshot(100L, 4, "Order Details", List.copyOf(slots), true, 5);
    }

    private static SlotSnapshot slot(int index, String itemId, int count, String name) {
        return new SlotSnapshot(index, itemId, count, name, List.of());
    }
}
