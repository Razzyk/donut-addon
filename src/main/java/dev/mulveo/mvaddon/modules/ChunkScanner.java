package dev.mulveo.mvaddon.modules;

import dev.mulveo.mvaddon.MVAddon;
import dev.mulveo.mvaddon.chunkscanner.ChunkScannerBridge;
import dev.mulveo.mvaddon.chunkscanner.light.LightCache;
import dev.mulveo.mvaddon.chunkscanner.render.LightInspectorRenderer;
import dev.donut.addon.mv.compat.events.render.Render3DEvent;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.settings.EnumSetting;
import dev.donut.addon.mv.compat.settings.ColorSetting;
import dev.donut.addon.mv.compat.settings.IntSetting;
import dev.donut.addon.mv.compat.settings.SettingGroup;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.utils.render.color.SettingColor;
import dev.donut.addon.mv.compat.events.EventHandler;

public final class ChunkScanner extends Module {
   private final SettingGroup general = this.settings.getDefaultGroup();
   private final Setting<Integer> alpha = this.general
      .add(
         new IntSetting.Builder().name("alpha").description("Alpha of the matte-green chunk plate (0-255).").defaultValue(140)
            .min(0)
            .sliderRange(0, 255)
            .build()
      );
   private final Setting<SettingColor> color = this.general
      .add(
         new ColorSetting.Builder()
                  .name("color")
               .description("Color of Light Scanner markers.")
            .defaultValue(new SettingColor(46, 214, 132, 140))
            .build()
      );
   private final Setting<ChunkScanner.MarkerShape> markerShape = this.general
      .add(
         new EnumSetting.Builder<ChunkScanner.MarkerShape>()
                     .name("marker-shape")
                  .description("Renders a clean plate, a vertical pillar, or both.")
               .defaultValue(ChunkScanner.MarkerShape.Plate)
            .build()
      );
   private final Setting<Integer> markerY = this.general
      .add(
         new IntSetting.Builder().name("marker-y").description("Height of the chunk plate and base of the pillar.").defaultValue(60)
            .range(-64, 320)
            .sliderRange(-64, 320)
            .build()
      );

   public ChunkScanner() {
      super(MVAddon.CATEGORY, "Light Scanner", "Detects unusual light-update data and marks suspicious underground chunks.");
   }

   public void onActivate() {
      LightCache.resetBaseline();
   }

   public void onChunkFound(int var1, int var2) {
      ChunkScannerBridge.onChunkFound(var1, var2);
   }

   @EventHandler
   private void onRender3d(Render3DEvent var1) {
      if (this.mc.level != null && this.mc.player != null && LightCache.hasAlertedChunks()) {
         LightInspectorRenderer.draw(
            var1, (Integer)this.alpha.get(), (SettingColor)this.color.get(), (ChunkScanner.MarkerShape)this.markerShape.get(), (Integer)this.markerY.get()
         );
      }
   }

   public static enum MarkerShape {
      Plate,
      Pillar,
      Both;
   }
}
