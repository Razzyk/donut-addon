package dev.donut.addon.expansion.schematic

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.Items
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.data.registries.VanillaRegistries
import net.minecraft.core.Direction
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.DoorHingeSide
import net.minecraft.world.level.block.state.properties.SlabType
import dev.donut.addon.expansion.automation.core.InventorySlotValue
import dev.donut.addon.expansion.automation.core.InventoryAckStatus
import dev.donut.addon.expansion.automation.core.InventoryAckTracker
import dev.donut.addon.expansion.automation.core.InventorySyncFrame
import dev.donut.addon.expansion.automation.core.DonutSellIdentity

class SchematicPlanningTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapNativeItems() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup())
                .forEach { it.apply() }
        }
    }

    private fun cell(x: Int, y: Int, z: Int, role: BuildRole = BuildRole.STRUCTURE, deps: Set<BuildCellPos> = emptySet()) =
        BuildCell(BuildCellPos(x, y, z), "minecraft:stone", "minecraft:stone", role, deps)

    @Test
    fun `all placement boxes are unioned with overlaps emitted once and negative coordinates retained`() {
        val cursor = SchematicScanCursor(listOf(
            SchematicRegion("first", BuildCellPos(-17, 3, -1), BuildCellPos(-16, 3, 0)),
            SchematicRegion("second", BuildCellPos(-16, 3, 0), BuildCellPos(-15, 3, 1)),
        ))
        val found = buildList {
            while (true) {
                val page = cursor.next(2)
                addAll(page.positions)
                if (page.complete) break
            }
        }
        assertEquals(7, found.size)
        assertEquals(found.size, found.toSet().size)
        assertTrue(found.contains(BuildCellPos(-17, 3, -1)))
        assertTrue(found.contains(BuildCellPos(-15, 3, 1)))
        assertEquals(-2 to -1, BuildCellPos(-17, 0, -1).chunkCoordinates())
    }

    @Test
    fun `scan cursor enforces cap and reports incomplete bounded input`() {
        val cursor = SchematicScanCursor(listOf(SchematicRegion("large", BuildCellPos(-40, 0, 0), BuildCellPos(40, 2, 40))), 25)
        val first = cursor.next(100)
        assertEquals(25, first.positions.size)
        assertTrue(first.complete)
        assertTrue(first.truncated)
        assertEquals(25, cursor.acceptedCount)
        cursor.reset()
        assertEquals(first.positions, cursor.next(100).positions)
    }

    @Test
    fun `continued scan crosses bounded windows without dropping or duplicating a large hollow volume`() {
        val cursor = SchematicScanCursor(
            listOf(SchematicRegion("hollow", BuildCellPos(-8, 0, -8), BuildCellPos(8, 1, 8))),
            maxUniqueCells = 17,
            continueWhenLimitReached = true,
        )
        val found = buildList {
            while (true) {
                val page = cursor.next(11)
                addAll(page.positions)
                assertFalse(page.truncated)
                if (page.complete) break
            }
        }
        assertEquals(578, found.size)
        assertEquals(found.size, found.toSet().size)
        assertTrue(cursor.isComplete)
        assertFalse(cursor.wasTruncated)
        assertEquals(578, cursor.acceptedCount)
    }

    @Test
    fun `layer scan streams sorted nonempty chunk windows and unions overlapping boxes`() {
        val regions = listOf(
            SchematicRegion("west", BuildCellPos(-17, 64, -1), BuildCellPos(1, 65, 1)),
            SchematicRegion("overlap", BuildCellPos(-16, 64, 0), BuildCellPos(17, 64, 16)),
            SchematicRegion("distant", BuildCellPos(1_600, 64, 1_600), BuildCellPos(1_600, 64, 1_600)),
        )
        val cursor = SchematicLayerScanCursor(regions)
        val windows = buildList {
            while (true) add(cursor.nextWindow() ?: break)
        }
        val actual = windows.flatMap(SchematicLayerWindow::positions)
        val expected = regions.flatMap { region ->
            val box = region.normalized()
            buildList {
                for (y in box.min.y..box.max.y) for (x in box.min.x..box.max.x) for (z in box.min.z..box.max.z) {
                    add(BuildCellPos(x, y, z))
                }
            }
        }.toSet()
        val keys = windows.map { Triple(it.y, it.chunkX, it.chunkZ) }

        assertEquals(expected, actual.toSet())
        assertEquals(actual.size, actual.toSet().size)
        assertTrue(windows.all { it.positions.isNotEmpty() && it.positions.size <= 512 })
        assertEquals(keys.sortedWith(compareBy({ it.first }, { it.second }, { it.third })), keys)
        assertTrue(cursor.isComplete)
        assertEquals(actual.size.toLong(), cursor.cellsEmitted)

        cursor.reset()
        assertEquals(windows, buildList {
            while (true) add(cursor.nextWindow() ?: break)
        })
    }

    @Test
    fun `layer scan skips empty coordinate gaps between distant placements`() {
        val cursor = SchematicLayerScanCursor(listOf(
            SchematicRegion("negative", BuildCellPos(-160, 12, -160), BuildCellPos(-160, 12, -160)),
            SchematicRegion("positive", BuildCellPos(160_000, 12, 160_000), BuildCellPos(160_000, 12, 160_000)),
        ))

        val first = cursor.nextWindow()
        val second = cursor.nextWindow()

        assertEquals(listOf(BuildCellPos(-160, 12, -160)), first?.positions)
        assertEquals(listOf(BuildCellPos(160_000, 12, 160_000)), second?.positions)
        assertNull(cursor.nextWindow())
        assertTrue(cursor.isComplete)
        assertEquals(2L, cursor.windowsEmitted)
        assertEquals(2L, cursor.cellsEmitted)
    }

    @Test
    fun `layer scan jumps empty vertical gaps instead of walking every missing layer`() {
        val cursor = SchematicLayerScanCursor(listOf(
            SchematicRegion("low", BuildCellPos(0, 0, 0), BuildCellPos(0, 0, 0)),
            SchematicRegion("high", BuildCellPos(32, 1_000_000, 32), BuildCellPos(32, 1_000_000, 32)),
        ))

        assertEquals(listOf(BuildCellPos(0, 0, 0)), cursor.nextWindow()?.positions)
        assertEquals(listOf(BuildCellPos(32, 1_000_000, 32)), cursor.nextWindow()?.positions)
        assertNull(cursor.nextWindow())
        assertTrue(cursor.isComplete)
    }

    @Test
    fun `layer scan applies a global unique cell limit without returning an oversized window`() {
        val cursor = SchematicLayerScanCursor(listOf(
            SchematicRegion("large", BuildCellPos(0, 0, 0), BuildCellPos(31, 1, 31)),
        ))

        val first = cursor.nextWindow(maxCells = 17)
        assertNotNull(first)
        assertEquals(17, first!!.positions.size)
        assertEquals(17L, cursor.cellsEmitted)
        assertTrue(cursor.cellLimitReached)
        assertTrue(cursor.isComplete)
        assertNull(cursor.nextWindow(maxCells = 17))

        cursor.reset()
        assertFalse(cursor.cellLimitReached)
        assertEquals(17, cursor.nextWindow(maxCells = 17)?.positions?.size)
    }

    @Test
    fun `layer scan streams more than four hundred thousand cells through bounded windows`() {
        val cursor = SchematicLayerScanCursor(listOf(
            SchematicRegion("large", BuildCellPos(0, 0, 0), BuildCellPos(255, 6, 255)),
        ))
        var count = 0L
        var maxWindow = 0
        while (true) {
            val window = cursor.nextWindow() ?: break
            count += window.positions.size
            maxWindow = maxOf(maxWindow, window.positions.size)
        }

        assertEquals(458_752L, count)
        assertEquals(count, cursor.cellsEmitted)
        assertTrue(maxWindow <= 512)
        assertTrue(cursor.isComplete)
    }

    @Test
    fun `stand route respects bounded nodes, one block steps and explicit transitions`() {
        val route = SchematicStandPlanner.findRoute(
            BuildCellPos(0, 0, 0),
            isGoal = { it == BuildCellPos(2, 1, 0) },
            canStand = { it == BuildCellPos(1, 1, 0) || it == BuildCellPos(2, 1, 0) },
            canTransition = { from, to -> SchematicRouteRules.allows(from, to) },
            visitBudget = 20,
        )
        assertEquals(listOf(BuildCellPos(1, 1, 0), BuildCellPos(2, 1, 0)), route.route)
        val blocked = SchematicStandPlanner.findRoute(
            BuildCellPos(0, 0, 0), { it == BuildCellPos(1, 1, 0) }, { it == BuildCellPos(1, 1, 0) },
            canTransition = { _, _ -> false }, visitBudget = 10,
        )
        assertNull(blocked.route)
        val bounded = SchematicStandPlanner.findRoute(
            BuildCellPos(0, 0, 0), { it.x == 20 }, { true }, visitBudget = 2,
        )
        assertTrue(bounded.exhaustedBudget)
    }

    @Test
    fun `transformed absolute world position is queried and unloaded schematic data remains pending`() {
        val intake = SchematicIntake()
        val worldPosition = BuildCellPos(-33, 71, 18)
        var queried: BuildCellPos? = null
        var chunk: Pair<Int, Int>? = null
        val unavailable = readProjectedCell(
            worldPosition,
            isChunkLoaded = { cx, cz -> chunk = cx to cz; false },
            stateAtWorldPosition = { queried = it; "minecraft:air" },
            isAir = { it == "minecraft:air" },
            toBuildCell = { pos, state -> cell(pos.x, pos.y, pos.z).copy(stateId = state) },
        )
        intake.observe(worldPosition, unavailable)
        assertEquals(-3 to 1, chunk)
        assertNull(queried)
        assertEquals(setOf(worldPosition), intake.snapshot().pending)
        assertTrue(intake.snapshot().cells.isEmpty())

        val loaded = readProjectedCell(
            worldPosition,
            isChunkLoaded = { _, _ -> true },
            stateAtWorldPosition = { queried = it; "minecraft:glass" },
            isAir = { it == "minecraft:air" },
            toBuildCell = { pos, state -> cell(pos.x, pos.y, pos.z).copy(stateId = state) },
        )
        intake.observe(worldPosition, loaded)
        assertEquals(worldPosition, queried)
        assertTrue(intake.snapshot().pending.isEmpty())
        assertEquals("minecraft:glass", intake.snapshot().cells.getValue(worldPosition).stateId)
    }

    @Test
    fun `multi-placement cell intake replaces stale expected data only after successful read`() {
        val intake = SchematicIntake()
        val pos = BuildCellPos(5, 6, 7)
        intake.observe(pos, SchematicCellRead.Block(cell(5, 6, 7).copy(stateId = "minecraft:oak_planks")))
        intake.observe(pos, SchematicCellRead.Unavailable)
        assertEquals("minecraft:oak_planks", intake.snapshot().cells.getValue(pos).stateId)
        assertTrue(pos in intake.snapshot().pending)
        intake.observe(pos, SchematicCellRead.Block(cell(5, 6, 7).copy(stateId = "minecraft:glass")))
        assertEquals("minecraft:glass", intake.snapshot().cells.getValue(pos).stateId)
        assertFalse(pos in intake.snapshot().pending)
    }

    @Test
    fun `planner orders unmet dependencies before attachment and holds dependency cycles`() {
        val support = cell(0, 0, 0)
        val attached = cell(0, 1, 0, BuildRole.ATTACHMENT, setOf(support.pos))
        val plan = SchematicBuildPlanner.plan(listOf(attached, support), emptySet())
        assertEquals(listOf(support, attached), plan.ordered)
        val cycle = SchematicBuildPlanner.plan(listOf(
            cell(2, 0, 0, deps = setOf(BuildCellPos(2, 1, 0))),
            cell(2, 1, 0, deps = setOf(BuildCellPos(2, 0, 0))),
        ), emptySet())
        assertTrue(cycle.ordered.isEmpty())
        assertEquals(2, cycle.waitingOnDependencies.size)
    }

    @Test
    fun `unknown and skipped supports do not unlock a target but validated world support does`() {
        val target = cell(0, 2, 0, BuildRole.ATTACHMENT, setOf(BuildCellPos(0, 1, 0)))
        val absent = SchematicBuildPlanner.plan(listOf(target), emptySet())
        assertTrue(absent.ordered.isEmpty())
        assertEquals(setOf(target.pos), absent.waitingOnDependencies)
        val skipped = SchematicBuildPlanner.plan(listOf(target), emptySet(), skipped = setOf(BuildCellPos(0, 1, 0)))
        assertTrue(skipped.ordered.isEmpty())
        assertEquals(setOf(target.pos), skipped.waitingOnDependencies)
        val worldSupport = SchematicBuildPlanner.plan(listOf(target), emptySet(), satisfiedDependencies = setOf(BuildCellPos(0, 1, 0)))
        assertEquals(listOf(target), worldSupport.ordered)
    }

    @Test
    fun `completed unsupported and TNT cells do not keep a finished plan incomplete`() {
        val broken = BuildCell(BuildCellPos(0, 0, 0), "mod:unknown", null, BuildRole.UNSUPPORTED)
        val tnt = cell(1, 0, 0, BuildRole.TNT)
        val plan = SchematicBuildPlanner.plan(listOf(broken, tnt), completed = setOf(broken.pos, tnt.pos))
        assertTrue(plan.complete)
        assertTrue(plan.unsupported.isEmpty())
        assertTrue(plan.excludedTnt.isEmpty())
    }

    @Test
    fun `source structure rules order upper halves and head blocks and count double slabs`() {
        val pos = BuildCellPos(-2, 9, 4)
        assertEquals(BuildCellPos(-2, 8, 4), SchematicStructureRules.requiredPredecessor(pos, "minecraft:oak_door", mapOf("half" to "upper")))
        assertEquals(BuildCellPos(-2, 9, 5), SchematicStructureRules.requiredPredecessor(pos, "minecraft:red_bed", mapOf("part" to "head", "facing" to "north")))
        assertEquals(BuildCellPos(-2, 8, 4), SchematicStructureRules.requiredPredecessor(pos, "minecraft:sunflower", mapOf("half" to "upper")))
        assertTrue(SchematicStructureRules.requiresTwoSlabItems("minecraft:stone_slab", "minecraft:stone_slab", mapOf("type" to "double")))
        assertEquals(2, SchematicStructureRules.materialCost("minecraft:stone_slab", "minecraft:stone_slab", mapOf("type" to "double")))
        assertEquals(0, SchematicStructureRules.materialCost("minecraft:oak_door", "minecraft:oak_door", mapOf("half" to "upper")))
        assertTrue(SchematicStructureRules.isDoubleSlabPending("minecraft:stone_slab", mapOf("type" to "double"), "minecraft:stone_slab", mapOf("type" to "top")))
        assertTrue(SchematicStructureRules.allowPartialDoubleSlab(
            "minecraft:stone_slab", mapOf("type" to "double"), "minecraft:stone_slab", emptyMap(), actualReplaceable = true,
        ))
        assertTrue(SchematicStructureRules.allowPartialDoubleSlab(
            "minecraft:stone_slab", mapOf("type" to "double"), "minecraft:stone_slab", mapOf("type" to "bottom"), actualReplaceable = false,
        ))
        assertFalse(SchematicStructureRules.allowPartialDoubleSlab(
            "minecraft:stone_slab", mapOf("type" to "double"), "minecraft:oak_planks", emptyMap(), actualReplaceable = false,
        ))
    }

    @Test
    fun `double chest single half is provisional only while expected partner is missing`() {
        val expected = mapOf("type" to "left", "facing" to "north")
        val single = mapOf("type" to "single", "facing" to "north")
        assertTrue(SchematicStructureRules.isChestProvisional("minecraft:chest", expected, "minecraft:chest", single, partnerExpected = true))
        assertFalse(SchematicStructureRules.isChestProvisional("minecraft:chest", expected, "minecraft:chest", single, partnerExpected = false))
        assertEquals(BuildCellPos(1, 0, 0), SchematicStructureRules.chestPartner(BuildCellPos(0, 0, 0), expected))
        assertEquals(
            BuildCellPos(1, 0, 0),
            SchematicStructureRules.provisionalChestPartnerTarget(
                BuildCellPos(0, 0, 0), expected, setOf(BuildCellPos(1, 0, 0)),
            ),
        )
        assertNull(SchematicStructureRules.provisionalChestPartnerTarget(BuildCellPos(0, 0, 0), expected, emptySet()))
    }

    @Test
    fun `support candidate search skips occluded first face and chooses a later visible face`() {
        val candidates = listOf("occluded-top", "visible-side", "visible-back")
        assertEquals("visible-side", firstVisibleSupportCandidate(candidates) { it != "occluded-top" })
        assertNull(firstVisibleSupportCandidate(candidates) { false })
    }

    @Test
    fun `pending virtual cells keep completion resumable but a capped scan cannot resume`() {
        assertTrue(SchematicCompletionRules.shouldStayResumable(1, scanTruncated = false))
        assertTrue(SchematicCompletionRules.shouldStayResumable(256, scanTruncated = false))
        assertFalse(SchematicCompletionRules.shouldStayResumable(0, scanTruncated = false))
        assertFalse(SchematicCompletionRules.shouldStayResumable(1, scanTruncated = true))
    }

    @Test
    fun `shared layer completion rule keeps unavailable waterlog crafter and chest work unresolved`() {
        fun unresolved(
            pendingVirtualData: Boolean = false,
            pendingWaterlog: Boolean = false,
            unresolvedCell: Boolean = false,
            deferredCell: Boolean = false,
            crafterMaskPending: Boolean = false,
            chestPartnerPending: Boolean = false,
            missing: Boolean = false,
            cellRole: BuildRole? = null,
            placeTnt: Boolean = false,
            placeWaterLava: Boolean = true,
        ) = layerCellIsUnresolved(
            pendingVirtualData = pendingVirtualData,
            unresolved = unresolvedCell,
            deferred = deferredCell,
            pendingWaterlog = pendingWaterlog,
            missing = missing,
            cellRole = cellRole,
            placeTnt = placeTnt,
            placeWaterLava = placeWaterLava,
            crafterMaskPending = crafterMaskPending,
            chestPartnerPending = chestPartnerPending,
        )

        assertTrue(unresolved(pendingVirtualData = true))
        assertTrue(unresolved(pendingWaterlog = true))
        assertTrue(unresolved(crafterMaskPending = true))
        assertTrue(unresolved(chestPartnerPending = true))
        assertTrue(unresolved(missing = true, cellRole = BuildRole.STRUCTURE))
        assertFalse(unresolved(missing = true, cellRole = BuildRole.TNT, placeTnt = false))
        assertFalse(unresolved(missing = true, cellRole = BuildRole.FLUID, placeWaterLava = false))
        assertFalse(unresolved(unresolvedCell = true, cellRole = BuildRole.TNT, placeTnt = false))
        assertFalse(unresolved(deferredCell = true, cellRole = BuildRole.FLUID, placeWaterLava = false))
    }

    @Test
    fun `trapped-player digout follows source eligibility and keeps automatic exit guarded`() {
        assertTrue(SchematicDigOutRules.canStartSourceDigOut(routeVisited = 0, escapeMaterialCount = 2))
        assertTrue(SchematicDigOutRules.canStartSourceDigOut(routeVisited = 3, escapeMaterialCount = 2))
        assertFalse(SchematicDigOutRules.canStartSourceDigOut(routeVisited = 4, escapeMaterialCount = 2))
        assertFalse(SchematicDigOutRules.canStartSourceDigOut(routeVisited = 1, escapeMaterialCount = 1))

        val sourceEligibleNonFullBlock = SchematicDigOutRules.sourceBlockEligible(
            isAir = false,
            replaceable = false,
            fullBlockCollision = false,
            isContainer = false,
            destroySpeed = 0.2f,
            adjacentFluidsEmpty = true,
        )
        assertTrue(sourceEligibleNonFullBlock)
        assertFalse(SchematicDigOutRules.sourceBlockEligible(false, false, false, true, 0.2f, true))
        assertFalse(SchematicDigOutRules.sourceBlockEligible(false, false, true, false, 0.2f, true))
        assertFalse(SchematicDigOutRules.sourceBlockEligible(false, false, false, false, -1f, true))
        assertFalse(SchematicDigOutRules.sourceBlockEligible(false, false, false, false, 0.2f, false))
        assertFalse(SchematicDigOutRules.sourceBlockEligible(true, false, false, false, 0.2f, true))
        assertFalse(SchematicDigOutRules.sourceBlockEligible(false, true, false, false, 0.2f, true))

        assertTrue(SchematicDigOutRules.safeRecoveryEligible(withinInteractionReach = true, nativeRayHitsTarget = true, attempts = 0))
        assertFalse(SchematicDigOutRules.safeRecoveryEligible(withinInteractionReach = false, nativeRayHitsTarget = true, attempts = 0))
        assertFalse(SchematicDigOutRules.safeRecoveryEligible(withinInteractionReach = true, nativeRayHitsTarget = false, attempts = 0))
        assertFalse(SchematicDigOutRules.safeRecoveryEligible(withinInteractionReach = true, nativeRayHitsTarget = true, attempts = SchematicDigOutRules.MAX_ATTEMPTS))
        assertTrue(SchematicDigOutRules.canUseAutomaticExit(stableFloorBelow = true, clearHeadroom = true, safeRouteExists = true))
        assertFalse(SchematicDigOutRules.canUseAutomaticExit(stableFloorBelow = false, clearHeadroom = true, safeRouteExists = true))
        assertFalse(SchematicDigOutRules.canUseAutomaticExit(stableFloorBelow = true, clearHeadroom = false, safeRouteExists = true))
        assertFalse(SchematicDigOutRules.canUseAutomaticExit(stableFloorBelow = true, clearHeadroom = true, safeRouteExists = false))
        assertTrue(SchematicDigOutRules.shouldAcknowledgeAirUpdate(4, 3, isAir = true))
        assertFalse(SchematicDigOutRules.shouldAcknowledgeAirUpdate(3, 3, isAir = true))
        assertFalse(SchematicDigOutRules.shouldAcknowledgeAirUpdate(4, 3, isAir = false))
    }

    @Test
    fun `semantic refresh detects only changes between two available projected states`() {
        assertTrue(SchematicSemanticRules.changed(true, "minecraft:air", true, "minecraft:stone[]"))
        assertTrue(SchematicSemanticRules.changed(true, "minecraft:stone[facing=north]", true, "minecraft:stone[facing=east]"))
        assertFalse(SchematicSemanticRules.changed(false, null, true, "minecraft:stone[]"))
        assertFalse(SchematicSemanticRules.changed(true, "minecraft:air", false, null))
        assertFalse(SchematicSemanticRules.changed(true, "minecraft:air", true, "minecraft:air"))
    }

    @Test
    fun `staged water and lava buckets may return as empty buckets after confirmed use`() {
        assertTrue(SchematicInventoryRules.canRestoreStagedItem("minecraft:water_bucket", "minecraft:bucket"))
        assertTrue(SchematicInventoryRules.canRestoreStagedItem("minecraft:lava_bucket", "minecraft:bucket"))
        assertTrue(SchematicInventoryRules.canRestoreStagedItem("minecraft:stone", "minecraft:stone"))
        assertFalse(SchematicInventoryRules.canRestoreStagedItem("minecraft:water_bucket", "minecraft:lava_bucket"))
        assertFalse(SchematicInventoryRules.canRestoreStagedItem("minecraft:stone", "minecraft:diamond"))
    }

    @Test
    fun `staged restore accepts only bounded remainder with owned components and enchantments`() {
        val original = InventorySlotValue(
            "minecraft:oak_planks", 16, "Oak Planks", componentFingerprint = "{}",
            actualEnchantments = mapOf("unbreaking" to 1),
        )
        assertTrue(SchematicInventoryRules.isOwnedRestoreRemainder(original, original.copy(count = 5)))
        assertTrue(SchematicInventoryRules.isOwnedRestoreRemainder(original, InventorySlotValue("", 0)))
        assertFalse(SchematicInventoryRules.isOwnedRestoreRemainder(original, original.copy(count = 17)))
        assertFalse(SchematicInventoryRules.isOwnedRestoreRemainder(original, original.copy(componentFingerprint = "{custom_data}")))
        assertFalse(SchematicInventoryRules.isOwnedRestoreRemainder(original, original.copy(actualEnchantments = emptyMap())))
        assertFalse(SchematicInventoryRules.isOwnedRestoreRemainder(original, original.copy(itemId = "minecraft:birch_planks")))

        val water = InventorySlotValue.fromStack(ItemStack(Items.WATER_BUCKET))
        val empty = InventorySlotValue.fromStack(ItemStack(Items.BUCKET))
        assertTrue(SchematicInventoryRules.isOwnedRestoreRemainder(
            empty, water, defaultEmptyBucket = empty, defaultWaterBucket = water,
        ))
        assertFalse(SchematicInventoryRules.isOwnedRestoreRemainder(
            empty.copy(name = "renamed"), water, defaultEmptyBucket = empty, defaultWaterBucket = water,
        ))
        assertTrue(SchematicInventoryRules.isOwnedRestoreRemainder(
            water, empty, water, empty,
        ))
        assertFalse(SchematicInventoryRules.isOwnedRestoreRemainder(
            water, empty.copy(count = 2), water, empty,
        ))
        assertFalse(SchematicInventoryRules.isOwnedRestoreRemainder(
            water.copy(componentFingerprint = "{custom_data}"), empty, water, empty,
        ))
    }

    @Test
    fun `procurement quantities share atomic structure costs and source stack rounding`() {
        val needs = listOf(
            SchematicMaterialNeed("minecraft:oak_door", "minecraft:oak_door", mapOf("half" to "lower")),
            SchematicMaterialNeed("minecraft:oak_door", "minecraft:oak_door", mapOf("half" to "upper")),
            SchematicMaterialNeed("minecraft:oak_slab", "minecraft:oak_slab", mapOf("type" to "double")),
        )
        assertEquals(mapOf("minecraft:oak_door" to 1, "minecraft:oak_slab" to 2),
            SchematicProcurementRules.requiredCounts(needs))
        assertEquals(0, SchematicProcurementRules.shortage(32, 40))
        assertEquals(5, SchematicProcurementRules.roundedTarget(5, 64))
        assertEquals(64, SchematicProcurementRules.roundedTarget(17, 64))
        assertEquals(17, SchematicProcurementRules.roundedTarget(17, 16))
    }

    @Test
    fun `staging swap expectation records before values so tracker accepts server swap`() {
        val sourceBefore = InventorySlotValue("minecraft:golden_apple", 12, componentFingerprint = "{food}")
        val destinationBefore = InventorySlotValue("minecraft:stone", 64, componentFingerprint = "{stone}")
        val expectation = SchematicInventoryRules.swapExpectation(
            11L, 4, 20, 12, 36, sourceBefore, destinationBefore,
        )
        assertEquals(sourceBefore, expectation.expectedSource)
        assertEquals(destinationBefore, expectation.expectedDestination)
        val tracker = InventoryAckTracker()
        tracker.begin(expectation)
        assertEquals(InventoryAckStatus.ACKNOWLEDGED, tracker.observe(InventorySyncFrame(
            connectionId = 11L,
            menuId = 4,
            stateId = 21,
            slots = mapOf(12 to destinationBefore, 36 to sourceBefore),
            cursor = null,
            fullContents = false,
        )))
    }

    @Test
    fun `food selection prefers the hotbar then falls back to storage`() {
        assertEquals(3, SchematicInventoryRules.preferredFoodSlot(0..8, 9..35) { it == 3 || it == 12 })
        assertEquals(12, SchematicInventoryRules.preferredFoodSlot(0..8, 9..35) { it == 12 })
        assertNull(SchematicInventoryRules.preferredFoodSlot(0..8, 9..35) { false })
    }

    @Test
    fun `totem requirement follows the setting and actual offhand item`() {
        assertTrue(SchematicInventoryRules.shouldEquipTotem(true, null))
        assertTrue(SchematicInventoryRules.shouldEquipTotem(true, "minecraft:shield"))
        assertFalse(SchematicInventoryRules.shouldEquipTotem(true, "minecraft:totem_of_undying"))
        assertFalse(SchematicInventoryRules.shouldEquipTotem(false, null))
    }

    @Test
    fun `disable restores leases only while the captured world player and connection remain current`() {
        assertTrue(SchematicRestoreRules.shouldRestoreOnDisable(
            moduleEnabled = false, forcedCleanup = false, sameWorld = true, samePlayer = true,
            sameConnection = true, stagedLeases = 1, pendingSwap = false,
        ))
        assertTrue(SchematicRestoreRules.shouldRestoreOnDisable(
            moduleEnabled = false, forcedCleanup = false, sameWorld = true, samePlayer = true,
            sameConnection = true, stagedLeases = 0, pendingSwap = true,
        ))
        assertFalse(SchematicRestoreRules.shouldRestoreOnDisable(
            moduleEnabled = false, forcedCleanup = false, sameWorld = false, samePlayer = true,
            sameConnection = true, stagedLeases = 1, pendingSwap = false,
        ))
        assertFalse(SchematicRestoreRules.shouldRestoreOnDisable(
            moduleEnabled = false, forcedCleanup = true, sameWorld = true, samePlayer = true,
            sameConnection = true, stagedLeases = 1, pendingSwap = false,
        ))
        assertFalse(SchematicRestoreRules.shouldRestoreOnDisable(
            moduleEnabled = true, forcedCleanup = false, sameWorld = true, samePlayer = true,
            sameConnection = true, stagedLeases = 1, pendingSwap = false,
        ))
        assertFalse(SchematicRestoreRules.shouldRestoreOnDisable(
            moduleEnabled = false, forcedCleanup = false, sameWorld = true, samePlayer = true,
            sameConnection = false, stagedLeases = 1, pendingSwap = false,
        ))
    }

    @Test
    fun `placement facing and axis are exact until the explicit relaxed orientation pass`() {
        val expected = mapOf("facing" to "north", "axis" to "x", "waterlogged" to "false")
        val sideways = mapOf("facing" to "east", "axis" to "z", "waterlogged" to "false")
        assertFalse(SchematicStateRules.matches("minecraft:oak_log", expected, "minecraft:oak_log", sideways, false))
        assertTrue(SchematicStateRules.matches("minecraft:oak_log", expected, "minecraft:oak_log", sideways, true))
        assertFalse(SchematicStateRules.matches("minecraft:oak_log", expected, "minecraft:birch_log", sideways, true))
    }

    @Test
    fun `control state differences are click adjustments after placement properties match`() {
        val expected = mapOf("facing" to "north", "delay" to "3", "mode_comparator" to "subtract")
        val actual = mapOf("facing" to "north", "delay" to "1", "mode_comparator" to "compare")
        assertFalse(SchematicStateRules.matches("minecraft:repeater", expected, "minecraft:repeater", actual, false))
        assertTrue(SchematicStateRules.placementCompatible("minecraft:repeater", expected, "minecraft:repeater", actual))
        assertTrue(SchematicStateRules.needsControlAdjustment("minecraft:repeater", expected, actual))
        assertFalse(SchematicStateRules.placementCompatible("minecraft:repeater", expected, "minecraft:repeater", actual + ("facing" to "east")))
    }

    @Test
    fun `source state semantics normalize plant aliases fluid levels faces and iron open controls`() {
        assertTrue(SchematicStateRules.matches(
            "minecraft:kelp_plant", mapOf("age" to "0"), "minecraft:kelp", mapOf("age" to "25"), false,
        ))
        assertTrue(SchematicStateRules.matches(
            "minecraft:water", mapOf("level" to "0"), "minecraft:water", mapOf("level" to "7"), false,
        ))
        assertFalse(SchematicStateRules.matches(
            "minecraft:glow_lichen", mapOf("north" to "false"), "minecraft:glow_lichen", mapOf("north" to "true"), false,
        ))
        assertTrue(SchematicStateRules.placementCompatible(
            "minecraft:glow_lichen", mapOf("north" to "true"), "minecraft:glow_lichen", mapOf("north" to "false"),
        ))
        assertFalse(SchematicStateRules.placementCompatible(
            "minecraft:glow_lichen", mapOf("north" to "false"), "minecraft:glow_lichen", mapOf("north" to "true"),
        ))
        assertTrue(SchematicStateRules.matches(
            "minecraft:iron_door", mapOf("open" to "true"), "minecraft:iron_door", mapOf("open" to "false"), false,
        ))
        assertFalse(SchematicStateRules.needsControlAdjustment(
            "minecraft:iron_trapdoor", mapOf("open" to "true"), mapOf("open" to "false"), setOf("open"),
        ))
        assertFalse(SchematicStateRules.matches(
            "minecraft:oak_door", mapOf("open" to "true"), "minecraft:oak_door", mapOf("open" to "false"), false,
        ))
        assertTrue(SchematicStateRules.needsControlAdjustment(
            "minecraft:oak_door", mapOf("open" to "true"), mapOf("open" to "false"), setOf("open"),
        ))
        assertTrue(SchematicStateRules.needsControlAdjustment(
            "minecraft:note_block", mapOf("note" to "24"), mapOf("note" to "0"), setOf("note"),
        ))
    }

    @Test
    fun `wrong state obstruction stays untouched while replaceable and partial slab targets remain buildable`() {
        assertTrue(SchematicStateRules.isObstructed("minecraft:stone", "minecraft:oak_planks", false, false))
        assertFalse(SchematicStateRules.isObstructed("minecraft:stone", "minecraft:oak_planks", true, false))
        assertFalse(SchematicStateRules.isObstructed("minecraft:stone_slab", "minecraft:stone_slab", false, true))
    }

    @Test
    fun `native registry item resolution handles blocks and fluid buckets`() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
        assertSame(Items.STONE, SchematicItemResolver.resolve("minecraft:stone"))
        assertSame(Items.WATER_BUCKET, SchematicItemResolver.resolve("minecraft:water_bucket"))
        assertSame(Items.LAVA_BUCKET, SchematicItemResolver.resolve("minecraft:lava_bucket"))
        assertNull(SchematicItemResolver.resolve("minecraft:air"))
        assertNull(SchematicItemResolver.resolve("not an identifier"))
        assertNull(SchematicItemResolver.resolve(null))
    }

    @Test
    fun `native state matcher covers every horizontal facing axis slab half and door state`() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()

        val facings = Direction.Plane.HORIZONTAL.map { facing ->
            net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, facing)
        }.toList()
        facings.forEachIndexed { index, expected ->
            val wrongFacing = facings[(index + 1) % facings.size]
            assertTrue(SchematicNativePlacement.matchesNativeState(expected, expected))
            assertFalse(SchematicNativePlacement.matchesNativeState(expected, wrongFacing))
            assertTrue(SchematicNativePlacement.matchesNativeState(expected, wrongFacing, allowRelaxedOrientation = true))
        }

        val axes = Direction.Axis.entries.map { axis ->
            net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, axis)
        }
        axes.forEachIndexed { index, expected ->
            val wrongAxis = axes[(index + 1) % axes.size]
            assertFalse(SchematicNativePlacement.matchesNativeState(expected, wrongAxis))
            assertTrue(SchematicNativePlacement.matchesNativeState(expected, wrongAxis, allowRelaxedOrientation = true))
        }

        val top = net.minecraft.world.level.block.Blocks.OAK_SLAB.defaultBlockState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE, SlabType.TOP)
        val bottom = top.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM)
        val double = top.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE)
        assertFalse(SchematicNativePlacement.matchesNativeState(top, bottom))
        assertTrue(SchematicNativePlacement.matchesNativeState(double, top, allowPartialDoubleSlab = true))
        assertFalse(SchematicNativePlacement.matchesNativeState(top, bottom, allowPartialDoubleSlab = true))

        val lowerDoor = net.minecraft.world.level.block.Blocks.OAK_DOOR.defaultBlockState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOOR_HINGE, DoorHingeSide.LEFT)
        val upperDoor = lowerDoor.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)
        val otherHinge = lowerDoor.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOOR_HINGE, DoorHingeSide.RIGHT)
        assertFalse(SchematicNativePlacement.matchesNativeState(lowerDoor, upperDoor))
        assertFalse(SchematicNativePlacement.matchesNativeState(lowerDoor, otherHinge))
        assertFalse(SchematicNativePlacement.matchesNativeState(lowerDoor, otherHinge, allowRelaxedOrientation = true))
    }

    @Test
    fun `native comparator control property is named mode and changes are adjustable`() {
        val nativeModeName = Blocks.COMPARATOR.defaultBlockState().properties
            .first { it == net.minecraft.world.level.block.state.properties.BlockStateProperties.MODE_COMPARATOR }.name
        assertEquals("mode", nativeModeName)
        val expected = mapOf("facing" to "north", nativeModeName to "subtract")
        val actual = mapOf("facing" to "north", nativeModeName to "compare")
        assertFalse(SchematicStateRules.matches("minecraft:comparator", expected, "minecraft:comparator", actual, false))
        assertTrue(SchematicStateRules.placementCompatible("minecraft:comparator", expected, "minecraft:comparator", actual))
        assertTrue(SchematicStateRules.needsControlAdjustment("minecraft:comparator", expected, actual, setOf(nativeModeName)))
    }

    @Test
    fun `placement rotation must match both managed and server rotations`() {
        assertTrue(SchematicNativePlacement.rotationAcknowledged(0f, 20f, 359.95f, 20.04f, 0.03f, 19.98f))
        assertFalse(SchematicNativePlacement.rotationAcknowledged(0f, 20f, 0f, 20f, 1f, 20f))
        assertFalse(SchematicNativePlacement.rotationAcknowledged(0f, 20f, 2f, 20f, 0f, 20f))
        assertFalse(SchematicNativePlacement.rotationAcknowledged(0f, 20f, null, null, 0f, 20f))
    }

    @Test
    fun `no action failures are rate limited without discarding the bounded retry stages`() {
        val gate = BuildActionFailureGate(20)
        val retry = BuildRetryLedger(3, 8)
        val pos = BuildCellPos(7, 8, 9)
        assertTrue(gate.shouldCount(pos, 1))
        assertFalse(gate.shouldCount(pos, 20))
        assertTrue(gate.shouldCount(pos, 21))
        assertEquals(RetryDecision.RETRY, retry.recordFailure(pos))
        assertEquals(RetryDecision.RETRY, retry.recordFailure(pos))
        assertEquals(RetryDecision.DEFER, retry.recordFailure(pos))
        repeat(5) { retry.recordFailure(pos) }
        assertEquals(RetryDecision.UNRESOLVED, retry.recordFailure(pos))
    }

    @Test
    fun `packet inbox is bounded and drains in arrival order`() {
        val inbox = BoundedSchematicInbox<Int>(2)
        assertTrue(inbox.offer(1))
        assertTrue(inbox.offer(2))
        assertFalse(inbox.offer(3))
        assertEquals(2, inbox.size())
        assertEquals(1, inbox.poll())
        assertTrue(inbox.offer(3))
        assertEquals(listOf(2, 3), listOf(inbox.poll(), inbox.poll()))
        assertNull(inbox.poll())
    }

    @Test
    fun `world gate waits for same world identity and ready session`() {
        val gate = SchematicWorldStartGate<Any>()
        val world = Any()
        gate.request(world)
        assertNull(gate.poll(Any(), sessionReady = true))
        assertNull(gate.poll(world, sessionReady = false))
        assertSame(world, gate.poll(world, sessionReady = true))
        assertNull(gate.poll(world, sessionReady = true))
        gate.request(world)
        gate.cancel()
        assertNull(gate.poll(world, sessionReady = true))
    }

    @Test
    fun `stage ordering and TNT setting are respected without dropping other work`() {
        val structure = cell(0, 0, 0, BuildRole.STRUCTURE)
        val repeater = cell(1, 0, 0, BuildRole.REDSTONE)
        val piston = cell(2, 0, 0, BuildRole.MACHINE)
        val tnt = cell(3, 0, 0, BuildRole.TNT)
        val safe = SchematicBuildPlanner.plan(listOf(tnt, piston, repeater, structure), emptySet())
        assertEquals(listOf(structure, repeater, piston), safe.ordered)
        assertEquals(setOf(tnt.pos), safe.excludedTnt)
        val armed = SchematicBuildPlanner.plan(listOf(tnt, piston, repeater, structure), emptySet(), placeTnt = true)
        assertEquals(listOf(structure, repeater, piston, tnt), armed.ordered)
        assertEquals(BuildRole.FLUID, BuildRoleRules.classify("minecraft:water", "minecraft:water_bucket", "LiquidBlock"))
        assertEquals(BuildRole.STRUCTURE, BuildRoleRules.classify("minecraft:crafter", "minecraft:crafter", "CrafterBlock"))
        assertEquals(BuildRole.MACHINE, BuildRoleRules.classify("minecraft:piston", "minecraft:piston", "PistonBaseBlock"))
    }

    @Test
    fun `planning windows are stage and layer ordered with floor-divided negative chunks`() {
        val first = SchematicBuildPlanner.windowKey(cell(-1, 64, -1))
        val sameChunk = SchematicBuildPlanner.windowKey(cell(-16, 64, -16))
        val previousChunk = SchematicBuildPlanner.windowKey(cell(-17, 64, -17))
        val nextLayer = SchematicBuildPlanner.windowKey(cell(-1, 65, -1))
        val piston = SchematicBuildPlanner.windowKey(cell(-1, 64, -1, BuildRole.MACHINE))
        assertEquals(first, sameChunk)
        assertEquals(-1, first.chunkX)
        assertEquals(-1, first.chunkZ)
        assertEquals(-2, previousChunk.chunkX)
        assertNotEquals(first, nextLayer)
        assertTrue(first < piston)
    }

    @Test
    fun `placement progress advances only on server state and stops after bounded retries`() {
        val progress = PlacementConfirmationMachine(maxRetries = 2)
        progress.begin()
        progress.sent()
        assertEquals(PlacementStatus.WAITING_FOR_SERVER, progress.current().status)
        assertEquals(PlacementStatus.CONFIRMED, progress.serverStateMatches(true).status)
        progress.reset()
        progress.begin(); progress.sent()
        assertEquals(PlacementStatus.RETRYING, progress.timeout("no update").status)
        progress.begin(); progress.sent()
        assertEquals(PlacementStatus.RETRYING, progress.timeout("still no update").status)
        progress.begin(); progress.sent()
        assertEquals(PlacementStatus.UNRESOLVED, progress.timeout("ghost").status)
        progress.cancel()
        assertEquals(PlacementStatus.CANCELLED, progress.current().status)
    }

    @Test
    fun `stubborn retry and deferred pass remain bounded per block`() {
        val ledger = BuildRetryLedger(stubbornAfter = 3, maxFailures = 8)
        val a = BuildCellPos(1, 2, 3)
        val b = BuildCellPos(4, 5, 6)
        assertEquals(RetryDecision.RETRY, ledger.recordFailure(a))
        assertEquals(RetryDecision.RETRY, ledger.recordFailure(a))
        assertEquals(RetryDecision.DEFER, ledger.recordFailure(a))
        assertTrue(ledger.inStubbornPhase(a))
        repeat(4) { assertEquals(RetryDecision.RETRY, ledger.recordFailure(a)) }
        assertEquals(RetryDecision.UNRESOLVED, ledger.recordFailure(a))
        assertEquals(RetryDecision.RETRY, ledger.recordFailure(b))
        assertEquals(8, ledger.failures(a))
        assertEquals(setOf(a), ledger.unresolved())
    }

    @Test
    fun `session gate uses object identity for world player connection and placement changes`() {
        val gate = BuilderSessionGate()
        val world = Any(); val player = Any(); val connection = Any(); val placement = Any()
        assertTrue(gate.observe(BuilderSessionStamp(world, player, connection, placement)))
        assertFalse(gate.observe(BuilderSessionStamp(world, player, connection, placement)))
        assertTrue(gate.observe(BuilderSessionStamp(Any(), player, connection, placement)))
        assertTrue(gate.observe(null))
    }

    @Test
    fun `money parser and spend cap reject malformed or over-budget listings`() {
        assertEquals(10_000_000L, BuildMoneyRules.parse("10m"))
        assertEquals(1_250L, BuildMoneyRules.parse("$1.25k"))
        assertNull(BuildMoneyRules.parse("unlimited"))
        assertTrue(BuildMoneyRules.mayBuy(10, 20, 75, 100))
        assertFalse(BuildMoneyRules.mayBuy(21, 20, 0, 100))
        assertFalse(BuildMoneyRules.mayBuy(20, 20, 81, 100))
    }

    @Test
    fun `auction receipt parser preserves reported purchase facts and source price rounding`() {
        val receipt = SchematicAuctionReceiptParser.parse("§aYou bought 5x Diamond for $1.25k")!!
        assertEquals(SchematicAuctionOutcome.PURCHASED, receipt.outcome)
        assertEquals("diamond", receipt.reportedName)
        assertEquals(5, receipt.count)
        assertEquals(java.math.BigDecimal("1250"), receipt.totalPrice)

        val oneItem = SchematicAuctionReceiptParser.parse("You bought Golden Apple for 1,234.5")!!
        assertEquals(1, oneItem.count)
        assertEquals("golden apple", oneItem.reportedName)
        assertEquals(java.math.BigDecimal("1235"), oneItem.totalPrice)
    }

    @Test
    fun `auction receipt parser retains incomplete purchases for uncertainty and ignores unrelated chat`() {
        val incomplete = SchematicAuctionReceiptParser.parse("You bought a mysterious item")!!
        assertEquals(SchematicAuctionOutcome.PURCHASED, incomplete.outcome)
        assertEquals("a mysterious item", incomplete.reportedName)
        assertEquals(1, incomplete.count)
        assertNull(incomplete.totalPrice)
        assertNull(SchematicAuctionReceiptParser.parse("Your balance is 1000"))
    }

    @Test
    fun `auction receipt parser classifies only explicit transaction rejections`() {
        assertEquals(
            SchematicAuctionOutcome.LISTING_GONE,
            SchematicAuctionReceiptParser.parse("That listing is no longer available")?.outcome,
        )
        assertEquals(
            SchematicAuctionOutcome.INSUFFICIENT_FUNDS,
            SchematicAuctionReceiptParser.parse("You don't have enough money")?.outcome,
        )
        assertEquals(
            SchematicAuctionOutcome.OWN_LISTING,
            SchematicAuctionReceiptParser.parse("You cannot buy your own listing")?.outcome,
        )
        assertNull(SchematicAuctionReceiptParser.parse("The item is available soon"))
    }

    @Test
    fun `procurement room includes compatible partial stacks and excludes component variants`() {
        val wanted = InventorySlotValue(
            "minecraft:stone", 1, "Stone", emptyList(), "full-with-lore", emptyMap(), "normalized-components",
        )
        val matchingStack = wanted.copy(count = 32)
        val matchingPartial = wanted.copy(count = 60, lore = listOf("Price: ${'$'}2"))
        val incompatibleVariant = wanted.copy(count = 63, procurementFingerprint = "different-components")
        val room = SchematicProcurementRules.inventoryRoom(
            wanted,
            64,
            listOf(matchingStack, matchingPartial, incompatibleVariant, InventorySlotValue("", 0)),
            compatibleLore = { lore -> lore.filterNot { it.startsWith("Price:") } },
        )
        assertEquals(100, room)
        assertEquals(32, SchematicProcurementRules.purchaseTarget(20, 32, 64))
        assertEquals(3, SchematicProcurementRules.purchaseTarget(9, 7, 1, waterBucket = true))
        assertEquals(0, SchematicProcurementRules.purchaseTarget(2, 4, 1, waterBucket = true))
    }

    @Test
    fun `interactive placement supports acquire stable sneak without discarding manual state`() {
        assertTrue(SchematicSneakRules.needsSneak("minecraft:hopper", hasBlockEntity = true))
        assertTrue(SchematicSneakRules.needsSneak("minecraft:oak_trapdoor", hasBlockEntity = false))
        assertTrue(SchematicSneakRules.needsSneak("minecraft:crafting_table", hasBlockEntity = false))
        assertFalse(SchematicSneakRules.needsSneak("minecraft:stone", hasBlockEntity = false))
        assertFalse(SchematicSneakRules.settled(true, 0))
        assertTrue(SchematicSneakRules.settled(true, 1))
        assertFalse(SchematicSneakRules.settled(false, 5))
        assertFalse(SchematicSneakRules.released(false, 0))
        assertTrue(SchematicSneakRules.released(false, 1))
        assertFalse(SchematicSneakRules.released(true, 5))
        assertFalse(SchematicSneakRules.restoreShift(
            manualBeforeLease = true, physicalDownAtAcquire = true, physicalDownNow = false,
        ))
        assertTrue(SchematicSneakRules.restoreShift(
            manualBeforeLease = false, physicalDownAtAcquire = false, physicalDownNow = true,
        ))
        assertTrue(SchematicSneakRules.restoreShift(
            manualBeforeLease = true, physicalDownAtAcquire = false, physicalDownNow = false,
        ))
        assertFalse(SchematicSneakRules.restoreShift(
            manualBeforeLease = false, physicalDownAtAcquire = false, physicalDownNow = false,
        ))
    }

    @Test
    fun `crafter mask toggles only empty mismatched slots and requires exact server data`() {
        val expected = listOf(true, false, true, false, false, false, false, false, false)
        val current = List(9) { false }
        val changes = SchematicCrafterRules.toggles(expected, current, occupiedSlots = setOf(2))
        assertEquals(listOf(SchematicCrafterRules.Toggle(0, enabled = false)), changes)
        assertFalse(SchematicCrafterRules.maskMatches(expected, current))
        assertTrue(SchematicCrafterRules.dataValueMatches(expectedDisabled = true, serverValue = 1))
        assertFalse(SchematicCrafterRules.dataValueMatches(expectedDisabled = true, serverValue = 0))
        assertTrue(SchematicCrafterRules.allDataAcknowledged(setOf(0), setOf(0)))
        assertFalse(SchematicCrafterRules.allDataAcknowledged(setOf(0, 2), setOf(0)))
        assertFalse(SchematicCrafterRules.allDataAcknowledged(setOf(9), setOf(9)))
    }

    @Test
    fun `crafter slot data can precede local menu while ownership still waits for matching screen`() {
        val announcedMenuId = 7
        assertTrue(SchematicCrafterRules.dataBelongsToAnnouncedMenu(announcedMenuId, 7, 0))
        assertTrue(SchematicCrafterRules.dataBelongsToAnnouncedMenu(announcedMenuId, 7, 8))
        assertFalse(SchematicCrafterRules.dataBelongsToAnnouncedMenu(announcedMenuId, 6, 0))
        assertFalse(SchematicCrafterRules.dataBelongsToAnnouncedMenu(announcedMenuId, 7, 9))
        assertFalse(SchematicCrafterRules.localMenuMatchesAnnouncement(announcedMenuId, 7, true, false))
        assertFalse(SchematicCrafterRules.localMenuMatchesAnnouncement(announcedMenuId, 7, false, true))
        assertFalse(SchematicCrafterRules.localMenuMatchesAnnouncement(announcedMenuId, 8, true, true))
        assertTrue(SchematicCrafterRules.localMenuMatchesAnnouncement(announcedMenuId, 7, true, true))
    }

    @Test
    fun `placed crafter mask is selected before unrelated later build cells finish`() {
        val placed = BuildCellPos(0, 1, 0)
        val later = BuildCellPos(8, 4, 3)
        val expected = linkedMapOf(placed to List(9) { it == 0 }, later to List(9) { it == 1 })
        val next = SchematicCrafterProgressRules.nextReady(expected, emptySet()) { it == placed }
        assertEquals(placed, next?.first)
        assertEquals(expected.getValue(placed), next?.second)
        assertNull(SchematicCrafterProgressRules.nextReady(expected, setOf(placed)) { it == placed })
    }

    @Test
    fun `food use requires server stack consumption and excludes unsafe or converting foods`() {
        assertTrue(SchematicFoodRules.eligible("minecraft:cooked_beef", nutrition = 8, hasUseRemainder = false))
        assertFalse(SchematicFoodRules.eligible("minecraft:rotten_flesh", nutrition = 4, hasUseRemainder = false))
        assertFalse(SchematicFoodRules.eligible("minecraft:honey_bottle", nutrition = 6, hasUseRemainder = true))
        val before = InventorySlotValue("minecraft:cooked_beef", 2, "Cooked Beef", componentFingerprint = "{}")
        assertTrue(SchematicFoodRules.serverConsumptionAcknowledged(before, before.copy(count = 1)))
        assertTrue(SchematicFoodRules.serverConsumptionAcknowledged(before, InventorySlotValue("", 0)))
        assertFalse(SchematicFoodRules.serverConsumptionAcknowledged(before, before))
        assertFalse(SchematicFoodRules.serverConsumptionAcknowledged(before, before.copy(count = 3)))
        assertFalse(SchematicFoodRules.serverConsumptionAcknowledged(before, before.copy(count = 1, componentFingerprint = "{renamed}")))
        assertFalse(SchematicFoodRules.serverConsumptionAcknowledged(before, InventorySlotValue("minecraft:bowl", 1)))
    }

    @Test
    fun `water refill accepts only native single bucket and authoritative state changes`() {
        val empty = InventorySlotValue.fromStack(ItemStack(Items.BUCKET))
        val water = InventorySlotValue.fromStack(ItemStack(Items.WATER_BUCKET))
        assertTrue(SchematicRefillRules.eligibleEmptyBucket(empty, empty))
        assertFalse(SchematicRefillRules.eligibleEmptyBucket(empty.copy(count = 2), empty))
        assertTrue(SchematicRefillRules.waterBucketAcknowledged(empty, water, empty, water))
        assertFalse(SchematicRefillRules.waterBucketAcknowledged(empty, water.copy(name = "renamed"), empty, water))
        assertTrue(SchematicRefillRules.unchangedUseAcknowledged(empty, empty, 32767, 0))
        assertFalse(SchematicRefillRules.unchangedUseAcknowledged(empty, empty, 12, 12))
        assertFalse(SchematicRefillRules.unchangedUseAcknowledged(empty, water, 12, 13))
    }

    @Test
    fun `waterlogging is a separate task from structural matching and needs an exact bucket remainder`() {
        val waterBucket = InventorySlotValue.fromStack(ItemStack(Items.WATER_BUCKET))
        val emptyBucket = InventorySlotValue.fromStack(ItemStack(Items.BUCKET))
        val customEmptyBucket = emptyBucket.copy(name = "custom bucket")

        assertTrue(SchematicWaterlogRules.needsWaterlogging(true, true, false))
        assertFalse(SchematicWaterlogRules.needsWaterlogging(false, true, false))
        assertFalse(SchematicWaterlogRules.needsWaterlogging(true, false, false))
        assertFalse(SchematicWaterlogRules.needsWaterlogging(true, true, true))
        assertTrue(SchematicWaterlogRules.waterBucketUseAcknowledged(waterBucket, emptyBucket, waterBucket, emptyBucket))
        assertFalse(SchematicWaterlogRules.waterBucketUseAcknowledged(waterBucket, customEmptyBucket, waterBucket, emptyBucket))
        assertFalse(SchematicWaterlogRules.waterBucketUseAcknowledged(waterBucket, waterBucket, waterBucket, emptyBucket))
        assertFalse(SchematicWaterlogRules.acknowledgmentsComplete(blockStateAcknowledged = false, inventoryRemainderAcknowledged = true))
        assertFalse(SchematicWaterlogRules.acknowledgmentsComplete(blockStateAcknowledged = true, inventoryRemainderAcknowledged = false))
        assertTrue(SchematicWaterlogRules.acknowledgmentsComplete(blockStateAcknowledged = true, inventoryRemainderAcknowledged = true))
    }

    @Test
    fun `scaffold inventory preference accepts only plain transparent or leaf blocks`() {
        val ordinaryGlass = SchematicScaffoldRules.Candidate("minecraft:glass", true, false, slot = 2)
        val leaves = SchematicScaffoldRules.Candidate("minecraft:oak_leaves", false, true, slot = 3)
        val stainedGlass = SchematicScaffoldRules.Candidate("minecraft:blue_stained_glass", true, false, slot = 4)
        val decoratedGlass = ordinaryGlass.copy(slot = 5, hasBlockEntityData = true)
        val stateOverriddenLeaves = leaves.copy(slot = 6, hasBlockState = true)
        val emptyLeaves = leaves.copy(slot = 7, count = 0)

        assertTrue(SchematicScaffoldRules.eligible(ordinaryGlass))
        assertFalse(SchematicScaffoldRules.eligible(decoratedGlass))
        assertFalse(SchematicScaffoldRules.eligible(stateOverriddenLeaves))
        assertFalse(SchematicScaffoldRules.eligible(emptyLeaves))
        assertEquals(ordinaryGlass, SchematicScaffoldRules.preferred(listOf(leaves, stainedGlass, ordinaryGlass)))
        assertEquals(leaves, SchematicScaffoldRules.preferred(listOf(stateOverriddenLeaves, leaves)))
        assertNull(SchematicScaffoldRules.preferred(listOf(decoratedGlass, emptyLeaves)))
    }

    @Test
    fun `scaffold cells reject obstruction unknown projection fluids player overlap and observer sensitivity`() {
        assertTrue(SchematicScaffoldRules.cellCanBeScaffolded(
            replaceable = true, projectedAir = true, emptyFluid = true, intersectsPlayer = false, observerSensitive = false,
        ))
        assertFalse(SchematicScaffoldRules.cellCanBeScaffolded(true, false, true, false, false))
        assertFalse(SchematicScaffoldRules.cellCanBeScaffolded(true, true, false, false, false))
        assertFalse(SchematicScaffoldRules.cellCanBeScaffolded(true, true, true, true, false))
        assertFalse(SchematicScaffoldRules.cellCanBeScaffolded(true, true, true, false, true))
    }

    @Test
    fun `support and climb scaffold controls represent distinct bounded routes`() {
        assertTrue(SchematicScaffoldRules.targetNeedsFaceSupport(nativeCanSurvive = false))
        assertFalse(SchematicScaffoldRules.targetNeedsFaceSupport(nativeCanSurvive = true))
        val start = BuildCellPos(0, 64, 0)
        val climbTarget = BuildCellPos(3, 70, 0)
        assertTrue(SchematicScaffoldRules.walkwayStepAdvances(start, BuildCellPos(1, 65, 0), climbTarget))
        assertTrue(SchematicScaffoldRules.walkwayStepAdvances(start, BuildCellPos(1, 64, 0), BuildCellPos(8, 64, 0)))
        assertFalse(SchematicScaffoldRules.walkwayStepAdvances(start, BuildCellPos(-1, 65, 0), climbTarget))
        assertFalse(SchematicScaffoldRules.walkwayStepAdvances(start, BuildCellPos(0, 66, 0), climbTarget))
    }

    @Test
    fun `vertical climb plans a physically adjacent two-block support chain for successive rises`() {
        val solids = linkedSetOf(BuildCellPos(1, 0, 1)) // flat floor under the first foothold
        val firstFoothold = BuildCellPos(1, 1, 1)
        assertTrue(SchematicScaffoldRules.hasAdjacentShapeSupport(firstFoothold, solids))
        solids += firstFoothold

        for (feet in listOf(BuildCellPos(1, 3, 2), BuildCellPos(1, 4, 3))) {
            val (lower, upper) = SchematicScaffoldRules.verticalRiseSupportChain(feet)
            assertTrue(SchematicScaffoldRules.hasAdjacentShapeSupport(lower, solids), "lower block $lower must touch the prior support")
            solids += lower
            assertTrue(SchematicScaffoldRules.hasAdjacentShapeSupport(upper, solids), "upper block $upper must touch the acknowledged lower block")
            solids += upper
        }
    }

    @Test
    fun `pillar admission enforces grounded vertical range and source full height floor`() {
        assertTrue(SchematicPillarRules.canStart(true, true, 4, 4, 0, 8))
        assertFalse(SchematicPillarRules.canStart(false, true, 1, 4, 0, 8))
        assertFalse(SchematicPillarRules.canStart(true, false, 1, 4, 0, 8))
        assertFalse(SchematicPillarRules.canStart(true, true, 5, 4, 0, 8))
        assertFalse(SchematicPillarRules.canStart(true, true, 1, 4, 8, 8))
        assertTrue(SchematicPillarRules.fullHeightFloor(0.99))
        assertFalse(SchematicPillarRules.fullHeightFloor(0.5))
        assertEquals(2, SchematicPillarRules.riseCount(7, 5, 2))
        assertEquals(1, SchematicPillarRules.riseCount(1, 8, 2))
        assertEquals(0, SchematicPillarRules.riseCount(4, 0, 2))
    }

    @Test
    fun `pillar placement waits for the native jump clearance and centered column`() {
        assertFalse(SchematicPillarRules.centered(0.12, 0.0, 0.12))
        assertTrue(SchematicPillarRules.centered(0.119, -0.119, 0.12))
        assertFalse(SchematicPillarRules.reachedPlacementWindow(3.019, 2))
        assertTrue(SchematicPillarRules.reachedPlacementWindow(3.02, 2))
        assertTrue(SchematicPillarRules.reachedPlacementWindow(4.0, 2))
    }

    @Test
    fun `pillar cleanup ownership requires both server block and inventory evidence`() {
        assertFalse(SchematicPillarRules.placementProven(false, false))
        assertFalse(SchematicPillarRules.placementProven(true, false))
        assertFalse(SchematicPillarRules.placementProven(false, true))
        assertTrue(SchematicPillarRules.placementProven(true, true))
        assertTrue(SchematicPillarRules.mayRetryUnsent(0, 3))
        assertTrue(SchematicPillarRules.mayRetryUnsent(2, 3))
        assertFalse(SchematicPillarRules.mayRetryUnsent(3, 3))
    }

    @Test
    fun `scaffold ownership needs exact single-item inventory decrement`() {
        val before = InventorySlotValue("minecraft:glass", 4, "Glass", componentFingerprint = "plain", actualEnchantments = emptyMap())
        assertTrue(SchematicScaffoldRules.inventoryDecrementAcknowledged(before, before.copy(count = 3)))
        assertFalse(SchematicScaffoldRules.inventoryDecrementAcknowledged(before, before))
        assertFalse(SchematicScaffoldRules.inventoryDecrementAcknowledged(before, before.copy(count = 2)))
        assertFalse(SchematicScaffoldRules.inventoryDecrementAcknowledged(before, before.copy(count = 3, componentFingerprint = "changed")))
        assertFalse(SchematicScaffoldRules.inventoryDecrementAcknowledged(before, InventorySlotValue("", 0)))
        assertTrue(SchematicScaffoldRules.inventoryDecrementAcknowledged(
            before.copy(count = 1), InventorySlotValue("", 0),
        ))
    }

    @Test
    fun `scaffold server states accept natural leaf distance changes but preserve placed properties`() {
        val expected = mapOf("persistent" to "false", "distance" to "7")
        assertTrue(SchematicScaffoldRules.acknowledgedStateMatches(
            "minecraft:oak_leaves", expected,
            "minecraft:oak_leaves", mapOf("persistent" to "false", "distance" to "1"),
        ))
        assertFalse(SchematicScaffoldRules.acknowledgedStateMatches(
            "minecraft:oak_leaves", expected,
            "minecraft:birch_leaves", mapOf("persistent" to "false", "distance" to "1"),
        ))
        assertFalse(SchematicScaffoldRules.acknowledgedStateMatches(
            "minecraft:oak_leaves", expected,
            "minecraft:oak_leaves", mapOf("persistent" to "true", "distance" to "1"),
        ))
    }

    @Test
    fun `surplus purchase ownership requires exact acknowledged unique inventory growth`() {
        val baselineStack = InventorySlotValue("minecraft:glass", 64, "Glass", componentFingerprint = "plain")
        val purchasedStack = baselineStack.copy(count = 8)
        val before = mapOf("slot0" to baselineStack)
        val after = mapOf("slot0" to baselineStack.copy(count = 64), "slot1" to purchasedStack)
        val expectedIdentity = SchematicSurplusRules.stackIdentity(purchasedStack.copy(count = 1))

        assertEquals(
            mapOf(expectedIdentity to 8),
            SchematicSurplusRules.identifyAcknowledgedPurchaseGrowth(
                before, after, setOf("minecraft:glass"), acknowledgedCount = 8,
            ),
        )
        assertNull(SchematicSurplusRules.identifyAcknowledgedPurchaseGrowth(
            before, after, setOf("minecraft:glass"), acknowledgedCount = 7,
        ))
        assertNull(SchematicSurplusRules.identifyAcknowledgedPurchaseGrowth(
            before, after + ("slot2" to purchasedStack.copy(count = 1, componentFingerprint = "another")),
            setOf("minecraft:glass"), acknowledgedCount = 9,
        ))
        assertNotEquals(
            SchematicSurplusRules.stackIdentity(purchasedStack.copy(count = 1)),
            SchematicSurplusRules.stackIdentity(purchasedStack.copy(count = 1, componentFingerprint = "renamed")),
        )
    }

    @Test
    fun `surplus sale selection subtracts baseline by full identity and keeps protected buckets`() {
        val plain = InventorySlotValue("minecraft:glass", 32, "Glass", componentFingerprint = "plain")
        val decorated = InventorySlotValue("minecraft:glass", 12, "Glass II", componentFingerprint = "custom")
        val water = InventorySlotValue.fromStack(ItemStack(Items.WATER_BUCKET))
        val plainId = SchematicSurplusRules.stackIdentity(plain)
        assertEquals(DonutSellIdentity.of(plain), plainId)
        val customId = SchematicSurplusRules.stackIdentity(decorated)
        val waterId = SchematicSurplusRules.stackIdentity(water)
        val slots = listOf(
            SchematicSurplusRules.SlotStack(0, plain.copy(count = 64)),
            SchematicSurplusRules.SlotStack(1, plain.copy(count = 32)),
            SchematicSurplusRules.SlotStack(2, decorated),
            SchematicSurplusRules.SlotStack(3, water),
        )
        val eligible = SchematicSurplusRules.saleEligibleSlots(
            slots,
            baselineCountsByIdentity = mapOf(plainId to 64, customId to 12, waterId to 1),
            acquiredCountsByIdentity = mapOf(plainId to 32, customId to 12, waterId to 1),
        )
        assertEquals(mapOf(1 to "minecraft:glass"), eligible)
    }

    @Test
    fun `scaffold cleanup requires owned exact safe cell and completed dependent target`() {
        fun mayRemove(
            owned: Boolean = true,
            exact: Boolean = true,
            targetComplete: Boolean = true,
            schematicAir: Boolean = true,
            player: Boolean = false,
            dependent: Boolean = false,
        ) = SchematicScaffoldRules.mayRemoveOwnedScaffold(owned, exact, targetComplete, schematicAir, player, dependent)

        assertTrue(mayRemove())
        assertFalse(mayRemove(owned = false))
        assertFalse(mayRemove(exact = false))
        assertFalse(mayRemove(targetComplete = false))
        assertFalse(mayRemove(schematicAir = false))
        assertFalse(mayRemove(player = true))
        assertFalse(mayRemove(dependent = true))
    }
}
