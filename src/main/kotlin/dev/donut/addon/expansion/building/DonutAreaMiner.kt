package dev.donut.addon.expansion.building

import dev.donut.addon.AddonCategories
import dev.donut.addon.expansion.automation.core.InventoryAckChannel
import dev.donut.addon.expansion.automation.core.InventoryAckStatus
import dev.donut.addon.expansion.automation.core.InventoryAckTracker
import dev.donut.addon.expansion.automation.core.InventorySlotValue
import dev.donut.addon.expansion.automation.core.InventorySyncFrame
import dev.donut.addon.expansion.automation.core.InventorySwapExpectation
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.input.InputTracker.isPressedOnAny
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.ccbluex.liquidbounce.utils.item.getEnchantment
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import net.minecraft.tags.ItemTags
import net.minecraft.tags.FluidTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt

/** Bounded, top-down area miner with a two-click world-position selector. */
object DonutAreaMiner : ClientModule("Donut Area Miner", AddonCategories.DONUT, disableOnQuit = true), BuildingSessionParticipant {
    private const val SCAN_BUDGET = 4_096
    private const val BATCH_SIZE = 256
    private const val ROUTE_BUDGET = 3_000
    private const val MAX_BREAK_FAILURES = 3
    private const val BREAK_TIMEOUT_TICKS = 160
    private const val ROUTE_TIMEOUT_TICKS = 240
    private const val INVENTORY_SWAP_TIMEOUT_TICKS = 100
    private const val MEND_TIMEOUT_TICKS = 1_200
    private const val MEND_SWAP_INTERVAL_TICKS = 5

    private enum class SwapPurpose { TOOL_STAGE, TOOL_RESTORE, FOOD_STAGE, FOOD_RESTORE, XP_STAGE, XP_RESTORE }

    private data class PendingInventorySwap(
        val purpose: SwapPurpose,
        val operationLease: AreaInventoryLease,
        val restoreOwner: AreaInventoryLease? = null,
        val startedAtTick: Int,
        val restoreSelectedSlot: Int = -1,
    )

    private val cornerOne = text("Corner 1", "")
    private val cornerTwo = text("Corner 2", "")
    private val selectAreaWithClicksAction = actionControl("Select Area With Clicks", ::selectAreaWithClicks)
    private val cornerOneAtFeet = actionControl("Corner 1 At Feet") { captureCorner(first = true, atCrosshair = false) }
    private val cornerOneAtCrosshair = actionControl("Corner 1 At Crosshair") { captureCorner(first = true, atCrosshair = true) }
    private val cornerTwoAtFeet = actionControl("Corner 2 At Feet") { captureCorner(first = false, atCrosshair = false) }
    private val cornerTwoAtCrosshair = actionControl("Corner 2 At Crosshair") { captureCorner(first = false, atCrosshair = true) }
    private val useShard by boolean("Use Shard Pickaxe", true)
    private val eatWhenHungry by boolean("Eat When Hungry", true)
    private val mendWithExperience by boolean("Mend With XP Bottles", true)
    private val mendBelow by int("Mend Below Durability", 150, 20..1_000)
    private val pauseInMenus by boolean("Pause In Menus", true)
    private val showArea by boolean("Show Area", true)
    private val chatFeedback by boolean("Chat Feedback", true)

    private var worldIdentity: Any? = null
    private var bounds: AreaBounds? = null
    private var picking = AreaCornerSelection()
    private var firstPicked: GridCell? = null
    private var countY = 0
    private var countLayer: AreaLayerCursor? = null
    private var initialEligible = 0
    private var pendingChunks = LinkedHashSet<Pair<Int, Int>>()
    private var scanY = 0
    private var scanCursor: AreaLayerCursor? = null
    private val batch = ArrayDeque<GridCell>()
    private val retryLedger = AreaRetryLedger(MAX_BREAK_FAILURES)
    private val planFailures = HashMap<GridCell, Int>()
    private val protectedCells = HashSet<GridCell>()
    private var currentTarget: GridCell? = null
    private var targetShard = false
    private var breakFace = Direction.UP
    private var breakTicks = 0
    private var breaking = false
    private var route: List<GridCell> = emptyList()
    private var routeIndex = 0
    private var routeTicks = 0
    private var routeGoal: GridCell? = null
    private var skippedCount = 0
    private var minedCount = 0
    private var selectSlotBefore = -1
    private var ownedHotbarSlot = -1
    private var swappedInventorySlot = -1
    private var expectedTool: Item? = null
    private var expectedToolValue: InventorySlotValue? = null
    private var toolInventoryLease: AreaInventoryLease? = null
    private var foodInventoryLease: AreaInventoryLease? = null
    private var xpInventoryLease: AreaInventoryLease? = null
    private val inventoryAckChannel = InventoryAckChannel()
    private val inventoryAckTracker = InventoryAckTracker()
    private var inventoryGeneration = 0L
    private var activeInventoryConnection: ClientPacketListener? = null
    private var activeInventoryWorld: ClientLevel? = null
    private var activeInventoryPlayer: LocalPlayer? = null
    private var activeInventoryMenu: AbstractContainerMenu? = null
    private var inventoryConnectionId = 0L
    private var pendingInventorySwap: PendingInventorySwap? = null
    private val authoritativeInventorySlots = HashMap<Pair<Int, Int>, InventorySlotValue>()
    private val authoritativeInventoryVersions = HashMap<Pair<Int, Int>, Long>()
    private var inventoryUpdateSerial = 0L
    private var mendOffhandUpdateVersion = 0L
    private var waitingForMendBottleEmptyAck = false
    private var mendHadOffhandBottle = false
    private var xpStageOffhandVersion = 0L
    private var beforeBreak: Set<GridCell> = emptySet()
    private var foodSlotBefore = -1
    private var foodHotbarSlot = -1
    private var foodHotbarValue: InventorySlotValue? = null
    private var foodStageDestinationVersion = 0L
    private var eating = false
    private var eatingStartFood = -1
    private var eatingTicks = 0
    private var mending = false
    private var mendStartedAt = 0
    private var tick = 0
    private var lastFeedbackTick = HashMap<String, Int>()

    private fun actionControl(name: String, action: () -> Unit): Value<Boolean> {
        lateinit var control: Value<Boolean>
        control = boolean(name, false)
            .notPersistent()
            .doNotIncludeAlways()
            .onChanged { requested ->
                if (requested) {
                    control.set(false)
                    action()
                }
            }
        return control
    }

    init {
        enabledValue.onChanged { requested ->
            if (!requested && !inGame) endBuildingSession(disableModule = false)
        }
        literalDescription {
            "Mines a bounded cuboid from the highest layer down. Click two blocks to select the corners; protected fluids, unreachable blocks, and oversized regions are handled safely."
        }
    }

    override fun onEnabled() {
        resetInventoryProtocol()
        val player = mc.player
        val level = mc.level
        if (player == null || level == null) {
            message("Join a world before selecting an area.", true)
            enabled = false
            return
        }
        clearRunState()
        worldIdentity = level
        AreaMinerSelectionInput.arm(this)

        val first = AreaBounds.parse(cornerOne.get())
        val second = AreaBounds.parse(cornerTwo.get())
        if (first == null || second == null) {
            startPicking()
            message("Hit the first corner block, then the second corner block.", false)
            return
        }
        if (!begin(AreaBounds.from(first, second), level)) enabled = false
    }

    override fun onDisabled() {
        runCatching { mc.options.keyUse.setDown(false) }
        AreaMinerSelectionInput.clear(this)
        val player = mc.player
        val level = mc.level
        if (worldIdentity != null && worldIdentity === level && level != null && player != null) {
            stopBreaking()
        } else {
            abandonBreaking()
        }
        if (worldIdentity != null && worldIdentity === level && level != null && player != null) {
            if (pendingInventorySwap != null && inventoryAckTracker.status() == InventoryAckStatus.ACKNOWLEDGED) {
                processPendingInventorySwap(player, level)
            }
            restoreOwnedSwapsWithoutFollowup()
        }
        clearRunState()
        resetInventoryProtocol()
    }

    /** Clears controls and inventory epochs even when the native toggle callback is skipped off-world. */
    override fun endBuildingSession(disableModule: Boolean) {
        worldIdentity = null
        abandonBreaking()
        if (disableModule && enabled) enabled = false
        runCatching { mc.options.keyUse.setDown(false) }
        AreaMinerSelectionInput.clear(this)
        clearRunState()
        resetInventoryProtocol()
    }

