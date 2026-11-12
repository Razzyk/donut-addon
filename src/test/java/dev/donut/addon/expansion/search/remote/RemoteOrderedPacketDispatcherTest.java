package dev.donut.addon.expansion.search.remote;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RemoteOrderedPacketDispatcherTest {
    @Test void worldGenerationIsReadInOrderAfterVanillaRespawnTask() {
        Harness harness = new Harness(8);
        Object connection = harness.connection;

        assertTrue(harness.enqueue("old light", connection));
        // Models the vanilla Respawn task queued between packet capture tasks.
        harness.executor.execute(() -> {
            harness.worldGeneration++;
            harness.captured.clear();
        });
        assertTrue(harness.enqueue("new light", connection));

        harness.executor.drain();

        assertEquals(List.of(new Captured("new light", 2L)), harness.captured);
    }

    @Test void oldConnectionTaskIsRejectedAfterDisconnect() {
        Harness harness = new Harness(8);
        Object oldConnection = harness.connection;
        harness.executor.execute(() -> {
            harness.connection = new Object();
            harness.connectionEpoch++;
        });
        assertTrue(harness.enqueue("old connection", oldConnection));

        harness.executor.drain();

        assertTrue(harness.captured.isEmpty());
    }

    @Test void unknownEntryConnectionCanCaptureAfterVanillaLoginInstallsIt() {
        Harness harness = new Harness(8);
        harness.executor.execute(() -> harness.connection = new Object());

        assertTrue(harness.enqueue("first login light", null));
        harness.executor.drain();

        assertEquals(List.of(new Captured("first login light", 1L)), harness.captured);
    }

    @Test void unknownEntryConnectionIsRejectedAcrossDisconnectEpoch() {
        Harness harness = new Harness(8);
        long oldEpoch = harness.connectionEpoch;
        harness.executor.execute(() -> {
            harness.connection = null;
            harness.connectionEpoch++;
        });

        assertTrue(harness.enqueue("late login light", null, oldEpoch, harness.sessionEpoch));
        harness.executor.drain();

        assertTrue(harness.captured.isEmpty());
    }

    @Test void searchSessionFenceRejectsAlreadyQueuedPackets() {
        Harness harness = new Harness(8);
        assertTrue(harness.enqueue("old search", harness.connection));
        harness.sessionEpoch++;

        harness.executor.drain();

        assertTrue(harness.captured.isEmpty());
    }

    @Test void stoppedCaptureRejectsQueuedCallbacks() {
        Harness harness = new Harness(8);
        assertTrue(harness.enqueue("late", harness.connection));
        harness.stopped = true;
        harness.executor.drain();
        assertTrue(harness.captured.isEmpty());
    }

    @Test void overflowInvalidatesQueuedTasksAndRaisesFailClosedSignal() {
        Harness harness = new Harness(1);
        assertTrue(harness.enqueue("queued", harness.connection));
        assertFalse(harness.enqueue("overflow", harness.connection));
        assertEquals(1, harness.overflows.get());
        assertTrue(harness.dispatcher.consumeOverflow());
        assertFalse(harness.dispatcher.consumeOverflow());

        harness.executor.drain();

        assertTrue(harness.captured.isEmpty());
        assertEquals(0, harness.dispatcher.pendingCount());
    }

    private record Captured(String value, long worldGeneration) {}

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { this.tasks.addLast(command); }
        void drain() { while (!this.tasks.isEmpty()) this.tasks.removeFirst().run(); }
    }

    private static final class Harness implements RemoteOrderedPacketDispatcher.Guard {
        final ManualExecutor executor = new ManualExecutor();
        final AtomicInteger overflows = new AtomicInteger();
        final List<Captured> captured = new ArrayList<>();
        final RemoteOrderedPacketDispatcher<String> dispatcher;
        Object connection = new Object();
        long connectionEpoch = 1L;
        long sessionEpoch = 1L;
        long worldGeneration = 1L;
        boolean stopped;

        Harness(int capacity) {
            this.dispatcher = new RemoteOrderedPacketDispatcher<>(capacity, this.executor, this.overflows::incrementAndGet);
        }

        boolean enqueue(String value, Object expectedConnection) {
            return this.enqueue(value, expectedConnection, this.connectionEpoch, this.sessionEpoch);
        }

        boolean enqueue(String value, Object expectedConnection, long expectedConnectionEpoch, long expectedSessionEpoch) {
            return this.dispatcher.enqueue(value, expectedConnection, expectedConnectionEpoch, expectedSessionEpoch,
                this, (captured, generation) -> this.captured.add(new Captured(captured, generation)));
        }

        @Override public boolean stopped() { return this.stopped; }
        @Override public Object connection() { return this.connection; }
        @Override public long connectionEpoch() { return this.connectionEpoch; }
        @Override public long sessionEpoch() { return this.sessionEpoch; }
        @Override public long worldGeneration() { return this.worldGeneration; }
    }
}
