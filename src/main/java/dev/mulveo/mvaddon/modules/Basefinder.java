package dev.mulveo.mvaddon.modules;

import dev.mulveo.mvaddon.MVAddon;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.donut.addon.mv.compat.events.game.GameJoinedEvent;
import dev.donut.addon.mv.compat.events.game.GameLeftEvent;
import dev.donut.addon.mv.compat.events.packets.PacketEvent.Receive;
import dev.donut.addon.mv.compat.events.render.Render3DEvent;
import dev.donut.addon.mv.compat.events.world.TickEvent.Post;
import dev.donut.addon.mv.compat.renderer.ShapeMode;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.utils.render.color.Color;
import dev.donut.addon.mv.compat.events.EventHandler;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;

public final class Basefinder extends Module {
   private static final int SECTION_BYTES = 2048;
   private static final int MARKER_LIFETIME_TICKS = 600;
   private static final int MAX_MARKERS = 2048;
   private static final int MAX_TRACKED_CHUNKS = 5000;
   private static final int SNAPSHOT_RADIUS = 32;
   private static final byte[] ZERO_SECTION = new byte[2048];
   private static final Color INCREASE_SIDE = new Color(0, 255, 90, 65);
   private static final Color INCREASE_LINE = new Color(0, 255, 90, 255);
   private static final Color DECREASE_SIDE = new Color(255, 50, 40, 65);
   private static final Color DECREASE_LINE = new Color(255, 50, 40, 255);
   private final LinkedHashMap<Long, Map<Integer, byte[]>> chunkLight = new LinkedHashMap<>(256, 0.75F, true);
   private final LinkedHashMap<Long, Basefinder.Marker> markers = new LinkedHashMap<>();

   public Basefinder() {
      super(MVAddon.CATEGORY, "Light Tracker", "Marks block-light changes that may indicate activity in a base.");
   }

   public void onActivate() {
      this.reset();
      this.snapshotLoadedChunks();
   }

   public void onDeactivate() {
      this.reset();
   }

   @EventHandler
   private void onGameJoined(GameJoinedEvent var1) {
      this.reset();
   }

   @EventHandler
   private void onGameLeft(GameLeftEvent var1) {
      this.reset();
   }

   @EventHandler
   private void onPacket(Receive var1) {
      if (var1.packet instanceof ClientboundLevelChunkWithLightPacket var2) {
         int var7 = var2.x();
         int var5 = var2.z();
         ClientboundLightUpdatePacketData var6 = var2.lightData();
         this.mc.execute(() -> {
            if (this.isActive() && this.mc.level != null) {
               this.baselineChunk(var7, var5, var6);
            }
         });
      } else if (var1.packet instanceof ClientboundLightUpdatePacket var3) {
         int var9 = var3.x();
         int var10 = var3.z();
         ClientboundLightUpdatePacketData var11 = var3.lightData();
         this.mc.execute(() -> {
            if (this.isActive() && this.mc.level != null) {
               this.detectChanges(var9, var10, var11);
            }
         });
      }
   }

   @EventHandler
   private void onTick(Post var1) {
      Iterator var2 = this.markers.values().iterator();

      while (var2.hasNext()) {
         Basefinder.Marker var3 = (Basefinder.Marker)var2.next();
         var3.ticksLeft--;
         if (var3.ticksLeft <= 0) {
            var2.remove();
         }
      }
   }

   @EventHandler
   private void onRender(Render3DEvent var1) {
      for (Basefinder.Marker var3 : this.markers.values()) {
         Color var4 = var3.newValue > var3.oldValue ? INCREASE_SIDE : DECREASE_SIDE;
         Color var5 = var3.newValue > var3.oldValue ? INCREASE_LINE : DECREASE_LINE;
         var1.renderer.box(var3.x, var3.y, var3.z, var3.x + 1, var3.y + 1, var3.z + 1, var4, var5, ShapeMode.Both, 0);
      }
   }

