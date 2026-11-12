package dev.donut.addon.expansion.search.growth;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DonutHoleScanCursorTest {
    @Test void boundedNoHoleScanVisitsTheEntireChunkAndTerminates() {
        int height = 384;
        int budget = 997;
        DonutHoleScanCursor cursor = new DonutHoleScanCursor(height);
        int total = 0;
        int calls = 0;
        while (!cursor.complete()) {
            int visited = cursor.advance(-32, -64, 48, budget, (x, y, z) -> false);
            assertTrue(visited > 0);
            assertTrue(visited <= budget);
            total += visited;
            calls++;
            assertTrue(calls <= 100);
        }
        assertEquals(16 * 16 * height, total);
        assertFalse(cursor.found());
        assertEquals(16 * 16, cursor.visitedColumns());
        assertEquals((total + budget - 1) / budget, calls);
        assertEquals(0, cursor.advance(-32, -64, 48, budget, (x, y, z) -> false));
    }

    @Test void aDetectedShaftStopsTheCursorBeforeScanningTheRestOfTheChunk() {
        DonutHoleScanCursor cursor = new DonutHoleScanCursor(16);
        DonutHoleSignal.OpenCell shaft = (x, y, z) ->
            x == 5 && z == 7 && y >= 2 && y <= 6 || x == 6 && z == 7 && y == 6;
        int total = 0;
        while (!cursor.complete()) total += cursor.advance(0, 0, 0, 64, shaft);
        assertTrue(cursor.found());
        assertTrue(total < 16 * 16 * 16);
    }
}
