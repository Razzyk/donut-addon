package dev.donut.addon.expansion.utility;

import java.util.ArrayDeque;

/** Counts nearby block-removal evidence while excluding ambient and self-caused changes. */
final class SpawnerTriggerLedger {
    private final ArrayDeque<Long> evidence = new ArrayDeque<>();

    boolean observe(long nowNanos, boolean oldWasAir, boolean oldWasProtected,
                    boolean newIsAir, int y, double horizontalDistance,
                    double triggerRange, double localDistance,
                    boolean trustedPlayerNearby, long windowNanos, int threshold) {
        prune(nowNanos, windowNanos);
        if (oldWasAir || oldWasProtected || !newIsAir || y <= -64 ||
            !Double.isFinite(horizontalDistance) || horizontalDistance > triggerRange ||
            localDistance < 6.0 || trustedPlayerNearby) {
            return false;
        }
        evidence.addLast(nowNanos);
        return evidence.size() >= Math.max(1, threshold);
    }

    int size(long nowNanos, long windowNanos) {
        prune(nowNanos, windowNanos);
        return evidence.size();
    }

    void clear() {
        evidence.clear();
    }

    private void prune(long nowNanos, long windowNanos) {
        long cutoff = nowNanos - Math.max(1L, windowNanos);
        while (!evidence.isEmpty() && evidence.peekFirst() < cutoff) evidence.removeFirst();
    }
}
