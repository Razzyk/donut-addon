package dev.donut.addon.expansion.building

import java.util.ArrayDeque
import dev.donut.addon.expansion.automation.core.InventorySlotValue

internal data class GridCell(val x: Int, val y: Int, val z: Int)

internal enum class AreaSide { DOWN, UP, NORTH, SOUTH, WEST, EAST }

internal object AreaMiningRules {
    fun isMineable(isAir: Boolean, isFluidBlock: Boolean, destroySpeed: Float): Boolean =
        !isAir && !isFluidBlock && destroySpeed >= 0f

    fun holdsFluid(ownFluid: Boolean, neighbors: Map<AreaSide, Boolean>): Boolean =
        ownFluid || neighbors.any { (side, fluid) -> side != AreaSide.DOWN && fluid }

    fun isShardPickaxe(isPickaxe: Boolean, displayName: String): Boolean =
        isPickaxe && (displayName.contains("shard", ignoreCase = true) || displayName.contains("amethyst", ignoreCase = true))

    fun shardCutFits(cells: Collection<AreaShardCell>): Boolean =
        cells.all { it.inside } && cells.none { it.mineable && it.holdsFluid }
}

internal object AreaTransitionRules {
    const val MAX_RISE = 1
    const val MAX_DROP = 3

    fun allows(from: GridCell, to: GridCell): Boolean {
        val horizontal = kotlin.math.abs(from.x.toLong() - to.x) + kotlin.math.abs(from.z.toLong() - to.z)
        val rise = to.y.toLong() - from.y
        return horizontal == 1L && rise <= MAX_RISE && rise >= -MAX_DROP
    }

    fun allowsSurfaceChange(fromY: Double, toY: Double): Boolean =
        toY - fromY <= 1.2 && fromY - toY <= 3.0

    fun allowsSafeTransition(
        fromY: Double,
        toY: Double,
        intoDeepWater: Boolean,
        nearLavaOrFire: Boolean,
        hasSafeExit: Boolean,
    ): Boolean {
        val drop = fromY - toY
        return allowsSurfaceChange(fromY, toY) &&
            !(drop > 0.6 && intoDeepWater) &&
            !nearLavaOrFire &&
            !(drop > 0.01 && !hasSafeExit)
    }
}

internal data class AreaShardCell(val inside: Boolean, val mineable: Boolean, val holdsFluid: Boolean)

internal data class AreaToolChoice(val slot: Int, val speed: Float, val suitable: Boolean)

internal object AreaToolSelection {
    /** Speed wins; drop suitability only breaks equal-speed ties, matching the source loop. */
    fun best(candidates: Iterable<AreaToolChoice>): AreaToolChoice? {
        var best: AreaToolChoice? = null
        for (candidate in candidates) {
            if (candidate.speed < 1f) continue
            val current = best
            if (current == null || candidate.speed > current.speed ||
                candidate.speed == current.speed && candidate.suitable && !current.suitable) {
                best = candidate
            }
        }
        return best
    }
}

internal object AreaProgressMath {
    fun actualAirChanges(before: Set<GridCell>, nowAir: Set<GridCell>): Int = before.count { it in nowAir }
}

internal enum class AreaFoodProgress { RUNNING, ATE, TIMED_OUT }

internal object AreaFoodRules {
    const val EAT_TICK_LIMIT = 70
    private val BLACKLIST = setOf(
        "chorus_fruit", "rotten_flesh", "spider_eye", "poisonous_potato", "pufferfish",
        "suspicious_stew", "enchanted_golden_apple", "golden_apple", "chicken",
    )

    fun hungry(foodLevel: Int, health: Float, maxHealth: Float): Boolean =
        foodLevel <= 14 || health < maxHealth && foodLevel < 18

    fun edible(nutrition: Int, itemId: String): Boolean =
        nutrition > 0 && itemId.substringAfter(':') !in BLACKLIST

    fun progress(startFood: Int, currentFood: Int, ticks: Int): AreaFoodProgress = when {
        currentFood > startFood -> AreaFoodProgress.ATE
        ticks > EAT_TICK_LIMIT -> AreaFoodProgress.TIMED_OUT
        else -> AreaFoodProgress.RUNNING
    }
}

