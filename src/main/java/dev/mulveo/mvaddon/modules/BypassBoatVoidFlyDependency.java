package dev.mulveo.mvaddon.modules;

import dev.mulveo.mvaddon.MVAddon;
import dev.donut.addon.mv.compat.events.entity.EntityMoveEvent;
import dev.donut.addon.mv.compat.events.world.TickEvent.Post;
import dev.donut.addon.mv.compat.events.world.TickEvent.Pre;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.settings.SettingGroup;
import dev.donut.addon.mv.compat.settings.BoolSetting;
import dev.donut.addon.mv.compat.settings.DoubleSetting;
import dev.donut.addon.mv.compat.settings.IntSetting;
import dev.donut.addon.mv.compat.settings.KeybindSetting;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.utils.misc.Keybind;
import dev.donut.addon.mv.compat.utils.misc.input.Input;
import dev.donut.addon.mv.compat.utils.player.PlayerUtils;
import dev.donut.addon.mv.compat.events.EventHandler;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;

public class BypassBoatVoidFlyDependency extends Module {
   private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
   private final SettingGroup sgVoid = this.settings.createGroup("Void Entry");
   private final SettingGroup sgFlight = this.settings.createGroup("Flight");
   private final Setting<Boolean> testMode = this.sgGeneral
      .add(
         new BoolSetting.Builder().name("test-mode")
                     .description("Moves the real boat up, restores its exact original position, and repeats without starting void flight.")
                  .defaultValue(false)
               .onChanged(this::onTestModeChanged)
            .build()
      );
   private final Setting<Double> testHeight = this.sgGeneral
      .add(
         new DoubleSetting.Builder()
                     .name("test-height")
                  .description("How far the real boat moves upward during test mode.")
               .defaultValue(10.0)
               .min(0.1)
               .sliderMax(50.0)
               .visible(this.testMode::get)
            .build()
      );
   private final Setting<Integer> testHoldTicks = this.sgGeneral
      .add(
         new IntSetting.Builder()
                        .name("test-hold-ticks")
                     .description("How long the boat stays at the test position before returning. Ten ticks is half a second.")
                  .defaultValue(10)
               .min(1)
               .sliderMax(40)
               .visible(this.testMode::get)
            .build()
      );
   private final Setting<Double> testInterval = this.sgGeneral
      .add(
         new DoubleSetting.Builder()
                     .name("test-interval")
                  .description("Seconds between test-mode cycles.")
               .defaultValue(5.0)
               .min(1.0)
               .sliderMax(30.0)
               .visible(this.testMode::get)
            .build()
      );
   private final Setting<Double> pulseHeight = this.sgGeneral
      .add(
         new DoubleSetting.Builder()
                     .name("pulse-height")
                  .description("How far above the boat the first vehicle packet is sent before returning to the real position.")
               .defaultValue(5.0)
               .min(0.1)
               .sliderMax(20.0)
               .visible(() -> !this.testMode.get())
            .build()
      );
   private final Setting<Boolean> pulseOnMount = this.sgGeneral
      .add(
         new BoolSetting.Builder().name("pulse-on-mount")
                     .description("Repeats the two-packet pulse after mounting a different boat while the module is enabled.")
                  .defaultValue(true)
               .visible(() -> !this.testMode.get())
            .build()
      );
   private final Setting<Integer> pulseReturnDelay = this.sgGeneral
      .add(
         new IntSetting.Builder()
                        .name("pulse-return-delay")
                     .description("Ticks to wait after the upward boat packet before sending the boat back down.")
                  .defaultValue(1)
               .min(0)
               .sliderMax(10)
               .visible(() -> !this.testMode.get())
            .build()
      );
   private final Setting<Boolean> repeatPulse = this.sgGeneral
      .add(
         new BoolSetting.Builder().name("repeat-pulse")
                     .description("Repeats the up-and-back vehicle packet pulse while Boat Void Fly is active.")
                  .defaultValue(true)
               .visible(() -> !this.testMode.get())
            .build()
      );
   private final Setting<Double> pulseInterval = this.sgGeneral
      .add(
         new DoubleSetting.Builder()
                     .name("pulse-interval")
                  .description("Seconds between repeated vehicle packet pulses.")
               .defaultValue(5.0)
               .min(0.25)
               .sliderMax(30.0)
               .visible(() -> !this.testMode.get() && this.repeatPulse.get())
            .build()
      );
   private final Setting<Boolean> forceMovePackets = this.sgGeneral
      .add(
         new BoolSetting.Builder().name("force-move-packets")
                  .description("Explicitly sends the boat's current position every tick after the pulse.")
               .defaultValue(true)
            .build()
      );
   private final Setting<Boolean> autoEnterVoid = this.sgVoid
      .add(
         new BoolSetting.Builder().name("auto-enter-void")
                     .description("Automatically phases the boat below the dimension's bottom boundary after the packet pulse.")
                  .defaultValue(true)
               .visible(() -> !this.testMode.get())
            .build()
      );
   private final Setting<Double> voidOffset = this.sgVoid
      .add(
         new DoubleSetting.Builder()
                     .name("void-offset")
                  .description("How many blocks below the dimension's bottom boundary the automatic descent stops.")
               .defaultValue(5.0)
               .min(1.0)
               .sliderMax(32.0)
               .visible(() -> !this.testMode.get() && this.autoEnterVoid.get())
            .build()
      );
   private final Setting<Double> autoDescendSpeed = this.sgVoid
      .add(
         new DoubleSetting.Builder()
                     .name("auto-descend-speed")
                  .description("Automatic void-entry speed in blocks per second.")
               .defaultValue(30.0)
               .min(1.0)
               .sliderMax(100.0)
               .visible(() -> !this.testMode.get() && this.autoEnterVoid.get())
            .build()
      );
   private final Setting<Double> horizontalSpeed = this.sgFlight
      .add(
         new DoubleSetting.Builder()
                  .name("horizontal-speed")
               .description("Horizontal boat speed in blocks per second.")
            .defaultValue(20.0)
            .min(0.0)
            .sliderMax(100.0)
            .build()
      );
   private final Setting<Double> verticalSpeed = this.sgFlight
      .add(
         new DoubleSetting.Builder()
                  .name("vertical-speed")
               .description("Vertical boat speed in blocks per second.")
            .defaultValue(12.0)
            .min(0.0)
            .sliderMax(50.0)
            .build()
      );
   private final Setting<Keybind> descendKey = this.sgFlight
      .add(
         new KeybindSetting.Builder()
                     .name("descend-key")
                  .description("Dedicated key for flying the boat downward without using Minecraft's dismount key.")
               .defaultValue(Keybind.fromKey(86))
            .build()
      );
   private final Setting<Double> idleFallSpeed = this.sgFlight
      .add(
         new DoubleSetting.Builder()
                  .name("idle-fall-speed")
               .description("Downward speed while no vertical key is held. Use 0 to hover.")
            .defaultValue(0.0)
            .min(0.0)
            .sliderMax(5.0)
            .build()
      );
   private final Setting<Boolean> lockYaw = this.sgFlight
      .add(
         new BoolSetting.Builder().name("lock-yaw").description("Points the boat in the same direction as the player.").defaultValue(true)
            .build()
      );
   private int pulsedBoatId;
   private boolean pulsePending;
   private int pulseReturnTicks;
   private int repeatPulseTicks;
   private double pulseBaseY;
   private Vec3 pulseOrigin = Vec3.ZERO;
   private boolean pulseStartsVoidEntry;
   private boolean autoDescending;
   private boolean warnedNoBoat;
   private AbstractBoat activeBoat;

