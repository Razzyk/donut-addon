package dev.donut.addon;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AutoReflyStateTest {
    @Test void onlyAnObservedStopSchedulesRecovery() {
        var state = new AutoReflyState();
        state.serverFlag(true, false, false);
        assertFalse(state.shouldStart(1, true, true, true, false, false));
        state.serverFlag(true, false, true);
        // The old local bit can still be set, but a server stop requires a command.
        assertTrue(state.shouldStart(2, true, true, true, false, true));
        // No positive metadata echo is needed to resume normal reset transactions.
        assertFalse(state.shouldStart(3, true, true, true, false, true));
        assertFalse(state.isPending());
        assertFalse(state.shouldStart(20, true, true, true, false, true));
    }

    @Test void disablingOrLosingEligibilityCancelsRecovery() {
        var state = new AutoReflyState();
        state.serverFlag(false, false, true);
        assertFalse(state.shouldStart(1, true, true, true, false, false));
        state.serverFlag(true, false, true);
        assertFalse(state.shouldStart(2, false, true, true, false, false));
        state.serverFlag(true, false, true);
        assertFalse(state.shouldStart(3, true, false, true, false, false));
        assertFalse(state.shouldStart(4, true, true, true, false, false));
        state.serverFlag(true, false, true);
        assertFalse(state.shouldStart(5, true, true, false, false, false));
        assertFalse(state.shouldStart(6, true, true, true, false, false));
    }

    @Test void repeatedRejectionsAndNewStopsKeepTheCooldown() {
        var state = new AutoReflyState();
        state.serverFlag(true, false, true);
        assertTrue(state.shouldStart(10, true, true, true, false, true));
        state.serverFlag(true, false, true);
        // A new stop cannot be dismissed by the locally preserved glide bit.
        assertFalse(state.shouldStart(14, true, true, true, false, true));
        assertTrue(state.isPending());
        assertTrue(state.shouldStart(15, true, true, true, false, true));
        state.serverFlag(true, true, true);
        state.serverFlag(true, false, true);
        assertFalse(state.shouldStart(16, true, true, true, false, true));
        assertTrue(state.shouldStart(20, true, true, true, false, true));
    }

    @Test void retriesWhenLocalFlightDoesNotStart() {
        var state = new AutoReflyState();
        state.serverFlag(true, false, true);
        assertTrue(state.shouldStart(10, true, true, true, false, false));
        assertFalse(state.shouldStart(14, true, true, true, false, false));
        assertTrue(state.shouldStart(15, true, true, true, false, false));
    }

    @Test void transactionRestoresTheChestBeforeRecovery() {
        var state = new AutoReflyState();
        state.serverFlag(true, false, true);
        assertFalse(state.shouldStart(10, true, true, false, true, true));
        assertTrue(state.isPending());
        assertTrue(state.shouldStart(11, true, true, true, false, true));
        assertFalse(state.shouldStart(12, true, true, true, false, true));
        assertFalse(state.isPending());
    }
}
