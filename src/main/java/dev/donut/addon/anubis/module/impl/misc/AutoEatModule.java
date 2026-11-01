package dev.donut.addon.anubis.module.impl.misc;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
public final class AutoEatModule {
    private AutoEatModule() {}
    public static boolean isEating() {
        var p = Minecraft.getInstance().player;
        return p != null && p.isUsingItem() && p.getUseItem().has(DataComponents.FOOD);
    }
}
