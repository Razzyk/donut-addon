package dev.donut.addon.expansion.automation;

import static org.junit.jupiter.api.Assertions.*;

import dev.donut.addon.expansion.automation.core.InventorySlotValue;
import dev.donut.addon.expansion.automation.core.InventorySyncFrameDecoder;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class InventorySyncFrameDecoderTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void decodesMojangSlotAndContentPacketShapesIncludingSpecialCursorMenu() {
        var cursorPacket = new ClientboundContainerSetSlotPacket(-1, 19, -1, ItemStack.EMPTY);
        var cursorFrame = InventorySyncFrameDecoder.decode(cursorPacket, 44);
        assertNotNull(cursorFrame);
        assertEquals(-1, cursorFrame.getMenuId());
        assertEquals(19, cursorFrame.getStateId());
        assertEquals(new InventorySlotValue("", 0), cursorFrame.getCursor());

        var contentPacket = new ClientboundContainerSetContentPacket(0, 20,
            List.of(ItemStack.EMPTY), ItemStack.EMPTY);
        var contentFrame = InventorySyncFrameDecoder.decode(contentPacket, 44);
        assertNotNull(contentFrame);
        assertTrue(contentFrame.getFullContents());
        assertEquals(0, contentFrame.getMenuId());
        assertEquals(20, contentFrame.getStateId());
        assertEquals(0, contentFrame.getSlots().get(0).getCount());
        assertNull(contentFrame.getCursor());
    }
}
