package dev.donut.addon.mv.compat.systems.modules.misc;

import dev.donut.addon.mv.compat.settings.DoubleSetting;
import dev.donut.addon.mv.compat.settings.Setting;
import net.ccbluex.liquidbounce.utils.client.ServerObserver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/** Reconnect utility for AutoRelog, using LiquidBounce's saved address and transfer state. */
public final class AutoReconnect {
    public final Setting<Double> time = new DoubleSetting.Builder().name("reconnect-delay")
        .description("Seconds before reconnecting.").defaultValue(3.5).min(0).build();
    public ServerData lastServerConnection;
    private boolean active;
    private long deadline;

    public boolean isActive() { return active; }
    public void toggle() { active = !active; if (!active) deadline = 0; }
    public void connected(ServerData server) { if (server != null) lastServerConnection = server; deadline = 0; }
    public void disconnected() {
        if (active && lastServerConnection != null) {
            deadline = System.nanoTime() + Math.round(time.get() * 1_000_000_000.0);
        }
    }
    public void tick() {
        if (!active || deadline == 0 || System.nanoTime() < deadline || Minecraft.getInstance().level != null) return;
        deadline = 0;
        ServerObserver.INSTANCE.reconnect();
    }
}
