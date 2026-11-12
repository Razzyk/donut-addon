package dev.donut.addon.expansion.movement;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VehicleCadenceTest {
    @Test void pulseHoldsForOneRealTickThenRestoresTheLatestOrdinaryHeight() {
        var state = new VehicleCadence();
        state.reset(1);
        state.tick();
        assertEquals(VehicleCadence.Action.PASS, state.accept(100, true, false, 40, 0));
        assertEquals(VehicleCadence.Action.DIP, state.accept(100, true, false, 40, 1));
        assertTrue(state.isHolding(25_000_001));
        assertEquals(VehicleCadence.Action.HOLD, state.accept(101, true, false, 40, 25_000_001));
        assertNull(state.recovery(49_999_999));
        assertEquals(101.0, state.recovery(50_000_001));
        assertFalse(state.isHolding(50_000_001));
        assertNull(state.recovery(100_000_000));
    }

    @Test void naturalFallingGroundedVehiclesAndLocalServersDoNotGetPulsed() {
        var state = new VehicleCadence();
        state.reset(1);
        state.tick();
        state.accept(100, true, false, 40, 0);
        assertEquals(VehicleCadence.Action.PASS, state.accept(99, true, false, 40, 1));
        assertEquals(VehicleCadence.Action.PASS, state.accept(99, false, false, 40, 2));
        assertEquals(VehicleCadence.Action.PASS, state.accept(99, true, true, 40, 3));
        state.reset(40);
        assertNull(state.recovery(1_000_000_000));
    }
}
