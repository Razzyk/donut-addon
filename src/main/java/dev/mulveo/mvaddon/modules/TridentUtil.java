package dev.mulveo.mvaddon.modules;

import dev.mulveo.mvaddon.MVAddon;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.settings.BoolSetting;
import dev.donut.addon.mv.compat.settings.DoubleSetting;
import dev.donut.addon.mv.compat.systems.modules.Module;

public final class TridentUtil extends Module {
   private final Setting<Double> scale = this.settings
      .getDefaultGroup()
      .add(new DoubleSetting.Builder().name("scale").description("Cooldown factor.").defaultValue(1.0).range(0.0, 1.0).sliderRange(0.0, 1.0).build());
   private final Setting<Boolean> noWater = this.settings
      .getDefaultGroup()
      .add(
         new BoolSetting.Builder()
                     .name("no-water")
                  .description("Allows Riptide use outside water or rain.")
               .defaultValue(true)
            .build()
      );

   public TridentUtil() {
      super(MVAddon.UTILITY, "Trident Util", "Adjusts trident charge time and optionally allows Riptide outside water or rain.");
   }

   public boolean allowsNoWater() {
      return this.isActive() && this.noWater.get();
   }

   public int adjustedMinimumCharge(int vanillaMinimum) {
      return !this.isActive() ? vanillaMinimum : Math.max(1, (int)Math.round(vanillaMinimum * this.scale.get()));
   }
}
