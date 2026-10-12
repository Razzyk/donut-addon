package dev.mulveo.mvaddon.modules;

import dev.mulveo.mvaddon.MVAddon;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import dev.donut.addon.mv.compat.events.game.GameJoinedEvent;
import dev.donut.addon.mv.compat.events.game.GameLeftEvent;
import dev.donut.addon.mv.compat.events.render.Render3DEvent;
import dev.donut.addon.mv.compat.events.world.TickEvent.Post;
import dev.donut.addon.mv.compat.renderer.ShapeMode;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.settings.ColorSetting;
import dev.donut.addon.mv.compat.settings.EnumSetting;
import dev.donut.addon.mv.compat.settings.IntSetting;
import dev.donut.addon.mv.compat.settings.BoolSetting;
import dev.donut.addon.mv.compat.settings.SettingGroup;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.utils.Utils;
import dev.donut.addon.mv.compat.utils.render.color.Color;
import dev.donut.addon.mv.compat.utils.render.color.SettingColor;
import dev.donut.addon.mv.compat.events.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;

public final class SusChunkFinder extends Module {
   private static final int SCAN_INTERVAL_TICKS = 10;
   private static final int REGION_LINK_DISTANCE = 7;
   private static final int MAX_REGION_SPAN = 256;
   private static final int RENDER_DISTANCE_CHUNKS = 128;
   private static final int MAX_RENDER_TILES = 6000;
   private static final int MIN_HOLE_DEPTH = 10;
   private static final int FLAG_RED = 255;
   private static final int FLAG_GREEN = 165;
   private static final int FLAG_BLUE = 55;
   private static final Set<Block> AMETHYST_BLOCKS = Set.of(
      Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST, Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD, Blocks.AMETHYST_CLUSTER
   );
   private static final Set<Block> SUSPICIOUS_STONE = Set.of(Blocks.STONE, Blocks.DEEPSLATE, Blocks.TUFF);
   private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
   private final SettingGroup sgRender = this.settings.createGroup("Render");
   private final Setting<Boolean> amethystDetection = this.sgGeneral
      .add(
         new BoolSetting.Builder().name("amethyst-detection").description("Detect suspicious stone touching amethyst clusters.")
               .defaultValue(true)
            .build()
      );
   private final Setting<Integer> minimumStone = this.sgGeneral
      .add(
         new IntSetting.Builder()
                        .name("minimum-clusters")
                     .description("Minimum strong amethyst/stone contacts required before a chunk is flagged.")
                  .defaultValue(3)
               .range(1, 30)
               .sliderRange(1, 30)
               .visible(this.amethystDetection::get)
            .build()
      );
   private final Setting<Boolean> fullBeehiveDetection = this.sgGeneral
      .add(
         new BoolSetting.Builder().name("full-beehive-detection").description("Flag only chunks containing at least two full beehives.")
               .defaultValue(true)
            .build()
      );
   private final Setting<Boolean> suschunk = this.sgGeneral
      .add(
         new BoolSetting.Builder().name("suschunk")
                  .description("Mark suspicious chunks with no 10-block-deep 1-wide or 3-wide shaft nearby.")
               .defaultValue(true)
            .build()
      );
   private final Setting<Integer> suschunkRange = this.sgGeneral
      .add(
         new IntSetting.Builder()
                        .name("suschunk-range")
                     .description("Chunk radius searched for qualifying vertical shafts.")
                  .defaultValue(4)
               .range(1, 30)
               .sliderRange(1, 30)
               .visible(this.suschunk::get)
            .build()
      );
   private final Setting<SusChunkFinder.MarkerMode> markerMode = this.sgRender
      .add(
         new EnumSetting.Builder<SusChunkFinder.MarkerMode>()
                     .name("marker-mode")
                  .description("How suspicious chunk plates are drawn.")
               .defaultValue(SusChunkFinder.MarkerMode.Filled)
            .build()
      );
   private final Setting<SusChunkFinder.MarkerShape> markerShape = this.sgRender
      .add(
         new EnumSetting.Builder<SusChunkFinder.MarkerShape>()
                     .name("marker-shape")
                  .description("Renders a clean plate, a vertical pillar, or both.")
               .defaultValue(SusChunkFinder.MarkerShape.Plate)
            .build()
      );
   private final Setting<SettingColor> markerColor = this.sgRender
      .add(
         new ColorSetting.Builder()
                  .name("marker-color")
               .description("Color of suspicious chunk markers.")
            .defaultValue(new SettingColor(255, 255, 255, 90))
            .build()
      );
   private final Setting<Integer> markerY = this.sgRender
      .add(
         new IntSetting.Builder()
                     .name("marker-y")
                  .description("Height of plates and base of pillars.")
               .defaultValue(60)
            .range(-64, 320)
            .sliderRange(-64, 320)
            .build()
      );
   private final Setting<Integer> flagOpacity = this.sgRender
      .add(
         new IntSetting.Builder()
                        .name("alpha")
                     .description("Opacity for every Suspicious Chunks plate.")
                  .defaultValue(45)
               .range(0, 255)
               .sliderRange(0, 255)
               .visible(this.suschunk::get)
            .build()
      );
   private final Setting<Boolean> smartAdjustments = this.sgRender
      .add(
         new BoolSetting.Builder()
            .name("smart-adjustments")
            .description("In heavily overlapping areas, shows only the highest-overlap core chunks instead of the whole flooded area.")
            .defaultValue(false)
            .build()
      );
   private final AtomicBoolean scanning = new AtomicBoolean();
   private final AtomicInteger scanVersion = new AtomicInteger();
   private final ExecutorService scanner = Executors.newSingleThreadExecutor(var0 -> {
      Thread var1 = new Thread(var0, "scf-scanner");
      var1.setDaemon(true);
      return var1;
   });
   private volatile List<SusChunkFinder.ChunkDetection> detections = List.of();
   private volatile List<SusChunkFinder.SignalHit> scannedHits = List.of();
   private volatile Set<Long> renderedPlates = Set.of();
   private final Set<Long> persistentPlates = new HashSet<>();
   private volatile Set<Long> xpFlags = Set.of();
   private int scanTimer;
   private DimensionType lastDimension;

