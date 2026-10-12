package dev.mulveo.mvaddon.modules;

import dev.mulveo.mvaddon.MVAddon;
import dev.donut.addon.mv.compat.MeteorClient;
import dev.donut.addon.mv.compat.events.world.TickEvent.Post;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.settings.IntSetting;
import dev.donut.addon.mv.compat.settings.StringSetting;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.events.EventHandler;

public final class HomeRelog extends Module {
   private static final double TRIGGER_Y = -3.0;
   private static final double REARM_Y = 10.0;
   private static final long COMMAND_DELAY_NANOS = 500000000L;
   private static final long RTP_SETTLE_DELAY_NANOS = 1000000000L;
   private final Setting<Integer> homeNumber = this.settings
      .getDefaultGroup()
      .add(
         new IntSetting.Builder().name("home-number").description("The home number used by delhome, sethome, and home.").defaultValue(1)
            .min(1)
            .sliderRange(1, 100)
            .noSlider()
            .build()
      );
   private final Setting<String> rtpText = this.settings
      .getDefaultGroup()
      .add(
         new StringSetting.Builder()
                     .name("rtp-text")
                  .description("Optional text added after /rtp. Leave blank to send only /rtp.")
               .defaultValue("")
            .build()
      );
   private HomeRelog.Stage stage = HomeRelog.Stage.Idle;
   private long nextCommandAt;
   private int sequenceHomeNumber;
   private String sequenceRtpText = "";
   private boolean waitingForRearm;
   private boolean sawReturnedHomeBelowTen;

   public HomeRelog() {
      super(MVAddon.UTILITY, "homerelog", "Runs the delete-home, set-home, RTP and return-home sequence below Y -3.");
      MeteorClient.EVENT_BUS.subscribe(new HomeRelog.ReArmListener());
   }

   public void onActivate() {
      this.stage = HomeRelog.Stage.Idle;
      this.nextCommandAt = 0L;
      this.sequenceHomeNumber = this.homeNumber.get();
      this.sequenceRtpText = (this.rtpText.get()).trim();
      this.waitingForRearm = false;
      this.sawReturnedHomeBelowTen = false;
   }

   public void onDeactivate() {
      this.stage = HomeRelog.Stage.Idle;
      this.nextCommandAt = 0L;
      this.waitingForRearm = false;
      this.sawReturnedHomeBelowTen = false;
   }

   @EventHandler
   private void onTick(Post event) {
      if (this.mc.player != null && this.mc.getConnection() != null) {
         if (this.stage == HomeRelog.Stage.Idle) {
            if (!(this.mc.player.getY() >= -3.0)) {
               this.sequenceHomeNumber = this.homeNumber.get();
               this.sequenceRtpText = (this.rtpText.get()).trim();
               this.send("delhome " + this.sequenceHomeNumber);
               this.stage = HomeRelog.Stage.SetHome;
               this.nextCommandAt = System.nanoTime() + 500000000L;
            }
         } else {
            long now = System.nanoTime();
            if (now >= this.nextCommandAt) {
               switch (this.stage) {
                  case Idle:
                  default:
                     break;
                  case SetHome:
                     this.send("sethome " + this.sequenceHomeNumber);
                     this.stage = HomeRelog.Stage.Rtp;
                     this.nextCommandAt = now + 500000000L;
                     break;
                  case Rtp:
                     this.send(this.sequenceRtpText.isBlank() ? "rtp" : "rtp " + this.sequenceRtpText);
                     this.stage = HomeRelog.Stage.Home;
                     this.nextCommandAt = now + 1000000000L;
                     break;
                  case Home:
                     this.send("home " + this.sequenceHomeNumber);
                     this.stage = HomeRelog.Stage.Idle;
                     this.nextCommandAt = 0L;
                     this.disable();
                     this.waitingForRearm = true;
                     this.sawReturnedHomeBelowTen = false;
               }
            }
         }
      }
   }

   private void send(String command) {
      if (this.mc.getConnection() != null) {
         this.mc.getConnection().sendCommand(command);
      }
   }

   private final class ReArmListener {
      @EventHandler
      private void onTick(Post event) {
         if (HomeRelog.this.waitingForRearm && HomeRelog.this.mc.player != null) {
            if (HomeRelog.this.mc.player.getY() < 10.0) {
               HomeRelog.this.sawReturnedHomeBelowTen = true;
            } else if (HomeRelog.this.sawReturnedHomeBelowTen) {
               HomeRelog.this.waitingForRearm = false;
               HomeRelog.this.sawReturnedHomeBelowTen = false;
               HomeRelog.this.enable();
            }
         }
      }
   }

   private static enum Stage {
      Idle,
      SetHome,
      Rtp,
      Home;
   }
}
