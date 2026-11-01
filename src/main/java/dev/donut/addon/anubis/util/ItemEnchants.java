package dev.donut.addon.anubis.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public final class ItemEnchants {
    private ItemEnchants() {}
    public static boolean supports(Item item) {
        return item != null && (item == Items.ENCHANTED_BOOK || item.components().has(DataComponents.ENCHANTABLE));
    }
}