   private void snapshotLoadedChunks() {
      ClientLevel var1 = this.mc.level;
      if (var1 != null && this.mc.player != null) {
         int var2 = this.mc.player.getBlockX() >> 4;
         int var3 = this.mc.player.getBlockZ() >> 4;

         for (int var4 = var2 - 32; var4 <= var2 + 32; var4++) {
            for (int var5 = var3 - 32; var5 <= var3 + 32; var5++) {
               Map var6 = this.snapshotChunk(var4, var5);
               if (var6 != null) {
                  this.chunkLight.put(packChunk(var4, var5), var6);
               }
            }
         }

         this.trimChunkCache();
      }
   }

   private Map<Integer, byte[]> snapshotChunk(int var1, int var2) {
      ClientLevel var3 = this.mc.level;
      if (var3 == null) {
         return null;
      } else {
         ClientChunkCache var4 = var3.getChunkSource();
         if (!var4.hasChunk(var1, var2)) {
            return null;
         } else {
            LayerLightEventListener var5 = var4.getLightEngine().getLayerListener(LightLayer.BLOCK);
            Map var6 = this.createZeroedChunk();

            for (int var7 = this.bottomSection(); var7 <= this.topSection(); var7++) {
               DataLayer var8 = var5.getDataLayerData(SectionPos.of(var1, var7, var2));
               if (var8 != null && !var8.isDefinitelyFilledWith(0)) {
                  var6.put(var7, Arrays.copyOf(var8.getData(), 2048));
               }
            }

            return var6;
         }
      }
   }

   private void baselineChunk(int var1, int var2, ClientboundLightUpdatePacketData var3) {
      Map var4 = this.createZeroedChunk();
      this.applyInitializedSections(var3, var4, var1, var2, false, null);
      this.applyUninitializedSections(var3, var4, var1, var2, false, null);
      this.chunkLight.put(packChunk(var1, var2), var4);
      this.trimChunkCache();
   }

   private void detectChanges(int var1, int var2, ClientboundLightUpdatePacketData var3) {
      long var4 = packChunk(var1, var2);
      Map var6 = this.chunkLight.get(var4);
      if (var6 == null) {
         var6 = this.snapshotChunk(var1, var2);
      }

      boolean var7 = var6 != null;
      if (var6 == null) {
         var6 = this.createZeroedChunk();
      }

      Basefinder.ChangeSummary var8 = new Basefinder.ChangeSummary();
      this.applyInitializedSections(var3, var6, var1, var2, var7, var8);
      this.applyUninitializedSections(var3, var6, var1, var2, var7, var8);
      this.chunkLight.put(var4, var6);
      this.trimChunkCache();
      if (var8.count == 1) {
         this.info(
            "Block light changed at " + var8.firstX + ", " + var8.firstY + ", " + var8.firstZ + ": " + var8.firstOld + " -> " + var8.firstNew, new Object[0]
         );
      } else if (var8.count > 1) {
         this.info(
            var8.count
               + " block-light values changed in chunk "
               + var1
               + ", "
               + var2
               + ". First: "
               + var8.firstX
               + ", "
               + var8.firstY
               + ", "
               + var8.firstZ
               + " "
               + var8.firstOld
               + " -> "
               + var8.firstNew,
            new Object[0]
         );
      }
   }

   private void applyInitializedSections(
      ClientboundLightUpdatePacketData var1, Map<Integer, byte[]> var2, int var3, int var4, boolean var5, Basefinder.ChangeSummary var6
   ) {
      BitSet var7 = var1.blockYMask();
      List var8 = var1.blockUpdates();
      int var9 = 0;

      for (int var10 = var7.nextSetBit(0); var10 >= 0 && var9 < var8.size(); var10 = var7.nextSetBit(var10 + 1)) {
         byte[] var11 = (byte[])var8.get(var9++);
         int var12 = this.lightSectionY(var10);
         if (this.isWorldSection(var12) && var11.length == 2048) {
            byte[] var13 = Arrays.copyOf(var11, 2048);
            this.updateSection(var2, var3, var4, var12, var13, var5, var6);
         }
      }
   }

   private void applyUninitializedSections(
      ClientboundLightUpdatePacketData var1, Map<Integer, byte[]> var2, int var3, int var4, boolean var5, Basefinder.ChangeSummary var6
   ) {
      BitSet var7 = var1.emptyBlockYMask();

      for (int var8 = var7.nextSetBit(0); var8 >= 0; var8 = var7.nextSetBit(var8 + 1)) {
         int var9 = this.lightSectionY(var8);
         if (this.isWorldSection(var9)) {
            this.updateSection(var2, var3, var4, var9, ZERO_SECTION, var5, var6);
         }
      }
   }

