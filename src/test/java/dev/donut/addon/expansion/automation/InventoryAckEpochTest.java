package dev.donut.addon.expansion.automation;

import static org.junit.jupiter.api.Assertions.*;

import dev.donut.addon.expansion.automation.core.InventoryAckEnvelope;
import dev.donut.addon.expansion.automation.core.InventoryAckEpoch;
import dev.donut.addon.expansion.automation.core.InventorySyncFrame;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InventoryAckEpochTest {
    @Test
    void queuedPacketSnapshotIsDroppedAcrossWorldOrConnectionEpochs() {
        var update = new InventorySyncFrame(4, 0, 8, Map.of(), null, true);
        var envelope = new InventoryAckEnvelope(3, 4, update);
        assertTrue(InventoryAckEpoch.accepts(envelope, 3, 4));
        assertFalse(InventoryAckEpoch.accepts(envelope, 4, 4));
        assertFalse(InventoryAckEpoch.accepts(envelope, 3, 5));
    }
}
