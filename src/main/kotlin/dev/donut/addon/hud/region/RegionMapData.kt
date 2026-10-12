package dev.donut.addon.hud.region

data class RegionShard(
    val region: String,
    val number: Int,
    val column: Int,
    val row: Int,
    val width: Int,
    val height: Int,
)

object RegionMapData {
    const val GRID_SIZE = 36
    private val WORLD_RADIUS = 225_000.0

    @JvmField
    val regions = linkedMapOf(
        "NA West" to listOf(
            intArrayOf(4, 0, 2, 4),
            intArrayOf(6, 0, 2, 4),
            intArrayOf(8, 0, 2, 4),
            intArrayOf(10, 0, 2, 4),
            intArrayOf(12, 0, 2, 4),
            intArrayOf(14, 0, 2, 4),
            intArrayOf(4, 4, 2, 4),
            intArrayOf(6, 4, 2, 4),
            intArrayOf(8, 4, 2, 4),
            intArrayOf(10, 4, 2, 4),
            intArrayOf(12, 4, 2, 4),
            intArrayOf(14, 4, 2, 4),
            intArrayOf(4, 8, 2, 4),
            intArrayOf(6, 8, 2, 4),
            intArrayOf(8, 8, 2, 4),
            intArrayOf(10, 8, 2, 4),
            intArrayOf(12, 8, 2, 4),
            intArrayOf(14, 8, 2, 4),
            intArrayOf(12, 12, 2, 4),
            intArrayOf(14, 12, 2, 4),
        ),
        "NA East" to listOf(
            intArrayOf(16, 0, 2, 4),
            intArrayOf(18, 0, 2, 4),
            intArrayOf(20, 0, 2, 4),
            intArrayOf(22, 0, 2, 4),
            intArrayOf(24, 0, 4, 4),
            intArrayOf(28, 0, 2, 4),
            intArrayOf(30, 0, 2, 4),
            intArrayOf(32, 0, 2, 4),
            intArrayOf(34, 0, 2, 4),
            intArrayOf(16, 4, 2, 2),
            intArrayOf(18, 4, 2, 2),
            intArrayOf(20, 4, 2, 2),
            intArrayOf(22, 4, 2, 2),
            intArrayOf(24, 4, 2, 2),
            intArrayOf(26, 4, 2, 4),
            intArrayOf(28, 4, 2, 2),
            intArrayOf(30, 4, 2, 2),
            intArrayOf(32, 4, 2, 4),
            intArrayOf(34, 4, 2, 4),
            intArrayOf(16, 6, 2, 2),
            intArrayOf(18, 6, 2, 2),
            intArrayOf(20, 6, 2, 2),
            intArrayOf(22, 6, 2, 2),
            intArrayOf(24, 6, 2, 2),
            intArrayOf(28, 6, 2, 2),
            intArrayOf(30, 6, 2, 2),
            intArrayOf(16, 8, 2, 4),
            intArrayOf(18, 8, 2, 4),
            intArrayOf(20, 8, 4, 4),
            intArrayOf(24, 8, 2, 4),
            intArrayOf(26, 8, 2, 4),
            intArrayOf(28, 8, 2, 4),
            intArrayOf(30, 8, 2, 4),
            intArrayOf(32, 8, 2, 4),
            intArrayOf(34, 8, 2, 4),
            intArrayOf(16, 12, 2, 4),
            intArrayOf(18, 12, 2, 4),
            intArrayOf(20, 12, 4, 4),
            intArrayOf(24, 12, 2, 4),
            intArrayOf(26, 12, 2, 4),
            intArrayOf(28, 12, 2, 4),
            intArrayOf(30, 12, 2, 4),
            intArrayOf(32, 12, 2, 4),
            intArrayOf(34, 12, 2, 4),
            intArrayOf(16, 16, 4, 4),
            intArrayOf(20, 16, 2, 4),
            intArrayOf(22, 16, 2, 2),
            intArrayOf(24, 16, 4, 4),
            intArrayOf(28, 16, 2, 4),
            intArrayOf(30, 16, 2, 4),
            intArrayOf(32, 16, 2, 4),
            intArrayOf(34, 16, 2, 4),
            intArrayOf(22, 18, 2, 2),
            intArrayOf(32, 20, 2, 4),
            intArrayOf(34, 20, 2, 4),
            intArrayOf(32, 24, 2, 4),
            intArrayOf(34, 24, 2, 4),
        ),
        "Oceania" to listOf(
            intArrayOf(0, 0, 2, 2),
            intArrayOf(2, 0, 2, 4),
            intArrayOf(0, 2, 2, 2),
            intArrayOf(0, 4, 2, 2),
            intArrayOf(2, 4, 2, 4),
            intArrayOf(0, 6, 2, 2),
            intArrayOf(0, 8, 2, 2),
            intArrayOf(2, 8, 2, 4),
            intArrayOf(0, 10, 2, 2),
            intArrayOf(0, 12, 2, 2),
            intArrayOf(2, 12, 2, 4),
            intArrayOf(4, 12, 2, 4),
            intArrayOf(6, 12, 2, 4),
            intArrayOf(8, 12, 2, 4),
            intArrayOf(10, 12, 2, 4),
            intArrayOf(0, 14, 2, 2),
        ),
        "Asia" to listOf(
            intArrayOf(0, 16, 2, 4),
            intArrayOf(2, 16, 2, 4),
            intArrayOf(4, 16, 2, 4),
            intArrayOf(6, 16, 2, 4),
            intArrayOf(8, 16, 2, 2),
            intArrayOf(10, 16, 2, 2),
            intArrayOf(12, 16, 2, 2),
            intArrayOf(14, 16, 2, 2),
            intArrayOf(8, 18, 2, 2),
            intArrayOf(10, 18, 2, 2),
            intArrayOf(12, 18, 2, 2),
            intArrayOf(14, 18, 2, 2),
            intArrayOf(0, 20, 2, 4),
            intArrayOf(2, 20, 2, 4),
            intArrayOf(0, 24, 2, 4),
            intArrayOf(2, 24, 2, 4),
        ),
        "EU West" to listOf(
            intArrayOf(4, 20, 2, 4),
            intArrayOf(6, 20, 2, 4),
            intArrayOf(8, 20, 2, 4),
            intArrayOf(10, 20, 2, 4),
            intArrayOf(4, 24, 2, 4),
            intArrayOf(6, 24, 2, 4),
            intArrayOf(8, 24, 2, 4),
            intArrayOf(10, 24, 2, 4),
            intArrayOf(4, 28, 2, 4),
            intArrayOf(6, 28, 2, 4),
            intArrayOf(4, 32, 2, 4),
            intArrayOf(6, 32, 2, 2),
            intArrayOf(8, 32, 2, 2),
            intArrayOf(10, 32, 2, 2),
            intArrayOf(12, 32, 2, 2),
            intArrayOf(14, 32, 2, 4),
            intArrayOf(16, 32, 2, 4),
            intArrayOf(18, 32, 2, 2),
            intArrayOf(20, 32, 2, 4),
            intArrayOf(22, 32, 2, 4),
            intArrayOf(24, 32, 2, 4),
            intArrayOf(26, 32, 2, 4),
            intArrayOf(6, 34, 2, 2),
            intArrayOf(8, 34, 2, 2),
            intArrayOf(10, 34, 2, 2),
            intArrayOf(12, 34, 2, 2),
            intArrayOf(18, 34, 2, 2),
        ),
        "EU Central" to listOf(
            intArrayOf(12, 20, 2, 4),
            intArrayOf(14, 20, 2, 4),
            intArrayOf(16, 20, 2, 4),
            intArrayOf(18, 20, 2, 4),
            intArrayOf(20, 20, 2, 4),
            intArrayOf(22, 20, 2, 2),
            intArrayOf(26, 20, 2, 4),
            intArrayOf(28, 20, 2, 4),
            intArrayOf(30, 20, 2, 2),
            intArrayOf(22, 22, 2, 2),
            intArrayOf(24, 22, 1, 2),
            intArrayOf(25, 22, 1, 2),
            intArrayOf(12, 24, 2, 4),
            intArrayOf(14, 24, 2, 4),
            intArrayOf(16, 24, 2, 4),
            intArrayOf(18, 24, 2, 4),
            intArrayOf(20, 24, 2, 4),
            intArrayOf(22, 24, 2, 4),
            intArrayOf(24, 24, 2, 4),
            intArrayOf(28, 24, 2, 2),
            intArrayOf(30, 24, 2, 4),
            intArrayOf(26, 26, 2, 2),
            intArrayOf(0, 28, 4, 1),
            intArrayOf(8, 28, 2, 2),
            intArrayOf(10, 28, 2, 4),
            intArrayOf(12, 28, 2, 2),
            intArrayOf(14, 28, 2, 2),
            intArrayOf(16, 28, 2, 2),
            intArrayOf(18, 28, 2, 2),
            intArrayOf(20, 28, 2, 2),
            intArrayOf(22, 28, 2, 2),
            intArrayOf(24, 28, 2, 2),
            intArrayOf(26, 28, 2, 4),
            intArrayOf(28, 28, 2, 2),
            intArrayOf(30, 28, 2, 2),
            intArrayOf(32, 28, 2, 2),
            intArrayOf(0, 29, 4, 1),
            intArrayOf(0, 30, 2, 2),
            intArrayOf(2, 30, 2, 2),
            intArrayOf(16, 30, 2, 2),
            intArrayOf(18, 30, 2, 2),
            intArrayOf(22, 30, 2, 2),
            intArrayOf(24, 30, 2, 2),
            intArrayOf(32, 30, 2, 2),
            intArrayOf(34, 30, 2, 2),
            intArrayOf(0, 32, 2, 2),
            intArrayOf(28, 32, 2, 4),
            intArrayOf(32, 32, 2, 4),
            intArrayOf(0, 34, 4, 2),
            intArrayOf(30, 34, 2, 2),
            intArrayOf(34, 34, 2, 2),
            intArrayOf(26, 24, 2, 2),
        ),
        "Europe" to listOf(
            intArrayOf(24, 20, 2, 2),
            intArrayOf(30, 22, 2, 2),
            intArrayOf(28, 26, 2, 2),
            intArrayOf(34, 28, 2, 2),
            intArrayOf(8, 30, 2, 2),
            intArrayOf(12, 30, 2, 2),
            intArrayOf(14, 30, 2, 2),
            intArrayOf(20, 30, 2, 2),
            intArrayOf(28, 30, 2, 2),
            intArrayOf(30, 30, 2, 2),
            intArrayOf(2, 32, 2, 2),
            intArrayOf(30, 32, 2, 2),
            intArrayOf(34, 32, 2, 2),
        ),
    )

