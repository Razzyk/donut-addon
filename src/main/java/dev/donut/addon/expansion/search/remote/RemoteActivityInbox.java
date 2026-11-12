package dev.donut.addon.expansion.search.remote;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Bounded queue of packet observations ordered onto the client thread. */
public final class RemoteActivityInbox {
    public record Signal(int x, int z, RemoteActivityLedger.Kind kind, long receivedNanos,
                         RemoteLightClassifier.PacketSummary lightSummary, long sessionGeneration,
                         long worldGeneration) {
        public Signal(int x, int z, RemoteActivityLedger.Kind kind, long receivedNanos) {
            this(x, z, kind, receivedNanos, null, -1L, -1L);
        }
        public Signal(int x, int z, RemoteActivityLedger.Kind kind, long receivedNanos,
                      RemoteLightClassifier.PacketSummary lightSummary) {
            this(x, z, kind, receivedNanos, lightSummary, -1L, -1L);
        }
        public Signal(int x, int z, RemoteActivityLedger.Kind kind, long receivedNanos,
                      RemoteLightClassifier.PacketSummary lightSummary, long sessionGeneration) {
            this(x, z, kind, receivedNanos, lightSummary, sessionGeneration, -1L);
        }
    }
    private final int capacity;
    private final ArrayDeque<Signal> queue = new ArrayDeque<>();

    public RemoteActivityInbox(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public synchronized boolean offer(Signal signal) {
        if (signal == null) throw new IllegalArgumentException("signal must not be null");
        if (this.queue.size() >= this.capacity) return false;
        this.queue.addLast(signal);
        return true;
    }

    public synchronized List<Signal> drain(int maximum) {
        if (maximum < 0) throw new IllegalArgumentException("maximum must be non-negative");
        ArrayList<Signal> result = new ArrayList<>(Math.min(maximum, this.queue.size()));
        while (result.size() < maximum && !this.queue.isEmpty()) result.add(this.queue.removeFirst());
        return List.copyOf(result);
    }

    public synchronized void clear() { this.queue.clear(); }
    public synchronized int size() { return this.queue.size(); }
}
