package dev.donut.addon.expansion.render.mixin;

import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientClockManager.class)
abstract class MixinDonutClockFactory {
    @Inject(method = "getInstance(Lnet/minecraft/core/Holder;)Lnet/minecraft/client/ClientClockManager$ClientClockInstance;",
        at = @At("RETURN"))
    private void donut$identifyClock(Holder<WorldClock> definition,
                                   CallbackInfoReturnable<ClientClockManager.ClientClockInstance> result) {
        ((DonutClockIdentity) result.getReturnValue()).donut$markOverworldClock(definition.is(WorldClocks.OVERWORLD));
    }
}
