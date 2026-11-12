package dev.donut.addon.expansion.search.growth;

import java.util.BitSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SuspectServerLightTest {
    @Test void mergesSectionPacketsAndInvalidatesChangedCells() {
        SuspectServerLight light = new SuspectServerLight();
        byte[] packed = new byte[2048];
        packed[0] = (byte) 0xab;
        BitSet sent = new BitSet();
        sent.set(0);
        light.applyPacket(2, -3, 0, 5, sent, new BitSet(), List.of(packed), true);
        assertEquals(11, light.storedLightAt(32, 0, -48));
        assertEquals(10, light.storedLightAt(33, 0, -48));
        assertEquals(SuspectServerLight.UNKNOWN, light.storedLightAt(32, 64, -48));

        light.blockChanged(new BlockPos(32, 0, -48));
        assertEquals(SuspectServerLight.UNKNOWN, light.storedLightAt(32, 0, -48));
        assertEquals(10, light.storedLightAt(33, 0, -48));

        BitSet cleared = new BitSet();
        cleared.set(0);
        light.applyPacket(2, -3, 0, 5, new BitSet(), cleared, List.of(), false);
        assertEquals(0, light.storedLightAt(32, 0, -48));
        assertEquals(0, light.storedLightAt(33, 0, -48));
    }

    @Test void fullChunkPacketReplacesPriorColumnsAndCacheIsBounded() {
        SuspectServerLight light = new SuspectServerLight();
        byte[] lit = new byte[2048];
        lit[0] = 0x22;
        BitSet sent = new BitSet();
        sent.set(0);
        light.applyPacket(0, 0, 0, 1, sent, new BitSet(), List.of(lit), true);
        assertEquals(2, light.storedLightAt(0, 0, 0));
        light.applyPacket(0, 0, 0, 1, new BitSet(), new BitSet(), List.of(), true);
        assertEquals(SuspectServerLight.UNKNOWN, light.storedLightAt(0, 0, 0));
        for (int x = 0; x < 1100; x++) light.applyPacket(x, 10, 0, 1, new BitSet(), new BitSet(), List.of(), true);
        assertTrue(light.size() <= 1024);
        light.clear();
        assertEquals(0, light.size());
    }
}
