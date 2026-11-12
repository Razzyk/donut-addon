package dev.donut.addon.expansion.schematic;

import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SchematicNativeCrafterTest {
    @BeforeAll static void prepareNativeData() throws Exception { SchematicNativeContextTest.prepareNativeData(); }

    @Test void plannedEmptySlotTogglesUseTheNativeEnabledArgumentDirection() {
        var player = new PlacementFixturePlayer(new PlacementFixtureLevel());
        var menu = new CrafterMenu(7, player.getInventory());
        menu.getSlot(0).set(new ItemStack(Items.DIAMOND));
        menu.setSlotState(2, false);
        assertTrue(menu.isSlotDisabled(2));
        var expected = new ArrayList<>(List.of(true,true,false,false,false,false,false,false,false));
        var before = new ArrayList<Boolean>();
        for(int slot=0;slot<9;slot++) before.add(menu.isSlotDisabled(slot));
        var changes = SchematicCrafterRules.INSTANCE.toggles(expected,before,Set.of(0));
        assertEquals(2, changes.size());
        for(var change : changes) menu.setSlotState(change.getSlot(),change.getEnabled());
        assertFalse(menu.isSlotDisabled(0),"Occupied slot must not be disabled by the planner");
        assertTrue(menu.isSlotDisabled(1));
        assertFalse(menu.isSlotDisabled(2));
        assertEquals(1,menu.getSlot(0).getItem().getCount());
    }

    @Test void nativeServerContainerDataUsesDisabledBitsAndKeepsPoweredSeparate() {
        var player = new PlacementFixturePlayer(new PlacementFixtureLevel());
        var menu = new CrafterMenu(9, player.getInventory());
        for(int slot=0;slot<9;slot++) {
            menu.setData(slot,1);
            assertTrue(menu.isSlotDisabled(slot));
            assertTrue(SchematicCrafterRules.INSTANCE.dataValueMatches(true,1));
            menu.setData(slot,0);
            assertFalse(menu.isSlotDisabled(slot));
            assertTrue(SchematicCrafterRules.INSTANCE.dataValueMatches(false,0));
        }
        menu.setData(9,1);
        assertTrue(menu.isPowered());
        assertFalse(SchematicCrafterRules.INSTANCE.allDataAcknowledged(Set.of(9),Set.of(9)));
        for(int slot=0;slot<9;slot++) assertFalse(menu.isSlotDisabled(slot));
    }
}
