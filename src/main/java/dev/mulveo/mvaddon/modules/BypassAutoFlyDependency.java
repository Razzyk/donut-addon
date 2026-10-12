package dev.mulveo.mvaddon.modules;

import dev.mulveo.mvaddon.MVAddon;
import dev.donut.addon.mv.compat.events.entity.player.PlayerMoveEvent;
import dev.donut.addon.mv.compat.events.world.TickEvent.Pre;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.settings.SettingGroup;
import dev.donut.addon.mv.compat.settings.BoolSetting;
import dev.donut.addon.mv.compat.settings.DoubleSetting;
import dev.donut.addon.mv.compat.settings.KeybindSetting;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.utils.misc.Keybind;
import dev.donut.addon.mv.compat.utils.misc.input.Input;
import dev.donut.addon.mv.compat.utils.player.PlayerUtils;
import dev.donut.addon.mv.compat.events.EventHandler;
import net.minecraft.world.phys.Vec3;

public class BypassAutoFlyDependency extends Module {
   private static final double POSITION_EPSILON = 1.0E-4;
   private final SettingGroup sgFlight = this.settings.getDefaultGroup();
   private final SettingGroup sgPhaseHop = this.settings.createGroup("Phase Hop");
   private boolean descending;
   private final Setting<Double> horizontalSpeed = this.sgFlight
      .add(
         new DoubleSetting.Builder().name("horizontal-speed").description("Horizontal phase-flight speed in blocks per second.")
            .defaultValue(15.0)
            .min(0.0)
            .sliderMax(100.0)
            .build()
      );
   private final Setting<Double> verticalSpeed = this.sgFlight
      .add(
         new DoubleSetting.Builder().name("vertical-speed").description("Manual vertical phase-flight speed in blocks per second.")
            .defaultValue(12.0)
            .min(0.0)
            .sliderMax(50.0)
            .build()
      );
   private final Setting<Boolean> continuousDescend = this.sgFlight
      .add(
         new BoolSetting.Builder()
                        .name("continuous-descend")
                     .description("Makes the descend key toggle continuous downward flight between phase hops.")
                  .defaultValue(true)
               .onChanged(value -> this.descending = false)
            .build()
      );
   private final Setting<Keybind> descendKey = this.sgFlight
      .add(
         new KeybindSetting.Builder()
                     .name("descend-key")
                  .description("Dedicated downward flight key that does not invoke Minecraft sneak.")
               .defaultValue(Keybind.fromKey(86))
            .action(() -> {
               if (this.continuousDescend.get()) {
                  this.descending = !this.descending;
                  this.info(this.descending ? "Continuous descent started." : "Continuous descent stopped.");
               }
            })
            .build()
      );
   private final Setting<Double> hopHeight = this.sgPhaseHop
      .add(
         new DoubleSetting.Builder().name("hop-height").description("How far the automatic phase movement rises before returning to its starting Y.")
            .defaultValue(10.0)
            .min(0.1)
            .sliderMax(50.0)
            .build()
      );
   private final Setting<Double> hopSpeed = this.sgPhaseHop
      .add(
         new DoubleSetting.Builder().name("hop-speed").description("Up-and-down phase-hop speed in blocks per second.")
            .defaultValue(200.0)
            .min(1.0)
            .sliderMax(500.0)
            .build()
      );
   private final Setting<Double> hopInterval = this.sgPhaseHop
      .add(
         new DoubleSetting.Builder().name("hop-interval").description("Seconds between automatic phase hops, measured start to start.")
            .defaultValue(3.0)
            .min(0.25)
            .sliderMax(30.0)
            .build()
      );
   private BypassAutoFlyDependency.HopState hopState = BypassAutoFlyDependency.HopState.Idle;
   private double hopBaseY;
   private int nextHopTicks;
   private boolean warnedVehicle;
   private Vec3 tickVelocity = Vec3.ZERO;

   public BypassAutoFlyDependency() {
      super(MVAddon.UTILITY, "auto-fly", "Phase flight that rapidly rises 10 blocks and returns every three seconds.");
   }

