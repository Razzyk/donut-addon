package dev.donut.addon.expansion.utility.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.donut.addon.expansion.utility.DonutShareGuard;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class MixinDonutShareGuardInventory {
    @Shadow protected int leftPos;
    @Shadow protected int topPos;
    @Shadow protected Slot hoveredSlot;

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void donut$startContainerPrivacyPass(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        DonutShareGuard.beginContainerFrame();
    }

    @ModifyArg(
        method = "extractSlot",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;item(Lnet/minecraft/world/item/ItemStack;III)V"),
        index = 0
    )
    private ItemStack donut$maskOwnedSlotItem(ItemStack stack, @Local(argsOnly = true) Slot slot) {
        return DonutShareGuard.maskSlot(stack, slot, leftPos + slot.x, topPos + slot.y);
    }

    @ModifyArg(
        method = "extractSlot",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V"),
        index = 1
    )
    private ItemStack donut$maskOwnedSlotDecorations(ItemStack stack, @Local(argsOnly = true) Slot slot) {
        return DonutShareGuard.maskSlot(stack, slot, leftPos + slot.x, topPos + slot.y);
    }

    @ModifyArg(
        method = "extractCarriedItem",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;extractFloatingItem(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V"),
        index = 1
    )
    private ItemStack donut$maskCarriedItem(
        ItemStack stack,
        @Local(argsOnly = true, ordinal = 0) int mouseX,
        @Local(argsOnly = true, ordinal = 1) int mouseY
    ) {
        return DonutShareGuard.maskCarried(stack, mouseX - 8, mouseY - 8);
    }

    @Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
    private void donut$hideOwnedItemTooltip(GuiGraphicsExtractor extractor, int mouseX, int mouseY, CallbackInfo ci) {
        if (DonutShareGuard.hideInventoryTooltip(hoveredSlot)) ci.cancel();
    }
}
