// Ported from the supplied Anubis macro extraction; original classes were only read statically.
package dev.donut.addon.anubis.module.impl.donutmacros;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import dev.donut.addon.anubis.chat.ModuleChat;
import dev.donut.addon.anubis.event.EventHandler;
import dev.donut.addon.anubis.event.impl.PacketReceiveNettyEvent;
import dev.donut.addon.anubis.event.impl.TickEvent;
import dev.donut.addon.anubis.module.ClientModule;
import dev.donut.addon.anubis.module.ModuleCategory;
import dev.donut.addon.anubis.module.impl.donutmacros.ServerText;
import dev.donut.addon.anubis.module.impl.donutmacros.TeleportConfirm;
import dev.donut.addon.anubis.module.setting.ModeSetting;
import dev.donut.addon.anubis.module.setting.NumberSetting;
import dev.donut.addon.anubis.module.setting.Setting;
import dev.donut.addon.anubis.module.setting.StringSetting;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.protocol.Packet;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.client.player.LocalPlayer;

@Environment(value=EnvType.CLIENT)
public final class TpMacroModule
extends ClientModule {
    private static final Pattern RATE_LIMIT = Pattern.compile("^you need to wait another ([\\d.]+) seconds? to execute a command");
    private static final Pattern VALID_NAME = Pattern.compile("\\.?[A-Za-z0-9_]{1,16}");
    private final ModeSetting command = (ModeSetting)this.addSetting("command", (Setting)new ModeSetting("Command", "tpa", new String[]{"tpa", "tpahere"}));
    private final StringSetting target = (StringSetting)this.addSetting("player", (Setting)new StringSetting("Player", "").hint("Player name"));
    private final NumberSetting interval = (NumberSetting)this.addSetting("interval", (Setting)new NumberSetting("Interval (s)", 1.0f, 0.25f, 60.0f).step(0.25f));
    private final TeleportConfirm confirm = new TeleportConfirm(this);
    private volatile long holdUntil;
    private long nextAt;
    private int sent;

    public TpMacroModule() {
        super("tp-macro", "Tp Macro", "Sends /tpa or /tpahere to one player on a timer.", ModuleCategory.DONUT_MACROS);
        this.usesNotifications();
        this.target.privateValue();
    }

    public boolean persistEnabled() {
        return false;
    }

    protected void onEnable() {
        String name = this.target.get().trim();
        if (!VALID_NAME.matcher(name).matches()) {
            ModuleChat.send((ClientModule)this, (ModuleChat.Body)ModuleChat.body().warn("Set a valid Player name first."));
            this.requestDisable();
            return;
        }
        this.confirm.reset();
        this.holdUntil = 0L;
        this.nextAt = 0L;
        this.sent = 0;
        ModuleChat.send((ClientModule)this, (ModuleChat.Body)ModuleChat.body().text("Sending ").value((Object)("/" + this.command.get() + " " + name)).text(" every ").value((Object)(this.interval.formatted() + "s")).text("."));
    }

    protected void onDisable() {
        if (this.sent > 0) {
            ModuleChat.send((ClientModule)this, (ModuleChat.Body)ModuleChat.body().text("Sent ").value((Object)this.sent).text(this.sent == 1 ? " request." : " requests."));
        }
    }

    @EventHandler
    public void onPacket(PacketReceiveNettyEvent event) {
        ClientboundSystemChatPacket chat;
        Packet class_25962 = event.packet;
        if (!(class_25962 instanceof ClientboundSystemChatPacket) || (chat = (ClientboundSystemChatPacket)class_25962).content() == null) {
            return;
        }
        Matcher wait = RATE_LIMIT.matcher(ServerText.normalize(chat.content().getString()));
        if (!wait.find()) {
            return;
        }
        try {
            this.holdUntil = System.currentTimeMillis() + (long)Math.ceil(Math.min(30.0, Double.parseDouble(wait.group(1))) * 1000.0) + 80L;
        }
        catch (NumberFormatException numberFormatException) {
            // empty catch block
        }
    }

    @EventHandler
    public void onTick(TickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.getConnection() == null || player.isDeadOrDying()) {
            return;
        }
        this.confirm.tick(mc);
        long now = System.currentTimeMillis();
        if (now < this.nextAt || now < this.holdUntil) {
            return;
        }
        player.connection.sendCommand(this.command.get() + " " + this.target.get().trim());
        this.confirm.sent();
        ++this.sent;
        this.nextAt = now + (long)(this.interval.get() * 1000.0f);
    }
}
