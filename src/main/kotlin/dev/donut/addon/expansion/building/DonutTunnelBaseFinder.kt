package dev.donut.addon.expansion.building

import dev.donut.addon.AddonCategories
import dev.donut.addon.expansion.automation.DonutDialogSnapshotCapture
import dev.donut.addon.expansion.automation.core.AuctionPurchaseConfirmation
import dev.donut.addon.expansion.automation.core.AuctionPurchaseConfirmationAction
import dev.donut.addon.expansion.automation.core.AuctionPurchaseEffect
import dev.donut.addon.expansion.automation.core.AuctionPurchaseFrame
import dev.donut.addon.expansion.automation.core.AuctionPurchaseHost
import dev.donut.addon.expansion.automation.core.AuctionPurchaseProgress
import dev.donut.addon.expansion.automation.core.AuctionPurchaseReceipt
import dev.donut.addon.expansion.automation.core.AuctionPurchaseRequest
import dev.donut.addon.expansion.automation.core.AuctionPurchaseStatus
import dev.donut.addon.expansion.automation.core.AuctionPurchaseWorkflow
import dev.donut.addon.expansion.automation.core.DonutSellCandidate
import dev.donut.addon.expansion.automation.core.DonutSellEffect
import dev.donut.addon.expansion.automation.core.DonutSellFrame
import dev.donut.addon.expansion.automation.core.DonutSellGridIdentityMode
import dev.donut.addon.expansion.automation.core.DonutSellIdentity
import dev.donut.addon.expansion.automation.core.DonutSellOfferAck
import dev.donut.addon.expansion.automation.core.DonutSellPartialReturnAck
import dev.donut.addon.expansion.automation.core.DonutSellRequest
import dev.donut.addon.expansion.automation.core.DonutSellResultAck
import dev.donut.addon.expansion.automation.core.DonutSellStatus
import dev.donut.addon.expansion.automation.core.DonutSellWorkflow
import dev.donut.addon.expansion.automation.core.AutomationSnapshot
import dev.donut.addon.expansion.automation.core.InventoryAckChannel
import dev.donut.addon.expansion.automation.core.InventoryAckStatus
import dev.donut.addon.expansion.automation.core.InventoryAckTracker
import dev.donut.addon.expansion.automation.core.InventorySlotValue
import dev.donut.addon.expansion.automation.core.InventorySwapExpectation
import dev.donut.addon.expansion.automation.core.InventorySyncFrame
import dev.donut.addon.expansion.automation.core.MarketRules
import dev.donut.addon.expansion.automation.core.MenuClassifier
import dev.donut.addon.expansion.automation.core.MenuKind
import dev.donut.addon.expansion.automation.core.MenuOpenAckTracker
import dev.donut.addon.expansion.automation.core.MenuOpenExpectation
import dev.donut.addon.expansion.automation.core.MenuSnapshot
import dev.donut.addon.expansion.automation.core.SessionStamp
import dev.donut.addon.expansion.automation.core.SlotSnapshot
import dev.donut.addon.expansion.search.SearchFeatures
import net.ccbluex.liquidbounce.config.types.list.Tagged
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
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.input.InputTracker.isPressedOnAny
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.inGame
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.gui.components.Button
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.tags.ItemTags
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.InteractionHand
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.FallingBlock
import net.minecraft.world.level.block.InfestedBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.SpawnerBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.floor
import java.math.BigDecimal
import net.ccbluex.liquidbounce.utils.item.getEnchantment
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/** Bounded Donut tunnel miner with local storage and spawner discovery. */
object DonutTunnelBaseFinder : ClientModule("Donut Tunnel Base Finder", AddonCategories.BASEFINDING, disableOnQuit = true), BuildingSessionParticipant {
    private enum class TunnelSize(override val tag: String) : Tagged {
        SHARD_3X3("3x3 Shard Pickaxe"), STANDARD_1X2("1x2")
    }
    private enum class FlagMode(override val tag: String) : Tagged {
        OFF("Off"), SUS("Sus Chunk"), BETTER("Better Sus Chunk"), BOTH("Both")
    }
    private enum class SwapPurpose { TOOL_STAGE, TOTEM_STAGE, FOOD_STAGE, XP_STAGE }
    private data class PendingInventorySwap(
        val purpose: SwapPurpose,
        val sourceMenuSlot: Int,
        val destinationMenuSlot: Int,
        val selectedSlot: Int,
        val startedAtTick: Long,
        val connectionId: Long,
        val world: ClientLevel,
        val player: LocalPlayer,
        val connection: ClientPacketListener,
        val menu: AbstractContainerMenu,
    )
    private data class StorageBlockUpdate(
        val generation: Long,
        val pos: BlockPos,
        val state: BlockState,
    )
    private data class ChunkRefresh(val generation: Long, val chunkKey: Long)
    private data class PendingTotemEvent(val generation: Long, val packet: ClientboundEntityEventPacket)
    private enum class PurchaseProduct(val queryItem: String, val itemId: String, val displayName: String, val amount: Int) {
        TOTEM("totem", "minecraft:totem_of_undying", "Totem", 1),
        GOLDEN_APPLE("golden_apple", "minecraft:golden_apple", "Golden Apples", 64),
        EXPERIENCE_BOTTLE("experience_bottle", "minecraft:experience_bottle", "XP Bottles", 64),
    }
    private data class QueuedPurchaseReceipt(val receipt: AuctionPurchaseReceipt)
    private data class QueuedSellRateLimit(val generation: Long, val rawText: String, val observedAtNanos: Long)
    private data class PurchaseAttemptBinding(val attemptId: Long, val session: SessionStamp)
    private data class AuctionLiveContext(
        val player: LocalPlayer,
        val level: ClientLevel,
        val connection: ClientPacketListener,
        val screen: net.minecraft.client.gui.screens.Screen?,
        val liveMenu: AbstractContainerMenu,
        val frame: AuctionPurchaseFrame,
    )
    private data class TunnelHopTask(
        val spot: BlockPos,
        val through: Boolean,
        val advanceRoute: Boolean,
        var from: BlockPos? = null,
        var floor: Double = Double.NaN,
        var centered: Boolean = false,
        var ticks: Int = 0,
    )

    private val size by enumChoice("Tunnel Size", TunnelSize.SHARD_3X3)
    private val wander by boolean("Wander", true)
    private val flags by enumChoice("Dig To Flagged Chunks", FlagMode.OFF)
    private val showVisited by boolean("Show Visited Chunks", true).visibleWhen { flags != FlagMode.OFF }
    private val storageToStop by int("Storage To Stop", 30, 3..300)
    private val baseRadius by int("Base Radius", 80, 16..192)
    private val stopAtSpawners by boolean("Stop At Spawners", true)
    private val disconnectOnFind by boolean("Disconnect On Find", true)
    private val quitOnTotemPop by boolean("Quit On Totem Pop", true)
    private val keepTotem by boolean("Keep Totem In Offhand", true)
    private val mendPickaxe by boolean("Mend Pickaxe", true)
    private val mendBelow by int("Mend Below Durability", 150, 20..1_000).visibleWhen { mendPickaxe }
    private val eatGoldenApples by boolean("Eat Golden Apples", true)
    private val sellJunk by boolean("Sell Junk For Room", true)
    private val buyFromAh by boolean("Buy From AH", true)
    private val maxPerListing by text("Max Price Per Listing", "10m").visibleWhen { buyFromAh }
    private val refreshMs by int("AH Refresh (ms)", 1_000, 300..3_000).visibleWhen { buyFromAh }
    private val chatFeedback by boolean("Chat Feedback", true)

    private val storageBlocks = setOf(
        Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.ENDER_CHEST, Blocks.HOPPER,
        Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.DROPPER, Blocks.DISPENSER,
    )
    private val visited = LinkedHashSet<Long>()
    private val blockedCells = HashSet<TunnelRoutePlanner.Cell>()
    private val foundPositions = HashMap<Long, Boolean>()
    private val pendingChunks = ArrayDeque<Long>()
    private val queuedChunks = HashSet<Long>()
    private val scannedChunks = HashSet<Long>()
    private val storageUpdates = TunnelPacketInbox<StorageBlockUpdate>(8_192)
    private val chunkRefreshes = TunnelPacketInbox<ChunkRefresh>(512)
    private val totemEvents = ConcurrentLinkedQueue<PendingTotemEvent>()

    private var worldIdentity: ClientLevel? = null
    private var start = BlockPos.ZERO
    private var axis = BlockPos.ZERO
    private var heading = TunnelRoutePlanner.Heading.NORTH
    private var cellSize = 1
    private var originX = 0
    private var originZ = 0
    private var tunnelY = 0
    private var tick = 0L
    private var nextDriftTick = 0L
    private var drift = 0
    private var randomState = 1
    private var terrainSeed = 1
    private var seenFrom = BlockPos.ZERO
    private var perceive = 4
    private var searchFailures = 0
    private var routeStart = TunnelRoutePlanner.Cell(0, 0)
    private var route = emptyList<TunnelRoutePlanner.Cell>()
    private var routeIndex = 0
    private var routeStepsSincePlan = 0
    private var targetBlock: BlockPos? = null
    private var hopTask: TunnelHopTask? = null
    private var targetFace = Direction.UP
    private var variedMiningAim = false
    private var miningAimU = 0.5
    private var miningAimV = 0.5
    private var breakReactionTicks = -1
    private var breaking = false
    private var breakTicks = 0
    private var streaming = false
    private var streamTarget: BlockPos? = null
    private var headBrokeAtTick = -100L
    private var decidedStreamHead: BlockPos? = null
    private var oldWayBlocksLeft = 0
    private val walkProgress = TunnelWalkProgress()
    private var standTarget: BlockPos? = null
    private var standSpot: Vec3? = null
    private var standVaried = false
    private var settleUntilTick = 0L
    private var nextRestTick = 0L
    private var restUntilTick = 0L
    private var idleRotation: Rotation? = null
    private var lingering = false
    private val failedTargets = HashMap<Long, Int>()
    private var selectedBefore = -1
    private var selectedByModule = -1
    private var walkTarget: Vec3? = null
    private var ownsCrouchForDrop = false
    private var aimTarget: Vec3? = null
    private var lastFlagNotice = Long.MIN_VALUE
    private var scanCenterChunk = Long.MIN_VALUE
    private var lastScanRefresh = 0L
    private var lastFindCheck = 0L
    private var screenSince = 0L
    private var pendingFind: Pair<String, BlockPos>? = null
    private var disconnectAtTick = 0L
    private var popSeen = false
    private var eating = false
    private var eatingFoodStart = -1
    private var eatingTicks = 0
    private var eatingHand: InteractionHand? = null
    private var mending = false
    private var mendStartedAtTick = 0L
    @Volatile private var inventoryGeneration = 0L
    private var inventoryConnectionId = 0L
    private var activeInventoryConnection: ClientPacketListener? = null
    private var activeInventoryWorld: ClientLevel? = null
    private var activeInventoryPlayer: LocalPlayer? = null
    private var activeInventoryMenu: AbstractContainerMenu? = null
    private val inventoryAckChannel = InventoryAckChannel()
    private val inventoryAckTracker = InventoryAckTracker()
    private val auctionPurchase = AuctionPurchaseWorkflow()
    private val auctionReceipts = ConcurrentLinkedQueue<QueuedPurchaseReceipt>()
    private val sellRateLimits = ConcurrentLinkedQueue<QueuedSellRateLimit>()
    private val auctionReceiptIds = AtomicLong(0L)
    private val purchaseCooldownUntil = HashMap<PurchaseProduct, Long>()
    @Volatile private var purchaseAttempt: PurchaseAttemptBinding? = null
    private var purchaseProduct: PurchaseProduct? = null
    private var purchaseListing: SlotSnapshot? = null
    private var auctionLiveContext: AuctionLiveContext? = null
    private var auctionPreviousMenuId = -1
    private var auctionOwnedMenuId = -1
    private var auctionOwnedScreenIdentity = Long.MIN_VALUE
    private var auctionConfirmationMenuId = -1
    private var auctionConfirmationScreenIdentity = Long.MIN_VALUE
    private var auctionActionTick = Long.MIN_VALUE
    private var mapEpoch = 0L
    private var mapEpochSignature = ""
    private val menuMapEpochs = HashMap<Int, Long>()
    private val acknowledgedMenuIds = HashMap<Int, Int>()
    private val fullContentEpochs = HashMap<Int, Long>()
    private val acceptedMenuSlotValues = HashMap<Int, MutableMap<Int, InventorySlotValue>>()
    private val acceptedMenuSlotRevisions = HashMap<Int, MutableMap<Int, Int>>()
    private val acceptedMenuCursorEmpty = HashMap<Int, Boolean>()
    private val acceptedMenuLatestState = HashMap<Int, Int>()
    private val acceptedMenuLatestArrivalOrdinal = HashMap<Int, Long>()
    private val acceptedMenuFullContentArrivalOrdinal = HashMap<Int, Long>()
    private val acceptedMenuUpdateEpochs = HashMap<Int, Long>()
    private var sellWorkflow = DonutSellWorkflow()
    private val sellHostPolicy = TunnelSellHostPolicy()
    private val sellMenuOpenAck = MenuOpenAckTracker()
    private val sellItemSlots = (0..52).toSet()
    private var sellWorkflowRunning = false
    private var sellRunId = 0L
    private var nextSellTick = 0L
    private var sellPreviousMenuId = -1
    private var sellOwnedMenuId = -1
    private var sellOwnedScreenIdentity = Long.MIN_VALUE
    private var sellOwnedMenuMapEpoch = -1L
    private var sellCandidates = emptyList<DonutSellCandidate>()
    private var sellBaselineInventoryCounts = emptyMap<String, Int>()
    private var sellExpectedInventoryCounts = emptyMap<String, Int>()
    private var sellResultInventoryExpected = false
    private var sellBaselineInventoryContentEpoch = 0L
    private var sellDeadlineTick = 0L
    private var sellCloseIssued = false
    private var sellMenuClosedAtNanos = 0L
    private var sellMenuClosedPacketOrdinal = 0L
    private var sellInventoryMapEpoch = 0L
    private var sellInventoryBeforeClose = emptyMap<Int, InventorySlotValue>()
    private var sellInventoryBeforeCapturedAtNanos = 0L
    private var sellInventoryBeforePacketOrdinal = 0L
    private var sellActionTick = Long.MIN_VALUE
    private var pendingSellOffer: DonutSellEffect.OfferStack? = null
    private var pendingSellOfferBeforeItems = emptyMap<Int, InventorySlotValue>()
    private var pendingSellConfirm: DonutSellEffect.ConfirmSale? = null
    private var pendingSellConfirmBeforeItems = emptyMap<Int, InventorySlotValue>()
    private var pendingInventorySwap: PendingInventorySwap? = null
    private var inventoryTick = 0L
    private val targetProgress = TunnelTargetProgress()

    init {
        enabledValue.onChanged { requested ->
            if (!requested && !inGame) endBuildingSession(disableModule = false)
        }
        literalDescription {
            "Digs a bounded 1x2 or Shard Pickaxe 3x3 tunnel, routes around unsafe cells, and stops when its loaded chunks reveal a base or non-spider spawner."
        }
    }

    override fun onEnabled() {
        val player = mc.player
        val level = mc.level
        if (player == null || level == null) {
            tell("Join a world before starting the tunnel miner.", true)
            enabled = false
            return
        }
        clearRun()
        if (++sellRunId <= 0L) sellRunId = 1L
        worldIdentity = level
        start = player.blockPosition()
        axis = start
        tunnelY = start.y
        heading = player.direction.toTunnelHeading()
        randomState = (start.x * 734_287_67) xor (start.z * 912_931) xor level.gameTime.toInt()
        terrainSeed = randomState
        nextDriftTick = 0L
        nextRestTick = tick + nextGapTicks()
        val shard = findTool(player, requireShard = true) != null
        cellSize = if (size == TunnelSize.SHARD_3X3 && shard) 3 else 1
        if (size == TunnelSize.SHARD_3X3 && !shard) tell("No Shard Pickaxe found; using a 1x2 tunnel.", true)
        if (findTool(player, requireShard = cellSize == 3) == null) {
            tell("No suitable pickaxe is available; stopped.", true)
            enabled = false
            return
        }
        originX = player.blockX - cellSize / 2
        originZ = player.blockZ - cellSize / 2
        selectHotbarTool(player, requireShard = cellSize == 3)
        worldIdentity = level
        routeStart = cellAt(player.blockX, player.blockZ)
        if (flags != FlagMode.OFF) enableFlagSources()
        tell("Digging a ${if (cellSize == 3) "3x3" else "1x2"} tunnel ${heading.name.lowercase()} at Y $tunnelY.", false)
    }

    override fun onDisabled() {
        releaseActions()
        val player = mc.player
        if (worldIdentity != null && worldIdentity === mc.level && player != null && selectedByModule >= 0 &&
            player.inventory.selectedSlot == selectedByModule && selectedBefore in 0..8) {
            player.inventory.selectedSlot = selectedBefore
        }
        clearRun()
    }

    /** Releases tunnel inputs and clears the inventory epoch when a world ends or the addon shuts down. */
    override fun endBuildingSession(disableModule: Boolean) {
        worldIdentity = null
        abandonBreaking()
        if (disableModule && enabled) enabled = false
        releaseActions()
        clearRun()
    }

