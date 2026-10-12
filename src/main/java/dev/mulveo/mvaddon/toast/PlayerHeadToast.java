package dev.mulveo.mvaddon.toast;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Items;

/** Player alerts use the same vanilla notification path as scanner alerts. */
public final class PlayerHeadToast {
    private PlayerHeadToast() {}
    public static void addResolved(String title, String text, GameProfile profile, Object skin) {
        Minecraft.getInstance().gui.toastManager().addToast(new MVToast(title, text, Items.PLAYER_HEAD, null));
    }
}
