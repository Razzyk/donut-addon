package dev.mulveo.mvaddon.chunkscanner.light;

import dev.mulveo.mvaddon.chunkscanner.ChunkScannerBridge;
import dev.mulveo.mvaddon.modules.ChunkScanner;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashSet;
import java.util.Set;
import dev.donut.addon.mv.compat.MeteorClient;
import dev.donut.addon.mv.compat.events.world.TickEvent.Pre;
import dev.donut.addon.mv.compat.systems.modules.Modules;
import dev.donut.addon.mv.compat.events.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;

public final class LightCache {
   public static final LightCache INSTANCE = new LightCache();
   public static final Long2ObjectOpenHashMap<ChunkLightSnapshot> SNAPSHOTS = new Long2ObjectOpenHashMap();
   private static final LongOpenHashSet ALERTED = new LongOpenHashSet();
   private static final Set<LightCache.AlertKey> ALERTED_SECTIONS = new HashSet<>();
   private static final int DETECTION_CUTOFF_Y = 1;
   private static final int MAX_DRAIN_PER_TICK = 4096;
   private ClientLevel lastWorld;

   private LightCache() {
   }

   public static void register() {
      MeteorClient.EVENT_BUS.subscribe(INSTANCE);
   }

   public static boolean hasAlertedChunks() {
      return !ALERTED.isEmpty();
   }

   public static LongIterator alertedChunkIterator() {
      return ALERTED.iterator();
   }

   public static float requiredFarPlaneDistance(double var0, double var2, float var4) {
      double var5 = var4;
      LongIterator var7 = ALERTED.iterator();

      while (var7.hasNext()) {
         ChunkPos var8 = ChunkPos.unpack(var7.nextLong());
         double var9 = (var8.x() << 4) + 8.0;
         double var11 = (var8.z() << 4) + 8.0;
         var5 = Math.max(var5, Math.hypot(var9 - var0, var11 - var2) + 256.0);
      }

      return (float)Math.min(var5, 3.0E7);
   }

   public static void resetBaseline() {
      ALERTED.clear();
      ALERTED_SECTIONS.clear();
   }

   @EventHandler
   private void onTickPre(Pre var1) {
      Minecraft var2 = Minecraft.getInstance();
      ClientLevel var3 = var2.level;
      if (var3 != this.lastWorld) {
         SNAPSHOTS.clear();
         ALERTED.clear();
         ALERTED_SECTIONS.clear();
         PacketIntake.QUEUE.clear();
         this.lastWorld = var3;
      }

      if (var3 != null) {
         int var4 = var3.getSectionsCount();
         int var5 = var3.getMinSectionY();
         int var6 = 0;

         PacketIntake.PendingLight var7;
         while ((var7 = PacketIntake.QUEUE.poll()) != null) {
            ingest(var7, var4, var5);
            if (++var6 >= 4096) {
               break;
            }
         }
      }
   }

   private static void ingest(PacketIntake.PendingLight var0, int var1, int var2) {
      long var3 = ChunkPos.pack(var0.chunkX(), var0.chunkZ());
      ChunkLightSnapshot var5 = (ChunkLightSnapshot)SNAPSHOTS.get(var3);
      if (var5 == null || var5.sectionCount != var1 || var5.bottomSectionY != var2) {
         var5 = new ChunkLightSnapshot(var0.chunkX(), var0.chunkZ(), var1, var2);
         SNAPSHOTS.put(var3, var5);
      }

      try {
         LightDataClassifier.apply(var0.data(), var5, var0.arrivalNanos());
      } catch (Throwable var7) {
         ChunkScannerBridge.LOG.error("[Chunk Finder] Light classifier crashed on chunk " + var0.chunkX() + "," + var0.chunkZ(), var7);
         return;
      }

      maybeAlert(var0.chunkX(), var0.chunkZ(), var3, var5, var0.arrivalNanos());
   }

   private static void maybeAlert(int var0, int var1, long var2, ChunkLightSnapshot var4, long var5) {
      ChunkScanner var7 = (ChunkScanner)Modules.get().get(ChunkScanner.class);
      if (var7 != null && var7.isActive()) {
         int var8 = var4.sectionCount + 1;

         for (int var9 = 1; var9 < var8; var9++) {
            int var10 = (var4.bottomSectionY + var9 - 1) * 16;
            if (var10 < 1) {
               if (var4.skyUpdatedNanos[var9] == var5) {
                  inspectTrack(var7, LightCache.LightTrack.SKY, var0, var1, var2, var10, var4.sky[var9], var4.skyValues[var9]);
               }

               if (var4.blockUpdatedNanos[var9] == var5) {
                  inspectTrack(var7, LightCache.LightTrack.BLOCK, var0, var1, var2, var10, var4.block[var9], var4.blockValues[var9]);
               }
            }
         }
      }
   }

   private static void inspectTrack(
      ChunkScanner var0, LightCache.LightTrack var1, int var2, int var3, long var4, int var6, SectionStatus var7, NibbleSummary var8
   ) {
      if (var8 != null
         && var7 != SectionStatus.UNKNOWN
         && var7 != SectionStatus.MISSING
         && var7 != SectionStatus.EMPTY_MASK
         && var7 != SectionStatus.NORMAL
         && ALERTED_SECTIONS.add(new LightCache.AlertKey(var4, var6, var1))
         && ALERTED.add(var4)) {
         var0.onChunkFound(var2, var3);
      }
   }

   private record AlertKey(long chunkKey, int sectionY, LightCache.LightTrack track) {
   }

   private static enum LightTrack {
      SKY,
      BLOCK;
   }
}
