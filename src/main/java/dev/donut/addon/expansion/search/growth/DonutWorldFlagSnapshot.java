package dev.donut.addon.expansion.search.growth;

import java.util.Set;

/** Immutable, world-scoped search flags for consumers such as Tunnel Base Finder. */
public record DonutWorldFlagSnapshot(String dimensionId, Object worldIdentity, long generation, Set<Long> chunkKeys) {
    public DonutWorldFlagSnapshot {
        dimensionId = dimensionId == null ? "" : dimensionId;
        chunkKeys = Set.copyOf(chunkKeys == null ? Set.of() : chunkKeys);
    }

    public static DonutWorldFlagSnapshot empty(long generation) {
        return new DonutWorldFlagSnapshot("", null, generation, Set.of());
    }

    public boolean belongsTo(Object world, String dimension) {
        return world != null && this.worldIdentity == world && this.dimensionId.equals(dimension);
    }
}
