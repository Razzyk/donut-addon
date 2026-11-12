package dev.donut.addon.expansion.building

import dev.donut.addon.expansion.automation.core.DonutSellIdentity
import dev.donut.addon.expansion.automation.core.InventorySlotValue
import java.util.Collections

/** Pure canonicalization and conservation checks for a sell menu returning items after close. */
internal object TunnelSellReturnRules {
    private val canonicalSlots = (0..35).toSet()

    /** Converts player inventory menu slots 9..44 into player inventory indices 0..35. */
    @JvmStatic
    fun fromPlayerMenuSlots(menuSlots: Map<Int, InventorySlotValue>): Map<Int, InventorySlotValue>? {
        val result = linkedMapOf<Int, InventorySlotValue>()
        for (inventorySlot in 0..35) {
            val menuSlot = playerMenuSlotFor(inventorySlot)
            result[inventorySlot] = menuSlots[menuSlot] ?: return null
        }
        return immutable(result)
    }

    /**
     * Converts an active container's player slots to canonical inventory indices. The mapping
     * records active-menu absolute slot -> player-inventory-menu slot.
     */
    @JvmStatic
    fun fromActiveMenuSlots(
        activeSlots: Map<Int, InventorySlotValue>,
        activeToPlayerMenuSlots: Map<Int, Int>,
    ): Map<Int, InventorySlotValue>? {
        val playerMenuToActive = linkedMapOf<Int, Int>()
        for ((active, playerMenu) in activeToPlayerMenuSlots) {
            if (playerMenu !in 9..44 || playerMenuToActive.put(playerMenu, active) != null) return null
        }
        if (playerMenuToActive.keys != (9..44).toSet()) return null
        val result = linkedMapOf<Int, InventorySlotValue>()
        for (inventorySlot in 0..35) {
            val menuSlot = playerMenuSlotFor(inventorySlot)
            val activeSlot = playerMenuToActive[menuSlot] ?: return null
            result[inventorySlot] = activeSlots[activeSlot] ?: return null
        }
        return immutable(result)
    }

    /** Requires the exact remaining full-stack identities and forbids any loss or substitution. */
    @JvmStatic
    fun returnedExactly(
        before: Map<Int, InventorySlotValue>,
        after: Map<Int, InventorySlotValue>,
        expectedRemaining: Map<String, Int>,
    ): Boolean {
        if (before.keys != canonicalSlots || after.keys != canonicalSlots) return false
        val beforeCounts = totals(before)
        val afterCounts = totals(after)
        val returned = linkedMapOf<String, Int>()
        for (identity in beforeCounts.keys + afterCounts.keys) {
            val difference = (afterCounts[identity] ?: 0) - (beforeCounts[identity] ?: 0)
            if (difference < 0) return false
            if (difference > 0) returned[identity] = difference
        }
        return returned == expectedRemaining
    }

    /** Full local/server equality for all 36 player inventory positions. */
    @JvmStatic
    fun reconciled(server: Map<Int, InventorySlotValue>, local: Map<Int, InventorySlotValue>): Boolean =
        server.keys == canonicalSlots && local.keys == canonicalSlots && server == local

    private fun playerMenuSlotFor(inventorySlot: Int): Int =
        if (inventorySlot in 0..8) inventorySlot + 36 else inventorySlot

    private fun totals(stacks: Map<Int, InventorySlotValue>): Map<String, Int> = buildMap {
        stacks.values.filter { it.count > 0 }.forEach { stack ->
            val identity = DonutSellIdentity.of(stack)
            put(identity, (get(identity) ?: 0) + stack.count)
        }
    }

    private fun immutable(stacks: Map<Int, InventorySlotValue>): Map<Int, InventorySlotValue> =
        Collections.unmodifiableMap(stacks.toSortedMap())
}
