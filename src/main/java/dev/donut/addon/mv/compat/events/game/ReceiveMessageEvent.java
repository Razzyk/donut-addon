package dev.donut.addon.mv.compat.events.game;

import net.minecraft.network.chat.Component;
public final class ReceiveMessageEvent extends dev.donut.addon.mv.compat.events.Cancellable {
    private Component message;
    public ReceiveMessageEvent(Component message) { this.message = message; }
    public Component getMessage() { return message; }
    public void setMessage(Component message) { this.message = message; }
}
