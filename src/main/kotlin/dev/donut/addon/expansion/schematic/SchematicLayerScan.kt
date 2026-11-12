package dev.donut.addon.expansion.schematic

import java.util.PriorityQueue

/** One bounded horizontal slice of one schematic Y layer. */
data class SchematicLayerWindow(
    val y: Int,
    val chunkX: Int,
    val chunkZ: Int,
    val positions: List<BuildCellPos>,
) {
    val key: SchematicLayerWindowKey get() = SchematicLayerWindowKey(y, chunkX, chunkZ)
}

data class SchematicLayerWindowKey(val y: Int, val chunkX: Int, val chunkZ: Int) : Comparable<SchematicLayerWindowKey> {
    override fun compareTo(other: SchematicLayerWindowKey): Int =
        compareValuesBy(this, other, SchematicLayerWindowKey::y, SchematicLayerWindowKey::chunkX, SchematicLayerWindowKey::chunkZ)
}

/**
 * Streams non-empty 16x16 windows in layer, chunk-X, chunk-Z order without materializing the full
 * set of windows or the full set of schematic cells. Overlapping boxes contribute each position
 * once, and disjoint distant placements do not force iteration through the space between them.
 */
class SchematicLayerScanCursor(
    regions: Collection<SchematicRegion>,
    private val layerSpan: Int = 2,
    startY: Int? = null,
    endY: Int? = null,
) {
    companion object {
        private const val MAX_LAYER_SPAN = 2
    }

    private val effectiveLayerSpan = layerSpan.coerceIn(1, MAX_LAYER_SPAN)
    private val boxes = regions.map(SchematicRegion::normalized).distinct()
    private val firstY = maxOf(boxes.minOfOrNull { it.min.y } ?: 0, startY ?: Int.MIN_VALUE)
    private val lastY = minOf(boxes.maxOfOrNull { it.max.y } ?: -1, endY ?: Int.MAX_VALUE)
    private var y = firstY
    private var queuedY: Int? = null
    private var queue = newQueue()
    private var finished = firstY > lastY

    var windowsEmitted: Long = 0
        private set
    var cellsEmitted: Long = 0
        private set
    var cellLimitReached: Boolean = false
        private set

    val isComplete: Boolean get() = finished

    fun keyAt(pos: BuildCellPos): SchematicLayerWindowKey? {
        if (pos.y !in firstY..lastY || boxes.none { it.contains(pos) }) return null
        val span = effectiveLayerSpan.toLong()
        val offset = pos.y.toLong() - firstY.toLong()
        val baseY = firstY.toLong() + Math.floorDiv(offset, span) * span
        if (baseY !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
        return SchematicLayerWindowKey(baseY.toInt(), Math.floorDiv(pos.x, 16), Math.floorDiv(pos.z, 16))
    }

    fun reset() {
        y = firstY
        queuedY = null
        queue = newQueue()
        finished = firstY > lastY
        windowsEmitted = 0
        cellsEmitted = 0
        cellLimitReached = false
    }

    /** Advances across empty Y bands without walking every integer layer between placements. */
    fun hasRemainingWindows(): Boolean {
        while (!finished) {
            if (queuedY != y) initializeLayer()
            if (queue.isNotEmpty()) return true
            if (!advanceLayer()) return false
        }
        return false
    }

    fun stopAtCellLimit() {
        finished = true
        queuedY = null
        queue.clear()
        cellLimitReached = true
    }

    /** Reconstructs one previously seen layer/chunk without retaining its cell list. */
    fun windowAt(baseY: Int, chunkX: Int, chunkZ: Int): SchematicLayerWindow? {
        val finalY = minOf(lastY.toLong(), baseY.toLong() + effectiveLayerSpan - 1L).toInt()
        if (baseY > finalY || baseY < firstY || finalY > lastY) return null
        val positions = coveredPositions(baseY, finalY, chunkX, chunkZ, boxes.indices.asSequence())
        return positions.takeIf { it.isNotEmpty() }?.let { SchematicLayerWindow(baseY, chunkX, chunkZ, it) }
    }

    fun nextWindow(maxCells: Long = Long.MAX_VALUE): SchematicLayerWindow? {
        while (!finished) {
            if (queuedY != y) initializeLayer()
            val available = (maxCells - cellsEmitted).coerceAtLeast(0L)
            if (available == 0L) {
                if (hasRemainingWindows()) stopAtCellLimit()
                return null
            }
            val first = queue.poll()
            if (first == null) {
                advanceLayer()
                continue
            }

            val chunkX = first.chunkX
            val chunkZ = first.chunkZ
            val owners = ArrayList<Int>()
            owners += first.regionIndex
            advanceAndRequeue(first)
            while (queue.peek()?.let { it.chunkX == chunkX && it.chunkZ == chunkZ } == true) {
                val same = queue.poll()
                owners += same.regionIndex
                advanceAndRequeue(same)
            }

            val finalY = minOf(lastY.toLong(), y.toLong() + effectiveLayerSpan - 1L).toInt()
            val positions = coveredPositions(y, finalY, chunkX, chunkZ, owners.asSequence())

            // A chunk cursor is created only for a box intersecting this layer and chunk, so
            // positions cannot be empty. Keep the guard in case bounds semantics change later.
            if (positions.isEmpty()) continue
            windowsEmitted++
            if (positions.size.toLong() > available) {
                val bounded = positions.take(available.toInt())
                cellsEmitted += bounded.size
                stopAtCellLimit()
                return SchematicLayerWindow(y, chunkX, chunkZ, bounded)
            }
            cellsEmitted += positions.size
            return SchematicLayerWindow(y, chunkX, chunkZ, positions)
        }
        return null
    }

    private fun advanceLayer(): Boolean {
        val span = effectiveLayerSpan.toLong()
        val minimumNextY = y.toLong() + span
        if (minimumNextY > lastY.toLong() || minimumNextY > Int.MAX_VALUE.toLong()) {
            finished = true
            queuedY = null
            return false
        }
        val nextRelevant = boxes.asSequence().mapNotNull { box ->
            val relevantY = maxOf(box.min.y.toLong(), minimumNextY)
            if (relevantY > box.max.y.toLong()) null
            else firstY.toLong() + Math.floorDiv(relevantY - firstY.toLong(), span) * span
        }.minOrNull()
        if (nextRelevant == null || nextRelevant > lastY.toLong() || nextRelevant !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            finished = true
            queuedY = null
            return false
        }
        y = nextRelevant.toInt()
        queuedY = null
        return true
    }

    private fun initializeLayer() {
        queue = newQueue()
        if (!finished && y <= lastY) {
            for ((index, box) in boxes.withIndex()) {
                val bandEnd = minOf(lastY.toLong(), y.toLong() + effectiveLayerSpan - 1L).toInt()
                if (box.min.y <= bandEnd && box.max.y >= y) {
                    val cursor = RegionChunkCursor(index, Math.floorDiv(box.min.x, 16), Math.floorDiv(box.max.x, 16),
                        Math.floorDiv(box.min.z, 16), Math.floorDiv(box.max.z, 16))
                    queue += cursor
                }
            }
        }
        queuedY = y
    }

    private fun advanceAndRequeue(cursor: RegionChunkCursor) {
        if (cursor.advance()) queue += cursor
    }

    /** Unions region rectangles with a bounded 2D difference grid for each Y in the window. */
    private fun coveredPositions(
        baseY: Int,
        finalY: Int,
        chunkX: Int,
        chunkZ: Int,
        owners: Sequence<Int>,
    ): List<BuildCellPos> {
        val yCount = (finalY.toLong() - baseY.toLong() + 1L).coerceIn(0L, MAX_LAYER_SPAN.toLong()).toInt()
        if (yCount == 0) return emptyList()
        val firstX = chunkX.toLong() * 16L
        val firstZ = chunkZ.toLong() * 16L
        val diff = Array(yCount) { Array(17) { IntArray(17) } }
        for (owner in owners) {
            val box = boxes[owner]
            val lowX = maxOf(firstX, box.min.x.toLong())
            val highX = minOf(firstX + 15L, box.max.x.toLong())
            val lowZ = maxOf(firstZ, box.min.z.toLong())
            val highZ = minOf(firstZ + 15L, box.max.z.toLong())
            val lowY = maxOf(baseY.toLong(), box.min.y.toLong())
            val highY = minOf(finalY.toLong(), box.max.y.toLong())
            if (lowX > highX || lowZ > highZ || lowY > highY) continue
            val x0 = (lowX - firstX).toInt()
            val x1 = (highX - firstX).toInt() + 1
            val z0 = (lowZ - firstZ).toInt()
            val z1 = (highZ - firstZ).toInt() + 1
            for (cellY in lowY.toInt()..highY.toInt()) {
                val bandY = cellY - baseY
                diff[bandY][x0][z0]++
                diff[bandY][x1][z0]--
                diff[bandY][x0][z1]--
                diff[bandY][x1][z1]++
            }
        }
        val positions = ArrayList<BuildCellPos>(256 * yCount)
        for (dy in 0 until yCount) {
            val coverage = diff[dy]
            for (x in 0..15) for (z in 0..15) {
                var count = coverage[x][z]
                if (x > 0) count += coverage[x - 1][z]
                if (z > 0) count += coverage[x][z - 1]
                if (x > 0 && z > 0) count -= coverage[x - 1][z - 1]
                coverage[x][z] = count
                if (count > 0) {
                    val worldX = firstX + x
                    val worldZ = firstZ + z
                    if (worldX in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
                        worldZ in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                        positions += BuildCellPos(worldX.toInt(), baseY + dy, worldZ.toInt())
                    }
                }
            }
        }
        return positions
    }

    private fun newQueue() = PriorityQueue<RegionChunkCursor>(compareBy({ it.chunkX }, { it.chunkZ }, { it.regionIndex }))

    private class RegionChunkCursor(
        val regionIndex: Int,
        private val minChunkX: Int,
        private val maxChunkX: Int,
        private val minChunkZ: Int,
        private val maxChunkZ: Int,
    ) {
        var chunkX = minChunkX
            private set
        var chunkZ = minChunkZ
            private set

        fun advance(): Boolean {
            if (chunkZ < maxChunkZ) {
                chunkZ++
            } else {
                chunkZ = minChunkZ
                if (chunkX >= maxChunkX) return false
                chunkX++
            }
            return true
        }
    }
}
