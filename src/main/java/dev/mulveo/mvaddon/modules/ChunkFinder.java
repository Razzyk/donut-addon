package dev.mulveo.mvaddon.modules;

import dev.mulveo.mvaddon.MVAddon;
import dev.mulveo.mvaddon.toast.MVToast;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.Predicate;
import dev.donut.addon.mv.compat.events.game.GameJoinedEvent;
import dev.donut.addon.mv.compat.events.game.GameLeftEvent;
import dev.donut.addon.mv.compat.events.render.Render3DEvent;
import dev.donut.addon.mv.compat.events.world.ChunkDataEvent;
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
import dev.donut.addon.mv.compat.utils.player.ChatUtils;
import dev.donut.addon.mv.compat.utils.render.color.Color;
import dev.donut.addon.mv.compat.utils.render.color.SettingColor;
import dev.donut.addon.mv.compat.events.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class ChunkFinder extends Module {
   private static final double PLATE_THICKNESS = 0.025;
   private static final double PLATE_Y = 60.0;
   private static final int MAX_HOLE_Y = 54;
   private static final int FEATURE_SCAN_CHUNKS_PER_TICK = 8;
   private static final int FAST_TRAVEL_SCAN_CHUNKS_PER_TICK = 16;
   private static final int CHUNK_DISCOVERY_INTERVAL_TICKS = 2;
   private static final int MAX_CHUNK_DISTANCE = 32;
   private static final int SOLID_ENCLOSURE_RADIUS = 5;
   private static final int GEODE_SCAN_MIN_Y = -64;
   private static final int GEODE_SCAN_MAX_Y = 80;
   private static final int GEODE_SHELL_RADIUS = 20;
   private static final int GEODE_AMETHYST_RADIUS = 14;
   private static final int GEODE_MIN_SEED_CALCITE = 4;
   private static final int GEODE_MIN_SEED_BASALT = 4;
   private static final int GEODE_MIN_CALCITE = 30;
   private static final int GEODE_MIN_BASALT = 20;
   private static final Predicate<BlockState> EXACT_AIR = state -> state.is(Blocks.AIR);
   private static final Predicate<BlockState> GEODE_SHELL = state -> state.is(Blocks.CALCITE) || state.is(Blocks.SMOOTH_BASALT);
   private static final Predicate<BlockState> VEIN_BLOCKS = state -> isVeinBlock(state.getBlock());
   private static final int[] VEIN_DX = new int[]{-1, 1, 0, 0, 0, 0};
   private static final int[] VEIN_DY = new int[]{0, 0, -1, 1, 0, 0};
   private static final int[] VEIN_DZ = new int[]{0, 0, 0, 0, -1, 1};
   private final SettingGroup sgTargets = this.settings.getDefaultGroup();
   private final SettingGroup sgRender = this.settings.createGroup("Plate");
   private final Setting<Boolean> armadillos = this.sgTargets
      .add(
         new BoolSetting.Builder().name("armadillos").description("Flags chunks containing enough armadillos.").defaultValue(true).build()
      );
   private final Setting<Integer> armadilloThreshold = this.sgTargets
      .add(
         new IntSetting.Builder()
                        .name("armadillo-threshold")
                     .description("Armadillos required in one chunk before it is flagged.")
                  .defaultValue(1)
               .range(1, 30)
               .sliderRange(1, 30)
               .visible(this.armadillos::get)
            .build()
      );
   private final Setting<Boolean> hostileMobs = this.sgTargets
      .add(
         new BoolSetting.Builder().name("hostile-mobs").description("Flags chunks containing enough hostile mobs.").defaultValue(true)
            .build()
      );
   private final Setting<Integer> hostileThreshold = this.sgTargets
      .add(
         new IntSetting.Builder()
                        .name("hostile-threshold")
                     .description("Hostile mobs required in one chunk before it is flagged.")
                  .defaultValue(6)
               .range(1, 30)
               .sliderRange(1, 30)
               .visible(this.hostileMobs::get)
            .build()
      );
   private final Setting<Boolean> oneByOneHoles = this.sgTargets
      .add(
         new BoolSetting.Builder().name("1x1-holes")
                  .description("Flags exact minecraft:air 1x1x1 pockets enclosed on all six sides below Y 55.")
               .defaultValue(true)
            .build()
      );
   private final Setting<Integer> holeThreshold = this.sgTargets
      .add(
         new IntSetting.Builder()
                        .name("hole-threshold")
                     .description("Separate 1x1 holes required in one chunk before it is flagged.")
                  .defaultValue(1)
               .range(1, 30)
               .sliderRange(1, 30)
               .visible(this.oneByOneHoles::get)
            .build()
      );
   private final Setting<Boolean> depletedGeodes = this.sgTargets
      .add(
         new BoolSetting.Builder().name("depleted-geodes")
                  .description("Flags mined-out geodes whose calcite and smooth-basalt shell remains but all amethyst is gone.")
               .defaultValue(true)
            .build()
      );
   private final Setting<Boolean> veins = this.sgTargets
      .add(
         new BoolSetting.Builder().name("veins")
                  .description("Flags enclosed horizontal or vertical dirt, andesite, gravel, and diorite lines.")
               .defaultValue(true)
            .build()
      );
   private final Setting<Integer> minVeinLength = this.sgTargets
      .add(
         new IntSetting.Builder()
                        .name("min-vien-length")
                     .description("Minimum number of blocks in an enclosed artificial line.")
                  .defaultValue(3)
               .range(3, 32)
               .sliderRange(3, 16)
               .visible(this.veins::get)
            .build()
      );
   private final Setting<Boolean> renderPlate = this.sgRender
      .add(
         new BoolSetting.Builder().name("render-plate").description("Draws a plate across every flagged chunk.").defaultValue(true)
            .build()
      );
   private final Setting<ChunkFinder.PlateStyle> plateStyle = this.sgRender
      .add(
         new EnumSetting.Builder<ChunkFinder.PlateStyle>()
                        .name("plate-style")
                     .description("Matte is the clean default; Locator renders a compact centered marker.")
                  .defaultValue(ChunkFinder.PlateStyle.Matte)
               .visible(this.renderPlate::get)
            .build()
      );
   private final Setting<ChunkFinder.MarkerShape> markerShape = this.sgRender
      .add(
         new EnumSetting.Builder<ChunkFinder.MarkerShape>()
                        .name("marker-shape")
                     .description("Renders a plate, a vertical pillar, or both.")
                  .defaultValue(ChunkFinder.MarkerShape.Plate)
               .visible(this.renderPlate::get)
            .build()
      );
   private final Setting<SettingColor> plateColor = this.sgRender
      .add(
         new ColorSetting.Builder()
                     .name("plate-color")
                  .description("Color of the flagged chunk plate.")
               .defaultValue(new SettingColor(156, 72, 255, 72))
               .visible(this.renderPlate::get)
            .build()
      );
   private final Setting<Boolean> distanceFade = this.sgRender
      .add(
         new BoolSetting.Builder().name("distance-fade")
                     .description("Softly fades distant plates instead of letting them crowd the horizon.")
                  .defaultValue(true)
               .visible(this.renderPlate::get)
            .build()
      );
   private final Setting<Integer> fadeStart = this.sgRender
      .add(
         new IntSetting.Builder()
                        .name("fade-start")
                     .description("Chunk distance where plates begin fading.")
                  .defaultValue(10)
               .range(1, 31)
               .sliderRange(1, 31)
               .visible(() -> this.renderPlate.get() && this.distanceFade.get())
            .build()
      );
   private final Setting<Boolean> colorByReason = this.sgRender
      .add(
         new BoolSetting.Builder().name("reason-colors")
                     .description("Uses a separate clean color for the reason that caused each flag.")
                  .defaultValue(false)
               .visible(this.renderPlate::get)
            .build()
      );
   private final Setting<SettingColor> holeColor = this.reasonColor("hole-color", 93, 196, 214);
   private final Setting<SettingColor> mobColor = this.reasonColor("mob-color", 218, 92, 96);
   private final Setting<SettingColor> armadilloColor = this.reasonColor("armadillo-color", 218, 174, 92);
   private final Setting<SettingColor> geodeColor = this.reasonColor("geode-color", 164, 111, 224);
   private final Setting<SettingColor> veinColor = this.reasonColor("vein-color", 221, 151, 88);
   private final Map<Long, ChunkFinder.Detection> detections = new LinkedHashMap<>();
   private final Map<Long, ChunkFinder.HoleData> holesByChunk = new HashMap<>();
   private final Map<Long, ChunkFinder.GeodeData> depletedGeodesBySourceChunk = new HashMap<>();
   private final Map<Long, ChunkFinder.VeinData> veinsByChunk = new HashMap<>();
   private final ArrayDeque<Long> pendingFeatureScans = new ArrayDeque<>();
   private final Set<Long> queuedFeatureScans = new HashSet<>();
   private final Set<Long> completedFeatureScans = new HashSet<>();
   private final Set<Long> knownLoadedChunks = new HashSet<>();
   private int chunkDiscoveryTimer;
   private int rescanTimer;
   private boolean previousHoleScanning;
   private boolean previousGeodeScanning;
   private boolean previousVeinScanning;

   public ChunkFinder() {
      super(MVAddon.CATEGORY, "Chunk Finder", "Finds chunks with armadillos, hostile mobs, narrow holes, depleted geodes, or ore veins.");
   }

   public void onActivate() {
      this.detections.clear();
      this.holesByChunk.clear();
      this.depletedGeodesBySourceChunk.clear();
      this.veinsByChunk.clear();
      this.pendingFeatureScans.clear();
      this.queuedFeatureScans.clear();
      this.completedFeatureScans.clear();
      this.knownLoadedChunks.clear();
      this.chunkDiscoveryTimer = 0;
      this.rescanTimer = 0;
      this.previousHoleScanning = (Boolean)this.oneByOneHoles.get();
      this.previousGeodeScanning = (Boolean)this.depletedGeodes.get();
      this.previousVeinScanning = (Boolean)this.veins.get();
      if (this.mc.level != null) {
         for (ChunkAccess chunk : Utils.chunks()) {
            this.knownLoadedChunks.add(chunk.getPos().pack());
            this.scheduleFeatureScan(chunk);
         }
      }
   }

   public void onDeactivate() {
      this.detections.clear();
      this.holesByChunk.clear();
      this.depletedGeodesBySourceChunk.clear();
      this.veinsByChunk.clear();
      this.pendingFeatureScans.clear();
      this.queuedFeatureScans.clear();
      this.completedFeatureScans.clear();
      this.knownLoadedChunks.clear();
      this.chunkDiscoveryTimer = 0;
      this.rescanTimer = 0;
   }

   @EventHandler
   private void onGameJoined(GameJoinedEvent event) {
      this.detections.clear();
      this.holesByChunk.clear();
      this.depletedGeodesBySourceChunk.clear();
      this.veinsByChunk.clear();
      this.pendingFeatureScans.clear();
      this.queuedFeatureScans.clear();
      this.completedFeatureScans.clear();
      this.knownLoadedChunks.clear();
      this.chunkDiscoveryTimer = 0;
      this.rescanTimer = 0;
   }

   @EventHandler
   private void onGameLeft(GameLeftEvent event) {
      this.detections.clear();
      this.holesByChunk.clear();
      this.depletedGeodesBySourceChunk.clear();
      this.veinsByChunk.clear();
      this.pendingFeatureScans.clear();
      this.queuedFeatureScans.clear();
      this.completedFeatureScans.clear();
      this.knownLoadedChunks.clear();
      this.chunkDiscoveryTimer = 0;
      this.rescanTimer = 0;
   }

   @EventHandler
   private void onChunkData(ChunkDataEvent event) {
      ChunkPos center = event.chunk().getPos();

      for (int x = center.x() - 2; x <= center.x() + 2; x++) {
         for (int z = center.z() - 2; z <= center.z() + 2; z++) {
            ChunkAccess loaded = this.mc.level == null ? null : this.mc.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (loaded != null) {
               this.knownLoadedChunks.add(loaded.getPos().pack());
               this.scheduleFeatureScan(loaded, true);
            }
         }
      }
   }

   @EventHandler
   private void onTick(Post event) {
      if (this.mc.level != null
         && this.mc.player != null
         && (
            (Boolean)this.armadillos.get()
               || (Boolean)this.hostileMobs.get()
               || (Boolean)this.oneByOneHoles.get()
               || (Boolean)this.depletedGeodes.get()
               || (Boolean)this.veins.get()
         )) {
         this.refreshFeatureSettings();
         this.discoverLoadedChunks();
         if ((Boolean)this.oneByOneHoles.get() || (Boolean)this.depletedGeodes.get() || (Boolean)this.veins.get()) {
            this.processFeatureScans();
         }

         Map<Long, ChunkFinder.Counts> countsByChunk = new HashMap<>();
         if ((Boolean)this.oneByOneHoles.get()) {
            for (Entry<Long, ChunkFinder.HoleData> entry : this.holesByChunk.entrySet()) {
               int chunkX = ChunkPos.getX(entry.getKey());
               int chunkZ = ChunkPos.getZ(entry.getKey());
               if (this.isWithinChunkRange(chunkX, chunkZ)) {
                  ChunkFinder.Counts counts = new ChunkFinder.Counts(chunkX, chunkZ);
                  counts.holes = entry.getValue().count();
                  counts.y = entry.getValue().highestY();
                  counts.holeX = entry.getValue().firstX();
                  counts.holeY = entry.getValue().firstY();
                  counts.holeZ = entry.getValue().firstZ();
                  countsByChunk.put(entry.getKey(), counts);
               }
            }
         }

         if ((Boolean)this.depletedGeodes.get()) {
            for (ChunkFinder.GeodeData geode : this.depletedGeodesBySourceChunk.values()) {
               ChunkPos chunk = new ChunkPos(geode.x() >> 4, geode.z() >> 4);
               if (this.isWithinChunkRange(chunk.x(), chunk.z())) {
                  ChunkFinder.Counts counts = countsByChunk.computeIfAbsent(chunk.pack(), ignored -> new ChunkFinder.Counts(chunk.x(), chunk.z()));
                  counts.depletedGeodes++;
                  counts.geodeX = geode.x();
                  counts.geodeY = geode.y();
                  counts.geodeZ = geode.z();
                  counts.y = Math.max(counts.y, geode.y());
               }
            }
         }

         if ((Boolean)this.veins.get()) {
            for (Entry<Long, ChunkFinder.VeinData> entryx : this.veinsByChunk.entrySet()) {
               int chunkX = ChunkPos.getX(entryx.getKey());
               int chunkZ = ChunkPos.getZ(entryx.getKey());
               if (this.isWithinChunkRange(chunkX, chunkZ)) {
                  ChunkFinder.Counts counts = countsByChunk.computeIfAbsent(entryx.getKey(), ignored -> new ChunkFinder.Counts(chunkX, chunkZ));
                  ChunkFinder.VeinData vein = entryx.getValue();
                  counts.veinLength = vein.length();
                  counts.veinX = vein.x();
                  counts.veinY = vein.y();
                  counts.veinZ = vein.z();
                  counts.y = Math.max(counts.y, vein.y());
               }
            }
         }

         for (Entity entity : this.mc.level.entitiesForRendering()) {
            boolean isArmadillo = (Boolean)this.armadillos.get() && entity instanceof Armadillo;
            boolean isHostile = (Boolean)this.hostileMobs.get() && entity.getType().getCategory() == MobCategory.MONSTER;
            if ((isArmadillo || isHostile) && !this.isRejectedY(entity.getBlockY())) {
               ChunkPos chunk = entity.chunkPosition();
               if (this.isWithinChunkRange(chunk.x(), chunk.z())) {
                  ChunkFinder.Counts counts = countsByChunk.computeIfAbsent(chunk.pack(), ignored -> new ChunkFinder.Counts(chunk.x(), chunk.z()));
                  if (isArmadillo) {
                     counts.armadillos++;
                  }

                  if (isHostile) {
                     counts.hostiles++;
                  }

                  counts.y = Math.max(counts.y, entity.getBlockY());
               }
            }
         }

         for (Entry<Long, ChunkFinder.Counts> entryxx : countsByChunk.entrySet()) {
            ChunkFinder.Counts countsx = entryxx.getValue();
            boolean armadilloMatch = (Boolean)this.armadillos.get() && countsx.armadillos >= (Integer)this.armadilloThreshold.get();
            boolean hostileMatch = (Boolean)this.hostileMobs.get() && countsx.hostiles >= (Integer)this.hostileThreshold.get();
            boolean holeMatch = (Boolean)this.oneByOneHoles.get() && countsx.holes >= (Integer)this.holeThreshold.get();
            boolean geodeMatch = (Boolean)this.depletedGeodes.get() && countsx.depletedGeodes > 0;
            boolean veinMatch = (Boolean)this.veins.get() && countsx.veinLength >= (Integer)this.minVeinLength.get();
            if (armadilloMatch || hostileMatch || holeMatch || geodeMatch || veinMatch) {
               ChunkFinder.Detection existing = this.detections.get(entryxx.getKey());
               if (existing != null) {
                  existing.y = Math.max(existing.y, countsx.y);
                  existing.armadillos = Math.max(existing.armadillos, countsx.armadillos);
                  existing.hostiles = Math.max(existing.hostiles, countsx.hostiles);
                  existing.holes = Math.max(existing.holes, countsx.holes);
                  existing.depletedGeodes = Math.max(existing.depletedGeodes, countsx.depletedGeodes);
                  existing.veinLength = Math.max(existing.veinLength, countsx.veinLength);
               } else {
                  int alertX = geodeMatch ? countsx.geodeX : (veinMatch ? countsx.veinX : (holeMatch ? countsx.holeX : (countsx.chunkX << 4) + 8));
                  int alertY = geodeMatch ? countsx.geodeY : (veinMatch ? countsx.veinY : (holeMatch ? countsx.holeY : countsx.y));
                  int alertZ = geodeMatch ? countsx.geodeZ : (veinMatch ? countsx.veinZ : (holeMatch ? countsx.holeZ : (countsx.chunkZ << 4) + 8));
                  ChunkFinder.Detection detection = new ChunkFinder.Detection(
                     countsx.chunkX,
                     countsx.chunkZ,
                     countsx.y,
                     countsx.armadillos,
                     countsx.hostiles,
                     countsx.holes,
                     countsx.depletedGeodes,
                     countsx.veinLength,
                     alertX,
                     alertY,
                     alertZ
                  );
                  this.detections.put(entryxx.getKey(), detection);
                  this.alert(detection, this.reasons(armadilloMatch, hostileMatch, holeMatch, geodeMatch, veinMatch));
               }
            }
         }

         this.detections.keySet().removeIf(key -> !this.isWithinChunkRange(ChunkPos.getX(key), ChunkPos.getZ(key)));
      }
   }

   @EventHandler
   private void onRender(Render3DEvent event) {
      if (this.renderPlate.get() && this.mc.player != null) {
         double opacity = 0.72;
         int playerChunkX = this.mc.player.chunkPosition().x();
         int playerChunkZ = this.mc.player.chunkPosition().z();
         double globalInset = 0.1;

         for (ChunkFinder.Detection detection : this.detections.values()) {
            if (this.isWithinChunkRange(detection.chunkX, detection.chunkZ)) {
               SettingColor selected = this.detectionColor(detection);
               int distance = Math.max(Math.abs(detection.chunkX - playerChunkX), Math.abs(detection.chunkZ - playerChunkZ));
               double fade = this.distanceFade.get() && distance > this.fadeStart.get()
                  ? Math.max(0.14, 1.0 - (double)(distance - (Integer)this.fadeStart.get()) / Math.max(1, 32 - (Integer)this.fadeStart.get()))
                  : 1.0;

               int baseSideAlpha = switch ((ChunkFinder.PlateStyle)this.plateStyle.get()) {
                  case Matte -> Math.min(46, selected.a);
                  case Glass -> Math.min(28, selected.a);
                  case Outline -> 0;
                  case Locator -> Math.min(62, selected.a);
               };

               int baseLineAlpha = switch ((ChunkFinder.PlateStyle)this.plateStyle.get()) {
                  case Matte -> 142;
                  case Glass -> 185;
                  case Outline -> 220;
                  case Locator -> 210;
               };
               int sideAlpha = (int)Math.round(baseSideAlpha * opacity * fade);
               int lineAlpha = (int)Math.round(baseLineAlpha * opacity * fade);
               Color sides = new Color(selected.r, selected.g, selected.b, sideAlpha);
               Color lines = new Color(Math.min(255, selected.r + 34), Math.min(255, selected.g + 34), Math.min(255, selected.b + 34), lineAlpha);
               ShapeMode mode = this.plateStyle.get() == ChunkFinder.PlateStyle.Outline ? ShapeMode.Lines : ShapeMode.Both;
               double inset = this.plateStyle.get() == ChunkFinder.PlateStyle.Locator ? 5.25 : Math.max(0.22, globalInset);
               double x = (detection.chunkX << 4) + inset;
               double z = (detection.chunkZ << 4) + inset;
               if (this.markerShape.get() != ChunkFinder.MarkerShape.Pillar) {
                  event.renderer.box(x, 60.0, z, x + 16.0 - inset * 2.0, 60.035, z + 16.0 - inset * 2.0, sides, lines, mode, 0);
                  double inner = Math.max(0.45, inset + 0.32);
                  event.renderer
                     .box(
                        (detection.chunkX << 4) + inner,
                        60.04,
                        (detection.chunkZ << 4) + inner,
                        (detection.chunkX << 4) + 16.0 - inner,
                        60.045,
                        (detection.chunkZ << 4) + 16.0 - inner,
                        new Color(sides.r, sides.g, sides.b, 0),
                        new Color(lines.r, lines.g, lines.b, Math.max(18, lines.a / 3)),
                        ShapeMode.Lines,
                        0
                     );
               }

               if (this.markerShape.get() != ChunkFinder.MarkerShape.Plate) {
                  double centerX = (detection.chunkX << 4) + 8.0;
                  double centerZ = (detection.chunkZ << 4) + 8.0;
                  event.renderer
                     .box(
                        centerX - 0.36,
                        60.0,
                        centerZ - 0.36,
                        centerX + 0.36,
                        320.0,
                        centerZ + 0.36,
                        new Color(sides.r, sides.g, sides.b, Math.min(26, sides.a)),
                        lines,
                        ShapeMode.Both,
                        0
                     );
               }
            }
         }
      }
   }

   private Setting<SettingColor> reasonColor(String name, int r, int g, int b) {
      return this.sgRender
         .add(
            new ColorSetting.Builder()
                        .name(name)
                     .description("Color used for this detection reason.")
                  .defaultValue(new SettingColor(r, g, b, 72))
                  .visible(() -> this.renderPlate.get() && this.colorByReason.get())
               .build()
         );
   }

   private SettingColor detectionColor(ChunkFinder.Detection detection) {
      if (!this.colorByReason.get()) {
         return (SettingColor)this.plateColor.get();
      } else if (detection.depletedGeodes > 0) {
         return (SettingColor)this.geodeColor.get();
      } else if (detection.holes > 0) {
         return (SettingColor)this.holeColor.get();
      } else if (detection.veinLength > 0) {
         return (SettingColor)this.veinColor.get();
      } else if (detection.armadillos > 0) {
         return (SettingColor)this.armadilloColor.get();
      } else {
         return detection.hostiles > 0 ? (SettingColor)this.mobColor.get() : (SettingColor)this.plateColor.get();
      }
   }

   public String getInfoString() {
      return Integer.toString(this.detections.size());
   }

   private void alert(ChunkFinder.Detection detection, String reason) {
      this.mc.gui.toastManager().addToast(MVToast.chunkAlert("Chunk Located", detection.alertX, detection.alertY, detection.alertZ));

      ChatUtils.sendMsg(
         Component.literal(
            "§8[§5Chunk Finder§8] §7Flag at §f" + detection.alertX + ", " + detection.alertY + ", " + detection.alertZ + " §8— §7Reason: §f" + reason
         )
      );
   }

   private String reasons(boolean armadilloMatch, boolean hostileMatch, boolean holeMatch, boolean geodeMatch, boolean veinMatch) {
      ArrayList<String> reasons = new ArrayList<>(5);
      if (holeMatch) {
         reasons.add("hole");
      }

      if (hostileMatch) {
         reasons.add("mobs");
      }

      if (armadilloMatch) {
         reasons.add("armadillos");
      }

      if (geodeMatch) {
         reasons.add("depletedgeode");
      }

      if (veinMatch) {
         reasons.add("vein");
      }

      return String.join(", ", reasons);
   }

   private void scheduleFeatureScan(ChunkAccess chunk) {
      this.scheduleFeatureScan(chunk, false);
   }

   private void scheduleFeatureScan(ChunkAccess chunk, boolean priority) {
      long key = chunk.getPos().pack();
      if (priority) {
         this.completedFeatureScans.remove(key);
      }

      if (!this.completedFeatureScans.contains(key) && this.queuedFeatureScans.add(key)) {
         if (priority) {
            this.pendingFeatureScans.addFirst(key);
         } else {
            this.pendingFeatureScans.addLast(key);
         }
      }
   }

   private void refreshFeatureSettings() {
      boolean holesEnabled = (Boolean)this.oneByOneHoles.get();
      boolean geodesEnabled = (Boolean)this.depletedGeodes.get();
      boolean veinsEnabled = (Boolean)this.veins.get();
      if (holesEnabled != this.previousHoleScanning || geodesEnabled != this.previousGeodeScanning || veinsEnabled != this.previousVeinScanning) {
         this.previousHoleScanning = holesEnabled;
         this.previousGeodeScanning = geodesEnabled;
         this.previousVeinScanning = veinsEnabled;
         this.completedFeatureScans.clear();
         this.pendingFeatureScans.clear();
         this.queuedFeatureScans.clear();
         if (!holesEnabled) {
            this.holesByChunk.clear();
         }

         if (!geodesEnabled) {
            this.depletedGeodesBySourceChunk.clear();
         }

         if (!veinsEnabled) {
            this.veinsByChunk.clear();
         }

         for (ChunkAccess chunk : Utils.chunks()) {
            this.scheduleFeatureScan(chunk);
         }
      }
   }

   private void discoverLoadedChunks() {
      if (++this.chunkDiscoveryTimer >= 2) {
         this.chunkDiscoveryTimer = 0;
         Set<Long> loadedNow = new HashSet<>();

         for (ChunkAccess chunk : Utils.chunks()) {
            ChunkPos pos = chunk.getPos();
            if (this.isWithinChunkRange(pos.x(), pos.z())) {
               long key = pos.pack();
               loadedNow.add(key);
               if (this.knownLoadedChunks.add(key)) {
                  this.scheduleFeatureScan(chunk, true);
               }
            }
         }

         this.knownLoadedChunks.retainAll(loadedNow);
         this.completedFeatureScans.retainAll(loadedNow);
         this.holesByChunk.keySet().removeIf(keyx -> !loadedNow.contains(keyx));
         this.depletedGeodesBySourceChunk.keySet().removeIf(keyx -> !loadedNow.contains(keyx));
         this.veinsByChunk.keySet().removeIf(keyx -> !loadedNow.contains(keyx));
         if (++this.rescanTimer >= 50) {
            this.rescanTimer = 0;

            for (ChunkAccess chunkx : Utils.chunks()) {
               this.scheduleFeatureScan(chunkx, true);
            }
         }
      }
   }

   private void processFeatureScans() {
      int scanBudget = this.mc.player != null && this.mc.player.getDeltaMovement().horizontalDistance() >= 0.8 ? 16 : 8;

      for (int scanned = 0; scanned < scanBudget && !this.pendingFeatureScans.isEmpty(); scanned++) {
         long key = this.pendingFeatureScans.removeFirst();
         this.queuedFeatureScans.remove(key);
         ChunkAccess chunk = this.mc.level.getChunkSource().getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), ChunkStatus.FULL, false);
         if (chunk != null) {
            boolean complete = true;
            if ((Boolean)this.oneByOneHoles.get()) {
               complete = this.scanHoles(chunk);
            }

            if ((Boolean)this.depletedGeodes.get()) {
               complete &= this.scanDepletedGeode(chunk);
            }

            if ((Boolean)this.veins.get()) {
               complete &= this.scanVeins(chunk);
            }

            if (complete) {
               this.completedFeatureScans.add(key);
            }
         }
      }
   }

   private boolean scanVeins(ChunkAccess chunk) {
      if (this.mc.level == null) {
         return false;
      } else {
         ChunkPos chunkPos = chunk.getPos();
         if (!this.hasLoadedNeighbours(chunkPos, 2)) {
            return false;
         } else {
            int bottomY = Math.max(16, this.mc.level.getMinY());
            int topY = Math.min(45, this.mc.level.getMaxY() - 1);
            int minimum = Math.max(3, (Integer)this.minVeinLength.get());
            boolean[] candidateSections = this.matchingSections(chunk, VEIN_BLOCKS);
            Set<Long> visited = new HashSet<>();
            MutableBlockPos pos = new MutableBlockPos();
            int best = 0;
            int bestX = chunkPos.getMinBlockX() + 8;
            int bestY = bottomY;
            int bestZ = chunkPos.getMinBlockZ() + 8;

            for (int localX = 0; localX < 16; localX++) {
               for (int localZ = 0; localZ < 16; localZ++) {
                  for (int y = bottomY; y <= topY; y++) {
                     int sectionIndex = this.mc.level.getSectionIndex(y);
                     if (sectionIndex >= 0 && sectionIndex < candidateSections.length && candidateSections[sectionIndex]) {
                        int worldX = chunkPos.getMinBlockX() + localX;
                        int worldZ = chunkPos.getMinBlockZ() + localZ;
                        pos.set(worldX, y, worldZ);
                        Block block = chunk.getBlockState(pos).getBlock();
                        if (isVeinBlock(block)) {
                           long packed = pos.asLong();
                           if (!visited.contains(packed)) {
                              ChunkFinder.PathComponent component = this.collectPathComponent(chunkPos, worldX, y, worldZ, block, bottomY, topY, visited);
                              if (component.complete()
                                 && component.blocks().size() >= minimum
                                 && component.blocks().size() > best
                                 && this.hasArtificialPathSignature(component.blocks())) {
                                 best = component.blocks().size();
                                 bestX = worldX;
                                 bestY = y;
                                 bestZ = worldZ;
                              }
                           }
                        }
                     }
                  }
               }
            }

            long key = chunkPos.pack();
            if (best >= minimum) {
               this.veinsByChunk.put(key, new ChunkFinder.VeinData(best, bestX, bestY, bestZ));
            } else {
               this.veinsByChunk.remove(key);
            }

            return true;
         }
      }
   }

   private ChunkFinder.PathComponent collectPathComponent(
      ChunkPos source, int startX, int startY, int startZ, Block block, int bottomY, int topY, Set<Long> visited
   ) {
      int minX = source.getMinBlockX() - 32;
      int maxX = source.getMinBlockX() + 47;
      int minZ = source.getMinBlockZ() - 32;
      int maxZ = source.getMinBlockZ() + 47;
      ArrayDeque<Long> queue = new ArrayDeque<>();
      List<Long> blocks = new ArrayList<>();
      MutableBlockPos cursor = new MutableBlockPos();
      long start = BlockPos.asLong(startX, startY, startZ);
      visited.add(start);
      queue.add(start);
      boolean complete = true;

      while (!queue.isEmpty()) {
         long current = queue.removeFirst();
         blocks.add(current);
         int x = BlockPos.getX(current);
         int y = BlockPos.getY(current);
         int z = BlockPos.getZ(current);

         for (int direction = 0; direction < 6; direction++) {
            int nx = x + VEIN_DX[direction];
            int ny = y + VEIN_DY[direction];
            int nz = z + VEIN_DZ[direction];
            cursor.set(nx, ny, nz);
            if (this.mc.level.getBlockState(cursor).getBlock() == block) {
               if (ny >= bottomY && ny <= topY && nx >= minX && nx <= maxX && nz >= minZ && nz <= maxZ && blocks.size() < 256) {
                  long next = cursor.asLong();
                  if (visited.add(next)) {
                     queue.addLast(next);
                  }
               } else {
                  complete = false;
               }
            }
         }
      }

      return new ChunkFinder.PathComponent(blocks, complete);
   }

   private boolean hasArtificialPathSignature(List<Long> blocks) {
      Set<Long> component = new HashSet<>(blocks);
      List<Long> endpoints = new ArrayList<>(2);
      MutableBlockPos cursor = new MutableBlockPos();
      Map<Block, Integer> hostCounts = new HashMap<>();
      int faceSamples = 0;

      for (long packed : blocks) {
         int centerX = BlockPos.getX(packed);
         int centerY = BlockPos.getY(packed);
         int centerZ = BlockPos.getZ(packed);
         int degree = 0;

         for (int direction = 0; direction < 6; direction++) {
            int sampleX = centerX + VEIN_DX[direction];
            int sampleY = centerY + VEIN_DY[direction];
            int sampleZ = centerZ + VEIN_DZ[direction];
            long neighbour = BlockPos.asLong(sampleX, sampleY, sampleZ);
            if (component.contains(neighbour)) {
               degree++;
            } else {
               cursor.set(sampleX, sampleY, sampleZ);
               if (!this.isDrySolid(cursor)) {
                  return false;
               }

               Block host = this.mc.level.getBlockState(cursor).getBlock();
               hostCounts.merge(host, 1, Integer::sum);
               faceSamples++;
            }
         }

         if (degree > 2 || degree == 0) {
            return false;
         }

         if (degree == 1) {
            endpoints.add(packed);
         }

         for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
               for (int dz = -1; dz <= 1; dz++) {
                  if (dx != 0 || dy != 0 || dz != 0) {
                     int sampleX = centerX + dx;
                     int sampleY = centerY + dy;
                     int sampleZ = centerZ + dz;
                     if (!component.contains(BlockPos.asLong(sampleX, sampleY, sampleZ))) {
                        cursor.set(sampleX, sampleY, sampleZ);
                        if (!this.isDrySolid(cursor)) {
                           return false;
                        }
                     }
                  }
               }
            }
         }
      }

      if (endpoints.size() != 2) {
         return false;
      } else {
         Entry<Block, Integer> dominant = null;

         for (Entry<Block, Integer> entry : hostCounts.entrySet()) {
            if (dominant == null || entry.getValue() > dominant.getValue()) {
               dominant = entry;
            }
         }

         if (dominant == null || dominant.getValue() * 100 < faceSamples * 78) {
            return false;
         } else if (this.endpointMatchesHost(endpoints.get(0), component, dominant.getKey())
            && this.endpointMatchesHost(endpoints.get(1), component, dominant.getKey())) {
            for (long packed : blocks) {
               int centerX = BlockPos.getX(packed);
               int centerY = BlockPos.getY(packed);
               int centerZ = BlockPos.getZ(packed);

               for (int dx = -3; dx <= 3; dx++) {
                  for (int dy = -3; dy <= 3; dy++) {
                     for (int dzx = -3; dzx <= 3; dzx++) {
                        int sampleX = centerX + dx;
                        int sampleY = centerY + dy;
                        int sampleZ = centerZ + dzx;
                        if (!component.contains(BlockPos.asLong(sampleX, sampleY, sampleZ))) {
                           cursor.set(sampleX, sampleY, sampleZ);
                           if (isVeinBlock(this.mc.level.getBlockState(cursor).getBlock())) {
                              return false;
                           }
                        }
                     }
                  }
               }
            }

            return true;
         } else {
            return false;
         }
      }
   }

   private boolean endpointMatchesHost(long endpoint, Set<Long> component, Block dominantHost) {
      int x = BlockPos.getX(endpoint);
      int y = BlockPos.getY(endpoint);
      int z = BlockPos.getZ(endpoint);
      MutableBlockPos cursor = new MutableBlockPos();

      for (int direction = 0; direction < 6; direction++) {
         int nx = x + VEIN_DX[direction];
         int ny = y + VEIN_DY[direction];
         int nz = z + VEIN_DZ[direction];
         if (component.contains(BlockPos.asLong(nx, ny, nz))) {
            cursor.set(x - VEIN_DX[direction], y - VEIN_DY[direction], z - VEIN_DZ[direction]);
            return this.mc.level.getBlockState(cursor).getBlock() == dominantHost;
         }
      }

      return false;
   }

   private static boolean isVeinBlock(Block block) {
      return block == Blocks.DIRT || block == Blocks.ANDESITE || block == Blocks.GRAVEL || block == Blocks.DIORITE;
   }

   private boolean scanHoles(ChunkAccess chunk) {
      if (this.mc.level != null && this.hasLoadedNeighbours(chunk.getPos(), 1)) {
         ChunkPos chunkPos = chunk.getPos();
         int bottomY = Math.max(16, this.mc.level.getMinY());
         int topY = Math.min(45, this.mc.level.getMaxY() - 1);
         int holes = 0;
         int highestY = bottomY;
         int firstX = 0;
         int firstY = 0;
         int firstZ = 0;
         boolean foundFirst = false;
         MutableBlockPos position = new MutableBlockPos();
         MutableBlockPos side = new MutableBlockPos();
         boolean[] candidateSections = this.matchingSections(chunk, EXACT_AIR);

         for (int localX = 0; localX < 16; localX++) {
            int worldX = chunkPos.getMinBlockX() + localX;

            for (int localZ = 0; localZ < 16; localZ++) {
               int worldZ = chunkPos.getMinBlockZ() + localZ;

               for (int y = bottomY; y <= topY; y++) {
                  if (!this.isRejectedY(y)) {
                     int sectionIndex = this.mc.level.getSectionIndex(y);
                     if (sectionIndex >= 0 && sectionIndex < candidateSections.length && candidateSections[sectionIndex]) {
                        position.set(worldX, y, worldZ);
                        if (chunk.getBlockState(position).is(Blocks.AIR) && this.isEnclosedOneByOneAir(worldX, y, worldZ, side)) {
                           holes++;
                           highestY = Math.max(highestY, y);
                           if (!foundFirst) {
                              firstX = worldX;
                              firstY = y;
                              firstZ = worldZ;
                              foundFirst = true;
                           }
                        }
                     }
                  }
               }
            }
         }

         long key = chunkPos.pack();
         if (holes > 0) {
            this.holesByChunk.put(key, new ChunkFinder.HoleData(holes, highestY, firstX, firstY, firstZ));
         } else {
            this.holesByChunk.remove(key);
         }

         return true;
      } else {
         return false;
      }
   }

   private boolean scanDepletedGeode(ChunkAccess chunk) {
      if (this.mc.level != null && this.hasLoadedNeighbours(chunk.getPos(), 2)) {
         ChunkPos chunkPos = chunk.getPos();
            long sourceKey = chunkPos.pack();
         this.depletedGeodesBySourceChunk.remove(sourceKey);
         int minY = Math.max(-64, this.mc.level.getMinY());
         int maxY = Math.min(80, this.mc.level.getMaxY());
         int seedCalcite = 0;
         int seedBasalt = 0;
         long sumX = 0L;
         long sumY = 0L;
         long sumZ = 0L;
         MutableBlockPos pos = new MutableBlockPos();
         boolean[] candidateSections = this.matchingSections(chunk, GEODE_SHELL);

         for (int localX = 0; localX < 16; localX++) {
            int x = chunkPos.getMinBlockX() + localX;

            for (int localZ = 0; localZ < 16; localZ++) {
               int z = chunkPos.getMinBlockZ() + localZ;

               for (int y = minY; y <= maxY; y++) {
                  int sectionIndex = this.mc.level.getSectionIndex(y);
                  if (sectionIndex >= 0 && sectionIndex < candidateSections.length && candidateSections[sectionIndex]) {
                     pos.set(x, y, z);
                     BlockState state = chunk.getBlockState(pos);
                     if (state.is(Blocks.CALCITE) || state.is(Blocks.SMOOTH_BASALT)) {
                        if (state.is(Blocks.CALCITE)) {
                           seedCalcite++;
                        } else {
                           seedBasalt++;
                        }

                        sumX += x;
                        sumY += y;
                        sumZ += z;
                     }
                  }
               }
            }
         }

         int seedTotal = seedCalcite + seedBasalt;
         if (seedCalcite >= 4 && seedBasalt >= 4 && seedTotal != 0) {
            int seedX = (int)(sumX / seedTotal);
            int seedY = (int)(sumY / seedTotal);
            int seedZ = (int)(sumZ / seedTotal);
            int calcite = 0;
            int basalt = 0;
            long shellX = 0L;
            long shellY = 0L;
            long shellZ = 0L;
            int shellRadiusSquared = 400;

            for (int dx = -20; dx <= 20; dx++) {
               for (int dy = -20; dy <= 20; dy++) {
                  for (int dz = -20; dz <= 20; dz++) {
                     if (dx * dx + dy * dy + dz * dz <= shellRadiusSquared) {
                        pos.set(seedX + dx, seedY + dy, seedZ + dz);
                        BlockState state = this.mc.level.getBlockState(pos);
                        if (state.is(Blocks.CALCITE) || state.is(Blocks.SMOOTH_BASALT)) {
                           if (state.is(Blocks.CALCITE)) {
                              calcite++;
                           } else {
                              basalt++;
                           }

                           shellX += pos.getX();
                           shellY += pos.getY();
                           shellZ += pos.getZ();
                        }
                     }
                  }
               }
            }

            int shellTotal = calcite + basalt;
            if (calcite >= 30 && basalt >= 20 && shellTotal != 0) {
               int centerX = (int)(shellX / shellTotal);
               int centerY = (int)(shellY / shellTotal);
               int centerZ = (int)(shellZ / shellTotal);
               int amethystRadiusSquared = 196;

               for (int dx = -14; dx <= 14; dx++) {
                  for (int dy = -14; dy <= 14; dy++) {
                     for (int dzx = -14; dzx <= 14; dzx++) {
                        if (dx * dx + dy * dy + dzx * dzx <= amethystRadiusSquared) {
                           pos.set(centerX + dx, centerY + dy, centerZ + dzx);
                           BlockState state = this.mc.level.getBlockState(pos);
                           if (state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.BUDDING_AMETHYST)) {
                              return true;
                           }
                        }
                     }
                  }
               }

               for (ChunkFinder.GeodeData existing : this.depletedGeodesBySourceChunk.values()) {
                  long dx = existing.x() - centerX;
                  long dy = existing.y() - centerY;
                  long dzxx = existing.z() - centerZ;
                  if (dx * dx + dy * dy + dzxx * dzxx < 576L) {
                     return true;
                  }
               }

               this.depletedGeodesBySourceChunk.put(sourceKey, new ChunkFinder.GeodeData(centerX, centerY, centerZ));
               return true;
            } else {
               return true;
            }
         } else {
            return true;
         }
      } else {
         return false;
      }
   }

   private boolean hasLoadedNeighbours(ChunkPos pos, int radius) {
      for (int x = pos.x() - radius; x <= pos.x() + radius; x++) {
         for (int z = pos.z() - radius; z <= pos.z() + radius; z++) {
            if ((x != pos.x() || z != pos.z()) && this.mc.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) == null) {
               return false;
            }
         }
      }

      return true;
   }

   private boolean[] matchingSections(ChunkAccess chunk, Predicate<BlockState> predicate) {
      LevelChunkSection[] sections = chunk.getSections();
      boolean[] matching = new boolean[sections.length];

      for (int index = 0; index < sections.length; index++) {
         LevelChunkSection section = sections[index];
         matching[index] = section != null && !section.hasOnlyAir() && section.maybeHas(predicate);
      }

      return matching;
   }

   private boolean isEnclosedOneByOneAir(int x, int y, int z, MutableBlockPos side) {
      for (int radius = 1; radius <= 5; radius++) {
         for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
               for (int dz = -radius; dz <= radius; dz++) {
                  if (Math.abs(dx) == radius || Math.abs(dy) == radius || Math.abs(dz) == radius) {
                     side.set(x + dx, y + dy, z + dz);
                     if (!this.isDrySolid(side)) {
                        return false;
                     }
                  }
               }
            }
         }
      }

      return true;
   }

   private boolean isDrySolid(BlockPos pos) {
      BlockState state = this.mc.level.getBlockState(pos);
      return state.getFluidState().isEmpty() && state.isRedstoneConductor(this.mc.level, pos);
   }

   private boolean isWithinChunkRange(int chunkX, int chunkZ) {
      if (this.mc.player == null) {
         return false;
      } else {
         ChunkPos playerChunk = this.mc.player.chunkPosition();
         return Math.max(Math.abs(chunkX - playerChunk.x()), Math.abs(chunkZ - playerChunk.z())) <= 32;
      }
   }

   private boolean isRejectedY(int y) {
      return y == 16 || y == 17 || y == 32;
   }

   private static final class Counts {
      private final int chunkX;
      private final int chunkZ;
      private int armadillos;
      private int hostiles;
      private int holes;
      private int holeX;
      private int holeY;
      private int holeZ;
      private int depletedGeodes;
      private int geodeX;
      private int geodeY;
      private int geodeZ;
      private int veinLength;
      private int veinX;
      private int veinY;
      private int veinZ;
      private int y = Integer.MIN_VALUE;

      private Counts(int chunkX, int chunkZ) {
         this.chunkX = chunkX;
         this.chunkZ = chunkZ;
      }
   }

   private static final class Detection {
      private final int chunkX;
      private final int chunkZ;
      private int y;
      private int armadillos;
      private int hostiles;
      private int holes;
      private int depletedGeodes;
      private int veinLength;
      private final int alertX;
      private final int alertY;
      private final int alertZ;

      private Detection(
         int chunkX, int chunkZ, int y, int armadillos, int hostiles, int holes, int depletedGeodes, int veinLength, int alertX, int alertY, int alertZ
      ) {
         this.chunkX = chunkX;
         this.chunkZ = chunkZ;
         this.y = y;
         this.armadillos = armadillos;
         this.hostiles = hostiles;
         this.holes = holes;
         this.depletedGeodes = depletedGeodes;
         this.veinLength = veinLength;
         this.alertX = alertX;
         this.alertY = alertY;
         this.alertZ = alertZ;
      }
   }

   private record GeodeData(int x, int y, int z) {
   }

   private record HoleData(int count, int highestY, int firstX, int firstY, int firstZ) {
   }

   public static enum MarkerShape {
      Plate,
      Pillar,
      Both;
   }

   private record PathComponent(List<Long> blocks, boolean complete) {
   }

   public static enum PlateStyle {
      Matte("Matte"),
      Glass("Glass"),
      Outline("Outline"),
      Locator("Locator");

      private final String label;

      private PlateStyle(String label) {
         this.label = label;
      }

      @Override
      public String toString() {
         return this.label;
      }
   }

   private record VeinData(int length, int x, int y, int z) {
   }
}