    @JvmField
    val shards: List<RegionShard> = buildList {
        for ((region, rectangles) in regions) {
            val ordered = rectangles.sortedWith(compareBy<IntArray> { it[1] }.thenBy { it[0] })
            ordered.forEachIndexed { index, rectangle ->
                val (column, row, width, height) = rectangle
                require(column >= 0 && row >= 0 && column + width <= GRID_SIZE && row + height <= GRID_SIZE) {
                    "Shard out of bounds in $region: $column,$row ${width}x$height"
                }
                add(RegionShard(region, index + 1, column, row, width, height))
            }
        }
    }

    @JvmField
    val cells: Array<Array<RegionShard?>> = Array(GRID_SIZE) { arrayOfNulls<RegionShard>(GRID_SIZE) }.also { cells ->
        val occupied = Array(GRID_SIZE) { BooleanArray(GRID_SIZE) }
        shards.forEach { shard ->
            for (row in shard.row until shard.row + shard.height) {
                for (column in shard.column until shard.column + shard.width) {
                    require(!occupied[row][column]) { "Overlapping shards at $column,$row" }
                    occupied[row][column] = true
                    cells[row][column] = shard
                }
            }
        }
    }

    /** Returns the local map-space pixel position of a world coordinate, clamped to the map. */
    @JvmStatic
    fun projectWorldPosition(worldX: Double, worldZ: Double, mapSize: Double): DoubleArray {
        require(mapSize.isFinite() && mapSize >= 0.0)
        val diameter = WORLD_RADIUS * 2.0
        val x = ((worldX + WORLD_RADIUS) / diameter).coerceIn(0.0, 1.0) * mapSize
        val z = ((worldZ + WORLD_RADIUS) / diameter).coerceIn(0.0, 1.0) * mapSize
        return doubleArrayOf(x, z)
    }
}