internal object AreaInventoryCalmRules {
    fun still(movingInput: Boolean, onGround: Boolean, horizontalSpeedSquared: Double): Boolean =
        !movingInput && onGround && horizontalSpeedSquared < 0.0025
}

/** A temporary SWAP may be reversed only while the exact session and both post-swap stacks still match. */
internal data class AreaInventoryLease(
    val worldIdentity: Any,
    val playerIdentity: Any,
    val connectionIdentity: Any,
    val menuIdentity: Any,
    val connectionId: Long,
    val menuId: Int,
    val sourceMenuSlot: Int,
    val destinationMenuSlot: Int,
    val selectedSlot: Int,
    val originalSource: InventorySlotValue,
    val originalDestination: InventorySlotValue,
) {
    fun withReducedSourceStack(
        sourceNow: InventorySlotValue?,
        destinationNow: InventorySlotValue?,
    ): AreaInventoryLease? {
        val current = destinationNow ?: return null
        if (sourceNow != originalDestination || current.count !in 0 until originalSource.count) return null
        val isEmpty = current.count == 0 && current.itemId.isEmpty()
        val sameComponents = originalSource.itemId == current.itemId &&
            originalSource.name == current.name && originalSource.lore == current.lore &&
            originalSource.componentFingerprint == current.componentFingerprint &&
            originalSource.actualEnchantments == current.actualEnchantments
        return if (isEmpty || sameComponents) copy(originalSource = current) else null
    }

    fun mayRestore(
        world: Any?,
        player: Any?,
        connection: Any?,
        menu: Any?,
        currentConnectionId: Long,
        currentMenuId: Int,
        currentSelectedSlot: Int,
        sourceNow: InventorySlotValue?,
        destinationNow: InventorySlotValue?,
        cursorEmpty: Boolean,
    ): Boolean = selectedSlot == currentSelectedSlot && cursorEmpty && stacksMatch(
        world, player, connection, menu, currentConnectionId, currentMenuId, sourceNow, destinationNow,
    )

    fun stacksMatch(
        world: Any?,
        player: Any?,
        connection: Any?,
        menu: Any?,
        currentConnectionId: Long,
        currentMenuId: Int,
        sourceNow: InventorySlotValue?,
        destinationNow: InventorySlotValue?,
    ): Boolean = worldIdentity === world && playerIdentity === player && connectionIdentity === connection &&
        menuIdentity === menu && connectionId == currentConnectionId && menuId == currentMenuId &&
        sourceNow == originalDestination && destinationNow == originalSource
}

internal sealed class AreaLeaseRefresh {
    data class Ready(val lease: AreaInventoryLease) : AreaLeaseRefresh()
    object Waiting : AreaLeaseRefresh()
    object Invalid : AreaLeaseRefresh()
}

/** Distinguishes an acknowledged consumable decrement from local prediction or an external edit. */
internal object AreaInventoryLeaseRefresh {
    fun evaluate(
        lease: AreaInventoryLease,
        destinationVersion: Long,
        destinationVersionBeforeUse: Long,
        sourceServer: InventorySlotValue?,
        destinationServer: InventorySlotValue?,
        sourceLocal: InventorySlotValue?,
        destinationLocal: InventorySlotValue?,
    ): AreaLeaseRefresh {
        if (destinationVersion <= destinationVersionBeforeUse) {
            return if (sourceLocal == lease.originalDestination && destinationLocal == lease.originalSource) {
                AreaLeaseRefresh.Ready(lease)
            } else AreaLeaseRefresh.Waiting
        }

        val serverLease = when {
            sourceServer == lease.originalDestination && destinationServer == lease.originalSource -> lease
            else -> lease.withReducedSourceStack(sourceServer, destinationServer) ?: return AreaLeaseRefresh.Invalid
        }
        if (sourceLocal != sourceServer || destinationLocal != destinationServer) return AreaLeaseRefresh.Waiting
        return AreaLeaseRefresh.Ready(serverLease)
    }
}

