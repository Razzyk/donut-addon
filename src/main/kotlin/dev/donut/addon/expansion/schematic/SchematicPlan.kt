package dev.donut.addon.expansion.schematic

import dev.donut.addon.expansion.automation.core.InventorySlotValue
import dev.donut.addon.expansion.automation.core.DonutSellIdentity
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Stable coordinate value used by the bounded planner and its tests. */
data class BuildCellPos(val x: Int, val y: Int, val z: Int) : Comparable<BuildCellPos> {
    override fun compareTo(other: BuildCellPos): Int = compareValuesBy(this, other, BuildCellPos::y, BuildCellPos::x, BuildCellPos::z)
    fun offset(dx: Int, dy: Int, dz: Int) = BuildCellPos(x + dx, y + dy, z + dz)
}

enum class BuildRole { FOUNDATION, STRUCTURE, ATTACHMENT, REDSTONE, FLUID, MACHINE, TNT, UNSUPPORTED }

data class BuildCell(
    val pos: BuildCellPos,
    val stateId: String,
    val itemId: String?,
    val role: BuildRole,
    val dependencies: Set<BuildCellPos> = emptySet(),
)

/** Shared final-window rule used after normal planning and during the streaming final audit. */
internal fun layerCellIsUnresolved(
    pendingVirtualData: Boolean,
    unresolved: Boolean,
    deferred: Boolean,
    pendingWaterlog: Boolean,
    missing: Boolean,
    cellRole: BuildRole?,
    placeTnt: Boolean,
    placeWaterLava: Boolean,
    crafterMaskPending: Boolean,
    chestPartnerPending: Boolean,
): Boolean {
    val excludedBySettings = (cellRole == BuildRole.TNT && !placeTnt) ||
        (cellRole == BuildRole.FLUID && !placeWaterLava)
    return pendingVirtualData || pendingWaterlog ||
        ((missing || unresolved || deferred) && !excludedBySettings) || crafterMaskPending || chestPartnerPending
}

data class BuildPlan(
    val ordered: List<BuildCell>,
    val waitingOnDependencies: Set<BuildCellPos>,
    val excludedTnt: Set<BuildCellPos>,
    val unsupported: Set<BuildCellPos>,
    val omittedByLimit: Set<BuildCellPos> = emptySet(),
) {
    val complete: Boolean get() = ordered.isEmpty() && waitingOnDependencies.isEmpty() && excludedTnt.isEmpty() && unsupported.isEmpty() && omittedByLimit.isEmpty()
}

data class SchematicPlanningWindowKey(val stage: Int, val y: Int, val chunkX: Int, val chunkZ: Int) : Comparable<SchematicPlanningWindowKey> {
    override fun compareTo(other: SchematicPlanningWindowKey): Int =
        compareValuesBy(this, other, SchematicPlanningWindowKey::stage, SchematicPlanningWindowKey::y,
            SchematicPlanningWindowKey::chunkX, SchematicPlanningWindowKey::chunkZ)
}

/** Deterministic dependency-first order with controls and dangerous blocks kept in separate stages. */
object SchematicBuildPlanner {
    fun windowKey(cell: BuildCell, machineLast: Boolean = true): SchematicPlanningWindowKey =
        SchematicPlanningWindowKey(
            stage(cell.role, machineLast), cell.pos.y,
            Math.floorDiv(cell.pos.x, 16), Math.floorDiv(cell.pos.z, 16),
        )

    fun plan(
        cells: Collection<BuildCell>,
        completed: Set<BuildCellPos>,
        skipped: Set<BuildCellPos> = emptySet(),
        machineLast: Boolean = true,
        placeTnt: Boolean = false,
        limit: Int = 600,
        satisfiedDependencies: Set<BuildCellPos> = emptySet(),
    ): BuildPlan {
        val byPosition = cells.associateBy(BuildCell::pos)
        val unsupported = cells.asSequence().filter { it.pos !in completed && (it.role == BuildRole.UNSUPPORTED || it.itemId == null) }
            .map(BuildCell::pos).toCollection(linkedSetOf())
        val excluded = cells.asSequence().filter { it.pos !in completed && it.role == BuildRole.TNT && !placeTnt }
            .map(BuildCell::pos).toCollection(linkedSetOf())
        val candidates = cells.asSequence()
            .filter { it.pos !in completed && it.pos !in skipped }
            .filter { it.pos !in unsupported && it.pos !in excluded }
            .toList()
        val unavailableDependencies = candidates.asSequence().filter { cell ->
            cell.dependencies.any { dep ->
                dep !in completed && dep !in satisfiedDependencies &&
                    (dep in skipped || dep in unsupported || dep in excluded || dep !in byPosition)
            }
        }.map(BuildCell::pos).toCollection(linkedSetOf())
        val candidateByPosition = candidates.associateBy(BuildCell::pos)
        val dependencyCount = candidates.associate { cell ->
            cell.pos to cell.dependencies.count { dep -> dep !in completed && dep !in satisfiedDependencies && dep in candidateByPosition }
        }.toMutableMap()
        val dependents = mutableMapOf<BuildCellPos, MutableList<BuildCellPos>>()
        for (cell in candidates) for (dependency in cell.dependencies) {
            if (dependency in candidateByPosition && dependency !in completed && dependency !in satisfiedDependencies) {
                dependents.getOrPut(dependency, ::mutableListOf) += cell.pos
            }
        }
        val emitted = linkedSetOf<BuildCellPos>()
        val ordered = ArrayList<BuildCell>()
        val ordering = compareBy<BuildCell>({ stage(it.role, machineLast) }, { it.pos.y }, { it.pos.x }, { it.pos.z })
        val ready = java.util.PriorityQueue(ordering)
        candidates.filter { it.pos !in unavailableDependencies && dependencyCount[it.pos] == 0 }.forEach(ready::add)
        while (ordered.size < limit.coerceAtLeast(0) && ready.isNotEmpty()) {
            val next = ready.remove()
            if (!emitted.add(next.pos)) continue
            ordered += next
            for (dependent in dependents[next.pos].orEmpty()) {
                val left = (dependencyCount[dependent] ?: 0) - 1
                dependencyCount[dependent] = left
                if (left == 0 && dependent !in unavailableDependencies) ready.add(candidateByPosition.getValue(dependent))
            }
        }
        val waiting = unavailableDependencies + candidates.asSequence()
            .filter { it.pos !in emitted && it.pos !in unavailableDependencies }
            .filter { cell -> cell.dependencies.any { it !in completed && it !in satisfiedDependencies && it !in emitted } }
            .map(BuildCell::pos).toSet()
        val omitted = candidates.asSequence().map(BuildCell::pos)
            .filter { it !in emitted && it !in waiting }.toSet()
        return BuildPlan(ordered, waiting, excluded, unsupported, omitted)
    }

