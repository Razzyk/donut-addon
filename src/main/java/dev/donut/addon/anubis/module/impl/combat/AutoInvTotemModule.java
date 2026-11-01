package dev.donut.addon.anubis.module.impl.combat;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Items;
public final class AutoInvTotemModule {
    private AutoInvTotemModule() {}
    public static boolean wantsHotbar() {
        var p = Minecraft.getInstance().player;
        if (p == null) return false;
        var carried = p.containerMenu.getCarried();
        return !carried.isEmpty() && (carried.is(Items.TOTEM_OF_UNDYING) || p.getOffhandItem().is(Items.TOTEM_OF_UNDYING));
    }
}
