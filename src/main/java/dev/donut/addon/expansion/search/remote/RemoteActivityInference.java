package dev.donut.addon.expansion.search.remote;

import dev.donut.addon.expansion.search.remote.RemoteActivityLedger.Entry;
import dev.donut.addon.expansion.search.remote.RemoteActivityLedger.Key;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Spreads recent remote update evidence across a simulation area and returns isolated peaks. */
public final class RemoteActivityInference {
    public record Hotspot(Key key, double score, double rawScore, int sourceChunks, long lastSeenNanos) {}
    private RemoteActivityInference() {}

    public static List<Hotspot> infer(List<Entry> entries, int centerX, int centerZ, int simulationDistance,
                                      int viewDistance, int ringMargin, double minimumRawScore,
                                      int minimumSourceChunks, long nowNanos, long frozenAfterNanos) {
        return infer(entries, centerX, centerZ, simulationDistance, viewDistance, ringMargin,
            minimumRawScore, minimumSourceChunks, nowNanos, frozenAfterNanos, Set.of());
    }

    /**
     * Like {@link #infer(List, int, int, int, int, int, double, int, long, long)}, while omitting
     * frozen witness chunks and reducing the score of their immediately adjacent areas by half.
     */
    public static List<Hotspot> infer(List<Entry> entries, int centerX, int centerZ, int simulationDistance,
                                      int viewDistance, int ringMargin, double minimumRawScore,
                                      int minimumSourceChunks, long nowNanos, long frozenAfterNanos,
                                      Set<Key> frozenChunks) {
        if (frozenChunks == null) throw new IllegalArgumentException("frozenChunks must not be null");
        int simulation = Math.max(1, simulationDistance);
        int ringStart = simulation + 2 + Math.max(0, ringMargin);
        Map<Key, Mutable> field = new HashMap<>();
        for (Entry source : entries) {
            int sx = source.key().chunkX(), sz = source.key().chunkZ();
            int distance = Math.max(Math.abs(sx - centerX), Math.abs(sz - centerZ));
            long age = Math.max(0L, nowNanos - source.lastSeenNanos());
            if (distance < ringStart || distance > viewDistance || age > frozenAfterNanos || source.score() < 0.5
                || frozenChunks.contains(source.key())) continue;
            for (int dx = -simulation; dx <= simulation; dx++) for (int dz = -simulation; dz <= simulation; dz++) {
                double norm = Math.sqrt(dx * dx + dz * dz) / (simulation * 1.4143 + 1.0);
                double weighted = source.score() * (1.0 - 0.3 * norm);
                Key target = new Key(sx + dx, sz + dz);
                Mutable value = field.computeIfAbsent(target, ignored -> new Mutable());
                value.score += weighted;
                value.raw += source.score();
                value.sources++;
                value.lastSeenNanos = Math.max(value.lastSeenNanos, source.lastSeenNanos());
            }
        }
        // Source behavior removes frozen chunks as candidates and damps nearby evidence so a
        // stalled mob does not erase a whole surrounding region from the scan.
        for (Key frozen : frozenChunks) {
            field.remove(frozen);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                Mutable neighbor = field.get(new Key(frozen.chunkX() + dx, frozen.chunkZ() + dz));
                if (neighbor != null) {
                    neighbor.score *= 0.5;
                    neighbor.raw *= 0.5;
                }
            }
        }
        ArrayList<Hotspot> result = new ArrayList<>();
        for (Map.Entry<Key, Mutable> candidate : field.entrySet()) {
            Key key = candidate.getKey();
            Mutable value = candidate.getValue();
            if (value.raw < minimumRawScore || value.sources < minimumSourceChunks) continue;
            boolean peak = true;
            for (int dx = -simulation; dx <= simulation && peak; dx++) for (int dz = -simulation; dz <= simulation; dz++) {
                if (dx == 0 && dz == 0) continue;
                Mutable other = field.get(new Key(key.chunkX() + dx, key.chunkZ() + dz));
                if (other != null && (other.score > value.score || other.score == value.score
                    && (other.raw > value.raw || other.raw == value.raw && other.sources > value.sources
                    || other.raw == value.raw && other.sources == value.sources
                    && (key.chunkX() + dx < key.chunkX() || key.chunkX() + dx == key.chunkX() && key.chunkZ() + dz < key.chunkZ())))) {
                    peak = false;
                    break;
                }
            }
            if (peak) result.add(new Hotspot(key, value.score, value.raw, value.sources, value.lastSeenNanos));
        }
        result.sort(Comparator.comparingDouble(Hotspot::score).reversed());
        return List.copyOf(result);
    }

    private static final class Mutable {
        private double score, raw;
        private int sources;
        private long lastSeenNanos;
    }
}