   public void onActivate() {
      this.descending = false;
      this.hopState = BypassAutoFlyDependency.HopState.Idle;
      this.nextHopTicks = 0;
      this.warnedVehicle = false;
      this.tickVelocity = Vec3.ZERO;
      this.info("AutoFly uses real Phase movement: 10 blocks up and back every %.1f seconds.", this.hopInterval.get());
   }

   public void onDeactivate() {
      this.descending = false;
      this.hopState = BypassAutoFlyDependency.HopState.Idle;
      if (this.mc.player != null) {
         this.mc.player.noPhysics = false;
         this.mc.player.setDeltaMovement(Vec3.ZERO);
      }
   }

   @EventHandler
   private void onPreTick(Pre event) {
      if (this.canFly()) {
         this.warnedVehicle = false;
         this.mc.player.noPhysics = true;
         this.mc.player.fallDistance = 0.0;
         if (this.nextHopTicks > 0) {
            this.nextHopTicks--;
         }

         if (this.hopState == BypassAutoFlyDependency.HopState.Idle && this.nextHopTicks <= 0) {
            this.beginHop();
         }

         this.tickVelocity = this.getAutoFlyVelocity();
         this.mc.player.setDeltaMovement(this.tickVelocity);
      }
   }

   @EventHandler
   private void onPlayerMove(PlayerMoveEvent event) {
      if (this.canFly()) {
         event.movement = new Vec3(this.tickVelocity.x, this.tickVelocity.y, this.tickVelocity.z);
      }
   }

   private void beginHop() {
      this.hopBaseY = this.mc.player.getY();
      this.hopState = BypassAutoFlyDependency.HopState.Ascending;
      this.nextHopTicks = Math.max(1, (int)Math.round(this.hopInterval.get() * 20.0));
   }

   private Vec3 getAutoFlyVelocity() {
      Vec3 horizontal = PlayerUtils.getHorizontalVelocity(this.horizontalSpeed.get());

      double vertical = switch (this.hopState) {
         case Idle -> this.getManualVerticalVelocity();
         case Ascending -> this.getAscendingVelocity();
         case Descending -> this.getDescendingVelocity();
      };
      return new Vec3(horizontal.x, vertical, horizontal.z);
   }

   private double getAscendingVelocity() {
      double remaining = this.hopBaseY + this.hopHeight.get() - this.mc.player.getY();
      if (remaining <= 1.0E-4) {
         this.hopState = BypassAutoFlyDependency.HopState.Descending;
         return this.getDescendingVelocity();
      } else {
         return Math.min(this.hopSpeed.get() / 20.0, remaining);
      }
   }

   private double getDescendingVelocity() {
      double remaining = this.mc.player.getY() - this.hopBaseY;
      if (remaining <= 1.0E-4) {
         this.hopState = BypassAutoFlyDependency.HopState.Idle;
         return this.getManualVerticalVelocity();
      } else {
         return -Math.min(this.hopSpeed.get() / 20.0, remaining);
      }
   }

   private double getManualVerticalVelocity() {
      double vertical = 0.0;
      if (Input.isPressed(this.mc.options.keyJump)) {
         this.descending = false;
         vertical += this.verticalSpeed.get() / 20.0;
      }

      if ((this.descendKey.get()).isPressed() || this.continuousDescend.get() && this.descending) {
         vertical -= this.verticalSpeed.get() / 20.0;
      }

      return vertical;
   }

   private boolean canFly() {
      if (this.mc.player == null || this.mc.level == null) {
         return false;
      } else if (this.mc.player.getVehicle() == null) {
         return true;
      } else {
         if (!this.warnedVehicle) {
            this.warning("AutoFly is player-only. Dismount before enabling flight.");
            this.warnedVehicle = true;
         }

         this.hopState = BypassAutoFlyDependency.HopState.Idle;
         this.tickVelocity = Vec3.ZERO;
         this.mc.player.noPhysics = false;
         return false;
      }
   }

   private static enum HopState {
      Idle,
      Ascending,
      Descending;
   }
}
