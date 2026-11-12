package dev.donut.addon.expansion.search.evidence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Small producer-safe bridge from packet events to the client-thread evidence ledger. */
public final class BlockSignalInbox {
    public record Signal(int x, int y, int z, String typeId, String payloadPreview,
                         BlockSignalLedger.Source source, long receivedNanos) {
        public Signal(int x, int y, int z, String typeId, BlockSignalLedger.Source source, long receivedNanos) {
            this(x, y, z, typeId, "", source, receivedNanos);
        }
    }

    private final int capacity;
    private final ArrayDeque<Signal> queue = new ArrayDeque<>();

    public BlockSignalInbox(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public synchronized boolean offer(Signal signal) {
        if (signal == null) return false;
        if (queue.size() >= capacity) return false;
        queue.addLast(signal);
        return true;
    }

    public synchronized List<Signal> drain(int maximum) {
        if (maximum <= 0) return List.of();
        List<Signal> result = new ArrayList<>(Math.min(maximum, queue.size()));
        while (result.size() < maximum) {
            Signal signal = queue.pollFirst();
            if (signal == null) break;
            result.add(signal);
        }
        return List.copyOf(result);
    }

    public synchronized void clear() {
        queue.clear();
    }

    public synchronized int size() { return queue.size(); }
}
