package dev.donut.addon.expansion.search.light;

import java.util.Map;

/** Minecraft-free checks for artificial light cells and denoised neighbourhoods. */
public final class LightCellClassifier {
    public record Cell(int x, int y, int z) {}

    private LightCellClassifier() {}

    public static boolean isArtificial(int blockLight, int skyLight, int minimumLight) {
        return blockLight >= minimumLight && blockLight > skyLight;
    }

    public static boolean hasEnoughNeighbours(Cell cell, Map<Cell, Integer> hits, int minimumNeighbours) {
        if (minimumNeighbours <= 0) return true;
        int adjacent = 0;
        for (int[] offset : OFFSETS) {
            if (hits.containsKey(new Cell(cell.x() + offset[0], cell.y() + offset[1], cell.z() + offset[2]))
                && ++adjacent >= minimumNeighbours) return true;
        }
        return false;
    }

    private static final int[][] OFFSETS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
}
