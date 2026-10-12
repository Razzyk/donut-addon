package dev.mulveo.mvaddon.modules;

import dev.mulveo.mvaddon.MVAddon;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
                  .description("Ticks between loaded-storage cache refreshes. One is fastest.")
               .defaultValue(1)
            .range(1, 20)
            .sliderRange(1, 20)
            .build()
      );
   private final Setting<Boolean> chests = this.toggle("chests", "Detect normal and trapped chests.", true);
   private final Setting<Boolean> enderChests = this.toggle("ender-chests", "Detect ender chests.", true);
   private final Setting<Boolean> shulkers = this.toggle("shulker-boxes", "Detect every shulker box color.", true);
   private final Setting<Boolean> spawners = this.toggle("spawners", "Detect monster spawners.", false);
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
      this.scanTimer = (Integer)this.scanDelay.get();
      this.refreshTargets();
   }

   public void onDeactivate() {
      this.targets = List.of();
   }

   @EventHandler
   private void onGameJoined(GameJoinedEvent event) {
      this.targets = List.of();
      this.scanTimer = (Integer)this.scanDelay.get();
   }

   @EventHandler
   private void onGameLeft(GameLeftEvent event) {
      this.targets = List.of();
   }

   @EventHandler
   private void onTick(Post event) {
      if (this.mc.level != null && this.mc.player != null) {
         if (++this.scanTimer >= (Integer)this.scanDelay.get()) {
            this.scanTimer = 0;
            this.refreshTargets();
         }
      } else {
         this.targets = List.of();
      }
   }

   @EventHandler
   private void onRender(Render3DEvent event) {
      if (this.mc.level != null && this.mc.player != null) {
         double amount = (Double)this.inset.get();
         double maxDistance = (Double)this.renderDistance.get();
         double maxDistanceSq = maxDistance * maxDistance;
         double themeOpacity = 0.72;
         int rendered = 0;

         for (StorageEspPlus.Target target : this.targets) {
            if (this.enabled(target.type)) {
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

   private void refreshTargets() {
      if (this.mc.level != null && this.mc.player != null) {
         int viewDistance = (Integer)this.mc.options.renderDistance().get();
         ChunkPos center = this.mc.player.chunkPosition();
         List<StorageEspPlus.Target> refreshed = new ArrayList<>();
         Set<BlockPos> consumedChests = new HashSet<>();

         for (int x = center.x() - viewDistance; x <= center.x() + viewDistance; x++) {
            for (int z = center.z() - viewDistance; z <= center.z() + viewDistance; z++) {
               LevelChunk chunk = this.mc.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
               if (chunk != null) {
                  for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                     StorageEspPlus.StorageType type = this.typeOf(blockEntity);
                     if (type != null) {
                        BlockPos pos = blockEntity.getBlockPos().immutable();
                        if (type == StorageEspPlus.StorageType.Chest) {
                           if (consumedChests.contains(pos)) {
                              continue;
                           }

                           BlockPos partner = this.chestPartner(pos);
                           if (partner != null) {
                              consumedChests.add(pos);
                              consumedChests.add(partner);
                              refreshed.add(new StorageEspPlus.Target(pos, partner, type));
                              continue;
                           }
                        }

                        refreshed.add(new StorageEspPlus.Target(pos, pos, type));
                     }
                  }
               }
            }
         }

         refreshed.sort(Comparator.comparingDouble(this::distanceSquared));
         this.targets = List.copyOf(refreshed);
      }
   }

   private BlockPos chestPartner(BlockPos pos) {
      if (this.mc.level == null) {
         return null;
      } else {
         BlockState state = this.mc.level.getBlockState(pos);
         if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos partner = pos.relative(ChestBlock.getConnectedDirection(state));
            BlockState partnerState = this.mc.level.getBlockState(partner);
            return !(partnerState.getBlock() instanceof ChestBlock) ? null : partner.immutable();
         } else {
            return null;
         }
      }
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

   private static enum StorageType {
      Chest,
      EnderChest,
      Shulker,
      Spawner,
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
