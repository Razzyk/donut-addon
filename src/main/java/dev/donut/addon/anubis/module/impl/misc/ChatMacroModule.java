// Ported from the supplied Anubis macro extraction; original classes were only read statically.
package dev.donut.addon.anubis.module.impl.misc;

import java.util.Locale;
import dev.donut.addon.anubis.chat.ModuleChat;
import dev.donut.addon.anubis.event.EventPriority;
import dev.donut.addon.anubis.event.EventType;
import dev.donut.addon.anubis.event.Stage;
import dev.donut.addon.anubis.event.impl.KeyInputEvent;
import dev.donut.addon.anubis.event.impl.MouseInputEvent;
import dev.donut.addon.anubis.event.impl.TickEvent;
import dev.donut.addon.anubis.input.ModuleKeybinds;
import dev.donut.addon.anubis.module.ClientModule;
import dev.donut.addon.anubis.module.ModuleCategory;
import dev.donut.addon.anubis.module.setting.KeySetting;
import dev.donut.addon.anubis.module.setting.ModeSetting;
import dev.donut.addon.anubis.module.setting.NumberSetting;
import dev.donut.addon.anubis.module.setting.Setting;
import dev.donut.addon.anubis.module.setting.StringSetting;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.util.StringUtil;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.apache.commons.lang3.StringUtils;

@Environment(value=EnvType.CLIENT)
public final class ChatMacroModule
extends ClientModule {
    public static final int MAX_MACROS = 8;
    private static final String ACTION_SEND = "Send";
    private static final String ACTION_OPEN = "Open in Chat";
    private final ModeSetting slots = (ModeSetting)this.addSetting("slots", (Setting)new ModeSetting("Slots", "3", ChatMacroModule.slotChoices()));
    private final ModeSetting pressAction = (ModeSetting)this.addSetting("on-press", (Setting)new ModeSetting("On Press", "Send", new String[]{"Send", "Open in Chat"}));
    private final NumberSetting cooldown = (NumberSetting)this.addSetting("cooldown", (Setting)new NumberSetting("Cooldown (s)", 1.0f, 1.0f, 10.0f).step(0.5f));
    private final KeySetting[] keys = new KeySetting[8];
    private final StringSetting[] texts = new StringSetting[8];
    private int pendingSlot = -1;
    private long lastSendNanos;
    private boolean sentAny;

    public ChatMacroModule() {
        super("chat-macro", "Chat Macro", "Sends chat messages or commands with a key press.", ModuleCategory.MISC);
        this.usesNotifications();
        this.cooldown.visibleWhen(() -> this.pressAction.is(ACTION_SEND));
        for (int i = 0; i < 8; ++i) {
            int slot = i + 1;
            this.keys[i] = (KeySetting)this.addSetting("macro-" + slot + "-key", (Setting)new KeySetting("Macro " + slot + " Key", -1));
            this.texts[i] = (StringSetting)this.addSetting("macro-" + slot + "-text", (Setting)new StringSetting("Macro " + slot + " Text", ""));
            this.texts[i].privateValue();
            this.keys[i].visibleWhen(() -> slot <= this.slotCount());
            this.texts[i].visibleWhen(() -> slot <= this.slotCount());
        }
    }

    public boolean inConfigs() {
        return false;
    }

    protected void onEnable() {
        this.pendingSlot = -1;
        this.on(KeyInputEvent.TYPE, Stage.PRE, EventPriority.HIGH, this::onKey);
        this.on(MouseInputEvent.TYPE, Stage.PRE, EventPriority.HIGH, this::onMouse);
        this.on(TickEvent.TYPE, Stage.PRE, this::onTick);
    }

    protected void onDisable() {
        this.pendingSlot = -1;
    }

    private void onKey(KeyInputEvent event) {
        if (event.action == 1) {
            this.onPress(event.key);
        }
    }

    private void onMouse(MouseInputEvent event) {
        if (event.action == 1) {
            this.onPress(1000 + event.button);
        }
    }

    private void onPress(int pressed) {
        if (!ModuleKeybinds.isBindable((int)pressed) || this.pendingSlot != -1) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null || mc.player == null) {
            return;
        }
        int count = this.slotCount();
        for (int i = 0; i < count; ++i) {
            if (this.keys[i].value() != pressed || this.texts[i].get().isBlank()) continue;
            this.pendingSlot = i;
            return;
        }
    }

    private void onTick(TickEvent event) {
        int slot = this.pendingSlot;
        if (slot < 0) {
            return;
        }
        this.pendingSlot = -1;
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (mc.player == null || connection == null || mc.gui.screen() != null) {
            return;
        }
        String text = ChatMacroModule.normalize(this.texts[slot].get());
        if (text.isEmpty() || text.equals("/")) {
            return;
        }
        if (this.pressAction.is(ACTION_OPEN)) {
            mc.setScreenAndShow((Screen)new ChatScreen(text, false));
            return;
        }
        long now = System.nanoTime();
        long left = (long)((double)this.cooldown.get() * 1.0E9) - (now - this.lastSendNanos);
        if (this.sentAny && left > 0L) {
            double seconds = Math.ceil((double)left / 1.0E8) / 10.0;
            ModuleChat.actionBar((ClientModule)this, (ModuleChat.Body)ModuleChat.body().warn("On cooldown").text(", ").value((Object)String.format(Locale.ROOT, "%.1fs", seconds)).text(" left"));
            return;
        }
        this.lastSendNanos = now;
        this.sentAny = true;
        mc.gui.hud.getChat().addRecentChat(text);
        if (text.startsWith("/")) {
            connection.sendCommand(text.substring(1));
        } else {
            connection.sendChat(text);
        }
    }

    static String normalize(String raw) {
        String allowed = StringUtil.filterText((String)raw);
        return StringUtil.trimChatMessage((String)StringUtils.normalizeSpace((String)allowed.trim()));
    }

    private int slotCount() {
        return Integer.parseInt(this.slots.get());
    }

    private static String[] slotChoices() {
        String[] choices = new String[8];
        for (int i = 0; i < 8; ++i) {
            choices[i] = Integer.toString(i + 1);
        }
        return choices;
    }
}
