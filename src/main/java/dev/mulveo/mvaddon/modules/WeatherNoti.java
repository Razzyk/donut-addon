package dev.mulveo.mvaddon.modules;

import dev.mulveo.mvaddon.MVAddon;
import dev.mulveo.mvaddon.toast.MVToast;
import dev.donut.addon.mv.compat.events.game.GameJoinedEvent;
import dev.donut.addon.mv.compat.events.game.GameLeftEvent;
import dev.donut.addon.mv.compat.events.world.TickEvent.Post;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.events.EventHandler;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public final class WeatherNoti extends Module {
   private static final boolean TOAST_ALERTS_ENABLED = true;
   private WeatherNoti.Weather previousWeather = WeatherNoti.Weather.UNKNOWN;

   public WeatherNoti() {
      super(MVAddon.UTILITY, "weathernoti", "Shows an alert when the weather changes.");
   }

   public void onActivate() {
      this.previousWeather = this.getWeather();
   }

   public void onDeactivate() {
      this.previousWeather = WeatherNoti.Weather.UNKNOWN;
   }

   @EventHandler
   private void onGameJoined(GameJoinedEvent event) {
      this.previousWeather = WeatherNoti.Weather.UNKNOWN;
   }

   @EventHandler
   private void onGameLeft(GameLeftEvent event) {
      this.previousWeather = WeatherNoti.Weather.UNKNOWN;
   }

   @EventHandler
   private void onTick(Post event) {
      WeatherNoti.Weather currentWeather = this.getWeather();
      if (currentWeather != WeatherNoti.Weather.UNKNOWN) {
         if (this.previousWeather == WeatherNoti.Weather.UNKNOWN) {
            this.previousWeather = currentWeather;
         } else if (currentWeather != this.previousWeather) {
            this.previousWeather = currentWeather;
            this.showWeatherToast(currentWeather);
         }
      }
   }

   private WeatherNoti.Weather getWeather() {
      if (this.mc.level == null) {
         return WeatherNoti.Weather.UNKNOWN;
      } else if (this.mc.level.isThundering()) {
         return WeatherNoti.Weather.THUNDERSTORM;
      } else {
         return this.mc.level.isRaining() ? WeatherNoti.Weather.RAIN : WeatherNoti.Weather.CLEAR;
      }
   }

   private void showWeatherToast(WeatherNoti.Weather weather) {
      if (TOAST_ALERTS_ENABLED) {
         String weatherName;
         Item icon;
         switch (weather) {
            case RAIN:
               weatherName = "Rain";
               icon = Items.WATER_BUCKET;
               break;
            case THUNDERSTORM:
               weatherName = "Thunderstorm";
               icon = Items.LIGHTNING_ROD.weathering().unaffected();
               break;
            default:
               weatherName = "Clear";
               icon = Items.SUNFLOWER;
         }

         try {
            MVToast toast = new MVToast("Weather Changed", weatherName, icon, SimpleSoundInstance.forUI(SoundEvents.PISTON_EXTEND, 1.0F, 1.0F));
            this.mc.gui.toastManager().addToast(toast);
         } catch (Throwable toastFailure) {
            SystemToast.add(
               this.mc.gui.toastManager(), SystemToastId.PERIODIC_NOTIFICATION, Component.literal("Weather Changed"), Component.literal(weatherName)
            );
            this.warning("Custom weather toast failed; used Minecraft's safe fallback instead.");
         }
      }
   }

   private static enum Weather {
      UNKNOWN,
      CLEAR,
      RAIN,
      THUNDERSTORM;
   }
}
