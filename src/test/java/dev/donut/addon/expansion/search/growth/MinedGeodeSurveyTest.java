package dev.donut.addon.expansion.search.growth;

import java.util.List;
import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import static org.junit.jupiter.api.Assertions.*;

class MinedGeodeSurveyTest {
    @Test void joinsExposedLiningAcrossChunkEdgesAndCountsBudsTogether() {
        long[] west = new long[6];
        long[] east = new long[6];
        for (int i = 0; i < 6; i++) {
            west[i] = BlockPos.asLong(15, i, 0);
            east[i] = BlockPos.asLong(16, i, 0);
        }
        var clusters = MinedGeodeSurvey.clusterSlices(List.of(
            new MinedGeodeSurvey.Slice(ChunkPos.pack(0, 0), west, west, 1),
            new MinedGeodeSurvey.Slice(ChunkPos.pack(1, 0), east, east, 1)
        ));
        assertEquals(1, clusters.size());
        assertEquals(12, clusters.getFirst().lining().length);
        assertEquals(12, clusters.getFirst().budding());
        assertTrue(MinedGeodeSurvey.large(clusters.getFirst()));
        assertEquals(ChunkPos.pack(0, 0), clusters.getFirst().centerChunk());
    }

    @Test void enforcesSourceClusterThresholdsAfterCrossChunkMerge() {
        long[] many = new long[160];
        int index = 0;
        for (int y = 0; y < 10; y++) for (int x = 0; x < 16; x++) many[index++] = BlockPos.asLong(x, y, 0);
        var liningOnly = MinedGeodeSurvey.clusterSlices(List.of(
            new MinedGeodeSurvey.Slice(ChunkPos.pack(0, 0), many, new long[0], 1)
        ));
        assertTrue(MinedGeodeSurvey.large(liningOnly.getFirst()));

        var tooSmallBudding = MinedGeodeSurvey.clusterSlices(List.of(
            new MinedGeodeSurvey.Slice(ChunkPos.pack(0, 0), new long[]{BlockPos.asLong(0, 0, 0)},
                new long[]{BlockPos.asLong(0, 0, 0)}, 1)
        ));
        assertFalse(MinedGeodeSurvey.large(tooSmallBudding.getFirst()));
    }

    @Test void keepsSpatiallySeparateShellsAsSeparateCandidates() {
        long a = BlockPos.asLong(0, 0, 0);
        long b = BlockPos.asLong(40, 0, 0);
        var clusters = MinedGeodeSurvey.clusterSlices(List.of(
            new MinedGeodeSurvey.Slice(ChunkPos.pack(0, 0), new long[]{a}, new long[0], 1),
            new MinedGeodeSurvey.Slice(ChunkPos.pack(2, 0), new long[]{b}, new long[0], 1)
        ));
        assertEquals(2, clusters.size());
    }
}
