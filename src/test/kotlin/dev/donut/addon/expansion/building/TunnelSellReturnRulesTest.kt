package dev.donut.addon.expansion.building

import dev.donut.addon.expansion.automation.core.DonutSellIdentity
import dev.donut.addon.expansion.automation.core.InventorySlotValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TunnelSellReturnRulesTest {
    @Test
    fun `active menu slots map hotbar and main inventory to canonical indices`() {
        val mapping = (0..35).associate { inventoryIndex ->
            val playerMenuSlot = if (inventoryIndex < 9) inventoryIndex + 36 else inventoryIndex
            (100 + inventoryIndex) to playerMenuSlot
        }
        val active = (0..35).associate { index -> (100 + index) to stack("item-$index", index + 1) }

        val canonical = TunnelSellReturnRules.fromActiveMenuSlots(active, mapping)

        assertNotNull(canonical)
        assertEquals((0..35).toSet(), canonical!!.keys)
        for (index in 0..35) assertEquals(stack("item-$index", index + 1), canonical[index])
    }

    @Test
    fun `active menu mapping rejects missing duplicate and foreign slots`() {
        val valid = (0..35).associate { index ->
            val playerMenuSlot = if (index < 9) index + 36 else index
            (100 + index) to playerMenuSlot
        }
        val values = (0..35).associate { (100 + it) to stack("item-$it", 1) }

        assertNull(TunnelSellReturnRules.fromActiveMenuSlots(values, valid - 100))
        assertNull(TunnelSellReturnRules.fromActiveMenuSlots(values, valid + (200 to 9)))
        assertNull(TunnelSellReturnRules.fromActiveMenuSlots(values - 100, valid))
    }

    @Test
    fun `player menu snapshot canonicalizes all hotbar and main inventory slots`() {
        val menuSlots = (9..44).associateWith { slot -> stack("menu-$slot", slot) }
        val canonical = TunnelSellReturnRules.fromPlayerMenuSlots(menuSlots)

        assertNotNull(canonical)
        assertEquals(stack("menu-36", 36), canonical!![0])
        assertEquals(stack("menu-44", 44), canonical[8])
        assertEquals(stack("menu-9", 9), canonical[9])
        assertEquals(stack("menu-35", 35), canonical[35])
        assertNull(TunnelSellReturnRules.fromPlayerMenuSlots(menuSlots - 36))
    }

    @Test
    fun `partial return requires exactly the remaining approved full stack identities`() {
        val before = (0..35).associateWith { InventorySlotValue("", 0) }
        val after = before.toMutableMap().also {
            it[3] = stack("minecraft:cobblestone", 12, "component-a")
            it[4] = stack("minecraft:coal", 8, "component-b")
        }
        val remaining = mapOf(
            DonutSellIdentity.of(stack("minecraft:cobblestone", 1, "component-a")) to 12,
            DonutSellIdentity.of(stack("minecraft:coal", 1, "component-b")) to 8,
        )

        assertTrue(TunnelSellReturnRules.returnedExactly(before, after, remaining))
        assertTrue(TunnelSellReturnRules.reconciled(after, after.toMap()))
        assertFalse(TunnelSellReturnRules.reconciled(after, after + (5 to stack("minecraft:coal", 8, "component-x"))))
        assertFalse(TunnelSellReturnRules.returnedExactly(before, after, remaining + ("unapproved" to 1)))
    }

    @Test
    fun `partial return rejects lost quantity and component substitution`() {
        val identity = DonutSellIdentity.of(stack("minecraft:coal", 1, "full-components"))
        val before = (0..35).associateWith { InventorySlotValue("", 0) }
        val expectedAfter = before.toMutableMap().also { it[7] = stack("minecraft:coal", 16, "full-components") }
        val lost = expectedAfter.toMutableMap().also { it[7] = stack("minecraft:coal", 15, "full-components") }
        val substituted = expectedAfter.toMutableMap().also { it[7] = stack("minecraft:coal", 16, "changed-components") }

        assertTrue(TunnelSellReturnRules.returnedExactly(before, expectedAfter, mapOf(identity to 16)))
        assertFalse(TunnelSellReturnRules.returnedExactly(before, lost, mapOf(identity to 16)))
        assertFalse(TunnelSellReturnRules.returnedExactly(before, substituted, mapOf(identity to 16)))
    }

    private fun stack(itemId: String, count: Int, components: String = "default") =
        InventorySlotValue(itemId, count, componentFingerprint = components)
}
