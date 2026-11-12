package dev.donut.addon.expansion.building

import java.util.PriorityQueue
import kotlin.math.abs

/** Bounded cell search used by Donut Tunnel Base Finder. */
internal object TunnelRoutePlanner {
    data class Cell(val x: Int, val z: Int)

    enum class Heading(val dx: Int, val dz: Int) {
        NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);

        val sideX: Int get() = -dz
        val sideZ: Int get() = dx
    }

    data class Request(
        val start: Cell,
        val heading: Heading,
        val axis: Cell,
        val drift: Int,
        val target: Cell? = null,
        val aheadLimit: Int = 40,
        val sideLimit: Int = 15,
        val maxExpanded: Int = 6_000,
    )

    data class Result(val cells: List<Cell>, val expanded: Int, val reachedTarget: Boolean)

    /** Returns a safe route prefix, excluding the start cell. */
    fun plan(
        request: Request,
        safe: (Cell) -> Boolean,
        blocked: Set<Cell> = emptySet(),
        visitedChunks: Set<Long> = emptySet(),
        chunkKey: (Cell) -> Long = { 0L },
        currentChunk: Long = Long.MIN_VALUE,
        targetChunk: Long = Long.MIN_VALUE,
        noise: (Cell) -> Double = { 0.0 },
    ): Result {
        require(request.aheadLimit >= 0 && request.sideLimit >= 0 && request.maxExpanded > 0)
        val start = request.start
        val target = request.target
        val frontier = PriorityQueue(compareBy<OpenNode> { it.priority }.thenBy { it.cost })
        val cheapest = HashMap<Cell, Double>()
        val parents = HashMap<Cell, Cell>()
        frontier += OpenNode(start, 0.0, heuristic(start, target))
        cheapest[start] = 0.0

        var best = start
        var bestRank = rank(start, request)
        var expanded = 0
        while (frontier.isNotEmpty() && expanded < request.maxExpanded) {
            val node = frontier.remove()
            if (node.cost != cheapest[node.cell]) continue
            expanded++

            val nodeRank = rank(node.cell, request)
            if (nodeRank > bestRank || nodeRank == bestRank && node.cost < (cheapest[best] ?: Double.POSITIVE_INFINITY)) {
                best = node.cell
                bestRank = nodeRank
            }
            if (target != null && node.cell == target) {
                best = node.cell
                break
            }

            for (heading in Heading.entries) {
                val next = Cell(node.cell.x + heading.dx, node.cell.z + heading.dz)
                if (!insideBounds(next, request) || next in blocked || !safe(next)) continue
                val chunk = chunkKey(next)
                val revisitCost = if (chunk != currentChunk && chunk != targetChunk && chunk in visitedChunks) 4.0 else 0.0
                val nextCost = node.cost + 1.0 + noise(next).coerceIn(-0.5, 3.0) + revisitCost
                if (nextCost >= (cheapest[next] ?: Double.POSITIVE_INFINITY)) continue

                cheapest[next] = nextCost
                parents[next] = node.cell
                frontier += OpenNode(next, nextCost, nextCost + heuristic(next, target))
            }
        }

        val path = ArrayList<Cell>()
        var cursor = best
        while (cursor != start) {
            path += cursor
            cursor = parents[cursor] ?: break
        }
        path.reverse()
        return Result(path, expanded, target != null && best == target)
    }

    private fun insideBounds(cell: Cell, request: Request): Boolean {
        val target = request.target
        if (target != null) {
            return abs(cell.x - request.start.x) <= request.aheadLimit &&
                abs(cell.z - request.start.z) <= request.aheadLimit
        }
        val forward = (cell.x - request.start.x) * request.heading.dx + (cell.z - request.start.z) * request.heading.dz
        val lateral = (cell.x - request.axis.x) * request.heading.sideX + (cell.z - request.axis.z) * request.heading.sideZ
        val startLateral = (request.start.x - request.axis.x) * request.heading.sideX +
            (request.start.z - request.axis.z) * request.heading.sideZ
        return forward in -2..request.aheadLimit && abs(lateral - startLateral - request.drift) <= request.sideLimit
    }

    private fun heuristic(cell: Cell, target: Cell?): Double =
        if (target == null) 0.0 else abs(cell.x - target.x).toDouble() + abs(cell.z - target.z)

    private fun rank(cell: Cell, request: Request): Double {
        val target = request.target
        if (target != null) return -abs(cell.x - target.x).toDouble() - abs(cell.z - target.z)
        val forward = (cell.x - request.start.x) * request.heading.dx + (cell.z - request.start.z) * request.heading.dz
        val lateral = (cell.x - request.axis.x) * request.heading.sideX + (cell.z - request.axis.z) * request.heading.sideZ
        return forward - 2.0 * abs(lateral - request.drift)
    }

    private data class OpenNode(val cell: Cell, val cost: Double, val priority: Double)
}