   public BypassBoatVoidFlyDependency() {
      super(MVAddon.UTILITY, "boat-void-fly", "Pulses a mounted boat up and back with vehicle packets, then lets it fly below the world.");
   }

   public void onActivate() {
      this.pulsedBoatId = Integer.MIN_VALUE;
      this.pulsePending = false;
      this.pulseReturnTicks = 0;
      this.repeatPulseTicks = 0;
      this.pulseStartsVoidEntry = false;
      this.autoDescending = false;
      this.warnedNoBoat = false;
      this.activeBoat = null;
      if (this.testMode.get()) {
         this.info("Boat test mode: +%.1f blocks, exact return, every %.1f seconds.", this.testHeight.get(), this.testInterval.get());
      } else {
         this.info("Boat Void Fly: pulse up/back, auto-enter void, then WASD/Space/%s flight.", this.descendKey.get());
      }
   }

   public void onDeactivate() {
      AbstractBoat boat = this.getControlledBoat();
      if (boat != null) {
         if (this.pulsePending) {
            this.finishPulse(boat, false);
         }

         boat.setDeltaMovement(Vec3.ZERO);
      }

      this.pulsePending = false;
      this.autoDescending = false;
      this.restoreActiveBoat();
   }

   @EventHandler
   private void onPreTick(Pre event) {
      AbstractBoat boat = this.getControlledBoat();
      if (boat == null) {
         this.pulsePending = false;
         this.autoDescending = false;
         this.restoreActiveBoat();
         if (!this.warnedNoBoat) {
            this.warning("Mount and control a boat to start Boat Void Fly.");
            this.warnedNoBoat = true;
         }
      } else {
         this.warnedNoBoat = false;
         if (this.activeBoat != boat) {
            this.restoreActiveBoat();
            this.activeBoat = boat;
         }

         boat.noPhysics = true;
         boat.fallDistance = 0.0;
         if (this.pulsePending && this.pulsedBoatId != boat.getId()) {
            this.pulsePending = false;
         }

         if (this.pulsePending) {
            this.pulseReturnTicks--;
            if (this.pulseReturnTicks <= 0) {
               this.finishPulse(boat);
            }
         }

         if (this.pulsePending || this.pulsedBoatId != Integer.MIN_VALUE && (!this.pulseOnMount.get() || this.pulsedBoatId == boat.getId())) {
            if (!this.pulsePending && (this.testMode.get() || this.repeatPulse.get())) {
               this.repeatPulseTicks--;
               if (this.repeatPulseTicks <= 0) {
                  this.beginPulse(boat, false);
                  this.info("Repeated boat pulse sent: +%.1f blocks, then back down.", this.getCurrentPulseHeight());
               }
            }
         } else {
            this.pulsedBoatId = boat.getId();
            this.beginPulse(boat, true);
            this.info("Boat pulse sent: +%.1f blocks, then back down.", this.getCurrentPulseHeight());
         }

         if (this.lockYaw.get()) {
            boat.setYRot(this.mc.player.getYRot());
         }

         if (this.testMode.get()) {
            boat.setDeltaMovement(Vec3.ZERO);
         } else {
            Vec3 horizontal = PlayerUtils.getHorizontalVelocity(this.horizontalSpeed.get());
            boat.setDeltaMovement(horizontal.x, this.getVerticalVelocity(boat), horizontal.z);
         }
      }
   }

