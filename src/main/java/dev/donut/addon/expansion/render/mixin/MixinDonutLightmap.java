package dev.donut.addon.expansion.render.mixin;

import dev.donut.addon.expansion.render.DonutBrightness;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Resolve the final stored gamma after normal client brightness policies. */
@Mixin(value = LightmapRenderStateExtractor.class, priority = 900)
abstract class MixinDonutLightmap {
    @ModifyVariable(method = "extract", at = @At("STORE"), name = "brightnessOption")
    private float donut$immediateGamma(float original) {
        return DonutBrightness.lightmapGamma(original);
    }
}
