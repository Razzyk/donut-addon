package dev.donut.addon;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NoFallTest2StateTest {
    @Test void correctsDangerousAirToGroundLandingUsingThePreTravelY() {
        var state = new NoFallTest2State();
        state.beforeTick(20, true, false, 3);
        state.beforeTick(16, false, false, 3);
        var correction = state.beforeNetwork(15, true);
        assertNotNull(correction);
        assertEquals(16 + 9e-8, correction.y());
        assertTrue(correction.restoreHorizontal());
    }

    @Test void leavesSafeLandingsAndAirborneMovementUnchanged() {
        var state = new NoFallTest2State();
        state.beforeTick(20, true, false, 3);
        state.beforeTick(19, false, false, 3);
        assertNull(state.beforeNetwork(18, true));
        state.beforeTick(16, false, false, 3);
        assertNull(state.beforeNetwork(15, false));
    }

    @Test void doesNotTreatGroundToGroundMovementAsALanding() {
        var state = new NoFallTest2State();
        state.beforeTick(20, true, false, 3);
        assertNull(state.beforeNetwork(10, true));
    }

    @Test void usesTheLargerSetbackNudgeWithoutRollingBackHorizontalMovement() {
        var state = new NoFallTest2State();
        state.beforeTick(20, true, false, 3);
        state.beforeTick(16, false, false, 3);
        state.beforeNetwork(15, true);
        state.onSetback();
        state.beforeTick(15, true, false, 3);
        var correction = state.beforeNetwork(15, true);
        assertNotNull(correction);
        assertEquals(15 + 9e-5, correction.y());
        assertFalse(correction.restoreHorizontal());
        assertNull(state.beforeNetwork(15, true));
    }

    @Test void waterOrWebAndNewSessionResetTheFallBaseline() {
        var state = new NoFallTest2State();
        state.beforeTick(20, true, false, 3);
        state.beforeTick(15, false, true, 3);
        assertNull(state.beforeNetwork(14, true));
        state.reset();
        state.beforeTick(10, false, false, 3);
        assertNull(state.beforeNetwork(9, true));
    }

    @Test void expiredSetbackDoesNotNudgeARecentGroundedTick() {
        var state = new NoFallTest2State();
        state.beforeTick(20, true, false, 3);
        state.beforeTick(16, false, false, 3);
        state.beforeNetwork(15, true);
        state.onSetback();
        for (int tick = 0; tick < 101; tick++) state.beforeTick(14, false, false, 3);
        assertNull(state.beforeNetwork(14, true));
    }
}