    private fun stage(role: BuildRole, machineLast: Boolean): Int = when (role) {
        BuildRole.FOUNDATION -> 0
        BuildRole.STRUCTURE -> 1
        BuildRole.ATTACHMENT -> 2
        BuildRole.REDSTONE -> 3
        BuildRole.FLUID -> 4
        BuildRole.MACHINE -> if (machineLast) 5 else 1
        BuildRole.TNT -> 6
        BuildRole.UNSUPPORTED -> 7
    }
}

/** Classifies by registry ID so the planner does not depend on optional mod types. */
object BuildRoleRules {
    fun classify(blockId: String, itemId: String?, className: String): BuildRole {
        val id = blockId.substringAfter(':').lowercase(Locale.ROOT)
        val type = className.lowercase(Locale.ROOT)
        if (itemId == null || itemId == "minecraft:air") return BuildRole.UNSUPPORTED
        if (id == "tnt" || id.endsWith("_tnt")) return BuildRole.TNT
        if (id == "water" || id == "lava" || id.endsWith("_water") || id.endsWith("_lava")) return BuildRole.FLUID
        if (id == "crafter") return BuildRole.STRUCTURE
        if (id == "piston" || id == "sticky_piston" || id == "observer") return BuildRole.MACHINE
        if (id.contains("redstone") || id.contains("repeater") || id.contains("comparator") || id.contains("lever") ||
            id.contains("button") || id.contains("pressure_plate") || id.contains("tripwire") || id.contains("rail") ||
            id.contains("redstone_torch") || id == "tnt") return BuildRole.REDSTONE
        if (type.contains("fallingblock")) return BuildRole.FOUNDATION
        if (type.contains("torch") || type.contains("ladder") || type.contains("wallSign".lowercase()) ||
            type.contains("faceattached") || id.contains("wall_torch") || id.contains("wall_sign")) return BuildRole.ATTACHMENT
        return BuildRole.STRUCTURE
    }
}

/** Source-state grouping rules for vanilla multi-cell blocks and multi-item slab placement. */
object SchematicStructureRules {
    fun allowPartialDoubleSlab(
        expectedBlockId: String,
        expectedProperties: Map<String, String>,
        actualBlockId: String,
        actualProperties: Map<String, String>,
        actualReplaceable: Boolean,
    ): Boolean = expectedBlockId.endsWith("_slab") &&
        expectedProperties["type"]?.equals("double", ignoreCase = true) == true &&
        (actualReplaceable || isDoubleSlabPending(expectedBlockId, expectedProperties, actualBlockId, actualProperties))

    fun requiredPredecessor(pos: BuildCellPos, blockId: String, properties: Map<String, String>): BuildCellPos? {
        val half = properties["half"]?.lowercase(Locale.ROOT)
        if ((blockId.endsWith("_door") || blockId.endsWith("_plant") || blockId in setOf(
                "minecraft:sunflower", "minecraft:lilac", "minecraft:rose_bush", "minecraft:peony", "minecraft:tall_grass", "minecraft:large_fern",
            )) && half == "upper") return pos.offset(0, -1, 0)
        if (blockId == "minecraft:bed" || blockId.endsWith("_bed")) {
            if (properties["part"]?.lowercase(Locale.ROOT) == "head") {
                val (dx, dz) = when (properties["facing"]?.lowercase(Locale.ROOT)) {
                    "north" -> 0 to 1
                    "south" -> 0 to -1
                    "east" -> -1 to 0
                    "west" -> 1 to 0
                    else -> return null
                }
                return pos.offset(dx, 0, dz)
            }
        }
        return null
    }

    fun requiresTwoSlabItems(blockId: String, itemId: String?, properties: Map<String, String>): Boolean =
        blockId.endsWith("_slab") && itemId == blockId && properties["type"]?.equals("double", ignoreCase = true) == true

    fun materialCost(blockId: String, itemId: String?, properties: Map<String, String>): Int {
        if (itemId == null) return 0
        if (requiresTwoSlabItems(blockId, itemId, properties)) return 2
        if (requiredPredecessor(BuildCellPos(0, 0, 0), blockId, properties) != null) return 0
        return 1
    }

    fun isDoubleSlabPending(blockId: String, expected: Map<String, String>, actualBlockId: String, actual: Map<String, String>): Boolean =
        blockId == actualBlockId && blockId.endsWith("_slab") && expected["type"]?.equals("double", true) == true &&
            actual["type"]?.lowercase(Locale.ROOT) in setOf("top", "bottom")

    fun isChestProvisional(
        blockId: String,
        expected: Map<String, String>,
        actualBlockId: String,
        actual: Map<String, String>,
        partnerExpected: Boolean,
    ): Boolean = blockId == actualBlockId && (blockId == "minecraft:chest" || blockId == "minecraft:trapped_chest") &&
        expected["type"]?.lowercase(Locale.ROOT) in setOf("left", "right") &&
        actual["type"]?.equals("single", true) == true && partnerExpected

    fun chestPartner(pos: BuildCellPos, properties: Map<String, String>): BuildCellPos? {
        val facing = properties["facing"]?.lowercase(Locale.ROOT) ?: return null
        val side = properties["type"]?.lowercase(Locale.ROOT) ?: return null
        val (dx, dz) = when (facing) {
            "north" -> if (side == "left") 1 to 0 else if (side == "right") -1 to 0 else return null
            "south" -> if (side == "left") -1 to 0 else if (side == "right") 1 to 0 else return null
            "east" -> if (side == "left") 0 to 1 else if (side == "right") 0 to -1 else return null
            "west" -> if (side == "left") 0 to -1 else if (side == "right") 0 to 1 else return null
            else -> return null
        }
        return pos.offset(dx, 0, dz)
    }

    /** Returns the still-missing half that must follow a provisional single chest. */
    fun provisionalChestPartnerTarget(
        pos: BuildCellPos,
        properties: Map<String, String>,
        missing: Set<BuildCellPos>,
    ): BuildCellPos? = chestPartner(pos, properties)?.takeIf { it in missing }
}