    private fun clearRun() {
        releaseActions()
        visited.clear()
        blockedCells.clear()
        foundPositions.clear()
        pendingChunks.clear()
        queuedChunks.clear()
        scannedChunks.clear()
        failedTargets.clear()
        route = emptyList()
        routeIndex = 0
        routeStepsSincePlan = 0
        targetBlock = null
        hopTask = null
        variedMiningAim = false
        miningAimU = 0.5
        miningAimV = 0.5
        breakReactionTicks = -1
        streaming = false
        streamTarget = null
        headBrokeAtTick = -100L
        decidedStreamHead = null
        oldWayBlocksLeft = 0
        standTarget = null
        standSpot = null
        standVaried = false
        walkProgress.reset()
        selectedBefore = -1
        selectedByModule = -1
        tick = 0L
        nextDriftTick = 0L
        drift = 0
        settleUntilTick = 0L
        nextRestTick = 0L
        restUntilTick = 0L
        idleRotation = null
        lingering = false
        searchFailures = 0
        pendingFind = null
        disconnectAtTick = 0L
        popSeen = false
        eating = false
        eatingFoodStart = -1
        eatingTicks = 0
        eatingHand = null
        mending = false
        mendStartedAtTick = 0L
        pendingInventorySwap = null
        inventoryAckChannel.reset()
        inventoryAckTracker.reset()
        auctionPurchase.resetRun()
        auctionReceipts.clear()
        sellRateLimits.clear()
        sellHostPolicy.resetRun()
        purchaseCooldownUntil.clear()
        purchaseAttempt = null
        purchaseProduct = null
        purchaseListing = null
        auctionLiveContext = null
        auctionPreviousMenuId = -1
        auctionOwnedMenuId = -1
        auctionOwnedScreenIdentity = Long.MIN_VALUE
        auctionConfirmationMenuId = -1
        auctionConfirmationScreenIdentity = Long.MIN_VALUE
        auctionActionTick = Long.MIN_VALUE
        mapEpoch = 0L
        mapEpochSignature = ""
        menuMapEpochs.clear()
        acknowledgedMenuIds.clear()
        fullContentEpochs.clear()
        acceptedMenuSlotValues.clear()
        acceptedMenuSlotRevisions.clear()
        acceptedMenuCursorEmpty.clear()
        acceptedMenuLatestState.clear()
        acceptedMenuLatestArrivalOrdinal.clear()
        acceptedMenuFullContentArrivalOrdinal.clear()
        acceptedMenuUpdateEpochs.clear()
        resetJunkSale()
        activeInventoryConnection = null
        activeInventoryWorld = null
        activeInventoryPlayer = null
        activeInventoryMenu = null
        inventoryConnectionId = 0L
        inventoryTick = 0L
        activeTargetChunk = null
        targetMissingChecks = 0
        lastFlagNotice = Long.MIN_VALUE
        scanCenterChunk = Long.MIN_VALUE
        lastScanRefresh = 0L
        lastFindCheck = 0L
        worldIdentity = null
        screenSince = 0L
        targetProgress.reset()
        storageUpdates.clear()
        chunkRefreshes.clear()
        totemEvents.clear()
    }

    private val tickHandler = handler<GameTickEvent> {
        val player = mc.player
        val level = mc.level
        if (player == null || level == null || mc.gameMode == null) {
            enabled = false
            return@handler
        }
        if (worldIdentity !== level) {
            tell("Stopped because the world or dimension changed.", true)
            enabled = false
            return@handler
        }
        tick++
        if (player.isDeadOrDying) {
            tell("You died; tunnel mining stopped.", true)
            enabled = false
            return@handler
        }
        inventoryTick++
        if (!synchronizeInventorySession(player, level)) {
            enabled = false
            return@handler
        }
        drainSellRateLimits()
        var purchaseFrame = if (purchaseProduct != null || sellWorkflowRunning)
            captureAuctionContext(player, level)?.frame else null
        drainInventorySession(purchaseFrame)
        if ((purchaseProduct != null || sellWorkflowRunning) && purchaseFrame != null) {
            purchaseFrame = purchaseFrame.copy(
                acknowledgedMenuId = purchaseFrame.snapshot.menu?.let { acknowledgedMenuIds[it.menuId] },
                menuContentEpoch = purchaseFrame.snapshot.menu?.let { fullContentEpochs[it.menuId] ?: 0L } ?: 0L,
                transactionReceipts = if (purchaseProduct != null) drainPurchaseReceipts() else emptyList(),
            )
            val context = auctionLiveContext
            if (context != null) auctionLiveContext = context.copy(frame = purchaseFrame)
            if (sellWorkflowRunning) {
                purchaseFrame.snapshot.menu?.let { menu ->
                    observePendingSellAcknowledgement(menu.menuId, purchaseFrame.menuSlotMapEpoch)
                }
            }
            val openedMenu = purchaseFrame.snapshot.menu
            if (purchaseProduct != null && openedMenu != null && purchaseFrame.acknowledgedMenuId == openedMenu.menuId &&
                openedMenu.menuId != auctionPreviousMenuId && MenuClassifier.classify(openedMenu) == MenuKind.AUCTION) {
                auctionOwnedMenuId = openedMenu.menuId
                auctionOwnedScreenIdentity = openedMenu.screenIdentity
            }
        }
        drainWorldUpdates(level)
        if (processPendingInventorySwap(player, level)) return@handler
        if (popSeen) {
            popSeen = false
            tell("Totem popped; tunnel mining stopped.", true)
            if (quitOnTotemPop) {
                level.disconnect(Component.literal("[Donut Tunnel Base Finder] Totem popped at ${player.blockX} ${player.blockY} ${player.blockZ}"))
                enabled = false
                return@handler
            }
        }
        if (sellWorkflowRunning) {
            releaseActions()
            if (purchaseFrame == null) {
                abandonJunkSale("The sell workflow lost its player session.", disable = true)
            } else {
                tickJunkSale(purchaseFrame)
            }
            return@handler
        }
        if (purchaseProduct != null) {
            releaseActions()
            if (purchaseFrame == null) {
                stopAuctionPurchase("The auction purchase lost its client session.", cooldown = true)
                return@handler
            }
            tickAuctionPurchase(purchaseFrame)
            return@handler
        }
        val currentScreen = mc.gui.screen()
        if (currentScreen != null) {
            releaseActions()
            if (currentScreen is AbstractContainerScreen<*>) {
                if (screenSince == 0L) screenSince = tick
                if (tick - screenSince > 100L) player.closeContainer()
            } else {
                screenSince = 0L
            }
            return@handler
        }
        screenSince = 0L
        if (pendingFind != null) {
            releaseActions()
            if (disconnectOnFind && tick >= disconnectAtTick) {
                val (what, position) = pendingFind!!
                level.disconnect(Component.literal("[Donut Tunnel Base Finder] Found $what at ${position.x} ${position.y} ${position.z}"))
                enabled = false
            }
            return@handler
        }
        if (mending && handleMending(player, level)) return@handler
        if (eating && handleEating(player)) return@handler
        if (keepTotem && !player.offhandItem.`is`(Items.TOTEM_OF_UNDYING)) {
            releaseActions()
            val source = findInventoryMenuSlot(player) { it.`is`(Items.TOTEM_OF_UNDYING) }
            if (source == null) {
                if (!beginPurchase(PurchaseProduct.TOTEM, player)) {
                    tell("No totem is available for the offhand; Buy From AH is disabled or the purchase cannot start.", true)
                    enabled = false
                }
                return@handler
            }
            if (inventoryTick % 5L == 0L && calm(player)) {
                startInventorySwap(source, 45, SwapPurpose.TOTEM_STAGE, player, level)
            }
            return@handler
        }
        if (eatGoldenApples && shouldEat(player)) {
            if (hasGoldenApple(player)) {
                if (startEating(player, level)) return@handler
            } else if (beginPurchase(PurchaseProduct.GOLDEN_APPLE, player)) {
                return@handler
            }
        }
        if (handleMendingThreshold(player, level)) return@handler
        if (!ensureToolReady(player)) return@handler

        if (flags != FlagMode.OFF) visited += ChunkPos.pack(player.blockX shr 4, player.blockZ shr 4)
        if (flags != FlagMode.OFF && tick % 20L == 0L) watchFlagTarget(player, level)
        if (tick % 20L == 0L) detectFind(player, level)
        if (pendingFind != null) return@handler
        if (tick % 20L == 0L) queueNearbyChunks(player, level)
        scanLoadedChunks(level, 2)
        if (tick < restUntilTick) {
            updateIdleRotation(player)
            return@handler
        }
        if (tick >= nextRestTick) {
            releaseActions()
            restUntilTick = tick + nextLognormalTicks(30.0, 0.6, 10, 200)
            nextRestTick = restUntilTick + nextGapTicks()
            idleRotation = null
            updateIdleRotation(player)
            return@handler
        }
        if (tick < settleUntilTick) {
            releaseActions()
            if (lingering) updateIdleRotation(player)
            return@handler
        }
        lingering = false
        idleRotation = null
        if (routeIndex >= route.size) {
            route = emptyList()
            routeIndex = 0
            if (!planSegment(player, level)) {
                searchFailures++
                if (searchFailures >= 4) {
                    tell("No safe tunnel route remains nearby; stopped.", true)
                    enabled = false
                } else {
                    heading = heading.rotateClockwise()
                    axis = player.blockPosition()
                    tell("Blocked ahead; turning ${heading.name.lowercase()}.", true)
                }
                return@handler
            }
            searchFailures = 0
        }

        followRoute(player, level)
    }

    private var activeTargetChunk: Long? = null
    private var targetMissingChecks = 0

    private fun chooseFlagTarget(player: LocalPlayer, level: ClientLevel): Long? {
        if (flags == FlagMode.OFF) return null
        val active = activeTargetChunk
        if (active != null && active !in visited) {
            val targetX = ChunkPos.getX(active) * 16 + 8
            val targetZ = ChunkPos.getZ(active) * 16 + 8
            if (abs(player.blockX - targetX) <= 3 && abs(player.blockZ - targetZ) <= 3) {
                visited += active
                activeTargetChunk = null
                targetMissingChecks = 0
                targetProgress.reset()
                heading = player.direction.toTunnelHeading()
                axis = player.blockPosition()
                drift = 0
                return null
            }
            return active
        }
        activeTargetChunk = null
        val flagged = readFlaggedChunks(level)
        val candidates = flagged.asSequence()
            .filter { it !in visited }
            .filter { key ->
                abs(ChunkPos.getX(key) * 16 + 8 - start.x) > 48 || abs(ChunkPos.getZ(key) * 16 + 8 - start.z) > 48
            }
            .sortedBy { key -> hypot((ChunkPos.getX(key) * 16 + 8 - player.x), (ChunkPos.getZ(key) * 16 + 8 - player.z)) }
            .toList()
        activeTargetChunk = candidates.firstOrNull()
        targetProgress.reset()
        if (activeTargetChunk != null && tick % 200L == 0L) {
            tell("Routing toward a flagged chunk at ${ChunkPos.getX(activeTargetChunk!!) * 16 + 8}, ${ChunkPos.getZ(activeTargetChunk!!) * 16 + 8}.", false)
        }
        return activeTargetChunk
    }

    private fun readFlaggedChunks(level: ClientLevel): Set<Long> {
        val wanted = when (flags) {
            FlagMode.SUS -> setOf("sus")
            FlagMode.BETTER -> setOf("better")
            FlagMode.BOTH -> setOf("sus", "better")
            FlagMode.OFF -> emptySet()
        }
        val flagged = LinkedHashSet<Long>()
        if ("sus" in wanted) {
            val dimensionId = level.dimension().identifier().toString()
            val snapshot = TunnelChunkTargetProviders.susSnapshotFor(level, dimensionId)
            if (snapshot == null) missingFlagSource("sus") else flagged += snapshot.chunkKeys()
        }
        if ("better" in wanted) {
            val source = TunnelChunkTargetProviders.betterSusChunk
            if (source == null) {
                missingFlagSource("better")
            } else {
                val dimensionId = level.dimension().identifier().toString()
                val snapshot = TunnelChunkTargetProviders.snapshotFor(level, dimensionId)
                if (snapshot == null) missingFlagSource("better") else flagged += snapshot.chunkKeys()
            }
        }
        return flagged
    }

