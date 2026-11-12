package dev.donut.addon.expansion.movement

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SurfaceWalkRulesTest {
    @Test fun waterExceptionsRemainIndependentAndUseTheStrictFallThreshold() {
        val options = SurfaceWalkRules.Options()
        val ordinary = SurfaceWalkRules.Guards()
        assertTrue(SurfaceWalkRules.water(ordinary.copy(fallDistance = 4.0), options, true))
        assertFalse(SurfaceWalkRules.water(ordinary.copy(fallDistance = 4.001), options, true))
        assertFalse(SurfaceWalkRules.water(ordinary.copy(burning = true), options, true))
        assertTrue(SurfaceWalkRules.water(ordinary.copy(burning = true), options, false))
        assertTrue(SurfaceWalkRules.water(ordinary.copy(sneaking = true), options.copy(sneakDip = false), true))
    }

    @Test fun dangerousLavaKeepsSupportEvenWhenSneakingOrFalling() {
        val dipping = SurfaceWalkRules.Guards(sneaking = true, fallDistance = 100.0)
        val options = SurfaceWalkRules.Options()
        assertTrue(SurfaceWalkRules.lava(dipping, options, true))
        assertFalse(SurfaceWalkRules.lava(dipping.copy(lavaProtection = true), options, true))
        assertTrue(SurfaceWalkRules.lava(dipping.copy(lavaProtection = true), options, false))
        assertFalse(SurfaceWalkRules.lava(dipping, options.copy(solid = false), false))
    }

    @Test fun eachLiquidHonorsItsOwnVehicleAndFlightExceptions() {
        val ordinary = SurfaceWalkRules.Guards()
        val options = SurfaceWalkRules.Options()
        assertFalse(SurfaceWalkRules.water(ordinary.copy(boat = true), options, false))
        assertTrue(SurfaceWalkRules.lava(ordinary.copy(boat = true), options, false))
        assertFalse(SurfaceWalkRules.lava(ordinary.copy(strider = true), options, false))
        assertTrue(SurfaceWalkRules.water(ordinary.copy(strider = true), options, false))
        assertFalse(SurfaceWalkRules.water(ordinary.copy(flightModule = true), options, false))
        assertFalse(SurfaceWalkRules.lava(ordinary.copy(flying = true), options, false))
        assertFalse(SurfaceWalkRules.water(ordinary.copy(spectator = true), options, false))
    }

    @Test fun pulseOccursOnFifteenthSupportedMoveAndDoesNotCarryAcrossLandOrWorlds() {
        val pulse = SurfaceWalkRules.Pulse()
        repeat(14) { assertFalse(pulse.step(true)) }
        assertTrue(pulse.step(true))
        repeat(10) { assertFalse(pulse.step(true)) }
        assertFalse(pulse.step(false))
        repeat(14) { assertFalse(pulse.step(true)) }
        pulse.reset()
        repeat(14) { assertFalse(pulse.step(true)) }
        assertTrue(pulse.step(true))
    }
}
