package dev.donut.addon.mv.mixin;

import dev.donut.addon.mv.MVEventBridge;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Matches the source client login event, without firing again on dimension changes. */
@Mixin(ClientPacketListener.class)
public abstract class ClientJoinMixin {
    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void mv$joinedServer(ClientboundLoginPacket packet, CallbackInfo ci) {
        MVEventBridge.joinedServer();
    }
}