    private fun watchFlagTarget(player: LocalPlayer, level: ClientLevel) {
        val target = activeTargetChunk ?: return
        val chunkX = ChunkPos.getX(target)
        val chunkZ = ChunkPos.getZ(target)
        if (level.chunkSource.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) == null || target in readFlaggedChunks(level)) {
            targetMissingChecks = 0
            return
        }
        if (++targetMissingChecks < 10) return
        visited += target
        activeTargetChunk = null
        targetMissingChecks = 0
        targetProgress.reset()
        route = emptyList()
        routeIndex = 0
        routeStepsSincePlan = 0
        heading = player.direction.toTunnelHeading()
        axis = player.blockPosition()
        drift = 0
        tell("Left the flagged chunk at ${chunkX * 16 + 8}, ${chunkZ * 16 + 8}; it is no longer flagged.", false)
    }

    private fun missingFlagSource(id: String) {
        if (tick - lastFlagNotice < 600L) return
        lastFlagNotice = tick
        val name = if (id == "sus") "Sus Chunk Finder" else "Better Sus Chunk"
        tell("$name is not available; that target source is skipped.", true)
    }

    private fun synchronizeInventorySession(player: LocalPlayer, level: ClientLevel): Boolean {
        val connection = mc.connection ?: return false
        val menu = player.inventoryMenu
        if (activeInventoryConnection !== connection || activeInventoryWorld !== level ||
            activeInventoryPlayer !== player || activeInventoryMenu !== menu) {
            val hadSession = activeInventoryConnection != null || activeInventoryWorld != null ||
                activeInventoryPlayer != null || activeInventoryMenu != null
            val hadPendingSwap = pendingInventorySwap != null
            inventoryAckChannel.reset()
            inventoryAckTracker.reset()
            inventoryGeneration++
            pendingInventorySwap = null
            activeInventoryConnection = connection
            activeInventoryWorld = level
            activeInventoryPlayer = player
            activeInventoryMenu = menu
            inventoryConnectionId = System.identityHashCode(connection).toLong().coerceAtLeast(1L)
            storageUpdates.clear()
            chunkRefreshes.clear()
            if (hadPendingSwap || hadSession) {
                tell(if (hadPendingSwap) "Inventory session changed during an item swap; stopped." else "The player session changed; tunnel mining stopped.", true)
                return false
            }
        }
        inventoryAckChannel.setSession(inventoryGeneration, inventoryConnectionId)
        return true
    }

    private fun drainInventorySession(frame: AuctionPurchaseFrame?) {
        if (frame != null && purchaseProduct != null) auctionPurchase.captureConfirmationMenuMap(frame)
        inventoryAckChannel.drainStamped(inventoryGeneration, inventoryConnectionId) { envelope ->
            val update = envelope.update
            inventoryAckTracker.observe(update)
            val epoch = menuMapEpochs[update.menuId] ?: Long.MIN_VALUE
            if (frame != null && purchaseProduct != null) auctionPurchase.observeInventory(update, epoch)
            sellMenuOpenAck.observe(update)
            val previousState = acceptedMenuLatestState[update.menuId]
            if (previousState != null && update.stateId != previousState && !update.stateId.isAfterState(previousState)) {
                return@drainStamped
            }
            if (update.fullContents) {
                acknowledgedMenuIds[update.menuId] = update.menuId
                fullContentEpochs[update.menuId] = (fullContentEpochs[update.menuId] ?: 0L) + 1L
                acceptedMenuSlotValues[update.menuId] = update.slots.toMutableMap()
                acceptedMenuSlotRevisions[update.menuId] = update.slots.keys.associateWith { update.stateId }.toMutableMap()
                acceptedMenuFullContentArrivalOrdinal[update.menuId] = envelope.arrivalOrdinal
                acceptedMenuCursorEmpty[update.menuId] = update.cursor == null || update.cursor.count == 0
            } else if (update.slots.isNotEmpty()) {
                acceptedMenuSlotValues.getOrPut(update.menuId) { mutableMapOf() }.putAll(update.slots)
                acceptedMenuSlotRevisions.getOrPut(update.menuId) { mutableMapOf() }
                    .putAll(update.slots.keys.associateWith { update.stateId })
            }
            if (update.cursor != null) acceptedMenuCursorEmpty[update.menuId] = update.cursor.count == 0
            acceptedMenuLatestState[update.menuId] = update.stateId
            acceptedMenuLatestArrivalOrdinal[update.menuId] = envelope.arrivalOrdinal
            acceptedMenuUpdateEpochs[update.menuId] = (acceptedMenuUpdateEpochs[update.menuId] ?: 0L) + 1L
        }
    }

    private fun captureAuctionContext(player: LocalPlayer, level: ClientLevel): AuctionLiveContext? {
        val connection = mc.connection ?: return null
        if (activeInventoryPlayer !== player || activeInventoryWorld !== level || activeInventoryConnection !== connection) return null
        val screen = mc.gui.screen()
        val liveMenu = player.containerMenu
        val inventoryMenu = player.inventoryMenu
        val dialogSnapshot = DonutDialogSnapshotCapture.capture(screen)
        val menuSnapshot = if (screen is AbstractContainerScreen<*>) {
            snapshotMenu(screen.title.string, screen, liveMenu)
        } else null
        val inventorySnapshot = snapshotMenu("Player Inventory", inventoryMenu, inventoryMenu)
        val slotMap = if (menuSnapshot == null) emptyMap() else activeInventoryMap(liveMenu, player)
        val currentSignature = if (menuSnapshot == null) {
            "closed:${inventoryMenu.containerId}"
        } else {
            buildString {
                append(menuSnapshot.menuId).append(':').append(menuSnapshot.screenIdentity).append(':')
                slotMap.toSortedMap().forEach { (from, to) -> append(from).append('>').append(to).append(',') }
            }
        }
        if (currentSignature != mapEpochSignature) {
            mapEpochSignature = currentSignature
            mapEpoch++
            if (menuSnapshot != null) {
                menuMapEpochs[menuSnapshot.menuId] = mapEpoch
                acknowledgedMenuIds.remove(menuSnapshot.menuId)
                acceptedMenuSlotValues.remove(menuSnapshot.menuId)
                acceptedMenuSlotRevisions.remove(menuSnapshot.menuId)
                acceptedMenuCursorEmpty.remove(menuSnapshot.menuId)
                acceptedMenuLatestState.remove(menuSnapshot.menuId)
                acceptedMenuLatestArrivalOrdinal.remove(menuSnapshot.menuId)
                acceptedMenuFullContentArrivalOrdinal.remove(menuSnapshot.menuId)
            }
        }
        val activeEpoch = menuSnapshot?.let { menuMapEpochs[it.menuId] ?: mapEpoch } ?: mapEpoch
        val stamp = SessionStamp(
            generation = inventoryGeneration,
            connection = inventoryConnectionId,
            world = System.identityHashCode(level).toLong(),
            player = System.identityHashCode(player).toLong(),
        )
        val snapshot = AutomationSnapshot(
            session = stamp,
            tick = inventoryTick,
            menu = menuSnapshot,
            inventoryFingerprint = (0 until 36).joinToString("|") { index ->
                InventorySlotValue.fromStack(player.inventory.getItem(index)).fingerprint
            },
            dialog = dialogSnapshot,
            playerInventoryMenu = inventorySnapshot,
        )
        val containerCount = if (menuSnapshot == null) 0 else (liveMenu.slots.size - 36).coerceAtLeast(0)
        val provisional = AuctionPurchaseFrame(
            snapshot = snapshot,
            playerName = player.gameProfile.name,
            acknowledgedMenuId = menuSnapshot?.let { acknowledgedMenuIds[it.menuId] },
            activeInventorySlotMap = slotMap,
            menuSlotMapEpoch = activeEpoch,
            menuContentEpoch = menuSnapshot?.let { fullContentEpochs[it.menuId] ?: 0L } ?: 0L,
            containerSlotCount = containerCount,
            nextPageAction = menuSnapshot?.takeIf { MenuClassifier.classify(it) == MenuKind.AUCTION }
                ?.let { TunnelAuctionRules.nextPageAction(it, containerCount) },
            confirmation = when {
                menuSnapshot != null -> buildConfirmation(menuSnapshot, containerCount)
                dialogSnapshot != null -> purchaseListing?.let { TunnelAuctionRules.confirmationFromDialog(dialogSnapshot, it) }
                else -> null
            },
        )
        if (purchaseAttempt != null && provisional.confirmation != null) {
            when (val action = provisional.confirmation.action) {
                is AuctionPurchaseConfirmationAction.MenuSlot -> if (auctionConfirmationMenuId < 0 ||
                    auctionConfirmationMenuId == action.menuId && auctionConfirmationScreenIdentity == action.screenIdentity) {
                    auctionConfirmationMenuId = action.menuId
                    auctionConfirmationScreenIdentity = action.screenIdentity
                }
                is AuctionPurchaseConfirmationAction.DialogWidget -> if (auctionConfirmationMenuId < 0 ||
                    auctionConfirmationMenuId == -2 && auctionConfirmationScreenIdentity == action.screenIdentity) {
                    auctionConfirmationMenuId = -2
                    auctionConfirmationScreenIdentity = action.screenIdentity
                }
            }
        }
        return AuctionLiveContext(player, level, connection, screen, liveMenu, provisional)
            .also { auctionLiveContext = it }
    }

    private fun snapshotMenu(
        title: String,
        identity: Any,
        menu: AbstractContainerMenu,
    ): MenuSnapshot {
        val screenIdentity = System.identityHashCode(identity).toLong()
        val slots = menu.slots.map { slot -> snapshotSlot(slot) }
        val cursor = InventorySlotValue.fromStack(menu.carried)
        return MenuSnapshot(
            screenIdentity,
            menu.containerId,
            title,
            slots,
            menu.carried.isEmpty,
            menu.stateId,
            cursor,
        )
    }

    private fun snapshotSlot(slot: Slot): SlotSnapshot {
        val value = InventorySlotValue.fromStack(slot.item)
        return SlotSnapshot(
            index = slot.index,
            itemId = value.itemId,
            count = value.count,
            name = value.name,
            lore = value.lore,
            componentFingerprint = value.componentFingerprint,
            actualEnchantments = value.actualEnchantments,
            maxStackSize = slot.item.maxStackSize,
            procurementFingerprint = value.procurementFingerprint,
        )
    }

    private fun activeInventoryMap(menu: AbstractContainerMenu, player: LocalPlayer): Map<Int, Int> =
        menu.slots.asSequence()
            .filter { it.container === player.inventory }
            .mapNotNull { slot ->
                val inventoryIndex = slot.containerSlot
                val inventoryMenuIndex = when (inventoryIndex) {
                    in 9..35 -> inventoryIndex
                    in 0..8 -> inventoryIndex + 36
                    else -> return@mapNotNull null
                }
                slot.index to inventoryMenuIndex
            }
            .toMap()

    private fun buildConfirmation(menu: MenuSnapshot, containerSlotCount: Int): AuctionPurchaseConfirmation? {
        val expected = purchaseListing ?: return null
        if (MenuClassifier.classify(menu) != MenuKind.CONFIRMATION || !menu.cursorEmpty) return null
        val items = menu.slots.filter { slot ->
            slot.index in 0 until containerSlotCount &&
                slot.itemId == expected.itemId && slot.count == expected.count &&
                slot.actualEnchantments.toSortedMap() == expected.actualEnchantments.toSortedMap() &&
                slot.procurementFingerprint == expected.procurementFingerprint
        }
        val item = items.singleOrNull() ?: return null
        val actionSlot = TunnelAuctionRules.positiveConfirmationAction(menu, containerSlotCount) ?: return null
        val total = TunnelAuctionRules.confirmationTotal(menu, containerSlotCount) ?: return null
        val action = AuctionPurchaseConfirmationAction.MenuSlot(
            menu.menuId,
            menu.screenIdentity,
            actionSlot.index,
            actionSlot.fingerprint,
        )
        val evidence = listOf(
            menu.menuId.toString(), menu.screenIdentity.toString(), item.fingerprint,
            actionSlot.fingerprint, total.stripTrailingZeros().toPlainString(),
        ).joinToString("|")
        return AuctionPurchaseConfirmation(
            itemId = item.itemId,
            count = item.count,
            enchantments = item.actualEnchantments,
            totalPrice = total,
            screenIdentity = menu.screenIdentity,
            menuId = menu.menuId,
            evidenceFingerprint = evidence,
            action = action,
            procurementFingerprint = item.procurementFingerprint,
        )
    }

    private fun drainPurchaseReceipts(): List<AuctionPurchaseReceipt> {
        val result = ArrayList<AuctionPurchaseReceipt>()
        while (true) result += auctionReceipts.poll()?.receipt ?: break
        return result
    }

    private fun drainSellRateLimits() {
        repeat(16) {
            val queued = sellRateLimits.poll() ?: return
            if (queued.generation != inventoryGeneration) return@repeat
            val retryAt = sellHostPolicy.observeRateLimit(queued.rawText, queued.observedAtNanos) ?: return@repeat
            val pending = sellWorkflow.pendingEffect() as? DonutSellEffect.OpenSell ?: return@repeat
            sellWorkflow.rejectOpenCommand(pending.id, queued.observedAtNanos, retryAt)
        }
    }

    private fun beginPurchase(product: PurchaseProduct, player: LocalPlayer): Boolean {
        if (!buyFromAh) return false
        if (purchaseProduct != null) return purchaseProduct == product
        if (tick < (purchaseCooldownUntil[product] ?: 0L)) return true
        if (!calm(player)) return true
        if ((0..35).none { player.inventory.getItem(it).isEmpty }) {
            startJunkSaleForRoom(player, product)
            return true
        }
        val cap = TunnelAuctionRules.parseMoney(maxPerListing)
        if (cap == null || cap.signum() <= 0) {
            tell("Max Price Per Listing must be a positive amount before buying ${product.displayName}.", true)
            purchaseCooldownUntil[product] = tick + 1_200L
            return false
        }
        auctionPurchase.resetRequest()
        val request = AuctionPurchaseRequest(
            itemId = product.queryItem,
            amount = product.amount,
            maxListingTotal = cap,
            cumulativeBudget = null,
            maxRefreshes = 5,
            maxPages = 5,
            settleTicks = 2L,
            refreshDelayTicks = ((refreshMs + 49) / 50).toLong(),
            ackTimeoutTicks = 60L,
            allowedItemIds = setOf(product.queryItem, product.itemId),
        )
        if (!auctionPurchase.start(request)) {
            tell("The ${product.displayName} purchase request failed its safety checks.", true)
            purchaseCooldownUntil[product] = tick + 1_200L
            return false
        }
        purchaseProduct = product
        purchaseListing = null
        purchaseAttempt = null
        auctionOwnedMenuId = -1
        auctionOwnedScreenIdentity = Long.MIN_VALUE
        auctionConfirmationMenuId = -1
        auctionConfirmationScreenIdentity = Long.MIN_VALUE
        tell("Searching the AH for ${product.displayName}.", false)
        return true
    }

    private fun startJunkSaleForRoom(player: LocalPlayer, product: PurchaseProduct) {
        if (!sellJunk || sellWorkflowRunning || tick < nextSellTick || sellActionTick == inventoryTick ||
            player.containerMenu !== player.inventoryMenu || mc.gui.screen() != null) {
            purchaseCooldownUntil[product] = tick + 200L
            return
        }
        val nowNanos = System.nanoTime()
        if (!sellHostPolicy.shouldCheck(nowNanos) || !sellHostPolicy.canStart(nowNanos)) {
            purchaseCooldownUntil[product] = tick + 10L
            return
        }
        val connection = mc.connection ?: run { purchaseCooldownUntil[product] = tick + 200L; return }
        val level = mc.level ?: run { purchaseCooldownUntil[product] = tick + 200L; return }
        val selected = player.inventory.selectedSlot
        val stacks = (0..35).map { index -> index to InventorySlotValue.fromStack(player.inventory.getItem(index)) }
        val approved = TunnelSellRules.approvedStacks(stacks, selected)
        val wanted = sellHostPolicy.eligible(approved, nowNanos)
        if (wanted.isEmpty()) {
            if (approved.isNotEmpty()) tell("Previously unsellable junk is skipped; no safe stack is available for this sale.", true)
            else tell("No safe junk stacks are available to make AH inventory room.", true)
            nextSellTick = tick + if (approved.isNotEmpty()) 12_000L else 1_200L
            purchaseCooldownUntil[product] = tick + 200L
            return
        }
        if (!sellHostPolicy.begin(nowNanos, wanted)) {
            purchaseCooldownUntil[product] = tick + 10L
            return
        }
        sellPreviousMenuId = player.inventoryMenu.containerId
        sellOwnedMenuId = -1
        sellOwnedScreenIdentity = Long.MIN_VALUE
        sellOwnedMenuMapEpoch = -1L
        sellCandidates = wanted
        sellBaselineInventoryCounts = playerInventoryCounts(player.inventoryMenu)
        sellExpectedInventoryCounts = emptyMap()
        sellBaselineInventoryContentEpoch = fullContentEpochs[sellPreviousMenuId] ?: 0L
        sellCloseIssued = false
        sellMenuClosedAtNanos = 0L
        sellMenuClosedPacketOrdinal = 0L
        sellInventoryMapEpoch = inventoryGeneration
        sellInventoryBeforeClose = emptyMap()
        sellInventoryBeforeCapturedAtNanos = 0L
        sellInventoryBeforePacketOrdinal = 0L
        pendingSellOffer = null
        pendingSellOfferBeforeItems = emptyMap()
        pendingSellConfirm = null
        pendingSellConfirmBeforeItems = emptyMap()
        sellMenuOpenAck.begin(MenuOpenExpectation(inventoryConnectionId, sellPreviousMenuId))
        val session = SessionStamp(
            inventoryGeneration,
            inventoryConnectionId,
            System.identityHashCode(level).toLong(),
            System.identityHashCode(player).toLong(),
        )
        val request = DonutSellRequest(
            runId = sellRunId,
            session = session,
            candidates = wanted,
            sellItemSlots = sellItemSlots,
            confirmationSlot = 53,
            gridIdentityMode = DonutSellGridIdentityMode.ITEM_TYPE_ONLY,
        )
        if (!sellWorkflow.start(request, System.nanoTime())) {
            sellHostPolicy.finishWithoutOffer()
            sellMenuOpenAck.reset()
            purchaseCooldownUntil[product] = tick + 200L
            tell("Could not start a verified junk sale; the purchase will retry later.", true)
            return
        }
        sellWorkflowRunning = true
        sellActionTick = Long.MIN_VALUE
        tell("Opening the sell menu to free safe junk stacks before buying ${product.displayName}.", false)
    }
    private fun tickAuctionPurchase(frame: AuctionPurchaseFrame) {
        val product = purchaseProduct ?: return
        val progress = auctionPurchase.tick(frame, AuctionPurchaseHost(::dispatchAuctionEffect))
        if (progress.status == AuctionPurchaseStatus.RUNNING) return
        when (progress.status) {
            AuctionPurchaseStatus.COMPLETE -> {
                tell("Bought ${progress.acquiredAmount} ${product.displayName} and verified the inventory update.", false)
                closeOwnedAuction(frame)
            }
            AuctionPurchaseStatus.PARTIAL -> {
                tell("Bought ${progress.acquiredAmount} ${product.displayName}; the AH search ended before the full target.", true)
                closeOwnedAuction(frame)
            }
            AuctionPurchaseStatus.UNCERTAIN -> {
                tell("The ${product.displayName} purchase is uncertain; it will not be clicked again this run.", true)
                purchaseCooldownUntil[product] = tick + 1_200L
            }
            AuctionPurchaseStatus.INSUFFICIENT_FUNDS,
            AuctionPurchaseStatus.UNAVAILABLE,
            AuctionPurchaseStatus.FAILED,
            AuctionPurchaseStatus.BUDGET_EXHAUSTED -> {
                tell("Could not buy ${product.displayName}: ${progress.reason}", true)
                purchaseCooldownUntil[product] = tick + 1_200L
                closeOwnedAuction(frame)
            }
            AuctionPurchaseStatus.IDLE,
            AuctionPurchaseStatus.RUNNING -> Unit
        }
        auctionPurchase.resetRequest()
        purchaseProduct = null
        purchaseListing = null
        purchaseAttempt = null
        auctionReceipts.clear()
    }

    private fun closeOwnedAuction(frame: AuctionPurchaseFrame) {
        val menu = frame.snapshot.menu ?: return
        if (menu.menuId != auctionOwnedMenuId || menu.screenIdentity != auctionOwnedScreenIdentity) return
        if (MenuClassifier.classify(menu) != MenuKind.AUCTION) return
        dispatchAuctionEffect(frame, AuctionPurchaseEffect.CloseMenu(frame.snapshot.session, menu.menuId, menu.screenIdentity))
    }

    private fun dispatchAuctionEffect(frame: AuctionPurchaseFrame, effect: AuctionPurchaseEffect): Boolean {
        val context = auctionLiveContext ?: return false
        val player = context.player
        val level = context.level
        val connection = context.connection
        if (!enabled || !inGame || worldIdentity !== level || mc.player !== player || mc.level !== level ||
            mc.connection !== connection || activeInventoryConnection !== connection ||
            activeInventoryPlayer !== player || activeInventoryWorld !== level ||
            frame.snapshot.session != context.frame.snapshot.session || frame.snapshot.tick != inventoryTick ||
            auctionActionTick == frame.snapshot.tick) return false

        val currentMenu = frame.snapshot.menu
        val liveScreen = mc.gui.screen()
        val liveMenu = player.containerMenu
        fun currentAuction(menuId: Int, screenIdentity: Long): Boolean {
            val snapshot = currentMenu ?: return false
            return liveScreen is AbstractContainerScreen<*> && liveMenu === context.liveMenu &&
                liveMenu.containerId == menuId && snapshot.menuId == menuId && snapshot.screenIdentity == screenIdentity &&
                System.identityHashCode(liveScreen).toLong() == screenIdentity && frame.acknowledgedMenuId == menuId &&
                MenuClassifier.classify(snapshot) == MenuKind.AUCTION && snapshot.cursorEmpty && liveMenu.carried.isEmpty
        }

        val gameMode = mc.gameMode ?: return false
        val accepted = when (effect) {
            is AuctionPurchaseEffect.OpenSearch -> {
                val query = MarketRules.safeCommandFragment(effect.query) ?: return false
                if (effect.session != frame.snapshot.session || frame.snapshot.menu != null || liveScreen != null ||
                    liveMenu !== player.inventoryMenu || effect.previousMenuId != player.inventoryMenu.containerId ||
                    !player.inventoryMenu.carried.isEmpty) false
                else {
                    connection.sendCommand("ah $query")
                    auctionPreviousMenuId = effect.previousMenuId
                    auctionOwnedMenuId = -1
                    auctionOwnedScreenIdentity = Long.MIN_VALUE
                    auctionConfirmationMenuId = -1
                    auctionConfirmationScreenIdentity = Long.MIN_VALUE
                    true
                }
            }
            is AuctionPurchaseEffect.ClickListing -> {
                val menu = currentMenu
                val slot = menu?.slot(effect.slotIndex)
                if (effect.session != frame.snapshot.session || !currentAuction(effect.menuId, effect.screenIdentity) ||
                    effect.menuId == auctionPreviousMenuId || slot == null || slot.fingerprint != effect.expectedFingerprint ||
                    effect.slotIndex !in 0 until minOf(45, frame.containerSlotCount) ||
                    snapshotSlot(liveMenu.slots.getOrNull(effect.slotIndex) ?: return false).fingerprint != effect.expectedFingerprint) false
                else {
                    val listing = slot
                    purchaseListing = listing
                    purchaseAttempt = PurchaseAttemptBinding(effect.attemptId, effect.session)
                    gameMode.handleContainerInput(effect.menuId, effect.slotIndex, 0, ContainerInput.PICKUP, player)
                    auctionOwnedMenuId = effect.menuId
                    auctionOwnedScreenIdentity = effect.screenIdentity
                    auctionConfirmationMenuId = -1
                    auctionConfirmationScreenIdentity = Long.MIN_VALUE
                    true
                }
            }
            is AuctionPurchaseEffect.NextPage -> {
                val action = frame.nextPageAction
                val slot = currentMenu?.slot(effect.slotIndex)
                if (effect.session != frame.snapshot.session || !currentAuction(effect.menuId, effect.screenIdentity) ||
                    action == null || action.slotIndex != effect.slotIndex || action.expectedFingerprint != effect.expectedFingerprint ||
                    slot?.fingerprint != effect.expectedFingerprint || effect.slotIndex < 45 ||
                    snapshotSlot(liveMenu.slots.getOrNull(effect.slotIndex) ?: return false).fingerprint != effect.expectedFingerprint) false
                else {
                    gameMode.handleContainerInput(effect.menuId, effect.slotIndex, 0, ContainerInput.PICKUP, player)
                    true
                }
            }
            is AuctionPurchaseEffect.ClickConfirmation -> {
                val confirmation = frame.confirmation
                if (effect.session != frame.snapshot.session || confirmation == null ||
                    confirmation.evidenceFingerprint != effect.evidenceFingerprint || confirmation.action != effect.action ||
                    confirmation.itemId != purchaseListing?.itemId || confirmation.count != purchaseListing?.count) false
                else when (val action = effect.action) {
                    is AuctionPurchaseConfirmationAction.MenuSlot -> {
                        val slot = currentMenu?.slot(action.slotIndex)
                        if (MenuClassifier.classify(currentMenu ?: return false) != MenuKind.CONFIRMATION ||
                            currentMenu?.menuId != action.menuId || currentMenu.screenIdentity != action.screenIdentity ||
                            System.identityHashCode(liveScreen).toLong() != action.screenIdentity ||
                            action.menuId != auctionConfirmationMenuId || action.screenIdentity != auctionConfirmationScreenIdentity ||
                            slot?.fingerprint != action.expectedFingerprint || !currentMenu.cursorEmpty || !liveMenu.carried.isEmpty ||
                            snapshotSlot(liveMenu.slots.getOrNull(action.slotIndex) ?: return false).fingerprint != action.expectedFingerprint) false
                        else {
                            gameMode.handleContainerInput(action.menuId, action.slotIndex, 0, ContainerInput.PICKUP, player)
                            auctionConfirmationMenuId = action.menuId
                            auctionConfirmationScreenIdentity = action.screenIdentity
                            true
                        }
                    }
                    is AuctionPurchaseConfirmationAction.DialogWidget -> {
                        val capturedDialog = frame.snapshot.dialog
                        val liveDialog = DonutDialogSnapshotCapture.capture(liveScreen)
                        val currentWidget = DonutDialogSnapshotCapture.widget(liveScreen, action.widgetIndex)
                        val button = liveScreen?.children()?.getOrNull(action.widgetIndex) as? Button
                        val currentEvidence = liveDialog?.let { purchaseListing?.let { listing ->
                            TunnelAuctionRules.confirmationFromDialog(it, listing)
                        } }
                        if (capturedDialog == null || liveScreen !== context.screen || player.containerMenu !== context.liveMenu ||
                            liveDialog == null || liveDialog != capturedDialog ||
                            liveDialog.screenIdentity != action.screenIdentity || action.screenIdentity != auctionConfirmationScreenIdentity ||
                            auctionConfirmationMenuId != -2 || currentWidget?.fingerprint != action.expectedFingerprint ||
                            currentWidget?.label != action.expectedLabel || button == null || !button.active || !button.visible ||
                            button.message.string != action.expectedLabel || currentEvidence?.evidenceFingerprint != effect.evidenceFingerprint ||
                            currentEvidence?.action != action) false
                        else {
                            button.onPress(KeyEvent(257, 0, 0))
                            auctionConfirmationMenuId = -2
                            auctionConfirmationScreenIdentity = action.screenIdentity
                            true
                        }
                    }
                }
            }
            is AuctionPurchaseEffect.CloseMenu -> {
                if (effect.session != frame.snapshot.session || !currentAuction(effect.menuId, effect.screenIdentity) ||
                    effect.menuId != auctionOwnedMenuId || effect.screenIdentity != auctionOwnedScreenIdentity) false
                else {
                    player.closeContainer()
                    true
                }
            }
        }
        if (accepted) auctionActionTick = frame.snapshot.tick
        return accepted
    }

    private fun stopAuctionPurchase(message: String, cooldown: Boolean) {
        val product = purchaseProduct ?: return
        if (cooldown) purchaseCooldownUntil[product] = tick + 1_200L
        tell(message, true)
        auctionPurchase.resetRequest()
        purchaseProduct = null
        purchaseListing = null
        purchaseAttempt = null
        auctionReceipts.clear()
    }

    private fun tickJunkSale(frame: AuctionPurchaseFrame) {
        val context = auctionLiveContext ?: run {
            abandonJunkSale("The sell workflow lost its live menu context.", disable = true)
            return
        }
        val player = context.player
        val menu = frame.snapshot.menu
        if (sellOwnedMenuId < 0 && menu != null && menu.menuId != sellPreviousMenuId &&
            sellMenuOpenAck.openedMenuId() == menu.menuId && context.screen is AbstractContainerScreen<*> &&
            context.liveMenu !== player.inventoryMenu && TunnelSellRules.isSellMenu(menu, frame.containerSlotCount)) {
            sellOwnedMenuId = menu.menuId
            sellOwnedScreenIdentity = menu.screenIdentity
            sellOwnedMenuMapEpoch = frame.menuSlotMapEpoch
            sellHostPolicy.menuOpened()
        }
        if (sellHostPolicy.openingTimedOut(System.nanoTime()) && sellWorkflow.status() == DonutSellStatus.IDLE) {
            sellWorkflow = DonutSellWorkflow()
            sellHostPolicy.finishWithoutOffer()
            abandonJunkSale("The sell menu did not open within 10 seconds; the purchase will retry after a cooldown.", disable = false)
            return
        }
        if (sellHostPolicy.expired(System.nanoTime())) {
            closeOwnedSellMenu(frame, context)
            abandonJunkSale("The sell workflow exceeded its 45-second limit; stopped with inventory settlement untrusted.", disable = true)
            return
        }
        val playerSlots = buildMap {
            for ((activeSlot, inventoryMenuSlot) in frame.activeInventorySlotMap) {
                val inventorySlot = when (inventoryMenuSlot) {
                    in 0..8 -> inventoryMenuSlot
                    in 9..35 -> inventoryMenuSlot
                    in 36..44 -> inventoryMenuSlot - 36
                    else -> continue
                }
                put(inventorySlot, activeSlot)
            }
        }
        val sellFrame = DonutSellFrame(
            session = frame.snapshot.session,
            nowNanos = System.nanoTime(),
            canIssueCommand = menu == null && context.screen == null && player.containerMenu === player.inventoryMenu &&
                mc.gui.screen() == null && tick >= nextSellTick && sellActionTick != frame.snapshot.tick &&
                sellHostPolicy.maySendCommand(System.nanoTime()),
            menu = menu,
            menuMapEpoch = frame.menuSlotMapEpoch,
            menuOpenAcknowledged = menu != null && sellMenuOpenAck.openedMenuId() == menu.menuId,
            playerInventoryMenuSlots = playerSlots,
            sellItemSlots = sellItemSlots,
            confirmationSlot = menu?.slot(53)?.let { 53 },
            sellMenuHostVerified = menu != null && menu.menuId == sellOwnedMenuId &&
                menu.screenIdentity == sellOwnedScreenIdentity && sellMenuOpenAck.openedMenuId() == menu.menuId &&
                context.screen is AbstractContainerScreen<*> && context.liveMenu !== player.inventoryMenu &&
                TunnelSellRules.isSellMenu(menu, frame.containerSlotCount),
        )
        val effect = sellWorkflow.tick(sellFrame)
        if (effect != null) {
            val accepted = dispatchSellEffect(frame, sellFrame, effect, context)
            sellWorkflow.reportDispatch(effect.id, accepted, System.nanoTime())
            if (accepted) sellActionTick = frame.snapshot.tick
        }
        when (sellWorkflow.status()) {
            DonutSellStatus.COMPLETE, DonutSellStatus.PARTIAL -> finishJunkSale(frame, context)
            DonutSellStatus.FAILED -> {
                closeOwnedSellMenu(frame, context)
                sellHostPolicy.finishWithoutOffer()
                abandonJunkSale("The sell menu or inventory layout did not match the verified workflow; stopped safely.", disable = false)
            }
            DonutSellStatus.UNCERTAIN -> {
                closeOwnedSellMenu(frame, context)
                abandonJunkSale("The sell result could not be confirmed from server inventory updates; stopped safely.", disable = true)
            }
            else -> Unit
        }
    }

    private fun dispatchSellEffect(
        frame: AuctionPurchaseFrame,
        sellFrame: DonutSellFrame,
        effect: DonutSellEffect,
        context: AuctionLiveContext,
    ): Boolean {
        val player = context.player
        val level = context.level
        val connection = context.connection
        if (!enabled || !inGame || worldIdentity !== level || mc.player !== player || mc.level !== level ||
            mc.connection !== connection || activeInventoryConnection !== connection || activeInventoryPlayer !== player ||
            activeInventoryWorld !== level || frame.snapshot.session != context.frame.snapshot.session ||
            frame.snapshot.tick != inventoryTick || sellActionTick == frame.snapshot.tick || effect.session != frame.snapshot.session) return false
        val menu = frame.snapshot.menu
        val liveScreen = mc.gui.screen()
        val liveMenu = player.containerMenu
        val gameMode = mc.gameMode ?: return false
        when (effect) {
            is DonutSellEffect.OpenSell -> {
                if (menu != null || liveScreen != null || context.screen != null ||
                    liveMenu !== player.inventoryMenu || liveMenu.containerId != sellPreviousMenuId ||
                    !liveMenu.carried.isEmpty || !sellFrame.canIssueCommand) return false
                if (!sellHostPolicy.commandSent(System.nanoTime())) return false
                connection.sendCommand("sell")
                return true
            }
            is DonutSellEffect.OfferStack -> {
                if (!isOwnedSellMenu(frame, context) || menu == null || effect.menuId != menu.menuId ||
                    effect.screenIdentity != menu.screenIdentity || effect.menuMapEpoch != frame.menuSlotMapEpoch ||
                    effect.baselineStateId != menu.stateId || frame.activeInventorySlotMap.entries
                        .singleOrNull { (_, inventoryMenuSlot) -> inventoryMenuSlot in 9..44 &&
                            (if (inventoryMenuSlot >= 36) inventoryMenuSlot - 36 else inventoryMenuSlot) == effect.inventorySlot }
                        ?.key != effect.menuSlot || !menu.cursorEmpty || !liveMenu.carried.isEmpty ||
                    sellActionTick == frame.snapshot.tick || !sellMenuMatchesAcknowledgedState(menu, frame.containerSlotCount)) return false
                val menuSlot = menu.slot(effect.menuSlot) ?: return false
                val sourceValue = menuInventoryValue(menuSlot)
                val liveSource = liveMenu.slots.getOrNull(effect.menuSlot)?.let { InventorySlotValue.fromStack(it.item) } ?: return false
                if (sourceValue != effect.expectedStack || liveSource != effect.expectedStack) return false
                val before = sellMenuValues(menu, sellItemSlots) ?: return false
                pendingSellOffer = effect
                pendingSellOfferBeforeItems = before
                gameMode.handleContainerInput(effect.menuId, effect.menuSlot, 0, ContainerInput.QUICK_MOVE, player)
                return true
            }
            is DonutSellEffect.ConfirmSale -> {
                if (!isOwnedSellMenu(frame, context) || menu == null || effect.menuId != menu.menuId ||
                    effect.screenIdentity != menu.screenIdentity || effect.menuMapEpoch != frame.menuSlotMapEpoch ||
                    effect.baselineStateId != menu.stateId || effect.slot != 53 || !menu.cursorEmpty ||
                    !liveMenu.carried.isEmpty || sellActionTick == frame.snapshot.tick ||
                    !sellMenuMatchesAcknowledgedState(menu, frame.containerSlotCount)) return false
                val button = menu.slot(effect.slot) ?: return false
                val liveButton = liveMenu.slots.getOrNull(effect.slot)?.let { InventorySlotValue.fromStack(it.item) } ?: return false
                if (!TunnelSellRules.isConfirmSaleButton(button) || menuInventoryValue(button) != effect.expectedButton ||
                    liveButton != effect.expectedButton) return false
                pendingSellConfirm = effect
                pendingSellConfirmBeforeItems = sellMenuValues(menu, sellItemSlots) ?: return false
                gameMode.handleContainerInput(effect.menuId, effect.slot, 0, ContainerInput.PICKUP, player)
                return true
            }
        }
    }

    private fun observePendingSellAcknowledgement(menuId: Int, epoch: Long) {
        if (!sellWorkflowRunning) return
        val context = auctionLiveContext ?: return
        val frame = context.frame
        val menu = frame.snapshot.menu ?: return
        if (menu.menuId != menuId || menu.menuId != sellOwnedMenuId || menu.screenIdentity != sellOwnedScreenIdentity ||
            epoch != (menuMapEpochs[menuId] ?: Long.MIN_VALUE) || frame.menuSlotMapEpoch != epoch ||
            frame.acknowledgedMenuId != menuId || !isOwnedSellMenu(frame, context)) return
        val latestState = acceptedMenuLatestState[menuId] ?: return
        val slotValues = acceptedMenuSlotValues[menuId] ?: return
        val slotRevisions = acceptedMenuSlotRevisions[menuId] ?: return
        val after = sellItemSlots.associateWith { slotValues[it] ?: return }
        val cursorEmpty = acceptedMenuCursorEmpty[menuId] == true && menu.cursorEmpty && context.liveMenu.carried.isEmpty
        if (!cursorEmpty || !sellMenuMatchesAcknowledgedState(menu, frame.containerSlotCount)) return

        val offer = pendingSellOffer
        if (offer != null && latestState.isAfterState(offer.baselineStateId) &&
            slotRevisions[offer.menuSlot]?.isAfterState(offer.baselineStateId) == true) {
            val destinationUpdated = sellItemSlots.any { index ->
                pendingSellOfferBeforeItems[index] != after[index] && slotRevisions[index]?.isAfterState(offer.baselineStateId) == true
            }
            if (destinationUpdated) {
                val sourceAfter = slotValues[offer.menuSlot] ?: return
                val status = sellWorkflow.acknowledgeOffer(
                    DonutSellOfferAck(
                        effectId = offer.id,
                        session = offer.session,
                        menuId = offer.menuId,
                        screenIdentity = offer.screenIdentity,
                        menuMapEpoch = offer.menuMapEpoch,
                        serverStateId = latestState,
                        observedAtNanos = System.nanoTime(),
                        inventorySlot = offer.inventorySlot,
                        sourceBefore = offer.expectedStack,
                        sourceAfter = sourceAfter,
                        sellItemsBefore = pendingSellOfferBeforeItems,
                        sellItemsAfter = after,
                        cursorEmpty = true,
                        localReconciled = true,
                    ),
                )
                pendingSellOffer = null
                pendingSellOfferBeforeItems = emptyMap()
                if (status == DonutSellStatus.UNCERTAIN) pendingSellOffer = null
            }
        }

        val confirm = pendingSellConfirm
        if (confirm != null && latestState.isAfterState(confirm.baselineStateId)) {
            val status = sellWorkflow.acknowledgeSale(
                DonutSellResultAck(
                    effectId = confirm.id,
                    session = confirm.session,
                    menuId = confirm.menuId,
                    screenIdentity = confirm.screenIdentity,
                    menuMapEpoch = confirm.menuMapEpoch,
                    serverStateId = latestState,
                    observedAtNanos = System.nanoTime(),
                    sellItemsBefore = pendingSellConfirmBeforeItems,
                    sellItemsAfter = after,
                    cursorEmpty = true,
                    localReconciled = true,
                ),
                System.nanoTime(),
            )
            pendingSellConfirm = null
            pendingSellConfirmBeforeItems = emptyMap()
            if (status == DonutSellStatus.UNCERTAIN) pendingSellConfirm = null
        }
    }

    private fun finishJunkSale(frame: AuctionPurchaseFrame, context: AuctionLiveContext) {
        val player = context.player
        val status = sellWorkflow.status()
        val now = System.nanoTime()
        if (!sellResultInventoryExpected) {
            val sold = sellWorkflow.totals().sold
            sellExpectedInventoryCounts = sellBaselineInventoryCounts.mapValues { (item, count) ->
                (count - (sold[item] ?: 0)).coerceAtLeast(0)
            }.filterValues { it > 0 }
            sellResultInventoryExpected = true
            sellDeadlineTick = tick + 100L
        }
        val menu = frame.snapshot.menu
        if (!sellCloseIssued) {
            if (menu != null) {
                if (menu.menuId != sellOwnedMenuId || menu.screenIdentity != sellOwnedScreenIdentity ||
                    frame.menuSlotMapEpoch != sellOwnedMenuMapEpoch || player.containerMenu !== context.liveMenu ||
                    context.liveMenu.containerId != sellOwnedMenuId ||
                    System.identityHashCode(context.screen).toLong() != sellOwnedScreenIdentity) {
                    abandonJunkSale("The sell menu changed before the acknowledged result could settle; stopped safely.", disable = true)
                    return
                }
                if (status == DonutSellStatus.PARTIAL) {
                    val latestState = acceptedMenuLatestState[sellOwnedMenuId]
                    val beforeOrdinal = acceptedMenuLatestArrivalOrdinal[sellOwnedMenuId] ?: 0L
                    if (latestState != menu.stateId || beforeOrdinal <= 0L ||
                        acceptedMenuCursorEmpty[sellOwnedMenuId] != true ||
                        !sellMenuMatchesAcknowledgedState(menu, frame.containerSlotCount)) {
                        if (tick > sellDeadlineTick) abandonJunkSale("The remaining sell stacks were not reconciled before closing the menu.", disable = true)
                        return
                    }
                    val before = canonicalInventoryFromActiveFrame(frame)
                    if (before == null || sellInventoryMapEpoch <= 0L || sellInventoryMapEpoch != inventoryGeneration) {
                        if (tick > sellDeadlineTick) abandonJunkSale("The player inventory mapping could not be proved before closing the sell menu.", disable = true)
                        return
                    }
                    sellInventoryBeforeClose = before
                    sellInventoryBeforeCapturedAtNanos = System.nanoTime()
                    sellInventoryBeforePacketOrdinal = beforeOrdinal
                }
                player.closeContainer()
                sellCloseIssued = true
                sellMenuClosedAtNanos = System.nanoTime()
                sellMenuClosedPacketOrdinal = inventoryAckChannel.currentArrivalOrdinal(inventoryGeneration, inventoryConnectionId)
                    ?: run {
                        abandonJunkSale("The sell close could not be bound to the active inventory session.", disable = true)
                        return
                    }
                return
            }
            if (status == DonutSellStatus.PARTIAL) {
                abandonJunkSale("The sell screen closed before the returned stack baseline could be captured.", disable = true)
                return
            }
            // COMPLETE may arrive after the server closed its menu. Use this observation as the
            // close boundary and still require a later, full player-menu packet.
            sellCloseIssued = true
            sellMenuClosedAtNanos = now
            sellMenuClosedPacketOrdinal = inventoryAckChannel.currentArrivalOrdinal(inventoryGeneration, inventoryConnectionId)
                ?: run {
                    abandonJunkSale("The inventory close could not be bound to the active session.", disable = true)
                    return
                }
        }
        if (menu != null) {
            if (menu.menuId == sellOwnedMenuId && menu.screenIdentity == sellOwnedScreenIdentity && tick <= sellDeadlineTick) return
            abandonJunkSale("A different menu opened before the acknowledged sale settled; stopped safely.", disable = true)
            return
        }
        if (mc.gui.screen() != null || !player.inventoryMenu.carried.isEmpty) {
            abandonJunkSale("The sell menu changed before the acknowledged result could settle; stopped safely.", disable = true)
            return
        }
        if (player.containerMenu !== player.inventoryMenu) {
            if (sellCloseIssued && player.containerMenu.containerId == sellOwnedMenuId && tick <= sellDeadlineTick) return
            abandonJunkSale("The player inventory menu did not become active after closing the sell screen.", disable = true)
            return
        }
        val inventoryMenuId = player.inventoryMenu.containerId
        val fullOrdinal = acceptedMenuFullContentArrivalOrdinal[inventoryMenuId] ?: 0L
        val packetOrdinal = acceptedMenuLatestArrivalOrdinal[inventoryMenuId] ?: 0L
        val hasFreshInventory = fullOrdinal > sellMenuClosedPacketOrdinal && packetOrdinal > sellMenuClosedPacketOrdinal &&
            (fullContentEpochs[inventoryMenuId] ?: 0L) > sellBaselineInventoryContentEpoch
        if (!hasFreshInventory) {
            if (tick > sellDeadlineTick) abandonJunkSale("The server did not reconcile the sold inventory after closing its menu.", disable = true)
            return
        }
        val serverAfter = TunnelSellReturnRules.fromPlayerMenuSlots(acceptedMenuSlotValues[inventoryMenuId].orEmpty())
        val localAfter = canonicalInventoryFromNativeMenu(player.inventoryMenu)
        if (serverAfter == null || localAfter == null || !TunnelSellReturnRules.reconciled(serverAfter, localAfter)) {
            if (tick > sellDeadlineTick) abandonJunkSale("The server inventory update did not converge with the live player inventory.", disable = true)
            return
        }
        if (status == DonutSellStatus.PARTIAL) {
            val remaining = sellWorkflow.totals().remaining
            if (sellInventoryBeforeClose.keys != (0..35).toSet() ||
                sellInventoryBeforeCapturedAtNanos <= 0L || sellInventoryBeforePacketOrdinal <= 0L ||
                sellMenuClosedAtNanos < sellInventoryBeforeCapturedAtNanos ||
                sellMenuClosedPacketOrdinal < sellInventoryBeforePacketOrdinal ||
                sellInventoryMapEpoch <= 0L || sellInventoryMapEpoch != inventoryGeneration ||
                !TunnelSellReturnRules.returnedExactly(sellInventoryBeforeClose, serverAfter, remaining)) {
                abandonJunkSale("The returned sell stacks did not match the exact unsold inventory identities.", disable = true)
                return
            }
            val observedAt = System.nanoTime()
            sellWorkflow.acknowledgePartialReturn(
                DonutSellPartialReturnAck(
                    runId = sellRunId,
                    session = frame.snapshot.session,
                    sellMenuId = sellOwnedMenuId,
                    sellScreenIdentity = sellOwnedScreenIdentity,
                    menuMapEpoch = sellOwnedMenuMapEpoch,
                    observedAtNanos = observedAt,
                    inventoryBeforeCapturedAtNanos = sellInventoryBeforeCapturedAtNanos,
                    menuClosedAtNanos = sellMenuClosedAtNanos,
                    inventoryAfterCapturedAtNanos = observedAt,
                    inventoryBeforePacketOrdinal = sellInventoryBeforePacketOrdinal,
                    menuClosedPacketOrdinal = sellMenuClosedPacketOrdinal,
                    inventoryAfterPacketOrdinal = packetOrdinal,
                    inventoryMapEpochBeforeClose = sellInventoryMapEpoch,
                    inventoryMapEpochAfterClose = inventoryGeneration,
                    inventoryBeforeMenuId = sellOwnedMenuId,
                    inventoryAfterMenuId = inventoryMenuId,
                    inventoryBefore = sellInventoryBeforeClose,
                    inventoryAfter = serverAfter,
                    menuClosed = true,
                    cursorEmpty = player.inventoryMenu.carried.isEmpty && acceptedMenuCursorEmpty[inventoryMenuId] == true,
                    localReconciled = true,
                ),
            )
        } else if (inventoryIdentityCounts(serverAfter) != sellExpectedInventoryCounts) {
            abandonJunkSale("The acknowledged sale did not match the expected player inventory stacks.", disable = true)
            return
        }
        val totals = sellWorkflow.totals()
        val settlement = sellHostPolicy.settlement(totals.sold, totals.remaining, System.nanoTime())
        settlement.newlySkippedItemIds.forEach { itemId ->
            tell("Could not sell $itemId twice; skipping it for 10 minutes.", true)
        }
        val soldCount = sellWorkflow.totals().sold.values.sum()
        val suffix = if (status == DonutSellStatus.PARTIAL) "; unsold stacks were returned" else ""
        tell("Sold $soldCount safe junk items and verified the server inventory update$suffix.", status == DonutSellStatus.PARTIAL)
        val freeInventorySlots = (0..35).count { context.player.inventory.getItem(it).isEmpty }
        nextSellTick = tick + if (freeInventorySlots == 0) 1_200L else 80L
        clearJunkSaleState()
    }

    private fun canonicalInventoryFromActiveFrame(frame: AuctionPurchaseFrame): Map<Int, InventorySlotValue>? {
        val menu = frame.snapshot.menu ?: return null
        if (menu.menuId != sellOwnedMenuId || menu.screenIdentity != sellOwnedScreenIdentity ||
            frame.menuSlotMapEpoch != sellOwnedMenuMapEpoch) return null
        val values = menu.slots.associate { slot -> slot.index to menuInventoryValue(slot) }
        return TunnelSellReturnRules.fromActiveMenuSlots(values, frame.activeInventorySlotMap)
    }

    private fun canonicalInventoryFromNativeMenu(menu: AbstractContainerMenu): Map<Int, InventorySlotValue>? {
        val values = linkedMapOf<Int, InventorySlotValue>()
        for (inventorySlot in 0..35) {
            val menuSlot = if (inventorySlot < 9) inventorySlot + 36 else inventorySlot
            val slot = menu.slots.getOrNull(menuSlot) ?: return null
            values[inventorySlot] = InventorySlotValue.fromStack(slot.item)
        }
        return values
    }

    private fun inventoryIdentityCounts(stacks: Map<Int, InventorySlotValue>): Map<String, Int> =
        stacks.values.filter { it.count > 0 }
            .groupBy({ DonutSellIdentity.of(it) }, { it.count }).mapValues { it.value.sum() }

    private fun closeOwnedSellMenu(frame: AuctionPurchaseFrame, context: AuctionLiveContext) {
        val menu = frame.snapshot.menu ?: return
        if (menu.menuId != sellOwnedMenuId || menu.screenIdentity != sellOwnedScreenIdentity ||
            context.player.containerMenu !== context.liveMenu || context.liveMenu.containerId != sellOwnedMenuId ||
            System.identityHashCode(context.screen).toLong() != sellOwnedScreenIdentity) return
        context.player.closeContainer()
    }

    private fun isOwnedSellMenu(frame: AuctionPurchaseFrame, context: AuctionLiveContext): Boolean {
        val menu = frame.snapshot.menu ?: return false
        return menu.menuId == sellOwnedMenuId && menu.screenIdentity == sellOwnedScreenIdentity &&
            frame.acknowledgedMenuId == menu.menuId && sellMenuOpenAck.openedMenuId() == menu.menuId &&
            TunnelSellRules.isSellMenu(menu, frame.containerSlotCount) && menu.cursorEmpty &&
            context.screen is AbstractContainerScreen<*> && System.identityHashCode(context.screen).toLong() == menu.screenIdentity &&
            context.player.containerMenu === context.liveMenu && context.liveMenu.containerId == menu.menuId &&
            context.liveMenu.carried.isEmpty
    }

    private fun sellMenuMatchesAcknowledgedState(menu: MenuSnapshot, containerSlotCount: Int): Boolean {
        val acknowledged = acceptedMenuSlotValues[menu.menuId] ?: return false
        for (index in 0 until containerSlotCount) {
            val current = menu.slot(index) ?: return false
            val server = acknowledged[index] ?: return false
            if (current.itemId != server.itemId || current.count != server.count || current.name != server.name ||
                current.lore != server.lore || current.componentFingerprint != server.componentFingerprint ||
                current.actualEnchantments != server.actualEnchantments ||
                current.procurementFingerprint != server.procurementFingerprint) return false
        }
        return true
    }

    private fun sellMenuValues(menu: MenuSnapshot, indexes: Set<Int>): Map<Int, InventorySlotValue>? {
        val result = linkedMapOf<Int, InventorySlotValue>()
        for (index in indexes) result[index] = menuInventoryValue(menu.slot(index) ?: return null)
        return result
    }

    private fun menuInventoryValue(slot: SlotSnapshot): InventorySlotValue = InventorySlotValue(
        slot.itemId, slot.count, slot.name, slot.lore, slot.componentFingerprint,
        slot.actualEnchantments, slot.procurementFingerprint,
    )

    private fun playerInventoryCounts(menu: MenuSnapshot?): Map<String, Int> = menu?.slots.orEmpty()
        .asSequence().filter { it.index in 9..44 && it.count > 0 && it.itemId.isNotBlank() }
        .groupBy({ DonutSellIdentity.of(menuInventoryValue(it)) }, { it.count }).mapValues { it.value.sum() }

    private fun playerInventoryCounts(menu: AbstractContainerMenu): Map<String, Int> = menu.slots.asSequence()
        .filter { it.index in 9..44 && !it.item.isEmpty }
        .groupBy({ DonutSellIdentity.of(InventorySlotValue.fromStack(it.item)) }, { it.item.count })
        .mapValues { it.value.sum() }

    private fun Int.isAfterState(baseline: Int): Boolean {
        val mask = 0x7FFF
        val delta = ((this and mask) - (baseline and mask)) and mask
        return delta in 1 until 0x4000
    }

    private fun abandonJunkSale(message: String, disable: Boolean) {
        tell(message, true)
        val inventory = mc.player?.inventory
        val freeSlots = inventory?.let { inv -> (0..35).count { inv.getItem(it).isEmpty } } ?: 0
        nextSellTick = tick + if (freeSlots == 0) 1_200L else 600L
        if (!disable) sellHostPolicy.finishWithoutOffer()
        clearJunkSaleState()
        if (disable) enabled = false
    }

    private fun clearJunkSaleState() {
        sellMenuOpenAck.reset()
        sellWorkflowRunning = false
        sellPreviousMenuId = -1
        sellOwnedMenuId = -1
        sellOwnedScreenIdentity = Long.MIN_VALUE
        sellOwnedMenuMapEpoch = -1L
        sellCandidates = emptyList()
        sellBaselineInventoryCounts = emptyMap()
        sellExpectedInventoryCounts = emptyMap()
        sellResultInventoryExpected = false
        sellBaselineInventoryContentEpoch = 0L
        sellDeadlineTick = 0L
        sellCloseIssued = false
        sellMenuClosedAtNanos = 0L
        sellMenuClosedPacketOrdinal = 0L
        sellInventoryMapEpoch = 0L
        sellInventoryBeforeClose = emptyMap()
        sellInventoryBeforeCapturedAtNanos = 0L
        sellInventoryBeforePacketOrdinal = 0L
        sellActionTick = Long.MIN_VALUE
        pendingSellOffer = null
        pendingSellOfferBeforeItems = emptyMap()
        pendingSellConfirm = null
        pendingSellConfirmBeforeItems = emptyMap()
    }

    private fun resetJunkSale() {
        sellWorkflow = DonutSellWorkflow()
        clearJunkSaleState()
        nextSellTick = 0L
    }
    private fun processPendingInventorySwap(player: LocalPlayer, level: ClientLevel): Boolean {
        val pending = pendingInventorySwap ?: return false
        val menu = player.inventoryMenu
        if (pending.world !== level || pending.player !== player || pending.connection !== mc.connection ||
            pending.menu !== menu || pending.connectionId != inventoryConnectionId ||
            player.containerMenu !== menu || mc.gui.screen() != null || player.inventory.selectedSlot != pending.selectedSlot) {
            inventoryAckTracker.reset()
            pendingInventorySwap = null
            tell("Inventory state changed before the item swap was confirmed; stopped.", true)
            enabled = false
            return true
        }
        when (inventoryAckTracker.status()) {
            InventoryAckStatus.PENDING -> {
                if (inventoryTick - pending.startedAtTick > 100L) {
                    inventoryAckTracker.abandon()
                    inventoryAckTracker.reset()
                    pendingInventorySwap = null
                    tell("The server did not confirm the item swap; stopped.", true)
                    enabled = false
                }
            }
            InventoryAckStatus.ACKNOWLEDGED -> {
                val source = menu.slots.getOrNull(pending.sourceMenuSlot)?.let { InventorySlotValue.fromStack(it.item) }
                val destination = menu.slots.getOrNull(pending.destinationMenuSlot)?.let { InventorySlotValue.fromStack(it.item) }
                val reconciled = inventoryAckTracker.reconcileLocal(source, destination, menu.carried.isEmpty)
                if (reconciled == InventoryAckStatus.ACKNOWLEDGED) {
                    inventoryAckTracker.reset()
                    pendingInventorySwap = null
                    if (pending.purpose == SwapPurpose.TOTEM_STAGE && !player.offhandItem.`is`(Items.TOTEM_OF_UNDYING)) {
                        tell("The server acknowledged a swap, but no totem appeared in the offhand; stopped.", true)
                        enabled = false
                    }
                } else {
                    inventoryAckTracker.reset()
                    pendingInventorySwap = null
                    tell("The acknowledged item swap did not match the local inventory; stopped.", true)
                    enabled = false
                }
            }
            InventoryAckStatus.REJECTED, InventoryAckStatus.ABANDONED, InventoryAckStatus.IDLE -> {
                inventoryAckTracker.reset()
                pendingInventorySwap = null
                tell("The server rejected the item swap; stopped.", true)
                enabled = false
            }
        }
        return true
    }

    private fun startInventorySwap(
        sourceMenuSlot: Int,
        destinationMenuSlot: Int,
        purpose: SwapPurpose,
        player: LocalPlayer,
        level: ClientLevel,
    ): Boolean {
        if (pendingInventorySwap != null) return true
        val connection = mc.connection ?: return false
        val menu = player.inventoryMenu
        val gameMode = mc.gameMode ?: return false
        if (player.containerMenu !== menu || mc.gui.screen() != null || !menu.carried.isEmpty ||
            activeInventoryPlayer !== player || activeInventoryWorld !== level || activeInventoryConnection !== connection ||
            activeInventoryMenu !== menu || sourceMenuSlot !in 9..44 || destinationMenuSlot !in 36..45 ||
            sourceMenuSlot == destinationMenuSlot || !calm(player)) return false
        val source = menu.slots.getOrNull(sourceMenuSlot)?.let { InventorySlotValue.fromStack(it.item) } ?: return false
        val destination = menu.slots.getOrNull(destinationMenuSlot)?.let { InventorySlotValue.fromStack(it.item) } ?: return false
        if (source.count <= 0 || source == destination) return false
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
            purpose, sourceMenuSlot, destinationMenuSlot, player.inventory.selectedSlot,
            inventoryTick, inventoryConnectionId, level, player, connection, menu,
        )
        val button = if (destinationMenuSlot == 45) 40 else destinationMenuSlot - 36
        gameMode.handleContainerInput(menu.containerId, sourceMenuSlot, button, ContainerInput.SWAP, player)
        return true
    }

    private fun ensureToolReady(player: LocalPlayer): Boolean {
        val requireShard = cellSize == 3
        if (matchesTool(player.mainHandItem, requireShard)) {
            val tool = player.mainHandItem
            val remaining = tool.maxDamage - tool.damageValue
            val hasMending = tool.getEnchantment(Enchantments.MENDING) > 0
            if (TunnelResourceRules.shouldStopPickaxe(remaining, mendPickaxe, hasMending)) {
                tell("The pickaxe is almost broken; tunnel mining stopped before it breaks.", true)
                enabled = false
                return false
            }
            return true
        }
        val hotbar = (0..8).firstOrNull { matchesTool(player.inventory.getItem(it), requireShard) }
        if (hotbar != null) {
            selectHotbarSlot(player, hotbar)
            return true
        }
        val source = (9..35).firstOrNull { matchesTool(player.inventory.getItem(it), requireShard) }
        if (source == null) {
            tell("The required pickaxe is no longer available; stopped.", true)
            enabled = false
            return false
        }
        releaseActions()
        if (calm(player)) {
            startInventorySwap(source, 36 + player.inventory.selectedSlot, SwapPurpose.TOOL_STAGE, player, mc.level ?: return false)
        }
        return false
    }

    private fun selectHotbarSlot(player: LocalPlayer, slot: Int) {
        if (player.inventory.selectedSlot == slot) return
        if (selectedBefore < 0) selectedBefore = player.inventory.selectedSlot
        player.inventory.selectedSlot = slot
        selectedByModule = slot
    }

    private fun findInventoryMenuSlot(player: LocalPlayer, predicate: (ItemStack) -> Boolean): Int? {
        for (inventorySlot in 0..35) {
            if (!predicate(player.inventory.getItem(inventorySlot))) continue
            return if (inventorySlot < 9) 36 + inventorySlot else inventorySlot
        }
        return null
    }

    private fun calm(player: LocalPlayer): Boolean {
        releaseActions()
        val physicalMovement = with(mc.options) {
            listOf(keyUp, keyDown, keyLeft, keyRight, keyJump, keyShift, keySprint).any { it.isPressedOnAny }
        }
        return TunnelResourceRules.calm(physicalMovement, player.onGround(), player.deltaMovement.horizontalDistanceSqr())
    }

    private fun shouldEat(player: LocalPlayer): Boolean {
        return TunnelResourceRules.hungry(player.foodData.foodLevel, player.health, player.maxHealth)
    }

    private fun hasGoldenApple(player: LocalPlayer): Boolean = player.offhandItem.`is`(Items.GOLDEN_APPLE) ||
        findInventoryMenuSlot(player) { it.`is`(Items.GOLDEN_APPLE) } != null

    private fun startEating(player: LocalPlayer, level: ClientLevel): Boolean {
        if (!calm(player)) return false
        if (player.offhandItem.`is`(Items.GOLDEN_APPLE)) return beginEating(player, InteractionHand.OFF_HAND)
        val source = findInventoryMenuSlot(player) { it.`is`(Items.GOLDEN_APPLE) } ?: return false
        releaseActions()
        if (source !in 36..44) {
            startInventorySwap(source, 36 + player.inventory.selectedSlot, SwapPurpose.FOOD_STAGE, player, level)
            return true
        }
        selectHotbarSlot(player, source - 36)
        return beginEating(player, InteractionHand.MAIN_HAND)
    }

    private fun beginEating(player: LocalPlayer, hand: InteractionHand): Boolean {
        val gameMode = mc.gameMode ?: return false
        val result = gameMode.useItem(player, hand)
        if (!result.consumesAction()) return false
        eating = true
        eatingFoodStart = player.foodData.foodLevel
        eatingTicks = 0
        eatingHand = hand
        mc.options.keyUse.setDown(true)
        return true
    }

    private fun handleEating(player: LocalPlayer): Boolean {
        releaseActions()
        val hand = eatingHand
        val stillHoldingFood = when (hand) {
            InteractionHand.MAIN_HAND -> player.mainHandItem.`is`(Items.GOLDEN_APPLE)
            InteractionHand.OFF_HAND -> player.offhandItem.`is`(Items.GOLDEN_APPLE)
            null -> false
        }
        if (!stillHoldingFood || player.foodData.foodLevel > eatingFoodStart || ++eatingTicks > 70) {
            mc.options.keyUse.setDown(false)
            eating = false
            eatingFoodStart = -1
            eatingTicks = 0
            eatingHand = null
            return true
        }
        aimTarget = null
        mc.options.keyUse.setDown(player.xRot < -60f)
        return true
    }

    private fun handleMendingThreshold(player: LocalPlayer, level: ClientLevel): Boolean {
        if (!mendPickaxe) return false
        val tool = player.mainHandItem
        if (tool.isEmpty || !tool.isDamageableItem || tool.getEnchantment(Enchantments.MENDING) <= 0) return false
        val remaining = tool.maxDamage - tool.damageValue
        if (remaining >= mendBelow) return false
        val xpAvailable = player.offhandItem.`is`(Items.EXPERIENCE_BOTTLE) ||
            findInventoryMenuSlot(player) { it.`is`(Items.EXPERIENCE_BOTTLE) } != null
        if (!xpAvailable) return beginPurchase(PurchaseProduct.EXPERIENCE_BOTTLE, player)
        releaseActions()
        mending = true
        mendStartedAtTick = inventoryTick
        tell("Mending the pickaxe with XP bottles.", false)
        return handleMending(player, level)
    }

    private fun handleMending(player: LocalPlayer, level: ClientLevel): Boolean {
        if (!mending) return false
        releaseActions()
        val tool = player.mainHandItem
        val repaired = matchesTool(tool, cellSize == 3) && tool.damageValue == 0
        val xpInInventory = findInventoryMenuSlot(player) { it.`is`(Items.EXPERIENCE_BOTTLE) }
        if (repaired || inventoryTick - mendStartedAtTick > 1_200L ||
            !player.offhandItem.`is`(Items.EXPERIENCE_BOTTLE) && xpInInventory == null) {
            mc.options.keyUse.setDown(false)
            mending = false
            return true
        }
        if (!player.offhandItem.`is`(Items.EXPERIENCE_BOTTLE)) {
            mc.options.keyUse.setDown(false)
            if (inventoryTick % 5L == 0L && calm(player)) {
                startInventorySwap(xpInInventory ?: return true, 45, SwapPurpose.XP_STAGE, player, level)
            }
            return true
        }
        mc.options.keyUse.setDown(player.xRot > 60f)
        return true
    }

    private fun enableFlagSources() {
        if (flags == FlagMode.SUS || flags == FlagMode.BOTH) {
            if (TunnelChunkTargetProviders.susChunk == null) {
                missingFlagSource("sus")
            } else if (!SearchFeatures.GROWTH_SURVEY.running) {
                SearchFeatures.GROWTH_SURVEY.toggle()
                tell("Turned on Donut Suspect Chunks to find chunks to dig to.", false)
            }
        }
        if (flags == FlagMode.BETTER || flags == FlagMode.BOTH) {
            if (TunnelChunkTargetProviders.betterSusChunk == null) {
                missingFlagSource("better")
            } else if (!SearchFeatures.GROWTH_INSPECTOR.running) {
                SearchFeatures.GROWTH_INSPECTOR.toggle()
                tell("Turned on Donut Growth Inspector to find chunks to dig to.", false)
            }
        }
    }

    private fun followRoute(player: LocalPlayer, level: ClientLevel) {
        val activeHop = hopTask
        if (activeHop != null) {
            val goal = Vec3(activeHop.spot.x + 0.5, activeHop.spot.y.toDouble(), activeHop.spot.z + 0.5)
            if (pauseAtDeepDrop(level, player, goal)) return
            if (tickTunnelHop(player, level, activeHop)) return
        }
        val next = route.getOrNull(routeIndex) ?: return
        val from = if (routeIndex == 0) routeStart else route[routeIndex - 1]
        val fromCenter = center(from)
        val nextCenter = center(next)
        val direction = directionBetween(from, next) ?: run { route = emptyList(); return }
        seenFrom = player.blockPosition()
        if (pauseAtDeepDrop(level, player, nextCenter)) return
        if (!safeCell(level, next, tunnelY)) {
            stopBreaking()
            targetBlock = null
            route = emptyList()
            routeIndex = 0
            routeStepsSincePlan = 0
            return
        }
        if (standTarget == null) {
            when (moveToward(level, player, fromCenter, fromCenter.add(0.0, 1.2, 0.0))) {
                TunnelWalkProgress.State.MOVING -> return
                TunnelWalkProgress.State.STALLED -> {
                    abandonBrokenPath(next)
                    return
                }
                TunnelWalkProgress.State.ARRIVED -> Unit
            }
        }
        walkTarget = null

        val head = nextCenter.add(0.0, 1.0, 0.0)
        val foot = nextCenter
        val mine = when {
            isSolid(level, head) -> head
            isSolid(level, foot) -> foot
            else -> null
        }
        if (tryStraightStream(player, level, next, direction, mine)) return
        if (mine == null) {
            stopBreaking()
            targetBlock = null
            clearStandPlan()
            when (TunnelHopRules.emptyCellAction(distanceFlat(player, nextCenter), distanceFlat(player, fromCenter))) {
                TunnelHopRules.EmptyCellAction.ADVANCE -> advanceRouteCell()
                TunnelHopRules.EmptyCellAction.RETURN_TO_PREVIOUS ->
                    startTunnelHop(BlockPos.containing(fromCenter), through = true, advanceRoute = false)
                TunnelHopRules.EmptyCellAction.HOP_TO_NEXT -> {
                    val nextNext = route.getOrNull(routeIndex + 1)
                    val through = nextNext != null && directionBetween(next, nextNext) == direction &&
                        tunnelMineTarget(level, nextNext) == null
                    startTunnelHop(BlockPos.containing(nextCenter), through, advanceRoute = true)
                }
            }
            return
        }

        val pos = BlockPos.containing(mine)
        val state = level.getBlockState(pos)
        if (!mineable(level, pos) || !safeFluidBoundary(level, pos)) {
            blockedCells += next
            stopBreaking()
            route = emptyList()
            return
        }
        val safeStand = findStandSpot(level, player, pos, direction)
        if (safeStand == null) {
            if (distanceFlat(player, fromCenter) > 0.7) {
                startTunnelHop(BlockPos.containing(fromCenter), through = false, advanceRoute = false)
            } else {
                blockedCells += next
                stopBreaking()
                route = emptyList()
                routeIndex = 0
                routeStepsSincePlan = 0
                tell("No safe standing position is available for the next block; routing around it.", true)
            }
            return
        }
        when (moveToward(level, player, safeStand, Vec3.atCenterOf(pos))) {
            TunnelWalkProgress.State.MOVING -> return
            TunnelWalkProgress.State.STALLED -> {
                abandonBrokenPath(next)
                return
            }
            TunnelWalkProgress.State.ARRIVED -> Unit
        }
        val requestedFace = if (cellSize == 3) Direction.UP else direction.opposite
        if (targetBlock != pos) {
            if (nextRandom(100) == 0) {
                settleUntilTick = tick + nextLognormalTicks(20.0, 0.5, 8, 100)
                idleRotation = null
                lingering = true
                return
            }
            stopBreaking()
            targetBlock = pos
            breakTicks = 0
            breakReactionTicks = -1
            targetFace = requestedFace
            variedMiningAim = TunnelMiningAimRules.variedAim(nextRandom(100))
            miningAimU = TunnelMiningAimRules.aimCoordinate(nextGaussian(), variedMiningAim)
            miningAimV = TunnelMiningAimRules.aimCoordinate(nextGaussian(), variedMiningAim)
        } else if (targetFace != requestedFace) {
            stopBreaking()
            targetBlock = pos
            breakTicks = 0
            breakReactionTicks = -1
            targetFace = requestedFace
            variedMiningAim = TunnelMiningAimRules.variedAim(nextRandom(100))
            miningAimU = TunnelMiningAimRules.aimCoordinate(nextGaussian(), variedMiningAim)
            miningAimV = TunnelMiningAimRules.aimCoordinate(nextGaussian(), variedMiningAim)
        }
        aimTarget = blockFaceAimPoint(level, pos, targetFace, miningAimU, miningAimV) ?: Vec3.atCenterOf(pos)
        val gameMode = mc.gameMode ?: return
        if (level.getBlockState(pos).isAir) {
            stopBreaking()
            targetBlock = null
            failedTargets.remove(pos.asLong())
            settleUntilTick = tick + if (cellSize == 3) nextLognormalTicks(2.2, 0.3, 2, 6) else nextLognormalTicks(1.0, 0.5, 0, 4)
            lingering = false
            return
        }
        val hit = mc.hitResult as? net.minecraft.world.phys.BlockHitResult
        if (hit?.type != net.minecraft.world.phys.HitResult.Type.BLOCK || hit.blockPos != pos || hit.direction != targetFace) {
            breakReactionTicks = -1
            breakTicks++
            if (breakTicks > 100) recordBreakFailure(next, pos)
            return
        }
        if (!breaking) {
            if (selectHotbarTool(player, requireShard = cellSize == 3)) return
            if (breakReactionTicks < 0) {
                breakReactionTicks = TunnelMiningAimRules.reactionTicks(variedMiningAim, nextGaussian())
                return
            }
            if (breakReactionTicks > 0) {
                breakReactionTicks--
                return
            }
            if (gameMode.startDestroyBlock(pos, targetFace)) {
                breaking = true
                breakTicks = 0
                SwingMode.DO_NOT_HIDE.swing(InteractionHand.MAIN_HAND)
            } else {
                recordBreakFailure(next, pos)
            }
        } else {
            applyMiningWobble(player, level, pos)
            if (gameMode.continueDestroyBlock(pos, targetFace)) SwingMode.DO_NOT_HIDE.swing(InteractionHand.MAIN_HAND)
            breakTicks++
            if (breakTicks > 100) {
                recordBreakFailure(next, pos)
            }
        }
    }

    private fun startTunnelHop(spot: BlockPos, through: Boolean, advanceRoute: Boolean) {
        if (hopTask != null) return
        endStraightStream()
        stopBreaking()
        walkProgress.reset()
        hopTask = TunnelHopTask(spot.immutable(), through, advanceRoute)
    }

    /** Runs the bounded, source-shaped step hop while retaining ownership of the route action. */
    private fun tickTunnelHop(player: LocalPlayer, level: ClientLevel, task: TunnelHopTask): Boolean {
        if (task.from == null) task.from = player.blockPosition().immutable()
        if (task.floor.isNaN()) task.floor = floorHeight(level, task.spot) ?: task.spot.y.toDouble()
        if (++task.ticks > 60) {
            finishTunnelHop(task, success = false)
            return false
        }

        val pos = player.position()
        val dx = task.spot.x + 0.5 - pos.x
        val dz = task.spot.z + 0.5 - pos.z
        val near = if (task.through) 0.35 else 0.12
        val over = abs(dx) < near && abs(dz) < near
        val settled = task.through || player.deltaMovement.horizontalDistanceSqr() < 0.0025
        val landed = pos.y < task.floor + 0.55
        if (over && settled && landed && player.onGround() && pos.y >= task.floor - 0.05) {
            finishTunnelHop(task, success = true)
            return false
        }

        if (!task.centered) {
            val from = task.from ?: player.blockPosition()
            val cx = from.x + 0.5 - pos.x
            val cz = from.z + 0.5 - pos.z
            val aside = if (task.spot.x != from.x) abs(cz) else abs(cx)
            val levelAtStart = task.floor - pos.y <= 0.55 && aside < 0.25
            if (!levelAtStart && (abs(cx) >= 0.15 || abs(cz) >= 0.15) && (player.onGround() || task.ticks <= 10)) {
                walkTarget = Vec3(from.x + 0.5, task.floor, from.z + 0.5)
                aimTarget = Vec3(from.x + 0.5, task.floor + 1.2, from.z + 0.5)
                mc.options.keyJump.setDown(false)
                mc.options.keySprint.setDown(false)
                return true
            }
            task.centered = true
        }

        val targetYaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        val off = abs(net.minecraft.util.Mth.wrapDegrees(targetYaw - player.yRot))
        val moving = player.deltaMovement.horizontalDistanceSqr() > 4.0E-4
        val push = (!over || !landed) && (task.through || hypot(dx, dz) > 0.3 || !moving || !landed)
        walkTarget = if (push) Vec3(task.spot.x + 0.5, task.floor, task.spot.z + 0.5) else null
        aimTarget = Vec3(task.spot.x + 0.5, task.floor + 1.2, task.spot.z + 0.5)
        mc.options.keySprint.setDown(false)
        val from = task.from ?: player.blockPosition()
        val ahead = (pos.x - (from.x + 0.5)) * Integer.signum(task.spot.x - from.x) +
            (pos.z - (from.z + 0.5)) * Integer.signum(task.spot.z - from.z)
        val sameCell = task.spot.x == from.x && task.spot.z == from.z
        mc.options.keyJump.setDown(
            TunnelHopRules.shouldJump(player.onGround(), task.floor - pos.y, player.horizontalCollision, ahead, sameCell),
        )
        if (!push || off >= 30.0) walkTarget = null
        return true
    }

    private fun finishTunnelHop(task: TunnelHopTask, success: Boolean) {
        if (hopTask !== task) return
        hopTask = null
        walkTarget = null
        mc.options.keyJump.setDown(false)
        mc.options.keySprint.setDown(false)
        walkProgress.reset()
        if (!success) {
            route = emptyList()
            routeIndex = 0
            routeStepsSincePlan = 0
            return
        }
        if (task.advanceRoute) advanceRouteCell()
    }

    private fun advanceRouteCell() {
        routeIndex++
        routeStepsSincePlan++
        if (routeIndex >= route.size || routeStepsSincePlan >= 9) {
            route = emptyList()
            routeIndex = 0
            routeStepsSincePlan = 0
        }
    }

    private fun tunnelMineTarget(level: ClientLevel, cell: TunnelRoutePlanner.Cell): BlockPos? {
        val foot = BlockPos.containing(center(cell))
        val head = foot.above()
        return when {
            isSolid(level, Vec3.atCenterOf(head)) -> head
            isSolid(level, Vec3.atCenterOf(foot)) -> foot
            else -> null
        }
    }

    private fun blockFaceAimPoint(level: ClientLevel, pos: BlockPos, face: Direction, u: Double, v: Double): Vec3? {
        val boxes = level.getBlockState(pos).getCollisionShape(level, pos).toAabbs()
        val box = boxes.maxByOrNull { shape ->
            when (face) {
                Direction.DOWN -> -shape.minY
                Direction.UP -> shape.maxY
                Direction.NORTH -> -shape.minZ
                Direction.SOUTH -> shape.maxZ
                Direction.WEST -> -shape.minX
                Direction.EAST -> shape.maxX
            }
        } ?: return null
        val point = TunnelMiningAimRules.facePoint(
            pos.x, pos.y, pos.z, face,
            box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, u, v,
        )
        return Vec3(point.x, point.y, point.z)
    }

    /** Source-style tiny aim jitter remains allowed only when a fresh native ray still hits this exact face. */
    private fun applyMiningWobble(player: LocalPlayer, level: ClientLevel, pos: BlockPos) {
        val jitter = TunnelMiningAimRules.wobbleRotation(
            variedMiningAim, nextRandom(100), nextGaussian(), nextGaussian(),
        ) ?: return
        val yaw = player.yRot + jitter.first
        val pitch = player.xRot + jitter.second
        val eye = player.eyePosition
        val end = eye.add(Vec3.directionFromRotation(pitch, yaw).scale(player.blockInteractionRange().toDouble()))
        val hit = level.clip(ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)) as? BlockHitResult
            ?: return
        if (hit.type == HitResult.Type.BLOCK && hit.blockPos == pos && hit.direction == targetFace) {
            aimTarget = end
        }
    }

    private fun moveToward(level: ClientLevel, player: LocalPlayer, destination: Vec3, lookAt: Vec3): TunnelWalkProgress.State {
        val state = walkProgress.observe(destination.x, destination.z, player.x, player.z)
        when (state) {
            TunnelWalkProgress.State.MOVING -> {
                walkTarget = destination
                aimTarget = lookAt
                mc.options.keyJump.setDown(player.onGround() &&
                    (player.horizontalCollision || needsStepJump(level, player, destination)))
            }
            TunnelWalkProgress.State.ARRIVED -> {
                walkTarget = null
                mc.options.keyJump.setDown(false)
            }
            TunnelWalkProgress.State.STALLED -> {
                walkTarget = null
                mc.options.keyJump.setDown(false)
            }
        }
        return state
    }

    private fun needsStepJump(level: ClientLevel, player: LocalPlayer, destination: Vec3): Boolean {
        val base = BlockPos.containing(destination.x, player.y, destination.z)
        var targetFloor: Double? = null
        for (dy in 0..1) {
            val candidate = floorHeight(level, base.above(dy)) ?: continue
            if (targetFloor == null || abs(candidate - player.y) < abs(targetFloor - player.y)) targetFloor = candidate
        }
        return targetFloor != null && targetFloor - player.y > 0.55
    }

    private fun floorHeight(level: ClientLevel, feet: BlockPos): Double? {
        if (!level.hasChunkAt(feet) || !level.getFluidState(feet).isEmpty) return null
        val ownTop = topUnderFoot(level, feet)
        val floorY = when {
            ownTop != null && ownTop <= 0.999 -> feet.y + ownTop
            ownTop != null -> return null
            else -> {
                val below = feet.below()
                val lowerTop = topUnderFoot(level, below) ?: return null
                if (lowerTop <= 0.999) return null
                below.y + lowerTop
            }
        }
        if (!level.getFluidState(feet.above()).isEmpty) return null
        val x = feet.x.toDouble()
        val z = feet.z.toDouble()
        val body = AABB(x + 0.2, floorY + 0.01, z + 0.2, x + 0.8, floorY + 1.8, z + 0.8)
        if (!level.noCollision(body)) return null
        val support = AABB(x + 0.2, floorY - 0.05, z + 0.2, x + 0.8, floorY - 0.001, z + 0.8)
        if (level.noCollision(support)) return null
        return floorY
    }

    private fun topUnderFoot(level: ClientLevel, pos: BlockPos): Double? {
        val shape = level.getBlockState(pos).getCollisionShape(level, pos)
        if (shape.isEmpty) return null
        var top: Double? = null
        for (box in shape.toAabbs()) {
            if (box.maxX <= 0.2 || box.minX >= 0.8 || box.maxZ <= 0.2 || box.minZ >= 0.8) continue
            if (top == null || box.maxY > top) top = box.maxY
        }
        return top
    }

    /** Mirrors the source hop guard so carried momentum cannot continue into a deep shaft. */
    private fun pauseAtDeepDrop(level: ClientLevel, player: LocalPlayer, goal: Vec3): Boolean {
        if (!player.onGround()) return false
        val motion = player.deltaMovement
        val speed = motion.horizontalDistance()
        if (speed < 0.02) {
            releaseDropCrouch()
            return false
        }
        val ahead = speed * 1.3 + 0.35
        val sample = BlockPos.containing(
            player.x + motion.x / speed * ahead,
            player.y + 0.01,
            player.z + motion.z / speed * ahead,
        )
        val feet = player.blockPosition()
        val sampleMatchesGoal = sample.x == floor(goal.x).toInt() && sample.z == floor(goal.z).toInt()
        val sampleMatchesPlayer = sample.x == feet.x && sample.z == feet.z
        if (!TunnelDropRules.shouldPause(speed, dropDepth(level, sample), sampleMatchesGoal, sampleMatchesPlayer)) {
            releaseDropCrouch()
            return false
        }

        blockedCells += cellAt(sample.x, sample.z)
        releaseActions()
        mc.options.keyShift.setDown(true)
        ownsCrouchForDrop = true
        route = emptyList()
        routeIndex = 0
        routeStepsSincePlan = 0
        tell("A deep drop interrupted the tunnel path; planning a safe route around it.", true)
        return true
    }

    private fun dropDepth(level: ClientLevel, feet: BlockPos): Int {
        if (!level.hasChunkAt(feet)) return 0
        if (level.getFluidState(feet).`is`(FluidTags.LAVA)) return 24
        if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty) return 0
        var depth = 0
        while (depth < 24) {
            val below = feet.below(depth + 1)
            if (level.getFluidState(below).`is`(FluidTags.LAVA) || level.getBlockState(below).`is`(BlockTags.FIRE)) return 24
            if (level.getFluidState(below).isEmpty && level.getBlockState(below).getCollisionShape(level, below).isEmpty) {
                depth++
            } else {
                return depth
            }
        }
        return 24
    }

    private fun releaseDropCrouch() {
        if (!ownsCrouchForDrop) return
        runCatching { mc.options.keyShift.setDown(false) }
        ownsCrouchForDrop = false
    }

    private fun tryStraightStream(
        player: LocalPlayer,
        level: ClientLevel,
        next: TunnelRoutePlanner.Cell,
        way: Direction,
        target: Vec3?,
    ): Boolean {
        val nextPos = BlockPos.containing(center(next))
        val head = nextPos.above()
        val trackedHead = streamTarget
        if (trackedHead != null && level.getBlockState(trackedHead).isAir) {
            headBrokeAtTick = tick
            streamTarget = null
            stopBreaking()
            targetBlock = null
        }

        val feetPending = target != null && BlockPos.containing(target) == nextPos && tick - headBrokeAtTick < 4L
        val sideX = -way.stepZ
        val sideZ = way.stepX
        val aligned = abs((player.x - (nextPos.x + 0.5)) * sideX + (player.z - (nextPos.z + 0.5)) * sideZ) <= 0.45
        val straight = routeIndex + 1 < route.size && directionBetween(next, route[routeIndex + 1]) == way
        val targetPos = target?.let { BlockPos.containing(it) }
        if (!TunnelStreamRules.shouldStream(cellSize, straight, aligned, target == null, targetPos == head, feetPending)) {
            endStraightStream()
            return false
        }

        if (targetPos == head && decidedStreamHead != head) {
            decidedStreamHead = head.immutable()
            if (oldWayBlocksLeft > 0) oldWayBlocksLeft--
            if (oldWayBlocksLeft == 0 && nextRandom(100) < 6) oldWayBlocksLeft = 2 + nextRandom(4)
        }
        if (oldWayBlocksLeft > 0) {
            endStraightStream()
            return false
        }

        streaming = true
        val facePoint = Vec3.atCenterOf(head).subtract(way.stepX * 0.5, 0.0, way.stepZ * 0.5)
        aimTarget = facePoint
        val gap = (facePoint.x - player.x) * way.stepX + (facePoint.z - player.z) * way.stepZ
        mc.options.keyUp.setDown(target == null || gap > 1.0)
        if (target == null) {
            walkTarget = null
            walkProgress.reset()
            routeIndex++
            routeStepsSincePlan++
            if (routeIndex >= route.size || routeStepsSincePlan >= 9) {
                route = emptyList()
                routeIndex = 0
                routeStepsSincePlan = 0
            }
            return true
        }
        if (feetPending) return true

        val hit = mc.hitResult as? net.minecraft.world.phys.BlockHitResult
        if (hit?.type == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.blockPos == head) {
            val gameMode = mc.gameMode ?: return true
            if (targetBlock != head) {
                stopBreaking()
                targetBlock = head
                targetFace = hit.direction
                if (gameMode.startDestroyBlock(head, hit.direction)) {
                    breaking = true
                    SwingMode.DO_NOT_HIDE.swing(InteractionHand.MAIN_HAND)
                }
            } else if (breaking && gameMode.continueDestroyBlock(head, targetFace)) {
                SwingMode.DO_NOT_HIDE.swing(InteractionHand.MAIN_HAND)
            }
            streamTarget = head.immutable()
        }
        return true
    }

    private fun endStraightStream() {
        if (!streaming) return
        streaming = false
        streamTarget = null
        mc.options.keyUp.setDown(false)
        stopBreaking()
    }

    private fun abandonBrokenPath(cell: TunnelRoutePlanner.Cell) {
        blockedCells += cell
        stopBreaking()
        clearStandPlan()
        route = emptyList()
        routeIndex = 0
        routeStepsSincePlan = 0
        walkTarget = null
        walkProgress.reset()
        tell("Movement stalled; planning around the blocked tunnel cell.", true)
    }

    private fun findStandSpot(level: ClientLevel, player: LocalPlayer, target: BlockPos, way: Direction): Vec3? {
        if (standTarget == target) return standSpot
        standTarget = target.immutable()
        standSpot = null
        standVaried = nextRandom(5) == 0
        val targetX = target.x + 0.5
        val targetZ = target.z + 0.5
        val sideX = -way.stepZ
        val sideZ = way.stepX
        val room = if (cellSize == 3) 0.7 else 0.2
        val sideTolerance = if (cellSize == 3) min(room, 0.45) else room
        if (TunnelStandRules.isPositioned(
                player.x, player.z, targetX, targetZ,
                way.stepX, way.stepZ, sideX, sideZ, sideTolerance,
            ) && openStandSpot(level, player, player.position())
        ) {
            standSpot = player.position()
            return standSpot
        }

        repeat(16) {
            val offset = TunnelStandRules.sampleOffset(
                room,
                standVaried,
                nextRandom(1_000_000) / 1_000_000.0,
                nextRandom(1_000_000) / 1_000_000.0,
            )
            val candidate = Vec3(
                targetX - way.stepX * offset.back + sideX * offset.lateral,
                player.y,
                targetZ - way.stepZ * offset.back + sideZ * offset.lateral,
            )
            if (openStandSpot(level, player, candidate)) {
                standSpot = candidate
                return standSpot
            }
        }
        return null
    }

    private fun openStandSpot(level: ClientLevel, player: LocalPlayer, spot: Vec3): Boolean {
        val box = player.getDimensions(net.minecraft.world.entity.Pose.STANDING)
            .makeBoundingBox(spot)
            .deflate(0.02)
        if (!level.noCollision(player, box)) return false
        val floor = BlockPos.containing(spot.x, spot.y - 0.5, spot.z)
        val floorState = level.getBlockState(floor)
        return floorState.fluidState.isEmpty && !floorState.getCollisionShape(level, floor).isEmpty
    }

    private fun clearStandPlan() {
        standTarget = null
        standSpot = null
        standVaried = false
        walkProgress.reset()
    }

    private fun recordBreakFailure(cell: TunnelRoutePlanner.Cell, pos: BlockPos) {
        stopBreaking()
        targetBlock = null
        val failures = (failedTargets[pos.asLong()] ?: 0) + 1
        failedTargets[pos.asLong()] = failures
        if (failures >= 3) {
            blockedCells += cell
            route = emptyList()
            tell("Could not break block at ${pos.x} ${pos.y} ${pos.z}; routing around it.", true)
        }
    }

    private fun safeCell(level: ClientLevel, cell: TunnelRoutePlanner.Cell, y: Int): Boolean {
        val size = cellSize
        val height = if (size == 3) 3 else 2
        val x0 = cell.x * size + originX
        val z0 = cell.z * size + originZ
        val dx = max(0, max(x0 - seenFrom.x, seenFrom.x - (x0 + size - 1)))
        val dz = max(0, max(z0 - seenFrom.z, seenFrom.z - (z0 + size - 1)))
        if (max(dx, dz) > perceive) return level.hasChunkAt(BlockPos(x0, y, z0))
        for (x in x0 until x0 + size) for (z in z0 until z0 + size) {
            val floor = BlockPos(x, y - 1, z)
            if (!level.hasChunkAt(floor)) return false
            val floorState = level.getBlockState(floor)
            if (!floorState.fluidState.isEmpty || floorState.getCollisionShape(level, floor).isEmpty) return false
            val above = BlockPos(x, y + height, z)
            if (level.getBlockState(above).block is FallingBlock) return false
            for (dy in 0 until height) {
                val pos = BlockPos(x, y + dy, z)
                val state = level.getBlockState(pos)
                if (!state.fluidState.isEmpty || state.block is InfestedBlock || state.getDestroySpeed(level, pos) < 0f) return false
                for (direction in Direction.values()) {
                    val neighbor = pos.relative(direction)
                    if (!level.getBlockState(neighbor).fluidState.isEmpty) return false
                }
            }
        }
        return true
    }

    private fun safeFluidBoundary(level: ClientLevel, pos: BlockPos): Boolean =
        Direction.values().none { direction -> !level.getBlockState(pos.relative(direction)).fluidState.isEmpty }

    private fun mineable(level: ClientLevel, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        return !state.isAir && state.fluidState.isEmpty && state.block !is InfestedBlock && state.getDestroySpeed(level, pos) >= 0f
    }

    private fun isSolid(level: ClientLevel, pos: Vec3): Boolean {
        val blockPos = BlockPos.containing(pos)
        val state = level.getBlockState(blockPos)
        return !state.getCollisionShape(level, blockPos).isEmpty
    }

    private fun center(cell: TunnelRoutePlanner.Cell): Vec3 {
        val half = cellSize / 2
        return Vec3(cell.x * cellSize + originX + half + 0.5, tunnelY.toDouble(), cell.z * cellSize + originZ + half + 0.5)
    }

    private fun cellAt(x: Int, z: Int) = TunnelRoutePlanner.Cell(Math.floorDiv(x - originX, cellSize), Math.floorDiv(z - originZ, cellSize))

    private fun chunkForCell(cell: TunnelRoutePlanner.Cell): Long {
        val point = center(cell)
        return ChunkPos.pack(floor(point.x).toInt() shr 4, floor(point.z).toInt() shr 4)
    }

    private fun directionBetween(from: TunnelRoutePlanner.Cell, to: TunnelRoutePlanner.Cell): Direction? = when {
        to.x > from.x && to.z == from.z -> Direction.EAST
        to.x < from.x && to.z == from.z -> Direction.WEST
        to.z > from.z && to.x == from.x -> Direction.SOUTH
        to.z < from.z && to.x == from.x -> Direction.NORTH
        else -> null
    }

    private fun planSegment(player: LocalPlayer, level: ClientLevel): Boolean {
        clearStandPlan()
        val startCell = cellAt(player.blockX, player.blockZ)
        seenFrom = player.blockPosition()
        perceive = 3 + nextRandom(3)
        routeStart = startCell
        val targetChunk = chooseFlagTarget(player, level)
        if (TunnelDriftRules.shouldShift(wander, targetChunk != null, tick, nextDriftTick)) {
            drift = TunnelDriftRules.shift(drift, nextRandom(3))
            nextDriftTick = TunnelDriftRules.nextTick(tick, nextRandom(60))
        }
        val targetCell = targetChunk?.let { cellAt(ChunkPos.getX(it) * 16 + 8, ChunkPos.getZ(it) * 16 + 8) }
        val safeCache = HashMap<TunnelRoutePlanner.Cell, Boolean>()
        val result = TunnelRoutePlanner.plan(
            TunnelRoutePlanner.Request(
                start = startCell,
                heading = heading,
                axis = cellAt(axis.x, axis.z),
                drift = if (wander && targetChunk == null) drift else 0,
                target = targetCell,
                aheadLimit = max(4, 40 / cellSize),
                sideLimit = max(3, 15 / cellSize),
                maxExpanded = 6_000,
            ),
            safe = { cell -> safeCache.getOrPut(cell) { safeCell(level, cell, tunnelY) } },
            blocked = blockedCells,
                visitedChunks = if (flags == FlagMode.OFF) emptySet() else visited,
            chunkKey = ::chunkForCell,
            currentChunk = ChunkPos.pack(player.blockX shr 4, player.blockZ shr 4),
            targetChunk = targetChunk ?: Long.MIN_VALUE,
            noise = { cell -> TunnelRouteNoise.weight(cell.x, cell.z, terrainSeed, wander) },
        )
        route = result.cells
        routeIndex = 0
        routeStepsSincePlan = 0
        if (targetChunk != null && targetCell != null) {
            val currentDistance = abs(startCell.x - targetCell.x) + abs(startCell.z - targetCell.z)
            val routeEnd = result.cells.lastOrNull() ?: startCell
            val bestDistance = abs(routeEnd.x - targetCell.x) + abs(routeEnd.z - targetCell.z)
            if (result.cells.isEmpty() || targetProgress.recordPlan(currentDistance, bestDistance)) {
                visited += targetChunk
                activeTargetChunk = null
                targetMissingChecks = 0
                targetProgress.reset()
                heading = player.direction.toTunnelHeading()
                axis = player.blockPosition()
                drift = 0
                tell("Leaving flagged chunk ${ChunkPos.getX(targetChunk) * 16 + 8} ${ChunkPos.getZ(targetChunk) * 16 + 8}; no closer safe route was found.", true)
                return true
            }
        }
        return route.isNotEmpty()
    }

    private fun nextRandom(bound: Int): Int {
        randomState = randomState * 1_664_525 + 1_013_904_223
        return (randomState ushr 1) % bound
    }

    private fun nextLognormalTicks(median: Double, sigma: Double, minimum: Int, maximum: Int): Long {
        val first = (nextRandom(1_000_000) + 1).toDouble() / 1_000_001.0
        val second = (nextRandom(1_000_000) + 1).toDouble() / 1_000_001.0
        val gaussian = sqrt(-2.0 * ln(first)) * cos(2.0 * Math.PI * second)
        val value = kotlin.math.exp(ln(median) + sigma * gaussian).coerceIn(minimum.toDouble(), maximum.toDouble())
        return kotlin.math.round(value).toLong()
    }

    private fun nextGaussian(): Double {
        val first = (nextRandom(1_000_000) + 1).toDouble() / 1_000_001.0
        val second = (nextRandom(1_000_000) + 1).toDouble() / 1_000_001.0
        return sqrt(-2.0 * ln(first)) * cos(2.0 * Math.PI * second)
    }

    private fun nextGapTicks(): Long {
        val uniform = (nextRandom(1_000_000) + 1).toDouble() / 1_000_001.0
        return (-ln(1.0 - uniform) * 2_400.0).coerceIn(600.0, 12_000.0).toLong()
    }

    private fun updateIdleRotation(player: LocalPlayer) {
        if (idleRotation == null || nextRandom(40) == 0) {
            val yaw = player.yRot + (nextGaussian() * 40.0).toFloat()
            val pitch = (10.0 + nextGaussian() * 22.0).toFloat().coerceIn(-45f, 70f)
            idleRotation = Rotation(yaw, pitch)
        }
    }

    private fun queueNearbyChunks(player: LocalPlayer, level: ClientLevel) {
        val centerKey = ChunkPos.pack(player.blockX shr 4, player.blockZ shr 4)
        if (centerKey != scanCenterChunk || tick - lastScanRefresh >= 100L) {
            scanCenterChunk = centerKey
            lastScanRefresh = tick
            val radius = (baseRadius + 15) / 16
            val centerX = player.blockX shr 4
            val centerZ = player.blockZ shr 4
            fun inWindow(key: Long): Boolean = abs(ChunkPos.getX(key) - centerX) <= radius + 1 &&
                abs(ChunkPos.getZ(key) - centerZ) <= radius + 1
            // Discovery is local. Discarding its scan stamp too permits a correct rescan on return.
            scannedChunks.removeIf { !inWindow(it) }
            queuedChunks.removeIf { !inWindow(it) }
            pendingChunks.removeIf { !inWindow(it) }
            foundPositions.keys.removeIf { key -> !inWindow(ChunkPos.pack(BlockPos.getX(key) shr 4, BlockPos.getZ(key) shr 4)) }
            for (distance in 0..radius) {
                for (x in -distance..distance) for (z in -distance..distance) {
                    if (max(abs(x), abs(z)) != distance) continue
                    val key = ChunkPos.pack((player.blockX shr 4) + x, (player.blockZ shr 4) + z)
                    if (key !in scannedChunks && queuedChunks.add(key)) pendingChunks.addLast(key)
                }
            }
        }
    }

    private fun scanLoadedChunks(level: ClientLevel, budget: Int) {
        var processed = 0
        while (processed < budget && pendingChunks.isNotEmpty()) {
            val key = pendingChunks.removeFirst()
            queuedChunks.remove(key)
            if (key in scannedChunks) continue
            val chunk = level.chunkSource.getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), ChunkStatus.FULL, false)
            if (chunk == null) continue
            scannedChunks += key
            processed++
            scanChunk(level, chunk)
        }
    }

    private fun scanChunk(level: ClientLevel, chunk: ChunkAccess) {
        val levelChunk = chunk as? LevelChunk ?: return
        for (entity in levelChunk.getBlockEntities().values) {
            val pos = entity.blockPos
            val state = level.getBlockState(pos)
            val isSpawner = state.`is`(Blocks.SPAWNER)
            if (isSpawner || state.`is`(BlockTags.SHULKER_BOXES) || state.block in storageBlocks) {
                foundPositions[pos.asLong()] = isSpawner
            }
        }
    }

    private fun drainWorldUpdates(level: ClientLevel) {
        val player = mc.player
        val storageOverflow = storageUpdates.consumeOverflow()
        val chunkOverflow = chunkRefreshes.consumeOverflow()
        if (storageOverflow || chunkOverflow) {
            // Lost packet evidence is never trusted as a complete local storage census.
            scannedChunks.clear()
            foundPositions.clear()
            queuedChunks.clear()
            pendingChunks.clear()
            scanCenterChunk = Long.MIN_VALUE
        }
        val radius = (baseRadius + 15) / 16 + 1
        val centerX = (player?.blockX ?: 0) shr 4
        val centerZ = (player?.blockZ ?: 0) shr 4
        repeat(32) {
            val pending = totemEvents.poll() ?: return@repeat
            if (pending.generation == inventoryGeneration && pending.packet.eventId == 35.toByte() &&
                pending.packet.getEntity(level) === player) popSeen = true
        }
        repeat(1_024) {
            val update = storageUpdates.poll() ?: return@repeat
            if (update.generation != inventoryGeneration) return@repeat
            val pos = update.pos
            if (abs((pos.x shr 4) - centerX) > radius || abs((pos.z shr 4) - centerZ) > radius) return@repeat
            if (isStorageState(update.state)) foundPositions[pos.asLong()] = update.state.`is`(Blocks.SPAWNER)
            else foundPositions.remove(pos.asLong())
        }
        repeat(32) {
            val refresh = chunkRefreshes.poll() ?: return@repeat
            if (refresh.generation != inventoryGeneration) return@repeat
            val x = ChunkPos.getX(refresh.chunkKey)
            val z = ChunkPos.getZ(refresh.chunkKey)
            if (abs(x - centerX) > radius || abs(z - centerZ) > radius) return@repeat
            if (level.chunkSource.getChunk(x, z, ChunkStatus.FULL, false) == null) return@repeat
            scannedChunks.remove(refresh.chunkKey)
            if (queuedChunks.add(refresh.chunkKey)) pendingChunks.addLast(refresh.chunkKey)
        }
    }

    private fun isStorageState(state: BlockState): Boolean =
        state.`is`(Blocks.SPAWNER) || state.`is`(BlockTags.SHULKER_BOXES) || state.block in storageBlocks

    private fun detectFind(player: LocalPlayer, level: ClientLevel) {
        val radius = baseRadius
        var storage = 0
        var sumX = 0L
        var sumZ = 0L
        var spawnerAt: BlockPos? = null
        val iterator = foundPositions.entries.iterator()
        while (iterator.hasNext()) {
            val (key, isSpawner) = iterator.next()
            val pos = BlockPos.of(key)
            val dx = abs(pos.x - player.blockX)
            val dz = abs(pos.z - player.blockZ)
            if (dx > radius || dz > radius) continue
            if (abs(pos.x - start.x) <= 48 && abs(pos.z - start.z) <= 48) continue
            val state = level.getBlockState(pos)
            val stillFound = if (isSpawner) state.`is`(Blocks.SPAWNER) else
                state.`is`(BlockTags.SHULKER_BOXES) || state.block in storageBlocks
            if (!stillFound) {
                iterator.remove()
                continue
            }
            if (isSpawner) {
                if (stopAtSpawners && spawnerAt == null && !isSpiderSpawner(level, pos)) spawnerAt = pos
            } else {
                storage++
                sumX += pos.x
                sumZ += pos.z
            }
        }
        when {
            spawnerAt != null -> found("a spawner", spawnerAt!!)
            storage >= storageToStop -> found("a base ($storage storage blocks)", BlockPos((sumX / storage).toInt(), player.blockY, (sumZ / storage).toInt()))
        }
    }

    private fun isSpiderSpawner(level: ClientLevel, pos: BlockPos): Boolean {
        val blockEntity = level.getBlockEntity(pos) as? SpawnerBlockEntity ?: return false
        val display = blockEntity.spawner.getOrCreateDisplayEntity(level, pos)
        val type = display?.let { BuiltInRegistries.ENTITY_TYPE.getKey(it.type)?.toString() }
        return type == "minecraft:spider" || type == "minecraft:cave_spider"
    }

    private fun found(what: String, pos: BlockPos) {
        releaseActions()
        pendingFind = what to pos.immutable()
        disconnectAtTick = tick + nextLognormalTicks(260.0, 0.6, 70, 1_400)
        tell("Found $what at ${pos.x} ${pos.y} ${pos.z}.", true)
        if (!disconnectOnFind) enabled = false
    }

    private fun selectHotbarTool(player: LocalPlayer, requireShard: Boolean): Boolean {
        if (matchesTool(player.mainHandItem, requireShard)) return true
        val match = (0..8).firstOrNull { slot -> matchesTool(player.inventory.getItem(slot), requireShard) } ?: return false
        selectHotbarSlot(player, match)
        return true
    }

    private fun findTool(player: LocalPlayer, requireShard: Boolean): ItemStack? =
        (0 until 36).asSequence().map(player.inventory::getItem).firstOrNull { matchesTool(it, requireShard) }

    private fun matchesTool(stack: ItemStack, requireShard: Boolean): Boolean {
        if (stack.isEmpty || !stack.`is`(ItemTags.PICKAXES)) return false
        if (!requireShard) return true
        val name = stack.hoverName.string
        return name.contains("shard", ignoreCase = true) || name.contains("amethyst", ignoreCase = true)
    }

    private fun releaseActions() {
        runCatching {
            mc.options.keyUse.setDown(false)
            mc.options.keyUp.setDown(false)
            mc.options.keyDown.setDown(false)
            mc.options.keyLeft.setDown(false)
            mc.options.keyRight.setDown(false)
            mc.options.keyJump.setDown(false)
            mc.options.keySprint.setDown(false)
            if (ownsCrouchForDrop && !mc.options.keyShift.isPressedOnAny) mc.options.keyShift.setDown(false)
        }
        ownsCrouchForDrop = false
        hopTask = null
        walkTarget = null
        aimTarget = null
        walkProgress.reset()
        streaming = false
        streamTarget = null
        if (breaking && worldIdentity != null && worldIdentity === mc.level && mc.player != null) {
            runCatching { mc.gameMode?.stopDestroyBlock() }
        }
        abandonBreaking()
    }

    private fun stopBreaking() {
        if (breaking) runCatching { mc.gameMode?.stopDestroyBlock() }
        breaking = false
        breakTicks = 0
        breakReactionTicks = -1
    }

    /** Drops local break ownership without sending a stop packet into a changed or absent world. */
    private fun abandonBreaking() {
        breaking = false
        breakTicks = 0
        breakReactionTicks = -1
    }

    private val rotationHandler = handler<RotationUpdateEvent> {
        val player = mc.player ?: return@handler
        val rotation = when {
            eating -> Rotation(player.yRot, -75f)
            mending -> Rotation(player.yRot, 80f)
            idleRotation != null -> idleRotation!!
            else -> {
                val destination = aimTarget ?: return@handler
                val delta = destination.subtract(player.eyePosition)
                val horizontal = hypot(delta.x, delta.z)
                Rotation(
                    Math.toDegrees(atan2(-delta.x, delta.z)).toFloat(),
                    Math.toDegrees(-atan2(delta.y, horizontal)).toFloat(),
                )
            }
        }
        RotationManager.setRotationTarget(
            RotationTarget(rotation, ticksUntilReset = 1, movementCorrection = MovementCorrection.STRICT),
            Priority.IMPORTANT_FOR_USAGE_1,
            this,
        )
    }

    private val movementHandler = handler<MovementInputEvent> { event ->
        if (mc.gui.screen() != null) return@handler
        val target = walkTarget ?: return@handler
        val player = mc.player ?: return@handler
        val physical = with(mc.options) { listOf(keyUp, keyDown, keyLeft, keyRight, keyJump).any { it.isPressedOnAny } }
        if (physical) return@handler
        if (distanceFlat(player, target) > 0.35) event.directionalInput = DirectionalInput.FORWARDS
    }

    private val packetHandler = handler<PacketEvent> { event ->
        inventoryAckChannel.onPacket(event.origin, event.original, event.packet)
        if (event.origin != TransferOrigin.INCOMING || !event.original) return@handler
        val packetGeneration = inventoryGeneration
        val chatPacket = event.packet as? ClientboundSystemChatPacket
        if (chatPacket != null && !chatPacket.overlay()) {
            val attempt = purchaseAttempt
            val raw = chatPacket.content?.string.orEmpty()
            if (raw.contains("wait another", ignoreCase = true) && sellRateLimits.size < 16) {
                sellRateLimits.offer(QueuedSellRateLimit(packetGeneration, raw, System.nanoTime()))
            }
            val notice = if (attempt != null) TunnelAuctionRules.parseNotice(raw) else null
            if (attempt != null && notice != null) {
                var receiptId = auctionReceiptIds.incrementAndGet()
                if (receiptId <= 0L) {
                    auctionReceiptIds.set(1L)
                    receiptId = 1L
                }
                val receipt = AuctionPurchaseReceipt(
                    receiptId = receiptId,
                    session = attempt.session,
                    attemptId = attempt.attemptId,
                    kind = notice.kind,
                    reportedName = notice.reportedName,
                    itemId = notice.itemId,
                    count = notice.count,
                    totalPrice = notice.totalPrice,
                )
                if (auctionReceipts.size >= 16) auctionReceipts.poll()
                auctionReceipts.offer(QueuedPurchaseReceipt(receipt))
            }
        }
        when (val packet = event.packet) {
            is ClientboundEntityEventPacket -> if (packet.eventId == 35.toByte())
                totemEvents.offer(PendingTotemEvent(packetGeneration, packet))
            is ClientboundBlockUpdatePacket -> storageUpdates.offer(
                StorageBlockUpdate(packetGeneration, packet.pos.immutable(), packet.blockState),
            )
            is ClientboundSectionBlocksUpdatePacket -> packet.runUpdates { pos, state ->
                storageUpdates.offer(StorageBlockUpdate(packetGeneration, pos.immutable(), state))
            }
            is ClientboundLevelChunkWithLightPacket -> chunkRefreshes.offer(
                ChunkRefresh(packetGeneration, ChunkPos.pack(packet.x(), packet.z())),
            )
        }
    }

    private val renderHandler = handler<WorldRenderEvent> { event ->
        if (!showVisited || flags == FlagMode.OFF || visited.isEmpty()) return@handler
        val level = mc.level ?: return@handler
        event.environment.withPositionRelativeToCamera {
            for (key in visited.toList().takeLast(256)) {
                val x = ChunkPos.getX(key) * 16.0
                val z = ChunkPos.getZ(key) * 16.0
                val y0 = (mc.player?.blockY ?: 0) - 32.0
                val y1 = y0 + 64.0
                drawBox(
                    AABB(x, y0, z, x + 16.0, y1, z + 16.0),
                    Color4b(255, 205, 60, 10),
                    Color4b(255, 205, 60, 55),
                )
            }
        }
    }

    private fun tell(text: String, warn: Boolean) {
        if (!chatFeedback && !warn) return
        chat("[Donut Tunnel Base Finder] $text", this)
    }

    private fun distanceFlat(player: LocalPlayer, target: Vec3): Double = hypot(player.x - target.x, player.z - target.z)

    private fun net.minecraft.core.Direction.toTunnelHeading() = when (this) {
        Direction.NORTH -> TunnelRoutePlanner.Heading.NORTH
        Direction.EAST -> TunnelRoutePlanner.Heading.EAST
        Direction.SOUTH -> TunnelRoutePlanner.Heading.SOUTH
        Direction.WEST -> TunnelRoutePlanner.Heading.WEST
        else -> TunnelRoutePlanner.Heading.NORTH
    }

    private fun TunnelRoutePlanner.Heading.rotateClockwise() = when (this) {
        TunnelRoutePlanner.Heading.NORTH -> TunnelRoutePlanner.Heading.EAST
        TunnelRoutePlanner.Heading.EAST -> TunnelRoutePlanner.Heading.SOUTH
        TunnelRoutePlanner.Heading.SOUTH -> TunnelRoutePlanner.Heading.WEST
        TunnelRoutePlanner.Heading.WEST -> TunnelRoutePlanner.Heading.NORTH
    }
}
