package dev.donut.addon.expansion.movement.mixin;

import dev.donut.addon.expansion.movement.DonutSurfaceWalk;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class MixinDonutSurfaceBubble {
    @Inject(method = {"onAboveBubbleColumn", "onInsideBubbleColumn"}, at = @At("HEAD"))
    private void donut$trackBubbleLift(CallbackInfo callback) {
        DonutSurfaceWalk.observeBubble((Entity)(Object)this);
    }
}
