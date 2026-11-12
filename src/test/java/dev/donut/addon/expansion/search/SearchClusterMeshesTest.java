package dev.donut.addon.expansion.search;

import java.util.List;
import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import static org.junit.jupiter.api.Assertions.*;

public class SearchClusterMeshesTest {
    @Test void reusesGeometryForTheSameSetRegardlessOfIterationOrder() {
        var cache = new SearchClusterMeshes();
        var first = cache.forPositions(List.of(new BlockPos(1, 2, 3), new BlockPos(2, 2, 3)));
        var second = cache.forPositions(List.of(new BlockPos(2, 2, 3), new BlockPos(1, 2, 3)));
        assertSame(first, second);
        assertEquals(2, first.blockCount());
    }

    @Test void clearDropsCachedGeometryBeforeTheNextClusterBuild() {
        var cache = new SearchClusterMeshes();
        var positions = List.of(new BlockPos(4, 5, 6));
        var first = cache.forPositions(positions);
        cache.clear();
        assertNotSame(first, cache.forPositions(positions));
    }
}
