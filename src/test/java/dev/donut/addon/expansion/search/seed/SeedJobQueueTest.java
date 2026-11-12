package dev.donut.addon.expansion.search.seed;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeedJobQueueTest {
    @Test void seedChangesAndSessionInvalidationDiscardOldJobsAndResults() {
        SeedScanCore.GenerationGate gate = new SeedScanCore.GenerationGate();
        SeedJobQueue<String> queue = new SeedJobQueue<>(2, 2);
        SeedScanCore.Token first = gate.begin(11L, 73L);
        assertTrue(queue.offer(first, 1, 2));
        assertEquals(1, queue.jobCount());

        SeedScanCore.Token second = gate.begin(11L, 74L);
        queue.publish(new SeedJobQueue.Result<>(first, 1, 2, "stale"), gate);
        assertEquals(0, queue.resultCount());
        assertNull(queue.pollCurrent(gate));
        assertTrue(queue.offer(second, 2, 3));
        assertEquals(2, queue.pollCurrent(gate).chunkX());

        queue.publish(new SeedJobQueue.Result<>(second, 2, 3, "current"), gate);
        gate.invalidate();
        assertTrue(queue.drainCurrent(gate, 2).isEmpty());
        assertFalse(gate.accepts(second));
    }

    @Test void workAndResultBuffersStayBounded() {
        SeedScanCore.GenerationGate gate = new SeedScanCore.GenerationGate();
        SeedJobQueue<Integer> queue = new SeedJobQueue<>(1, 1);
        SeedScanCore.Token token = gate.begin(1L, 5L);
        assertTrue(queue.offer(token, 0, 0));
        assertFalse(queue.offer(token, 1, 0));
        queue.publish(new SeedJobQueue.Result<>(token, 0, 0, 1), gate);
        queue.publish(new SeedJobQueue.Result<>(token, 1, 0, 2), gate);
        assertEquals(1, queue.resultCount());
        assertEquals(2, queue.drainCurrent(gate, 1).getFirst().value());
    }
}
