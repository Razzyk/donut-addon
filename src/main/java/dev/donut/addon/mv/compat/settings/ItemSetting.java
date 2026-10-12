package dev.donut.addon.mv.compat.settings;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public final class ItemSetting {
    private ItemSetting() {}
    public static final class Builder extends SettingBuilder<Item, Builder> {
        public Builder() { kind = "item"; defaultValue = Items.AIR; }
        @Override protected Builder self() { return this; }
        public Setting<Item> build() { return buildSetting("item"); }
    }
}