data class SchematicMaterialNeed(
    val itemId: String,
    val blockId: String,
    val properties: Map<String, String>,
)

/** Shares the planner's atomic-structure material costs with bounded inventory purchasing. */
object SchematicProcurementRules {
    fun requiredCounts(needs: Iterable<SchematicMaterialNeed>): Map<String, Int> = buildMap {
        for (need in needs) {
            val cost = SchematicStructureRules.materialCost(need.blockId, need.itemId, need.properties)
            if (cost > 0) put(need.itemId, (get(need.itemId) ?: 0) + cost)
        }
    }

    fun shortage(required: Int, inventoryCount: Int): Int = (required - inventoryCount.coerceAtLeast(0)).coerceAtLeast(0)

    fun roundedTarget(shortage: Int, maxStackSize: Int): Int {
        if (shortage <= 0) return 0
        if (shortage <= 16 || maxStackSize <= 16) return shortage
        return ((shortage + maxStackSize - 1) / maxStackSize) * maxStackSize
    }

    fun inventoryRoom(
        wanted: dev.donut.addon.expansion.automation.core.InventorySlotValue,
        maxStackSize: Int,
        inventory: Iterable<dev.donut.addon.expansion.automation.core.InventorySlotValue>,
        compatibleLore: (List<String>) -> List<String> = { it },
    ): Int {
        if (wanted.itemId.isBlank() || maxStackSize <= 0) return 0
        var room = 0L
        for (slot in inventory) {
            if (slot.itemId.isBlank() || slot.count <= 0) {
                room += maxStackSize
                continue
            }
            val compatible = slot.itemId == wanted.itemId &&
                slot.procurementFingerprint == wanted.procurementFingerprint &&
                slot.name.equals(wanted.name, ignoreCase = true) &&
                slot.actualEnchantments == wanted.actualEnchantments &&
                compatibleLore(slot.lore).map { it.trim().lowercase(Locale.ROOT) } ==
                compatibleLore(wanted.lore).map { it.trim().lowercase(Locale.ROOT) }
            if (compatible) room += (maxStackSize - slot.count).coerceAtLeast(0)
        }
        return room.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun purchaseTarget(shortage: Int, availableRoom: Int, maxStackSize: Int, waterBucket: Boolean = false): Int {
        if (shortage <= 0 || availableRoom <= 0) return 0
        val safeRoom = if (waterBucket) minOf(availableRoom - 4, 8) else availableRoom
        if (safeRoom <= 0) return 0
        return minOf(roundedTarget(shortage, maxStackSize), safeRoom)
    }
}

object SchematicSurplusRules {
    data class SlotStack(val slot: Int, val value: InventorySlotValue)

    /** Exact, count-independent identity used to reserve the user's baseline stacks. */
    fun stackIdentity(value: InventorySlotValue): String = DonutSellIdentity.of(value)

    /**
     * A purchase is assigned to one exact inventory identity only when its server-acknowledged
     * growth equals the observed current-minus-baseline growth. Ambiguous or unrelated changes are
     * deliberately left unowned so they can never become sale candidates.
     */
    fun identifyAcknowledgedPurchaseGrowth(
        baseline: Map<String, InventorySlotValue>,
        current: Map<String, InventorySlotValue>,
        allowedItemIds: Set<String>,
        acknowledgedCount: Int,
    ): Map<String, Int>? {
        if (acknowledgedCount <= 0) return emptyMap()
        val before = baseline.values.groupBy(::stackIdentity).mapValues { (_, values) -> values.sumOf { it.count } }
        val after = current.values.groupBy(::stackIdentity).mapValues { (_, values) -> values.sumOf { it.count } }
        val identityToItem = current.values.associateBy(::stackIdentity).mapValues { (_, value) -> value.itemId }
        val increases = after.mapNotNull { (identity, count) ->
            val itemId = identityToItem[identity] ?: return@mapNotNull null
            val delta = count - (before[identity] ?: 0)
            if (itemId in allowedItemIds && delta > 0) identity to delta else null
        }.toMap()
        return increases.takeIf { it.size == 1 && it.values.single() == acknowledgedCount }
    }

    fun saleEligibleSlots(
        inventory: List<SlotStack>,
        baselineCountsByIdentity: Map<String, Int>,
        acquiredCountsByIdentity: Map<String, Int>,
    ): Map<Int, String> {
        val currentByIdentity = inventory.groupBy { stackIdentity(it.value) }
            .mapValues { (_, stacks) -> stacks.sumOf { it.value.count } }
        val result = linkedMapOf<Int, String>()
        for ((identity, acquired) in acquiredCountsByIdentity) {
            val itemId = inventory.firstOrNull { stackIdentity(it.value) == identity }?.value?.itemId ?: continue
            if (itemId in setOf("minecraft:totem_of_undying", "minecraft:water_bucket", "minecraft:lava_bucket")) continue
            var surplus = minOf(acquired, (currentByIdentity[identity].orZero() - baselineCountsByIdentity[identity].orZero()).coerceAtLeast(0))
            if (surplus <= 0) continue
            for (stack in inventory.filter { stackIdentity(it.value) == identity }.sortedByDescending { it.value.count }) {
                if (stack.value.count <= surplus) {
                    result[stack.slot] = itemId
                    surplus -= stack.value.count
                }
            }
        }
        return result
    }

    private fun Int?.orZero(): Int = this ?: 0
}

object SchematicSneakRules {
    private val clickUsingBlocks = setOf(
        "minecraft:anvil", "minecraft:chipped_anvil", "minecraft:damaged_anvil", "minecraft:bell",
        "minecraft:daylight_detector", "minecraft:note_block", "minecraft:lever", "minecraft:repeater",
        "minecraft:comparator",
    )

    fun needsSneak(blockId: String, hasBlockEntity: Boolean): Boolean = hasBlockEntity ||
        blockId in clickUsingBlocks || blockId.endsWith("_door") || blockId.endsWith("_trapdoor") ||
        blockId.endsWith("_fence_gate") || blockId.endsWith("_button") ||
        blockId == "minecraft:crafting_table" || blockId.endsWith("_crafting_table")

    fun settled(shiftDown: Boolean, stableTicks: Int): Boolean = shiftDown && stableTicks >= 1

