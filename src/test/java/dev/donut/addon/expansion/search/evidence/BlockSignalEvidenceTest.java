package dev.donut.addon.expansion.search.evidence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BlockSignalEvidenceTest {
    @Test
    void inboxHasAHardBoundAndDrainsOnlyTheRequestedBatch() {
        BlockSignalInbox inbox = new BlockSignalInbox(2);
        BlockSignalInbox.Signal first = signal(1, 2, 3);
        BlockSignalInbox.Signal second = signal(4, 5, 6);

        assertTrue(inbox.offer(first));
        assertTrue(inbox.offer(second));
        assertFalse(inbox.offer(signal(7, 8, 9)));
        assertEquals(2, inbox.size());
        assertEquals(java.util.List.of(first), inbox.drain(1));
        assertEquals(java.util.List.of(second), inbox.drain(20));
        assertTrue(inbox.drain(1).isEmpty());
        assertEquals(0, inbox.size());
    }

    @Test
    void ledgerMergesEvidenceAndExpiresByMostRecentObservation() {
        BlockSignalLedger ledger = new BlockSignalLedger(4);
        var first = ledger.observe(17, 40, -1, "minecraft:chest",
            BlockSignalLedger.Source.ENTITY_PACKET, 10);
        var merged = ledger.observe(17, 40, -1, "",
            BlockSignalLedger.Source.SOUND, 20);

        assertEquals(first.key(), merged.key());
        assertEquals("minecraft:chest", merged.typeId());
        assertEquals(1, merged.packetCount());
        assertTrue(merged.has(BlockSignalLedger.Source.ENTITY_PACKET));
        assertTrue(merged.has(BlockSignalLedger.Source.SOUND));
        assertEquals(0, ledger.pruneOlderThan(25, 5));
        assertEquals(1, ledger.pruneOlderThan(26, 5));
        assertTrue(ledger.snapshot().isEmpty());
    }

    @Test
    void chunkUnloadCapacityAndWorldChangeBoundTheCache() {
        BlockSignalLedger ledger = new BlockSignalLedger(2);
        ledger.observe(0, 64, 0, "minecraft:chest", BlockSignalLedger.Source.ENTITY_PACKET, 1);
        ledger.observe(32, 64, 0, "minecraft:barrel", BlockSignalLedger.Source.ENTITY_PACKET, 2);
        ledger.observe(48, 64, 0, "minecraft:hopper", BlockSignalLedger.Source.ENTITY_PACKET, 3);

        assertEquals(2, ledger.size());
        assertEquals(0, ledger.removeChunk(0, 0)); // eldest was evicted at capacity
        assertEquals(1, ledger.removeChunk(2, 0));
        assertEquals(1, ledger.size());
        long oldEpoch = ledger.worldEpoch();
        ledger.beginWorld();
        assertEquals(oldEpoch + 1, ledger.worldEpoch());
        assertEquals(0, ledger.size());
    }

    @Test
    void onlyLoadedClientStateConflictsBecomeHiddenBlockSignals() {
        assertFalse(HiddenBlockClassifier.conflicts("minecraft:chest", false, true, false));
        assertTrue(HiddenBlockClassifier.conflicts("minecraft:chest", true, true, false));
        assertTrue(HiddenBlockClassifier.conflicts("minecraft:chest", true, false, false));
        assertFalse(HiddenBlockClassifier.conflicts("minecraft:chest", true, false, true));
        assertFalse(HiddenBlockClassifier.conflicts("", true, true, false));
    }

    private static BlockSignalInbox.Signal signal(int x, int y, int z) {
        return new BlockSignalInbox.Signal(x, y, z, "minecraft:chest",
            BlockSignalLedger.Source.ENTITY_PACKET, 1);
    }
}