   @EventHandler
   private void onPostTick(Post event) {
      AbstractBoat boat = this.getControlledBoat();
      if (boat != null && !this.pulsePending && this.forceMovePackets.get()) {
         this.mc.getConnection().send(ServerboundMoveVehiclePacket.fromEntity(boat));
      }
   }

   @EventHandler
   private void onEntityMove(EntityMoveEvent event) {
      AbstractBoat boat = this.getControlledBoat();
      if (boat != null && event.entity == boat) {
         if (this.testMode.get()) {
            event.movement = new Vec3(0.0, 0.0, 0.0);
         } else {
            Vec3 horizontal = PlayerUtils.getHorizontalVelocity(this.horizontalSpeed.get());
            event.movement = new Vec3(horizontal.x, this.getVerticalVelocity(boat), horizontal.z);
         }
      }
   }

   private double getVerticalVelocity(AbstractBoat boat) {
      if (Input.isPressed(this.mc.options.keyJump)) {
         this.autoDescending = false;
         return this.verticalSpeed.get() / 20.0;
      } else if (this.autoDescending) {
         double targetY = this.mc.level.getMinY() - this.voidOffset.get();
         if (boat.getY() <= targetY) {
            this.autoDescending = false;
            this.info("Boat reached void-flight level at Y %.1f.", targetY);
            return 0.0;
         } else {
            return -this.autoDescendSpeed.get() / 20.0;
         }
      } else {
         return (this.descendKey.get()).isPressed() ? -this.verticalSpeed.get() / 20.0 : -this.idleFallSpeed.get() / 20.0;
      }
   }

