package dev.donut.addon.expansion.search.growth;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DonutHoleSignalTest {
    @Test void detectsOpenSingleShaftOnlyWhenItIsEnclosedAndHasAClosedBottom() {
        DonutHoleSignal.OpenCell shaft = (x, y, z) ->
            x == 5 && z == 7 && y >= 2 && y <= 6 || x == 6 && z == 7 && y == 6;
        assertTrue(DonutHoleSignal.hasHole(0, 0, 0, 8, shaft));
        assertFalse(DonutHoleSignal.hasHole(0, 0, 0, 8, (x, y, z) -> x == 5 && z == 7 && y >= 0 && y <= 6));
        assertFalse(DonutHoleSignal.hasHole(0, 0, 0, 8, (x, y, z) ->
            x == 5 && z == 7 && y >= 2 && y <= 5 || x == 6 && z == 7 && y >= 2 && y <= 5));
    }

    @Test void wideShaftRequiresThreeEnclosedCellsAndRejectsSideOpenings() {
        DonutHoleSignal.OpenCell row = (x, y, z) -> y >= 2 && y <= 6 && z == 4 && x >= 3 && x <= 5 ||
            y == 7 && z == 4 && x >= 3 && x <= 5 || x == 4 && y == 7 && z == 5;
        assertTrue(DonutHoleSignal.hasHole(0, 0, 0, 8, row));
        DonutHoleSignal.OpenCell sideOpening = (x, y, z) ->
            y >= 2 && y <= 7 && z == 4 && x >= 3 && x <= 5 || x == 4 && y >= 2 && y <= 7 && z == 5;
        assertFalse(DonutHoleSignal.hasHole(0, 0, 0, 8, sideOpening));
    }
}
