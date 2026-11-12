package dev.donut.addon.expansion.building

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TunnelRoutePlannerTest {
    @Test
    fun `flagged target is abandoned only after three stalled plans`() {
        val progress = TunnelTargetProgress()

        assertFalse(progress.recordPlan(currentDistance = 12, bestRouteDistance = 8))
        assertFalse(progress.recordPlan(currentDistance = 12, bestRouteDistance = 8))
        assertFalse(progress.recordPlan(currentDistance = 12, bestRouteDistance = 8))
        assertTrue(progress.recordPlan(currentDistance = 12, bestRouteDistance = 8))
    }

    @Test
    fun `a closer current position or route resets flagged target stalls`() {
        val progress = TunnelTargetProgress()

        assertFalse(progress.recordPlan(currentDistance = 12, bestRouteDistance = 8))
        assertFalse(progress.recordPlan(currentDistance = 11, bestRouteDistance = 8))
        assertFalse(progress.recordPlan(currentDistance = 11, bestRouteDistance = 7))
        assertFalse(progress.recordPlan(currentDistance = 11, bestRouteDistance = 7))
    }

    @Test
    fun `finds a shortest safe path to a reachable target`() {
        val start = TunnelRoutePlanner.Cell(0, 0)
        val target = TunnelRoutePlanner.Cell(3, 0)
        val request = TunnelRoutePlanner.Request(
            start, TunnelRoutePlanner.Heading.EAST, start, drift = 0,
            target = target, aheadLimit = 8, sideLimit = 4, maxExpanded = 100,
        )

        val result = TunnelRoutePlanner.plan(request, safe = { true })

        assertTrue(result.reachedTarget)
        assertEquals(3, result.cells.size)
        assertEquals(target, result.cells.last())
    }

    @Test
    fun `routes around blocked cells and rejects unsafe route cells`() {
        val start = TunnelRoutePlanner.Cell(0, 0)
        val target = TunnelRoutePlanner.Cell(2, 0)
        val blocked = setOf(TunnelRoutePlanner.Cell(1, 0))
        val request = TunnelRoutePlanner.Request(
            start, TunnelRoutePlanner.Heading.EAST, start, drift = 0,
            target = target, aheadLimit = 8, sideLimit = 4, maxExpanded = 100,
        )

        val result = TunnelRoutePlanner.plan(request, safe = { it.z != -1 }, blocked = blocked)

        assertTrue(result.reachedTarget)
        assertTrue(result.cells.size > 2)
        assertFalse(TunnelRoutePlanner.Cell(1, 0) in result.cells)
        assertFalse(result.cells.any { it.z == -1 })
    }

    @Test
    fun `wander route obeys forward bounds and lateral drift`() {
        val start = TunnelRoutePlanner.Cell(0, 0)
        val request = TunnelRoutePlanner.Request(
            start, TunnelRoutePlanner.Heading.NORTH, start, drift = 1,
            aheadLimit = 6, sideLimit = 2, maxExpanded = 200,
        )

        val result = TunnelRoutePlanner.plan(request, safe = { true })

        assertTrue(result.cells.isNotEmpty())
        assertTrue(result.cells.all { it.z in -6..0 && it.x in -1..3 })
    }

    @Test
    fun `route ranking uses the source axis when the start is laterally offset`() {
        val request = TunnelRoutePlanner.Request(
            start = TunnelRoutePlanner.Cell(2, 0),
            heading = TunnelRoutePlanner.Heading.NORTH,
            axis = TunnelRoutePlanner.Cell(0, 0),
            drift = 0,
            aheadLimit = 2,
            sideLimit = 2,
            maxExpanded = 100,
        )

        val result = TunnelRoutePlanner.plan(request, safe = { true })

        assertTrue(result.cells.isNotEmpty())
        assertEquals(0, result.cells.last().x)
        assertEquals(-2, result.cells.last().z)
    }

    @Test
    fun `search budget bounds work while preserving its best reachable prefix`() {
        val start = TunnelRoutePlanner.Cell(0, 0)
        val request = TunnelRoutePlanner.Request(
            start, TunnelRoutePlanner.Heading.EAST, start, drift = 0,
            target = TunnelRoutePlanner.Cell(20, 20), aheadLimit = 30, sideLimit = 30, maxExpanded = 10,
        )

        val result = TunnelRoutePlanner.plan(request, safe = { true })

        assertEquals(10, result.expanded)
        assertFalse(result.reachedTarget)
        assertTrue(result.cells.isNotEmpty())
    }
}
