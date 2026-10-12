package dev.mulveo.mvaddon.modules;

import dev.mulveo.mvaddon.MVAddon;
import dev.mulveo.mvaddon.toast.MVToast;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import dev.donut.addon.mv.compat.events.packets.PacketEvent.Receive;
import dev.donut.addon.mv.compat.events.render.Render3DEvent;
import dev.donut.addon.mv.compat.events.world.TickEvent.Pre;
import dev.donut.addon.mv.compat.renderer.ShapeMode;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.settings.ColorSetting;
import dev.donut.addon.mv.compat.settings.DoubleSetting;
import dev.donut.addon.mv.compat.settings.EnumSetting;
import dev.donut.addon.mv.compat.settings.BoolSetting;
import dev.donut.addon.mv.compat.settings.SettingGroup;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.utils.player.ChatUtils;
import dev.donut.addon.mv.compat.utils.render.color.Color;
import dev.donut.addon.mv.compat.utils.render.color.SettingColor;
import dev.donut.addon.mv.compat.events.EventHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class mv1 extends Module {
   private static final Logger LOG = LoggerFactory.getLogger("Activity Inspector");
   private static final int MAX_DRAIN = 4096;
   private static final int MAX_CACHE = 32768;
   private static final int RENDER_DISTANCE = 16;
   private static final int NIBBLES = 2048;
   private static final byte UNKNOWN = 0;
   private static final byte NORMAL = 1;
   private static final byte EMPTY = 2;
   private static final byte ZERO = 3;
   private static final byte FULL = 4;
   private static final int COUNT = 0;
   private static final int BOTTOM = 1;
   private static final int BLOCK = 2;
   private static final int SKY = 3;
   private static final int BLOCK_TIMES = 4;
   private static final int SKY_TIMES = 5;
   private final ConcurrentLinkedQueue<Object[]> queue = new ConcurrentLinkedQueue<>();
   private final Long2ObjectOpenHashMap<Object[]> snapshots = new Long2ObjectOpenHashMap();
   private final Long2IntOpenHashMap confidence = new Long2IntOpenHashMap();
   private final LongOpenHashSet alerted = new LongOpenHashSet();
   private ClientLevel lastWorld;
   private final SettingGroup sgAlerts = this.settings.createGroup("Alerts");
   private final SettingGroup sgPlate = this.settings.createGroup("Plate");
   private final Setting<Boolean> toastOnFlag = this.sgAlerts
      .add(new BoolSetting.Builder().name("toast-on-flag").description("Shows a chest notification when a chunk is flagged.").defaultValue(true).build());
   private final Setting<Boolean> chatOnFlag = this.sgAlerts
      .add(new BoolSetting.Builder().name("chat-on-flag").description("Shows the flag in chat.").defaultValue(true).build());
   private final Setting<Boolean> leaveOnFlag = this.sgAlerts
      .add(
         new BoolSetting.Builder().name("leave-on-flag").description("Disconnects normally and shows the flagged coordinates.")
               .defaultValue(false)
            .build()
      );
   private final Setting<Boolean> renderPlate = this.sgPlate
      .add(new BoolSetting.Builder().name("plate").description("Shows a plate over flagged chunks.").defaultValue(true).build());
   private final Setting<Boolean> doublePlate = this.sgPlate
      .add(
         new BoolSetting.Builder().name("white-blue-style").description("Uses the white and blue double plate.")
                  .defaultValue(true)
               .visible(this.renderPlate::get)
            .build()
      );
   private final Setting<ShapeMode> shapeMode = this.sgPlate
      .add(
         new EnumSetting.Builder<ShapeMode>()
                        .name("draw-mode")
                     .description("Plate fill and outline.")
                  .defaultValue(ShapeMode.Sides)
               .visible(this.renderPlate::get)
            .build()
      );
   private final Setting<Double> plateY = this.sgPlate
      .add(
         new DoubleSetting.Builder()
                     .name("height")
                  .description("Plate height.")
               .defaultValue(64.0)
               .range(-64.0, 320.0)
               .sliderRange(-64.0, 200.0)
               .visible(this.renderPlate::get)
            .build()
      );
   private final Setting<Double> plateThickness = this.sgPlate
      .add(
         new DoubleSetting.Builder()
                     .name("thickness")
                  .description("Plate thickness.")
               .defaultValue(0.06)
               .range(0.01, 1.0)
               .sliderRange(0.01, 0.35)
               .visible(this.renderPlate::get)
            .build()
      );
   private final Setting<Double> plateGap = this.sgPlate
      .add(
         new DoubleSetting.Builder()
                     .name("gap")
                  .description("Space between plates.")
               .defaultValue(0.12)
               .range(0.0, 2.0)
               .sliderRange(0.0, 1.0)
               .visible(this.renderPlate::get)
            .build()
      );
   private final Setting<SettingColor> normalColor = this.sgPlate
      .add(
         new ColorSetting.Builder()
                     .name("normal-color")
                  .description("Single plate color.")
               .defaultValue(new SettingColor(76, 166, 255, 120))
               .visible(() -> this.renderPlate.get() && !this.doublePlate.get())
            .build()
      );
   private final Setting<SettingColor> whiteColor = this.sgPlate
      .add(
         new ColorSetting.Builder()
                     .name("white-color")
                  .description("Outer plate color.")
               .defaultValue(new SettingColor(245, 247, 250, 120))
               .visible(() -> this.renderPlate.get() && this.doublePlate.get())
            .build()
      );
   private final Setting<SettingColor> blueColor = this.sgPlate
      .add(
         new ColorSetting.Builder()
                     .name("blue-color")
                  .description("Inner plate color.")
               .defaultValue(new SettingColor(125, 205, 235, 190))
               .visible(() -> this.renderPlate.get() && this.doublePlate.get())
            .build()
      );

   public mv1() {
      super(MVAddon.BASEFINDING, "Activity Inspector", "Detects suspicious block-light changes from chunk light updates.");
      this.confidence.defaultReturnValue(0);
   }

   public void onActivate() {
      this.clear();
      this.lastWorld = this.mc.level;
   }

   public void onDeactivate() {
      this.clear();
      this.lastWorld = null;
   }

   @EventHandler
   private void onPacket(Receive event) {
      if (event.packet instanceof ClientboundLevelChunkWithLightPacket packet) {
         this.offer(packet.x(), packet.z(), packet.lightData());
      } else if (event.packet instanceof ClientboundLightUpdatePacket packet) {
         this.offer(packet.x(), packet.z(), packet.lightData());
      }
   }

   @EventHandler
   private void onTick(Pre event) {
      ClientLevel world = this.mc.level;
      if (world != this.lastWorld) {
         this.clear();
         this.lastWorld = world;
      }

      if (world != null) {
         int sections = world.getSectionsCount();
         int bottom = world.getMinSectionY();

         Object[] pending;
         for (int i = 0; i < 4096 && (pending = this.queue.poll()) != null; i++) {
            this.ingest(pending, sections, bottom);
         }

         if (this.snapshots.size() > 32768) {
            this.clear();
         }
      }
   }

   private void offer(int x, int z, ClientboundLightUpdatePacketData data) {
      if (data != null) {
         this.queue.offer(new Object[]{x, z, data, System.nanoTime()});
      }
   }

   private void clear() {
      this.queue.clear();
      this.snapshots.clear();
      this.confidence.clear();
      this.alerted.clear();
   }

   private void ingest(Object[] pending, int sectionCount, int bottomSection) {
      int chunkX = (Integer)pending[0];
      int chunkZ = (Integer)pending[1];
      ClientboundLightUpdatePacketData data = (ClientboundLightUpdatePacketData)pending[2];
      long time = (Long)pending[3];
      long key = this.key(chunkX, chunkZ);
      Object[] snapshot = (Object[])this.snapshots.get(key);
      if (snapshot == null || (Integer)snapshot[0] != sectionCount || (Integer)snapshot[1] != bottomSection) {
         int size = sectionCount + 2;
         snapshot = new Object[]{sectionCount, bottomSection, new byte[size], new byte[size], new long[size], new long[size]};
         this.snapshots.put(key, snapshot);
      }

      try {
         this.track(data.blockYMask(), data.emptyBlockYMask(), data.blockUpdates(), (byte[])snapshot[2], (long[])snapshot[4], time);
         this.track(data.skyYMask(), data.emptySkyYMask(), data.skyUpdates(), (byte[])snapshot[3], (long[])snapshot[5], time);
      } catch (Throwable var13) {
         LOG.error("[Activity Inspector] Failed to classify light for chunk {}, {}", new Object[]{chunkX, chunkZ, var13});
         return;
      }

      int score = this.score(snapshot);
      if (score > 0) {
         if (score > this.confidence.get(key)) {
            this.confidence.put(key, score);
         }

         if (this.alerted.add(key)) {
            this.flag(chunkX, chunkZ);
         }
      }
   }

   private void track(BitSet initialized, BitSet uninitialized, List<byte[]> payloads, byte[] output, long[] times, long time) {
      int payload = 0;

      for (int i = 0; i < output.length; i++) {
         if (initialized.get(i)) {
            byte[] bytes = payload < payloads.size() ? payloads.get(payload) : null;
            payload++;
            output[i] = this.classify(bytes);
            times[i] = time;
         } else if (uninitialized.get(i)) {
            output[i] = 2;
            times[i] = time;
         }
      }
   }

   private byte classify(byte[] data) {
      if (data != null && data.length == 2048) {
         boolean zero = true;
         boolean full = true;

         for (byte value : data) {
            if (value != 0) {
               zero = false;
            }

            if (value != -1) {
               full = false;
            }

            if (!zero && !full) {
               return 1;
            }
         }

         return zero ? 3 : (byte)(full ? 4 : 1);
      } else {
         return 0;
      }
   }

   private int score(Object[] snapshot) {
      int sectionCount = (Integer)snapshot[0];
      int bottomSection = (Integer)snapshot[1];
      byte[] block = (byte[])snapshot[2];
      byte[] sky = (byte[])snapshot[3];
      int zeroed = 0;
      int normalBlock = 0;
      int longest = 0;
      int run = 0;
      int lowest = Integer.MAX_VALUE;

      for (int i = 1; i < sectionCount + 1; i++) {
         int sectionY = bottomSection + i - 1;
         int minY = sectionY * 16;
         int maxY = minY + 15;
         if (maxY >= -64 && minY <= 184 && maxY < 62) {
            if (sky[i] == 3) {
               zeroed++;
               longest = Math.max(longest, ++run);
               lowest = Math.min(lowest, maxY);
               if (block[i] == 1 || block[i] == 4) {
                  normalBlock++;
               }
            } else {
               run = 0;
            }
         } else {
            run = 0;
         }
      }

      if (zeroed == 0) {
         return 0;
      } else {
         int result = 76 + Math.min(12, (zeroed - 1) * 4);
         if (longest >= 2) {
            result += 5;
         }

         if (longest >= 3) {
            result += 3;
         }

         result += Math.min(7, normalBlock * 4);
         if (lowest <= 0) {
            result += 2;
         }

         if (lowest <= -32) {
            result += 2;
         }

         return Math.min(99, result);
      }
   }

   private void flag(int chunkX, int chunkZ) {
      int x = (chunkX << 4) + 8;
      int z = (chunkZ << 4) + 8;
      int y = (int)Math.round((Double)this.plateY.get());
      if ((Boolean)this.chatOnFlag.get()) {
         ChatUtils.sendMsg(Component.literal("§9[Activity Inspector] §bChunk flagged at §f" + x + ", " + y + ", " + z));
      }

      if ((Boolean)this.toastOnFlag.get()) {
         this.mc.gui.toastManager().addToast(MVToast.chunkAlert("Chunk Flagged", x, y, z));
      }

      if ((Boolean)this.leaveOnFlag.get()) {
         this.mc.execute(() -> {
            if (this.mc.getConnection() != null) {
               this.mc.getConnection().getConnection().disconnect(Component.literal("Chunk found at " + x + ", " + y + ", " + z));
            }
         });
      }
   }

   @EventHandler
   private void onRender3d(Render3DEvent event) {
      if (this.renderPlate.get() && this.mc.level != null && this.mc.player != null && !this.snapshots.isEmpty()) {
         int centerX = this.mc.player.getBlockX() >> 4;
         int centerZ = this.mc.player.getBlockZ() >> 4;

         for (int chunkX = centerX - 16; chunkX <= centerX + 16; chunkX++) {
            for (int chunkZ = centerZ - 16; chunkZ <= centerZ + 16; chunkZ++) {
               long key = ChunkPos.pack(chunkX, chunkZ);
               Object[] snapshot = (Object[])this.snapshots.get(key);
               if (snapshot != null && (this.alerted.contains(key) || this.hasSignal(snapshot))) {
                  this.drawPlate(event, chunkX, chunkZ);
               }
            }
         }
      }
   }

   private boolean hasSignal(Object[] snapshot) {
      int count = (Integer)snapshot[0];
      int bottom = (Integer)snapshot[1];
      byte[] sky = (byte[])snapshot[3];

      for (int i = 1; i < count + 1; i++) {
         int top = (bottom + i - 1) * 16 + 15;
         if (top < 62 && sky[i] == 3) {
            return true;
         }
      }

      return false;
   }

   private void drawPlate(Render3DEvent event, int chunkX, int chunkZ) {
      int x = chunkX << 4;
      int z = chunkZ << 4;
      double y = (Double)this.plateY.get();
      double thickness = (Double)this.plateThickness.get();
      double gap = (Double)this.plateGap.get();
      if (!this.doublePlate.get()) {
         Color color = new Color((Color)this.normalColor.get());
         event.renderer.box(new AABB(x + gap, y, z + gap, x + 16 - gap, y + thickness, z + 16 - gap), color, color, (ShapeMode)this.shapeMode.get(), 0);
      } else {
         Color white = new Color((Color)this.whiteColor.get());
         Color blue = new Color((Color)this.blueColor.get());
         event.renderer.box(new AABB(x + gap, y, z + gap, x + 16 - gap, y + thickness, z + 16 - gap), white, white, (ShapeMode)this.shapeMode.get(), 0);
         double inner = Math.min(7.5, Math.max(gap + 1.75, 2.0));
         event.renderer
            .box(
               new AABB(x + inner, y + thickness + 0.01, z + inner, x + 16 - inner, y + thickness * 2.0 + 0.01, z + 16 - inner),
               blue,
               blue,
               (ShapeMode)this.shapeMode.get(),
               0
            );
      }
   }

   private long key(int x, int z) {
      return x & 4294967295L | (z & 4294967295L) << 32;
   }
}
