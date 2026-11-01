package dev.donut.addon;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NoJumpDelayInputStateTest {
    @Test void heldGroundJumpIsReleasedInAirAndResumesOnLanding() {
        var state = new NoJumpDelayInputState();
        assertTrue(state.filter(true, true, true, false));
        assertFalse(state.filter(true, true, false, false));
        assertTrue(state.filter(true, true, true, false));
    }
    @Test void airbornePressFluidCreativeAndDisableRemainUsable() {
        var state = new NoJumpDelayInputState();
        assertTrue(state.filter(true, true, false, false));
        assertTrue(state.filter(true, true, true, false));
        assertTrue(state.filter(true, true, false, true));
        assertTrue(state.filter(true, true, false, false));
        assertTrue(state.filter(true, true, true, false));
        assertTrue(state.filter(false, true, false, false));
        assertTrue(state.filter(true, true, false, false));
    }
    @Test void releasingAndRepressingInAirIsAnIntentionalInput() {
        var state = new NoJumpDelayInputState();
        assertTrue(state.filter(true, true, true, false));
        assertFalse(state.filter(true, false, false, false));
        assertTrue(state.filter(true, true, false, false));
    }
}
