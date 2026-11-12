package dev.donut.addon.expansion.search.evidence;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LightLeakClassifierTest {
    @Test void opaqueCellRequiresLightBeyondItsOwnEmission() {
        assertTrue(LightLeakClassifier.solidCell(15, 2, 0));
        assertFalse(LightLeakClassifier.solidCell(14, 8, 0));
        assertFalse(LightLeakClassifier.solidCell(15, 1, 0));
        assertFalse(LightLeakClassifier.solidCell(15, 8, 15));
    }

    @Test void darkGapRequiresAThreeLevelStepAndExcludesGeodes() {
        assertTrue(LightLeakClassifier.darkGap(2, 5, false));
        assertFalse(LightLeakClassifier.darkGap(2, 4, false));
        assertFalse(LightLeakClassifier.darkGap(2, 10, true));
        assertFalse(LightLeakClassifier.darkGap(13, 15, false));
    }
}
