package dev.mulveo.mvaddon.modules;

import dev.mulveo.mvaddon.MVAddon;
import dev.donut.addon.mv.compat.events.world.TickEvent.Post;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.settings.IntSetting;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.events.EventHandler;

public final class Home extends Module {
   private static final int COMMAND_DELAY_TICKS = 15;
   private final Setting<Integer> home = this.settings
      .getDefaultGroup()
      .add(
         new IntSetting.Builder().name("home").description("The home number used by both commands.").defaultValue(1)
            .min(1)
            .sliderRange(1, 100)
            .noSlider()
            .build()
      );
   private int ticksRemaining;

   public Home() {
      super(MVAddon.UTILITY, "home-reset", "Deletes the selected home, then sets it again after 15 ticks.");
   }

   public void onActivate() {
      if (this.mc.getConnection() == null) {
         this.error("Join a server before using Home.");
         this.toggle();
      } else {
         this.mc.getConnection().sendCommand("delhome " + this.home.get());
         this.ticksRemaining = 15;
      }
   }

   public void onDeactivate() {
      this.ticksRemaining = 0;
   }

   @EventHandler
   private void onTick(Post event) {
      if (this.ticksRemaining > 0 && --this.ticksRemaining <= 0) {
         if (this.mc.getConnection() != null) {
            this.mc.getConnection().sendCommand("sethome " + this.home.get());
         }

         this.toggle();
      }
   }
}
