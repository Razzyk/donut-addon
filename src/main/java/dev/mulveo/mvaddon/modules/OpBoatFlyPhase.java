package dev.mulveo.mvaddon.modules;

import dev.mulveo.mvaddon.MVAddon;
import dev.donut.addon.mv.compat.events.entity.EntityMoveEvent;
import dev.donut.addon.mv.compat.events.entity.player.PlayerMoveEvent;
import dev.donut.addon.mv.compat.events.world.TickEvent.Pre;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.settings.SettingGroup;
import dev.donut.addon.mv.compat.settings.BoolSetting;
import dev.donut.addon.mv.compat.settings.DoubleSetting;
import dev.donut.addon.mv.compat.settings.KeybindSetting;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.systems.modules.Modules;
import dev.donut.addon.mv.compat.utils.misc.Keybind;
import dev.donut.addon.mv.compat.utils.misc.input.Input;
import dev.donut.addon.mv.compat.utils.player.PlayerUtils;
import dev.donut.addon.mv.compat.events.EventHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;

public class OpBoatFlyPhase extends Module {
   private final SettingGroup sgMovement = this.settings.getDefaultGroup();
   private boolean descending;
   private final Setting<Double> horizontalSpeed = this.sgMovement
      .add(
         new DoubleSetting.Builder().name("horizontal-speed").description("Horizontal phase speed in blocks per second.")
            .defaultValue(15.0)
            .min(0.0)
            .sliderMax(100.0)
            .build()
      );
   private final Setting<Double> verticalSpeed = this.sgMovement
      .add(
         new DoubleSetting.Builder().name("vertical-speed").description("Vertical phase speed in blocks per second.")
            .defaultValue(12.0)
            .min(0.0)
            .sliderMax(50.0)
            .build()
      );
   private final Setting<Boolean> continuousDescend = this.sgMovement
      .add(
         new BoolSetting.Builder()
                        .name("continuous-descend")
                     .description("Makes the descend key toggle continuous downward movement instead of requiring it to be held.")
                  .defaultValue(true)
               .onChanged(value -> this.descending = false)
            .build()
      );
   private final Setting<Keybind> descendKey = this.sgMovement
      .add(
         new KeybindSetting.Builder()
                     .name("descend-key")
                  .description("Dedicated key for phasing down through the ground without using Minecraft's dismount key.")
               .defaultValue(Keybind.fromKey(86))
            .action(() -> {
               if (this.continuousDescend.get()) {
                  this.descending = !this.descending;
                  this.info(this.descending ? "Continuous descent started." : "Continuous descent stopped.");
               }
            })
            .build()
      );
   private final Setting<Boolean> lockBoatYaw = this.sgMovement
      .add(
         new BoolSetting.Builder()
                     .name("lock-boat-yaw")
                  .description("Points a phased boat in the same direction as the player.")
               .defaultValue(true)
            .build()
      );
   private Entity phasedEntity;
   private boolean warnedPassenger;

   public OpBoatFlyPhase() {
      super(MVAddon.UTILITY, "OpBoatFly/Phase", "Controls player and boat phasing with adjustable movement speeds.");
   }

   public void onActivate() {
      this.phasedEntity = null;
      this.warnedPassenger = false;
      this.descending = false;
      this.info("Phase controls: WASD to move, Space up, tap %s to toggle continuous down.", this.descendKey.get());
   }

   public void onDeactivate() {
      this.descending = false;
      this.restoreNoClip();
      if (this.mc.player != null) {
         this.mc.player.noPhysics = false;
         Entity vehicle = this.mc.player.getVehicle();
         if (vehicle != null) {
            vehicle.noPhysics = false;
         }
      }
   }

   @EventHandler
   private void onPreTick(Pre event) {
      Entity target = this.getTarget();
      if (target == null) {
         this.restoreNoClip();
         if (!this.warnedPassenger) {
            this.warning("You must control the vehicle you are riding to phase it.");
            this.warnedPassenger = true;
         }
      } else {
         this.warnedPassenger = false;
         if (target != this.phasedEntity) {
            this.restoreNoClip();
            this.phasedEntity = target;
         }

         target.noPhysics = true;
         if (target == this.mc.player && Modules.get().isActive(BypassAutoFlyDependency.class)) {
            this.mc.player.fallDistance = 0.0;
         } else {
            if (target instanceof AbstractBoat && this.lockBoatYaw.get()) {
               target.setYRot(this.mc.player.getYRot());
            }

            if (!(target instanceof AbstractBoat) || !Modules.get().isActive(BypassBoatVoidFlyDependency.class)) {
               target.setDeltaMovement(this.getPhaseVelocity());
            }

            this.mc.player.fallDistance = 0.0;
         }
      }
   }

   @EventHandler
   private void onEntityMove(EntityMoveEvent event) {
      if (this.phasedEntity != null
         && event.entity == this.phasedEntity
         && this.phasedEntity instanceof AbstractBoat
         && !Modules.get().isActive(BypassBoatVoidFlyDependency.class)) {
         Vec3 velocity = this.getPhaseVelocity();
         event.movement = new Vec3(velocity.x, velocity.y, velocity.z);
      }
   }

   @EventHandler
   private void onPlayerMove(PlayerMoveEvent event) {
      if (this.mc.player != null && this.phasedEntity == this.mc.player && !Modules.get().isActive(BypassAutoFlyDependency.class)) {
         Vec3 velocity = this.getPhaseVelocity();
         event.movement = new Vec3(velocity.x, velocity.y, velocity.z);
      }
   }

   private Vec3 getPhaseVelocity() {
      Vec3 horizontal = PlayerUtils.getHorizontalVelocity(this.horizontalSpeed.get());
      double vertical = 0.0;
      if (Input.isPressed(this.mc.options.keyJump)) {
         this.descending = false;
         vertical += this.verticalSpeed.get() / 20.0;
      }

      if ((this.descendKey.get()).isPressed() || this.continuousDescend.get() && this.descending) {
         vertical -= this.verticalSpeed.get() / 20.0;
      }

      return new Vec3(horizontal.x, vertical, horizontal.z);
   }

   private Entity getTarget() {
      if (this.mc.player != null && this.mc.level != null) {
         Entity vehicle = this.mc.player.getVehicle();
         if (vehicle == null) {
            return this.mc.player;
         } else {
            return vehicle instanceof AbstractBoat boat && boat.getControllingPassenger() == this.mc.player ? boat : null;
         }
      } else {
         return null;
      }
   }

   private void restoreNoClip() {
      if (this.phasedEntity != null) {
         this.phasedEntity.noPhysics = false;
         this.phasedEntity = null;
      }
   }
}
