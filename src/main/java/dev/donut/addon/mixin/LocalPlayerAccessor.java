package dev.donut.addon.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LocalPlayer.class)
public interface LocalPlayerAccessor {
    @Accessor("xLast") void donut$setXLast(double value);
    @Accessor("yLast") void donut$setYLast(double value);
    @Accessor("zLast") void donut$setZLast(double value);

}