    private fun startPicking() {
        stopBreaking()
        restoreToolSlot()
        restoreFoodSlot()
        clearRunState()
        bounds = null
        firstPicked = null
        val level = mc.level
        if (mc.player == null || level == null || !picking.arm(level)) {
            message("Join a world before selecting an area.", true)
            return
        }
        worldIdentity = level
        AreaMinerSelectionInput.arm(this)
    }

    private fun selectAreaWithClicks() {
        if (mc.player == null || mc.level == null) {
            message("Join a world before selecting an area.", true)
            return
        }
        cornerOne.set("")
        cornerTwo.set("")
        if (!enabled) {
            enabled = true
            return
        }
        startPicking()
        if (picking.armed) message("Hit the first corner block, then the second corner block.", false)
    }

    private fun captureCorner(first: Boolean, atCrosshair: Boolean) {
        val player = mc.player
        val level = mc.level
        if (player == null || level == null) {
            message("Join a world before setting area corners.", true)
            return
        }
        val pos = if (atCrosshair) (mc.hitResult as? BlockHitResult)?.blockPos else player.blockPosition()
        if (pos == null) {
            message("Look at a block before setting a corner.", true)
            return
        }

        stopBreaking()
        restoreToolSlot()
        restoreFoodSlot()
        clearRunState()
        val cell = toGridCell(pos)
        if (first) {
            cornerOne.set(cell.toCoordString())
            firstPicked = cell
        } else {
            cornerTwo.set(cell.toCoordString())
        }
        worldIdentity = level.takeIf { enabled }
        message("Corner ${if (first) 1 else 2} set to ${cell.toCoordString()}.", false)

        if (!enabled) return
        val one = AreaBounds.parse(cornerOne.get())
        val two = AreaBounds.parse(cornerTwo.get())
        if (one == null || two == null) return
        val area = AreaBounds.from(one, two)
        if (!area.isValid) {
            message("That area exceeds the 2,000,000 block limit. Adjust a corner.", true)
            return
        }
        begin(area, level)
    }

    internal fun consumeSelectionClick(button: Int, action: Int): Boolean {
        if (!picking.armed || button != AreaCornerSelection.LEFT_BUTTON || action != AreaCornerSelection.PRESS) return false
        val level = mc.level
        if (level == null || mc.player == null) {
            cancelSelection()
            return false
        }
        if (mc.gui.screen() != null) {
            return false
        }
        if (level !== worldIdentity) {
            cancelSelection()
            return false
        }
        val hit = mc.hitResult as? BlockHitResult
        val cell = hit?.blockPos?.let(::toGridCell)
        when (picking.click(button, action, false, level, cell)) {
            AreaCornerSelection.Result.IGNORED -> return false
            AreaCornerSelection.Result.CONSUMED_NO_TARGET -> {
                message("No block there. Look at a block and click again.", true)
                return true
            }
            AreaCornerSelection.Result.FIRST_SET -> {
                val first = cell ?: return true
                firstPicked = first
                cornerOne.set(first.toCoordString())
                message("Corner 1 set. Hit the second corner block.", false)
                return true
            }
            AreaCornerSelection.Result.INVALID_SIZE -> {
                message("That area exceeds the 2,000,000 block limit. Choose a closer second corner.", true)
                return true
            }
            AreaCornerSelection.Result.WORLD_CHANGED -> {
                cancelSelection()
                return false
            }
            AreaCornerSelection.Result.COMPLETE -> {
                val first = firstPicked ?: run { cancelSelection(); return true }
                val second = cell ?: run { cancelSelection(); return true }
                cornerTwo.set(second.toCoordString())
                AreaMinerSelectionInput.clear(this)
                if (!begin(AreaBounds.from(first, second), level)) enabled = false
                return true
            }
        }
    }

    private fun cancelSelection() {
        picking.clear()
        firstPicked = null
        AreaMinerSelectionInput.clear(this)
    }

    private fun begin(area: AreaBounds, level: net.minecraft.client.multiplayer.ClientLevel): Boolean {
        if (!area.isValid) {
            message("Area too large (${area.volume} blocks; maximum ${AreaBounds.MAX_VOLUME}).", true)
            return false
        }
        clearRunState()
        AreaMinerSelectionInput.clear(this)
        bounds = area
        worldIdentity = level
        countY = area.maxY
        countLayer = area.layer(countY)
        scanY = area.maxY
        scanCursor = area.layer(scanY)
        message("Counting blocks in ${area.volume} cells; mining will start after a bounded scan.", false)
        return true
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val player = mc.player
        val level = mc.level
        if (player == null || level == null) {
            if (picking.armed) cancelSelection()
            enabled = false
            return@handler
        }
        if (worldIdentity !== level) {
            message("Stopped because the world or dimension changed.", true)
            enabled = false
            return@handler
        }
        tick++
        if (!synchronizeInventorySession(player, level)) {
            enabled = false
            return@handler
        }
        if (processPendingInventorySwap(player, level)) return@handler
        if (picking.armed || bounds == null) return@handler
        if (mc.gui.screen() != null && pauseInMenus) {
            stopBreaking()
            return@handler
        }
        if (!eating && foodInventoryLease != null) {
            restoreFoodSlot()
            if (pendingInventorySwap != null || foodInventoryLease != null) return@handler
        }
        if (!mending && xpInventoryLease != null) {
            restoreXpInventorySlot()
            if (pendingInventorySwap != null || xpInventoryLease != null) return@handler
        }
        if (handleMending(player)) return@handler
        if (currentTarget == null && route.isEmpty() && !breaking && inventoryCalm(player) && handleFood(player)) return@handler
        updateRoute(player)
        if (route.isNotEmpty()) {
            if (++routeTicks > ROUTE_TIMEOUT_TICKS) failCurrentTarget("Could not reach target")
            return@handler
        }
        if (currentTarget != null) {
            mineTarget(player, level)
            return@handler
        }
        if (!countedInitialArea()) {
            countInitialArea(level)
            return@handler
        }
        if (batch.isEmpty()) {
            fillBatch(level)
            if (batch.isEmpty()) {
                advanceLayerOrWait(level)
                return@handler
            }
        }
        chooseBatchTarget(player, level)
    }

    @Suppress("unused")
    private val inventoryPackets = handler<PacketEvent> { event ->
        inventoryAckChannel.onPacket(event.origin, event.original, event.packet)
    }

    private fun synchronizeInventorySession(player: LocalPlayer, level: ClientLevel): Boolean {
        val connection = mc.connection ?: return false
        val menu = player.inventoryMenu
        if (activeInventoryConnection !== connection || activeInventoryWorld !== level ||
            activeInventoryPlayer !== player || activeInventoryMenu !== menu) {
            inventoryAckChannel.reset()
            inventoryAckTracker.reset()
            inventoryGeneration++
            pendingInventorySwap = null
            toolInventoryLease = null
            foodInventoryLease = null
            xpInventoryLease = null
            activeInventoryConnection = connection
            activeInventoryWorld = level
            activeInventoryPlayer = player
            activeInventoryMenu = menu
            inventoryConnectionId = System.identityHashCode(connection).toLong()
            authoritativeInventorySlots.clear()
            authoritativeInventoryVersions.clear()
            inventoryUpdateSerial = 0L
            mendOffhandUpdateVersion = 0L
            waitingForMendBottleEmptyAck = false
            mendHadOffhandBottle = false
            xpStageOffhandVersion = 0L
            foodStageDestinationVersion = 0L
        }
        inventoryAckChannel.setSession(inventoryGeneration, inventoryConnectionId)
        inventoryAckChannel.drain(inventoryGeneration, inventoryConnectionId, ::observeInventoryFrame)
        return true
    }

    private fun observeInventoryFrame(frame: InventorySyncFrame) {
        inventoryAckTracker.observe(frame)
        for ((slot, value) in frame.slots) {
            val key = frame.menuId to slot
            authoritativeInventorySlots[key] = value
            authoritativeInventoryVersions[key] = ++inventoryUpdateSerial
        }
    }

