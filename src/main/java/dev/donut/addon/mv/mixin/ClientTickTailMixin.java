package dev.donut.addon.mv.mixin;

import dev.donut.addon.mv.compat.MeteorClient;
import dev.donut.addon.mv.compat.events.world.TickEvent;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public abstract class ClientTickTailMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void mv$postTick(CallbackInfo ci) { MeteorClient.EVENT_BUS.post(new TickEvent.Post()); }
}
