package dev.donut.addon.mv.compat.utils.player;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.ccbluex.liquidbounce.utils.client.ClientChat;
public final class ChatUtils {
    public static void sendMsg(Component text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> ClientChat.chat(text));
    }
    public static void info(String text, Object... args) { sendMsg(Component.literal(String.format(text, args))); }
}
