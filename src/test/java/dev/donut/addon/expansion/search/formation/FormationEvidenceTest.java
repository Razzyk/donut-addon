package dev.donut.addon.expansion.search.formation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FormationEvidenceTest {
    @Test void requiresAllContextSignals() {
        assertTrue(FormationEvidence.qualifies(4, 3, true, true, true));
        assertFalse(FormationEvidence.qualifies(4, 3, false, true, true));
        assertFalse(FormationEvidence.qualifies(4, 3, true, false, true));
        assertFalse(FormationEvidence.qualifies(4, 3, true, true, false));
        assertFalse(FormationEvidence.qualifies(2, 3, true, true, true));
    }

    @Test void confidenceGrowsOnlyForQualifiedStacks() {
        assertEquals(0, FormationEvidence.confidence(8, 3, true, false, true));
        assertEquals(2, FormationEvidence.confidence(3, 3, true, true, true));
        assertEquals(3, FormationEvidence.confidence(5, 3, true, true, true));
        assertEquals(4, FormationEvidence.confidence(7, 3, true, true, true));
    }
}
