package dev.donut.addon.expansion.search.amethyst;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeodePaletteEvidenceTest {
    @Test void requiresEnoughPaletteCellsAndVisibleCorroboration() {
        assertTrue(GeodePaletteEvidence.candidate(12, 1, 12));
        assertFalse(GeodePaletteEvidence.candidate(11, 1, 12));
        assertFalse(GeodePaletteEvidence.candidate(20, 0, 12));
    }

    @Test void everyRelevantUpdateInvalidatesTheCachedChunk() {
        assertTrue(GeodePaletteEvidence.shouldInvalidate(true, false, false));
        assertTrue(GeodePaletteEvidence.shouldInvalidate(false, true, false));
        assertTrue(GeodePaletteEvidence.shouldInvalidate(false, false, true));
        assertFalse(GeodePaletteEvidence.shouldInvalidate(false, false, false));
    }

    @Test void lightModeRequiresARecognizedGrowthLevelAtALocalMaximum() {
        assertTrue(GeodePaletteEvidence.isGrowthLight(1));
        assertTrue(GeodePaletteEvidence.isGrowthLight(2));
        assertTrue(GeodePaletteEvidence.isGrowthLight(4));
        assertTrue(GeodePaletteEvidence.isGrowthLight(5));
        assertFalse(GeodePaletteEvidence.isGrowthLight(3));
        assertTrue(GeodePaletteEvidence.isLocalMaximum(4, 3, 2, 0, 1, 0, 2));
        assertFalse(GeodePaletteEvidence.isLocalMaximum(4, 4, 0, 0, 0, 0, 0));
    }

    @Test void glowModeRequiresDarkOpenCellAndLevelFourLitOpening() {
        assertTrue(GeodePaletteEvidence.isGlowEdge(0, true, 4, true));
        assertFalse(GeodePaletteEvidence.isGlowEdge(1, true, 4, true));
        assertFalse(GeodePaletteEvidence.isGlowEdge(0, false, 4, true));
        assertFalse(GeodePaletteEvidence.isGlowEdge(0, true, 5, true));
        assertFalse(GeodePaletteEvidence.isGlowEdge(0, true, 4, false));
    }
}
