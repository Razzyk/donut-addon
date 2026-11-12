package dev.donut.addon.expansion.automation.core

import java.util.Locale

enum class DonutOrderDetailState { STILL_FILLING, COLLECT, NO_CONTROL }

data class DonutOrderCollectControl(val state: DonutOrderDetailState, val slotIndex: Int? = null)

/** Source-style order detail control scan, bounded to the active container section. */
object DonutOrderCollectionRules {
    private const val MAX_CONTAINER_SLOTS = 128

    @JvmStatic
    fun collectionControl(menu: MenuSnapshot, containerSlotCount: Int): DonutOrderCollectControl {
        if (containerSlotCount <= 0 || containerSlotCount > MAX_CONTAINER_SLOTS) {
            return DonutOrderCollectControl(DonutOrderDetailState.NO_CONTROL)
        }
        val slots = (0 until containerSlotCount).mapNotNull(menu::slot)
        if (slots.any { slot ->
                slot.itemId.equals("minecraft:red_terracotta", ignoreCase = true) ||
                    slot.itemId.equals("red_terracotta", ignoreCase = true) ||
                    normalizeName(slot.name).contains("cancel")
            }) return DonutOrderCollectControl(DonutOrderDetailState.STILL_FILLING)

        val collect = slots.firstOrNull { normalizeName(it.name).contains("collect") }
        if (collect != null) return DonutOrderCollectControl(DonutOrderDetailState.COLLECT, collect.index)

        val fallback = if (containerSlotCount > 13) menu.slot(13)?.takeIf { it.itemId.isNotBlank() } else null
        return if (fallback != null) DonutOrderCollectControl(DonutOrderDetailState.COLLECT, 13)
        else DonutOrderCollectControl(DonutOrderDetailState.NO_CONTROL)
    }

    private fun normalizeName(value: String): String =
        TextNormalization.stripFormatting(value).lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
}