   public SusChunkFinder() {
      super(MVAddon.CATEGORY, "Suspicious Chunks", "Finds suspicious chunks.");
   }

   public void onActivate() {
      this.reset();
      this.lastDimension = this.mc.level == null ? null : this.mc.level.dimensionType();
      this.requestScan();
   }

   public void onDeactivate() {
      this.reset();
   }

   @EventHandler
   private void onGameJoined(GameJoinedEvent var1) {
      this.reset();
      this.lastDimension = this.mc.level == null ? null : this.mc.level.dimensionType();
   }

   @EventHandler
   private void onGameLeft(GameLeftEvent var1) {
      this.reset();
      this.lastDimension = null;
   }

   @EventHandler
   private void onTick(Post var1) {
      if (Utils.canUpdate()) {
         DimensionType var2 = this.mc.level.dimensionType();
         if (var2 != this.lastDimension) {
            this.reset();
            this.lastDimension = var2;
         }

         if (++this.scanTimer >= 10) {
            this.scanTimer = 0;
            this.requestScan();
         }
      }
   }

   private void reset() {
      this.scanVersion.incrementAndGet();
      this.detections = List.of();
      this.scannedHits = List.of();
      this.renderedPlates = Set.of();
      this.persistentPlates.clear();
      this.xpFlags = Set.of();
      this.scanTimer = 0;
   }

