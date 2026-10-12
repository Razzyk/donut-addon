package dev.donut.addon.hud.region;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionMapDataTest {
    @Test
    void preservesAllRegionsAndShardCount() {
        assertEquals(7, RegionMapData.regions.size());
        assertEquals(201, RegionMapData.shards.size());
        assertEquals(Set.of("NA West", "NA East", "Oceania", "Asia", "EU West", "EU Central", "Europe"),
            RegionMapData.regions.keySet());
    }

    @Test
    void assignsShardNumbersInOriginalRowThenColumnOrder() {
        assertShard("NA West", 1, 4, 0);
        assertShard("NA West", 6, 14, 0);
        assertShard("NA West", 7, 4, 4);
        assertShard("Oceania", 1, 0, 0);
        assertShard("Oceania", 2, 2, 0);
        assertShard("EU Central", 1, 12, 20);
    }

    @Test
    void everyShardIsInBoundsAndDoesNotOverlapAnotherShard() {
        boolean[][] occupied = new boolean[RegionMapData.GRID_SIZE][RegionMapData.GRID_SIZE];
        for (RegionShard shard : RegionMapData.shards) {
            assertTrue(RegionMapData.regions.containsKey(shard.getRegion()));
            assertTrue(shard.getColumn() >= 0 && shard.getRow() >= 0);
            assertTrue(shard.getWidth() > 0 && shard.getHeight() > 0);
            assertTrue(shard.getColumn() + shard.getWidth() <= RegionMapData.GRID_SIZE);
            assertTrue(shard.getRow() + shard.getHeight() <= RegionMapData.GRID_SIZE);

            for (int row = shard.getRow(); row < shard.getRow() + shard.getHeight(); row++) {
                for (int column = shard.getColumn(); column < shard.getColumn() + shard.getWidth(); column++) {
                    assertFalse(occupied[row][column], "overlap at " + column + "," + row);
                    occupied[row][column] = true;
                    assertNotNull(RegionMapData.cells[row][column]);
                    assertEquals(shard.getRegion(), RegionMapData.cells[row][column].getRegion());
                    assertEquals(shard.getNumber(), RegionMapData.cells[row][column].getNumber());
                }
            }
        }

        for (int row = 0; row < RegionMapData.GRID_SIZE; row++) {
            for (int column = 0; column < RegionMapData.GRID_SIZE; column++) {
                assertEquals(occupied[row][column], RegionMapData.cells[row][column] != null);
            }
        }
    }

    @Test
    void projectsAndClampsWorldCoordinatesToMapPixels() {
        assertPoint(0, 0, RegionMapData.projectWorldPosition(-225_000, -225_000, 144));
        assertPoint(72, 72, RegionMapData.projectWorldPosition(0, 0, 144));
        assertPoint(144, 144, RegionMapData.projectWorldPosition(225_000, 225_000, 144));
        assertPoint(0, 144, RegionMapData.projectWorldPosition(-300_000, 300_000, 144));
    }

    private static void assertShard(String region, int number, int column, int row) {
        RegionShard shard = RegionMapData.shards.stream()
            .filter(candidate -> candidate.getRegion().equals(region) && candidate.getNumber() == number)
            .findFirst().orElseThrow();
        assertEquals(column, shard.getColumn());
        assertEquals(row, shard.getRow());
    }

    private static void assertPoint(double expectedX, double expectedZ, double[] actual) {
        assertEquals(expectedX, actual[0], 1.0e-9);
        assertEquals(expectedZ, actual[1], 1.0e-9);
    }
}
