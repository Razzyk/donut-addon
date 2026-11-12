package dev.donut.addon.expansion.search.growth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MinedGeodeClassifierTest {
    @Test void requiresLargeAndUnlitShellEvidence() {
        assertTrue(MinedGeodeClassifier.candidate(160, 0, true));
        assertTrue(MinedGeodeClassifier.candidate(0, 10, true));
        assertFalse(MinedGeodeClassifier.candidate(160, 0, false));
        assertFalse(MinedGeodeClassifier.candidate(159, 0, true));
    }
}
