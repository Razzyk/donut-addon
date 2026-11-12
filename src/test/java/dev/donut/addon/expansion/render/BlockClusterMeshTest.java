package dev.donut.addon.expansion.render;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlockClusterMeshTest {
    @Test void singleBlockBuildsOneClosedOutline() {
        var mesh = BlockClusterMesh.build(List.of(new BlockPos(-7, 4, -2)));
        assertEquals(6, mesh.faces().size());
        assertEquals(12, mesh.edges().size());
        assertEquals(1, mesh.blockCount());
    }

    @Test void componentsUseSixFaceConnectivityAndSortNegativeCoordinatesDeterministically() {
        var input = List.of(new BlockPos(10, 0, 0), new BlockPos(-4, -2, 7),
            new BlockPos(-3, -2, 7), new BlockPos(-3, -1, 7), new BlockPos(-2, -1, 7));

        var components = BlockClusterMesh.connectedComponents(input);

        assertEquals(2, components.size());
        assertEquals(List.of(new BlockPos(-4, -2, 7), new BlockPos(-3, -2, 7),
            new BlockPos(-3, -1, 7), new BlockPos(-2, -1, 7)), components.getFirst());
        assertEquals(List.of(new BlockPos(10, 0, 0)), components.get(1));
        assertThrows(UnsupportedOperationException.class, () -> components.add(List.of()));
        assertThrows(UnsupportedOperationException.class, () -> components.getFirst().add(new BlockPos(0, 0, 0)));
    }

    @Test void diagonalBlocksAreSeparateComponents() {
        var components = BlockClusterMesh.connectedComponents(List.of(
            new BlockPos(-1, 0, -1), new BlockPos(0, 1, 0)));
        assertEquals(2, components.size());
    }

    @Test void adjacentBlocksMergeCoplanarFacesAndDeduplicateLongEdges() {
        var mesh = BlockClusterMesh.build(List.of(new BlockPos(2, 3, -4), new BlockPos(3, 3, -4)));

        assertEquals(6, mesh.faces().size());
        assertEquals(12, mesh.edges().size());
        assertTrue(mesh.edges().stream().anyMatch(edge ->
            Math.abs(edge.a().x() - edge.b().x()) == 2.0));
        assertEquals(2, mesh.blockCount());
    }

    @Test void ringRetainsConcaveInnerHoleEdges() {
        List<BlockPos> ring = new ArrayList<>();
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) {
            if (x != 1 || z != 1) ring.add(new BlockPos(x, 0, z));
        }
        var mesh = BlockClusterMesh.build(ring);

        assertTrue(mesh.edges().stream().anyMatch(edge ->
            samePoint(edge.a(), 1, 0, 1) && samePoint(edge.b(), 1, 1, 1)
                || samePoint(edge.b(), 1, 0, 1) && samePoint(edge.a(), 1, 1, 1)),
            "the concave vertical edge at the ring opening must remain in the outline");
        assertTrue(mesh.faces().stream().anyMatch(face -> face.a().x() == 1 || face.b().x() == 1
            || face.c().x() == 1 || face.d().x() == 1), "the merged surface keeps the hole boundary");
    }

    @Test void meshRebasesLargeWorldCoordinatesBeforeFloatConversion() {
        var mesh = BlockClusterMesh.build(List.of(new BlockPos(2_000_000_000, -1_900_000_000, 1_800_000_000),
            new BlockPos(2_000_000_001, -1_900_000_000, 1_800_000_000)));

        assertEquals(2_000_000_000, mesh.originX());
        assertEquals(-1_900_000_000, mesh.originY());
        assertEquals(1_800_000_000, mesh.originZ());
        assertTrue(mesh.faces().stream().flatMap(face -> List.of(face.a(), face.b(), face.c(), face.d()).stream())
            .allMatch(vertex -> vertex.x() >= 0 && vertex.x() <= 2 && vertex.y() >= 0 && vertex.y() <= 1));
    }

    private static boolean samePoint(BlockClusterMesh.Vertex point, double x, double y, double z) {
        return point.x() == x && point.y() == y && point.z() == z;
    }
}
