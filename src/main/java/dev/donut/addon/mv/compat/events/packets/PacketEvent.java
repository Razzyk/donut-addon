package dev.donut.addon.mv.compat.events.packets;

import net.minecraft.network.protocol.Packet;
public abstract class PacketEvent extends dev.donut.addon.mv.compat.events.Cancellable {
    public final Packet<?> packet;
    protected PacketEvent(Packet<?> packet) { this.packet = packet; }
    public static final class Receive extends PacketEvent { public Receive(Packet<?> packet) { super(packet); } }
    public static final class Send extends PacketEvent { public Send(Packet<?> packet) { super(packet); } }
}
