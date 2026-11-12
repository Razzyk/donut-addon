package dev.donut.addon.expansion.search.remote;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class RemoteCaptureLifecycleTest {
    private Object previousStore;
    private RemoteKnownBaseStore testStore;

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
    }

    @BeforeEach void resetCaptureForTest(@TempDir Path directory) throws Exception {
        setField("stopped", false);
        var shared=RemoteKnownBaseStore.class.getDeclaredField("sharedInstance");
        shared.setAccessible(true);
        previousStore=shared.get(null);
        testStore=new RemoteKnownBaseStore(directory);
        shared.set(null,testStore);
    }

    @AfterEach void resetCaptureForOtherTests() throws Exception {
        RemoteActivityCapture.INSTANCE.shutdown();
        setField("stopped", false);
        RemoteActivityState.INBOX.clear();
        testStore.close();
        var shared=RemoteKnownBaseStore.class.getDeclaredField("sharedInstance");
        shared.setAccessible(true);
        shared.set(null,previousStore);
    }

    @Test void worldBoundaryDropsOldPacketEvidenceAndKeepsFirstNewPacket() throws Exception {
        RemoteActivityCapture.INSTANCE.endSearchSession();
        var sessionEpoch=atomicField("sessionGeneration").get();
        var worldEpoch=atomicField("worldEpoch");
        long oldWorldEpoch=worldEpoch.get();
        receiveBlock();
        assertEquals(1,RemoteActivityState.INBOX.size());
        RemoteActivityCapture.INSTANCE.onWorldChanging(null);
        assertEquals(oldWorldEpoch + 1, worldEpoch.get());
        assertEquals(sessionEpoch, atomicField("sessionGeneration").get(), "a world boundary does not advance the search fence");
        assertEquals(0,RemoteActivityState.INBOX.size());
        receiveBlock();
        assertEquals(1,RemoteActivityState.INBOX.size());
        var initialized=RemoteActivityCapture.class.getDeclaredField("levelInitialized");
        initialized.setAccessible(true);
        assertTrue(initialized.getBoolean(null),"a world boundary records the expected next level immediately");
    }

    @Test void permanentTeardownPreventsLatePacketsRestoringEvidence() throws Exception {
        receiveBlock();
        RemoteActivityCapture.INSTANCE.shutdown();
        receiveBlock();
        assertEquals(0,RemoteActivityState.INBOX.size());
    }

    private static void receiveBlock() throws Exception {
        var packet=new ClientboundBlockUpdatePacket(new BlockPos(16,5,32),Blocks.STONE.defaultBlockState());
        var captureClass=Class.forName(RemoteActivityCapture.class.getName()+"$PendingPacket");
        var constructor=captureClass.getDeclaredConstructor(Object.class,long.class);
        constructor.setAccessible(true);
        Object pending=constructor.newInstance(packet,System.nanoTime());
        var method=RemoteActivityCapture.class.getDeclaredMethod("capturePacket",captureClass,long.class);
        method.setAccessible(true);
        method.invoke(RemoteActivityCapture.INSTANCE,pending,atomicField("worldEpoch").get());
    }

    private static void setField(String name, Object value) throws Exception {
        var field=RemoteActivityCapture.class.getDeclaredField(name);
        field.setAccessible(true); field.set(null,value);
    }

    private static AtomicLong atomicField(String name) throws Exception {
        var field=RemoteActivityCapture.class.getDeclaredField(name);
        field.setAccessible(true); return (AtomicLong)field.get(null);
    }
}
