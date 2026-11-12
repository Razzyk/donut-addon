package dev.donut.addon.expansion.utility.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.donut.addon.expansion.utility.DonutShareGuard;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class MixinDonutShareGuardHud {
    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void donut$startHudPrivacyPass(GuiGraphicsExtractor extractor, DeltaTracker deltaTracker, CallbackInfo ci) {
        DonutShareGuard.beginHudFrame();
    }

    @ModifyVariable(method = "extractSlot", at = @At("HEAD"), argsOnly = true)
    private ItemStack donut$maskHotbarStack(
        ItemStack stack,
        @Local(argsOnly = true, ordinal = 0) int x,
        @Local(argsOnly = true, ordinal = 1) int y,
        @Local(argsOnly = true, ordinal = 2) int slotSeed
    ) {
        return DonutShareGuard.maskHotbar(stack, slotSeed, x, y);
    }

    @Redirect(
        method = "extractItemHotbar",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V")
    )
    private void donut$hideSelectedHotbarCell(
        GuiGraphicsExtractor extractor,
        com.mojang.renderpearl.api.pipeline.RenderPipeline pipeline,
        Identifier sprite,
        int x, int y, int width, int height
    ) {
        if (!DonutShareGuard.hideHotbarSelection() || !"hud/hotbar_selection".equals(sprite.getPath())) {
            extractor.blitSprite(pipeline, sprite, x, y, width, height);
        }
    }

    @Inject(method = "extractSelectedItemName", at = @At("HEAD"), cancellable = true)
    private void donut$hideSelectedName(GuiGraphicsExtractor extractor, CallbackInfo ci) {
        if (DonutShareGuard.hideHotbarName()) ci.cancel();
    }
}