    fun released(shiftDown: Boolean, stableTicks: Int): Boolean = !shiftDown && stableTicks >= 1

    fun restoreShift(
        manualBeforeLease: Boolean,
        physicalDownAtAcquire: Boolean,
        physicalDownNow: Boolean,
    ): Boolean = if (manualBeforeLease && !physicalDownAtAcquire) true else physicalDownNow
}

/** Source-style crafter adjustment: only empty slots are toggled and each server data value is checked. */
object SchematicCrafterRules {
    data class Toggle(val slot: Int, val enabled: Boolean)

    fun toggles(expectedDisabled: List<Boolean>, currentDisabled: List<Boolean>, occupiedSlots: Set<Int>): List<Toggle> {
        if (expectedDisabled.size != 9 || currentDisabled.size != 9) return emptyList()
        return (0 until 9).asSequence()
            .filter { currentDisabled[it] != expectedDisabled[it] && it !in occupiedSlots }
            .map { Toggle(it, enabled = !expectedDisabled[it]) }
            .toList()
    }

    fun maskMatches(expectedDisabled: List<Boolean>, currentDisabled: List<Boolean>): Boolean =
        expectedDisabled.size == 9 && currentDisabled.size == 9 && expectedDisabled == currentDisabled

    fun dataValueMatches(expectedDisabled: Boolean, serverValue: Int): Boolean =
        serverValue == if (expectedDisabled) 1 else 0

    fun allDataAcknowledged(requiredSlots: Set<Int>, acknowledgedSlots: Set<Int>): Boolean =
        requiredSlots.all { it in 0..8 && it in acknowledgedSlots }

    fun dataBelongsToAnnouncedMenu(announcedMenuId: Int, packetMenuId: Int, slot: Int): Boolean =
        announcedMenuId >= 0 && packetMenuId == announcedMenuId && slot in 0..8

    fun localMenuMatchesAnnouncement(
        announcedMenuId: Int,
        localMenuId: Int,
        isCrafterMenu: Boolean,
        hasContainerScreen: Boolean,
    ): Boolean = announcedMenuId >= 0 && localMenuId == announcedMenuId && isCrafterMenu && hasContainerScreen
}

object SchematicCrafterProgressRules {
    fun nextReady(
        expectedMasks: Map<BuildCellPos, List<Boolean>>,
        completed: Set<BuildCellPos>,
        isPlaced: (BuildCellPos) -> Boolean,
    ): Pair<BuildCellPos, List<Boolean>>? = expectedMasks.entries.firstOrNull { (pos, _) ->
        pos !in completed && isPlaced(pos)
    }?.let { it.key to it.value }
}

object SchematicFoodRules {
    private val excluded = setOf(
        "minecraft:chorus_fruit", "minecraft:rotten_flesh", "minecraft:spider_eye",
        "minecraft:poisonous_potato", "minecraft:pufferfish", "minecraft:suspicious_stew",
        "minecraft:enchanted_golden_apple", "minecraft:golden_apple", "minecraft:chicken",
    )

    fun eligible(itemId: String, nutrition: Int, hasUseRemainder: Boolean): Boolean =
        nutrition > 0 && itemId !in excluded && !hasUseRemainder

    fun serverConsumptionAcknowledged(
        original: dev.donut.addon.expansion.automation.core.InventorySlotValue,
        current: dev.donut.addon.expansion.automation.core.InventorySlotValue,
    ): Boolean {
        if (original.count <= 0 || current.count !in 0 until original.count) return false
        if (current.count == 0) return current.itemId.isEmpty()
        return current.itemId == original.itemId && current.name == original.name && current.lore == original.lore &&
            current.componentFingerprint == original.componentFingerprint && current.actualEnchantments == original.actualEnchantments
    }
}

object SchematicRefillRules {
    fun eligibleEmptyBucket(
        current: dev.donut.addon.expansion.automation.core.InventorySlotValue,
        defaultEmptyBucket: dev.donut.addon.expansion.automation.core.InventorySlotValue,
    ): Boolean = current == defaultEmptyBucket && current.count == 1

    fun waterBucketAcknowledged(
        before: dev.donut.addon.expansion.automation.core.InventorySlotValue,
        current: dev.donut.addon.expansion.automation.core.InventorySlotValue,
        defaultEmptyBucket: dev.donut.addon.expansion.automation.core.InventorySlotValue,
        defaultWaterBucket: dev.donut.addon.expansion.automation.core.InventorySlotValue,
    ): Boolean = before == defaultEmptyBucket && current == defaultWaterBucket

    fun unchangedUseAcknowledged(
        before: dev.donut.addon.expansion.automation.core.InventorySlotValue,
        current: dev.donut.addon.expansion.automation.core.InventorySlotValue,
        baselineStateId: Int,
        observedStateId: Int,
    ): Boolean = before == current && isAfter(observedStateId, baselineStateId)

    fun serverUpdateAfter(baselineStateId: Int, observedStateId: Int): Boolean = isAfter(observedStateId, baselineStateId)

    private fun isAfter(candidate: Int, baseline: Int): Boolean {
        val delta = ((candidate and 0x7fff) - (baseline and 0x7fff)) and 0x7fff
        return delta in 1 until 0x4000
    }
}

object SchematicWaterlogRules {
    fun needsWaterlogging(structuralMatch: Boolean, expectedWaterlogged: Boolean, actualWaterlogged: Boolean): Boolean =
        structuralMatch && expectedWaterlogged && !actualWaterlogged

    fun acknowledgmentsComplete(blockStateAcknowledged: Boolean, inventoryRemainderAcknowledged: Boolean): Boolean =
        blockStateAcknowledged && inventoryRemainderAcknowledged

    fun waterBucketUseAcknowledged(
        before: dev.donut.addon.expansion.automation.core.InventorySlotValue,
        current: dev.donut.addon.expansion.automation.core.InventorySlotValue,
        defaultWaterBucket: dev.donut.addon.expansion.automation.core.InventorySlotValue,
        defaultEmptyBucket: dev.donut.addon.expansion.automation.core.InventorySlotValue,
    ): Boolean = before == defaultWaterBucket && current == defaultEmptyBucket
}

object SchematicScaffoldRules {
    private val dynamicStateProperties = setOf("distance")
    data class Candidate(
        val itemId: String,
        val transparentBlock: Boolean,
        val leafItem: Boolean,
        val slot: Int = -1,
        val count: Int = 1,
        val hasBlockState: Boolean = false,
        val hasBlockEntityData: Boolean = false,
    )

