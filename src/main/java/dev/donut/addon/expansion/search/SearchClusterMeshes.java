package dev.donut.addon.expansion.search;

import dev.donut.addon.expansion.render.BlockClusterMesh;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Small LRU cache so unchanged loaded marker clusters do not rebuild render meshes each frame. */
public final class SearchClusterMeshes {
    private static final int MAX_CACHED_CLUSTERS = 512;
    private final LinkedHashMap<List<Long>, BlockClusterMesh.Mesh> meshes = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<List<Long>, BlockClusterMesh.Mesh> eldest) {
            return size() > MAX_CACHED_CLUSTERS;
        }
    };

    public synchronized BlockClusterMesh.Mesh forPositions(Collection<BlockPos> positions) {
        List<Long> key = positions.stream().map(BlockPos::asLong).distinct().sorted().toList();
        return this.meshes.computeIfAbsent(key, ignored -> BlockClusterMesh.build(key.stream().map(BlockPos::of).toList()));
    }

    public synchronized void clear() { this.meshes.clear(); }
}
