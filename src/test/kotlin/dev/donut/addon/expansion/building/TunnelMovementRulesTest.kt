package dev.donut.addon.expansion.building

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import net.minecraft.core.Direction

class TunnelMovementRulesTest {
    @Test
    fun `movement progress resets when a destination changes and times out after sixty unchanged ticks`() {
        val progress = TunnelWalkProgress()

        assertEquals(TunnelWalkProgress.State.MOVING, progress.observe(4.0, 0.0, 0.0, 0.0))
        assertEquals(TunnelWalkProgress.State.MOVING, progress.observe(4.0, 0.0, 0.2, 0.0))
        assertEquals(TunnelWalkProgress.State.ARRIVED, progress.observe(4.0, 0.0, 3.8, 0.0))
        repeat(60) {
            val state = progress.observe(8.0, 0.0, 0.0, 0.0)
            if (it < 59) assertEquals(TunnelWalkProgress.State.MOVING, state)
            else assertEquals(TunnelWalkProgress.State.STALLED, state)
        }
    }

    @Test
    fun `stand distance and lateral tolerance match the source envelope`() {
        assertTrue(TunnelStandRules.isPositioned(5.5, 0.5, 8.0, 0.5, 1, 0, 0, 1, 0.45))
        assertFalse(TunnelStandRules.isPositioned(7.1, 0.5, 8.0, 0.5, 1, 0, 0, 1, 0.45))
        assertFalse(TunnelStandRules.isPositioned(5.5, 1.1, 8.0, 0.5, 1, 0, 0, 1, 0.45))
    }

    @Test
    fun `narrow and varied offsets retain source back and side ranges`() {
        assertEquals(TunnelStandRules.Offset(1.15, 0.0), TunnelStandRules.sampleOffset(0.7, false, 0.5, 0.9))
        val varied = TunnelStandRules.sampleOffset(0.7, true, 0.5, 0.0)
        assertEquals(1.3, varied.back, 1e-9)
        assertEquals(-0.7, varied.lateral, 1e-9)
    }

    @Test
    fun `straight Shard streaming requires alignment and a clear or head target`() {
        assertTrue(TunnelStreamRules.shouldStream(3, true, true, true, false, false))
        assertTrue(TunnelStreamRules.shouldStream(3, true, true, false, false, true))
        assertFalse(TunnelStreamRules.shouldStream(3, true, true, false, false, false))
        assertFalse(TunnelStreamRules.shouldStream(3, true, false, true, false, false))
        assertFalse(TunnelStreamRules.shouldStream(1, true, true, true, false, false))
    }

    @Test
    fun `deep drop guard pauses only moving paths beyond the safe three block drop`() {
        assertFalse(TunnelDropRules.shouldPause(0.019, 8, false, false))
        assertFalse(TunnelDropRules.shouldPause(0.02, 3, false, false))
        assertFalse(TunnelDropRules.shouldPause(0.02, 8, true, false))
        assertFalse(TunnelDropRules.shouldPause(0.02, 8, false, true))
        assertTrue(TunnelDropRules.shouldPause(0.02, 4, false, false))
    }

    @Test
    fun `drift changes only when wandering without an active flagged target`() {
        assertFalse(TunnelDriftRules.shouldShift(false, false, 10L, 0L))
        assertFalse(TunnelDriftRules.shouldShift(true, true, 10L, 0L))
        assertFalse(TunnelDriftRules.shouldShift(true, false, 9L, 10L))
        assertTrue(TunnelDriftRules.shouldShift(true, false, 10L, 10L))
        assertEquals(-2, TunnelDriftRules.shift(-2, 0))
        assertEquals(0, TunnelDriftRules.shift(1, 0))
        assertEquals(2, TunnelDriftRules.shift(2, 2))
        assertEquals(600L, TunnelDriftRules.nextTick(0L, 0))
        assertEquals(1_780L, TunnelDriftRules.nextTick(0L, 59))
    }

    @Test
    fun `wander route noise matches the bounded source hash and vanishes when disabled`() {
        assertEquals(0.0, TunnelRouteNoise.weight(2, -7, 3, false))
        assertEquals(0.45845552297165193, TunnelRouteNoise.weight(2, -7, 3, true), 1e-12)
        assertEquals(TunnelRouteNoise.weight(2, -7, 3, true), TunnelRouteNoise.weight(2, -7, 3, true))
    }

    @Test
    fun `empty cell hops use the source distance branches`() {
        assertEquals(TunnelHopRules.EmptyCellAction.ADVANCE, TunnelHopRules.emptyCellAction(3.0, 9.0))
        assertEquals(TunnelHopRules.EmptyCellAction.RETURN_TO_PREVIOUS, TunnelHopRules.emptyCellAction(3.01, 1.21))
        assertEquals(TunnelHopRules.EmptyCellAction.HOP_TO_NEXT, TunnelHopRules.emptyCellAction(3.01, 1.2))
    }

    @Test
    fun `hop jumps require a grounded rise and a real step edge`() {
        assertFalse(TunnelHopRules.shouldJump(false, 1.0, true, 0.5, false))
        assertFalse(TunnelHopRules.shouldJump(true, 0.55, true, 0.5, false))
        assertFalse(TunnelHopRules.shouldJump(true, 0.8, false, 0.15, false))
        assertTrue(TunnelHopRules.shouldJump(true, 0.8, false, 0.16, false))
        assertTrue(TunnelHopRules.shouldJump(true, 0.8, false, 0.0, true))
    }

    @Test
    fun `mining aim varies source-style only inside the clipped face band`() {
        assertTrue(TunnelMiningAimRules.variedAim(0))
        assertTrue(TunnelMiningAimRules.variedAim(19))
        assertFalse(TunnelMiningAimRules.variedAim(20))
        assertEquals(0.22, TunnelMiningAimRules.aimCoordinate(-5.0, true), 1e-12)
        assertEquals(0.78, TunnelMiningAimRules.aimCoordinate(5.0, true), 1e-12)
        assertEquals(0.5, TunnelMiningAimRules.aimCoordinate(-5.0, false), 1e-12)

        val top = TunnelMiningAimRules.facePoint(
            10, 20, 30, Direction.UP, 0.1, 0.2, 0.3, 0.9, 0.8, 0.7, 0.25, 0.75,
        )
        assertEquals(TunnelMiningAimRules.FacePoint(10.3, 20.8, 30.6), top)
        val east = TunnelMiningAimRules.facePoint(
            0, 0, 0, Direction.EAST, 0.1, 0.2, 0.3, 0.9, 0.8, 0.7, 0.25, 0.75,
        )
        assertEquals(0.9, east.x, 1e-12)
        assertEquals(0.65, east.y, 1e-12)
        assertEquals(0.4, east.z, 1e-12)
    }

    @Test
    fun `mining reaction and wobble match source bounds and exact-on-face gating`() {
        assertEquals(2, TunnelMiningAimRules.reactionTicks(false, 10.0))
        assertEquals(2, TunnelMiningAimRules.reactionTicks(true, -10.0))
        assertEquals(6, TunnelMiningAimRules.reactionTicks(true, 10.0))
        assertEquals(3, TunnelMiningAimRules.reactionTicks(true, 0.0))
        assertEquals(null, TunnelMiningAimRules.wobbleRotation(true, 30, 1.0, 1.0))
        assertEquals(null, TunnelMiningAimRules.wobbleRotation(false, 0, 1.0, 1.0))
        assertEquals(0.35f to -0.245f, TunnelMiningAimRules.wobbleRotation(true, 29, 1.0, -1.0))
    }
}
