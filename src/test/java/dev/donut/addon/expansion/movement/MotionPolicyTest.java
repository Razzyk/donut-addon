package dev.donut.addon.expansion.movement;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MotionPolicyTest {
    @Test void diagonalTravelHasTheSameSpeedAsStraightTravel() {
        var straight = MotionPolicy.horizontal(42f, 1, 0, 15.0);
        var diagonal = MotionPolicy.horizontal(42f, 1, 1, 15.0);
        assertEquals(.75, Math.hypot(straight.getX(), straight.getZ()), 1e-9);
        assertEquals(.75, Math.hypot(diagonal.getX(), diagonal.getZ()), 1e-9);
        var east = MotionPolicy.horizontal(90f, 1, 0, 20);
        assertEquals(-1, east.getX(), 1e-9);
        assertEquals(0, east.getZ(), 1e-9);
        assertEquals(0, MotionPolicy.horizontal(0, 0, 0, 20).getX());
    }

    @Test void cooldownVarianceCannotIncreaseTheVanillaDelay() {
        assertEquals(10, MotionPolicy.jumpCooldown(10, 9, 4, 4));
        assertEquals(5, MotionPolicy.jumpCooldown(10, 3, 4, 2));
        assertEquals(0, MotionPolicy.jumpCooldown(10, 0, 0, 0));
    }

}