   private void beginPulse(AbstractBoat boat, boolean startVoidEntry) {
      Vec3 original = boat.position();
      this.pulseBaseY = original.y;
      this.pulseOrigin = original;
      this.pulseStartsVoidEntry = startVoidEntry && !this.testMode.get();
      double height = this.getCurrentPulseHeight();
      boat.setPos(original.add(0.0, height, 0.0));
      this.mc.getConnection().send(ServerboundMoveVehiclePacket.fromEntity(boat));
      this.pulseReturnTicks = this.testMode.get() ? this.testHoldTicks.get() : this.pulseReturnDelay.get();
      this.pulsePending = this.pulseReturnTicks > 0;
      if (!this.pulsePending) {
         this.finishPulse(boat);
      }
   }

   private void finishPulse(AbstractBoat boat) {
      this.finishPulse(boat, this.pulseStartsVoidEntry);
   }

   private void finishPulse(AbstractBoat boat, boolean startVoidEntry) {
      Vec3 current = boat.position();
      if (this.testMode.get()) {
         boat.setPos(this.pulseOrigin);
      } else {
         boat.setPos(new Vec3(current.x, this.pulseBaseY, current.z));
      }

      this.mc.getConnection().send(ServerboundMoveVehiclePacket.fromEntity(boat));
      this.pulsePending = false;
      this.pulseStartsVoidEntry = false;
      double interval = this.testMode.get() ? this.testInterval.get() : this.pulseInterval.get();
      this.repeatPulseTicks = Math.max(1, (int)Math.round(interval * 20.0));
      if (startVoidEntry && this.autoEnterVoid.get()) {
         this.autoDescending = true;
      }

      if (this.autoDescending) {
         double targetY = this.mc.level.getMinY() - this.voidOffset.get();
         this.info("Auto-entering void to Y %.1f. Press Space to cancel.", targetY);
      }
   }

   private void restoreActiveBoat() {
      if (this.activeBoat != null) {
         this.activeBoat.noPhysics = false;
         this.activeBoat = null;
      }
   }

   private void onTestModeChanged(boolean enabled) {
      AbstractBoat boat = this.getControlledBoat();
      if (this.pulsePending && boat != null) {
         this.finishPulse(boat, false);
      }

      this.pulsePending = false;
      this.autoDescending = false;
      this.pulsedBoatId = Integer.MIN_VALUE;
      this.repeatPulseTicks = 0;
   }

   private double getCurrentPulseHeight() {
      return this.testMode.get() ? this.testHeight.get() : this.pulseHeight.get();
   }

   private AbstractBoat getControlledBoat() {
      if (this.mc.player != null && this.mc.level != null && this.mc.getConnection() != null) {
         if (this.mc.player.getVehicle() instanceof AbstractBoat boat) {
            return boat.getControllingPassenger() != this.mc.player ? null : boat;
         } else {
            return null;
         }
      } else {
         return null;
      }
   }
}