    private fun processPendingInventorySwap(player: LocalPlayer, level: ClientLevel): Boolean {
        val pending = pendingInventorySwap ?: return false
        val menu = player.inventoryMenu
        if (activeInventoryPlayer !== player || activeInventoryWorld !== level ||
            activeInventoryConnection !== mc.connection || activeInventoryMenu !== menu ||
            pending.operationLease.connectionId != inventoryConnectionId ||
            pending.operationLease.menuId != menu.containerId) {
            inventoryAckTracker.reset()
            pendingInventorySwap = null
            toolInventoryLease = null
            foodInventoryLease = null
            xpInventoryLease = null
            enabled = false
            return true
        }

        val status = inventoryAckTracker.status()
        when (status) {
            InventoryAckStatus.PENDING -> {
                if (tick - pending.startedAtTick > INVENTORY_SWAP_TIMEOUT_TICKS) {
                    inventoryAckTracker.abandon()
                    failPendingInventorySwap(pending)
                }
            }
            InventoryAckStatus.ACKNOWLEDGED -> {
                val source = menuSlotValue(menu, pending.operationLease.sourceMenuSlot)
                val destination = menuSlotValue(menu, pending.operationLease.destinationMenuSlot)
                val reconciled = inventoryAckTracker.reconcileLocal(source, destination, menu.carried.isEmpty)
                if (reconciled == InventoryAckStatus.ACKNOWLEDGED) {
                    finishPendingInventorySwap(player, pending)
                } else failPendingInventorySwap(pending)
            }
            InventoryAckStatus.REJECTED, InventoryAckStatus.ABANDONED, InventoryAckStatus.IDLE ->
                failPendingInventorySwap(pending)
        }
        return true
    }

    private fun finishPendingInventorySwap(player: LocalPlayer, pending: PendingInventorySwap) {
        val lease = pending.operationLease
        pendingInventorySwap = null
        inventoryAckTracker.reset()
        when (pending.purpose) {
            SwapPurpose.TOOL_STAGE -> {
                if (player.inventory.selectedSlot != lease.selectedSlot) {
                    toolInventoryLease = lease
                    enabled = false
                    return
                }
                toolInventoryLease = lease
                selectSlotBefore = lease.selectedSlot
                ownedHotbarSlot = lease.selectedSlot
                swappedInventorySlot = lease.sourceMenuSlot
                val stack = player.inventory.getItem(lease.selectedSlot)
                expectedTool = stack.item
                expectedToolValue = InventorySlotValue.fromStack(stack)
            }
            SwapPurpose.TOOL_RESTORE -> {
                toolInventoryLease = null
                val restoreSlot = pending.restoreSelectedSlot
                if (restoreSlot in 0..8 && player.inventory.selectedSlot == lease.selectedSlot) {
                    player.inventory.selectedSlot = restoreSlot
                }
                clearToolState()
            }
            SwapPurpose.FOOD_STAGE -> {
                if (player.inventory.selectedSlot != lease.selectedSlot) {
                    foodInventoryLease = lease
                    enabled = false
                    return
                }
                foodInventoryLease = lease
                foodSlotBefore = lease.selectedSlot
                foodHotbarSlot = lease.destinationMenuSlot - 36
                foodHotbarValue = InventorySlotValue.fromStack(player.inventory.getItem(foodHotbarSlot))
                foodStageDestinationVersion = serverSlotVersion(lease.menuId, lease.destinationMenuSlot)
            }
            SwapPurpose.FOOD_RESTORE -> {
                foodInventoryLease = null
                val restoreSlot = pending.restoreSelectedSlot
                if (restoreSlot in 0..8 && player.inventory.selectedSlot == lease.selectedSlot) {
                    player.inventory.selectedSlot = restoreSlot
                }
                clearFoodSelection()
            }
            SwapPurpose.XP_STAGE -> {
                xpInventoryLease = lease
                mendHadOffhandBottle = player.offhandItem.`is`(Items.EXPERIENCE_BOTTLE)
                mendOffhandUpdateVersion = serverSlotVersion(lease.menuId, 45)
                xpStageOffhandVersion = mendOffhandUpdateVersion
                waitingForMendBottleEmptyAck = false
            }
            SwapPurpose.XP_RESTORE -> xpInventoryLease = null
        }
    }

    private fun failPendingInventorySwap(pending: PendingInventorySwap) {
        pendingInventorySwap = null
        inventoryAckTracker.reset()
        when (pending.purpose) {
            SwapPurpose.TOOL_RESTORE -> toolInventoryLease = null
            SwapPurpose.FOOD_RESTORE -> foodInventoryLease = null
            SwapPurpose.XP_RESTORE -> xpInventoryLease = null
            else -> Unit
        }
        enabled = false
    }

    private fun startInventorySwap(
        sourceMenuSlot: Int,
        destinationMenuSlot: Int,
        purpose: SwapPurpose,
        restoreOwner: AreaInventoryLease? = null,
        restoreSelectedSlot: Int = -1,
    ): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        val connection = mc.connection ?: return false
        val menu = player.inventoryMenu
        val gameMode = mc.gameMode ?: return false
        if (player.containerMenu !== menu || mc.gui.screen() != null || !menu.carried.isEmpty ||
            activeInventoryPlayer !== player || activeInventoryWorld !== level ||
            activeInventoryConnection !== connection || activeInventoryMenu !== menu ||
            inventoryConnectionId != System.identityHashCode(connection).toLong() ||
            sourceMenuSlot !in 9..44 || destinationMenuSlot !in 36..45 || sourceMenuSlot == destinationMenuSlot) return false
        if (!settleInventoryForAction(player, level)) return false

