package dev.mulveo.mvaddon.modules;

import dev.mulveo.mvaddon.MVAddon;
import java.util.ArrayList;
import java.util.Collection;
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
import dev.donut.addon.mv.compat.settings.BoolSetting;
import dev.donut.addon.mv.compat.settings.IntSetting;
import dev.donut.addon.mv.compat.settings.SettingGroup;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.utils.Utils;
import dev.donut.addon.mv.compat.utils.render.RenderUtils;
import dev.donut.addon.mv.compat.utils.render.color.Color;
import dev.donut.addon.mv.compat.utils.render.color.SettingColor;
import dev.donut.addon.mv.compat.events.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class AmethystEsp extends Module {
   private static final int SCAN_INTERVAL_TICKS = 20;
   private static final Set<Block> AMETHYST_BLOCKS = Set.of(
      Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST, Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD, Blocks.AMETHYST_CLUSTER
   );
   private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
   private final SettingGroup sgRender = this.settings.createGroup("Render");
   private final Setting<Integer> minimumClusters = this.sgGeneral
      .add(
         new IntSetting.Builder().name("minimum-clusters")
                  .description("Minimum stone contacts touching amethyst required to draw a geode.")
               .defaultValue(8)
            .range(1, 30)
            .sliderRange(1, 30)
            .build()
      );
   private final Setting<Boolean> tracers = this.sgRender
      .add(
         new BoolSetting.Builder()
                     .name("tracers")
                  .description("Draw stable camera-anchored tracers to detected geodes.")
               .defaultValue(false)
            .build()
      );
   private final Setting<Boolean> renderPlate = this.sgRender
      .add(
         new BoolSetting.Builder()
                     .name("render-plate")
                  .description("Render a chunk-wide plate above each qualifying geode.")
               .defaultValue(false)
            .build()
      );
   private final Setting<SettingColor> color = this.sgRender
      .add(
         new ColorSetting.Builder()
                  .name("color")
               .description("Color used for boxes, tracers, and geode plates.")
            .defaultValue(new SettingColor(151, 62, 255, 90))
            .build()
      );
   private final AtomicBoolean scanning = new AtomicBoolean();
   private final AtomicInteger scanVersion = new AtomicInteger();
   private final ExecutorService scanner = Executors.newSingleThreadExecutor(task -> {
      Thread thread = new Thread(task, "mv-amethyst-esp-scanner");
      thread.setDaemon(true);
      return thread;
   });
   private volatile Map<Long, AmethystEsp.Detection> detections = Map.of();
   private int scanTimer;
   private ResourceKey<Level> lastDimension;

   public AmethystEsp() {
      super(MVAddon.CATEGORY, "Geode Finder", "Finds and highlights likely amethyst geodes by scanning stone contacts.");
   }

   public void onActivate() {
      this.reset();
      this.lastDimension = this.mc.level == null ? null : this.mc.level.dimension();
      this.requestScan();
   }

   public void onDeactivate() {
      this.reset();
   }

   @EventHandler
   private void onGameJoined(GameJoinedEvent event) {
      this.reset();
      this.lastDimension = this.mc.level == null ? null : this.mc.level.dimension();
   }

   @EventHandler
   private void onGameLeft(GameLeftEvent event) {
      this.reset();
      this.lastDimension = null;
   }

   @EventHandler
   private void onTick(Post event) {
      if (Utils.canUpdate()) {
         ResourceKey<Level> dimension = this.mc.level.dimension();
         if (dimension != this.lastDimension) {
            this.reset();
            this.lastDimension = dimension;
         }

         if (++this.scanTimer >= 20) {
            this.scanTimer = 0;
            this.requestScan();
         }
      }
   }

   @EventHandler
   private void onRender(Render3DEvent event) {
      SettingColor selected = (SettingColor)this.color.get();
      Color sides = new Color(selected.r, selected.g, selected.b, (int)Math.round(Math.min(selected.a, 46) * 0.72));
      Color lines = new Color(selected.r, selected.g, selected.b, (int)Math.round(205 * 0.72));
      Color tracer = new Color(selected.r, selected.g, selected.b, (int)Math.round(Math.max(selected.a, 190) * 0.72));
      Color plate = new Color(selected.r, selected.g, selected.b, (int)Math.round(Math.min(selected.a, 46) * 0.72));
      ShapeMode mode = ShapeMode.Both;
      int minimum = (Integer)this.minimumClusters.get();

      for (AmethystEsp.Detection detection : this.detections.values()) {
         if (detection.contacts().size() >= minimum) {
            for (long packedPosition : detection.contacts()) {
               event.renderer.box(BlockPos.of(packedPosition), sides, lines, mode, 0);
            }

            double centerX = (detection.chunkX() << 4) + 8.0;
            double centerZ = (detection.chunkZ() << 4) + 8.0;
            double targetY = detection.topY() + 1.1;
            if (this.renderPlate.get()) {
               double inset = 0.1;
               double startX = (detection.chunkX() << 4) + inset;
               double startZ = (detection.chunkZ() << 4) + inset;
               event.renderer.box(startX, targetY, startZ, startX + 16.0 - inset * 2.0, targetY + 0.025, startZ + 16.0 - inset * 2.0, plate, lines, mode, 0);
            }

            if ((Boolean)this.tracers.get()) {
               event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, centerX, targetY, centerZ, tracer);
            }
         }
      }
   }

   public String getInfoString() {
      int minimum = (Integer)this.minimumClusters.get();
      int count = 0;

      for (AmethystEsp.Detection detection : this.detections.values()) {
         if (detection.contacts().size() >= minimum) {
            count++;
         }
      }

      return Integer.toString(count);
   }

   private void reset() {
      this.scanVersion.incrementAndGet();
      this.detections = Map.of();
      this.scanTimer = 0;
   }

   private void requestScan() {
      if (this.isActive() && this.mc.level != null && this.scanning.compareAndSet(false, true)) {
         int version = this.scanVersion.get();
         Map<Long, ChunkAccess> loadedChunks = new HashMap<>();

         for (ChunkAccess chunk : Utils.chunks()) {
            loadedChunks.put(chunk.getPos().pack(), chunk);
         }

         this.scanner.submit(() -> {
            try {
               List<AmethystEsp.Detection> scanResults = scanLoadedChunks(loadedChunks);
               this.mc.execute(() -> {
                  if (this.isActive() && version == this.scanVersion.get()) {
                     this.mergeDetections(scanResults);
                  }
               });
            } catch (Throwable var7) {
               this.mc.execute(() -> this.error("Geode Finder scan failed: %s", new Object[]{var7.getMessage()}));
            } finally {
               this.scanning.set(false);
            }
         });
      }
   }

   private void mergeDetections(List<AmethystEsp.Detection> scanResults) {
      Map<Long, AmethystEsp.Detection> merged = new HashMap<>(this.detections);

      for (AmethystEsp.Detection current : scanResults) {
         long chunkKey = ChunkPos.pack(current.chunkX(), current.chunkZ());
         AmethystEsp.Detection previous = merged.get(chunkKey);
         if (previous == null) {
            merged.put(chunkKey, current);
         } else {
            Set<Long> contacts = new HashSet<>(previous.contacts());
            contacts.addAll(current.contacts());
            merged.put(chunkKey, new AmethystEsp.Detection(current.chunkX(), current.chunkZ(), Set.copyOf(contacts), Math.max(previous.topY(), current.topY())));
         }
      }

      this.detections = Map.copyOf(merged);
   }

   private static List<AmethystEsp.Detection> scanLoadedChunks(Map<Long, ChunkAccess> loadedChunks) {
      Set<AmethystEsp.SectionKey> amethystSections = findAmethystSections(loadedChunks.values());
      List<AmethystEsp.Detection> results = new ArrayList<>();

      for (ChunkAccess chunk : loadedChunks.values()) {
         AmethystEsp.Detection detection = findStoneContacts(chunk, loadedChunks, amethystSections);
         if (detection != null) {
            results.add(detection);
         }
      }

      return results;
   }

   private static Set<AmethystEsp.SectionKey> findAmethystSections(Collection<ChunkAccess> chunks) {
      Set<AmethystEsp.SectionKey> sections = new HashSet<>();

      for (ChunkAccess chunk : chunks) {
         LevelChunkSection[] chunkSections = chunk.getSections();

         for (int sectionIndex = 0; sectionIndex < chunkSections.length; sectionIndex++) {
            LevelChunkSection section = chunkSections[sectionIndex];
            if (section != null && !section.hasOnlyAir() && section.maybeHas(state -> AMETHYST_BLOCKS.contains(state.getBlock()))) {
               sections.add(new AmethystEsp.SectionKey(chunk.getPos().x(), chunk.getMinY() / 16 + sectionIndex, chunk.getPos().z()));
            }
         }
      }

      return sections;
   }

   private static AmethystEsp.Detection findStoneContacts(ChunkAccess chunk, Map<Long, ChunkAccess> loadedChunks, Set<AmethystEsp.SectionKey> amethystSections) {
      LevelChunkSection[] sections = chunk.getSections();
      Set<Long> contacts = new HashSet<>();
      MutableBlockPos mutable = new MutableBlockPos();
      int chunkStartX = chunk.getPos().x() << 4;
      int chunkStartZ = chunk.getPos().z() << 4;
      int topY = Integer.MIN_VALUE;

      for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
         LevelChunkSection section = sections[sectionIndex];
         if (section != null && !section.hasOnlyAir() && section.maybeHas(statex -> statex.is(Blocks.STONE))) {
            int sectionY = chunk.getMinY() / 16 + sectionIndex;
            if (touchesAmethystSection(new AmethystEsp.SectionKey(chunk.getPos().x(), sectionY, chunk.getPos().z()), amethystSections)) {
               int sectionStartY = chunk.getMinY() + (sectionIndex << 4);

               for (int localY = 0; localY < 16; localY++) {
                  for (int localZ = 0; localZ < 16; localZ++) {
                     for (int localX = 0; localX < 16; localX++) {
                        BlockState state = section.getBlockState(localX, localY, localZ);
                        if (state.is(Blocks.STONE)) {
                           mutable.set(chunkStartX + localX, sectionStartY + localY, chunkStartZ + localZ);
                           if (touchesAmethyst(mutable, loadedChunks)) {
                              contacts.add(mutable.asLong());
                              topY = Math.max(topY, mutable.getY());
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      return contacts.isEmpty() ? null : new AmethystEsp.Detection(chunk.getPos().x(), chunk.getPos().z(), Set.copyOf(contacts), topY);
   }

   private static boolean touchesAmethystSection(AmethystEsp.SectionKey section, Set<AmethystEsp.SectionKey> amethystSections) {
      if (amethystSections.contains(section)) {
         return true;
      } else {
         for (Direction direction : Direction.values()) {
            if (amethystSections.contains(
               new AmethystEsp.SectionKey(section.x() + direction.getStepX(), section.y() + direction.getStepY(), section.z() + direction.getStepZ())
            )) {
               return true;
            }
         }

         return false;
      }
   }

   private static boolean touchesAmethyst(BlockPos stone, Map<Long, ChunkAccess> loadedChunks) {
      MutableBlockPos neighbour = new MutableBlockPos();

      for (Direction direction : Direction.values()) {
         neighbour.set(stone).move(direction);
         ChunkAccess chunk = loadedChunks.get(ChunkPos.pack(neighbour.getX() >> 4, neighbour.getZ() >> 4));
         if (chunk != null && AMETHYST_BLOCKS.contains(chunk.getBlockState(neighbour).getBlock())) {
            return true;
         }
      }

      return false;
   }

   private record Detection(int chunkX, int chunkZ, Set<Long> contacts, int topY) {
   }

   private record SectionKey(int x, int y, int z) {
   }
}
