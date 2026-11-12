package dev.donut.addon.expansion.search.seed;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeedPredictionWorkerTest {
    @Test void disabledConstructionStartsNoThreadAndClosingIsFinal() throws Exception {
        var worker = new SeedPredictionWorker();
        Thread thread = threadOf(worker);
        assertFalse(thread.isAlive());
        worker.close();
        worker.close();
        assertFalse(thread.isAlive());
        assertThrows(IllegalStateException.class, () -> worker.begin(1L, 42L));
    }

    @Test void runInvalidationAndTeardownReleaseWorkerWithoutStartingWorldGeneration() throws Exception {
        var worker = new SeedPredictionWorker();
        try {
            var token = worker.begin(1L, 42L);
            assertTrue(threadOf(worker).isAlive());
            assertTrue(worker.accepts(token));
            worker.invalidate();
            assertFalse(worker.accepts(token));
        } finally {
            worker.close();
        }
        threadOf(worker).join(1000L);
        assertFalse(threadOf(worker).isAlive());
    }

    private static Thread threadOf(SeedPredictionWorker worker) throws Exception {
        var field = SeedPredictionWorker.class.getDeclaredField("thread");
        field.setAccessible(true);
        return (Thread)field.get(worker);
    }
}