   private void requestScan() {
      if (this.isActive() && this.mc.level != null && this.scanning.compareAndSet(false, true)) {
         int var1 = this.scanVersion.get();
         SusChunkFinder.ScanConfig var2 = this.snapshotConfig();
         HashMap var3 = new HashMap();

         for (ChunkAccess var5 : Utils.chunks()) {
            var3.put(var5.getPos().pack(), var5);
         }

         this.scanner.submit(() -> {
            try {
               SusChunkFinder.ScanResult var4 = scanLoadedChunks(var3, var2);
               if (this.isActive() && var1 == this.scanVersion.get()) {
                  List<SusChunkFinder.ChunkDetection> var5x = List.copyOf(var4.detections);
                  List<SusChunkFinder.SignalHit> var6 = List.copyOf(var4.hits);
                  Set<Long> var7 = Set.copyOf(var4.xpFlags);
                  this.mc.execute(() -> {
                     if (this.isActive() && var1 == this.scanVersion.get()) {
                        boolean var5xx = var7.stream().anyMatch(var1xxx -> !this.xpFlags.contains(var1xxx));
                        this.detections = var5x;
                        this.scannedHits = var6;
                        this.xpFlags = var7;
                        this.refreshRenderedPlates();
                        if (var5xx && this.mc.player != null) {
                           this.mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 1.2F);
                        }
                     }
                  });
               }
            } catch (Throwable var111) {
               this.mc.execute(() -> this.error("SusChunk Finder scan failed: %s", new Object[]{var111.getMessage()}));
            } finally {
               this.scanning.set(false);
            }
         });
      }
   }

   private SusChunkFinder.ScanConfig snapshotConfig() {
      return new SusChunkFinder.ScanConfig(
         (Boolean)this.amethystDetection.get(),
         (Integer)this.minimumStone.get(),
         (Boolean)this.fullBeehiveDetection.get(),
         (Boolean)this.suschunk.get(),
         (Integer)this.suschunkRange.get()
      );
   }

   private static SusChunkFinder.ScanResult scanLoadedChunks(Map<Long, ChunkAccess> var0, SusChunkFinder.ScanConfig var1) {
      Set<SusChunkFinder.SectionKey> var2 = var1.amethystEnabled ? findAmethystSections(var0.values()) : Set.of();
      ArrayList<SusChunkFinder.ChunkDetection> var3 = new ArrayList<>();
      ArrayList<SusChunkFinder.SignalHit> var4 = new ArrayList<>();

      for (ChunkAccess var6 : var0.values()) {
         ChunkPos var7 = var6.getPos();
         int var8 = var1.amethystEnabled ? countStoneTouchingAmethyst(var6, var7, var0, var2) : 0;
         int var9 = var1.fullBeehiveEnabled ? countFullBeehives(var6) : 0;
         boolean var10 = var8 >= var1.minimumStone;
         boolean var11 = var9 >= 2;
         if (var10 || var11) {
            var4.add(new SusChunkFinder.SignalHit(var7.x(), var7.z()));
         }

         if (var10 || var11) {
            var3.add(new SusChunkFinder.ChunkDetection(var7, var8, var9));
         }
      }

      HashSet<Long> var12 = new HashSet<>();
      if (var1.suschunkEnabled) {
         HashMap var13 = new HashMap();

         for (SusChunkFinder.SignalHit var15 : var4) {
            if (hasNoQualifyingHoleNearby(var0, var15.chunkX, var15.chunkZ, var1.suschunkRange, var13)) {
               var12.add(pack(var15.chunkX, var15.chunkZ));
            }
         }
      }

      return new SusChunkFinder.ScanResult(var3, var4, var12);
   }

   private static int countFullBeehives(ChunkAccess var0) {
      int var1 = 0;

      for (LevelChunkSection var5 : var0.getSections()) {
         if (!var5.hasOnlyAir() && var5.maybeHas(var0x -> isBeehiveBlock(var0x.getBlock()))) {
            for (int var6 = 0; var6 < 16; var6++) {
               for (int var7 = 0; var7 < 16; var7++) {
                  for (int var8 = 0; var8 < 16; var8++) {
                     BlockState var9 = var5.getBlockState(var8, var6, var7);
                     if (isBeehiveBlock(var9.getBlock()) && (Integer)var9.getValue(BeehiveBlock.HONEY_LEVEL) >= 5) {
                        var1++;
                     }
                  }
               }
            }
         }
      }

      return var1;
   }

   private static boolean isBeehiveBlock(Block var0) {
      return var0 == Blocks.BEE_NEST || var0 == Blocks.BEEHIVE;
   }

   private static int countStoneTouchingAmethyst(ChunkAccess var0, ChunkPos var1, Map<Long, ChunkAccess> var2, Set<SusChunkFinder.SectionKey> var3) {
      LevelChunkSection[] var4 = var0.getSections();
      MutableBlockPos var5 = new MutableBlockPos();
      int var6 = 0;

      for (int var7 = 0; var7 < var4.length; var7++) {
         LevelChunkSection var8 = var4[var7];
         if (!var8.hasOnlyAir() && var8.maybeHas(var0x -> SUSPICIOUS_STONE.contains(var0x.getBlock()))) {
            int var9 = var0.getMinY() / 16 + var7;
            SusChunkFinder.SectionKey var10 = new SusChunkFinder.SectionKey(var1.x(), var9, var1.z());
            if (touchesAmethystSection(var10, var3)) {
               int var11 = var1.getMinBlockX();
               int var12 = var9 << 4;
               int var13 = var1.getMinBlockZ();

               for (int var14 = 0; var14 < 16; var14++) {
                  for (int var15 = 0; var15 < 16; var15++) {
                     for (int var16 = 0; var16 < 16; var16++) {
                        if (SUSPICIOUS_STONE.contains(var8.getBlockState(var16, var14, var15).getBlock())) {
                           var5.set(var11 + var16, var12 + var14, var13 + var15);
                           if (touchesAmethyst(var5, var2)) {
                              var6++;
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      return var6;
   }

   private static Set<SusChunkFinder.SectionKey> findAmethystSections(Collection<ChunkAccess> var0) {
      HashSet<SusChunkFinder.SectionKey> var1 = new HashSet<>();

      for (ChunkAccess var3 : var0) {
         LevelChunkSection[] var4 = var3.getSections();

         for (int var5 = 0; var5 < var4.length; var5++) {
            LevelChunkSection var6 = var4[var5];
            if (!var6.hasOnlyAir() && var6.maybeHas(var0x -> AMETHYST_BLOCKS.contains(var0x.getBlock()))) {
               var1.add(new SusChunkFinder.SectionKey(var3.getPos().x(), var3.getMinY() / 16 + var5, var3.getPos().z()));
            }
         }
      }

      return var1;
   }

   private static boolean touchesAmethystSection(SusChunkFinder.SectionKey var0, Set<SusChunkFinder.SectionKey> var1) {
      if (var1.contains(var0)) {
         return true;
      } else {
         for (Direction var5 : Direction.values()) {
            SusChunkFinder.SectionKey var6 = new SusChunkFinder.SectionKey(var0.x + var5.getStepX(), var0.y + var5.getStepY(), var0.z + var5.getStepZ());
            if (var1.contains(var6)) {
               return true;
            }
         }

         return false;
      }
   }

   private static boolean touchesAmethyst(BlockPos var0, Map<Long, ChunkAccess> var1) {
      MutableBlockPos var2 = new MutableBlockPos();

      for (Direction var6 : Direction.values()) {
         var2.set(var0).move(var6);
         ChunkAccess var7 = var1.get(ChunkPos.pack(var2.getX() >> 4, var2.getZ() >> 4));
         if (var7 != null && AMETHYST_BLOCKS.contains(var7.getBlockState(var2).getBlock())) {
            return true;
         }
      }

      return false;
   }

   private static boolean hasNoQualifyingHoleNearby(Map<Long, ChunkAccess> var0, int var1, int var2, int var3, Map<Long, Boolean> var4) {
      for (int var12 = -var3; var12 <= var3; var12++) {
         for (int var13 = -var3; var13 <= var3; var13++) {
            int var7 = var1 + var12;
            int var8 = var2 + var13;
            long var9 = ChunkPos.pack(var7, var8);
            if (var0.containsKey(var9)) {
               Boolean var11 = var4.get(var9);
               if (var11 == null) {
                  var11 = chunkHasQualifyingHole(var0, var7, var8);
                  var4.put(var9, var11);
               }

               if (var11) {
                  return false;
               }
            }
         }
      }

      return true;
   }

   private static boolean chunkHasQualifyingHole(Map<Long, ChunkAccess> var0, int var1, int var2) {
      ChunkAccess var3 = var0.get(ChunkPos.pack(var1, var2));
      if (var3 == null) {
         return false;
      } else {
         int var4 = var3.getMinY();
         int var5 = var4 + var3.getHeight() - 1;
         int var6 = var1 << 4;
         int var7 = var2 << 4;
         MutableBlockPos var8 = new MutableBlockPos();

         for (int var9 = var6; var9 < var6 + 16; var9++) {
            for (int var10 = var7; var10 < var7 + 16; var10++) {
               byte var11 = 0;
               byte var12 = 0;
               byte var13 = 0;
               byte var14 = 0;

               for (int var15 = var5; var15 >= var4; var15--) {
                  if (!isAir(var0, var8, var9, var15, var10)) {
                     var14 = 0;
                     var13 = 0;
                     var12 = 0;
                     var11 = 0;
                  } else {
                     var11 = (byte)(isOneWideLayer(var0, var8, var9, var15, var10) ? var11 + 1 : 0);
                     var12 = (byte)(isThreeWideXLayer(var0, var8, var9, var15, var10) ? var12 + 1 : 0);
                     var13 = (byte)(isThreeWideZLayer(var0, var8, var9, var15, var10) ? var13 + 1 : 0);
                     var14 = (byte)(isThreeByThreeLayer(var0, var8, var9, var15, var10) ? var14 + 1 : 0);
                     if (var11 >= 10 || var12 >= 10 || var13 >= 10 || var14 >= 10) {
                        return true;
                     }
                  }
               }
            }
         }

         return false;
      }
   }

   private static boolean isOneWideLayer(Map<Long, ChunkAccess> var0, MutableBlockPos var1, int var2, int var3, int var4) {
      return !isAir(var0, var1, var2 - 1, var3, var4)
         && !isAir(var0, var1, var2 + 1, var3, var4)
         && !isAir(var0, var1, var2, var3, var4 - 1)
         && !isAir(var0, var1, var2, var3, var4 + 1);
   }

   private static boolean isThreeWideXLayer(Map<Long, ChunkAccess> var0, MutableBlockPos var1, int var2, int var3, int var4) {
      if (!isAir(var0, var1, var2 - 1, var3, var4) || !isAir(var0, var1, var2 + 1, var3, var4)) {
         return false;
      } else if (!isAir(var0, var1, var2 - 2, var3, var4) && !isAir(var0, var1, var2 + 2, var3, var4)) {
         for (int var5 = -1; var5 <= 1; var5++) {
            if (isAir(var0, var1, var2 + var5, var3, var4 - 1) || isAir(var0, var1, var2 + var5, var3, var4 + 1)) {
               return false;
            }
         }

         return true;
      } else {
         return false;
      }
   }

   private static boolean isThreeWideZLayer(Map<Long, ChunkAccess> var0, MutableBlockPos var1, int var2, int var3, int var4) {
      if (!isAir(var0, var1, var2, var3, var4 - 1) || !isAir(var0, var1, var2, var3, var4 + 1)) {
         return false;
      } else if (!isAir(var0, var1, var2, var3, var4 - 2) && !isAir(var0, var1, var2, var3, var4 + 2)) {
         for (int var5 = -1; var5 <= 1; var5++) {
            if (isAir(var0, var1, var2 - 1, var3, var4 + var5) || isAir(var0, var1, var2 + 1, var3, var4 + var5)) {
               return false;
            }
         }

         return true;
      } else {
         return false;
      }
   }

   private static boolean isThreeByThreeLayer(Map<Long, ChunkAccess> var0, MutableBlockPos var1, int var2, int var3, int var4) {
      for (int var5 = -1; var5 <= 1; var5++) {
         for (int var6 = -1; var6 <= 1; var6++) {
            if (!isAir(var0, var1, var2 + var5, var3, var4 + var6)) {
               return false;
            }
         }
      }

      int var7 = -2;

      while (var7 <= 2) {
         if (!isAir(var0, var1, var2 + var7, var3, var4 - 2) && !isAir(var0, var1, var2 + var7, var3, var4 + 2)) {
            if (!isAir(var0, var1, var2 - 2, var3, var4 + var7) && !isAir(var0, var1, var2 + 2, var3, var4 + var7)) {
               var7++;
               continue;
            }

            return false;
         }

         return false;
      }

      return true;
   }

   private static boolean isAir(Map<Long, ChunkAccess> var0, MutableBlockPos var1, int var2, int var3, int var4) {
      ChunkAccess var5 = var0.get(ChunkPos.pack(var2 >> 4, var4 >> 4));
      if (var5 == null) {
         return false;
      } else {
         var1.set(var2, var3, var4);
         return var5.getBlockState(var1).isAir();
      }
   }

   private void refreshRenderedPlates() {
      HashSet<Long> var1 = new HashSet<>();

      for (SusChunkFinder.SignalHit var3 : this.scannedHits) {
         var1.add(pack(var3.chunkX, var3.chunkZ));
      }

      this.persistentPlates.addAll(var1);
      this.renderedPlates = this.smartAdjustments.get()
         ? Set.copyOf(SusChunkPlateFilter.findOverlapCores(this.persistentPlates))
         : Set.copyOf(this.persistentPlates);
   }

   private static Set<Long> buildPlateSnapshot(List<SusChunkFinder.SignalHit> var0) {
      HashSet var1 = new HashSet();
      if (var0.isEmpty()) {
         return var1;
      } else {
         var0.sort(Comparator.comparingInt(SusChunkFinder.SignalHit::chunkX).thenComparingInt(SusChunkFinder.SignalHit::chunkZ));

         for (List var3 : buildRegions(var0)) {
            var1.addAll(buildRegion(var3));
         }

         return var1;
      }
   }

   private static List<List<SusChunkFinder.SignalHit>> buildRegions(List<SusChunkFinder.SignalHit> var0) {
      ArrayList var1 = new ArrayList();
      boolean[] var2 = new boolean[var0.size()];

      for (int var3 = 0; var3 < var0.size(); var3++) {
         if (!var2[var3]) {
            SusChunkFinder.SignalHit var4 = var0.get(var3);
            ArrayList var5 = new ArrayList();
            ArrayDeque var6 = new ArrayDeque();
            var5.add(var4);
            var6.add(var3);
            var2[var3] = true;
            int var7 = var4.chunkX;
            int var8 = var4.chunkX;
            int var9 = var4.chunkZ;
            int var10 = var4.chunkZ;

            while (!var6.isEmpty()) {
               SusChunkFinder.SignalHit var11 = var0.get((Integer)var6.poll());

               for (int var12 = 0; var12 < var0.size(); var12++) {
                  if (!var2[var12]) {
                     SusChunkFinder.SignalHit var13 = var0.get(var12);
                     if (chunkDistance(var11, var13) <= 7) {
                        int var14 = Math.min(var7, var13.chunkX);
                        int var15 = Math.max(var8, var13.chunkX);
                        int var16 = Math.min(var9, var13.chunkZ);
                        int var17 = Math.max(var10, var13.chunkZ);
                        if (var15 - var14 <= 256 && var17 - var16 <= 256) {
                           var7 = var14;
                           var8 = var15;
                           var9 = var16;
                           var10 = var17;
                           var2[var12] = true;
                           var5.add(var13);
                           var6.add(var12);
                        }
                     }
                  }
               }
            }

            var1.add(var5);
         }
      }

      return var1;
   }

   private static Set<Long> buildRegion(List<SusChunkFinder.SignalHit> var0) {
      HashSet var1 = new HashSet();
      if (var0.isEmpty()) {
         return var1;
      } else {
         for (SusChunkFinder.SignalHit var3 : var0) {
            var1.add(pack(var3.chunkX, var3.chunkZ));
         }

         if (var0.size() > 1) {
            addBoxyTriangulation(var0, var1);
            return expandSquare(var1);
         } else {
            return var1;
         }
      }
   }

   private static void addBoxyTriangulation(List<SusChunkFinder.SignalHit> var0, Set<Long> var1) {
      boolean[] var2 = new boolean[var0.size()];
      var2[0] = true;

      for (int var3 = 1; var3 < var0.size(); var3++) {
         int var4 = -1;
         int var5 = -1;
         int var6 = Integer.MAX_VALUE;

         for (int var7 = 0; var7 < var0.size(); var7++) {
            if (var2[var7]) {
               SusChunkFinder.SignalHit var8 = var0.get(var7);

               for (int var9 = 0; var9 < var0.size(); var9++) {
                  if (!var2[var9]) {
                     SusChunkFinder.SignalHit var10 = var0.get(var9);
                     int var11 = var8.chunkX - var10.chunkX;
                     int var12 = var8.chunkZ - var10.chunkZ;
                     int var13 = var11 * var11 + var12 * var12;
                     if (var13 < var6) {
                        var6 = var13;
                        var4 = var7;
                        var5 = var9;
                     }
                  }
               }
            }
         }

         if (var4 < 0 || var5 < 0) {
            break;
         }

         addBoxyConnection(var0.get(var4), var0.get(var5), var1);
         var2[var5] = true;
      }
   }

   private static void addBoxyConnection(SusChunkFinder.SignalHit var0, SusChunkFinder.SignalHit var1, Set<Long> var2) {
      int var3 = Math.min(var0.chunkX, var1.chunkX);
      int var4 = Math.max(var0.chunkX, var1.chunkX);
      int var5 = Math.min(var0.chunkZ, var1.chunkZ);
      int var6 = Math.max(var0.chunkZ, var1.chunkZ);

      for (int var7 = var3; var7 <= var4; var7++) {
         for (int var8 = var5; var8 <= var6; var8++) {
            var2.add(pack(var7, var8));
         }
      }
   }

   private static Set<Long> expandSquare(Set<Long> var0) {
      HashSet var1 = new HashSet<>(var0);

      for (long var3 : var0) {
         int var5 = unpackX(var3);
         int var6 = unpackZ(var3);

         for (int var7 = -1; var7 <= 1; var7++) {
            for (int var8 = -1; var8 <= 1; var8++) {
               var1.add(pack(var5 + var7, var6 + var8));
            }
         }
      }

      return var1;
   }

   private static int chunkDistance(SusChunkFinder.SignalHit var0, SusChunkFinder.SignalHit var1) {
      return Math.max(Math.abs(var0.chunkX - var1.chunkX), Math.abs(var0.chunkZ - var1.chunkZ));
   }

   private static long pack(int var0, int var1) {
      return var0 & 4294967295L | (var1 & 4294967295L) << 32;
   }

   private static int unpackX(long var0) {
      return (int)var0;
   }

   private static int unpackZ(long var0) {
      return (int)(var0 >> 32);
   }

   @EventHandler
   private void onRender(Render3DEvent var1) {
      SettingColor currentColor = this.markerColor.get();
      int alpha = this.flagOpacity.get();
      if (currentColor.a != alpha) {
         this.markerColor.set(new SettingColor(currentColor.r, currentColor.g, currentColor.b, alpha, currentColor.rainbow));
      }

      ShapeMode var3 = switch ((SusChunkFinder.MarkerMode)this.markerMode.get()) {
         case Filled -> ShapeMode.Sides;
         case Outline -> ShapeMode.Lines;
         case Both -> ShapeMode.Both;
      };
      if (this.mc.player != null) {
         int var4 = this.mc.player.blockPosition().getX() >> 4;
         int var5 = this.mc.player.blockPosition().getZ() >> 4;
         this.renderPlates(var1, this.renderedPlates, (SettingColor)this.markerColor.get(), var3, var4, var5, 0);
         this.renderXpFlags(var1, var4, var5);
      }
   }

   private void renderXpFlags(Render3DEvent var1, int var2, int var3) {
      double var4 = ((Integer)this.markerY.get()).intValue() + 0.08;
      int var6 = 0;
      int alpha = (Integer)this.flagOpacity.get();
      Color var7 = new Color(255, 165, 55, Math.min(52, alpha));
      Color line = new Color(255, 201, 119, Math.min(230, alpha * 3));

      for (long var9 : this.xpFlags) {
         if (var6 >= 6000) {
            break;
         }

         int var11 = unpackX(var9);
         int var12 = unpackZ(var9);
         if (Math.abs(var11 - var2) <= 128 && Math.abs(var12 - var3) <= 128) {
            double var13 = (var11 << 4) + 0.38;
            double var15 = (var12 << 4) + 0.38;
            if (this.markerShape.get() != SusChunkFinder.MarkerShape.Pillar) {
               var1.renderer.box(var13, var4, var15, var13 + 15.24, var4 + 0.035, var15 + 15.24, var7, line, ShapeMode.Both, 0);
            }

            if (this.markerShape.get() != SusChunkFinder.MarkerShape.Plate) {
               double centerX = var13 + 7.62;
               double centerZ = var15 + 7.62;
               var1.renderer
                  .box(
                     centerX - 0.38,
                     var4,
                     centerZ - 0.38,
                     centerX + 0.38,
                     320.0,
                     centerZ + 0.38,
                     new Color(var7.r, var7.g, var7.b, Math.min(24, var7.a)),
                     line,
                     ShapeMode.Both,
                     0
                  );
            }

            var6++;
         }
      }
   }

   private int renderPlates(Render3DEvent var1, Set<Long> var2, SettingColor var3, ShapeMode var4, int var5, int var6, int var7) {
      int var8 = var7;
      double var9 = ((Integer)this.markerY.get()).intValue();
      double opacity = 0.72;
      Color side = new Color(var3.r, var3.g, var3.b, (int)(Math.min(48, var3.a) * opacity));
      Color line = new Color(Math.min(255, var3.r + 34), Math.min(255, var3.g + 34), Math.min(255, var3.b + 34), (int)(Math.max(92, var3.a) * opacity));

      for (long var12 : var2) {
         if (var8 >= 6000) {
            break;
         }

         int var14 = unpackX(var12);
         int var15 = unpackZ(var12);
         if (Math.abs(var14 - var5) <= 128 && Math.abs(var15 - var6) <= 128) {
            double var16 = (var14 << 4) + 0.38;
            double var18 = (var15 << 4) + 0.38;
            if (this.markerShape.get() != SusChunkFinder.MarkerShape.Pillar) {
               var1.renderer.box(var16, var9, var18, var16 + 15.24, var9 + 0.035, var18 + 15.24, side, line, var4, 0);
               var1.renderer
                  .box(
                     var16 + 0.28,
                     var9 + 0.04,
                     var18 + 0.28,
                     var16 + 14.96,
                     var9 + 0.045,
                     var18 + 14.96,
                     new Color(side.r, side.g, side.b, 0),
                     new Color(line.r, line.g, line.b, Math.max(16, line.a / 3)),
                     ShapeMode.Lines,
                     0
                  );
            }

            if (this.markerShape.get() != SusChunkFinder.MarkerShape.Plate) {
               double centerX = var16 + 7.62;
               double centerZ = var18 + 7.62;
               var1.renderer
                  .box(
                     centerX - 0.36,
                     var9,
                     centerZ - 0.36,
                     centerX + 0.36,
                     320.0,
                     centerZ + 0.36,
                     new Color(side.r, side.g, side.b, Math.min(24, side.a)),
                     line,
                     ShapeMode.Both,
                     0
                  );
            }

            var8++;
         }
      }

      return var8;
   }

   public String getInfoString() {
      return Integer.toString(this.detections.size());
   }

   private record ChunkDetection(ChunkPos chunk, int amethystCount, int fullBeehiveCount) {
   }

   public static enum MarkerMode {
      Filled,
      Outline,
      Both;

      private static SusChunkFinder.MarkerMode[] $values() {
         return new SusChunkFinder.MarkerMode[]{Filled, Outline, Both};
      }
   }

   public static enum MarkerShape {
      Plate,
      Pillar,
      Both;
   }

   private record ScanConfig(boolean amethystEnabled, int minimumStone, boolean fullBeehiveEnabled, boolean suschunkEnabled, int suschunkRange) {
   }

   private record ScanResult(List<SusChunkFinder.ChunkDetection> detections, List<SusChunkFinder.SignalHit> hits, Set<Long> xpFlags) {
   }

   private record SectionKey(int x, int y, int z) {
   }

   private record SignalHit(int chunkX, int chunkZ) {
   }
}