    fun eligible(candidate: Candidate): Boolean = candidate.count > 0 &&
        (candidate.leafItem || candidate.transparentBlock) &&
        !candidate.hasBlockState && !candidate.hasBlockEntityData

    fun priority(candidate: Candidate): Int? = when {
        candidate.itemId == "minecraft:glass" -> 0
        candidate.leafItem -> 1
        candidate.transparentBlock -> 2
        else -> null
    }

    fun preferred(candidates: Iterable<Candidate>): Candidate? = candidates
        .filter(::eligible)
        .mapNotNull { candidate -> priority(candidate)?.let { it to candidate } }
        .minWithOrNull(compareBy<Pair<Int, Candidate>>({ it.first }, { it.second.itemId }))?.second

    fun cellCanBeScaffolded(
        replaceable: Boolean,
        projectedAir: Boolean,
        emptyFluid: Boolean,
        intersectsPlayer: Boolean,
        observerSensitive: Boolean,
    ): Boolean = replaceable && projectedAir && emptyFluid && !intersectsPlayer && !observerSensitive

    fun targetNeedsFaceSupport(nativeCanSurvive: Boolean): Boolean = !nativeCanSurvive

    fun walkwayStepAdvances(current: BuildCellPos, next: BuildCellPos, target: BuildCellPos): Boolean {
        if (!SchematicRouteRules.allows(current, next) || distanceSquared(next, target) >= distanceSquared(current, target)) return false
        val targetDx = target.x - current.x
        val targetDz = target.z - current.z
        val moveDx = next.x - current.x
        val moveDz = next.z - current.z
        return targetDx == 0 && targetDz == 0 || targetDx * moveDx + targetDz * moveDz > 0
    }

    /** Bottom then top block needed when a one-step higher stand has no adjacent support. */
    fun verticalRiseSupportChain(standFeet: BuildCellPos): List<BuildCellPos> = listOf(
        standFeet.offset(0, -2, 0),
        standFeet.offset(0, -1, 0),
    )

    fun hasAdjacentShapeSupport(target: BuildCellPos, solidShapes: Set<BuildCellPos>): Boolean =
        directionOffsets.any { (dx, dy, dz) -> target.offset(dx, dy, dz) in solidShapes }

    private val directionOffsets = listOf(
        Triple(1, 0, 0), Triple(-1, 0, 0), Triple(0, 1, 0),
        Triple(0, -1, 0), Triple(0, 0, 1), Triple(0, 0, -1),
    )

    private fun distanceSquared(first: BuildCellPos, second: BuildCellPos): Long {
        val dx = first.x.toLong() - second.x
        val dy = first.y.toLong() - second.y
        val dz = first.z.toLong() - second.z
        return dx * dx + dy * dy + dz * dz
    }

    fun serverBlockMatchesScaffold(expectedItemId: String, actualBlockIsLeaves: Boolean, actualBlockIsTransparent: Boolean): Boolean =
        expectedItemId.isNotBlank() && (actualBlockIsLeaves || actualBlockIsTransparent)

    fun acknowledgedStateMatches(
        expectedBlockId: String,
        expectedProperties: Map<String, String>,
        actualBlockId: String,
        actualProperties: Map<String, String>,
    ): Boolean = expectedBlockId == actualBlockId && expectedProperties
        .filterKeys { it !in dynamicStateProperties }
        .all { (name, value) -> actualProperties[name] == value }

    fun inventoryDecrementAcknowledged(
        before: dev.donut.addon.expansion.automation.core.InventorySlotValue,
        after: dev.donut.addon.expansion.automation.core.InventorySlotValue,
    ): Boolean {
        if (before.count <= 0 || after.count != before.count - 1) return false
        if (after.count == 0) return after.itemId.isEmpty()
        return before.itemId == after.itemId && before.name == after.name && before.lore == after.lore &&
            before.componentFingerprint == after.componentFingerprint && before.actualEnchantments == after.actualEnchantments
    }

    fun mayRemoveOwnedScaffold(
        ownedByThisRun: Boolean,
        exactAcknowledgedStateStillPresent: Boolean,
        dependentTargetComplete: Boolean,
        projectedSchematicCellIsAir: Boolean,
        playerIntersects: Boolean,
        otherBlocksMayDependOnIt: Boolean,
    ): Boolean = ownedByThisRun && exactAcknowledgedStateStillPresent && dependentTargetComplete &&
        projectedSchematicCellIsAir && !playerIntersects && !otherBlocksMayDependOnIt
}

/** Pure guards shared by the bounded jump/place-underfoot climb controller and its tests. */
object SchematicPillarRules {
    fun canStart(
        grounded: Boolean,
        targetAboveFeet: Boolean,
        horizontalDistance: Int,
        maxHorizontalDistance: Int,
        completedRuns: Int,
        maxRuns: Int,
    ): Boolean = grounded && targetAboveFeet && horizontalDistance in 0..maxHorizontalDistance && completedRuns < maxRuns

    fun fullHeightFloor(shapeMaxY: Double): Boolean = shapeMaxY >= 0.99

    fun riseCount(targetRise: Int, availableBlocks: Int, perRunLimit: Int): Int =
        minOf(targetRise.coerceAtLeast(0), availableBlocks.coerceAtLeast(0), perRunLimit.coerceAtLeast(0))

    fun centered(offsetX: Double, offsetZ: Double, tolerance: Double): Boolean =
        kotlin.math.abs(offsetX) < tolerance && kotlin.math.abs(offsetZ) < tolerance

    fun reachedPlacementWindow(feetY: Double, targetBlockY: Int, clearance: Double = 1.02): Boolean =
        feetY >= targetBlockY + clearance

    fun placementProven(blockUpdateAcknowledged: Boolean, inventoryDecrementAcknowledged: Boolean): Boolean =
        blockUpdateAcknowledged && inventoryDecrementAcknowledged

