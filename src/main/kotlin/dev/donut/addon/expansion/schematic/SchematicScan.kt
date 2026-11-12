package dev.donut.addon.expansion.schematic

/** World-coordinate bounds returned by Litematica's enabled placement boxes. */
data class SchematicRegion(
    val placementId: String,
    val min: BuildCellPos,
    val max: BuildCellPos,
) {
    fun normalized(): SchematicRegion = copy(
        min = BuildCellPos(minOf(min.x, max.x), minOf(min.y, max.y), minOf(min.z, max.z)),
        max = BuildCellPos(maxOf(min.x, max.x), maxOf(min.y, max.y), maxOf(min.z, max.z)),
    )

    fun volume(): Long {
        val box = normalized()
        val x = box.max.x.toLong() - box.min.x + 1L
        val y = box.max.y.toLong() - box.min.y + 1L
        val z = box.max.z.toLong() - box.min.z + 1L
        return runCatching { Math.multiplyExact(Math.multiplyExact(x, y), z) }.getOrDefault(Long.MAX_VALUE)
    }

    fun contains(pos: BuildCellPos): Boolean {
        val box = normalized()
        return pos.x in box.min.x..box.max.x && pos.y in box.min.y..box.max.y && pos.z in box.min.z..box.max.z
    }
}

data class SchematicScanPage(val positions: List<BuildCellPos>, val complete: Boolean, val truncated: Boolean)

/** Iterates a bounded union of all enabled placement boxes, visiting overlaps exactly once. */
class SchematicScanCursor(
    regions: Collection<SchematicRegion>,
    private val maxUniqueCells: Long = 2_000_000L,
    private val continueWhenLimitReached: Boolean = false,
) {
    private val boxes = regions.map(SchematicRegion::normalized)
    private val limit = maxUniqueCells.coerceAtLeast(0L)
    private var regionIndex = 0
    private var x = boxes.firstOrNull()?.min?.x ?: 0
    private var y = boxes.firstOrNull()?.min?.y ?: 0
    private var z = boxes.firstOrNull()?.min?.z ?: 0
    private var count = 0L
    private var windowCount = 0L
    private var done = boxes.isEmpty() || limit == 0L

    val acceptedCount: Long get() = count
    val isComplete: Boolean get() = done
    val wasTruncated: Boolean get() = done && !allBoxesConsumed()

    /** Replays the same bounded region union for a later semantic refresh pass. */
    fun reset() {
        regionIndex = 0
        x = boxes.firstOrNull()?.min?.x ?: 0
        y = boxes.firstOrNull()?.min?.y ?: 0
        z = boxes.firstOrNull()?.min?.z ?: 0
        count = 0
        windowCount = 0
        done = boxes.isEmpty() || limit == 0L
    }

    fun next(budget: Int): SchematicScanPage {
        val output = ArrayList<BuildCellPos>(budget.coerceAtLeast(0))
        var steps = 0
        while (!done && output.size < budget.coerceAtLeast(0) && steps < budget.coerceAtLeast(0) * 64 + 64) {
            if (regionIndex >= boxes.size) {
                done = true
                break
            }
            val currentRegionIndex = regionIndex
            val box = boxes[currentRegionIndex]
            val pos = BuildCellPos(x, y, z)
            advance(box)
            steps++
            if (boxes.take(currentRegionIndex).none { it.contains(pos) }) {
                if (!continueWhenLimitReached && count >= limit) {
                    done = true
                    break
                }
                count++
                windowCount++
                output += pos
                if (windowCount >= limit) {
                    if (continueWhenLimitReached) windowCount = 0L
                    else if (regionIndex < boxes.size) done = true
                }
            }
        }
        if (regionIndex >= boxes.size) done = true
        return SchematicScanPage(output, done, wasTruncated)
    }

    private fun advance(box: SchematicRegion) {
        if (z < box.max.z) {
            z++
        } else {
            z = box.min.z
            if (x < box.max.x) {
                x++
            } else {
                x = box.min.x
                if (y < box.max.y) {
                    y++
                } else {
                    regionIndex++
                    if (regionIndex < boxes.size) {
                        val next = boxes[regionIndex]
                        x = next.min.x
                        y = next.min.y
                        z = next.min.z
                    }
                }
            }
        }
    }

    private fun allBoxesConsumed(): Boolean = regionIndex >= boxes.size
}

sealed interface SchematicCellRead {
    data object Unavailable : SchematicCellRead
    data object Air : SchematicCellRead
    data class Block(val cell: BuildCell) : SchematicCellRead
}

