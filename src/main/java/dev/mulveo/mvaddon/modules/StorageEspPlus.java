package dev.mulveo.mvaddon.modules;

import dev.mulveo.mvaddon.MVAddon;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayDeque;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.chunk.LevelChunkSection;
import dev.donut.addon.mv.compat.events.world.ChunkDataEvent;
import dev.donut.addon.mv.compat.events.world.StorageBlocksChangedEvent;
import dev.donut.addon.mv.compat.events.world.StorageChunkUnloadedEvent;
import java.util.List;
import dev.donut.addon.mv.compat.events.game.GameJoinedEvent;
import dev.donut.addon.mv.compat.events.game.GameLeftEvent;
import dev.donut.addon.mv.compat.events.render.Render3DEvent;
import dev.donut.addon.mv.compat.events.world.TickEvent.Post;
import dev.donut.addon.mv.compat.renderer.ShapeMode;
import dev.donut.addon.mv.compat.settings.IVisible;
import dev.donut.addon.mv.compat.settings.ColorSetting;
import dev.donut.addon.mv.compat.settings.BoolSetting;
import dev.donut.addon.mv.compat.settings.DoubleSetting;
import dev.donut.addon.mv.compat.settings.IntSetting;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.settings.EnumSetting;
import dev.donut.addon.mv.compat.settings.SettingGroup;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.utils.render.RenderUtils;
import dev.donut.addon.mv.compat.utils.render.color.Color;
import dev.donut.addon.mv.compat.utils.render.color.SettingColor;
import dev.donut.addon.mv.compat.events.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.EnchantingTableBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class StorageEspPlus extends Module {
   private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
   private final SettingGroup sgBlocks = this.settings.createGroup("Detected Blocks");
   private final SettingGroup sgColors = this.settings.createGroup("Colors");
   private final Setting<ShapeMode> shapeMode = this.sgGeneral
      .add(
         new EnumSetting.Builder<ShapeMode>().name("shape-mode").description("How detected storage blocks are drawn.").defaultValue(ShapeMode.Lines)
            .build()
      );
   private final Setting<Integer> fillOpacity = this.sgGeneral
      .add(
         new IntSetting.Builder()
                     .name("fill-opacity")
                  .description("Opacity of the box fill.")
               .defaultValue(30)
            .range(0, 255)
            .sliderRange(0, 255)
            .build()
      );
   private final Setting<Double> renderDistance = this.sgGeneral
      .add(
         new DoubleSetting.Builder()
                  .name("render-distance")
               .description("Maximum distance at which storage ESP is drawn.")
            .defaultValue(256.0)
            .range(8.0, 2048.0)
            .sliderRange(16.0, 1024.0)
            .build()
      );
   private final Setting<Boolean> distanceFade = this.sgGeneral
      .add(
         new BoolSetting.Builder()
                     .name("distance-fade")
                  .description("Smoothly fades distant boxes to keep dense areas readable.")
               .defaultValue(true)
            .build()
      );
   private final Setting<Double> fadeStart = this.sgGeneral
      .add(
         new DoubleSetting.Builder()
                     .name("fade-start")
                  .description("Distance at which the fade begins.")
               .defaultValue(128.0)
               .range(0.0, 2048.0)
               .sliderRange(0.0, 1024.0)
               .visible(this.distanceFade::get)
            .build()
      );
   private final Setting<Double> safeFillDistance = this.sgGeneral
      .add(
         new DoubleSetting.Builder()
                  .name("nearby-fill-cutoff")
               .description("Uses outlines only within this distance so a box can never cover your screen.")
            .defaultValue(4.0)
            .range(0.0, 16.0)
            .sliderRange(0.0, 10.0)
            .build()
      );
   private final Setting<Integer> maxBoxes = this.sgGeneral
      .add(
         new IntSetting.Builder()
                     .name("max-boxes")
                  .description("Maximum number of nearest detected storage boxes drawn at once.")
               .defaultValue(512)
            .range(16, 4096)
            .sliderRange(64, 1024)
            .build()
      );
   private final Setting<Integer> lineOpacity = this.sgGeneral
      .add(
         new IntSetting.Builder()
                     .name("line-opacity")
                  .description("Opacity of the box outline.")
               .defaultValue(225)
            .range(0, 255)
            .sliderRange(0, 255)
            .build()
      );
   private final Setting<Double> inset = this.sgGeneral
      .add(
         new DoubleSetting.Builder()
                  .name("box-inset")
               .description("Pulls the ESP box inward from the block edges.")
            .defaultValue(0.08)
            .range(0.0, 0.45)
            .sliderRange(0.0, 0.3)
            .decimalPlaces(2)
            .build()
      );
   private final Setting<Boolean> tracers = this.sgGeneral
      .add(
         new BoolSetting.Builder()
                     .name("tracers")
                  .description("Draws stable camera-anchored tracers to detected blocks.")
               .defaultValue(false)
            .build()
      );
   private final Setting<Integer> tracerOpacity = this.sgGeneral
      .add(
         new IntSetting.Builder()
                        .name("tracer-opacity")
                     .description("Opacity of storage tracers.")
                  .defaultValue(190)
               .range(0, 255)
               .sliderRange(0, 255)
               .visible(this.tracers::get)
            .build()
      );
   private final Setting<Integer> scanDelay = this.sgGeneral
      .add(
         new IntSetting.Builder()
                     .name("scan-delay")
                  .description("Ticks between batches of loaded chunk sections. One is fastest.")
               .defaultValue(1)
            .range(1, 20)
            .sliderRange(1, 20)
            .build()
      );
   private final Setting<Boolean> chests = this.toggle("chests", "Detect normal and trapped chests.", true);
   private final Setting<Boolean> enderChests = this.toggle("ender-chests", "Detect ender chests.", true);
   private final Setting<Boolean> shulkers = this.toggle("shulker-boxes", "Detect every shulker box color.", true);
   private final Setting<Boolean> spawners = this.toggle("spawners", "Detect monster spawners.", false);
   private final Setting<Boolean> trialSpawners = this.toggle("trial-spawners", "Detect trial spawners.", true);
   private final Setting<Boolean> vaults = this.toggle("vaults", "Detect vaults.", true);
   private final Setting<Boolean> furnaces = this.toggle("furnaces", "Detect furnaces, blast furnaces, and smokers.", true);
   private final Setting<Boolean> barrels = this.toggle("barrels", "Detect barrels.", true);
   private final Setting<Boolean> enchantingTables = this.toggle("enchanting-tables", "Detect enchanting tables.", false);
   private final Setting<Boolean> pistons = this.toggle("moving-pistons", "Detect active piston block entities.", false);
   private final Setting<Boolean> hoppers = this.toggle("hoppers", "Detect hoppers.", true);
   private final Setting<Boolean> dispensers = this.toggle("dispensers", "Detect dispensers.", true);
   private final Setting<Boolean> droppers = this.toggle("droppers", "Detect droppers.", true);
   private final Setting<Boolean> brewingStands = this.toggle("brewing-stands", "Detect brewing stands.", true);
   private final Setting<Boolean> crafters = this.toggle("crafters", "Detect crafters.", true);
   private final Setting<Boolean> bookshelves = this.toggle("chiseled-bookshelves", "Detect chiseled bookshelves.", true);
   private final Setting<Boolean> decoratedPots = this.toggle("decorated-pots", "Detect decorated pots.", true);
   private final Setting<SettingColor> chestColor = this.color("chest", this.chests::get, 156, 91, 0);
   private final Setting<SettingColor> enderChestColor = this.color("ender-chest", this.enderChests::get, 127, 91, 255);
   private final Setting<SettingColor> shulkerColor = this.color("shulker-box", this.shulkers::get, 236, 85, 204);
   private final Setting<SettingColor> spawnerColor = this.color("spawner", this.spawners::get, 255, 76, 98);
   private final Setting<SettingColor> trialSpawnerColor = this.color("trial-spawner", this.trialSpawners::get, 255, 120, 60);
   private final Setting<SettingColor> vaultColor = this.color("vault", this.vaults::get, 100, 210, 210);
   private final Setting<SettingColor> furnaceColor = this.color("furnace", this.furnaces::get, 102, 168, 255);
   private final Setting<SettingColor> barrelColor = this.color("barrel", this.barrels::get, 232, 151, 70);
   private final Setting<SettingColor> enchantColor = this.color("enchanting-table", this.enchantingTables::get, 62, 224, 207);
   private final Setting<SettingColor> pistonColor = this.color("moving-piston", this.pistons::get, 151, 224, 88);
   private final Setting<SettingColor> hopperColor = this.color("hopper", this.hoppers::get, 80, 80, 80);
   private final Setting<SettingColor> dispenserColor = this.color("dispenser", this.dispensers::get, 95, 188, 255);
   private final Setting<SettingColor> dropperColor = this.color("dropper", this.droppers::get, 118, 134, 162);
   private final Setting<SettingColor> brewingColor = this.color("brewing-stand", this.brewingStands::get, 249, 115, 140);
   private final Setting<SettingColor> crafterColor = this.color("crafter", this.crafters::get, 255, 195, 72);
   private final Setting<SettingColor> bookshelfColor = this.color("chiseled-bookshelf", this.bookshelves::get, 189, 126, 255);
   private final Setting<SettingColor> potColor = this.color("decorated-pot", this.decoratedPots::get, 223, 119, 92);
   private List<StorageEspPlus.Target> targets = List.of();
   private int scanTimer;
   private BlockPos lastSortPosition;
   private ClientLevel scanWorld;
   private final StorageHitCache<ChunkPos, BlockPos, StorageType> chunkHits = new StorageHitCache<>();
   private final ArrayDeque<ChunkPos> priorityChunks = new ArrayDeque<>();
   private List<ChunkPos> sweep = List.of();
   private int sweepIndex;
   private ChunkPos sweepCenter;
   private int sweepDistance = -1;
   private LevelChunk scanningChunk;
   private int scanningSection;
   private Map<BlockPos, StorageType> scanningHits;
   private static final int SECTIONS_PER_BATCH = 4;
   private final Setting<Boolean> cleanRenderV2 = this.sgGeneral
      .add(
         new BoolSetting.Builder()
                        .name("clean-render-v2")
                     .description("Internal settings migration flag.")
                  .defaultValue(false)
               .visible(() -> false)
            .build()
      );

   public StorageEspPlus() {
      super(MVAddon.BASEFINDING, "Storage Finder", "Highlights nearby storage blocks and useful block entities.");
   }

   public void onActivate() {
      this.migrateLegacyRenderSettings();
      this.clearScan();
      this.scanTimer = this.scanDelay.get();
   }

   public void onDeactivate() { this.clearScan(); }

   @EventHandler
   private void onGameJoined(GameJoinedEvent event) { this.clearScan(); }

   @EventHandler
   private void onGameLeft(GameLeftEvent event) { this.clearScan(); }

   @EventHandler
   private void onChunkData(ChunkDataEvent event) {
      if (this.mc.level != null && this.scanWorld == this.mc.level) {
         ChunkPos pos = event.chunk().getPos();
         if (!this.inView(pos)) return;
         this.chunkHits.remove(pos);
         this.priorityChunks.remove(pos);
         this.priorityChunks.addFirst(pos);
         if (this.scanningChunk != null && this.scanningChunk.getPos().equals(pos)) this.scanningChunk = null;
         this.rebuildTargets();
      }
   }

   @EventHandler
   private void onBlocksChanged(StorageBlocksChangedEvent event) {
      if (this.scanWorld == null || this.scanWorld != this.mc.level) return;
      boolean changed = false;
      for (Map.Entry<BlockPos, BlockState> entry : event.changes().entrySet()) {
         BlockPos pos = entry.getKey().immutable();
         ChunkPos chunk = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
         if (!this.inView(chunk) || this.mc.level.getChunkSource().getChunk(chunk.x(), chunk.z(), ChunkStatus.FULL, false) == null) continue;
         StorageType type = typeOfState(this.mc.level.getBlockState(pos));
         StorageType old = this.chunkHits.update(chunk, pos, type);
         if (this.scanningChunk != null && this.scanningChunk.getPos().equals(chunk)) {
            if (type == null) this.scanningHits.remove(pos); else this.scanningHits.put(pos, type);
         }
         changed |= old != type || type == StorageType.Chest;
      }
      if (changed) this.rebuildTargets();
   }

   @EventHandler
   private void onChunkUnloaded(StorageChunkUnloadedEvent event) {
      ChunkPos pos = event.pos();
      this.priorityChunks.remove(pos);
      if (this.scanningChunk != null && this.scanningChunk.getPos().equals(pos)) {
         this.scanningChunk = null;
         this.scanningHits = null;
      }
      if (this.chunkHits.remove(pos)) this.rebuildTargets();
   }

   @EventHandler
   private void onTick(Post event) {
      if (this.mc.level == null || this.mc.player == null) { this.clearScan(); return; }
      if (this.scanWorld != this.mc.level) { this.clearScan(); this.scanWorld = this.mc.level; }
      BlockPos playerPos = this.mc.player.blockPosition();
      if (!playerPos.equals(this.lastSortPosition)) {
         this.lastSortPosition = playerPos.immutable();
         if (!this.targets.isEmpty()) {
            List<Target> sorted = new ArrayList<>(this.targets);
            sorted.sort(Comparator.comparingDouble(this::distanceSquared));
            this.targets = List.copyOf(sorted);
         }
      }
      if (++this.scanTimer >= this.scanDelay.get()) {
         this.scanTimer = 0;
         this.scanBatch();
      }
   }

   private boolean inView(ChunkPos pos) {
      if (this.mc.player == null) return false;
      ChunkPos center = this.mc.player.chunkPosition();
      int distance = this.mc.options.renderDistance().get();
      return Math.abs(pos.x() - center.x()) <= distance && Math.abs(pos.z() - center.z()) <= distance;
   }

   private void clearScan() {
      this.targets = List.of();
      this.chunkHits.clear();
      this.priorityChunks.clear();
      this.sweep = List.of();
      this.sweepIndex = 0;
      this.sweepCenter = null;
      this.sweepDistance = -1;
      this.scanningChunk = null;
      this.scanningHits = null;
      this.scanWorld = null;
      this.lastSortPosition = null;
   }

   @EventHandler
   private void onRender(Render3DEvent event) {
      if (this.mc.level != null && this.mc.level == this.scanWorld && this.mc.player != null) {
         double amount = (Double)this.inset.get();
         double maxDistance = (Double)this.renderDistance.get();
         double maxDistanceSq = maxDistance * maxDistance;
         double themeOpacity = 0.72;
         int rendered = 0;

         for (StorageEspPlus.Target target : this.targets) {
            if (this.enabled(target.type) && this.currentTarget(target)) {
               double dx = target.centerX() - this.mc.player.getX();
               double dy = target.centerY() - this.mc.player.getY();
               double dz = target.centerZ() - this.mc.player.getZ();
               double distanceSq = dx * dx + dy * dy + dz * dz;
               if (!(distanceSq > maxDistanceSq) && rendered++ < (Integer)this.maxBoxes.get()) {
                  double distance = Math.sqrt(distanceSq);
                  double fade = 1.0;
                  if (this.distanceFade.get() && maxDistance > (Double)this.fadeStart.get() && distance > (Double)this.fadeStart.get()) {
                     fade = Math.max(0.12, 1.0 - (distance - (Double)this.fadeStart.get()) / (maxDistance - (Double)this.fadeStart.get()));
                  }

                  SettingColor settingColor = this.colorFor(target.type);

                  int themedFill = Math.min((Integer)this.fillOpacity.get(), 34);
                  int themedLine = Math.min((Integer)this.lineOpacity.get(), 205);
                  Color sides = new Color(settingColor.r, settingColor.g, settingColor.b, (int)Math.round(themedFill * fade * themeOpacity));
                  Color lines = new Color(settingColor.r, settingColor.g, settingColor.b, (int)Math.round(themedLine * fade * themeOpacity));
                  BlockPos first = target.first;
                  BlockPos second = target.second;
                  int minX = Math.min(first.getX(), second.getX());
                  int minY = Math.min(first.getY(), second.getY());
                  int minZ = Math.min(first.getZ(), second.getZ());
                  int maxX = Math.max(first.getX(), second.getX()) + 1;
                  int maxY = Math.max(first.getY(), second.getY()) + 1;
                  int maxZ = Math.max(first.getZ(), second.getZ()) + 1;
                  ShapeMode mode = distance < this.safeFillDistance.get() ? ShapeMode.Lines : (ShapeMode)this.shapeMode.get();
                  event.renderer.box(minX + amount, minY + amount, minZ + amount, maxX - amount, maxY - amount, maxZ - amount, sides, lines, mode, 0);
                  if ((Boolean)this.tracers.get()) {
                     Color tracer = new Color(
                        settingColor.r, settingColor.g, settingColor.b, (int)Math.round(((Integer)this.tracerOpacity.get()).intValue() * themeOpacity)
                     );
                     event.renderer
                        .line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, target.centerX(), target.centerY(), target.centerZ(), tracer);
                  }
               }
            }
         }
      }
   }

   // All world reads occur on the client thread. Each batch scans at most four matching sections and checks at most eight chunks.
   private void scanBatch() {
      ChunkPos center = this.mc.player.chunkPosition();
      int distance = this.mc.options.renderDistance().get();
      if (!center.equals(this.sweepCenter) || distance != this.sweepDistance) {
         this.sweepCenter = center;
         this.sweepDistance = distance;
         List<ChunkPos> positions = new ArrayList<>();
         for (int x = -distance; x <= distance; x++) for (int z = -distance; z <= distance; z++)
            positions.add(new ChunkPos(center.x() + x, center.z() + z));
         positions.sort(Comparator.comparingInt(p -> Math.max(Math.abs(p.x() - center.x()), Math.abs(p.z() - center.z()))));
         this.sweep = positions;
         // Keep sweep progress as the player moves; restarting at the center can
         // otherwise starve distant loaded chunks indefinitely.
         this.sweepIndex %= this.sweep.size();
         this.chunkHits.removeIf(p -> Math.abs(p.x() - center.x()) > distance || Math.abs(p.z() - center.z()) > distance);
         this.priorityChunks.removeIf(p -> Math.abs(p.x() - center.x()) > distance || Math.abs(p.z() - center.z()) > distance);
         if (this.scanningChunk != null) {
            ChunkPos current = this.scanningChunk.getPos();
            if (Math.abs(current.x() - center.x()) > distance || Math.abs(current.z() - center.z()) > distance) {
               this.scanningChunk = null;
               this.scanningHits = null;
            }
         }
         this.rebuildTargets();
      }
      int budget = SECTIONS_PER_BATCH;
      // Bound even empty/unloaded chunks, which otherwise could traverse the entire view in one tick.
      int chunkBudget = 8;
      while (budget > 0 && chunkBudget > 0) {
         if (this.scanningChunk == null) {
            ChunkPos pos = this.priorityChunks.pollFirst();
            if (pos == null) {
               if (this.sweep.isEmpty()) return;
               pos = this.sweep.get(this.sweepIndex++);
               if (this.sweepIndex >= this.sweep.size()) this.sweepIndex = 0;
            }
            chunkBudget--;
            LevelChunk chunk = this.mc.level.getChunkSource().getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false);
            if (chunk == null) {
               if (this.chunkHits.remove(pos)) this.rebuildTargets();
               continue;
            }
            this.scanningChunk = chunk;
            this.scanningSection = 0;
            this.scanningHits = new HashMap<>();
            for (BlockEntity entity : chunk.getBlockEntities().values()) {
               StorageType type = this.typeOf(entity);
               if (type != null) this.scanningHits.put(entity.getBlockPos().immutable(), type);
            }
         }
         LevelChunk chunk = this.scanningChunk;
         ChunkPos pos = chunk.getPos();
         if (this.mc.level.getChunkSource().getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false) != chunk) {
            this.scanningChunk = null;
            this.chunkHits.remove(pos);
            this.rebuildTargets();
            continue;
         }
         LevelChunkSection[] sections = chunk.getSections();
         while (budget > 0 && this.scanningSection < sections.length) {
            int index = this.scanningSection++;
            LevelChunkSection section = sections[index];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(state -> typeOfState(state) != null)) continue;
            budget--;
            int yBase = chunk.getMinY() + (index << 4);
            for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
               StorageType type = typeOfState(section.getBlockState(x, y, z));
               if (type != null) this.scanningHits.put(new BlockPos((pos.x() << 4) + x, yBase + y, (pos.z() << 4) + z), type);
            }
         }
         if (this.scanningSection >= sections.length) {
            this.chunkHits.replace(pos, this.scanningHits);
            this.scanningChunk = null;
            this.scanningHits = null;
            this.rebuildTargets();
         }
      }
   }

   private void rebuildTargets() {
      if (this.mc.level == null || this.mc.player == null) { this.targets = List.of(); return; }
      Map<BlockPos, StorageType> hits = this.chunkHits.flattened();
      List<Target> refreshed = new ArrayList<>();
      for (StorageHitCache.Hit<BlockPos, StorageType> hit : StorageHitCache.mergePairs(hits, StorageType.Chest, this::chestPartner)) {
         refreshed.add(new Target(hit.first(), hit.second(), hit.type()));
      }
      refreshed.sort(Comparator.comparingDouble(this::distanceSquared));
      this.targets = List.copyOf(refreshed);
   }

   private boolean currentTarget(Target target) {
      if (!this.currentPosition(target.first, target.type)) return false;
      if (target.first.equals(target.second)) return true;
      return this.currentPosition(target.second, target.type)
         && matchingChestPair(target.first, this.mc.level.getBlockState(target.first),
            target.second, this.mc.level.getBlockState(target.second));
   }

   private boolean currentPosition(BlockPos pos, StorageType type) {
      ChunkPos chunk = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
      if (this.mc.level.getChunkSource().getChunk(chunk.x(), chunk.z(), ChunkStatus.FULL, false) == null) return false;
      // Recheck cached positions so removals and chunk unloads never leave visible ghosts.
      StorageType stateType = typeOfState(this.mc.level.getBlockState(pos));
      if (stateType == type) return true;
      BlockEntity entity = this.mc.level.getBlockEntity(pos);
      return entity != null && this.typeOf(entity) == type;
   }

   static StorageType typeOfState(BlockState state) {
      Block block = state.getBlock();
      if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) return StorageType.Chest;
      if (block == Blocks.ENDER_CHEST) return StorageType.EnderChest;
      if (block instanceof ShulkerBoxBlock) return StorageType.Shulker;
      if (block == Blocks.SPAWNER) return StorageType.Spawner;
      if (block == Blocks.TRIAL_SPAWNER) return StorageType.TrialSpawner;
      if (block == Blocks.VAULT) return StorageType.Vault;
      if (block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.SMOKER) return StorageType.Furnace;
      if (block == Blocks.BARREL) return StorageType.Barrel;
      if (block == Blocks.ENCHANTING_TABLE) return StorageType.EnchantingTable;
      if (block == Blocks.MOVING_PISTON) return StorageType.Piston;
      if (block == Blocks.HOPPER) return StorageType.Hopper;
      if (block == Blocks.DISPENSER) return StorageType.Dispenser;
      if (block == Blocks.DROPPER) return StorageType.Dropper;
      if (block == Blocks.BREWING_STAND) return StorageType.BrewingStand;
      if (block == Blocks.CRAFTER) return StorageType.Crafter;
      if (block == Blocks.CHISELED_BOOKSHELF) return StorageType.ChiseledBookshelf;
      if (block == Blocks.DECORATED_POT) return StorageType.DecoratedPot;
      return null;
   }

   private BlockPos chestPartner(BlockPos pos) {
      if (this.mc.level == null) {
         return null;
      } else {
         BlockState state = this.mc.level.getBlockState(pos);
         if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos partner = pos.relative(ChestBlock.getConnectedDirection(state));
            ChunkPos partnerChunk = new ChunkPos(partner.getX() >> 4, partner.getZ() >> 4);
            if (this.mc.level.getChunkSource().getChunk(partnerChunk.x(), partnerChunk.z(), ChunkStatus.FULL, false) == null) return null;
            BlockState partnerState = this.mc.level.getBlockState(partner);
            return matchingChestPair(pos, state, partner, partnerState) ? partner.immutable() : null;
         } else {
            return null;
         }
      }
   }

   static boolean matchingChestPair(BlockPos pos, BlockState state, BlockPos partner, BlockState partnerState) {
      if (!(state.getBlock() instanceof ChestBlock) || partnerState.getBlock() != state.getBlock()) return false;
      if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE || partnerState.getValue(ChestBlock.TYPE) == ChestType.SINGLE
            || partnerState.getValue(ChestBlock.TYPE) == state.getValue(ChestBlock.TYPE)
            || partnerState.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING)) return false;
      return pos.relative(ChestBlock.getConnectedDirection(state)).equals(partner)
         && partner.relative(ChestBlock.getConnectedDirection(partnerState)).equals(pos);
   }

   private double distanceSquared(StorageEspPlus.Target target) {
      if (this.mc.player == null) {
         return Double.MAX_VALUE;
      } else {
         double dx = target.centerX() - this.mc.player.getX();
         double dy = target.centerY() - this.mc.player.getY();
         double dz = target.centerZ() - this.mc.player.getZ();
         return dx * dx + dy * dy + dz * dz;
      }
   }

   private void migrateLegacyRenderSettings() {
      if (!(Boolean)this.cleanRenderV2.get()) {
         this.shapeMode.set(ShapeMode.Lines);
         this.fillOpacity.set(30);
         this.spawners.set(false);
         this.enchantingTables.set(false);
         this.pistons.set(false);
         this.cleanRenderV2.set(true);
      }
   }

   private StorageEspPlus.StorageType typeOf(BlockEntity blockEntity) {
      if (blockEntity instanceof ChestBlockEntity) {
         return StorageEspPlus.StorageType.Chest;
      } else if (blockEntity instanceof EnderChestBlockEntity) {
         return StorageEspPlus.StorageType.EnderChest;
      } else if (blockEntity instanceof ShulkerBoxBlockEntity) {
         return StorageEspPlus.StorageType.Shulker;
      } else if (blockEntity instanceof SpawnerBlockEntity) {
         return StorageEspPlus.StorageType.Spawner;
      } else if (blockEntity instanceof AbstractFurnaceBlockEntity) {
         return StorageEspPlus.StorageType.Furnace;
      } else if (blockEntity instanceof BarrelBlockEntity) {
         return StorageEspPlus.StorageType.Barrel;
      } else if (blockEntity instanceof EnchantingTableBlockEntity) {
         return StorageEspPlus.StorageType.EnchantingTable;
      } else if (blockEntity instanceof PistonMovingBlockEntity) {
         return StorageEspPlus.StorageType.Piston;
      } else if (blockEntity instanceof HopperBlockEntity) {
         return StorageEspPlus.StorageType.Hopper;
      } else if (blockEntity instanceof DropperBlockEntity) {
         return StorageEspPlus.StorageType.Dropper;
      } else if (blockEntity instanceof DispenserBlockEntity) {
         return StorageEspPlus.StorageType.Dispenser;
      } else if (blockEntity instanceof BrewingStandBlockEntity) {
         return StorageEspPlus.StorageType.BrewingStand;
      } else if (blockEntity instanceof CrafterBlockEntity) {
         return StorageEspPlus.StorageType.Crafter;
      } else if (blockEntity instanceof ChiseledBookShelfBlockEntity) {
         return StorageEspPlus.StorageType.ChiseledBookshelf;
      } else {
         return blockEntity instanceof DecoratedPotBlockEntity ? StorageEspPlus.StorageType.DecoratedPot : null;
      }
   }

   private boolean enabled(StorageEspPlus.StorageType type) {
      return switch (type) {
         case Chest -> this.chests.get();
         case EnderChest -> this.enderChests.get();
         case Shulker -> this.shulkers.get();
         case Spawner -> this.spawners.get();
         case TrialSpawner -> this.trialSpawners.get();
         case Vault -> this.vaults.get();
         case Furnace -> this.furnaces.get();
         case Barrel -> this.barrels.get();
         case EnchantingTable -> this.enchantingTables.get();
         case Piston -> this.pistons.get();
         case Hopper -> this.hoppers.get();
         case Dispenser -> this.dispensers.get();
         case Dropper -> this.droppers.get();
         case BrewingStand -> this.brewingStands.get();
         case Crafter -> this.crafters.get();
         case ChiseledBookshelf -> this.bookshelves.get();
         case DecoratedPot -> this.decoratedPots.get();
      };
   }

   private SettingColor colorFor(StorageEspPlus.StorageType type) {
      return switch (type) {
         case Chest -> (SettingColor)this.chestColor.get();
         case EnderChest -> (SettingColor)this.enderChestColor.get();
         case Shulker -> (SettingColor)this.shulkerColor.get();
         case Spawner -> (SettingColor)this.spawnerColor.get();
         case TrialSpawner -> this.trialSpawnerColor.get();
         case Vault -> this.vaultColor.get();
         case Furnace -> (SettingColor)this.furnaceColor.get();
         case Barrel -> (SettingColor)this.barrelColor.get();
         case EnchantingTable -> (SettingColor)this.enchantColor.get();
         case Piston -> (SettingColor)this.pistonColor.get();
         case Hopper -> (SettingColor)this.hopperColor.get();
         case Dispenser -> (SettingColor)this.dispenserColor.get();
         case Dropper -> (SettingColor)this.dropperColor.get();
         case BrewingStand -> (SettingColor)this.brewingColor.get();
         case Crafter -> (SettingColor)this.crafterColor.get();
         case ChiseledBookshelf -> (SettingColor)this.bookshelfColor.get();
         case DecoratedPot -> (SettingColor)this.potColor.get();
      };
   }

   private Setting<Boolean> toggle(String name, String description, boolean defaultValue) {
      return this.sgBlocks
         .add(
            new BoolSetting.Builder()
                        .name(name)
                     .description(description)
                  .defaultValue(defaultValue)
               .build()
         );
   }

   private Setting<SettingColor> color(String name, IVisible visible, int r, int g, int b) {
      return this.sgColors
         .add(
            new ColorSetting.Builder()
                        .name(name + "-color")
                     .description("Color used for " + name.replace('-', ' ') + " ESP.")
                  .defaultValue(new SettingColor(r, g, b, 255))
                  .visible(visible)
               .build()
         );
   }

   static enum StorageType {
      Chest,
      EnderChest,
      Shulker,
      Spawner,
      TrialSpawner,
      Vault,
      Furnace,
      Barrel,
      EnchantingTable,
      Piston,
      Hopper,
      Dispenser,
      Dropper,
      BrewingStand,
      Crafter,
      ChiseledBookshelf,
      DecoratedPot;
   }

   private record Target(BlockPos first, BlockPos second, StorageEspPlus.StorageType type) {
      private double centerX() {
         return (this.first.getX() + this.second.getX() + 1) * 0.5;
      }

      private double centerY() {
         return (this.first.getY() + this.second.getY() + 1) * 0.5;
      }

      private double centerZ() {
         return (this.first.getZ() + this.second.getZ() + 1) * 0.5;
      }
   }
}