    fun mayRetryUnsent(attempts: Int, maxAttempts: Int): Boolean = attempts < maxAttempts
}

/** Face candidates are considered in caller order, but an occluded first face cannot starve later faces. */
fun <T> firstVisibleSupportCandidate(candidates: Iterable<T>, isVisible: (T) -> Boolean): T? =
    candidates.firstOrNull(isVisible)

object SchematicStateRules {
    private val orientationProperties = setOf("facing", "horizontal_facing", "axis")
    private val placedProperties = setOf(
        "facing", "horizontal_facing", "facing_hopper", "vertical_direction", "axis", "horizontal_axis",
        "half", "double_block_half", "type", "rotation", "face", "orientation", "hinge", "part",
        "attachment", "hanging", "bell_attachment",
    )
    private val clickedProperties = setOf("delay", "mode", "mode_comparator", "note", "open", "inverted")
    private val plantBlockAliases = mapOf(
        "minecraft:kelp_plant" to "minecraft:kelp",
        "minecraft:twisting_vines_plant" to "minecraft:twisting_vines",
        "minecraft:weeping_vines_plant" to "minecraft:weeping_vines",
        "minecraft:cave_vines_plant" to "minecraft:cave_vines",
    )
    private val directionalFaceBlocks = setOf("minecraft:vine", "minecraft:glow_lichen", "minecraft:sculk_vein")
    private val faceProperties = setOf("north", "south", "east", "west", "up", "down")

    fun canonicalBlockId(blockId: String): String = plantBlockAliases[blockId] ?: blockId

    fun matches(
        expectedBlock: String,
        expectedProperties: Map<String, String>,
        actualBlock: String,
        actualProperties: Map<String, String>,
        allowRelaxedOrientation: Boolean,
    ): Boolean {
        if (canonicalBlockId(expectedBlock) != canonicalBlockId(actualBlock)) return false
        return expectedProperties.all { (name, value) ->
            val significant = name in placedProperties || clickable(expectedBlock, name) ||
                expectedBlock == "minecraft:lever" && name == "powered" && value.equals("true", ignoreCase = true) ||
                expectedBlock in directionalFaceBlocks && name in faceProperties
            !significant || allowRelaxedOrientation && name in orientationProperties || actualProperties[name] == value
        }
    }

    fun placementCompatible(
        expectedBlock: String,
        expectedProperties: Map<String, String>,
        actualBlock: String,
        actualProperties: Map<String, String>,
        allowRelaxedOrientation: Boolean = false,
    ): Boolean {
        if (canonicalBlockId(expectedBlock) != canonicalBlockId(actualBlock)) return false
        return expectedProperties.all { (name, value) ->
            if (expectedBlock in directionalFaceBlocks && name in faceProperties) {
                !(actualProperties[name]?.equals("true", true) == true && value.equals("false", true))
            } else name !in placedProperties || allowRelaxedOrientation && name in orientationProperties || actualProperties[name] == value
        }
    }

    fun needsControlAdjustment(
        expectedBlock: String,
        expectedProperties: Map<String, String>,
        actualProperties: Map<String, String>,
        enabledControls: Set<String> = clickedProperties,
    ): Boolean = expectedProperties.any { (name, value) ->
        name in enabledControls && clickable(expectedBlock, name) && actualProperties[name] != value
    } || "powered" in enabledControls && expectedBlock == "minecraft:lever" &&
        expectedProperties["powered"]?.equals("true", ignoreCase = true) == true && actualProperties["powered"]?.equals("false", ignoreCase = true) == true

    fun isClickableProperty(blockId: String, property: String): Boolean = when {
        property !in clickedProperties -> false
        property != "open" -> true
        blockId == "minecraft:iron_door" || blockId == "minecraft:iron_trapdoor" -> false
        blockId.endsWith("_door") || blockId.endsWith("_trapdoor") || blockId.endsWith("_fence_gate") -> true
        else -> false
    }

    private fun clickable(blockId: String, property: String): Boolean = isClickableProperty(blockId, property)

    fun isObstructed(
        expectedBlock: String,
        actualBlock: String,
        actualCanBeReplaced: Boolean,
        partialDoubleSlab: Boolean,
    ): Boolean = actualBlock != expectedBlock && !actualCanBeReplaced && !partialDoubleSlab
}

object SchematicCompletionRules {
    fun shouldStayResumable(pendingVirtualCells: Int, scanTruncated: Boolean): Boolean =
        pendingVirtualCells > 0 && !scanTruncated
}

/** Source-shaped, bounded guards for the trapped-player underfoot dig-out recovery. */
object SchematicDigOutRules {
    const val MAX_ATTEMPTS = 2
    const val ACK_TIMEOUT_TICKS = 100
    const val RETRY_DELAY_TICKS = 20

    fun canStartSourceDigOut(routeVisited: Int, escapeMaterialCount: Int): Boolean =
        routeVisited <= 3 && escapeMaterialCount >= 2

    fun sourceBlockEligible(
        isAir: Boolean,
        replaceable: Boolean,
        fullBlockCollision: Boolean,
        isContainer: Boolean,
        destroySpeed: Float,
        adjacentFluidsEmpty: Boolean,
    ): Boolean = !isAir && !replaceable && !fullBlockCollision && !isContainer &&
        destroySpeed >= 0f && adjacentFluidsEmpty

    fun safeRecoveryEligible(
        withinInteractionReach: Boolean,
        nativeRayHitsTarget: Boolean,
        attempts: Int,
    ): Boolean = withinInteractionReach && nativeRayHitsTarget && attempts < MAX_ATTEMPTS

    fun canUseAutomaticExit(stableFloorBelow: Boolean, clearHeadroom: Boolean, safeRouteExists: Boolean): Boolean =
        stableFloorBelow && clearHeadroom && safeRouteExists

    fun shouldAcknowledgeAirUpdate(ordinal: Long, baselineOrdinal: Long, isAir: Boolean): Boolean =
        ordinal > baselineOrdinal && isAir
}

object SchematicSemanticRules {
    fun changed(previousAvailable: Boolean, previousState: String?, nextAvailable: Boolean, nextState: String?): Boolean =
        previousAvailable && nextAvailable && previousState != nextState
}

object SchematicInventoryRules {
    const val OFFHAND_MENU_SLOT = 45

    fun swapExpectation(
        connectionId: Long,
        menuId: Int,
        baselineStateId: Int,
        sourceMenuSlot: Int,
        destinationMenuSlot: Int,
        sourceBefore: dev.donut.addon.expansion.automation.core.InventorySlotValue,
        destinationBefore: dev.donut.addon.expansion.automation.core.InventorySlotValue,
    ): dev.donut.addon.expansion.automation.core.InventorySwapExpectation =
        dev.donut.addon.expansion.automation.core.InventorySwapExpectation(
            connectionId, menuId, baselineStateId, sourceMenuSlot, destinationMenuSlot, sourceBefore, destinationBefore,
        )

