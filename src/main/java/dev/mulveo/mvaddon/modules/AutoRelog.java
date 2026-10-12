package dev.mulveo.mvaddon.modules;

import dev.mulveo.mvaddon.MVAddon;
import dev.donut.addon.mv.compat.MeteorClient;
import dev.donut.addon.mv.compat.events.game.GameJoinedEvent;
import dev.donut.addon.mv.compat.events.world.TickEvent.Post;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.systems.modules.Modules;
import dev.donut.addon.mv.compat.systems.modules.misc.AutoReconnect;
import dev.donut.addon.mv.compat.events.EventHandler;
import net.minecraft.network.chat.Component;

public final class AutoRelog extends Module {
   private static final double REARM_Y = 15.0;
   private boolean reconnectPending;
   private boolean waitingForRearm;
   private double previousRearmY = Double.NaN;
   private boolean changedAutoReconnect;
   private boolean autoReconnectWasActive;
   private double oldReconnectDelay;

   public AutoRelog() {
      super(MVAddon.UTILITY, "autorelog", "Reconnects at Y -1 or below, then rearms after rising above Y 15.");
      MeteorClient.EVENT_BUS.subscribe(new AutoRelog.ReconnectListener());
   }

   public void onDeactivate() {
      if (!this.reconnectPending) {
         this.restoreAutoReconnect();
      }
   }

   @EventHandler
   private void onTick(Post event) {
      if (this.isActive() && !this.reconnectPending && this.mc.player != null && this.mc.getConnection() != null && !(this.mc.player.getY() > -1.0)) {
         AutoReconnect autoReconnect = (AutoReconnect)Modules.get().get(AutoReconnect.class);
         if (autoReconnect != null && autoReconnect.lastServerConnection != null) {
            this.autoReconnectWasActive = autoReconnect.isActive();
            this.oldReconnectDelay = (Double)autoReconnect.time.get();
            this.changedAutoReconnect = true;
            autoReconnect.time.set(0.0);
            if (!this.autoReconnectWasActive) {
               autoReconnect.toggle();
            }

            this.reconnectPending = true;
            this.disable();
            this.mc.getConnection().getConnection().disconnect(Component.literal("MV Addon AutoRelog"));
         } else {
            this.error("AutoRelog cannot reconnect because no server connection is saved.");
         }
      }
   }

   private void restoreAutoReconnect() {
      if (this.changedAutoReconnect) {
         AutoReconnect autoReconnect = (AutoReconnect)Modules.get().get(AutoReconnect.class);
         if (autoReconnect != null) {
            autoReconnect.time.set(this.oldReconnectDelay);
            if (!this.autoReconnectWasActive && autoReconnect.isActive()) {
               autoReconnect.toggle();
            }
         }

         this.changedAutoReconnect = false;
      }
   }

   private final class ReconnectListener {
      @EventHandler
      private void onGameJoined(GameJoinedEvent event) {
         if (AutoRelog.this.reconnectPending) {
            AutoRelog.this.reconnectPending = false;
            AutoRelog.this.restoreAutoReconnect();
            AutoRelog.this.waitingForRearm = true;
            AutoRelog.this.previousRearmY = AutoRelog.this.mc.player == null ? Double.NaN : AutoRelog.this.mc.player.getY();
         }
      }

      @EventHandler
      private void onTick(Post event) {
         if (AutoRelog.this.waitingForRearm && AutoRelog.this.mc.player != null) {
            double currentY = AutoRelog.this.mc.player.getY();
            boolean crossedUpward = !Double.isNaN(AutoRelog.this.previousRearmY)
               && AutoRelog.this.previousRearmY <= 15.0
               && currentY > 15.0
               && currentY > AutoRelog.this.previousRearmY;
            AutoRelog.this.previousRearmY = currentY;
            if (crossedUpward) {
               AutoRelog.this.waitingForRearm = false;
               AutoRelog.this.enable();
            }
         }
      }
   }
}
