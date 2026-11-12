package dev.donut.addon.expansion.automation.core;

import static org.junit.jupiter.api.Assertions.*;

import net.ccbluex.liquidbounce.event.events.TransferOrigin;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

class InventoryAckChannelTest {
    @Test
    void stampsIncomingFramesAtArrivalAndKeepsTheLegacyDrainShape() {
        var channel = new InventoryAckChannel();
        channel.setSession(4, 9);
        var packet = new ClientboundContainerSetSlotPacket(0, 12, 5, ItemStack.EMPTY);

        channel.onPacket(TransferOrigin.OUTGOING, true, packet);
        channel.onPacket(TransferOrigin.INCOMING, false, packet);
        assertEquals(0L, channel.currentArrivalOrdinal(4, 9));

        channel.onPacket(TransferOrigin.INCOMING, true, packet);
        assertEquals(1L, channel.currentArrivalOrdinal(4, 9));
        var stamped = new java.util.ArrayList<InventoryAckEnvelope>();
        channel.drainStamped(4, 9, envelope -> { stamped.add(envelope); return kotlin.Unit.INSTANCE; });
        assertEquals(1, stamped.size());
        assertEquals(1L, stamped.getFirst().getArrivalOrdinal());
        assertEquals(12, stamped.getFirst().getUpdate().getStateId());

        channel.onPacket(TransferOrigin.INCOMING, true, packet);
        var legacy = new java.util.ArrayList<InventorySyncFrame>();
        channel.drain(4, 9, frame -> { legacy.add(frame); return kotlin.Unit.INSTANCE; });
        assertEquals(2L, channel.currentArrivalOrdinal(4, 9));
        assertEquals(1, legacy.size());
        assertEquals(12, legacy.getFirst().getStateId());
    }

    @Test
    void resetsOrdinalAndQueuedFramesOnSessionReplacement() {
        var channel = new InventoryAckChannel();
        channel.setSession(4, 9);
        var packet = new ClientboundContainerSetSlotPacket(0, 1, 5, ItemStack.EMPTY);
        channel.onPacket(TransferOrigin.INCOMING, true, packet);
        channel.setSession(5, 10);

        assertNull(channel.currentArrivalOrdinal(4, 9));
        assertEquals(0L, channel.currentArrivalOrdinal(5, 10));
        var stale = new java.util.ArrayList<InventoryAckEnvelope>();
        channel.drainStamped(4, 9, envelope -> { stale.add(envelope); return kotlin.Unit.INSTANCE; });
        assertTrue(stale.isEmpty());

        channel.onPacket(TransferOrigin.INCOMING, true, packet);
        assertEquals(1L, channel.currentArrivalOrdinal(5, 10));
        var current = new java.util.ArrayList<InventoryAckEnvelope>();
        channel.drainStamped(5, 10, envelope -> { current.add(envelope); return kotlin.Unit.INSTANCE; });
        assertEquals(1L, current.getFirst().getArrivalOrdinal());
    }
}
