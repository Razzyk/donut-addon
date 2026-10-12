package dev.mulveo.mvaddon.chunkscanner.render;

import dev.mulveo.mvaddon.chunkscanner.light.LightCache;
import dev.mulveo.mvaddon.modules.ChunkScanner;
import it.unimi.dsi.fastutil.longs.LongIterator;
import dev.donut.addon.mv.compat.events.render.Render3DEvent;
import dev.donut.addon.mv.compat.renderer.ShapeMode;
import dev.donut.addon.mv.compat.utils.render.color.Color;
import dev.donut.addon.mv.compat.utils.render.color.SettingColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

public final class LightInspectorRenderer {
   private static final double MARKER_Y = 60.0;
   private static final double MARKER_HEIGHT = 0.06;

   private LightInspectorRenderer() {
   }

   public static void draw(Render3DEvent var0, int var1, SettingColor configured, ChunkScanner.MarkerShape shape, int markerY) {
      if (LightCache.hasAlertedChunks()) {
         int var2 = Math.max(0, Math.min(255, var1));
         Color var3 = new Color(configured.r, configured.g, configured.b, Math.min(var2, 52));
         Color var4 = new Color(Math.min(255, configured.r + 38), Math.min(255, configured.g + 38), Math.min(255, configured.b + 38), var2);
         LongIterator var5 = LightCache.alertedChunkIterator();

         while (var5.hasNext()) {
            ChunkPos var6 = ChunkPos.unpack(var5.nextLong());
            double var7 = (var6.x() << 4) + 0.35;
            double var9 = (var6.z() << 4) + 0.35;
            if (shape != ChunkScanner.MarkerShape.Pillar) {
               AABB var11 = new AABB(var7, markerY, var9, var7 + 15.3, markerY + 0.035, var9 + 15.3);
               var0.renderer.box(var11, var3, var4, ShapeMode.Both, 0);
            }

            if (shape != ChunkScanner.MarkerShape.Plate) {
               double centerX = var7 + 7.65;
               double centerZ = var9 + 7.65;
               var0.renderer
                  .box(
                     centerX - 0.34,
                     markerY,
                     centerZ - 0.34,
                     centerX + 0.34,
                     320.0,
                     centerZ + 0.34,
                     new Color(var3.r, var3.g, var3.b, Math.min(28, var3.a)),
                     var4,
                     ShapeMode.Both,
                     0
                  );
            }
         }
      }
   }
}
