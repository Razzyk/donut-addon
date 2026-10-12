package dev.donut.addon.mv.compat.systems.modules;

import net.ccbluex.liquidbounce.features.module.ModuleCategory;
public final class Category {
    public final String name;
    public final ModuleCategory nativeCategory;
    public Category(ModuleCategory category) { this.name = category.getTag(); this.nativeCategory = category; }
}