    fun canRestoreStagedItem(originalItemId: String, currentItemId: String): Boolean =
        currentItemId == originalItemId ||
            originalItemId == "minecraft:water_bucket" && currentItemId == "minecraft:bucket" ||
            originalItemId == "minecraft:lava_bucket" && currentItemId == "minecraft:bucket"

    /**
     * Accepts only a remainder of the exact staged stack. The stack may shrink while the builder
     * places blocks, eats, or uses a filled bucket, but it may never grow or change components.
     */
    @JvmOverloads
    fun isOwnedRestoreRemainder(
        original: dev.donut.addon.expansion.automation.core.InventorySlotValue,
        current: dev.donut.addon.expansion.automation.core.InventorySlotValue,
        defaultFilledBucket: dev.donut.addon.expansion.automation.core.InventorySlotValue? = null,
        defaultEmptyBucket: dev.donut.addon.expansion.automation.core.InventorySlotValue? = null,
        defaultWaterBucket: dev.donut.addon.expansion.automation.core.InventorySlotValue? = null,
    ): Boolean {
        if (original.count <= 0 || current.count !in 0..original.count) return false
        if (current.count == 0) return current.itemId.isEmpty()
        if (original.itemId != current.itemId) {
            val isFilledBucket = original.itemId == "minecraft:water_bucket" || original.itemId == "minecraft:lava_bucket"
            val filledToEmpty = isFilledBucket && current.itemId == "minecraft:bucket" && original.count == 1 && current.count == 1 &&
                defaultFilledBucket == original && defaultEmptyBucket?.copy(count = current.count) == current
            val emptyToWater = original.itemId == "minecraft:bucket" && current.itemId == "minecraft:water_bucket" &&
                original.count == 1 && current.count == 1 && defaultEmptyBucket == original &&
                defaultWaterBucket?.copy(count = current.count) == current
            return filledToEmpty || emptyToWater
        }
        return current.name == original.name && current.lore == original.lore &&
            current.componentFingerprint == original.componentFingerprint &&
            current.actualEnchantments == original.actualEnchantments
    }

    fun preferredFoodSlot(
        hotbarSlots: Iterable<Int>,
        storageSlots: Iterable<Int>,
        hasFood: (Int) -> Boolean,
    ): Int? = hotbarSlots.firstOrNull(hasFood) ?: storageSlots.firstOrNull(hasFood)

    fun shouldEquipTotem(keepEnabled: Boolean, offhandItemId: String?): Boolean =
        keepEnabled && offhandItemId != "minecraft:totem_of_undying"
}

/** Keeps cancellation restores in the same live session; world/player/connection changes abandon them. */
object SchematicRestoreRules {
    fun shouldRestoreOnDisable(
        moduleEnabled: Boolean,
        forcedCleanup: Boolean,
        sameWorld: Boolean,
        samePlayer: Boolean,
        sameConnection: Boolean,
        stagedLeases: Int,
        pendingSwap: Boolean,
    ): Boolean = !moduleEnabled && !forcedCleanup && sameWorld && samePlayer && sameConnection &&
        (stagedLeases > 0 || pendingSwap)
}

/** Suppresses repeated no-action failures while retaining the normal three/eight retry policy. */
class BuildActionFailureGate(private val intervalTicks: Long = 20) {
    private val lastFailure = mutableMapOf<BuildCellPos, Long>()

    fun shouldCount(pos: BuildCellPos, tick: Long): Boolean {
        val previous = lastFailure[pos]
        if (previous != null && tick - previous < intervalTicks) return false
        lastFailure[pos] = tick
        return true
    }

    fun clear() = lastFailure.clear()
}

/** Cross-thread packet inbox with a hard memory bound and stable nonblocking drains. */
class BoundedSchematicInbox<T>(private val capacity: Int) {
    private val queue = ConcurrentLinkedQueue<T>()
    private val size = AtomicInteger()

    init { require(capacity > 0) }

    fun offer(value: T): Boolean {
        while (true) {
            val count = size.get()
            if (count >= capacity) return false
            if (size.compareAndSet(count, count + 1)) break
        }
        queue.offer(value)
        return true
    }

    fun poll(): T? {
        val value = queue.poll() ?: return null
        size.decrementAndGet()
        return value
    }

    fun clear() {
        queue.clear()
        size.set(0)
    }

    fun size(): Int = size.get()
}

enum class PlacementStatus { IDLE, AIMING, WAITING_FOR_SERVER, CONFIRMED, RETRYING, UNRESOLVED, CANCELLED }

data class PlacementProgress(val status: PlacementStatus, val attempts: Int, val lastReason: String = "")

/** Server confirmation owns progress: prediction alone cannot advance this machine. */
class PlacementConfirmationMachine(private val maxRetries: Int = 2) {
    private var progress = PlacementProgress(PlacementStatus.IDLE, 0)
    fun current(): PlacementProgress = progress
    fun begin() { progress = PlacementProgress(PlacementStatus.AIMING, progress.attempts) }
    fun sent() { if (progress.status == PlacementStatus.AIMING || progress.status == PlacementStatus.RETRYING) progress = progress.copy(status = PlacementStatus.WAITING_FOR_SERVER) }
    fun serverStateMatches(matches: Boolean): PlacementProgress {
        if (progress.status != PlacementStatus.WAITING_FOR_SERVER) return progress
        progress = if (matches) progress.copy(status = PlacementStatus.CONFIRMED, lastReason = "")
        else retryOrStop("server block state did not match")
        return progress
    }
    fun timeout(reason: String): PlacementProgress {
        if (progress.status == PlacementStatus.WAITING_FOR_SERVER || progress.status == PlacementStatus.AIMING || progress.status == PlacementStatus.RETRYING) {
            progress = retryOrStop(reason)
        }
        return progress
    }
    fun cancel(): PlacementProgress {
        progress = progress.copy(status = PlacementStatus.CANCELLED)
        return progress
    }
    fun reset() { progress = PlacementProgress(PlacementStatus.IDLE, 0) }
    private fun retryOrStop(reason: String): PlacementProgress {
        val next = progress.attempts + 1
        return PlacementProgress(if (next <= maxRetries) PlacementStatus.RETRYING else PlacementStatus.UNRESOLVED, next, reason)
    }
}

enum class RetryDecision { RETRY, DEFER, UNRESOLVED }

/** Position-local retry counts prevent one ghost block from consuming another block's retry budget. */
class BuildRetryLedger(private val stubbornAfter: Int = 3, private val maxFailures: Int = 8) {
    private val failures = linkedMapOf<BuildCellPos, Int>()
    fun recordFailure(pos: BuildCellPos): RetryDecision {
        val count = failures.merge(pos, 1, Int::plus) ?: 1
        return when {
            count >= maxFailures -> RetryDecision.UNRESOLVED
            count == stubbornAfter -> RetryDecision.DEFER
            else -> RetryDecision.RETRY
        }
    }
    fun failures(pos: BuildCellPos): Int = failures[pos] ?: 0
    fun unresolved(): Set<BuildCellPos> = failures.filterValues { it >= maxFailures }.keys
    fun inStubbornPhase(pos: BuildCellPos): Boolean = (failures[pos] ?: 0) >= stubbornAfter
    fun clearPosition(pos: BuildCellPos) { failures.remove(pos) }
    fun clear() = failures.clear()
}

object BuildMoneyRules {
    private val money = Regex("^\\s*\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmb])?\\s*$", RegexOption.IGNORE_CASE)

