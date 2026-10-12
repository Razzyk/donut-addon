package dev.donut.addon.mv.compat.settings;

import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.enchantment.Enchantment;

public final class EnchantmentListSetting {
    private EnchantmentListSetting() {}
    public static final class Builder extends SettingBuilder<Set<ResourceKey<Enchantment>>, Builder> {
        public Builder() { kind = "enchantment-list"; defaultValue = new LinkedHashSet<>(); }
        @Override protected Builder self() { return this; }
        @Override public Builder defaultValue(Set<ResourceKey<Enchantment>> value) { defaultValue = new LinkedHashSet<>(value); return this; }
        public Setting<Set<ResourceKey<Enchantment>>> build() { return buildSetting("enchantment-list"); }
    }
}
