package dev.donut.addon.expansion.building

import dev.donut.addon.expansion.automation.core.InventorySlotValue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AreaMinerPlanTest {
    @Test
    fun `parses and normalizes inclusive corners with a configured volume ceiling`() {
        assertEquals(GridCell(-2, 4, 9), AreaBounds.parse(" -2  4 9 "))
        assertEquals(GridCell(-2, 4, 9), AreaBounds.parse("-2,4,9"))
        assertEquals(GridCell(-2, 4, 9), AreaBounds.parse(" -2, 4  9 "))
        assertNull(AreaBounds.parse("1 2"))
        assertNull(AreaBounds.parse("1 two 3"))

        val reversed = AreaBounds.from(GridCell(3, 5, 8), GridCell(-1, 2, 4))
        assertEquals(5L * 4L * 5L, reversed.volume)
        assertTrue(reversed.contains(GridCell(0, 3, 5)))
        assertFalse(reversed.contains(GridCell(4, 3, 5)))

        assertTrue(AreaBounds.from(GridCell(0, 0, 0), GridCell(1_999_999, 0, 0)).isValid)
        assertFalse(AreaBounds.from(GridCell(0, 0, 0), GridCell(2_000_000, 0, 0)).isValid)
        assertFalse(AreaBounds.from(GridCell(Int.MIN_VALUE, 0, 0), GridCell(Int.MAX_VALUE, 0, 0)).isValid)
    }

    @Test
    fun `layer cursor scans top layer in stable order and makes bounded progress`() {
        val bounds = AreaBounds.from(GridCell(2, 0, 5), GridCell(4, 2, 6))
        val cursor = bounds.layer(2)!!
        val all = mutableListOf<GridCell>()
        var totalChecks = 0
        var calls = 0
        while (!cursor.done) {
            val batch = cursor.scan(2, 10) { true }
            assertTrue(batch.checked <= 2)
            assertTrue(batch.cells.size <= 10)
            totalChecks += batch.checked
            calls++
            all.addAll(batch.cells)
        }
        assertEquals(6, totalChecks)
        assertEquals(3, calls)
        assertEquals(listOf(
            GridCell(2, 2, 5), GridCell(2, 2, 6), GridCell(3, 2, 5),
            GridCell(3, 2, 6), GridCell(4, 2, 5), GridCell(4, 2, 6),
        ), all)
    }

    @Test
    fun `path search finds only safe bounded routes and reports budget exhaustion`() {
        val start = GridCell(0, 0, 0)
        val goal = GridCell(3, 0, 0)
        val path = AreaStandPlanner.findRoute(start, setOf(goal), 100, canStand = { cell ->
            cell.y == 0 && cell.x in 0..3 && cell.z == 0
        })
        assertEquals(listOf(GridCell(1, 0, 0), GridCell(2, 0, 0), goal), path.route)
        assertFalse(path.exhaustedBudget)

        val blocked = AreaStandPlanner.findRoute(start, setOf(goal), 100, canStand = { cell ->
            cell.y == 0 && cell.x in 0..3 && cell.z == 0 && cell.x != 2
        })
        assertNull(blocked.route)

        val bounded = AreaStandPlanner.findRoute(start, setOf(GridCell(20, 0, 0)), 3, canStand = { cell ->
            cell.y == 0 && cell.x in 0..20 && cell.z == 0
        })
        assertTrue(bounded.exhaustedBudget)
        assertTrue(bounded.visited <= 3)
    }

    @Test
    fun `standing planner traverses one-block stairs and ledges but respects transition clearance`() {
        val stairStart = GridCell(0, 0, 0)
        val stairTop = GridCell(1, 1, 0)
        val stair = AreaStandPlanner.findRoute(stairStart, setOf(stairTop), 16,
            canStand = { it == stairTop },
            canTransition = { from, to -> from == stairStart && to == stairTop },
        )
        assertEquals(listOf(stairTop), stair.route)

        val ledgeStart = GridCell(0, 1, 0)
        val ledgeBottom = GridCell(1, 0, 0)
        val ledge = AreaStandPlanner.findRoute(ledgeStart, setOf(ledgeBottom), 16,
            canStand = { it == ledgeBottom },
            canTransition = { from, to -> from == ledgeStart && to == ledgeBottom },
        )
        assertEquals(listOf(ledgeBottom), ledge.route)

        val lowCeiling = AreaStandPlanner.findRoute(stairStart, setOf(stairTop), 16,
            canStand = { it == stairTop },
            canTransition = { _, _ -> false },
        )
        assertNull(lowCeiling.route)
    }

    @Test
    fun `horizontal transitions allow source bounded drops and reject larger or diagonal edges`() {
        assertTrue(AreaTransitionRules.allows(GridCell(0, 4, 0), GridCell(1, 5, 0)))
        assertTrue(AreaTransitionRules.allows(GridCell(0, 4, 0), GridCell(1, 1, 0)))
        assertFalse(AreaTransitionRules.allows(GridCell(0, 4, 0), GridCell(1, 0, 0)))
        assertFalse(AreaTransitionRules.allows(GridCell(0, 4, 0), GridCell(1, 6, 0)))
        assertFalse(AreaTransitionRules.allows(GridCell(0, 4, 0), GridCell(1, 4, 1)))

        val dropGoal = GridCell(1, 1, 0)
        val drop = AreaStandPlanner.findRoute(GridCell(0, 4, 0), setOf(dropGoal), 8,
            canStand = { it == dropGoal })
        assertEquals(listOf(dropGoal), drop.route)
    }

    @Test
    fun `route transitions use partial floor heights and reject unsafe pits`() {
        assertTrue(AreaTransitionRules.allowsSurfaceChange(0.5, 1.5))
        assertTrue(AreaTransitionRules.allowsSurfaceChange(1.0, -2.0))
        assertFalse(AreaTransitionRules.allowsSurfaceChange(0.5, 1.8))
        assertFalse(AreaTransitionRules.allowsSurfaceChange(4.0, 0.9))

        assertTrue(AreaTransitionRules.allowsSafeTransition(0.5, 1.5, false, false, true))
        assertTrue(AreaTransitionRules.allowsSafeTransition(4.0, 1.0, false, false, true))
        assertFalse(AreaTransitionRules.allowsSafeTransition(4.0, 1.0, false, false, false))
        assertFalse(AreaTransitionRules.allowsSafeTransition(2.0, 1.0, true, false, true))
        assertFalse(AreaTransitionRules.allowsSafeTransition(2.0, 1.0, false, true, true))
    }

    @Test
    fun `source fluid rules ignore down neighbor and constrain the whole shard cut`() {
        assertTrue(AreaMiningRules.isMineable(false, false, 0f))
        assertFalse(AreaMiningRules.isMineable(true, false, 2f))
        assertFalse(AreaMiningRules.isMineable(false, true, 2f))
        assertFalse(AreaMiningRules.isMineable(false, false, -1f))
        assertTrue(AreaMiningRules.isShardPickaxe(true, "Amethyst Miner"))
        assertTrue(AreaMiningRules.isShardPickaxe(true, "Shard Pickaxe"))
        assertFalse(AreaMiningRules.isShardPickaxe(false, "Amethyst Tool"))
        assertFalse(AreaMiningRules.holdsFluid(false, mapOf(AreaSide.DOWN to true)))
        assertTrue(AreaMiningRules.holdsFluid(false, mapOf(AreaSide.UP to true)))
        assertTrue(AreaMiningRules.holdsFluid(true, emptyMap()))

        val validFootprint = (0 until 27).map {
            AreaShardCell(inside = true, mineable = false, holdsFluid = true)
        }
        assertTrue(AreaMiningRules.shardCutFits(validFootprint))
        assertFalse(AreaMiningRules.shardCutFits(validFootprint + AreaShardCell(false, false, false)))
        assertFalse(AreaMiningRules.shardCutFits(validFootprint + AreaShardCell(true, true, true)))
    }

    @Test
    fun `tool selection prioritizes speed and uses suitability only for ties`() {
        val choice = AreaToolSelection.best(listOf(
            AreaToolChoice(0, 12f, false),
            AreaToolChoice(1, 11f, true),
        ))
        assertEquals(0, choice?.slot)

        val tied = AreaToolSelection.best(listOf(
            AreaToolChoice(0, 12f, false),
            AreaToolChoice(1, 12f, true),
        ))
        assertEquals(1, tied?.slot)
        assertEquals(0, AreaToolSelection.best(listOf(AreaToolChoice(0, 1f, true)))?.slot)
        assertNull(AreaToolSelection.best(listOf(AreaToolChoice(0, 0.99f, true))))
    }

    @Test
    fun `progress counts only captured footprint cells that are actually air`() {
        val before = setOf(GridCell(0, 0, 0), GridCell(1, 0, 0), GridCell(2, 0, 0))
        assertEquals(0, AreaProgressMath.actualAirChanges(before, emptySet()))
        assertEquals(2, AreaProgressMath.actualAirChanges(before, setOf(GridCell(0, 0, 0), GridCell(2, 0, 0))))
    }

    @Test
    fun `food helper follows source hunger thresholds blacklist and eat completion`() {
        assertTrue(AreaFoodRules.hungry(14, 20f, 20f))
        assertFalse(AreaFoodRules.hungry(15, 20f, 20f))
        assertTrue(AreaFoodRules.hungry(17, 19f, 20f))
        assertFalse(AreaFoodRules.hungry(18, 19f, 20f))
        assertTrue(AreaFoodRules.edible(4, "minecraft:bread"))
        assertFalse(AreaFoodRules.edible(0, "minecraft:bread"))
        listOf("chorus_fruit", "rotten_flesh", "spider_eye", "poisonous_potato", "pufferfish",
            "suspicious_stew", "enchanted_golden_apple", "golden_apple", "chicken")
            .forEach { assertFalse(AreaFoodRules.edible(8, "minecraft:$it"), it) }
        assertTrue(AreaFoodRules.edible(2, "minecraft:cooked_chicken"))
        assertEquals(AreaFoodProgress.RUNNING, AreaFoodRules.progress(10, 10, 70))
        assertEquals(AreaFoodProgress.ATE, AreaFoodRules.progress(10, 11, 1))
        assertEquals(AreaFoodProgress.TIMED_OUT, AreaFoodRules.progress(10, 10, 71))
    }

    @Test
    fun `inventory lease restores only exact owned stack pair in its original session`() {
        val world = Any()
        val player = Any()
        val connection = Any()
        val menu = Any()
        val originalSource = InventorySlotValue(
            "minecraft:diamond_pickaxe", 1, "Miner", listOf("Mending"),
            componentFingerprint = "damage=40;custom_name=Miner",
            actualEnchantments = mapOf("mending" to 1),
        )
        val originalDestination = InventorySlotValue("minecraft:bread", 12)
        val lease = AreaInventoryLease(
            world, player, connection, menu, 12L, 0, 9, 36, 2,
            originalSource, originalDestination,
        )

        assertTrue(lease.mayRestore(
            world, player, connection, menu, 12L, 0, 2,
            originalDestination, originalSource, cursorEmpty = true,
        ))
        assertFalse(lease.mayRestore(
            world, player, connection, menu, 12L, 0, 2,
            originalDestination.copy(count = 11), originalSource, cursorEmpty = true,
        ))
        assertFalse(lease.mayRestore(
            world, player, connection, menu, 12L, 0, 2,
            originalDestination, originalSource.copy(lore = listOf("Edited")), cursorEmpty = true,
        ))
        assertFalse(lease.mayRestore(
            world, player, connection, menu, 12L, 0, 2,
            originalDestination, originalSource.copy(componentFingerprint = "damage=2;custom_name=Miner"), cursorEmpty = true,
        ))
        assertFalse(lease.mayRestore(
            Any(), player, connection, menu, 12L, 0, 2,
            originalDestination, originalSource, cursorEmpty = true,
        ))
        assertFalse(lease.mayRestore(
            world, player, connection, menu, 13L, 0, 2,
            originalDestination, originalSource, cursorEmpty = true,
        ))
        assertFalse(lease.mayRestore(
            world, player, connection, menu, 12L, 0, 3,
            originalDestination, originalSource, cursorEmpty = true,
        ))
        assertFalse(lease.mayRestore(
            world, player, connection, Any(), 12L, 0, 2,
            originalDestination, originalSource, cursorEmpty = true,
        ))
        assertFalse(lease.mayRestore(
            world, player, connection, menu, 12L, 0, 2,
            originalDestination, originalSource, cursorEmpty = false,
        ))

        val foodStack = InventorySlotValue(
            "minecraft:bread", 12, componentFingerprint = "food=5;custom_name=travel",
        )
        val oldHotbar = InventorySlotValue("minecraft:diamond_pickaxe", 1, componentFingerprint = "damage=40")
        val foodLease = lease.copy(originalSource = foodStack, originalDestination = oldHotbar)
        val afterOneFood = foodStack.copy(count = 11)
        val updatedLease = foodLease.withReducedSourceStack(oldHotbar, afterOneFood)
        assertNotNull(updatedLease)
        assertTrue(updatedLease!!.mayRestore(
            world, player, connection, menu, 12L, 0, 2,
            oldHotbar, afterOneFood, cursorEmpty = true,
        ))
        assertNull(foodLease.withReducedSourceStack(oldHotbar, foodStack.copy(count = 13)))
        assertNull(foodLease.withReducedSourceStack(oldHotbar, foodStack.copy(count = 11, componentFingerprint = "edited")))
    }

    @Test
    fun `offhand inventory swap requires a quiet grounded player`() {
        assertTrue(AreaInventoryCalmRules.still(false, true, 0.0024))
        assertFalse(AreaInventoryCalmRules.still(true, true, 0.0))
        assertFalse(AreaInventoryCalmRules.still(false, false, 0.0))
        assertFalse(AreaInventoryCalmRules.still(false, true, 0.0025))
    }

    @Test
    fun `repeated failures skip only the target that actually failed`() {
        val ledger = AreaRetryLedger(3)
        val one = GridCell(1, 2, 3)
        val two = GridCell(2, 2, 3)
        assertEquals(1, ledger.recordFailure(one))
        assertEquals(2, ledger.recordFailure(one))
        assertFalse(ledger.isSkipped(one))
        assertEquals(1, ledger.recordFailure(two))
        assertEquals(3, ledger.recordFailure(one))
        assertTrue(ledger.isSkipped(one))
        assertFalse(ledger.isSkipped(two))
        ledger.clear()
        assertEquals(0, ledger.skippedCount())
    }

    @Test
    fun `corner selection consumes only left presses in the same active world`() {
        val world = Any()
        val selection = AreaCornerSelection()
        assertTrue(selection.arm(world))
        assertEquals(AreaCornerSelection.Result.IGNORED,
            selection.click(0, 1, true, world, GridCell(0, 0, 0)))
        assertTrue(selection.armed)
        assertEquals(AreaCornerSelection.Result.IGNORED,
            selection.click(1, 1, false, world, GridCell(0, 0, 0)))
        assertEquals(AreaCornerSelection.Result.IGNORED,
            selection.click(0, 0, false, world, GridCell(0, 0, 0)))
        assertEquals(AreaCornerSelection.Result.IGNORED,
            selection.click(0, 1, false, null, GridCell(0, 0, 0)))
        assertFalse(selection.armed)

        assertTrue(selection.arm(world))
        assertEquals(AreaCornerSelection.Result.CONSUMED_NO_TARGET,
            selection.click(0, 1, false, world, null))
        assertEquals(AreaCornerSelection.Result.FIRST_SET,
            selection.click(0, 1, false, world, GridCell(0, 0, 0)))
        assertEquals(AreaCornerSelection.Result.INVALID_SIZE,
            selection.click(0, 1, false, world, GridCell(2_000_000, 0, 0)))
        assertTrue(selection.armed)
        assertEquals(AreaCornerSelection.Result.WORLD_CHANGED,
            selection.click(0, 1, false, Any(), GridCell(0, 0, 0)))
        assertFalse(selection.armed)
    }

    @Test
    fun `window acceptance follows the vanilla zero-or-current-handle rule`() {
        assertTrue(AreaMinerWindowAcceptance.accepts(0L, 42L))
        assertTrue(AreaMinerWindowAcceptance.accepts(42L, 42L))
        assertFalse(AreaMinerWindowAcceptance.accepts(7L, 42L))
        assertFalse(AreaMinerWindowAcceptance.accepts(7L, 0L))
    }

    @Test
    fun `progress stays finite and clamped for inconsistent counts`() {
        assertEquals(12, AreaProgress(20, 8, 3, 2).mined)
        assertFalse(AreaProgress(20, 8, 3, 2).finished)
        assertEquals(20, AreaProgress(20, -4, 0, 0).mined)
        assertTrue(AreaProgress(20, 0, 0, 0).finished)
    }
}
