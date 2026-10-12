package dev.mulveo.mvaddon.toast;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Transient vanilla notification only; no MV HUD, fonts, theme, or persistent overlay. */
public final class MVToast implements Toast {
    private final Component title;
    private final Component text;
    private final ItemStack icon;
    private final SoundInstance sound;
    private final long duration;
    private long start = -1;
    private boolean playedSound;
    private Visibility visibility = Visibility.HIDE;

    public MVToast(String title, String text, Item icon, SoundInstance sound) {
        this(title, text, icon, sound, 6000);
    }
    public MVToast(String title, String text, Item icon, SoundInstance sound, long durationMs) {
        this.title = Component.literal(title);
        this.text = Component.literal(text);
        this.icon = icon.getDefaultInstance();
        this.sound = sound;
        this.duration = Math.max(0, durationMs);
    }
    public static MVToast chunkAlert(String title, int x, int y, int z) {
        return new MVToast(title, "X: " + x + "  Y: " + y + "  Z: " + z, Items.CHEST,
            SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_BREAK, 1.0F, 1.0F));
    }
    @Override public int width() { return Math.max(160, 38 + Math.max(Minecraft.getInstance().font.width(title), Minecraft.getInstance().font.width(text))); }
    @Override public int height() { return 32; }
    @Override public Visibility getWantedVisibility() { return visibility; }
    @Override public void update(ToastManager manager, long elapsed) {
        if (start < 0) start = elapsed;
        visibility = elapsed - start >= duration ? Visibility.HIDE : Visibility.SHOW;
        if (!playedSound && sound != null) { Minecraft.getInstance().getSoundManager().play(sound); playedSound = true; }
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long elapsed) {
        graphics.fill(0, 0, width(), height(), 0xEE202020);
        graphics.item(icon, 8, 8);
        graphics.text(font, title, 31, 4, 0xFFE0DCD4, false);
        graphics.text(font, text, 31, 15, 0xFFBEBAB3, false);
    }
}
