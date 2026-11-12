package dev.donut.addon.expansion.search.seed;

import dev.donut.addon.mv.compat.MeteorClient;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SeedInspectorLifecycleTest {
    @BeforeAll static void prepareNativeTypes() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void disabledOutsideWorldClosesWorkerAndDropsEvidence() throws Exception { cleanup(false); }
    @Test void disabledModuleCleansUpEvenIfReadinessIsStillTrue() throws Exception { cleanup(true); }

    private static void cleanup(boolean ready) throws Exception {
        var module = new DonutSeedInspector();
        var worker = new SeedPredictionWorker(); // Constructor does not launch its lazy worker thread.
        try {
            field("worker").set(module, worker);
            field("token").set(module, new SeedScanCore.Token(1, 2, 3));
            var ledger = (SeedVerdictLedger)field("ledger").get(module);
            ledger.reconcile(4, List.of(new SeedVerdictLedger.Finding(5, SeedScanCore.GRANITE, SeedScanCore.Verdict.ALERT)));
            var inbox = (SeedObservationInbox)field("observations").get(module);
            inbox.begin(new SeedScanCore.Token(1, 2, 3));
            assertFalse(ledger.flagged().isEmpty());
            module.seedLifecycleTick(ready);
            assertNull(field("worker").get(module));
            assertNull(field("token").get(module));
            assertNull(inbox.token());
            assertTrue(ledger.flagged().isEmpty());
            assertThrows(IllegalStateException.class, () -> worker.begin(2, 3));
            module.endSeedSession(); // Normal shutdown and rollback may both call cleanup.
        } finally { worker.close(); MeteorClient.EVENT_BUS.unsubscribe(module); }
    }

    private static Field field(String name) throws Exception {
        Field f = DonutSeedInspector.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
}
