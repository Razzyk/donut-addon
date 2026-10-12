package dev.donut.addon.hud.speedometer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeedometerMathTest {
    @Test
    void convertsMinecraftMovementToConfiguredUnits() {
        assertEquals(150.0, SpeedometerMath.INSTANCE.convert(150.0 / 3.6, SpeedometerHudComponent.Unit.KILOMETERS_PER_HOUR), 1e-9);
        assertEquals(10.0, SpeedometerMath.INSTANCE.convert(10.0, SpeedometerHudComponent.Unit.BLOCKS_PER_SECOND), 1e-9);
        assertEquals(22.369362920544, SpeedometerMath.INSTANCE.convert(10.0, SpeedometerHudComponent.Unit.MILES_PER_HOUR), 1e-9);
    }

    @Test
    void samplesOncePerTickAndResetsAcrossWorldPlayerAndTeleportChanges() {
        var sampler = new SpeedSampleTracker();
        var world = new Object();
        var player = new Object();

        assertEquals(0.0, sampler.update(world, player, 0, 64, 0, false), 1e-9);
        assertEquals(10.0, sampler.update(world, player, 0.5, 64, 0, false), 1e-9);
        assertEquals(0.0, sampler.update(world, player, 100, 64, 0, false), 1e-9);
        assertEquals(10.0, sampler.update(world, player, 100.5, 64, 0, false), 1e-9);
        assertEquals(0.0, sampler.update(new Object(), player, 101, 64, 0, false), 1e-9);
        assertEquals(0.0, sampler.update(world, new Object(), 101, 64, 0, false), 1e-9);
        assertEquals(0.0, sampler.update(null, player, 101, 64, 0, false), 1e-9);
    }

    @Test
    void canIncludeVerticalMovementAndFormatsTemplateSafely() {
        var sampler = new SpeedSampleTracker();
        var world = new Object();
        var player = new Object();
        sampler.update(world, player, 0, 64, 0, false);
        assertEquals(0.0, sampler.update(world, player, 0, 64.5, 0, false), 1e-9);
        assertEquals(10.0, sampler.update(world, player, 0, 65, 0, true), 1e-9);

        assertEquals("10.0 km/h", SpeedometerMath.INSTANCE.applyTemplate("{speed} {unit}", "10.0", "km/h"));
        assertEquals("10.0", SpeedometerMath.INSTANCE.format(10.0, 1));
        assertFalse(SpeedometerMath.INSTANCE.unitLabel(SpeedometerHudComponent.Unit.KILOMETERS_PER_HOUR).isBlank());
    }

    @Test
    void invalidPositionOrTickGapResetsWithoutProducingASpike() {
        var sampler = new SpeedSampleTracker();
        var world = new Object();
        var player = new Object();

        assertEquals(0.0, sampler.update(world, player, 0, 64, 0, false, 10), 1e-9);
        assertEquals(10.0, sampler.update(world, player, 0.5, 64, 0, false, 11), 1e-9);
        assertEquals(0.0, sampler.update(world, player, Double.NaN, 64, 0, false, 12), 1e-9);
        assertEquals(0.0, sampler.update(world, player, 0.7, 64, 0, false, 13), 1e-9);
        assertEquals(0.0, sampler.update(world, player, 1.2, 64, 0, false, 15), 1e-9);
        assertEquals(10.0, sampler.update(world, player, 1.7, 64, 0, false, 16), 1e-9);
    }

    @Test
    void verticalTeleportResetsEvenWhenDisplayUsesHorizontalSpeed() {
        var sampler = new SpeedSampleTracker();
        var world = new Object();
        var player = new Object();

        sampler.update(world, player, 0, 64, 0, false);
        assertEquals(0.0, sampler.update(world, player, 0, 130, 0, false), 1e-9);
        assertEquals(10.0, sampler.update(world, player, 0.5, 130, 0, false), 1e-9);
    }

    @Test
    void legitimateFastTravelBelowTeleportThresholdIsPreserved() {
        var sampler = new SpeedSampleTracker();
        var world = new Object();
        var player = new Object();

        sampler.update(world, player, 0, 64, 0, false);
        assertEquals(400.0, sampler.update(world, player, 20, 64, 0, false), 1e-9);
        assertEquals(1440.0, SpeedometerMath.INSTANCE.convert(400.0, SpeedometerHudComponent.Unit.KILOMETERS_PER_HOUR), 1e-9);
    }
}
