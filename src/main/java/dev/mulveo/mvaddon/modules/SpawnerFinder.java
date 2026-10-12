package dev.mulveo.mvaddon.modules;

import dev.mulveo.mvaddon.MVAddon;
import dev.mulveo.mvaddon.toast.MVToast;
import java.util.LinkedHashSet;
import java.util.Set;
import dev.donut.addon.mv.compat.events.game.GameJoinedEvent;
import dev.donut.addon.mv.compat.events.game.GameLeftEvent;
import dev.donut.addon.mv.compat.events.render.Render3DEvent;
import dev.donut.addon.mv.compat.events.world.ChunkDataEvent;
import dev.donut.addon.mv.compat.events.world.TickEvent.Post;
import dev.donut.addon.mv.compat.renderer.ShapeMode;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.settings.ColorSetting;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.utils.Utils;
import dev.donut.addon.mv.compat.utils.render.color.Color;
import dev.donut.addon.mv.compat.utils.render.color.SettingColor;
import dev.donut.addon.mv.compat.events.EventHandler;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class SpawnerFinder extends Module {
   private static final int PILLAR_MIN_Y = -64;
   private static final int PILLAR_MAX_Y = 200;
   private final Setting<SettingColor> pillarColor = this.settings
      .getDefaultGroup()
      .add(
         new ColorSetting.Builder().name("beacon-color").description("The color of the beacon beam rendered at a found spawner.")
            .defaultValue(new SettingColor(255, 0, 0, 90))
            .build()
      );
   private final Set<BlockPos> foundSpawners = new LinkedHashSet<>();

   public SpawnerFinder() {
      super(MVAddon.CATEGORY, "Spawner Finder", "Finds loaded spawners and marks their locations.");
   }

   public void onActivate() {
      this.foundSpawners.clear();
      if (this.mc.level != null) {
         for (ChunkAccess chunk : Utils.chunks()) {
            this.scanChunk(chunk);
         }

         for (BlockEntity blockEntity : this.mc.level.getGloballyRenderedBlockEntities()) {
            this.scanBlockEntity(blockEntity);
         }
      }
   }

   public void onDeactivate() {
      this.foundSpawners.clear();
   }

   @EventHandler
   private void onGameJoined(GameJoinedEvent event) {
      this.foundSpawners.clear();
   }

   @EventHandler
   private void onGameLeft(GameLeftEvent event) {
      this.foundSpawners.clear();
   }

   @EventHandler
   private void onTick(Post event) {
      if (this.mc.level != null) {
         for (BlockEntity blockEntity : this.mc.level.getGloballyRenderedBlockEntities()) {
            this.scanBlockEntity(blockEntity);
         }
      }
   }

   @EventHandler
   private void onChunkData(ChunkDataEvent event) {
      this.scanChunk(event.chunk());
   }

   @EventHandler
   private void onRender3D(Render3DEvent event) {
      SettingColor selected = (SettingColor)this.pillarColor.get();
      Color glow = new Color(selected.r, selected.g, selected.b, Math.min(selected.a, 32));
      Color core = new Color(selected.r, selected.g, selected.b, Math.max(selected.a, 150));
      Color outline = new Color(selected.r, selected.g, selected.b, 255);
      double pulse = (Math.sin(System.currentTimeMillis() / 220.0) + 1.0) * 0.025;

      for (BlockPos pos : this.foundSpawners) {
         event.renderer
            .box(
               pos.getX() + 0.08 - pulse,
               -64.0,
               pos.getZ() + 0.08 - pulse,
               pos.getX() + 0.92 + pulse,
               200.0,
               pos.getZ() + 0.92 + pulse,
               glow,
               glow,
               ShapeMode.Sides,
               0
            );
         event.renderer.box(pos.getX() + 0.38, -64.0, pos.getZ() + 0.38, pos.getX() + 0.62, 200.0, pos.getZ() + 0.62, core, outline, ShapeMode.Both, 0);
         event.renderer.box(pos, glow, outline, ShapeMode.Both, 0);
      }
   }

   private void scanChunk(ChunkAccess chunk) {
      if (chunk != null) {
         LevelChunkSection[] sections = chunk.getSections();
         int chunkStartX = chunk.getPos().x() << 4;
         int chunkStartZ = chunk.getPos().z() << 4;
         int bottomY = chunk.getMinY();

         for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section != null && !section.hasOnlyAir() && section.maybeHas(state -> state.is(Blocks.SPAWNER))) {
               int sectionStartY = bottomY + (sectionIndex << 4);

               for (int localY = 0; localY < 16; localY++) {
                  for (int localZ = 0; localZ < 16; localZ++) {
                     for (int localX = 0; localX < 16; localX++) {
                        if (section.getBlockState(localX, localY, localZ).is(Blocks.SPAWNER)) {
                           this.detectPosition(new BlockPos(chunkStartX + localX, sectionStartY + localY, chunkStartZ + localZ));
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private void scanBlockEntity(BlockEntity blockEntity) {
      if (blockEntity instanceof SpawnerBlockEntity spawner) {
         this.detectPosition(spawner.getBlockPos().immutable());
      }
   }

   private void detectPosition(BlockPos position) {
      BlockPos pos = position.immutable();
      if (this.foundSpawners.add(pos)) {
         MVToast toast = new MVToast(
            "Spawner Located",
            "X: " + pos.getX() + "  Y: " + pos.getY() + "  Z: " + pos.getZ(),
            Items.SPAWNER,
            SimpleSoundInstance.forUI(SoundEvents.AMETHYST_CLUSTER_STEP, 1.0F, 1.0F)
         );
         this.mc.gui.toastManager().addToast(toast);
      }
   }
}