data class SchematicIntakeSnapshot(
    val cells: Map<BuildCellPos, BuildCell>,
    val pending: Set<BuildCellPos>,
)

/** Unavailable/unbuilt virtual chunks stay pending; only an available air state removes a cell. */
class SchematicIntake {
    private val cells = linkedMapOf<BuildCellPos, BuildCell>()
    private val pending = linkedSetOf<BuildCellPos>()

    fun observe(pos: BuildCellPos, read: SchematicCellRead) {
        when (read) {
            SchematicCellRead.Unavailable -> pending += pos
            SchematicCellRead.Air -> {
                pending -= pos
                cells -= pos
            }
            is SchematicCellRead.Block -> {
                pending -= pos
                cells[pos] = read.cell.copy(pos = pos)
            }
        }
    }

    fun snapshot(): SchematicIntakeSnapshot = SchematicIntakeSnapshot(cells.toMap(), pending.toSet())
    fun clear() { cells.clear(); pending.clear() }
}

/** Chunk coordinates must floor for negative world positions. */
fun BuildCellPos.chunkCoordinates(): Pair<Int, Int> = Math.floorDiv(x, 16) to Math.floorDiv(z, 16)

/** Reads by absolute world position and refuses to interpret a non-materialized virtual chunk as air. */
fun <T : Any> readProjectedCell(
    worldPosition: BuildCellPos,
    isChunkLoaded: (chunkX: Int, chunkZ: Int) -> Boolean,
    stateAtWorldPosition: (BuildCellPos) -> T,
    isAir: (T) -> Boolean,
    toBuildCell: (BuildCellPos, T) -> BuildCell,
): SchematicCellRead {
    val (chunkX, chunkZ) = worldPosition.chunkCoordinates()
    if (!runCatching { isChunkLoaded(chunkX, chunkZ) }.getOrDefault(false)) return SchematicCellRead.Unavailable
    val state = runCatching { stateAtWorldPosition(worldPosition) }.getOrNull() ?: return SchematicCellRead.Unavailable
    return if (runCatching { isAir(state) }.getOrDefault(false)) SchematicCellRead.Air
    else runCatching { SchematicCellRead.Block(toBuildCell(worldPosition, state)) }.getOrDefault(SchematicCellRead.Unavailable)
}

data class SchematicRouteResult(val route: List<BuildCellPos>?, val visited: Int, val exhaustedBudget: Boolean)

object SchematicRouteRules {
    fun allows(from: BuildCellPos, to: BuildCellPos): Boolean =
        kotlin.math.abs(from.x - to.x) + kotlin.math.abs(from.z - to.z) == 1 && to.y - from.y in -3..1
}

/** Bounded feet-position route search with one-block steps and short drops. */
object SchematicStandPlanner {
    fun findRoute(
        start: BuildCellPos,
        isGoal: (BuildCellPos) -> Boolean,
        canStand: (BuildCellPos) -> Boolean,
        canTransition: (BuildCellPos, BuildCellPos) -> Boolean = { _, _ -> true },
        visitBudget: Int = 3_000,
        horizontalRadius: Int = 48,
        maxDrop: Int = 3,
    ): SchematicRouteResult {
        if (isGoal(start)) return SchematicRouteResult(emptyList(), 1, false)
        val queue = ArrayDeque<BuildCellPos>()
        val previous = HashMap<BuildCellPos, BuildCellPos?>()
        queue += start
        previous[start] = null
        val offsets = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        var found: BuildCellPos? = null
        var visited = 0
        val budget = visitBudget.coerceAtLeast(0)
        while (queue.isNotEmpty() && visited < budget) {
            val here = queue.removeFirst()
            visited++
            if (isGoal(here)) { found = here; break }
            for ((dx, dz) in offsets) {
                for (dy in 1 downTo -maxDrop) {
                    val next = here.offset(dx, dy, dz)
                    if (!SchematicRouteRules.allows(here, next)) continue
                    if (kotlin.math.abs(next.x - start.x) > horizontalRadius || kotlin.math.abs(next.z - start.z) > horizontalRadius) continue
                    if (next in previous || !canStand(next) || !canTransition(here, next)) continue
                    previous[next] = here
                    queue += next
                }
            }
        }
        if (found == null) return SchematicRouteResult(null, visited, queue.isNotEmpty() && visited >= budget)
        val reversed = ArrayList<BuildCellPos>()
        var cursor: BuildCellPos? = found
        while (cursor != null && cursor != start) {
            reversed += cursor
            cursor = previous[cursor]
        }
        reversed.reverse()
        return SchematicRouteResult(reversed, visited, false)
    }
}