   private void updateSection(Map<Integer, byte[]> var1, int var2, int var3, int var4, byte[] var5, boolean var6, Basefinder.ChangeSummary var7) {
      byte[] var8 = var1.get(var4);
      if (var6 && var8 != null && var7 != null) {
         this.compareSection(var8, var5, var2, var3, var4, var7);
      }

      var1.put(var4, var5);
   }

   private void compareSection(byte[] var1, byte[] var2, int var3, int var4, int var5, Basefinder.ChangeSummary var6) {
      for (int var7 = 0; var7 < 2048; var7++) {
         int var8 = var1[var7] & 255;
         int var9 = var2[var7] & 255;
         int var10 = var8 ^ var9;
         if ((var10 & 15) != 0) {
            this.recordChange(var3, var4, var5, var7 << 1, var8 & 15, var9 & 15, var6);
         }

         if ((var10 & 240) != 0) {
            this.recordChange(var3, var4, var5, (var7 << 1) + 1, var8 >>> 4 & 15, var9 >>> 4 & 15, var6);
         }
      }
   }

   private void recordChange(int var1, int var2, int var3, int var4, int var5, int var6, Basefinder.ChangeSummary var7) {
      int var8 = var4 & 15;
      int var9 = var4 >>> 4 & 15;
      int var10 = var4 >>> 8 & 15;
      int var11 = (var1 << 4) + var8;
      int var12 = (var3 << 4) + var10;
      int var13 = (var2 << 4) + var9;
      var7.add(var11, var12, var13, var5, var6);
      long var14 = BlockPos.asLong(var11, var12, var13);
      this.markers.put(var14, new Basefinder.Marker(var11, var12, var13, var5, var6, 600));

      while (this.markers.size() > 2048) {
         Iterator var16 = this.markers.keySet().iterator();
         var16.next();
         var16.remove();
      }
   }

   private Map<Integer, byte[]> createZeroedChunk() {
      HashMap var1 = new HashMap();

      for (int var2 = this.bottomSection(); var2 <= this.topSection(); var2++) {
         var1.put(var2, ZERO_SECTION);
      }

      return var1;
   }

   private int lightSectionY(int var1) {
      ClientLevel var2 = this.mc.level;
      if (var2 == null) {
         return Integer.MIN_VALUE;
      } else {
         LevelLightEngine var3 = var2.getChunkSource().getLightEngine();
         return var3.getMinLightSection() + var1;
      }
   }

   private int bottomSection() {
      return this.mc.level.getMinSectionY();
   }

   private int topSection() {
      return this.mc.level.getMaxSectionY();
   }

   private boolean isWorldSection(int var1) {
      return var1 >= this.bottomSection() && var1 <= this.topSection();
   }

   private void trimChunkCache() {
      while (this.chunkLight.size() > 5000) {
         Iterator var1 = this.chunkLight.keySet().iterator();
         var1.next();
         var1.remove();
      }
   }

   private void reset() {
      this.chunkLight.clear();
      this.markers.clear();
   }

   private static long packChunk(int var0, int var1) {
      return var0 & 4294967295L | (var1 & 4294967295L) << 32;
   }

   private static final class ChangeSummary {
      private int count;
      private int firstX;
      private int firstY;
      private int firstZ;
      private int firstOld;
      private int firstNew;

      private void add(int var1, int var2, int var3, int var4, int var5) {
         if (this.count == 0) {
            this.firstX = var1;
            this.firstY = var2;
            this.firstZ = var3;
            this.firstOld = var4;
            this.firstNew = var5;
         }

         this.count++;
      }
   }

   private static final class Marker {
      private final int x;
      private final int y;
      private final int z;
      private final int oldValue;
      private final int newValue;
      private int ticksLeft;

      private Marker(int var1, int var2, int var3, int var4, int var5, int var6) {
         this.x = var1;
         this.y = var2;
         this.z = var3;
         this.oldValue = var4;
         this.newValue = var5;
         this.ticksLeft = var6;
      }
   }
}
