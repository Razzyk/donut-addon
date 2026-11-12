package dev.donut.addon.expansion.search;

import net.ccbluex.liquidbounce.event.EventHook;
import net.ccbluex.liquidbounce.event.Event;
import net.ccbluex.liquidbounce.event.events.PacketEvent;
import net.ccbluex.liquidbounce.event.events.TransferOrigin;
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SearchPacketCaptureLifecycleTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
    }

    @AfterEach void restoreSingletonForOtherTests() throws Exception {
        SearchPacketCapture.INSTANCE.shutdown();
        var stopped=SearchPacketCapture.class.getDeclaredField("stopped");
        stopped.setAccessible(true); stopped.setBoolean(null,false);
    }

    @Test void worldBoundaryDiscardsQueuedEvidenceBeforeTheNextWorldTick() throws Exception {
        SearchFeatures.clearPendingEvidence();
        receiveChestEvent();
        assertEquals(1,SearchFeatures.BLOCK_SIGNAL_INBOX.size());
        SearchPacketCaptureLifecycleTest.<WorldChangeEvent>hook("worlds").getHandler().accept(new WorldChangeEvent(null));
        assertEquals(0,SearchFeatures.BLOCK_SIGNAL_INBOX.size());
    }

    @Test void teardownClearsEvidenceAndRejectsLatePacketCallbacks() throws Exception {
        receiveChestEvent();
        SearchPacketCapture.INSTANCE.shutdown();
        receiveChestEvent();
        assertEquals(0,SearchFeatures.BLOCK_SIGNAL_INBOX.size());
        var world=SearchPacketCapture.class.getDeclaredField("lastWorld");
        world.setAccessible(true); assertNull(world.get(null));
    }

    @Test void decodingStartedBeforeAWorldBoundaryCannotPublishIntoTheNewEpoch() throws Exception {
        SearchFeatures.clearPendingEvidence();
        var generation=SearchPacketCapture.class.getDeclaredField("generation");
        generation.setAccessible(true);
        long oldEpoch=generation.getLong(null);
        SearchPacketCaptureLifecycleTest.<WorldChangeEvent>hook("worlds").getHandler().accept(new WorldChangeEvent(null));
        var publish=SearchPacketCapture.class.getDeclaredMethod("offerCurrent",long.class,kotlin.jvm.functions.Function0.class);
        publish.setAccessible(true);
        kotlin.jvm.functions.Function0<kotlin.Unit> delayedDecode=() -> {
            SearchFeatures.offerBlockEntityReport(1,2,3,"minecraft:chest");
            return kotlin.Unit.INSTANCE;
        };
        publish.invoke(SearchPacketCapture.INSTANCE,oldEpoch,delayedDecode);
        assertEquals(0,SearchFeatures.BLOCK_SIGNAL_INBOX.size());
        receiveChestEvent();
        assertEquals(1,SearchFeatures.BLOCK_SIGNAL_INBOX.size());
    }

    private static void receiveChestEvent() throws Exception {
        var packet=new ClientboundBlockEventPacket(new BlockPos(1,2,3),Blocks.CHEST,1,1);
        SearchPacketCaptureLifecycleTest.<PacketEvent>hook("packets").getHandler()
            .accept(new PacketEvent(TransferOrigin.INCOMING,packet,true));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Event> EventHook<T> hook(String name) throws Exception {
        var field=SearchPacketCapture.class.getDeclaredField(name);
        field.setAccessible(true); return (EventHook<T>)field.get(null);
    }
}