    fun parse(raw: String): Long? {
        val match = money.matchEntire(raw) ?: return null
        val amount = match.groupValues[1].replace(",", "").toBigDecimalOrNull() ?: return null
        val multiplier = when (match.groupValues[2].lowercase(Locale.ROOT)) {
            "k" -> 1_000L
            "m" -> 1_000_000L
            "b" -> 1_000_000_000L
            else -> 1L
        }
        return runCatching { amount.multiply(multiplier.toBigDecimal()).longValueExact() }.getOrNull()?.takeIf { it > 0 }
    }

    fun mayBuy(totalPrice: Long, maxPerListing: Long, spent: Long, totalBudget: Long): Boolean =
        totalPrice > 0 && maxPerListing > 0 && totalPrice <= maxPerListing &&
            (totalBudget <= 0 || spent <= totalBudget - totalPrice)
}

enum class SchematicAuctionOutcome { PURCHASED, LISTING_GONE, OWN_LISTING, INSUFFICIENT_FUNDS }

data class ParsedSchematicAuctionOutcome(
    val outcome: SchematicAuctionOutcome,
    val reportedName: String = "",
    val count: Int? = null,
    val totalPrice: BigDecimal? = null,
)

/** Parses only explicit server transaction outcomes; malformed purchase receipts remain incomplete. */
object SchematicAuctionReceiptParser {
    private val boughtCount = Regex("^(\\d{1,4})\\s*x?\\s+")
    private val moneyToken = Regex("\\$?[ \\t]*([0-9][0-9,]*(?:\\.[0-9]+)?)[ \\t]*([kmb])?(?![a-z])", RegexOption.IGNORE_CASE)
    private val formatting = Regex("(?i)§.")
    private const val SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘʀѕꜱᴛᴜᴠᴡʏᴢ"
    private const val SMALL_CAPS_PLAIN = "abcdefghijklmnoprsstuvwyz"

    fun parse(raw: String): ParsedSchematicAuctionOutcome? {
        val line = normalize(raw)
        if (line.isBlank()) return null
        if (line.startsWith("you bought")) {
            var rest = line.removePrefix("you bought").trim()
            var count = 1
            boughtCount.find(rest)?.let { match ->
                count = match.groupValues[1].toIntOrNull()?.coerceAtLeast(1) ?: 1
                rest = rest.substring(match.range.last + 1)
            }
            val forAt = rest.lastIndexOf(" for ")
            val reportedName = (if (forAt >= 0) rest.substring(0, forAt) else rest).trim()
            val total = if (forAt >= 0) parseFirstMoney(rest.substring(forAt + 5)) else null
            return ParsedSchematicAuctionOutcome(
                SchematicAuctionOutcome.PURCHASED,
                reportedName = reportedName,
                count = count,
                totalPrice = total,
            )
        }
        return when {
            "already bought" in line || "no longer available" in line || "not available" in line ->
                ParsedSchematicAuctionOutcome(SchematicAuctionOutcome.LISTING_GONE)
            "enough money" in line -> ParsedSchematicAuctionOutcome(SchematicAuctionOutcome.INSUFFICIENT_FUNDS)
            "your own" in line -> ParsedSchematicAuctionOutcome(SchematicAuctionOutcome.OWN_LISTING)
            else -> null
        }
    }

    private fun normalize(raw: String): String = buildString(raw.length) {
        val cleaned = formatting.replace(raw, "")
        for (character in cleaned) {
            val index = SMALL_CAPS.indexOf(character)
            when {
                index >= 0 && index < SMALL_CAPS_PLAIN.length -> append(SMALL_CAPS_PLAIN[index])
                character.code == 0x27a4 || character.code == 0x2192 -> append('-')
                else -> append(character)
            }
        }
    }.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

    private fun parseFirstMoney(text: String): BigDecimal? {
        val match = moneyToken.find(text) ?: return null
        val amount = match.groupValues[1].replace(",", "").toBigDecimalOrNull() ?: return null
        val multiplier = when (match.groupValues[2].lowercase(Locale.ROOT)) {
            "k" -> BigDecimal("1000")
            "m" -> BigDecimal("1000000")
            "b" -> BigDecimal("1000000000")
            else -> BigDecimal.ONE
        }
        return amount.multiply(multiplier).setScale(0, RoundingMode.HALF_UP).takeIf { it.signum() > 0 }
    }
}

data class BuilderSessionStamp(val world: Any, val player: Any, val connection: Any, val placement: Any)

/** Cancels all pending work when the actual live Litematica placement or client session changes. */
class BuilderSessionGate {
    private var active: BuilderSessionStamp? = null
    fun observe(next: BuilderSessionStamp?): Boolean {
        val previous = active
        active = next
        return previous == null && next != null || previous != null && next == null || previous != null && next != null &&
            (previous.world !== next.world || previous.player !== next.player || previous.connection !== next.connection || previous.placement !== next.placement)
    }
    fun active(): BuilderSessionStamp? = active
    fun clear() { active = null }
}
