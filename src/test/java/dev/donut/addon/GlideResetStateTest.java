package dev.donut.addon;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GlideResetStateTest {
    @Test void cannotOverwriteAnUnfinishedSwapDuringServerLag() {
        var state = new GlideResetState();
        assertTrue(state.begin(10, 14));
        assertFalse(state.begin(26, 20));
        assertEquals(14, state.slot());
        assertFalse(state.needsRestore(26, true, true, true));
    }

    @Test void serverStopTriggersRestorationAndTheNextResetIsIndependent() {
        var state = new GlideResetState();
        assertFalse(state.serverStopped());
        state.begin(10, 14);
        assertTrue(state.serverStopped());
        assertTrue(state.needsRestore(11, true, true, true));
        state.finish();
        assertFalse(state.isPending());
        assertTrue(state.begin(30, 16));
        assertFalse(state.needsRestore(31, true, true, true));
    }

    @Test void disablingLandingOrAnUnexpectedGlideStopRequiresRecovery() {
        var state = new GlideResetState();
        state.begin(10, 14);
        assertTrue(state.needsRestore(11, false, true, true));
        assertTrue(state.needsRestore(11, true, false, true));
        assertTrue(state.needsRestore(11, true, true, false));
    }

    @Test void timeoutRecoversBothASwapAndTheNoFreeSlotFallback() {
        var state = new GlideResetState();
        state.begin(10, -1);
        assertFalse(state.needsRestore(49, true, true, true));
        assertTrue(state.needsRestore(50, true, true, true));
        assertEquals(-1, state.slot());
    }
}
