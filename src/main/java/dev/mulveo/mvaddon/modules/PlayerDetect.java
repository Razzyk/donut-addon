package dev.mulveo.mvaddon.modules;

import dev.mulveo.mvaddon.MVAddon;
import dev.mulveo.mvaddon.toast.PlayerHeadToast;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import dev.donut.addon.mv.compat.events.game.GameJoinedEvent;
import dev.donut.addon.mv.compat.events.game.GameLeftEvent;
import dev.donut.addon.mv.compat.events.world.TickEvent.Post;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.events.EventHandler;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;

public final class PlayerDetect extends Module {
   private static final boolean SHOW_COORDINATES = true;
   private final Set<UUID> visiblePlayers = new HashSet<>();

   public PlayerDetect() {
      super(MVAddon.UTILITY, "player-detect", "Shows an alert when another player enters view.");
   }

   public void onActivate() {
      this.visiblePlayers.clear();
   }

   public void onDeactivate() {
      this.visiblePlayers.clear();
   }

   @EventHandler
   private void onGameJoined(GameJoinedEvent event) {
      this.visiblePlayers.clear();
   }

   @EventHandler
   private void onGameLeft(GameLeftEvent event) {
      this.visiblePlayers.clear();
   }

   @EventHandler
   private void onTick(Post event) {
      if (this.mc.level != null && this.mc.player != null) {
         Set<UUID> playersThisTick = new HashSet<>();

         for (AbstractClientPlayer player : this.mc.level.players()) {
            UUID uuid = player.getUUID();
            if (!uuid.equals(this.mc.player.getUUID())) {
               playersThisTick.add(uuid);
               if (!this.visiblePlayers.contains(uuid)) {
                  BlockPos pos = player.blockPosition();
                  PlayerHeadToast.addResolved(
                     "Player Detected",
                     SHOW_COORDINATES
                        ? player.getName().getString() + " @ " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                        : player.getName().getString(),
                     player.getGameProfile(),
                     player.getSkin()
                  );
               }
            }
         }

         this.visiblePlayers.retainAll(playersThisTick);
         this.visiblePlayers.addAll(playersThisTick);
      }
   }
}