/** Mirrors MouseHandler's native-window guard while retaining the vanilla synthetic window id 0. */
internal object AreaMinerWindowAcceptance {
    fun accepts(eventWindow: Long, currentWindow: Long): Boolean =
        eventWindow == 0L || eventWindow == currentWindow
}

/** Inclusive, normalized area bounds with overflow-safe validation. */
internal data class AreaBounds private constructor(
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
) {
    val width: Long get() = maxX.toLong() - minX + 1L
    val height: Long get() = maxY.toLong() - minY + 1L
    val depth: Long get() = maxZ.toLong() - minZ + 1L
    val volume: Long get() = try {
        Math.multiplyExact(Math.multiplyExact(width, height), depth)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
    val isValid: Boolean get() = volume in 1L..MAX_VOLUME

    fun contains(cell: GridCell): Boolean =
        cell.x in minX..maxX && cell.y in minY..maxY && cell.z in minZ..maxZ

    fun layer(y: Int): AreaLayerCursor? = if (y in minY..maxY) AreaLayerCursor(this, y) else null

    companion object {
        const val MAX_VOLUME = 2_000_000L

        @JvmStatic
        fun from(first: GridCell, second: GridCell): AreaBounds = AreaBounds(
            minOf(first.x, second.x), minOf(first.y, second.y), minOf(first.z, second.z),
            maxOf(first.x, second.x), maxOf(first.y, second.y), maxOf(first.z, second.z),
        )

        @JvmStatic
        fun parse(text: String): GridCell? {
            val parts = text.trim().replace(',', ' ').split(Regex("\\s+"))
            if (parts.size != 3) return null
            val values = parts.map { it.toIntOrNull() ?: return null }
            return GridCell(values[0], values[1], values[2])
        }
    }
}

internal data class LayerScanBatch(
    val cells: List<GridCell>,
    val checked: Int,
    val done: Boolean,
)

/** Incremental X-then-Z scan for one Y layer; each call has a hard cell budget. */
internal class AreaLayerCursor(private val bounds: AreaBounds, val y: Int) {
    private val total = bounds.width * bounds.depth
    var checked: Long = 0L
        private set

    val done: Boolean get() = checked >= total

    fun scan(maxChecks: Int, maxCandidates: Int, predicate: (GridCell) -> Boolean): LayerScanBatch {
        require(maxChecks > 0)
        require(maxCandidates > 0)
        val cells = ArrayList<GridCell>(minOf(maxCandidates, maxChecks))
        var used = 0
        while (!done && used < maxChecks && cells.size < maxCandidates) {
            val xOffset = checked / bounds.depth
            val zOffset = checked % bounds.depth
            val cell = GridCell(
                x = (bounds.minX.toLong() + xOffset).toInt(),
                y = y,
                z = (bounds.minZ.toLong() + zOffset).toInt(),
            )
            checked++
            used++
            if (predicate(cell)) cells.add(cell)
        }
        return LayerScanBatch(cells, used, done)
    }
}

internal data class PathSearchResult(
    val route: List<GridCell>?,
    val visited: Int,
    val exhaustedBudget: Boolean,
)

/** Bounded horizontal BFS with one-block step-up and step-down edges. The route excludes its start. */
internal object AreaStandPlanner {
    private val HORIZONTAL = listOf(
        GridCell(-1, 0, 0), GridCell(1, 0, 0), GridCell(0, 0, -1), GridCell(0, 0, 1),
    )
    private val STEP_HEIGHTS = intArrayOf(1, 0, -1, -2, -3)

    fun findRoute(
        start: GridCell,
        goals: Set<GridCell>,
        maxVisited: Int,
        canStand: (GridCell) -> Boolean,
        canTransition: (GridCell, GridCell) -> Boolean = { _, _ -> true },
    ): PathSearchResult {
        require(maxVisited > 0)
        if (start in goals) return PathSearchResult(emptyList(), 1, false)
        if (goals.isEmpty()) return PathSearchResult(null, 0, false)

        val queue = ArrayDeque<GridCell>()
        val parent = HashMap<GridCell, GridCell?>(minOf(maxVisited, 1024))
        queue.addLast(start)
        parent[start] = null
        var found: GridCell? = null

        while (queue.isNotEmpty() && parent.size < maxVisited) {
            val current = queue.removeFirst()
            for (horizontal in HORIZONTAL) {
                for (rise in STEP_HEIGHTS) {
                    val x = current.x.toLong() + horizontal.x
                    val y = current.y.toLong() + rise
                    val z = current.z.toLong() + horizontal.z
                    if (x !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
                        y !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
                        z !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) continue
                    val next = GridCell(x.toInt(), y.toInt(), z.toInt())
                    if (next in parent || !AreaTransitionRules.allows(current, next) ||
                        !canStand(next) || !canTransition(current, next)) continue
                    parent[next] = current
                    if (next in goals) {
                        found = next
                        break
                    }
                    queue.addLast(next)
                    if (parent.size >= maxVisited) break
                }
                if (found != null || parent.size >= maxVisited) break
            }
            if (found != null) break
        }

        if (found == null) return PathSearchResult(null, parent.size, queue.isNotEmpty() && parent.size >= maxVisited)
        val reversed = ArrayList<GridCell>()
        var cursor: GridCell? = found
        while (cursor != null && cursor != start) {
            reversed.add(cursor)
            cursor = parent[cursor]
        }
        reversed.reverse()
        return PathSearchResult(reversed, parent.size, false)
    }
}

/** Per-target failures are isolated; a target is skipped only after repeated real attempts. */
internal class AreaRetryLedger(private val maxFailures: Int = 3) {
    private val failures = HashMap<GridCell, Int>()
    private val skipped = HashSet<GridCell>()

    init { require(maxFailures > 0) }

    fun recordFailure(cell: GridCell): Int {
        val count = failures.merge(cell, 1, Int::plus) ?: 1
        if (count >= maxFailures) skipped.add(cell)
        return count
    }

    fun isSkipped(cell: GridCell): Boolean = cell in skipped
    fun failures(cell: GridCell): Int = failures[cell] ?: 0
    fun skippedCount(): Int = skipped.size
    fun clear() { failures.clear(); skipped.clear() }
}

internal data class AreaProgress(val planned: Int, val remaining: Int, val skipped: Int, val protected: Int) {
    val mined: Int get() = (planned - remaining).coerceIn(0, planned.coerceAtLeast(0))
    val finished: Boolean get() = remaining <= 0
}

/** Pure click-selection state. A world key ties both captured corners to one dimension/session. */
internal class AreaCornerSelection {
    enum class Result { IGNORED, CONSUMED_NO_TARGET, FIRST_SET, COMPLETE, INVALID_SIZE, WORLD_CHANGED }

    private var worldKey: Any? = null
    private var first: GridCell? = null
    var armed: Boolean = false
        private set

    fun arm(world: Any?): Boolean {
        if (world == null) { clear(); return false }
        worldKey = world
        first = null
        armed = true
        return true
    }

    fun click(
        button: Int,
        action: Int,
        hasScreen: Boolean,
        currentWorld: Any?,
        position: GridCell?,
    ): Result {
        if (!armed || button != LEFT_BUTTON || action != PRESS || hasScreen) return Result.IGNORED
        if (worldKey == null || currentWorld == null) { clear(); return Result.IGNORED }
        if (currentWorld !== worldKey) { clear(); return Result.WORLD_CHANGED }
        if (position == null) return Result.CONSUMED_NO_TARGET
        val firstCorner = first
        if (firstCorner == null) {
            first = position
            return Result.FIRST_SET
        }
        if (!AreaBounds.from(firstCorner, position).isValid) return Result.INVALID_SIZE
        clear()
        return Result.COMPLETE
    }

    fun clear() {
        armed = false
        first = null
        worldKey = null
    }

    companion object {
        const val LEFT_BUTTON = 0
        const val PRESS = 1
    }
}
