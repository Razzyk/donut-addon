package dev.donut.addon.expansion.schematic

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items

/** Resolves ordinary registry-backed item ids without confusing Registry#get with a raw Item value. */
internal object SchematicItemResolver {
    fun resolve(id: String?): Item? {
        if (id == null) return null
        return when (id) {
            "minecraft:water_bucket" -> Items.WATER_BUCKET
            "minecraft:lava_bucket" -> Items.LAVA_BUCKET
            else -> runCatching { BuiltInRegistries.ITEM.getValue(Identifier.parse(id)) }
                .getOrNull()
                ?.takeUnless { it == Items.AIR }
        }
    }
}
