package dev.donut.addon.expansion.movement;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlightAndStrideStateTest {
    @Test void flightCadenceSeparatesDescentWindowAndRecoveryBoundary() {
        var cadence = new FlightCadence();
        for (int i = 0; i < 19; i++) {
            cadence.tick(20, 2);
            assertFalse(cadence.getDipping());
        }
        cadence.tick(20, 2);
        assertTrue(cadence.getDipping());
        assertTrue(cadence.getBoundary());
        cadence.tick(20, 2);
        assertTrue(cadence.getDipping());
        assertFalse(cadence.getBoundary());
        cadence.tick(20, 2);
        assertFalse(cadence.getDipping());
        assertTrue(cadence.getBoundary());
        cadence.reset();
        assertFalse(cadence.getBoundary());
    }

    @Test void correctionResetDiscardsAccelerationAndTheSpeedCapStillApplies() {
        var state = new StrideState();
        var hop = state.advance(true, true, false, false, .28, .2873, 1.6, .40123128, -.08, .43);
        assertEquals(.40123128, hop.getVertical(), 1e-9);
        assertEquals(.43, hop.getHorizontal(), 1e-9);
        state.advance(true, false, false, false, 10, .2873, 1.6, .4, -.08, 20);
        state.reset();
        var after = state.advance(true, false, false, false, 0, .2873, 1.6, .4, -.08, 20);
        assertTrue(after.getHorizontal() < .4);
        assertEquals(-.08, after.getVertical(), 1e-9);
        assertEquals(0, state.advance(false, false, false, false, 1, .2873, 1.6, .4, -.08, 20).getHorizontal());
    }
}
