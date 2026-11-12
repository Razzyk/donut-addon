package dev.donut.addon.expansion.search.remote;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Places packet observations on the client executor in the same order as vanilla packet handling.
 * A world generation is read only when the queued task runs, after earlier vanilla tasks.
 */
final class RemoteOrderedPacketDispatcher<T> {
    interface Guard {
        boolean stopped();
        Object connection();
        long connectionEpoch();
        long sessionEpoch();
        long worldGeneration();
    }

    @FunctionalInterface
    interface Capture<T> {
        void accept(T value, long worldGeneration);
    }

    private final int capacity;
    private final Executor executor;
    private final Runnable overflowCallback;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicLong dispatchEpoch = new AtomicLong();
    private final AtomicBoolean overflowed = new AtomicBoolean();

    RemoteOrderedPacketDispatcher(int capacity, Executor executor, Runnable overflowCallback) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.overflowCallback = Objects.requireNonNull(overflowCallback, "overflowCallback");
    }

    boolean enqueue(T value, Object expectedConnection, long expectedConnectionEpoch, long expectedSessionEpoch,
            Guard guard, Capture<T> capture) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(guard, "guard");
        Objects.requireNonNull(capture, "capture");
        if (guard.stopped()) return false;

        long acceptedEpoch = this.dispatchEpoch.get();
        if (!this.reserve()) {
            this.invalidatePending();
            return false;
        }

        AtomicBoolean executed = new AtomicBoolean();
        try {
            this.executor.execute(() -> {
                executed.set(true);
                try {
                    if (acceptedEpoch != this.dispatchEpoch.get()
                        || guard.stopped()
                        || (expectedConnection != null && guard.connection() != expectedConnection)
                        || guard.connectionEpoch() != expectedConnectionEpoch
                        || guard.sessionEpoch() != expectedSessionEpoch) return;

                    // Read this inside the client task. A respawn's vanilla task can run first.
                    long worldGeneration = guard.worldGeneration();
                    if (!guard.stopped()) capture.accept(value, worldGeneration);
                } finally {
                    this.pending.decrementAndGet();
                }
            });
            return true;
        } catch (RuntimeException rejected) {
            if (executed.get()) throw rejected;
            this.pending.decrementAndGet();
            this.invalidatePending();
            return false;
        }
    }

    boolean consumeOverflow() {
        return this.overflowed.getAndSet(false);
    }

    int pendingCount() {
        return this.pending.get();
    }

    private boolean reserve() {
        while (true) {
            int current = this.pending.get();
            if (current >= this.capacity) return false;
            if (this.pending.compareAndSet(current, current + 1)) return true;
        }
    }

    private void invalidatePending() {
        this.dispatchEpoch.incrementAndGet();
        this.overflowed.set(true);
        this.overflowCallback.run();
    }
}
