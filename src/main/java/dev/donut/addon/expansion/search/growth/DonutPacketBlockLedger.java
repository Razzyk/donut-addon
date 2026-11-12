package dev.donut.addon.expansion.search.growth;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Main-thread ledger matching inbound block states to the subsequently applied client updates. */
public final class DonutPacketBlockLedger {
    private record Observation(Object state, long receivedTick) {}

    private final LinkedHashMap<Long, Observation> pending = new LinkedHashMap<>();

    public void record(long packedPosition, Object state, long receivedTick) {
        this.pending.put(packedPosition, new Observation(Objects.requireNonNull(state), receivedTick));
    }

    /** Consumes one matching packet observation; mismatched and expired updates never qualify. */
    public boolean consumeApplied(long packedPosition, Object state, long currentTick, long maxAgeTicks) {
        Observation seen = this.pending.remove(packedPosition);
        return seen != null && currentTick >= seen.receivedTick() && currentTick - seen.receivedTick() <= maxAgeTicks
            && Objects.equals(seen.state(), state);
    }

    public void prune(long currentTick, long maxAgeTicks) {
        Iterator<Map.Entry<Long, Observation>> iterator = this.pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Observation observation = iterator.next().getValue();
            if (currentTick < observation.receivedTick() || currentTick - observation.receivedTick() > maxAgeTicks) iterator.remove();
        }
    }

    public void trimToSize(int maximumEntries) {
        if (maximumEntries < 0) throw new IllegalArgumentException("negative entry limit");
        while (this.pending.size() > maximumEntries) {
            Iterator<Long> oldest = this.pending.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    public boolean hasPending(long packedPosition) { return this.pending.containsKey(packedPosition); }
    public void clear() { this.pending.clear(); }
    public int pendingCount() { return this.pending.size(); }
}