        val source = menuSlotValue(menu, sourceMenuSlot) ?: return false
        val destination = menuSlotValue(menu, destinationMenuSlot) ?: return false
        if (source.count <= 0 || source == destination) return false
        val operationLease = AreaInventoryLease(
            worldIdentity = level,
            playerIdentity = player,
            connectionIdentity = connection,
            menuIdentity = menu,
            connectionId = inventoryConnectionId,
            menuId = menu.containerId,
            sourceMenuSlot = sourceMenuSlot,
            destinationMenuSlot = destinationMenuSlot,
            selectedSlot = player.inventory.selectedSlot,
            originalSource = source,
            originalDestination = destination,
        )
        inventoryAckTracker.reset()
        inventoryAckTracker.begin(
            InventorySwapExpectation(
                inventoryConnectionId,
                menu.containerId,
                menu.stateId,
                sourceMenuSlot,
                destinationMenuSlot,
                source,
                destination,
            ),
        )
        pendingInventorySwap = PendingInventorySwap(
            purpose, operationLease, restoreOwner, tick, restoreSelectedSlot,
        )
        val button = if (destinationMenuSlot == 45) 40 else destinationMenuSlot - 36
        gameMode.handleContainerInput(menu.containerId, sourceMenuSlot, button, ContainerInput.SWAP, player)
        return true
    }

    private fun startOwnedRestore(
        owner: AreaInventoryLease,
        purpose: SwapPurpose,
        restoreSelectedSlot: Int = -1,
    ): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        val connection = mc.connection ?: return false
        val menu = player.inventoryMenu
        if (player.containerMenu !== menu || mc.gui.screen() != null || !menu.carried.isEmpty) return false
        val canRestore = owner.mayRestore(
            level, player, connection, menu, inventoryConnectionId, menu.containerId,
            player.inventory.selectedSlot,
            menuSlotValue(menu, owner.sourceMenuSlot), menuSlotValue(menu, owner.destinationMenuSlot),
            menu.carried.isEmpty,
        )
        return canRestore && startInventorySwap(
            owner.sourceMenuSlot, owner.destinationMenuSlot, purpose, owner, restoreSelectedSlot,
        )
    }

    private fun leaseStillOwned(owner: AreaInventoryLease): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        val connection = mc.connection ?: return false
        val menu = player.inventoryMenu
        return owner.stacksMatch(
            level, player, connection, menu, inventoryConnectionId, menu.containerId,
            menuSlotValue(menu, owner.sourceMenuSlot), menuSlotValue(menu, owner.destinationMenuSlot),
        )
    }

    private fun menuSlotValue(menu: AbstractContainerMenu, slot: Int): InventorySlotValue? =
        menu.slots.getOrNull(slot)?.let { InventorySlotValue.fromStack(it.item) }

    private fun serverSlotVersion(menuId: Int, slot: Int): Long =
        authoritativeInventoryVersions[menuId to slot] ?: 0L

    private fun refreshLeaseForRestore(
        lease: AreaInventoryLease,
        destinationVersionBeforeUse: Long,
    ): AreaLeaseRefresh {
        val player = mc.player ?: return AreaLeaseRefresh.Invalid
        val level = mc.level ?: return AreaLeaseRefresh.Invalid
        val menu = player.inventoryMenu
        val connection = mc.connection
        if (connection == null || activeInventoryPlayer !== player || activeInventoryWorld !== level ||
            activeInventoryConnection !== connection || activeInventoryMenu !== menu ||
            lease.connectionId != inventoryConnectionId || lease.menuId != menu.containerId) return AreaLeaseRefresh.Invalid
        return AreaInventoryLeaseRefresh.evaluate(
            lease,
            serverSlotVersion(lease.menuId, lease.destinationMenuSlot),
            destinationVersionBeforeUse,
            authoritativeInventorySlots[lease.menuId to lease.sourceMenuSlot],
            authoritativeInventorySlots[lease.menuId to lease.destinationMenuSlot],
            menuSlotValue(menu, lease.sourceMenuSlot),
            menuSlotValue(menu, lease.destinationMenuSlot),
        )
    }

    private fun resetInventoryProtocol() {
        inventoryAckChannel.reset()
        inventoryAckTracker.reset()
        inventoryGeneration++
        activeInventoryConnection = null
        activeInventoryWorld = null
        activeInventoryPlayer = null
        activeInventoryMenu = null
        inventoryConnectionId = 0L
        pendingInventorySwap = null
        toolInventoryLease = null
        foodInventoryLease = null
        xpInventoryLease = null
        authoritativeInventorySlots.clear()
        authoritativeInventoryVersions.clear()
        inventoryUpdateSerial = 0L
        mendOffhandUpdateVersion = 0L
        waitingForMendBottleEmptyAck = false
        mendHadOffhandBottle = false
        xpStageOffhandVersion = 0L
        foodStageDestinationVersion = 0L
    }

    /** On disable, reverse only a fully acknowledged, unchanged swap; this cleanup starts no later action. */
    private fun restoreOwnedSwapsWithoutFollowup() {
        if (pendingInventorySwap != null) return
        val player = mc.player ?: return
        val level = mc.level ?: return
        val connection = mc.connection ?: return
        val menu = player.inventoryMenu
        val lease = toolInventoryLease ?: foodInventoryLease ?: xpInventoryLease
        if (lease == null) {
            restoreToolSlot()
            restoreFoodSlot()
            return
        }
        if (player.containerMenu !== menu || mc.gui.screen() != null || !menu.carried.isEmpty || mc.gameMode == null ||
            !settleInventoryForAction(player, level)) return
        if (!lease.mayRestore(
                level, player, connection, menu, inventoryConnectionId, menu.containerId,
                player.inventory.selectedSlot, menuSlotValue(menu, lease.sourceMenuSlot),
                menuSlotValue(menu, lease.destinationMenuSlot), menu.carried.isEmpty,
            )) return
        val button = if (lease.destinationMenuSlot == 45) 40 else lease.destinationMenuSlot - 36
        mc.gameMode?.handleContainerInput(menu.containerId, lease.sourceMenuSlot, button, ContainerInput.SWAP, player)
        if (lease === toolInventoryLease && player.inventory.selectedSlot == lease.selectedSlot && selectSlotBefore in 0..8) {
            player.inventory.selectedSlot = selectSlotBefore
        }
    }

    private fun countedInitialArea(): Boolean = countY < (bounds?.minY ?: 0)

    private fun countInitialArea(level: net.minecraft.client.multiplayer.ClientLevel) {
        val area = bounds ?: return
        var left = SCAN_BUDGET
        while (left > 0 && countY >= area.minY) {
            val cursor = countLayer ?: area.layer(countY)
            if (cursor == null) { countY--; continue }
            val batch = cursor.scan(left, Int.MAX_VALUE) { cell ->
                val pos = cell.toBlockPos()
                if (!level.hasChunkAt(pos)) {
                    pendingChunks.add(pos.x shr 4 to (pos.z shr 4))
                    false
                } else {
                    pendingChunks.remove(pos.x shr 4 to (pos.z shr 4))
                    val state = level.getBlockState(pos)
                    if (isMineable(state, level, pos) && !protectsFluid(level, pos)) {
                        initialEligible++
                        true
                    } else false
                }
            }
            left -= batch.checked
            if (cursor.done) { countY--; countLayer = area.layer(countY) }
            if (batch.checked == 0) break
        }
        if (countedInitialArea()) {
            scanY = area.maxY
            scanCursor = area.layer(scanY)
            message("Found $initialEligible eligible blocks. Starting at Y=${area.maxY}.", false)
        }
    }

    private fun fillBatch(level: net.minecraft.client.multiplayer.ClientLevel) {
        val area = bounds ?: return
        var left = SCAN_BUDGET
        val cursor = scanCursor ?: area.layer(scanY) ?: return
        while (left > 0 && batch.size < BATCH_SIZE && scanY >= area.minY) {
            val result = cursor.scan(left, BATCH_SIZE - batch.size) { cell ->
                if (retryLedger.isSkipped(cell)) return@scan false
                val pos = cell.toBlockPos()
                if (!level.hasChunkAt(pos)) {
                    pendingChunks.add(pos.x shr 4 to (pos.z shr 4))
                    return@scan false
                }
                pendingChunks.remove(pos.x shr 4 to (pos.z shr 4))
                isMineable(level.getBlockState(pos), level, pos) && !protectsFluid(level, pos)
            }
            result.cells.forEach(batch::addLast)
            left -= result.checked
            if (cursor.done) {
                if (batch.isEmpty()) {
                    scanY--
                    scanCursor = area.layer(scanY)
                    if (scanY >= area.minY) {
                        cursorLayerFeedback(scanY)
                    }
                } else {
                    scanCursor = cursor
                }
            }
            if (result.checked == 0 || batch.size >= BATCH_SIZE) break
        }
    }

    private fun cursorLayerFeedback(y: Int) {
        if (chatFeedback) message("Scanning layer Y=$y.", false, 20)
    }

    private fun advanceLayerOrWait(level: net.minecraft.client.multiplayer.ClientLevel) {
        val area = bounds ?: return
        if (scanY >= area.minY) return
        val nowLoaded = pendingChunks.any { (x, z) -> level.hasChunk(x, z) }
        if (nowLoaded) {
            pendingChunks.removeIf { (x, z) -> level.hasChunk(x, z) }
            scanY = area.maxY
            scanCursor = area.layer(scanY)
            message("New area chunks loaded; rescanning.", false, 40)
            return
        }
        if (pendingChunks.isNotEmpty()) {
            message("Waiting for ${pendingChunks.size} unloaded area chunks. Move closer to load them.", false, 200)
            return
        }
        val missed = retryLedger.skippedCount()
        val protected = protectedCells.size
        message("Area pass finished: mined $minedCount / $initialEligible eligible blocks; skipped $missed unreachable and protected $protected fluid-adjacent blocks.", false)
        enabled = false
    }

    private fun chooseBatchTarget(player: net.minecraft.client.player.LocalPlayer, level: net.minecraft.client.multiplayer.ClientLevel) {
        val area = bounds ?: return
        val candidates = batch.toList().sortedBy { it.toBlockPos().distToCenterSqr(player.eyePosition) }
        for (candidate in candidates) {
            val pos = candidate.toBlockPos()
            if (!level.hasChunkAt(pos) || !isMineable(level.getBlockState(pos), level, pos)) {
                batch.remove(candidate)
                continue
            }
            if (protectsFluid(level, pos)) {
                protectedCells.add(candidate)
                batch.remove(candidate)
                continue
            }
            val shard = useShard && shardCutFits(level, area, pos)
            if (miningShot(player, level, pos) != null) {
                currentTarget = candidate
                targetShard = shard
                return
            }
            val routeResult = planApproach(player, level, candidate)
            if (routeResult != null) {
                currentTarget = candidate
                targetShard = shard
                route = routeResult
                routeIndex = 0
                routeTicks = 0
                routeGoal = route.lastOrNull()
                return
            }
            val failures = planFailures.merge(candidate, 1, Int::plus) ?: 1
            if (failures >= 2) {
                retryLedger.recordFailure(candidate)
                retryLedger.recordFailure(candidate)
                retryLedger.recordFailure(candidate)
                batch.remove(candidate)
                skippedCount++
                message("No safe route to ${pos.toShortString()}; leaving it.", false, 60)
            }
        }
        if (batch.isEmpty() && scanCursor?.done == true) {
            scanY--
            scanCursor = area.layer(scanY)
            if (scanY >= area.minY) cursorLayerFeedback(scanY)
        }
    }

    private fun planApproach(
        player: net.minecraft.client.player.LocalPlayer,
        level: net.minecraft.client.multiplayer.ClientLevel,
        target: GridCell,
    ): List<GridCell>? {
        val pos = target.toBlockPos()
        val goals = Direction.values().map { pos.relative(it).let(::toGridCell) }.toSet()
        if (goals.isEmpty()) return null
        val start = player.blockPosition().let(::toGridCell)
        val result = AreaStandPlanner.findRoute(start, goals, ROUTE_BUDGET, canStand = { cell ->
            val candidate = cell.toBlockPos()
            standingSurfaceY(level, candidate) != null
        }, canTransition = { from, to -> transitionClear(player, level, from, to) })
        if (result.route == null || result.route.isEmpty()) return null
        return result.route
    }

    /** Checks both standing columns and the swept body space at their real floor heights. */
    private fun transitionClear(
        player: net.minecraft.client.player.LocalPlayer,
        level: net.minecraft.client.multiplayer.ClientLevel,
        from: GridCell,
        to: GridCell,
    ): Boolean {
        if (!AreaTransitionRules.allows(from, to)) return false
        val fromPos = from.toBlockPos()
        val toPos = to.toBlockPos()
        val fromY = standingSurfaceY(level, fromPos) ?: return false
        val toY = standingSurfaceY(level, toPos) ?: return false
        val drop = fromY - toY
        val intoWater = drop > 0.6 && level.getFluidState(toPos).`is`(FluidTags.WATER)
        val safeExit = drop <= 0.01 || hasAdjacentSafeExit(level, toPos, toY)
        if (!AreaTransitionRules.allowsSafeTransition(fromY, toY, intoWater, nearLavaOrFire(level, toPos), safeExit)) return false

        val topY = maxOf(fromY, toY)
        val halfWidth = 0.3
        val here = standingBody(fromPos, fromY, topY)
        val there = standingBody(toPos, toY, topY)
        val across = AABB(
            minOf(here.minX, there.minX), topY + 0.55, minOf(here.minZ, there.minZ),
            maxOf(here.maxX, there.maxX), topY + 1.8, maxOf(here.maxZ, there.maxZ),
        )
        return !level.getBlockCollisions(player, here).iterator().hasNext() &&
            !level.getBlockCollisions(player, there).iterator().hasNext() &&
            !level.getBlockCollisions(player, across).iterator().hasNext()
    }

    private fun standingBody(feet: BlockPos, floorY: Double, topY: Double): AABB = AABB(
        feet.x + 0.2, floorY + 0.01, feet.z + 0.2,
        feet.x + 0.8, topY + 1.8, feet.z + 0.8,
    )

    /** Returns the top surface under the player's central footprint, including slabs and stairs. */
    private fun standingSurfaceY(level: net.minecraft.client.multiplayer.ClientLevel, feet: BlockPos): Double? {
        if (!level.hasChunkAt(feet) || !level.hasChunkAt(feet.above())) return null
        val fluid = level.getFluidState(feet)
        if (fluid.`is`(FluidTags.WATER) && fluid.getHeight(level, feet) > 0.4f) return null

        val ownTop = topUnderFoot(level, feet)
        val floorY = when {
            ownTop != null && ownTop <= 0.999 -> feet.y + ownTop
            !fluid.isEmpty -> return null
            else -> {
                val below = feet.below()
                val belowTop = topUnderFoot(level, below) ?: return null
                if (belowTop <= 0.999) return null
                below.y + belowTop
            }
        }
        if (!level.getFluidState(feet.above()).isEmpty) return null
        if (nearLavaOrFire(level, feet)) return null
        if (level.getBlockCollisions(null, standingBody(feet, floorY, floorY)).iterator().hasNext()) return null
        return floorY
    }

    private fun topUnderFoot(level: net.minecraft.client.multiplayer.ClientLevel, pos: BlockPos): Double? {
        val shape = level.getBlockState(pos).getCollisionShape(level, pos)
        if (shape.isEmpty) return null
        var top: Double? = null
        for (box in shape.toAabbs()) {
            if (box.maxX > 0.2 && box.minX < 0.8 && box.maxZ > 0.2 && box.minZ < 0.8) {
                top = maxOf(top ?: Double.NEGATIVE_INFINITY, box.maxY)
            }
        }
        return top
    }

    private fun nearLavaOrFire(level: net.minecraft.client.multiplayer.ClientLevel, feet: BlockPos): Boolean =
        listOf(feet, feet.above(), feet.below(), feet.north(), feet.south(), feet.east(), feet.west()).any { cell ->
            level.getFluidState(cell).`is`(FluidTags.LAVA) || level.getBlockState(cell).`is`(net.minecraft.tags.BlockTags.FIRE)
        }

    /** Rejects a drop into a pocket with no safe neighboring floor, as the source walker does. */
    private fun hasAdjacentSafeExit(
        level: net.minecraft.client.multiplayer.ClientLevel,
        feet: BlockPos,
        floorY: Double,
    ): Boolean {
        for (direction in Direction.Plane.HORIZONTAL) {
            val side = feet.relative(direction)
            for (dy in 1 downTo -3) {
                val candidate = side.above(dy)
                val candidateY = standingSurfaceYWithoutExitCheck(level, candidate) ?: continue
                if (floorY - candidateY > 3.0 || candidateY - floorY > 1.2) continue
                if (floorY - candidateY > 0.6 && level.getFluidState(candidate).`is`(FluidTags.WATER)) continue
                if (nearLavaOrFire(level, candidate)) continue
                if (passageClear(level, feet, floorY, candidate, candidateY)) return true
            }
        }
        return false
    }

    private fun standingSurfaceYWithoutExitCheck(
        level: net.minecraft.client.multiplayer.ClientLevel,
        feet: BlockPos,
    ): Double? {
        if (!level.hasChunkAt(feet) || !level.hasChunkAt(feet.above())) return null
        val fluid = level.getFluidState(feet)
        if (fluid.`is`(FluidTags.WATER) && fluid.getHeight(level, feet) > 0.4f) return null
        val ownTop = topUnderFoot(level, feet)
        val floorY = when {
            ownTop != null && ownTop <= 0.999 -> feet.y + ownTop
            !fluid.isEmpty -> return null
            else -> {
                val below = feet.below()
                val belowTop = topUnderFoot(level, below) ?: return null
                if (belowTop <= 0.999) return null
                below.y + belowTop
            }
        }
        if (!level.getFluidState(feet.above()).isEmpty || nearLavaOrFire(level, feet)) return null
        if (level.getBlockCollisions(null, standingBody(feet, floorY, floorY)).iterator().hasNext()) return null
        return floorY
    }

    private fun passageClear(
        level: net.minecraft.client.multiplayer.ClientLevel,
        from: BlockPos,
        fromY: Double,
        to: BlockPos,
        toY: Double,
    ): Boolean {
        val topY = maxOf(fromY, toY)
        val here = standingBody(from, fromY, topY)
        val there = standingBody(to, toY, topY)
        val across = AABB(
            minOf(here.minX, there.minX), topY + 0.55, minOf(here.minZ, there.minZ),
            maxOf(here.maxX, there.maxX), topY + 1.8, maxOf(here.maxZ, there.maxZ),
        )
        return !level.getBlockCollisions(null, here).iterator().hasNext() &&
            !level.getBlockCollisions(null, there).iterator().hasNext() &&
            !level.getBlockCollisions(null, across).iterator().hasNext()
    }

    private fun updateRoute(player: net.minecraft.client.player.LocalPlayer) {
        if (route.isEmpty()) return
        while (routeIndex < route.size) {
            val next = route[routeIndex]
            val dx = player.x - (next.x + 0.5)
            val dz = player.z - (next.z + 0.5)
            if (dx * dx + dz * dz > 0.16 || kotlin.math.abs(player.y - next.y) > 0.8) break
            routeIndex++
            routeTicks = 0
        }
        if (routeIndex >= route.size) {
            route = emptyList()
            routeIndex = 0
            routeGoal = null
            routeTicks = 0
        }
    }

    private fun mineTarget(player: net.minecraft.client.player.LocalPlayer, level: net.minecraft.client.multiplayer.ClientLevel) {
        val gameMode = mc.gameMode ?: return
        val target = currentTarget ?: return
        val pos = target.toBlockPos()
        val state = level.getBlockState(pos)
        if (!isMineable(state, level, pos)) {
            if (breaking) {
                minedCount += AreaProgressMath.actualAirChanges(
                    beforeBreak,
                    beforeBreak.filterTo(HashSet()) { level.getBlockState(it.toBlockPos()).isAir },
                )
                finishCurrentTarget()
            } else {
                batch.remove(target)
                finishCurrentTarget()
            }
            return
        }
        val shot = miningShot(player, level, pos)
        if (shot == null) {
            stopBreaking()
            val path = planApproach(player, level, target)
            if (path == null) failCurrentTarget("Target moved out of reach") else {
                route = path
                routeIndex = 0
                routeTicks = 0
                routeGoal = route.lastOrNull()
            }
            return
        }
        if (handleMending(player)) return
        if (breaking && breakTicks > BREAK_TIMEOUT_TICKS) {
            failCurrentTarget("Mining timed out")
            return
        }
        if (breaking) {
            gameMode.continueDestroyBlock(pos, breakFace)
            breakTicks++
        } else {
            if (!selectTool(player, state, targetShard)) return
            beforeBreak = breakFootprint(level, target, targetShard)
            if (gameMode.startDestroyBlock(pos, shot.face)) {
                breaking = true
                breakFace = shot.face
                breakTicks = 0
            } else {
                beforeBreak = emptySet()
            }
        }
    }

    private fun failCurrentTarget(reason: String) {
        val target = currentTarget ?: return
        stopBreaking()
        val failures = retryLedger.recordFailure(target)
        if (failures >= MAX_BREAK_FAILURES) {
            skippedCount++
            batch.remove(target)
            message("$reason at ${target.toBlockPos().toShortString()}; leaving it.", false, 60)
            finishCurrentTarget()
        } else {
            restoreToolSlot()
            route = emptyList()
            routeIndex = 0
            routeTicks = 0
            currentTarget = null
            batch.remove(target)
        }
    }

    private fun finishCurrentTarget() {
        stopBreaking()
        val target = currentTarget
        if (target != null) batch.remove(target)
        currentTarget = null
        targetShard = false
        breakFace = Direction.UP
        beforeBreak = emptySet()
        route = emptyList()
        routeIndex = 0
        routeTicks = 0
        routeGoal = null
        breakTicks = 0
        breaking = false
        restoreToolSlot()
    }

    private fun stopBreaking() {
        if (breaking) runCatching { mc.gameMode?.stopDestroyBlock() }
        breaking = false
        breakTicks = 0
    }

    /** Drops local break ownership without sending a stop packet into a changed or absent world. */
    private fun abandonBreaking() {
        breaking = false
        breakTicks = 0
        beforeBreak = emptySet()
    }

    private data class MiningShot(val point: Vec3, val face: Direction)

    private fun miningShot(
        player: net.minecraft.client.player.LocalPlayer,
        level: net.minecraft.client.multiplayer.ClientLevel,
        pos: BlockPos,
    ): MiningShot? {
        val shape = level.getBlockState(pos).getShape(level, pos)
        if (shape.isEmpty) return null
        val box = shape.bounds().move(pos)
        val eye = player.eyePosition
        val faces = listOf(Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN)
        for (face in faces) {
            val exposed = when (face) {
                Direction.UP -> eye.y > box.maxY + 0.02
                Direction.DOWN -> eye.y < box.minY - 0.02
                Direction.NORTH -> eye.z < box.minZ - 0.02
                Direction.SOUTH -> eye.z > box.maxZ + 0.02
                Direction.WEST -> eye.x < box.minX - 0.02
                Direction.EAST -> eye.x > box.maxX + 0.02
            }
            if (!exposed) continue
            val point = when (face) {
                Direction.UP -> Vec3((box.minX + box.maxX) * 0.5, box.maxY, (box.minZ + box.maxZ) * 0.5)
                Direction.DOWN -> Vec3((box.minX + box.maxX) * 0.5, box.minY, (box.minZ + box.maxZ) * 0.5)
                Direction.NORTH -> Vec3((box.minX + box.maxX) * 0.5, (box.minY + box.maxY) * 0.5, box.minZ)
                Direction.SOUTH -> Vec3((box.minX + box.maxX) * 0.5, (box.minY + box.maxY) * 0.5, box.maxZ)
                Direction.WEST -> Vec3(box.minX, (box.minY + box.maxY) * 0.5, (box.minZ + box.maxZ) * 0.5)
                Direction.EAST -> Vec3(box.maxX, (box.minY + box.maxY) * 0.5, (box.minZ + box.maxZ) * 0.5)
            }
            if (eye.distanceTo(point) > player.blockInteractionRange()) continue
            val hit = level.clip(ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
            if (hit.type == HitResult.Type.BLOCK && hit is BlockHitResult && hit.blockPos == pos && hit.direction == face) {
                return MiningShot(point, face)
            }
        }
        return null
    }

    private fun isMineable(state: BlockState, level: Level, pos: BlockPos): Boolean =
        AreaMiningRules.isMineable(state.isAir, state.block is LiquidBlock, state.getDestroySpeed(level, pos))

    private fun breakFootprint(level: net.minecraft.client.multiplayer.ClientLevel, center: GridCell, shard: Boolean): Set<GridCell> {
        if (!shard) return setOf(center)
        val area = bounds ?: return setOf(center)
        val changed = LinkedHashSet<GridCell>()
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            val cell = GridCell(center.x + dx, center.y + dy, center.z + dz)
            val pos = cell.toBlockPos()
            if (area.contains(cell) && level.hasChunkAt(pos) && isMineable(level.getBlockState(pos), level, pos) &&
                !protectsFluid(level, pos)) changed.add(cell)
        }
        return changed.ifEmpty { setOf(center) }
    }

    private fun protectsFluid(level: net.minecraft.client.multiplayer.ClientLevel, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        if (!state.fluidState.isEmpty) return true
        val hasFluidNeighbor = Direction.values().any { direction -> direction != Direction.DOWN &&
            !level.getFluidState(pos.relative(direction)).isEmpty
        }
        return hasFluidNeighbor
    }

    private fun shardCutFits(level: net.minecraft.client.multiplayer.ClientLevel, area: AreaBounds, center: BlockPos): Boolean {
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            val pos = center.offset(dx, dy, dz)
            if (!area.contains(toGridCell(pos))) return false
            val state = level.getBlockState(pos)
            if (isMineable(state, level, pos) && protectsFluid(level, pos)) return false
        }
        return true
    }

    private fun selectTool(player: net.minecraft.client.player.LocalPlayer, state: BlockState, shard: Boolean): Boolean {
        val inventory = player.inventory
        val best = (0 until 36).asSequence()
            .mapNotNull { slot ->
                val stack = inventory.getItem(slot)
                if (stack.isEmpty) return@mapNotNull null
                val isShardTool = isShard(stack)
                if (isShardTool != shard) return@mapNotNull null
                val speed = stack.getDestroySpeed(state)
                val suitable = stack.isCorrectToolForDrops(state)
                AreaToolChoice(slot, speed, suitable)
            }
            .asIterable()
            .let(AreaToolSelection::best)
        if (best == null && shard) return selectTool(player, state, false)

        val currentLease = toolInventoryLease
        if (currentLease != null) {
            val canUseLease = currentLease.mayRestore(
                mc.level, player, mc.connection, player.inventoryMenu, inventoryConnectionId,
                player.inventoryMenu.containerId, inventory.selectedSlot,
                menuSlotValue(player.inventoryMenu, currentLease.sourceMenuSlot),
                menuSlotValue(player.inventoryMenu, currentLease.destinationMenuSlot),
                player.inventoryMenu.carried.isEmpty,
            ) && inventory.selectedSlot == currentLease.selectedSlot
            val stagedStack = inventory.getItem(inventory.selectedSlot)
            val leaseStillSelected = best?.slot == inventory.selectedSlot || best == null && !isShard(stagedStack)
            if (canUseLease && leaseStillSelected &&
                currentLease.destinationMenuSlot == 36 + inventory.selectedSlot &&
                menuSlotValue(player.inventoryMenu, currentLease.destinationMenuSlot) ==
                    InventorySlotValue.fromStack(stagedStack)) {
                expectedTool = inventory.getItem(inventory.selectedSlot).item
                expectedToolValue = InventorySlotValue.fromStack(inventory.getItem(inventory.selectedSlot))
                ownedHotbarSlot = inventory.selectedSlot
                return true
            }
            restoreToolSlot()
            return false
        }

        if (best == null) {
            if (!isShard(player.mainHandItem)) return true
            val fallbackHotbar = (0 until 9).firstOrNull { !isShard(inventory.getItem(it)) }
            if (fallbackHotbar != null) {
                val selected = inventory.selectedSlot
                if (selectSlotBefore < 0) selectSlotBefore = selected
                if (ownedHotbarSlot < 0) ownedHotbarSlot = selected
                inventory.selectedSlot = fallbackHotbar
                ownedHotbarSlot = fallbackHotbar
                expectedTool = inventory.getItem(fallbackHotbar).item
                expectedToolValue = InventorySlotValue.fromStack(inventory.getItem(fallbackHotbar))
                return true
            }
            val fallbackInventory = (9 until 36).firstOrNull { !inventory.getItem(it).isEmpty && !isShard(inventory.getItem(it)) }
                ?: return false
            return stageToolFromInventory(player, fallbackInventory)
        }

        val selected = inventory.selectedSlot
        val bestStack = inventory.getItem(best.slot)
        val bestToolItem = bestStack.item
        val bestValue = InventorySlotValue.fromStack(bestStack)
        if (selectSlotBefore < 0) selectSlotBefore = selected
        if (ownedHotbarSlot < 0) ownedHotbarSlot = selected
        if (best.slot < 9) {
            if (selected != best.slot) inventory.selectedSlot = best.slot
            ownedHotbarSlot = best.slot
            swappedInventorySlot = -1
            expectedTool = bestToolItem
            expectedToolValue = bestValue
            return true
        }
        val destinationMenuSlot = 36 + selected
        if (bestValue == menuSlotValue(player.inventoryMenu, destinationMenuSlot)) {
            inventory.selectedSlot = selected
            ownedHotbarSlot = selected
            expectedTool = inventory.getItem(selected).item
            expectedToolValue = InventorySlotValue.fromStack(inventory.getItem(selected))
            return true
        }
        return stageToolFromInventory(player, best.slot)
    }

    private fun stageToolFromInventory(player: LocalPlayer, sourceSlot: Int): Boolean {
        val selected = player.inventory.selectedSlot
        if (selectSlotBefore < 0) selectSlotBefore = selected
        if (ownedHotbarSlot < 0) ownedHotbarSlot = selected
        return startInventorySwap(sourceSlot, 36 + selected, SwapPurpose.TOOL_STAGE)
    }

    private fun restoreToolSlot(): Boolean {
        val player = mc.player ?: run { clearToolState(); return false }
        val lease = toolInventoryLease
        if (lease != null) {
            val started = startOwnedRestore(lease, SwapPurpose.TOOL_RESTORE, selectSlotBefore)
            if (!started && !leaseStillOwned(lease)) {
                toolInventoryLease = null
                clearToolState()
            }
            return started
        }
        val inventory = player.inventory
        val stillOwnSlot = ownedHotbarSlot >= 0 && inventory.selectedSlot == ownedHotbarSlot &&
            expectedToolValue != null && InventorySlotValue.fromStack(inventory.getItem(ownedHotbarSlot)) == expectedToolValue
        if (stillOwnSlot && selectSlotBefore >= 0) inventory.selectedSlot = selectSlotBefore
        clearToolState()
        return false
    }

    private fun clearToolState() {
        selectSlotBefore = -1
        ownedHotbarSlot = -1
        swappedInventorySlot = -1
        expectedTool = null
        expectedToolValue = null
    }

    private fun isPickaxe(stack: ItemStack): Boolean = stack.`is`(ItemTags.PICKAXES)
    private fun isShard(stack: ItemStack): Boolean = AreaMiningRules.isShardPickaxe(isPickaxe(stack), stack.hoverName.string)

    private fun handleFood(player: net.minecraft.client.player.LocalPlayer): Boolean {
        if (!eatWhenHungry) {
            if (eating) {
                finishEating()
                return true
            }
            return false
        }
        if (eating) {
            eatingTicks++
            when (AreaFoodRules.progress(eatingStartFood, player.foodData.foodLevel, eatingTicks)) {
                AreaFoodProgress.RUNNING -> {
                    mc.options.keyUse.setDown(player.xRot < -60f)
                    return true
                }
                AreaFoodProgress.ATE, AreaFoodProgress.TIMED_OUT -> {
                    finishEating()
                    return true
                }
            }
        }
        if (!AreaFoodRules.hungry(player.foodData.foodLevel, player.health, player.maxHealth)) return false
        val selected = player.inventory.selectedSlot
        val hand = when {
            isEdibleFood(player.mainHandItem) -> InteractionHand.MAIN_HAND
            (0 until 9).firstOrNull { isEdibleFood(player.inventory.getItem(it)) } != null -> {
                val slot = (0 until 9).first { isEdibleFood(player.inventory.getItem(it)) }
                if (foodSlotBefore < 0) foodSlotBefore = selected
                player.inventory.selectedSlot = slot
                foodHotbarSlot = slot
                foodHotbarValue = InventorySlotValue.fromStack(player.inventory.getItem(slot))
                InteractionHand.MAIN_HAND
            }
            isEdibleFood(player.offhandItem) -> InteractionHand.OFF_HAND
            else -> {
                val sourceSlot = (9 until 36).firstOrNull { isEdibleFood(player.inventory.getItem(it)) }
                    ?: return false
                startInventorySwap(sourceSlot, 36 + selected, SwapPurpose.FOOD_STAGE)
                return true
            }
        }
        eatingStartFood = player.foodData.foodLevel
        eatingTicks = 0
        val result = mc.gameMode?.useItem(player, hand) ?: return false
        if (!result.consumesAction()) {
            val restorePending = restoreFoodSlot()
            eatingStartFood = -1
            return restorePending
        }
        eating = true
        mc.options.keyUse.setDown(true)
        return true
    }

    private fun isEdibleFood(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        val food = stack.get(DataComponents.FOOD) ?: return false
        val itemId = BuiltInRegistries.ITEM.getKey(stack.item)?.toString() ?: return false
        return AreaFoodRules.edible(food.nutrition(), itemId)
    }

    private fun finishEating() {
        runCatching { mc.options.keyUse.setDown(false) }
        eating = false
        eatingStartFood = -1
        eatingTicks = 0
        restoreFoodSlot()
    }

    private fun restoreFoodSlot(): Boolean {
        val player = mc.player ?: run { clearFoodSelection(); return false }
        val currentLease = foodInventoryLease
        if (currentLease != null) {
            when (val refresh = refreshLeaseForRestore(currentLease, foodStageDestinationVersion)) {
                is AreaLeaseRefresh.Ready -> foodInventoryLease = refresh.lease
                AreaLeaseRefresh.Waiting -> return false
                AreaLeaseRefresh.Invalid -> {
                    foodInventoryLease = null
                    clearFoodSelection()
                    return false
                }
            }
        }
        val lease = foodInventoryLease
        if (lease != null) {
            val started = startOwnedRestore(lease, SwapPurpose.FOOD_RESTORE, foodSlotBefore)
            if (!started && !leaseStillOwned(lease)) {
                foodInventoryLease = null
                clearFoodSelection()
            }
            return started
        }
        val stillOwn = foodHotbarSlot >= 0 && player.inventory.selectedSlot == foodHotbarSlot &&
            foodHotbarValue != null && InventorySlotValue.fromStack(player.inventory.getItem(foodHotbarSlot)) == foodHotbarValue
        if (stillOwn && foodSlotBefore >= 0) {
            player.inventory.selectedSlot = foodSlotBefore
        }
        clearFoodSelection()
        return false
    }

    private fun clearFoodSelection() {
        foodSlotBefore = -1
        foodHotbarSlot = -1
        foodHotbarValue = null
    }

    private fun restoreXpInventorySlot(): Boolean {
        val currentLease = xpInventoryLease ?: return false
        when (val refresh = refreshLeaseForRestore(currentLease, xpStageOffhandVersion)) {
            is AreaLeaseRefresh.Ready -> xpInventoryLease = refresh.lease
            AreaLeaseRefresh.Waiting -> return false
            AreaLeaseRefresh.Invalid -> {
                xpInventoryLease = null
                return false
            }
        }
        val lease = xpInventoryLease ?: return false
        val started = startOwnedRestore(lease, SwapPurpose.XP_RESTORE)
        if (!started && !leaseStillOwned(lease)) xpInventoryLease = null
        return started
    }

    private fun handleMending(player: LocalPlayer): Boolean {
        if (!mendWithExperience) {
            if (mending) {
                finishMending("")
                return true
            }
            return false
        }
        val tool = player.mainHandItem
        if (!mending) {
            if (tool.isEmpty || !tool.isDamageableItem || tool.getEnchantment(Enchantments.MENDING) <= 0 ||
                tool.maxDamage - tool.damageValue >= mendBelow.coerceAtLeast(1)) return false
            val hasExperience = player.offhandItem.`is`(Items.EXPERIENCE_BOTTLE) ||
                (0 until 36).any { player.inventory.getItem(it).`is`(Items.EXPERIENCE_BOTTLE) }
            if (!hasExperience || player.isUsingItem) return false
            mending = true
            mendStartedAt = tick
            mendOffhandUpdateVersion = serverSlotVersion(player.inventoryMenu.containerId, 45)
            mendHadOffhandBottle = player.offhandItem.`is`(Items.EXPERIENCE_BOTTLE)
            waitingForMendBottleEmptyAck = false
            stopBreaking()
            message("Tool worn; mending with XP bottles.", false)
        }

        if (!tool.isDamageableItem || tool.damageValue == 0) {
            finishMending("Tool mending ended.")
            return true
        }

        val offhandBottle = player.offhandItem.`is`(Items.EXPERIENCE_BOTTLE)
        val inventoryBottle = (0 until 36).any { player.inventory.getItem(it).`is`(Items.EXPERIENCE_BOTTLE) }
        if (tick - mendStartedAt > MEND_TIMEOUT_TICKS ||
            !offhandBottle && !inventoryBottle && !mendHadOffhandBottle) {
            finishMending("Tool mending ended.")
            return true
        }

        if (offhandBottle) {
            val version = serverSlotVersion(player.inventoryMenu.containerId, 45)
            val serverValue = authoritativeInventorySlots[player.inventoryMenu.containerId to 45]
            if (version > mendOffhandUpdateVersion && serverValue?.count == 0) {
                waitingForMendBottleEmptyAck = true
                mc.options.keyUse.setDown(false)
                return true
            }
            mendHadOffhandBottle = true
            mendOffhandUpdateVersion = maxOf(mendOffhandUpdateVersion, version)
            waitingForMendBottleEmptyAck = false
            mc.options.keyUse.setDown(player.xRot > 60f)
            return true
        }

        mc.options.keyUse.setDown(false)
        if (mendHadOffhandBottle) {
            waitingForMendBottleEmptyAck = true
            val version = serverSlotVersion(player.inventoryMenu.containerId, 45)
            val serverValue = authoritativeInventorySlots[player.inventoryMenu.containerId to 45]
            if (version <= mendOffhandUpdateVersion || serverValue?.count != 0) return true
            mendHadOffhandBottle = false
            waitingForMendBottleEmptyAck = false
            mendOffhandUpdateVersion = version
        }

        val level = mc.level ?: return true
        if (tick % MEND_SWAP_INTERVAL_TICKS != 0 || !settleInventoryForAction(player, level)) return true
        val xpInventorySlot = (0 until 36).firstOrNull { player.inventory.getItem(it).`is`(Items.EXPERIENCE_BOTTLE) }
            ?: run { finishMending("Out of XP bottles; resuming mining."); return true }
        val sourceMenuSlot = if (xpInventorySlot < 9) 36 + xpInventorySlot else xpInventorySlot
        startInventorySwap(sourceMenuSlot, 45, SwapPurpose.XP_STAGE)
        return true
    }

    private fun inventoryCalm(player: LocalPlayer): Boolean {
        val movingInput = with(mc.options) {
            listOf(keyUp, keyDown, keyLeft, keyRight, keyJump, keySprint, keyShift)
        }.any { it.isPressedOnAny }
        val velocity = player.deltaMovement
        val horizontalSpeedSquared = velocity.x * velocity.x + velocity.z * velocity.z
        return AreaInventoryCalmRules.still(movingInput, player.onGround(), horizontalSpeedSquared)
    }

    private fun settleInventoryForAction(player: LocalPlayer, level: ClientLevel): Boolean {
        if (inventoryCalm(player)) return true
        with(mc.options) {
            listOf(keyUp, keyDown, keyLeft, keyRight, keyJump, keySprint, keyUse, keyAttack).forEach { it.setDown(false) }
        }
        val velocity = player.deltaMovement
        val horizontalSpeedSquared = velocity.x * velocity.x + velocity.z * velocity.z
        val movingInput = with(mc.options) {
            listOf(keyUp, keyDown, keyLeft, keyRight, keyJump, keySprint)
        }.any { it.isPressedOnAny }
        if (movingInput || !player.onGround() || horizontalSpeedSquared >= 1.0e-4) return false
        val below = BlockPos.containing(player.x, player.y - 0.05, player.z)
        if (level.getBlockState(below).getCollisionShape(level, below).isEmpty) return true
        mc.options.keyShift.setDown(false)
        return false
    }

    private fun finishMending(reason: String) {
        mc.options.keyUse.setDown(false)
        mending = false
        mendStartedAt = 0
        waitingForMendBottleEmptyAck = false
        mendHadOffhandBottle = false
        restoreXpInventorySlot()
        if (reason.isNotBlank()) message(reason, false)
    }

    @Suppress("unused")
    private val rotation = handler<RotationUpdateEvent> {
        val player = mc.player ?: return@handler
        val target = currentTarget?.toBlockPos()
        val next = route.getOrNull(routeIndex)
        val rotation = when {
            eating -> Rotation(player.yRot, -75f)
            mending -> Rotation(player.yRot, 80f)
            next != null -> rotationTo(player, Vec3(next.x + 0.5, next.y.toDouble(), next.z + 0.5))
            target != null -> {
                val point = mc.level?.let { miningShot(player, it, target)?.point } ?: Vec3.atCenterOf(target)
                rotationTo(player, point)
            }
            else -> return@handler
        }
        RotationManager.setRotationTarget(
            RotationTarget(rotation, ticksUntilReset = 1, movementCorrection = MovementCorrection.STRICT),
            Priority.IMPORTANT_FOR_USAGE_1,
            this,
        )
    }

    private fun rotationTo(player: LocalPlayer, destination: Vec3): Rotation {
        val delta = destination.subtract(player.eyePosition)
        val horizontal = sqrt(delta.x * delta.x + delta.z * delta.z)
        val yaw = Math.toDegrees(atan2(-delta.x, delta.z)).toFloat()
        val pitch = Math.toDegrees(-atan2(delta.y, horizontal)).toFloat()
        return Rotation(yaw, pitch)
    }

    @Suppress("unused")
    private val moveInput = handler<MovementInputEvent> { event ->
        val player = mc.player ?: return@handler
        if (mc.gui.screen() != null || route.isEmpty()) return@handler
        val physical = with(mc.options) { listOf(keyUp, keyDown, keyLeft, keyRight) }.any { it.isPressedOnAny }
        if (physical) return@handler
        val next = route.getOrNull(routeIndex) ?: return@handler
        event.directionalInput = DirectionalInput.FORWARDS
        event.jump = next.y > player.blockPosition().y
    }

    @Suppress("unused")
    private val worldRender = handler<WorldRenderEvent> { event ->
        if (!showArea) return@handler
        val area = bounds ?: return@handler
        event.environment.withPositionRelativeToCamera {
            drawBox(
                AABB(area.minX.toDouble(), area.minY.toDouble(), area.minZ.toDouble(),
                    area.maxX + 1.0, area.maxY + 1.0, area.maxZ + 1.0),
                Color4b(50, 180, 255, 24),
                Color4b(50, 180, 255, 220),
            )
            currentTarget?.let { cell ->
                drawBox(AABB(cell.toBlockPos()), Color4b(255, 170, 45, 34), Color4b(255, 170, 45, 255))
            }
        }
    }

    private fun toGridCell(pos: BlockPos) = GridCell(pos.x, pos.y, pos.z)
    private fun GridCell.toBlockPos() = BlockPos(x, y, z)
    private fun GridCell.toCoordString() = "$x $y $z"

    private fun message(text: String, warn: Boolean, gap: Int = 0) {
        if (!chatFeedback && !warn) return
        val last = lastFeedbackTick[text] ?: Int.MIN_VALUE
        if (tick - last < gap) return
        lastFeedbackTick[text] = tick
        mc.player ?: return
        net.ccbluex.liquidbounce.utils.client.chat(Component.literal("[Donut Area Miner] $text"), this)
    }

    private fun clearRunState() {
        picking.clear()
        AreaMinerSelectionInput.clear(this)
        worldIdentity = null
        bounds = null
        firstPicked = null
        countY = 0
        countLayer = null
        initialEligible = 0
        pendingChunks.clear()
        scanY = 0
        scanCursor = null
        batch.clear()
        retryLedger.clear()
        planFailures.clear()
        protectedCells.clear()
        currentTarget = null
        targetShard = false
        breakTicks = 0
        breaking = false
        route = emptyList()
        routeIndex = 0
        routeTicks = 0
        routeGoal = null
        skippedCount = 0
        minedCount = 0
        selectSlotBefore = -1
        ownedHotbarSlot = -1
        swappedInventorySlot = -1
        expectedTool = null
        expectedToolValue = null
        beforeBreak = emptySet()
        clearFoodSelection()
        runCatching { mc.options.keyUse.setDown(false) }
        eating = false
        eatingStartFood = -1
        eatingTicks = 0
        mending = false
        mendStartedAt = 0
        tick = 0
        lastFeedbackTick = HashMap()
    }

}

/** Small bridge kept separate so MouseHandler never initializes the module during startup. */
object AreaMinerSelectionInput {
    @Volatile private var armedMiner: DonutAreaMiner? = null

    fun arm(miner: DonutAreaMiner) { armedMiner = miner }
    fun clear(miner: DonutAreaMiner) { if (armedMiner === miner) armedMiner = null }

    @JvmStatic
    fun consume(button: Int, action: Int): Boolean = armedMiner?.consumeSelectionClick(button, action) ?: false
}
