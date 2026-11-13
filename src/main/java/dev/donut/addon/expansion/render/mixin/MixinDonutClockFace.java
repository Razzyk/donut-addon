package dev.donut.addon.expansion.render.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.donut.addon.expansion.render.DonutClock;
import dev.donut.addon.expansion.render.DonutClockIdentity;
import net.minecraft.client.ClientClockManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ClientClockManager.ClientClockInstance.class, priority = 900)
abstract class MixinDonutClockFace implements DonutClockIdentity {
    @Unique private boolean donut$overworldClock;

    @Override
    public void donut$markOverworldClock(boolean overworldClock) {
        donut$overworldClock = overworldClock;
    }

    @ModifyReturnValue(method = "totalTicks", at = @At("RETURN"))
    private long donut$displayedTime(long original) {
        return DonutClock.resolveTicks(original, donut$overworldClock);
    }
}
