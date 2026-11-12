package dev.donut.addon.expansion.building.mixin;

import dev.donut.addon.expansion.building.AreaMinerSelectionInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MixinDonutAreaMinerMouse {
    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void donut$consumeCornerSelection(long window, MouseButtonInfo input, int action, CallbackInfo ci) {
        long currentWindow = Minecraft.getInstance().getWindow().handle();
        if ((window == 0L || window == currentWindow) &&
            AreaMinerSelectionInput.consume(input.button(), action)) {
            ci.cancel();
        }
    }
}
