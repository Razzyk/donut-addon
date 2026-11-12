package dev.donut.addon.expansion.search.light;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LightCellClassifierTest {
    @Test
    void artificialLightMustBeatSkyAndMeetTheConfiguredCutoff() {
        assertTrue(LightCellClassifier.isArtificial(7, 0, 5));
        assertFalse(LightCellClassifier.isArtificial(4, 0, 5));
        assertFalse(LightCellClassifier.isArtificial(15, 15, 5));
    }

    @Test
    void denoiseKeepsClusterCellsAndDropsIsolatedHits() {
        var a = new LightCellClassifier.Cell(0, 0, 0);
        var b = new LightCellClassifier.Cell(1, 0, 0);
        var c = new LightCellClassifier.Cell(1, 1, 0);
        var isolated = new LightCellClassifier.Cell(8, 8, 8);
        Map<LightCellClassifier.Cell, Integer> hits = Map.of(a, 10, b, 11, c, 9, isolated, 8);

        assertTrue(LightCellClassifier.hasEnoughNeighbours(a, hits, 1));
        assertTrue(LightCellClassifier.hasEnoughNeighbours(b, hits, 2));
        assertFalse(LightCellClassifier.hasEnoughNeighbours(isolated, hits, 1));
        assertTrue(LightCellClassifier.hasEnoughNeighbours(isolated, hits, 0));
    }
}
