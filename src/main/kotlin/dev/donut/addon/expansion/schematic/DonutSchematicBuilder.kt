package dev.donut.addon.expansion.schematic

import dev.donut.addon.AddonCategories
import dev.donut.addon.expansion.automation.core.ActionGate
import dev.donut.addon.expansion.automation.core.ActionTicket
import dev.donut.addon.expansion.automation.core.AutomationSnapshot
import dev.donut.addon.expansion.automation.core.AuctionPurchaseConfirmation
import dev.donut.addon.expansion.automation.core.AuctionPurchaseConfirmationAction
import dev.donut.addon.expansion.automation.core.AuctionPurchaseEffect
import dev.donut.addon.expansion.automation.core.AuctionPurchaseFrame
import dev.donut.addon.expansion.automation.core.AuctionPurchaseHost
import dev.donut.addon.expansion.automation.core.AuctionPurchaseListingRules
import dev.donut.addon.expansion.automation.core.AuctionPurchasePageAction
import dev.donut.addon.expansion.automation.core.AuctionPurchaseProgress
import dev.donut.addon.expansion.automation.core.AuctionPurchaseReceipt
import dev.donut.addon.expansion.automation.core.AuctionPurchaseReceiptKind
import dev.donut.addon.expansion.automation.core.AuctionPurchaseRequest
import dev.donut.addon.expansion.automation.core.AuctionPurchaseStatus
import dev.donut.addon.expansion.automation.core.AuctionPurchaseWorkflow
import dev.donut.addon.expansion.automation.core.DialogSnapshot
import dev.donut.addon.expansion.automation.core.DialogWidgetSnapshot
import dev.donut.addon.expansion.automation.DonutDialogSnapshotCapture
import dev.donut.addon.expansion.automation.core.DonutSellCandidate
import dev.donut.addon.expansion.automation.core.DonutSellClickType
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
import dev.donut.addon.expansion.automation.core.InventoryAckChannel
import dev.donut.addon.expansion.automation.core.InventoryAckStatus
import dev.donut.addon.expansion.automation.core.InventoryAckTracker
import dev.donut.addon.expansion.automation.core.InventorySlotValue
import dev.donut.addon.expansion.automation.core.InventorySyncFrame
import dev.donut.addon.expansion.automation.core.InventorySwapExpectation
import dev.donut.addon.expansion.automation.core.MenuClassifier
import dev.donut.addon.expansion.automation.core.MenuKind
import dev.donut.addon.expansion.automation.core.MenuSnapshot
import dev.donut.addon.expansion.automation.core.MarketRules
import dev.donut.addon.expansion.automation.core.PlannedEffect
import dev.donut.addon.expansion.automation.core.SessionStamp
import dev.donut.addon.expansion.automation.core.SlotSnapshot
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.resources.Identifier
import net.minecraft.core.component.DataComponents
import net.minecraft.tags.BlockTags
import net.minecraft.tags.ItemTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.CrafterMenu
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.TransparentBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.Container
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private enum class SchematicPhase { SCANNING, BUILDING, RESTORING, COMPLETE }

private data class SchematicBlockUpdate(val generation: Long, val pos: BlockPos, val state: BlockState, val ordinal: Long)
private data class SchematicAuctionChatReceipt(val session: SessionStamp, val attemptId: Long, val text: String)
private data class SchematicAuctionAttempt(val session: SessionStamp, val attemptId: Long)
private data class SchematicCrafterDataUpdate(val generation: Long, val menuId: Int, val slot: Int, val value: Int)
private data class SchematicCrafterOpenUpdate(val generation: Long, val menuId: Int, val ordinal: Long)
private data class SchematicServerMenuState(
    val slots: MutableMap<Int, InventorySlotValue> = linkedMapOf(),
    val slotStateIds: MutableMap<Int, Int> = linkedMapOf(),
    var stateId: Int = -1,
    var fullContentsOrdinal: Long = 0L,
    var packetOrdinal: Long = 0L,
    var cursor: InventorySlotValue = InventorySlotValue("", 0),
    var cursorKnown: Boolean = false,
)
private data class SchematicSellOfferPending(
    val effect: DonutSellEffect.OfferStack,
    val sellItemsBefore: Map<Int, InventorySlotValue>,
    val packetOrdinalBefore: Long,
)
private data class SchematicSellConfirmPending(
    val effect: DonutSellEffect.ConfirmSale,
    val sellItemsBefore: Map<Int, InventorySlotValue>,
    val packetOrdinalBefore: Long,
)
private data class SchematicSellReturnCapture(
    val runId: Long,
    val session: SessionStamp,
    val sellMenuId: Int,
    val sellScreenIdentity: Long,
    val menuMapEpoch: Long,
    val inventoryBeforeCapturedAtNanos: Long,
    val inventoryBeforePacketOrdinal: Long,
    val inventoryMapEpoch: Long,
    val inventoryBefore: Map<Int, InventorySlotValue>,
    var closeDispatchedAtNanos: Long = 0L,
    var menuClosedAtNanos: Long = 0L,
    var menuClosedPacketOrdinal: Long = 0L,
)
private enum class SchematicRefillOutcome { WAITING, FILLED, UNCHANGED }
private data class SchematicWaterRefillTask(
    val target: BlockPos,
    val menuId: Int,
    val menuSlot: Int,
    val before: InventorySlotValue,
    val connectionId: Long,
    val worldId: Long,
    val playerId: Long,
    var baselineStateId: Int,
    var attempts: Int = 0,
    var waiting: Boolean = false,
    var createdAt: Long = 0L,
    var sentAt: Long = 0L,
    var retryAt: Long = 0L,
    var outcome: SchematicRefillOutcome = SchematicRefillOutcome.WAITING,
)
private data class SchematicWaterlogTask(
    val target: BuildCellPos,
    val expectedState: BlockState,
    val connectionId: Long,
    val worldId: Long,
    val playerId: Long,
    val generation: Long,
    var menuId: Int = -1,
    var menuSlot: Int = -1,
    var before: InventorySlotValue? = null,
    var baselineStateId: Int = -1,
    var inventoryOrdinalBaseline: Long = 0L,
    var blockPacketBaseline: Long = 0L,
    var sent: Boolean = false,
    var blockAcknowledged: Boolean = false,
    var inventoryAcknowledged: Boolean = false,
    var blockRejected: Boolean = false,
    var inventoryRejected: Boolean = false,
    var sentAt: Long = 0L,
    var startedAt: Long = 0L,
    var retryAt: Long = 0L,
    var attempts: Int = 0,
)
private enum class SchematicDigOutStage { AIMING, DESTROYING, WAITING_ACK, WAITING_EXIT }
private data class SchematicDigOutTask(
    val target: BuildCellPos,
    val originalState: BlockState,
    val expectedState: BlockState?,
    val connectionId: Long,
    val worldId: Long,
    val playerId: Long,
    val generation: Long,
    var stage: SchematicDigOutStage = SchematicDigOutStage.AIMING,
    var attempts: Int = 0,
    var retryAt: Long = 0L,
    var sentAt: Long = 0L,
    var ackedAt: Long = 0L,
    var blockPacketBaseline: Long = 0L,
    var face: Direction? = null,
    var blockAcknowledged: Boolean = false,
    var blockRejected: Boolean = false,
    var exitRoutePlanned: Boolean = false,
    var exitReportSent: Boolean = false,
)
private enum class SchematicScaffoldStage { WAITING_FOR_PROOF, READY_TO_WALK, REMOVING }
private enum class SchematicScaffoldPurpose { TARGET_SUPPORT, WALKWAY }
private enum class SchematicPillarStage { CENTER, JUMP, RISING, AIMING_PLACEMENT, WAITING_FOR_BLOCK_PROOF, WAITING_FOR_STAND }
private data class SchematicPillarTask(
    val target: BuildCellPos,
    val x: Int,
    val z: Int,
    val baseY: Int,
    val height: Int,
    val item: Item,
    val itemId: String,
    val menuId: Int,
    val menuSlot: Int,
    var before: InventorySlotValue,
    var baselineStateId: Int,
    val connectionId: Long,
    val worldId: Long,
    val playerId: Long,
    val generation: Long,
    var stage: SchematicPillarStage = SchematicPillarStage.CENTER,
    var step: Int = 0,
    var stageStartedAt: Long = 0L,
    var attempts: Int = 0,
    var candidate: SchematicPlacementCandidate? = null,
    var expectedState: BlockState? = null,
    var acknowledgedState: BlockState? = null,
    var blockAcknowledged: Boolean = false,
    var blockRejected: Boolean = false,
    var inventoryAcknowledged: Boolean = false,
    var sentAt: Long = 0L,
    var blockPacketBaseline: Long = 0L,
)
private data class SchematicScaffoldTask(
    val target: BuildCellPos,
    val stand: BuildCellPos?,
    val purpose: SchematicScaffoldPurpose,
    val supportSequence: List<BlockPos>,
    var sequenceIndex: Int,
    var support: BlockPos,
    var state: BlockState,
    val item: Item,
    val menuId: Int,
    val menuSlot: Int,
    var before: InventorySlotValue,
    var baselineStateId: Int,
    val connectionId: Long,
    val worldId: Long,
    val playerId: Long,
    val generation: Long,
    var stageStartedAt: Long,
    var stage: SchematicScaffoldStage = SchematicScaffoldStage.WAITING_FOR_PROOF,
    var blockAcknowledged: Boolean = false,
    var blockRejected: Boolean = false,
    var acknowledgedState: BlockState? = null,
    var inventoryAcknowledged: Boolean = false,
    var sentAt: Long = 0L,
)
private data class OwnedSchematicScaffold(
    val pos: BlockPos,
    var state: BlockState,
    val target: BuildCellPos,
    var targetComplete: Boolean = false,
)
private enum class SchematicCrafterStage { PREPARE, OPENING, INITIAL_SYNC, WAIT_ACK }
private class SchematicCrafterTask(val pos: BuildCellPos, val expectedDisabled: List<Boolean>) {
    var stage = SchematicCrafterStage.PREPARE
    var since = 0L
    var previousMenuId = -1
    var menuId = -1
    var screenIdentity = 0L
    var openPacketBaseline = 0L
    var rounds = 0
    val serverData = mutableMapOf<Int, Int>()
    val awaitingData = linkedSetOf<Int>()
    val acknowledgedData = linkedSetOf<Int>()
}

private data class StagedSlotLease(
    val sourceSlot: Int,
    val destinationSlot: Int,
    val originalSource: InventorySlotValue,
    val originalDestination: InventorySlotValue,
    val itemId: String,
)

private val CONFIRMATION_PRICE = Regex(
    "(?i)(?:total(?:\\s+(?:price|cost))?|price|cost|buy\\s*now|purchase\\s+for)[^0-9$]{0,24}\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?\\s*[kmb]?)",
)

/** Independently authored, bounded Litematica build executor. */
object DonutSchematicBuilder : ClientModule("Donut Schematic Builder", AddonCategories.DONUT, disableOnQuit = true) {
    private const val SCAN_BUDGET = 4_096
    private const val SEMANTIC_SCAN_BUDGET = 256
    private const val PENDING_READ_BUDGET = 256
    private const val PLAN_LIMIT = 600
    private const val PLAN_WINDOWS_PER_TICK = 8
    private const val ROUTE_BUDGET = 3_000
    private const val PLACE_TIMEOUT_TICKS = 45
    private const val SWAP_TIMEOUT_TICKS = 100
    private const val VIRTUAL_RETRY_INTERVAL_TICKS = 20
    private const val ACTION_RETRY_INTERVAL_TICKS = 20
    private const val LEFT_SHIFT_KEY = 340
    private const val RIGHT_SHIFT_KEY = 344
    private const val MAX_SCAFFOLD_STEPS_PER_TARGET = 48
    private const val MAX_PILLAR_RUNS_PER_TARGET = 8
    private const val PILLAR_RISES_PER_RUN = 2
    private const val PILLAR_MAX_TRIES = 3
    private const val PILLAR_PHASE_TIMEOUT_TICKS = 40L
    private const val PILLAR_JUMP_START_TICKS = 4L
    private const val PILLAR_PLACEMENT_CLEARANCE = 1.02
    private const val PILLAR_CENTER_TOLERANCE = 0.12
    private const val PILLAR_MOTION_SQUARED_LIMIT = 0.0004
    private const val MAX_PILLAR_HORIZONTAL_RANGE = 4
    private const val WALK_STEP_HEIGHT = 0.55
    private const val SCAFFOLD_PROOF_TIMEOUT_TICKS = 45L
    private const val SELL_CONFIRM_SLOT = 53
    private const val MAX_SELL_CYCLES = 2
    private val SELL_ITEM_SLOTS = (0 until SELL_CONFIRM_SLOT).toSet()

    private val buyFromAh by boolean("Buy From AH", true)
    private val maxPerListing by text("Max Price Per Listing", "10m")
    private val totalSpendLimit by text("Max Total Spend", "")
    private val ahRefreshMs by int("AH Refresh (ms)", 1_000, 300..3_000)
    private val climbWithLeaves by boolean("Climb With Leaves", true)
    private val supportLeaves by boolean("Support Leaves", true)
    private val walkWithBaritone by boolean("Walk With Baritone", false)
    private val crafterSlots by boolean("Crafter Slots", true)
    private val repeatersComparators by boolean("Repeaters & Comparators", true)
    private val placeWaterLava by boolean("Place Water & Lava", true)
    private val sellLeftovers by boolean("Sell Leftovers", true)
    private val keepTotem by boolean("Keep Totem In Offhand", true)
    private val placeTnt by boolean("Place TNT", false)
    private val pistonsObserversLast by boolean("Pistons & Observers Last", true)
    private val flipLeversOn by boolean("Flip Levers On", true)
    private val relaxFacingWhenStuck by boolean("Relax Facing When Stuck", true)
    private val eatWhenHungry by boolean("Eat When Hungry", true)
    private val tickDelay by int("Tick Delay", 1, 0..10)
    private val chatFeedback by boolean("Chat Feedback", true)

    private val retryLedger = BuildRetryLedger(stubbornAfter = 3, maxFailures = 8)
    private val inventoryAckChannel = InventoryAckChannel()
    private val inventoryAckTracker = InventoryAckTracker()
    private val auctionPurchaseWorkflow = AuctionPurchaseWorkflow()
    private val sellWorkflow = DonutSellWorkflow()
    private val auctionActionGate = ActionGate(16)
    private val auctionHost = AuctionPurchaseHost(::dispatchAuctionEffect)
    private val auctionMenuEpochs = ConcurrentHashMap<Int, Long>()
    private val auctionAcknowledgedMenuEpochs = ConcurrentHashMap<Int, Long>()
    private val auctionMenuContentEpochs = ConcurrentHashMap<Pair<Int, Long>, Long>()
    private val auctionEpochCounter = AtomicLong()
    private val serverMenuStates = linkedMapOf<Pair<Int, Long>, SchematicServerMenuState>()
    private val menuInventoryMappings = linkedMapOf<Pair<Int, Long>, Map<Int, Int>>()
    private var inventorySyncOrdinal = 0L
    private var inventoryFullSyncOrdinal = 0L
    private var inventoryFullSyncMenuId = -1
    private val auctionReceiptIds = AtomicLong()
    private val incomingAuctionReceipts = BoundedSchematicInbox<SchematicAuctionChatReceipt>(64)
    private val auctionReceipts = mutableListOf<AuctionPurchaseReceipt>()
    @Volatile private var activeAuctionReceiptAttempt: SchematicAuctionAttempt? = null
    private val auctionLayoutSignatures = mutableMapOf<Pair<Int, Long>, String>()
    private val ownedAuctionMenus = linkedSetOf<Int>()
    private val ownedSellMenus = linkedSetOf<Int>()
    private val ownedSellMenuScreens = mutableMapOf<Int, Long>()
    private val blockedPurchaseItems = linkedSetOf<String>()
    private val waitingForInventoryRoom = linkedSetOf<String>()
    private val runStartItemCounts = mutableMapOf<String, Int>()
    private val runStartStackCounts = mutableMapOf<String, Int>()
    private val acknowledgedPurchaseCounts = mutableMapOf<String, Int>()
    private val acquiredSurplusByIdentity = mutableMapOf<String, Int>()
    private val acquiredSurplusItemIds = mutableMapOf<String, String>()
    private val soldSurplusByIdentity = mutableMapOf<String, Int>()
    private val runStartInventoryBySlot = mutableMapOf<Int, InventorySlotValue>()
    private val latestServerInventoryBySlot = mutableMapOf<Int, InventorySlotValue>()
    private var activePurchaseBaselineInventory: Map<String, InventorySlotValue> = emptyMap()
    private val incomingBlockUpdates = BoundedSchematicInbox<SchematicBlockUpdate>(8_192)
    private val blockPacketOrdinal = AtomicLong()
    private val incomingCrafterData = BoundedSchematicInbox<SchematicCrafterDataUpdate>(128)
    private val incomingCrafterOpens = BoundedSchematicInbox<SchematicCrafterOpenUpdate>(16)
    private val crafterOpenOrdinal = AtomicLong()
    private val expectedStates = linkedMapOf<BuildCellPos, BlockState>()
    private val expectedCrafterMasks = linkedMapOf<BuildCellPos, List<Boolean>>()
    private val completedCrafters = linkedSetOf<BuildCellPos>()
    private var waterRefillTask: SchematicWaterRefillTask? = null
    private var waterlogTask: SchematicWaterlogTask? = null
    private val waterRefillCooldowns = mutableMapOf<Long, Long>()
    private var refilledWaterBucketCount = 0
    private val missingCells = linkedMapOf<BuildCellPos, BuildCell>()
    private val completedCells = linkedSetOf<BuildCellPos>()
    private val pendingWaterlogCells = linkedSetOf<BuildCellPos>()
    private val excludedWaterlogCells = linkedSetOf<BuildCellPos>()
    private val pendingVirtualData = linkedSetOf<BuildCellPos>()
    private val unresolvedCells = linkedSetOf<BuildCellPos>()
    private val deferredCells = linkedSetOf<BuildCellPos>()
    private val relaxedCandidates = linkedSetOf<BuildCellPos>()
    private val relaxedCompleted = linkedSetOf<BuildCellPos>()
    private val provisionalChests = linkedSetOf<BuildCellPos>()
    private val provisionalChestAt = mutableMapOf<BuildCellPos, Long>()
    private val controlAdjustmentClicks = mutableMapOf<BuildCellPos, Int>()
    private val planningWindows = sortedMapOf<SchematicPlanningWindowKey, LinkedHashSet<BuildCellPos>>()
    private val planningWindowByPos = mutableMapOf<BuildCellPos, SchematicPlanningWindowKey>()
    private val plannedTargetQueue = ArrayDeque<BuildCellPos>()
    private var planningWindowOrder: List<SchematicPlanningWindowKey> = emptyList()
    private var planningWaitingWindows = linkedSetOf<SchematicPlanningWindowKey>()
    private var planningWindowCursor = 0
    private var activePlanningWindow: SchematicPlanningWindowKey? = null
    private var planningIndexRevision = 0L
    private var planningPassRevision = -1L
    private var planningPassProgress = 0L
    private var placementProgressGeneration = 0L
    private var planningWorkPending = false
    private val stagedLeases = ArrayDeque<StagedSlotLease>()
    private val ownedScaffolds = linkedMapOf<Long, OwnedSchematicScaffold>()
    private val retainedScaffolds = linkedSetOf<Long>()
    private val actionFailureGate = BuildActionFailureGate(ACTION_RETRY_INTERVAL_TICKS.toLong())
    private var activeCrafterTask: SchematicCrafterTask? = null
    private var activeScaffoldTask: SchematicScaffoldTask? = null
    private var activePillarTask: SchematicPillarTask? = null
    private var activeDigOutTask: SchematicDigOutTask? = null
    private var nextDigOutAllowedTick = 0L
    private var scaffoldCleanupPos: BlockPos? = null
    private var scaffoldCleanupSentAt = 0L
    private var scaffoldCleanupRejected = false
    private var scaffoldRetreatTarget: BuildCellPos? = null
    private var scaffoldRetreatForPos: Long? = null
    private var scaffoldRetreatStartedAt = 0L
    private val scaffoldStepsByTarget = mutableMapOf<BuildCellPos, Int>()
    private val pillarRunsByTarget = mutableMapOf<BuildCellPos, Int>()

    private var phase = SchematicPhase.SCANNING
    private var frame: LitematicaPlacementFrame? = null
    private var layerScanCursor: SchematicLayerScanCursor? = null
    private var semanticWindowKey: SchematicLayerWindowKey? = null
    private var semanticWindowCursor = 0
    private var activeLayerWindow: SchematicLayerWindow? = null
    private var prefetchedLayerWindow: SchematicLayerWindow? = null
    private val blockedLayerWindows = sortedSetOf<SchematicLayerWindowKey>()
    private val retryLayerWindows = ArrayDeque<SchematicLayerWindowKey>()
    private val unresolvedLayerWindows = ArrayDeque<SchematicLayerWindowKey>()
    private var activeLayerWindowIsRetry = false
    private var retryLayerProgressBaseline = 0L
    private var unresolvedLayerFinalization = false
    private var layerPassProgressBaseline = 0L
    private var finalAuditDone = false
    private var confirmedCellCount = 0L
    private var excludedTntCount = 0
    private var excludedFluidCount = 0
    private val unresolvedCountByLayerWindow = mutableMapOf<SchematicLayerWindowKey, Int>()
    private val excludedCountByLayerWindow = mutableMapOf<SchematicLayerWindowKey, Pair<Int, Int>>()
    private var activeWorld: ClientLevel? = null
    private var activePlayer: LocalPlayer? = null
    private var activeConnection: ClientPacketListener? = null
    @Volatile private var runGeneration = 0L
    private var tick = 0L
    private var nextFrameRefresh = 0L
    private var nextVirtualRetryTick = 0L
    private var nextPlacementTick = 0L
    private var lastPlacementTick: Long? = null
    private var sneakLeaseTarget: BuildCellPos? = null
    private var sneakLeaseOwned = false
    private var sneakWasDownBeforeLease = false
    private var sneakWaitTicks = 0
    private var lastSneakSample: Boolean? = null
    private var sneakStableTicks = 0
    private var sneakPhysicalDownAtAcquire = false
    private var sneakReleaseTarget: BuildCellPos? = null
    private var sneakReleaseWaitTicks = 0
    private val physicalShiftKeys = linkedSetOf<Int>()
    private var physicalShiftInputKnown = false
    private var physicalShiftHint = false
    private var currentTarget: BuildCellPos? = null
    private var placementCandidate: SchematicPlacementCandidate? = null
    private var placementCandidateFor: BuildCellPos? = null
    private var rotationWaitTarget: BuildCellPos? = null
    private var rotationWaitSince = 0L
    private var route: List<BuildCellPos> = emptyList()
    private var routeIndex = 0
    private var routeTarget: BuildCellPos? = null
    private var waitingForPlacement: BuildCellPos? = null
    private var placementSentAt = 0L
    private var placementAttempts = 0
    private var relaxedPass = false
    private var deferredPass = false
    private var pendingSwapLease: StagedSlotLease? = null
    private var pendingSwapStarted = 0L
    private var pendingSwapRestore = false
    private var restorationAfterDisable = false
    private var pendingExpectedSource: InventorySlotValue? = null
    private var pendingExpectedDestination: InventorySlotValue? = null
    private var inventoryGeneration = 0L
    private var inventoryConnectionId = 0L
    private var auctionTicketId = 0L
    private var auctionCommittedTick = Long.MIN_VALUE
    private var activePurchaseItemId: String? = null
    private var activePurchaseAllowedItems: Set<String> = emptySet()
    private var auctionCommandPending = false
    private var awaitingAuctionConfirmation = false
    private var auctionListPriceCap: BigDecimal? = null
    private var auctionTotalSpendCap: BigDecimal? = null
    private var sellCommandPending = false
    private var sellRunId = 0L
    private var sellCyclesStarted = 0
    private var nextSellTick = 0L
    private var sellFinishedForRun = false
    private var sellTerminalHandled = false
    private var sellPartialReturnVerified = false
    private var sellCloseRequested = false
    private var sellInventoryBeforeClose: Map<Int, InventorySlotValue>? = null
    private var pendingSellOffer: SchematicSellOfferPending? = null
    private var pendingSellConfirmation: SchematicSellConfirmPending? = null
    private var pendingSellReturn: SchematicSellReturnCapture? = null
    private var buildFullyComplete = false
    private var scanFinalized = false
    private var truncatedReported = false
    private var lastMessage = ""
    private var lastMessageTick = Long.MIN_VALUE
    private var eatingTicks = 0
    private var eating = false
    private var eatingStartFood = -1
    private var eatingMenuSlot = -1
    private var eatingBeforeStack: InventorySlotValue? = null
    private var eatingInventoryAcknowledged = false
    private var eatingUseFinished = false
    private var nextEatAttemptTick = 0L
    private var missingTotemReported = false
    private var totemSwapFailures = 0
    private var nextTotemAttemptTick = 0L
    private var retainedScaffoldReportSent = false

    init {
        enabledValue.doNotIncludeAlways().notPersistent()
        enabledValue.onChanged { newState -> if (!newState) cleanupTransientState() }
        literalDescription {
            "Builds every enabled Litematica placement from its transformed projected world, confirms placements from server updates, and reports bounded unresolved work."
        }
    }

    override fun onEnabled() {
        if (restorationAfterDisable) {
            message("Finish restoring staged inventory before starting another build.", true)
            enabled = false
            return
        }
        auctionPurchaseWorkflow.resetRun()
        physicalShiftKeys.clear()
        physicalShiftInputKnown = false
        physicalShiftHint = mc.options.keyShift.isDown
        auctionActionGate.clear()
        clearAuctionMenuTracking()
        blockedPurchaseItems.clear()
        waitingForInventoryRoom.clear()
        runStartItemCounts.clear()
        runStartStackCounts.clear()
        acknowledgedPurchaseCounts.clear()
        acquiredSurplusByIdentity.clear()
        acquiredSurplusItemIds.clear()
        soldSurplusByIdentity.clear()
        runStartInventoryBySlot.clear()
        latestServerInventoryBySlot.clear()
        serverMenuStates.clear()
        menuInventoryMappings.clear()
        inventorySyncOrdinal = 0L
        inventoryFullSyncOrdinal = 0L
        inventoryFullSyncMenuId = -1
        activePurchaseBaselineInventory = emptyMap()
        if (ownedScaffolds.isNotEmpty()) {
            message("The previous run left ${ownedScaffolds.size} owned scaffold block(s); they were not mined without a fresh safety check.", true)
        }
        ownedScaffolds.clear()
        retainedScaffolds.clear()
        scaffoldStepsByTarget.clear()
        pillarRunsByTarget.clear()
        activePillarTask = null
        retainedScaffoldReportSent = false
        activePurchaseItemId = null
        sellRunId = runGeneration + 1L
        sellCyclesStarted = 0
        nextSellTick = 0L
        sellFinishedForRun = false
        sellTerminalHandled = false
        sellPartialReturnVerified = false
        sellCloseRequested = false
        sellInventoryBeforeClose = null
        pendingSellOffer = null
        pendingSellConfirmation = null
        pendingSellReturn = null
        sellCommandPending = false
        buildFullyComplete = false
        auctionListPriceCap = BuildMoneyRules.parse(maxPerListing)?.let(BigDecimal::valueOf)
        auctionTotalSpendCap = totalSpendLimit.takeIf(String::isNotBlank)
            ?.let { BuildMoneyRules.parse(it) }?.let(BigDecimal::valueOf)
        tick = 0
        nextFrameRefresh = 0
        runGeneration++
        resetRun()
        if (buyFromAh && auctionListPriceCap == null) {
            message("Set Max Price Per Listing to a valid amount such as 10m.", true)
            enabled = false
            return
        }
        if (buyFromAh && totalSpendLimit.isNotBlank() && auctionTotalSpendCap == null) {
            message("Set Max Total Spend to a valid amount such as 500m, or leave it empty.", true)
            enabled = false
            return
        }
        if (!LitematicaRuntimeAdapter.modsAvailable()) {
            message("Litematica and MaLiLib must both be installed.", true)
            enabled = false
            return
        }
        val player = mc.player
        val world = mc.level
        val connection = mc.connection
        if (player == null || world == null || connection == null) {
            message("Join a world before enabling the builder.", true)
            enabled = false
            return
        }
        activePlayer = player
        activeWorld = world
        activeConnection = connection
        for (index in 0..35) {
            val stack = player.inventory.getItem(index)
            if (!stack.isEmpty) {
                val itemId = BuiltInRegistries.ITEM.getKey(stack.item).toString()
                runStartItemCounts[itemId] = (runStartItemCounts[itemId] ?: 0) + stack.count
                val value = InventorySlotValue.fromStack(stack)
                val identity = SchematicSurplusRules.stackIdentity(value)
                runStartStackCounts[identity] = (runStartStackCounts[identity] ?: 0) + value.count
                val menuSlot = if (index < 9) 36 + index else index
                runStartInventoryBySlot[menuSlot] = value
                latestServerInventoryBySlot[menuSlot] = value
            }
        }
        setInventoryEpoch(connection)
        beginSnapshot()
    }

    override fun onDisabled() {
        cleanupTransientState()
    }

    internal fun onSchematicWorldChanging(world: ClientLevel?) {
        cleanupTransientState(force = world !== activeWorld)
        if (world == null && enabled) enabled = false
    }

    internal fun onSchematicSessionStarted(world: ClientLevel) {
        if (!enabled || mc.level !== world) return
        val player = mc.player ?: return
        if (player.level() !== world) return
        val connection = mc.connection ?: return
        activeWorld = world
        activePlayer = player
        activeConnection = connection
        setInventoryEpoch(connection)
        beginSnapshot()
    }

    internal fun onSchematicShutdown() {
        cleanupTransientState(force = true)
    }

    private fun cleanupTransientState(force: Boolean = false) {
        stopSchematicSaleForLifecycle(force)
        if (SchematicRestoreRules.shouldRestoreOnDisable(
                enabled,
                force,
                activeWorld != null && activeWorld === mc.level,
                activePlayer != null && activePlayer === mc.player,
                activeConnection != null && activeConnection === mc.connection,
                stagedLeases.size,
                pendingSwapLease != null,
            )) {
            beginDisableRestoration()
            return
        }
        runGeneration++
        mc.options.keyUse.setDown(false)
        route = emptyList()
        routeIndex = 0
        waitingForPlacement = null
        inventoryAckTracker.abandon()
        inventoryAckTracker.reset()
        inventoryAckChannel.reset()
        incomingBlockUpdates.clear()
        if (stagedLeases.isNotEmpty() || pendingSwapLease != null) {
            message("Builder stopped with inventory swaps still staged; no unsafe automatic restore was attempted.", true)
        }
        resetRun()
        activePlayer = null
        activeWorld = null
        activeConnection = null
        restorationAfterDisable = false
    }

    private fun stopSchematicSaleForLifecycle(force: Boolean) {
        val status = sellWorkflow.status()
        if (status == DonutSellStatus.IDLE) return
        val sameSession = activeWorld != null && activeWorld === mc.level && activePlayer === mc.player &&
            activeConnection === mc.connection
        if (!force && sameSession) {
            val player = mc.player
            val screen = mc.gui.screen() as? AbstractContainerScreen<*>
            if (player != null && screen != null && player.containerMenu.containerId in ownedSellMenus &&
                ownedSellMenuScreens[player.containerMenu.containerId] == screen.identityHash() &&
                mc.gui.screen() === screen) player.closeContainer()
        }
        if (status !in setOf(DonutSellStatus.COMPLETE, DonutSellStatus.PARTIAL, DonutSellStatus.FAILED, DonutSellStatus.UNCERTAIN)) {
            sellWorkflow.tick(DonutSellFrame(
                session = SessionStamp(Long.MIN_VALUE, 0L, 0L, 0L),
                nowNanos = System.nanoTime(),
                canIssueCommand = false,
                menu = null,
                menuMapEpoch = Long.MIN_VALUE,
                menuOpenAcknowledged = false,
                playerInventoryMenuSlots = emptyMap(),
                sellItemSlots = emptySet(),
                confirmationSlot = null,
            ))
        }
        pendingSellOffer = null
        pendingSellConfirmation = null
        pendingSellReturn = null
        sellCommandPending = false
    }

    private fun beginDisableRestoration() {
        if (restorationAfterDisable) return
        restorationAfterDisable = true
        runGeneration++
        mc.options.keyUse.setDown(false)
        cancelDigOutTask()
        releaseSneakLease()
        route = emptyList()
        routeIndex = 0
        routeTarget = null
        waitingForPlacement = null
        placementCandidate = null
        placementCandidateFor = null
        rotationWaitTarget = null
        incomingBlockUpdates.clear()
        // Keep the authoritative swap tracker/channel alive so an in-flight acknowledged swap can
        // first become a lease, then restore safely from the always-on lifecycle listener.
        resetBuildData()
        frame = null
        layerScanCursor = null
        activeLayerWindow = null
        prefetchedLayerWindow = null
        semanticWindowKey = null
        semanticWindowCursor = 0
        phase = SchematicPhase.RESTORING
        eating = false
        eatingTicks = 0
        eatingStartFood = -1
        eatingMenuSlot = -1
        eatingBeforeStack = null
        eatingInventoryAcknowledged = false
        eatingUseFinished = false
        nextEatAttemptTick = 0L
        if (stagedLeases.isEmpty() && pendingSwapLease == null) finishDisableRestoration()
        else message("Builder disabled; restoring acknowledged inventory staging in the current session.", false)
    }

    internal val awaitingDisabledRestoration: Boolean
        get() = restorationAfterDisable

    internal fun onSchematicLifecyclePacket(
        origin: TransferOrigin,
        original: Boolean,
        packet: net.minecraft.network.protocol.Packet<*>,
    ) {
        if (!restorationAfterDisable) return
        inventoryAckChannel.onPacket(origin, original, packet)
    }

    internal fun onSchematicLifecycleTick() {
        if (!restorationAfterDisable || enabled) return
        val player = mc.player
        val world = mc.level
        val connection = mc.connection
        if (player == null || world == null || connection == null || world !== activeWorld ||
            connection !== activeConnection || player !== activePlayer) {
            cleanupTransientState(force = true)
            return
        }
        tick++
        observeSneakState(player)
        drainInventoryAcks(player, world, connection)
        processPendingSwap(player, world, connection)
        if (pendingSwapLease == null) restoreNextLease(player, world, connection)
        if (phase == SchematicPhase.COMPLETE && pendingSwapLease == null && stagedLeases.isEmpty()) {
            finishDisableRestoration()
        }
    }

    private fun finishDisableRestoration() {
        restorationAfterDisable = false
        cleanupTransientState(force = true)
    }

    fun shutdown() {
        if (enabled) enabled = false else onDisabled()
    }

    @Suppress("unused")
    private val manualShiftInput = handler<KeyboardKeyEvent> { event ->
        val key = event.key.value
        if (key != LEFT_SHIFT_KEY && key != RIGHT_SHIFT_KEY) return@handler
        physicalShiftInputKnown = true
        if (event.isPressed) physicalShiftKeys += key else physicalShiftKeys -= key
        physicalShiftHint = physicalShiftKeys.isNotEmpty()
    }

    @Suppress("unused")
    private val inventoryPackets = handler<PacketEvent> { event ->
        inventoryAckChannel.onPacket(event.origin, event.original, event.packet)
        if (event.origin != TransferOrigin.INCOMING || !event.original) return@handler
        val generation = runGeneration
        when (val packet = event.packet) {
            is ClientboundOpenScreenPacket -> {
                val menuId = packet.containerId
                auctionMenuEpochs[menuId] = auctionEpochCounter.incrementAndGet()
                incomingCrafterOpens.offer(SchematicCrafterOpenUpdate(generation, menuId, crafterOpenOrdinal.incrementAndGet()))
            }
            is ClientboundSystemChatPacket -> {
                val attempt = activeAuctionReceiptAttempt ?: return@handler
                incomingAuctionReceipts.offer(SchematicAuctionChatReceipt(
                    attempt.session,
                    attempt.attemptId,
                    packet.content.string,
                ))
            }
            is ClientboundContainerSetDataPacket -> incomingCrafterData.offer(
                SchematicCrafterDataUpdate(generation, packet.containerId, packet.id, packet.value),
            )
        }
    }

    @Suppress("unused")
    private val blockPackets = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING || !event.original) return@handler
        val generation = runGeneration
        when (val packet = event.packet) {
            is ClientboundBlockUpdatePacket -> incomingBlockUpdates.offer(
                SchematicBlockUpdate(generation, packet.pos.immutable(), packet.blockState, blockPacketOrdinal.incrementAndGet()),
            )
            is ClientboundSectionBlocksUpdatePacket -> packet.runUpdates { pos, state ->
                incomingBlockUpdates.offer(SchematicBlockUpdate(generation, pos.immutable(), state, blockPacketOrdinal.incrementAndGet()))
            }
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val player = mc.player
        val world = mc.level
        val connection = mc.connection
        if (player == null || world == null || connection == null) {
            if (activePlayer != null || activeWorld != null || activeConnection != null) resetForSessionChange()
            return@handler
        }
        if (player.level() !== world) return@handler
        tick++
        if (world !== activeWorld || player !== activePlayer || connection !== activeConnection) {
            resetForSessionChange()
            activeWorld = world
            activePlayer = player
            activeConnection = connection
            setInventoryEpoch(connection)
            beginSnapshot()
            return@handler
        }
        if (!walkWithBaritone) BaritoneGoalBridge.cancelOwned(this)
        val sellNeedsFrame = sellWorkflow.status() != DonutSellStatus.IDLE ||
            sellLeftovers && buildFullyComplete && phase == SchematicPhase.COMPLETE && !sellFinishedForRun
        val purchaseFrame = if (activePurchaseItemId != null || sellNeedsFrame) captureAuctionFrame(player, world, connection) else null
        if (purchaseFrame?.confirmation != null) auctionPurchaseWorkflow.captureConfirmationMenuMap(purchaseFrame)
        drainInventoryAcks(player, world, connection)
        drainCrafterData(player)
        processServerUpdates(world)
        if (serviceDigOutTask(player, world, connection)) return@handler
        if (activePurchaseItemId != null && serviceAuctionPurchase(player, world, connection, purchaseFrame)) return@handler
        if (serviceSchematicSale(player, world, connection, purchaseFrame)) return@handler
        if (serviceWaterRefill(player, world)) return@handler
        if (serviceWaterlogTask(player, world, connection)) return@handler
        if (serviceCrafterConfiguration(player, world)) return@handler
        if (servicePillarClimb(player, world, connection)) return@handler
        if (serviceScaffoldTask(player, world, connection)) return@handler
        if (phase == SchematicPhase.BUILDING && missingCells.isEmpty() && unresolvedCells.isEmpty() &&
            pendingVirtualData.isEmpty() && serviceScaffoldCleanup(player, world)) return@handler
        if (mc.gui.screen() != null) return@handler
        if (tick >= nextFrameRefresh) {
            nextFrameRefresh = tick + 20
            refreshPlacementFrame()
        }
        if (phase == SchematicPhase.SCANNING) scanTick(world)
        retryVirtualData(world)
        if (phase == SchematicPhase.BUILDING && !scanFinalized) finalizeScanIfPossible()
        if (phase == SchematicPhase.BUILDING && scanFinalized) refreshSemanticPage(world)
        if (pendingSwapLease != null) {
            processPendingSwap(player, world, connection)
            return@handler
        }
        if (waitingForPlacement != null) {
            if (tick - placementSentAt >= PLACE_TIMEOUT_TICKS) placementTimeout()
            return@handler
        }
        if (phase != SchematicPhase.RESTORING && ensureTotemInOffhand(player, world)) return@handler
        if ((eating || eatWhenHungry) && tryEat(player)) return@handler
        if (phase == SchematicPhase.RESTORING) {
            restoreNextLease(player, world, connection)
            return@handler
        }
        if (phase != SchematicPhase.BUILDING) return@handler
        if (tick < nextPlacementTick) return@handler
        buildTick(player, world)
    }

    private fun beginSnapshot() {
        when (val result = LitematicaRuntimeAdapter.snapshot()) {
            is LitematicaFrameResult.Unavailable -> {
                message(result.reason, true)
                enabled = false
            }
            is LitematicaFrameResult.Ready -> {
                frame = result.frame
                val nextRegions = result.frame.enabledRegions.map { region ->
                    SchematicRegion(
                        region.placementId,
                        BuildCellPos(region.min.x, region.min.y, region.min.z),
                        BuildCellPos(region.max.x, region.max.y, region.max.z),
                    )
                }
                layerScanCursor = SchematicLayerScanCursor(nextRegions, layerSpan = 2)
                semanticWindowKey = null
                semanticWindowCursor = 0
                phase = SchematicPhase.SCANNING
                layerPassProgressBaseline = placementProgressGeneration
                finalAuditDone = false
                confirmedCellCount = 0L
                excludedTntCount = 0
                excludedFluidCount = 0
                scanFinalized = false
                if (nextRegions.isEmpty()) message("No enabled placement regions were returned.", true)
                else message("Scanning ${nextRegions.size} enabled placement region(s).", false)
            }
        }
    }

    private fun refreshPlacementFrame() {
        when (val result = LitematicaRuntimeAdapter.snapshot()) {
            is LitematicaFrameResult.Unavailable -> {
                if (frame != null) {
                    message(result.reason, true)
                    resetBuildData()
                    frame = null
                    phase = SchematicPhase.SCANNING
                }
            }
            is LitematicaFrameResult.Ready -> {
                val current = frame
                if (current == null || current.identity != result.frame.identity) {
                    message("Enabled placements changed; cancelling the old plan and rescanning.", false)
                    restartSchematicScan()
                } else {
                    frame = result.frame
                }
            }
        }
    }

    private fun scanTick(world: ClientLevel) {
        if (tick < nextVirtualRetryTick) return
        val cursor = layerScanCursor ?: return
        val currentFrame = frame ?: return
        if (unresolvedLayerWindows.isNotEmpty()) {
            finalizeUnresolvedLayerWindow(currentFrame, world, unresolvedLayerWindows.removeFirst())
            return
        }
        val retryKey = retryLayerWindows.removeFirstOrNull()
        val isRetry = retryKey != null
        val window = if (retryKey != null) {
            blockedLayerWindows.remove(retryKey)
            cursor.windowAt(retryKey.y, retryKey.chunkX, retryKey.chunkZ)
        } else if (!cursor.isComplete) {
            cursor.nextWindow()
        } else {
            finishLayerScanPass(currentFrame, world)
            return
        }
        if (window == null) {
            finishLayerScanPass(currentFrame, world)
            return
        }
        clearLayerWindowState()
        activeLayerWindow = window
        activeLayerWindowIsRetry = isRetry
        for (pos in window.positions) readExpected(currentFrame, world, pos)
        phase = SchematicPhase.BUILDING
        scanFinalized = false
        finalizeScanIfPossible()
        if (window.positions.size > SCAN_BUDGET) {
            message("A schematic planning window exceeded its bounded scan budget and was held for a later pass.", true)
            blockedLayerWindows += window.key
            clearLayerWindowState()
            activeLayerWindow = null
            phase = SchematicPhase.SCANNING
        }
    }

    private fun clearLayerWindowState() {
        val positions = activeLayerWindow?.positions.orEmpty()
        for (pos in positions) {
            removePlanningIndex(pos)
            expectedStates -= pos
            missingCells -= pos
            completedCells -= pos
            expectedCrafterMasks -= pos
            completedCrafters -= pos
            unresolvedCells -= pos
            deferredCells -= pos
            relaxedCandidates -= pos
            provisionalChests -= pos
            provisionalChestAt -= pos
            controlAdjustmentClicks -= pos
            retryLedger.clearPosition(pos)
            pendingWaterlogCells -= pos
            excludedWaterlogCells -= pos
            if (waterlogTask?.target == pos) waterlogTask = null
        }
        planningWindows.clear()
        planningWindowByPos.clear()
        resetPlanningPass()
        currentTarget = null
        placementCandidate = null
        placementCandidateFor = null
        rotationWaitTarget = null
        waitingForPlacement = null
        placementSentAt = 0L
        route = emptyList()
        routeIndex = 0
        routeTarget = null
        semanticWindowKey = null
        semanticWindowCursor = 0
    }

    private fun finalizeUnresolvedLayerWindow(currentFrame: LitematicaPlacementFrame, world: ClientLevel, key: SchematicLayerWindowKey) {
        val window = layerScanCursor?.windowAt(key.y, key.chunkX, key.chunkZ) ?: return
        clearLayerWindowState()
        activeLayerWindow = window
        for (pos in window.positions) readExpected(currentFrame, world, pos)
        val (tntExcluded, fluidExcluded) = excludeDisabledCells(window)
        val unresolved = unresolvedLayerCellCount(window)
        if (pendingVirtualData.any { it in window.positions }) {
            pendingVirtualData.removeAll(window.positions.toSet())
            message("Unavailable projected cells in layer ${key.y} could not be read before the final pass.", true)
        }
        if (unresolved > 0) unresolvedCountByLayerWindow[key] = unresolved
        else unresolvedCountByLayerWindow.remove(key)
        recordExcludedCount(key, tntExcluded to fluidExcluded)
        unresolvedCells.clear()
        deferredCells.clear()
        if (unresolved > 0) {
            message("$unresolved cells in layer ${key.y} remain unresolved because no dependency-safe plan made progress.", true)
        }
        clearLayerWindowState()
        activeLayerWindow = null
        deferredPass = false
        relaxedPass = false
        phase = SchematicPhase.SCANNING
    }

    private fun finishLayerScanPass(currentFrame: LitematicaPlacementFrame, world: ClientLevel) {
        if (unresolvedLayerWindows.isNotEmpty()) return
        if (retryLayerWindows.isNotEmpty()) return
        if (pendingVirtualData.isNotEmpty()) {
            restartLayerScanPass()
            nextVirtualRetryTick = tick + VIRTUAL_RETRY_INTERVAL_TICKS
            return
        }
        if (blockedLayerWindows.isNotEmpty()) {
            if (!unresolvedLayerFinalization) {
                unresolvedLayerFinalization = true
                retryLayerProgressBaseline = placementProgressGeneration
                retryLayerWindows.addAll(blockedLayerWindows.toList())
            } else if (placementProgressGeneration > retryLayerProgressBaseline) {
                retryLayerProgressBaseline = placementProgressGeneration
                retryLayerWindows.addAll(blockedLayerWindows.toList())
            } else {
                unresolvedLayerWindows.addAll(blockedLayerWindows.toList())
                blockedLayerWindows.clear()
                unresolvedLayerFinalization = false
            }
            return
        }
        unresolvedLayerFinalization = false
        if (placementProgressGeneration != layerPassProgressBaseline) {
            finalAuditDone = false
            restartLayerScanPass()
            return
        }
        if (!finalAuditDone) {
            finalAuditDone = true
            restartLayerScanPass()
            return
        }
        finishOrReport(world)
    }

    private fun restartLayerScanPass() {
        val cursor = layerScanCursor ?: return
        clearLayerWindowState()
        activeLayerWindow = null
        prefetchedLayerWindow = null
        blockedLayerWindows.clear()
        retryLayerWindows.clear()
        unresolvedLayerWindows.clear()
        unresolvedLayerFinalization = false
        pendingVirtualData.clear()
        cursor.reset()
        phase = SchematicPhase.SCANNING
        scanFinalized = false
        layerPassProgressBaseline = placementProgressGeneration
        confirmedCellCount = 0L
        excludedTntCount = 0
        excludedFluidCount = 0
        unresolvedCountByLayerWindow.clear()
        excludedCountByLayerWindow.clear()
    }

    private fun completeLayerWindow() {
        val window = activeLayerWindow ?: return
        val (tntExcluded, fluidExcluded) = excludeDisabledCells(window)
        val unresolvedCount = unresolvedLayerCellCount(window)
        if (planningWaitingWindows.isNotEmpty() || unresolvedCount > 0) {
            blockedLayerWindows += window.key
            unresolvedCountByLayerWindow[window.key] = unresolvedCount
        } else {
            blockedLayerWindows -= window.key
            unresolvedCountByLayerWindow.remove(window.key)
        }
        recordExcludedCount(window.key, tntExcluded to fluidExcluded)
        clearLayerWindowState()
        activeLayerWindow = null
        activeLayerWindowIsRetry = false
        deferredPass = false
        relaxedPass = false
        phase = SchematicPhase.SCANNING
        scanFinalized = false
        if (retryLayerWindows.isEmpty() && unresolvedLayerFinalization) {
            if (blockedLayerWindows.isNotEmpty() && placementProgressGeneration > retryLayerProgressBaseline) {
                retryLayerProgressBaseline = placementProgressGeneration
                retryLayerWindows.addAll(blockedLayerWindows.toList())
            } else if (blockedLayerWindows.isNotEmpty()) {
                unresolvedLayerWindows.addAll(blockedLayerWindows.toList())
                blockedLayerWindows.clear()
                unresolvedLayerFinalization = false
            } else {
                unresolvedLayerFinalization = false
            }
        }
    }

    private fun unresolvedLayerCellCount(window: SchematicLayerWindow): Int = window.positions.count { pos ->
        val expected = expectedStates[pos]
        val cellRole = missingCells[pos]?.role ?: expected?.let { buildCell(pos, it).role }
        layerCellIsUnresolved(
            pendingVirtualData = pos in pendingVirtualData,
            unresolved = pos in unresolvedCells,
            deferred = pos in deferredCells,
            pendingWaterlog = pos in pendingWaterlogCells,
            missing = pos in missingCells,
            cellRole = cellRole,
            placeTnt = placeTnt,
            placeWaterLava = placeWaterLava,
            crafterMaskPending = pos in expectedCrafterMasks && pos !in completedCrafters,
            chestPartnerPending = pos in provisionalChests && expected?.let { hasMissingChestPartner(pos, it) } == true,
        )
    }

    private fun recordExcludedCount(key: SchematicLayerWindowKey, counts: Pair<Int, Int>) {
        if (counts.first == 0 && counts.second == 0) excludedCountByLayerWindow.remove(key)
        else excludedCountByLayerWindow[key] = counts
    }

    private fun excludeDisabledCells(window: SchematicLayerWindow): Pair<Int, Int> {
        var tnt = 0
        var fluids = 0
        for (pos in window.positions) {
            val cell = missingCells[pos] ?: continue
            if (cell.role == BuildRole.TNT && !placeTnt) tnt++
            if (cell.role == BuildRole.FLUID && !placeWaterLava) fluids++
        }
        fluids += window.positions.count { it in excludedWaterlogCells }
        return tnt to fluids
    }

    private fun readExpected(currentFrame: LitematicaPlacementFrame, world: ClientLevel, pos: BuildCellPos) {
        pendingWaterlogCells -= pos
        excludedWaterlogCells -= pos
        if (waterlogTask?.target == pos) waterlogTask = null
        val blockPos = pos.toBlockPos()
        var projectedState: BlockState? = null
        val read = readProjectedCell(
            pos,
            isChunkLoaded = { cx, cz -> currentFrame.schematicWorld.hasChunkAt(BlockPos(cx * 16, 0, cz * 16)) },
            stateAtWorldPosition = { LitematicaRuntimeAdapter.expectedStateAtInEnabledRegion(currentFrame, blockPos)?.also { projectedState = it }
                ?: throw IllegalStateException("projected cell unavailable") },
            isAir = BlockState::isAir,
            toBuildCell = { worldPos, state -> buildCell(worldPos, state) },
        )
        if (read == SchematicCellRead.Unavailable) {
            removePlanningIndex(pos)
            pendingVirtualData += pos
            completedCells -= pos
            expectedStates -= pos
            missingCells -= pos
            return
        }
        pendingVirtualData -= pos
        if (read == SchematicCellRead.Air) {
            removePlanningIndex(pos)
            expectedStates -= pos
            missingCells -= pos
            completedCells -= pos
            unresolvedCells -= pos
            expectedCrafterMasks -= pos
            completedCrafters -= pos
            return
        }
        val expected = (read as SchematicCellRead.Block).cell
        val state = projectedState ?: return
        unresolvedCells -= pos
        expectedStates[pos] = state
        indexPlanningCell(expected)
        if (crafterSlots && state.block == Blocks.CRAFTER) {
            val mask = LitematicaRuntimeAdapter.expectedCrafterDisabledSlots(currentFrame, blockPos)
            if (mask != null && mask.any { it }) {
                if (expectedCrafterMasks[pos] != mask) {
                    completedCrafters -= pos
                    discardCrafterTaskForChangedMask(pos)
                }
                expectedCrafterMasks[pos] = mask
            } else {
                discardCrafterTaskForChangedMask(pos)
                expectedCrafterMasks -= pos
                completedCrafters -= pos
            }
        } else {
            discardCrafterTaskForChangedMask(pos)
            expectedCrafterMasks -= pos
            completedCrafters -= pos
        }
        if (world.hasChunkAt(blockPos) && statesMatch(state, world.getBlockState(blockPos), pos in relaxedCompleted)) {
            missingCells -= pos
            markCellComplete(pos)
            ownedScaffolds.values.filter { it.target == pos }.forEach { it.targetComplete = true }
        } else {
            completedCells -= pos
            missingCells[pos] = expected
            if (isChestUpdateProvisional(pos, state, world.getBlockState(blockPos))) {
                provisionalChests += pos
                provisionalChestAt.putIfAbsent(pos, tick)
            }
        }
    }

    private fun retryVirtualData(world: ClientLevel) {
        val currentFrame = frame ?: return
        val activePositions = activeLayerWindow?.positions?.toHashSet() ?: return
        if (pendingVirtualData.none { it in activePositions }) return
        if (tick < nextVirtualRetryTick) return
        nextVirtualRetryTick = tick + VIRTUAL_RETRY_INTERVAL_TICKS
        val batch = pendingVirtualData.asSequence().filter { it in activePositions }.take(PENDING_READ_BUDGET).toList()
        for (pos in batch) readExpected(currentFrame, world, pos)
    }

    /** Bounded semantic polling catches in-place schematic edits even when boxes stay unchanged. */
    private fun refreshSemanticPage(world: ClientLevel) {
        val currentFrame = frame ?: return
        val window = activeLayerWindow ?: return
        if (semanticWindowKey != window.key) {
            semanticWindowKey = window.key
            semanticWindowCursor = 0
        }
        val end = minOf(window.positions.size, semanticWindowCursor + SEMANTIC_SCAN_BUDGET)
        for (index in semanticWindowCursor until end) {
            val pos = window.positions[index]
            val before = expectedStates[pos] ?: continue
            val after = LitematicaRuntimeAdapter.expectedStateAtInEnabledRegion(currentFrame, pos.toBlockPos())
            if (after == null) {
                pendingVirtualData += pos
                continue
            }
            val beforeId = semanticStateId(before)
            val afterId = if (after.isAir) "minecraft:air" else semanticStateId(after)
            if (SchematicSemanticRules.changed(true, beforeId, true, afterId)) {
                message("The projected schematic changed in place; cancelling stale work and rescanning.", true)
                restartSchematicScan()
                return
            }
        }
        semanticWindowCursor = if (end >= window.positions.size) 0 else end
    }

    private fun semanticStateId(state: BlockState): String =
        BuiltInRegistries.BLOCK.getKey(state.block).toString() + properties(state).toSortedMap().entries.joinToString(",", "[", "]") {
            "${it.key}=${it.value}"
        }

    private fun restartSchematicScan() {
        runGeneration++
        cancelDigOutTask()
        BaritoneGoalBridge.cancelOwned(this)
        activeScaffoldTask = null
        waitingForPlacement = null
        placementSentAt = 0
        currentTarget = null
        placementCandidate = null
        placementCandidateFor = null
        rotationWaitTarget = null
        route = emptyList()
        routeIndex = 0
        routeTarget = null
        incomingBlockUpdates.clear()
        inventoryAckTracker.abandon()
        inventoryAckTracker.reset()
        inventoryAckChannel.reset()
        pendingSwapLease = null
        resetBuildData()
        beginSnapshot()
    }

    private fun finalizeScanIfPossible() {
        if (scanFinalized) return
        if (phase != SchematicPhase.BUILDING || scanFinalized) return
        scanFinalized = true
        rebuildPlan()
    }

    private fun buildTick(player: LocalPlayer, world: ClientLevel) {
        if (currentTarget == null) currentTarget = selectNextTarget(world)
        val pos = currentTarget
        if (pos == null) {
            if (planningWorkPending) return
            if (!deferredPass && deferredCells.isNotEmpty()) {
                deferredPass = true
                deferredCells.clear()
                resetPlanningPass()
                message("Retrying deferred cells after the ordinary build pass.", false)
                rebuildPlan()
                currentTarget = selectNextTarget(world)
            }
            if (currentTarget == null && !relaxedPass && relaxFacingWhenStuck && relaxedCandidates.isNotEmpty()) {
                relaxedPass = true
                resetPlanningPass()
                message("Trying one relaxed legal facing for blocks that exhausted eight exact attempts.", false)
                rebuildPlan()
                currentTarget = selectNextTarget(world)
            }
            if (currentTarget == null) completeLayerWindow()
            return
        }
        val targetPos = pos.toBlockPos()
        val expected = expectedStates[pos] ?: run { missingCells.remove(pos); currentTarget = null; return }
        val actualNow = if (world.hasChunkAt(targetPos)) world.getBlockState(targetPos) else null
        if (world.hasChunkAt(targetPos)) {
            if (actualNow != null && statesMatch(expected, actualNow, pos in relaxedCompleted)) {
                completeTarget(pos, pos in relaxedCompleted)
                return
            }
        }
        if (actualNow != null && provisionalChests.contains(pos)) {
            val partner = SchematicStructureRules.provisionalChestPartnerTarget(pos, properties(expected), missingCells.keys)
            if (partner != null) {
                currentTarget = partner
                return
            }
        }
        if (actualNow != null && provisionalChests.contains(pos) && chestPartner(pos, expected)?.let { it !in missingCells } == true) {
            if (tick - (provisionalChestAt[pos] ?: tick) < 60) return
            unresolvedCells += pos
            missingCells -= pos
            currentTarget = null
            message("Double chest at ${pos.asText()} did not merge after the partner was built.", true)
            return
        }
        if (actualNow != null && SchematicStateRules.isObstructed(
                BuiltInRegistries.BLOCK.getKey(expected.block).toString(),
                BuiltInRegistries.BLOCK.getKey(actualNow.block).toString(),
                actualNow.canBeReplaced(),
                SchematicStructureRules.isDoubleSlabPending(
                    BuiltInRegistries.BLOCK.getKey(expected.block).toString(), properties(expected),
                    BuiltInRegistries.BLOCK.getKey(actualNow.block).toString(), properties(actualNow),
                ),
            )) {
            unresolvedCells += pos
            missingCells -= pos
            currentTarget = null
            message("${pos.asText()} is occupied by ${BuiltInRegistries.BLOCK.getKey(actualNow.block)}; left untouched as an obstruction.", true)
            return
        }
        if (mc.gui.screen() != null) return
        if (expected.block == Blocks.WATER || expected.block == Blocks.LAVA) {
            if (!placeWaterLava) {
                // Keep the missing entry so the window summary can count this configured exclusion.
                currentTarget = null
                return
            }
        }
        if (expected.block == Blocks.TNT && !placeTnt) {
            currentTarget = null
            return
        }
        if (expected.block == Blocks.TNT && world.hasNeighborSignal(targetPos)) {
            unresolvedCells += pos
            currentTarget = null
            message("TNT at ${pos.asText()} is powered already; left untouched.", true)
            return
        }
        if (actualNow != null && SchematicStateRules.canonicalBlockId(BuiltInRegistries.BLOCK.getKey(expected.block).toString()) ==
            SchematicStateRules.canonicalBlockId(BuiltInRegistries.BLOCK.getKey(actualNow.block).toString()) &&
            controlStateNeedsAdjustment(expected, actualNow)) {
            if ((controlAdjustmentClicks[pos] ?: 0) >= 30) {
                unresolvedCells += pos
                missingCells -= pos
                currentTarget = null
                message("State adjustment at ${pos.asText()} reached the source's 30-click limit.", true)
                return
            }
            interactExisting(targetPos, player, world, pos, expected)
            return
        }
        val targetCell = missingCells[pos] ?: buildCell(pos, expected)
            val item = resolveItem(targetCell.itemId) ?: run {
            unresolvedCells += pos
            missingCells -= pos
            message("No placeable item exists for ${targetCell.stateId} at ${pos.asText()}.", true)
            currentTarget = null
            return
        }
        if (targetCell.role == BuildRole.MACHINE && expected.block == Blocks.CRAFTER && crafterSlots) {
            // Crafter slot filling is handled after the block is placed; the native block entity is not guessed before placement.
        }
        val inventorySlot = findHotbarItem(player, item)
        if (inventorySlot == null) {
            val storageIndex = (9..35).firstOrNull { player.inventory.getItem(it).item == item && !player.inventory.getItem(it).isEmpty }
            if (storageIndex != null) {
                beginInventorySwap(player, world, storageIndex, item)
                return
            }
            if (expected.block == Blocks.WATER && placeWaterLava && tryStartWaterRefill(player, world)) return
            if (buyFromAh && targetCell.itemId != null && lastPlacementTick?.let { tick - it < 40L } == true) return
            if (buyFromAh && targetCell.itemId != null && startMaterialPurchase(player, targetCell.itemId, item)) return
            if (targetCell.itemId in waitingForInventoryRoom) return
            val messageText = "Missing ${targetCell.itemId ?: targetCell.stateId} for ${missingCells.size} remaining cells."
            message(messageText, true)
            unresolvedCells += pos
            missingCells -= pos
            currentTarget = null
            return
        }
        player.inventory.selectedSlot = inventorySlot

        if (actualNow != null && SchematicStructureRules.isDoubleSlabPending(
                BuiltInRegistries.BLOCK.getKey(expected.block).toString(), properties(expected),
                BuiltInRegistries.BLOCK.getKey(actualNow.block).toString(), properties(actualNow),
            )) {
            interactExisting(targetPos, player, world, pos, expected)
            return
        }

        val bucketPlacement = expected.block == Blocks.WATER || expected.block == Blocks.LAVA
        val useRotation: Rotation
        val hit: BlockHitResult
        if (bucketPlacement) {
            val support = findSupport(world, targetPos, player)
            if (support == null) {
                placementCandidate = null
                placementCandidateFor = null
                if (!walkTowardStand(player, world, pos, expected, player.mainHandItem)) {
                    recordNoActionProgress(pos, "no reachable visible bucket-use face was found")
                }
                return
            }
            val point = support.second.location
            useRotation = aimAt(player, point)
            val supportHit = world.clip(ClipContext(player.eyePosition, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
                as? BlockHitResult
            if (supportHit == null || supportHit.type != HitResult.Type.BLOCK ||
                supportHit.blockPos != support.first || supportHit.direction != support.second.direction) {
                recordNoActionProgress(pos, "bucket support face is occluded")
                return
            }
            hit = supportHit
            placementCandidate = null
            placementCandidateFor = null
        } else {
            val partner = SchematicStructureRules.provisionalChestPartnerTarget(pos, properties(expected), missingCells.keys)
            val candidate = SchematicNativePlacement.findCandidate(
                world,
                player,
                targetPos,
                expected,
                player.mainHandItem,
                allowRelaxedOrientation = relaxedPass && pos in relaxedCandidates,
                allowProvisionalChest = partner != null,
                allowPartialDoubleSlab = allowPartialDoubleSlab(expected, world.getBlockState(targetPos)),
            )
            if (candidate == null) {
                placementCandidate = null
                placementCandidateFor = null
                if (!walkTowardStand(player, world, pos, expected, player.mainHandItem)) {
                    recordNoActionProgress(pos, "no reachable stand and hit orientation predicts the requested state")
                }
                return
            }
            useRotation = candidate.rotation
            hit = candidate.hit
            if (placementCandidateFor != pos || placementCandidate?.rotation?.rotationDeltaLengthTo(useRotation)?.let { it > 0.25f } != false) {
                rotationWaitTarget = pos
                rotationWaitSince = tick
            }
            placementCandidate = candidate
            placementCandidateFor = pos
            requestAim(useRotation)
        }

        if (rotationWaitTarget != pos) {
            rotationWaitTarget = pos
            rotationWaitSince = tick
        }
        if (!SchematicNativePlacement.rotationAcknowledged(
                useRotation.yaw,
                useRotation.pitch,
                RotationManager.currentRotation?.yaw,
                RotationManager.currentRotation?.pitch,
                RotationManager.serverRotation.yaw,
                RotationManager.serverRotation.pitch,
            )) {
            if (tick - rotationWaitSince >= PLACE_TIMEOUT_TICKS) {
                rotationWaitSince = tick
                recordNoActionProgress(pos, "server rotation did not reach the state-aware placement aim")
            }
            return
        }
        rotationWaitTarget = null

        // RotationManager's packet hook handles movement yaw, but STRICT correction can leave the
        // LocalPlayer's visible yaw untouched. Native BlockPlaceContext must see the same yaw that
        // the last rotation packet sent to the server before this use action.
        val activeHit = if (bucketPlacement) currentViewHit(world, player) else currentRotationHit(world, player, useRotation)
        if (activeHit == null || activeHit.type != HitResult.Type.BLOCK ||
            activeHit.blockPos != hit.blockPos || activeHit.direction != hit.direction ||
            !bucketPlacement && activeHit.location.distanceToSqr(hit.location) > 0.0004) {
            recordNoActionProgress(pos, "acknowledged rotation no longer ray-hits the planned support face")
            return
        }
        if (!ensureSneakReady(pos, activeHit.blockPos, player, world)) return
        if (tickDelay > 0 && tick % (tickDelay + 1L) != 0L) return
        val oldYaw = player.yRot
        val oldPitch = player.xRot
        val result = try {
            player.yRot = useRotation.yaw
            player.xRot = useRotation.pitch
            if (bucketPlacement) {
                // BucketItem.use owns its POV raycast; useItemOn only calls useOn, which returns PASS.
                mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
            } else {
                mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, activeHit)
            }
        } finally {
            player.yRot = oldYaw
            player.xRot = oldPitch
        }
        if (result?.consumesAction() == true) {
            placementAttempts++
            placementSentAt = tick
            waitingForPlacement = pos
            nextPlacementTick = tick + tickDelay + 1
        } else {
            recordNoActionProgress(pos, "placement use did not consume an action")
        }
    }

    private fun buildCell(pos: BuildCellPos, state: BlockState): BuildCell {
        val blockId = BuiltInRegistries.BLOCK.getKey(state.block).toString()
        val canonicalBlockId = SchematicStateRules.canonicalBlockId(blockId)
        val item = when (state.block) {
            Blocks.WATER -> Items.WATER_BUCKET
            Blocks.LAVA -> Items.LAVA_BUCKET
            Blocks.KELP_PLANT -> Blocks.KELP.asItem()
            Blocks.TWISTING_VINES_PLANT -> Blocks.TWISTING_VINES.asItem()
            Blocks.WEEPING_VINES_PLANT -> Blocks.WEEPING_VINES.asItem()
            Blocks.CAVE_VINES_PLANT -> Blocks.CAVE_VINES.asItem()
            else -> state.block.asItem().takeUnless { it == Items.AIR }
        }
        val itemId = item?.let { BuiltInRegistries.ITEM.getKey(it).toString() }
        val role = BuildRoleRules.classify(canonicalBlockId, itemId, state.block.javaClass.simpleName)
        val dependencies = linkedSetOf<BuildCellPos>()
        SchematicStructureRules.requiredPredecessor(pos, canonicalBlockId, properties(state))?.let(dependencies::add)
        if (role == BuildRole.ATTACHMENT) {
            dependencies += attachmentSupport(pos, state)
        }
        return BuildCell(pos, canonicalBlockId + properties(state).entries.joinToString(prefix = "[", postfix = "]") { "${it.key}=${it.value}" }, itemId, role, dependencies)
    }

    private fun properties(state: BlockState): Map<String, String> =
        state.properties.associate { property -> property.name to state.getValue(property).toString().lowercase() }

    private fun allowPartialDoubleSlab(expected: BlockState, actual: BlockState): Boolean =
        SchematicStructureRules.allowPartialDoubleSlab(
            BuiltInRegistries.BLOCK.getKey(expected.block).toString(), properties(expected),
            BuiltInRegistries.BLOCK.getKey(actual.block).toString(), properties(actual), actual.canBeReplaced(),
        )

    private fun controlStateNeedsAdjustment(expected: BlockState, actual: BlockState): Boolean {
        val expectedId = BuiltInRegistries.BLOCK.getKey(expected.block).toString()
        val controls = linkedSetOf("note", "inverted")
        if (expected.block in setOf(Blocks.REPEATER, Blocks.COMPARATOR) && repeatersComparators) {
            controls += "delay"
            controls += "mode"
            controls += "mode_comparator"
        }
        if (expectedId.endsWith("_door") || expectedId.endsWith("_trapdoor") || expectedId.endsWith("_fence_gate")) {
            controls += "open"
        }
        if (expected.block == Blocks.LEVER && flipLeversOn && expected.hasProperty(BlockStateProperties.POWERED) &&
            expected.getValue(BlockStateProperties.POWERED)) controls += "powered"
        return SchematicStateRules.needsControlAdjustment(expectedId, properties(expected), properties(actual), controls)
    }

    private fun chestPartner(pos: BuildCellPos, state: BlockState): BuildCellPos? =
        SchematicStructureRules.chestPartner(pos, properties(state))

    private fun projectedExpectedState(pos: BuildCellPos): BlockState? =
        expectedStates[pos] ?: frame?.let { LitematicaRuntimeAdapter.expectedStateAt(it, pos.toBlockPos()) }

    private fun hasMissingChestPartner(pos: BuildCellPos, state: BlockState): Boolean {
        val partner = chestPartner(pos, state) ?: return false
        val expectedPartner = projectedExpectedState(partner) ?: return true
        if (expectedPartner.isAir || expectedPartner.block != state.block) return false
        val world = activeWorld ?: return true
        val partnerPos = partner.toBlockPos()
        if (!world.hasChunkAt(partnerPos)) return true
        return !statesMatch(expectedPartner, world.getBlockState(partnerPos), partner in relaxedCompleted)
    }

    private fun attachmentSupport(pos: BuildCellPos, state: BlockState): BuildCellPos {
        val name = state.block.javaClass.simpleName.lowercase()
        val facing = if (state.hasProperty(BlockStateProperties.FACING)) state.getValue(BlockStateProperties.FACING) else Direction.DOWN
        return if (name.contains("wall") || name.contains("button") || name.contains("lever")) {
            val supportDirection = if (facing.axis.isVertical) Direction.DOWN else facing.opposite
            pos.offset(supportDirection.stepX, supportDirection.stepY, supportDirection.stepZ)
        } else pos.offset(0, -1, 0)
    }

    private fun indexPlanningCell(cell: BuildCell) {
        val next = SchematicBuildPlanner.windowKey(cell, pistonsObserversLast)
        val previous = planningWindowByPos[cell.pos]
        if (previous == next) return
        if (previous != null) {
            planningWindows[previous]?.let { positions ->
                positions.remove(cell.pos)
                if (positions.isEmpty()) planningWindows.remove(previous)
            }
        }
        planningWindowByPos[cell.pos] = next
        planningWindows.getOrPut(next, ::linkedSetOf).add(cell.pos)
        planningIndexRevision++
        if (scanFinalized) resetPlanningPass()
    }

    private fun removePlanningIndex(pos: BuildCellPos) {
        val previous = planningWindowByPos.remove(pos) ?: return
        planningWindows[previous]?.let { positions ->
            positions.remove(pos)
            if (positions.isEmpty()) planningWindows.remove(previous)
        }
        planningIndexRevision++
        if (scanFinalized) resetPlanningPass()
    }

    private fun resetPlanningPass() {
        plannedTargetQueue.clear()
        planningWindowOrder = emptyList()
        planningWaitingWindows.clear()
        planningWindowCursor = 0
        activePlanningWindow = null
        planningPassRevision = -1L
        planningWorkPending = false
    }

    private fun selectNextTarget(world: ClientLevel): BuildCellPos? {
        planningWorkPending = false
        while (plannedTargetQueue.isNotEmpty()) {
            val next = plannedTargetQueue.removeFirst()
            val cell = missingCells[next] ?: continue
            if (isEligiblePlanCell(cell)) return next
        }
        activePlanningWindow = null

        if (planningPassRevision != planningIndexRevision || planningWindowOrder.isEmpty() && planningWindowCursor == 0) {
            beginPlanningPass(planningWindows.keys.toList())
        }

        var processed = 0
        while (processed < PLAN_WINDOWS_PER_TICK) {
            if (planningWindowCursor >= planningWindowOrder.size) {
                if (planningWaitingWindows.isEmpty() || placementProgressGeneration == planningPassProgress) return null
                val retryWindows = planningWaitingWindows.toList().sorted()
                beginPlanningPass(retryWindows)
            }
            val key = planningWindowOrder[planningWindowCursor++]
            processed++
            val cells = planningWindows[key].orEmpty().mapNotNull(missingCells::get).filter(::isEligiblePlanCell)
            if (cells.isEmpty()) continue
            val planCells = cells.map { expectedStates[it.pos]?.let { state -> buildCell(it.pos, state) } ?: it }
            val windowPositions = planCells.mapTo(hashSetOf(), BuildCell::pos)
            val satisfied = satisfiedExistingDependencies(world, planCells, windowPositions)
            val plan = SchematicBuildPlanner.plan(
                planCells,
                completedCells,
                skipped = unresolvedCells + deferredCells + pendingVirtualData,
                machineLast = pistonsObserversLast,
                placeTnt = placeTnt,
                limit = PLAN_LIMIT,
                satisfiedDependencies = satisfied,
            )
            if (plan.unsupported.isNotEmpty()) {
                planningWaitingWindows += key
            }
            if (plan.waitingOnDependencies.isNotEmpty()) planningWaitingWindows += key
            if (plan.ordered.isNotEmpty()) {
                plannedTargetQueue.addAll(plan.ordered.map(BuildCell::pos))
                activePlanningWindow = key
                while (plannedTargetQueue.isNotEmpty()) {
                    val next = plannedTargetQueue.removeFirst()
                    val cell = missingCells[next] ?: continue
                    if (isEligiblePlanCell(cell)) return next
                }
                activePlanningWindow = null
            }
        }
        planningWorkPending = planningWindowCursor < planningWindowOrder.size ||
            planningWaitingWindows.isNotEmpty() && placementProgressGeneration != planningPassProgress
        return null
    }

    private fun beginPlanningPass(keys: List<SchematicPlanningWindowKey>) {
        planningWindowOrder = keys.distinct().filter { it in planningWindows }.sorted()
        planningWindowCursor = 0
        planningWaitingWindows.clear()
        planningPassRevision = planningIndexRevision
        planningPassProgress = placementProgressGeneration
        activePlanningWindow = null
    }

    private fun isEligiblePlanCell(cell: BuildCell): Boolean =
        cell.pos !in unresolvedCells && cell.pos !in deferredCells && cell.pos !in pendingVirtualData &&
            (if (relaxedPass) cell.pos in relaxedCandidates else cell.pos !in retryLedger.unresolved() && cell.pos !in relaxedCandidates) &&
            !(cell.pos in provisionalChests && expectedStates[cell.pos]?.let { hasMissingChestPartner(cell.pos, it) } == true) &&
            (placeTnt || cell.role != BuildRole.TNT) && (placeWaterLava || cell.role != BuildRole.FLUID)

    private fun satisfiedExistingDependencies(
        world: ClientLevel,
        cells: List<BuildCell>,
        windowPositions: Set<BuildCellPos>,
    ): Set<BuildCellPos> {
        val satisfied = linkedSetOf<BuildCellPos>()
        for (cell in cells) {
            val target = expectedStates[cell.pos] ?: continue
            val targetId = BuiltInRegistries.BLOCK.getKey(target.block).toString()
            val atomicPredecessor = SchematicStructureRules.requiredPredecessor(cell.pos, targetId, properties(target))
            for (dependency in cell.dependencies) {
                if (dependency in completedCells || dependency in windowPositions || dependency in pendingVirtualData ||
                    dependency in unresolvedCells || dependency in deferredCells) continue
                val supportPos = dependency.toBlockPos()
                if (!world.hasChunkAt(supportPos)) continue
                if (dependency == atomicPredecessor && world.getBlockState(supportPos).block == target.block) {
                    satisfied += dependency
                    continue
                }
                val projected = projectedExpectedState(dependency)
                if (projected == null) continue
                if (!projected.isAir) {
                    if (!statesMatch(projected, world.getBlockState(supportPos), dependency in relaxedCompleted)) continue
                }
                val direction = directionBetween(dependency, cell.pos) ?: continue
                if (world.getBlockState(supportPos).isFaceSturdy(world, supportPos, direction)) satisfied += dependency
            }
        }
        return satisfied
    }

    private fun directionBetween(from: BuildCellPos, to: BuildCellPos): Direction? = when {
        to.x == from.x && to.y == from.y + 1 && to.z == from.z -> Direction.UP
        to.x == from.x && to.y == from.y - 1 && to.z == from.z -> Direction.DOWN
        to.x == from.x + 1 && to.y == from.y && to.z == from.z -> Direction.EAST
        to.x == from.x - 1 && to.y == from.y && to.z == from.z -> Direction.WEST
        to.x == from.x && to.y == from.y && to.z == from.z + 1 -> Direction.SOUTH
        to.x == from.x && to.y == from.y && to.z == from.z - 1 -> Direction.NORTH
        else -> null
    }

    private fun rebuildPlan() {
        route = emptyList()
        routeIndex = 0
        routeTarget = null
        if (currentTarget !in missingCells) currentTarget = null
    }

    private fun tryStartDigOutTask(player: LocalPlayer, world: ClientLevel, visited: Int): Boolean {
        if (activeDigOutTask != null || tick < nextDigOutAllowedTick ||
            hasManualPillarMovement() || pendingSwapLease != null || activeScaffoldTask != null || activePillarTask != null ||
            mc.gui.screen() != null || player.containerMenu !== player.inventoryMenu ||
            !player.containerMenu.carried.isEmpty) return false
        val connection = activeConnection ?: return false
        val blockPos = player.blockPosition().immutable()
        if (!world.hasChunkAt(blockPos)) return false
        val escapeMaterials = (0..35).sumOf { slot -> scaffoldItemCount(player.inventory.getItem(slot)) } +
            scaffoldItemCount(player.offhandItem)
        if (!SchematicDigOutRules.canStartSourceDigOut(visited, escapeMaterials)) return false
        val state = world.getBlockState(blockPos)
        val projected = frame?.let { LitematicaRuntimeAdapter.expectedStateAt(it, blockPos) }
        val expectedForRestore = projected?.takeUnless { it.isAir }
        if (!digOutSourceBlockStillEligible(world, blockPos, state)) return false
        val desired = digOutAim(world, player, blockPos)
        val rayHitsTarget = desired?.let { currentRotationHit(world, player, it.first)?.blockPos == blockPos } == true
        val safe = SchematicDigOutRules.safeRecoveryEligible(
            withinInteractionReach = player.eyePosition.distanceToSqr(Vec3.atCenterOf(blockPos)) <=
                player.blockInteractionRange().toDouble().let { it * it },
            nativeRayHitsTarget = rayHitsTarget,
            attempts = 0,
        )
        if (!safe) return false
        activeDigOutTask = SchematicDigOutTask(
            target = BuildCellPos(blockPos.x, blockPos.y, blockPos.z),
            originalState = state,
            expectedState = expectedForRestore,
            connectionId = System.identityHashCode(connection).toLong(),
            worldId = System.identityHashCode(world).toLong(),
            playerId = System.identityHashCode(player).toLong(),
            generation = runGeneration,
        )
        route = emptyList()
        routeIndex = 0
        routeTarget = null
        message("The player is trapped in a source-eligible non-full block; starting a bounded ACK-gated dig-out.", false)
        return true
    }

    private fun serviceDigOutTask(
        player: LocalPlayer,
        world: ClientLevel,
        connection: ClientPacketListener,
    ): Boolean {
        val task = activeDigOutTask ?: return false
        if (task.generation != runGeneration || task.connectionId != System.identityHashCode(connection).toLong() ||
            task.worldId != System.identityHashCode(world).toLong() || task.playerId != System.identityHashCode(player).toLong()) {
            cancelDigOutTask()
            return false
        }
        val target = task.target.toBlockPos()
        if (!world.hasChunkAt(target) || !world.hasChunkAt(target.below())) {
            stopDigOutDestroy()
            activeDigOutTask = null
            message("Trapped-player dig-out stopped because its target or support chunk unloaded.", true)
            return false
        }
        if (task.stage == SchematicDigOutStage.WAITING_EXIT) {
            if (!world.getBlockState(target).isAir) {
                activeDigOutTask = null
                route = emptyList()
                routeIndex = 0
                routeTarget = null
                message("The dig-out cell changed before safe exit; no further block action was sent.", true)
                return false
            }
            val targetBounds = net.minecraft.world.phys.AABB(
                target.x.toDouble(), target.y.toDouble(), target.z.toDouble(),
                target.x + 1.0, target.y + 1.0, target.z + 1.0,
            )
            if (player.blockPosition() != target && !player.boundingBox.intersects(targetBounds)) {
                activeDigOutTask = null
                route = emptyList()
                routeIndex = 0
                routeTarget = null
                message(
                    if (task.expectedState != null) "The player exited the dig-out cell; normal planning will restore its projected block."
                    else "The player exited the dig-out cell; normal planning resumed.",
                    false,
                )
                return false
            }
            if (mc.gui.screen() != null || player.containerMenu !== player.inventoryMenu || hasManualPillarMovement()) {
                route = emptyList()
                routeIndex = 0
                return true
            }
            if (!task.exitRoutePlanned) {
                task.exitRoutePlanned = true
                val start = player.blockPosition().let { BuildCellPos(it.x, it.y, it.z) }
                val exit = Direction.Plane.HORIZONTAL.map { direction ->
                    start.offset(direction.stepX, 0, direction.stepZ)
                }.plus(Direction.Plane.HORIZONTAL.map { direction ->
                    start.offset(direction.stepX, 1, direction.stepZ)
                }).filter { candidate ->
                    val feet = candidate.toBlockPos()
                    val safeRouteExists = SchematicRouteRules.allows(start, candidate) &&
                        canStandAt(world, player, candidate) && safeTransition(world, player, start, candidate)
                    candidate != start && SchematicDigOutRules.canUseAutomaticExit(
                        stableFloorBelow = hasStableDigOutFloor(world, feet.below()),
                        clearHeadroom = hasDigOutHeadroom(world, feet),
                        safeRouteExists = safeRouteExists,
                    )
                }.minByOrNull { distanceSquared(start, it) }
                if (exit != null) {
                    route = listOf(exit)
                    routeIndex = 0
                    routeTarget = null
                } else if (!task.exitReportSent) {
                    task.exitReportSent = true
                    message("No safe same-level exit was available after the acknowledged dig-out; manual movement is available.", true)
                }
            }
            return true
        }

        if (task.blockAcknowledged) {
            stopDigOutDestroy()
            if (!world.getBlockState(target).isAir) {
                task.blockRejected = true
            } else {
                task.stage = SchematicDigOutStage.WAITING_EXIT
                task.ackedAt = tick
                task.exitRoutePlanned = false
                placementProgressGeneration++
                if (task.expectedState != null && activeLayerWindow?.positions?.contains(task.target) == true) {
                    frame?.let { readExpected(it, world, task.target) }
                }
                route = emptyList()
                routeIndex = 0
                routeTarget = null
                return true
            }
        }

        if (task.blockRejected) {
            stopDigOutDestroy()
            activeDigOutTask = null
            nextDigOutAllowedTick = tick + SchematicDigOutRules.RETRY_DELAY_TICKS
            message("Server state changed during trapped-player dig-out; the block was left under server control.", true)
            return false
        }
        if (mc.gui.screen() != null || player.containerMenu !== player.inventoryMenu ||
            !player.containerMenu.carried.isEmpty || hasManualPillarMovement()) {
            stopDigOutDestroy()
            if (task.stage == SchematicDigOutStage.DESTROYING) {
                task.stage = SchematicDigOutStage.WAITING_ACK
            }
            return true
        }
        if (task.stage == SchematicDigOutStage.WAITING_ACK) {
            if (tick - task.sentAt >= SchematicDigOutRules.ACK_TIMEOUT_TICKS) {
                if (world.getBlockState(target).isAir) {
                    activeDigOutTask = null
                    nextDigOutAllowedTick = tick + SchematicDigOutRules.ACK_TIMEOUT_TICKS
                    message("The local dig-out changed the block but no server air update arrived; its removal was not accepted as owned.", true)
                    return false
                }
                if (task.attempts >= SchematicDigOutRules.MAX_ATTEMPTS ||
                    player.blockPosition() != target || hasManualPillarMovement() ||
                    world.getBlockState(target) != task.originalState ||
                    !digOutSourceBlockStillEligible(world, target, task.originalState)) {
                    activeDigOutTask = null
                    nextDigOutAllowedTick = tick + SchematicDigOutRules.ACK_TIMEOUT_TICKS
                    message("Trapped-player dig-out timed out without a safe retry; the server block state remains authoritative.", true)
                    return false
                }
                task.stage = SchematicDigOutStage.AIMING
                task.retryAt = tick + SchematicDigOutRules.RETRY_DELAY_TICKS
                task.face = null
                task.blockPacketBaseline = blockPacketOrdinal.get()
            }
            return true
        }
        if (task.stage == SchematicDigOutStage.DESTROYING && tick - task.sentAt >= SchematicDigOutRules.ACK_TIMEOUT_TICKS) {
            stopDigOutDestroy()
            if (world.getBlockState(target).isAir) {
                activeDigOutTask = null
                nextDigOutAllowedTick = tick + SchematicDigOutRules.ACK_TIMEOUT_TICKS
                message("The local dig-out changed the block but no server air update arrived; its removal was not accepted as owned.", true)
                return false
            }
            if (task.attempts >= SchematicDigOutRules.MAX_ATTEMPTS) {
                activeDigOutTask = null
                nextDigOutAllowedTick = tick + SchematicDigOutRules.ACK_TIMEOUT_TICKS
                message("Trapped-player dig-out timed out after two bounded server-confirmation attempts.", true)
                return false
            }
            task.stage = SchematicDigOutStage.AIMING
            task.retryAt = tick + SchematicDigOutRules.RETRY_DELAY_TICKS
            task.face = null
            task.blockPacketBaseline = blockPacketOrdinal.get()
            return true
        }
        if (player.blockPosition() != target || world.getBlockState(target) != task.originalState ||
            !digOutSourceBlockStillEligible(world, target, task.originalState)) {
            if ((task.stage == SchematicDigOutStage.DESTROYING || task.stage == SchematicDigOutStage.WAITING_ACK) &&
                world.getBlockState(target).isAir) return true
            stopDigOutDestroy()
            if (task.stage == SchematicDigOutStage.DESTROYING || task.stage == SchematicDigOutStage.WAITING_ACK) {
                task.stage = SchematicDigOutStage.WAITING_ACK
                return true
            }
            activeDigOutTask = null
            nextDigOutAllowedTick = tick + SchematicDigOutRules.RETRY_DELAY_TICKS
            message("Trapped-player dig-out safety conditions changed; no retry was sent.", true)
            return false
        }
        if (tick < task.retryAt) return true
        if (task.stage == SchematicDigOutStage.AIMING && task.attempts >= SchematicDigOutRules.MAX_ATTEMPTS) {
            activeDigOutTask = null
            nextDigOutAllowedTick = tick + SchematicDigOutRules.ACK_TIMEOUT_TICKS
            message("No safe ray remained for the bounded trapped-player dig-out attempts.", true)
            return false
        }
        val aim = digOutAim(world, player, target)
        if (aim == null) {
            task.retryAt = tick + SchematicDigOutRules.RETRY_DELAY_TICKS
            if (task.attempts < SchematicDigOutRules.MAX_ATTEMPTS) task.attempts++
            return true
        }
        val desired = aim.first
        requestAim(desired)
        if (!SchematicNativePlacement.rotationAcknowledged(
                desired.yaw, desired.pitch,
                RotationManager.currentRotation?.yaw, RotationManager.currentRotation?.pitch,
                RotationManager.serverRotation.yaw, RotationManager.serverRotation.pitch,
            )) return true
        val hit = currentRotationHit(world, player, desired)
        if (hit == null || hit.type != HitResult.Type.BLOCK || hit.blockPos != target || hit.direction != aim.second) {
            task.retryAt = tick + SchematicDigOutRules.RETRY_DELAY_TICKS
            if (task.attempts < SchematicDigOutRules.MAX_ATTEMPTS) task.attempts++
            return true
        }
        if (task.stage == SchematicDigOutStage.AIMING) {
            val beforeState = world.getBlockState(target)
            if (beforeState != task.originalState || !digOutSourceBlockStillEligible(world, target, beforeState) ||
                !SchematicDigOutRules.safeRecoveryEligible(
                    withinInteractionReach = player.eyePosition.distanceToSqr(Vec3.atCenterOf(target)) <=
                        player.blockInteractionRange().toDouble().let { it * it },
                    nativeRayHitsTarget = true,
                    attempts = task.attempts,
                )) {
                activeDigOutTask = null
                nextDigOutAllowedTick = tick + SchematicDigOutRules.RETRY_DELAY_TICKS
                return false
            }
            val baseline = blockPacketOrdinal.get()
            val oldYaw = player.yRot
            val oldPitch = player.xRot
            val started = try {
                player.yRot = desired.yaw
                player.xRot = desired.pitch
                mc.gameMode?.startDestroyBlock(target, hit.direction) == true
            } finally {
                player.yRot = oldYaw
                player.xRot = oldPitch
            }
            if (!started) {
                task.attempts++
                task.retryAt = tick + SchematicDigOutRules.RETRY_DELAY_TICKS
                return true
            }
            task.blockPacketBaseline = baseline
            task.face = hit.direction
            task.stage = SchematicDigOutStage.DESTROYING
            task.attempts++
            task.sentAt = tick
            return true
        }
        val face = task.face ?: hit.direction
        if (hit.direction != face) {
            stopDigOutDestroy()
            task.stage = SchematicDigOutStage.AIMING
            task.retryAt = tick + SchematicDigOutRules.RETRY_DELAY_TICKS
            task.face = null
            return true
        }
        val oldYaw = player.yRot
        val oldPitch = player.xRot
        try {
            player.yRot = desired.yaw
            player.xRot = desired.pitch
            mc.gameMode?.continueDestroyBlock(target, face)
        } finally {
            player.yRot = oldYaw
            player.xRot = oldPitch
        }
        return true
    }

    private fun stopDigOutDestroy() {
        mc.gameMode?.stopDestroyBlock()
    }

    private fun scaffoldItemCount(stack: ItemStack): Int {
        val item = stack.item
        return if (item is BlockItem && (item.block is TransparentBlock || stack.`is`(ItemTags.LEAVES))) stack.count else 0
    }

    private fun digOutSourceBlockStillEligible(world: ClientLevel, target: BlockPos, state: BlockState): Boolean =
        SchematicDigOutRules.sourceBlockEligible(
            isAir = state.isAir,
            replaceable = state.canBeReplaced(),
            fullBlockCollision = state.isCollisionShapeFullBlock(world, target),
            isContainer = world.getBlockEntity(target) is Container,
            destroySpeed = state.getDestroySpeed(world, target),
            adjacentFluidsEmpty = Direction.values().all { world.getFluidState(target.relative(it)).isEmpty },
        )

    private fun digOutAim(world: ClientLevel, player: LocalPlayer, target: BlockPos): Pair<Rotation, Direction>? {
        if (player.eyePosition.distanceToSqr(Vec3.atCenterOf(target)) >
            player.blockInteractionRange().toDouble().let { it * it }) return null
        val desired = Rotation.lookingAt(Vec3.atCenterOf(target), player.eyePosition).normalize()
        val hit = currentRotationHit(world, player, desired) ?: return null
        if (hit.type != HitResult.Type.BLOCK || hit.blockPos != target) return null
        return desired to hit.direction
    }

    private fun hasStableDigOutFloor(world: ClientLevel, floor: BlockPos): Boolean {
        if (!world.hasChunkAt(floor) || !world.getFluidState(floor).isEmpty) return false
        val shape = world.getBlockState(floor).getCollisionShape(world, floor)
        return shape.toAabbs().any { box ->
            box.maxY >= 0.999 && box.minX < 0.8 && box.maxX > 0.2 && box.minZ < 0.8 && box.maxZ > 0.2
        }
    }

    private fun hasDigOutHeadroom(world: ClientLevel, feet: BlockPos): Boolean = (1..2).all { offset ->
        val pos = feet.above(offset)
        world.hasChunkAt(pos) && world.getFluidState(pos).isEmpty && world.getBlockState(pos).getCollisionShape(world, pos).isEmpty
    }

    private fun walkTowardStand(
        player: LocalPlayer,
        world: ClientLevel,
        target: BuildCellPos,
        expected: BlockState,
        stack: net.minecraft.world.item.ItemStack,
    ): Boolean {
        if (walkWithBaritone) {
            val trackedStand = BaritoneGoalBridge.trackedStand(this, target)
            val baritoneStand = trackedStand?.takeIf { stand ->
                val standEye = eyeAtFeet(world, player, stand) ?: return@takeIf false
                canStandAt(world, player, stand) && withinUseReach(world, player, stand, target.toBlockPos()) &&
                    SchematicNativePlacement.findCandidate(
                        world, player, target.toBlockPos(), expected, stack, standEye,
                        allowRelaxedOrientation = relaxedPass && target in relaxedCandidates,
                        allowProvisionalChest = SchematicStructureRules.provisionalChestPartnerTarget(
                            target, properties(expected), missingCells.keys,
                        ) != null,
                        allowPartialDoubleSlab = allowPartialDoubleSlab(expected, world.getBlockState(target.toBlockPos())),
                        coarseSearch = true,
                    ) != null
            } ?: findSafeBaritoneStand(player, world, target, expected, stack)
            if (baritoneStand != null) {
                val current = player.blockPosition().let { BuildCellPos(it.x, it.y, it.z) }
                if (current == baritoneStand) {
                    BaritoneGoalBridge.cancelOwned(this, target)
                    routeTarget = null
                    return true
                }
                if (BaritoneGoalBridge.walkTo(this, target, baritoneStand)) return true
                BaritoneGoalBridge.cancelOwned(this, target)
            } else {
                BaritoneGoalBridge.cancelOwned(this)
            }
        } else {
            BaritoneGoalBridge.cancelOwned(this)
        }
        if (routeTarget != target || route.isEmpty()) {
            val start = player.blockPosition().let { BuildCellPos(it.x, it.y, it.z) }
            var nativeStandChecks = 0
            val routeResult = SchematicStandPlanner.findRoute(
                start,
                isGoal = { candidate ->
                    if (++nativeStandChecks > 96 || !canStandAt(world, player, candidate)) return@findRoute false
                    val eye = eyeAtFeet(world, player, candidate) ?: return@findRoute false
                    val reach = player.blockInteractionRange().toDouble()
                    if (eye.distanceToSqr(Vec3.atCenterOf(target.toBlockPos())) > reach * reach) return@findRoute false
                    val expectedPartner = SchematicStructureRules.provisionalChestPartnerTarget(
                        target, properties(expected), missingCells.keys,
                    ) != null
                    SchematicNativePlacement.findCandidate(
                        world, player, target.toBlockPos(), expected, stack, eye,
                        allowRelaxedOrientation = relaxedPass && target in relaxedCandidates,
                        allowProvisionalChest = expectedPartner,
                        allowPartialDoubleSlab = allowPartialDoubleSlab(expected, world.getBlockState(target.toBlockPos())),
                        coarseSearch = true,
                    ) != null
                },
                canStand = { candidate -> canStandAt(world, player, candidate) },
                canTransition = { from, to ->
                    SchematicRouteRules.allows(from, to) && safeTransition(world, player, from, to)
                },
                visitBudget = ROUTE_BUDGET,
            )
            route = routeResult.route.orEmpty()
            routeIndex = 0
            routeTarget = target
            if (routeResult.route == null) {
                if (supportLeaves && startTargetSupportScaffold(player, world, target, expected)) return true
                if (climbWithLeaves && startPillarClimb(player, world, target)) return true
                if (climbWithLeaves && startSupportScaffold(player, world, target)) return true
                if (routeResult.visited <= 3 && tryStartDigOutTask(player, world, routeResult.visited)) return true
                if (climbWithLeaves || supportLeaves) {
                    message("No safe stand route to ${target.asText()}; the enabled support/climb scaffold paths found no safe acknowledged step.", true)
                }
                return false
            }
            if (route.isEmpty()) return false
        }
        if (routeIndex >= route.size) {
            route = emptyList()
            routeTarget = null
            return false
        }
        val next = route[routeIndex]
        val surfaceY = standSurfaceY(world, next) ?: return false
        val center = Vec3(next.x + 0.5, surfaceY, next.z + 0.5)
        val dx = player.x - center.x
        val dz = player.z - center.z
        if (dx * dx + dz * dz < 0.22 && kotlin.math.abs(player.y - surfaceY) < 0.15) {
            routeIndex++
            if (routeIndex >= route.size) {
                route = emptyList()
                routeTarget = null
                return true
            }
        }
        return routeIndex < route.size
    }

    private fun findSafeBaritoneStand(
        player: LocalPlayer,
        world: ClientLevel,
        target: BuildCellPos,
        expected: BlockState,
        stack: ItemStack,
    ): BuildCellPos? {
        val candidates = buildList {
            for (dy in -3..1) for (dx in -3..3) for (dz in -3..3) {
                if (kotlin.math.abs(dx) + kotlin.math.abs(dz) > 4) continue
                add(target.offset(dx, dy, dz))
            }
        }.sortedBy { candidate ->
            distanceSquared(player.blockPosition().let { BuildCellPos(it.x, it.y, it.z) }, candidate)
        }
        var checks = 0
        for (candidate in candidates) {
            if (++checks > 96) break
            if (!canStandAt(world, player, candidate) || !withinUseReach(world, player, candidate, target.toBlockPos())) continue
            val eye = eyeAtFeet(world, player, candidate) ?: continue
            if (SchematicNativePlacement.findCandidate(
                    world, player, target.toBlockPos(), expected, stack, eye,
                    allowRelaxedOrientation = relaxedPass && target in relaxedCandidates,
                    allowProvisionalChest = SchematicStructureRules.provisionalChestPartnerTarget(
                        target, properties(expected), missingCells.keys,
                    ) != null,
                    allowPartialDoubleSlab = allowPartialDoubleSlab(expected, world.getBlockState(target.toBlockPos())),
                    coarseSearch = true,
                ) != null) return candidate
        }
        return null
    }

    private data class ScaffoldItemChoice(
        val slot: Int,
        val stack: ItemStack,
        val item: Item,
        val itemId: String,
        val state: BlockState,
        val rule: SchematicScaffoldRules.Candidate,
    )

    private fun scaffoldItemChoices(player: LocalPlayer): List<ScaffoldItemChoice> =
        (0..35).mapNotNull { slot ->
            val stack = player.inventory.getItem(slot)
            val blockItem = stack.item as? BlockItem ?: return@mapNotNull null
            val block = blockItem.block
            val state = block.defaultBlockState()
            val itemId = BuiltInRegistries.ITEM.getKey(stack.item).toString()
            val candidate = SchematicScaffoldRules.Candidate(
                itemId = itemId,
                transparentBlock = block is TransparentBlock,
                leafItem = stack.`is`(ItemTags.LEAVES),
                slot = slot,
                count = stack.count,
                hasBlockState = stack.get(DataComponents.BLOCK_STATE) != null,
                hasBlockEntityData = stack.get(DataComponents.BLOCK_ENTITY_DATA) != null,
            )
            if (!SchematicScaffoldRules.eligible(candidate) || state.isAir) return@mapNotNull null
            ScaffoldItemChoice(slot, stack, stack.item, itemId, state, candidate)
        }.sortedWith(compareBy<ScaffoldItemChoice>({ SchematicScaffoldRules.priority(it.rule) ?: Int.MAX_VALUE }, { it.slot >= 9 }, { it.slot }, { it.itemId }))

    /** Adds a safe adjacent support when no native block-item context can place the target yet. */
    private fun startTargetSupportScaffold(
        player: LocalPlayer,
        world: ClientLevel,
        target: BuildCellPos,
        expected: BlockState,
    ): Boolean {
        if (activeScaffoldTask != null || pendingSwapLease != null || mc.gui.screen() != null ||
            player.containerMenu !== player.inventoryMenu || !player.containerMenu.carried.isEmpty) return false
        val targetPos = target.toBlockPos()
        if (!world.hasChunkAt(targetPos) || expected.block == Blocks.WATER || expected.block == Blocks.LAVA) return false
        val preferred = attachmentSupport(target, expected).toBlockPos()
        val candidates = (listOf(preferred) + listOf(targetPos.below(), targetPos.north(), targetPos.south(), targetPos.east(), targetPos.west(), targetPos.above()))
            .distinct().filter { scaffoldCellAllowed(world, player, it) }
        if (candidates.isEmpty()) return false
        val choices = scaffoldItemChoices(player)
        if (choices.isEmpty()) return buyFromAh && supportLeaves && startScaffoldPurchase(player)
        for ((index, dependency) in candidates.withIndex()) {
            for (choice in choices) {
                val place = SchematicNativePlacement.findCandidate(world, player, dependency, choice.state, choice.stack) ?: continue
                if (choice.slot >= 9) {
                    if (beginInventorySwap(player, world, choice.slot, choice.item)) return true
                    continue
                }
                player.inventory.selectedSlot = choice.slot
                val menuSlot = 36 + choice.slot
                val before = InventorySlotValue.fromStack(player.inventoryMenu.getSlot(menuSlot).item)
                if (before.itemId != choice.itemId || before.count <= 0) continue
                activeScaffoldTask = newScaffoldTask(
                    player, world, target, null, SchematicScaffoldPurpose.TARGET_SUPPORT,
                    candidates.drop(index), place.predictedState, choice.item, menuSlot, before,
                ) ?: return false
                requestAim(place.rotation)
                message("Placing a source-approved ${choice.itemId} support face for ${target.asText()}.", false)
                return true
            }
        }
        return false
    }

    private fun nativeTargetPlacementAvailable(player: LocalPlayer, world: ClientLevel, target: BuildCellPos): Boolean {
        val expected = expectedStates[target] ?: return false
        val targetItem = resolveItem(buildCell(target, expected).itemId) ?: return false
        if (targetItem !is BlockItem) return false
        return SchematicNativePlacement.findCandidate(
            world, player, target.toBlockPos(), expected, ItemStack(targetItem),
            allowRelaxedOrientation = relaxedPass && target in relaxedCandidates,
            allowProvisionalChest = SchematicStructureRules.provisionalChestPartnerTarget(
                target, properties(expected), missingCells.keys,
            ) != null,
            allowPartialDoubleSlab = allowPartialDoubleSlab(expected, world.getBlockState(target.toBlockPos())),
        ) != null
    }

    /** Plans one clear, projected-air foothold that advances the current stand toward the target. */
    private fun startSupportScaffold(player: LocalPlayer, world: ClientLevel, target: BuildCellPos): Boolean {
        if (activeScaffoldTask != null || pendingSwapLease != null || mc.gui.screen() != null) return false
        if ((scaffoldStepsByTarget[target] ?: 0) >= MAX_SCAFFOLD_STEPS_PER_TARGET) return false
        if (player.containerMenu !== player.inventoryMenu || !player.containerMenu.carried.isEmpty) return false

        val choices = scaffoldItemChoices(player)
        if (choices.isEmpty()) {
            if (buyFromAh && (supportLeaves || climbWithLeaves) && startScaffoldPurchase(player)) return true
            return false
        }

        val start = player.blockPosition().let { BuildCellPos(it.x, it.y, it.z) }
        val directions = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        val stands = buildList {
            for ((dx, dz) in directions) for (dy in -1..1) {
                val stand = start.offset(dx, dy, dz)
                if (!SchematicScaffoldRules.walkwayStepAdvances(start, stand, target)) continue
                add(stand)
            }
        }.sortedWith(compareBy<BuildCellPos>({ distanceSquared(it, target) }, { it.y }, { it.x }, { it.z }))

        for (stand in stands) {
            val support = stand.toBlockPos().below()
            if (!world.hasChunkAt(support) || !world.hasChunkAt(stand.toBlockPos()) || !world.hasChunkAt(stand.toBlockPos().above())) continue
            val current = world.getBlockState(support)
            val body = net.minecraft.world.phys.AABB(
                stand.x + 0.12, stand.y.toDouble(), stand.z + 0.12,
                stand.x + 0.88, stand.y + 1.8, stand.z + 0.88,
            )
            val bodyClear = world.getBlockState(stand.toBlockPos()).getCollisionShape(world, stand.toBlockPos()).isEmpty &&
                world.getBlockState(stand.toBlockPos().above()).getCollisionShape(world, stand.toBlockPos().above()).isEmpty &&
                !world.getFluidState(stand.toBlockPos()).isSource && !world.getFluidState(stand.toBlockPos().above()).isSource &&
                world.noCollision(player, body)
            if (!scaffoldCellAllowed(world, player, support) || !bodyClear) continue

            for (choice in choices) {
                var sequence = listOf(support)
                var place = SchematicNativePlacement.findCandidate(world, player, support, choice.state, choice.stack)
                if (place == null && stand.y > start.y) {
                    val plannedChain = SchematicScaffoldRules.verticalRiseSupportChain(stand)
                    val lower = plannedChain.first().toBlockPos()
                    if (scaffoldCellAllowed(world, player, lower)) {
                        val lowerPlacement = SchematicNativePlacement.findCandidate(world, player, lower, choice.state, choice.stack)
                        if (lowerPlacement != null) {
                            sequence = plannedChain.map { it.toBlockPos() }
                            place = lowerPlacement
                        }
                    }
                }
                if (place == null) continue
                if (choice.slot >= 9) {
                    if (beginInventorySwap(player, world, choice.slot, choice.item)) return true
                    continue
                }
                player.inventory.selectedSlot = choice.slot
                val menuSlot = 36 + choice.slot
                val before = InventorySlotValue.fromStack(player.inventoryMenu.getSlot(menuSlot).item)
                if (before.itemId != choice.itemId || before.count <= 0) continue
                activeScaffoldTask = newScaffoldTask(
                    player, world, target, stand, SchematicScaffoldPurpose.WALKWAY,
                    sequence, place.predictedState, choice.item, menuSlot, before,
                ) ?: return false
                requestAim(place.rotation)
                message("Placing one acknowledged ${choice.itemId} foothold toward ${target.asText()}.", false)
                return true
            }
        }
        return false
    }

    /** Starts the source-style jump/place-underfoot climb on a safe full-height floor cell. */
    private fun startPillarClimb(player: LocalPlayer, world: ClientLevel, target: BuildCellPos): Boolean {
        if (activePillarTask != null) return true
        val baseY = kotlin.math.floor(player.y + 0.01).toInt()
        val horizontalDistance = kotlin.math.abs(target.x - player.blockPosition().x) +
            kotlin.math.abs(target.z - player.blockPosition().z)
        if (!SchematicPillarRules.canStart(
                player.onGround(), target.y > baseY, horizontalDistance, MAX_PILLAR_HORIZONTAL_RANGE,
                pillarRunsByTarget[target] ?: 0, MAX_PILLAR_RUNS_PER_TARGET,
            ) || mc.gui.screen() != null || player.containerMenu !== player.inventoryMenu ||
            !player.containerMenu.carried.isEmpty || hasManualPillarMovement()) return false
        val columnX = kotlin.math.floor(player.x).toInt()
        val columnZ = kotlin.math.floor(player.z).toInt()
        val base = BlockPos(columnX, baseY, columnZ)
        val floor = base.below()
        if (!world.hasChunkAt(base) || !world.hasChunkAt(floor)) return false
        val floorShape = world.getBlockState(floor).getCollisionShape(world, floor)
        if (floorShape.isEmpty || !SchematicPillarRules.fullHeightFloor(floorShape.max(Direction.Axis.Y))) return false
        val heightLimit = SchematicPillarRules.riseCount(target.y - baseY, Int.MAX_VALUE, PILLAR_RISES_PER_RUN)
        if (heightLimit <= 0) return false
        for (dy in 0..heightLimit + 2) {
            val cell = base.above(dy)
            if (!world.hasChunkAt(cell) || !world.getBlockState(cell).getCollisionShape(world, cell).isEmpty ||
                !world.getFluidState(cell).isEmpty || !projectedCellIsAirOrOutside(cell) || scaffoldObserverSensitive(world, cell)) {
                return false
            }
        }
        val choices = scaffoldItemChoices(player)
        if (choices.isEmpty()) return buyFromAh && climbWithLeaves && startScaffoldPurchase(player)
        for (choice in choices) {
            if (choice.slot >= 9) {
                if (beginInventorySwap(player, world, choice.slot, choice.item)) return true
                continue
            }
            val height = SchematicPillarRules.riseCount(heightLimit, choice.stack.count, PILLAR_RISES_PER_RUN)
            if (height <= 0) continue
            player.inventory.selectedSlot = choice.slot
            val menuSlot = 36 + choice.slot
            val before = InventorySlotValue.fromStack(player.inventoryMenu.getSlot(menuSlot).item)
            if (before.itemId != choice.itemId || before.count < height) continue
            activePillarTask = SchematicPillarTask(
                target = target,
                x = columnX,
                z = columnZ,
                baseY = baseY,
                height = height,
                item = choice.item,
                itemId = choice.itemId,
                menuId = player.inventoryMenu.containerId,
                menuSlot = menuSlot,
                before = before,
                baselineStateId = player.inventoryMenu.stateId,
                connectionId = System.identityHashCode(activeConnection ?: return false).toLong(),
                worldId = System.identityHashCode(world).toLong(),
                playerId = System.identityHashCode(player).toLong(),
                generation = runGeneration,
                stageStartedAt = tick,
            )
            requestAim(pillarAim(player, columnX, columnZ))
            message("Starting a bounded ${height}-block underfoot scaffold climb toward ${target.asText()}.", false)
            return true
        }
        return false
    }

    private fun hasManualPillarMovement(): Boolean =
        mc.options.keyUp.isDown || mc.options.keyDown.isDown || mc.options.keyLeft.isDown ||
            mc.options.keyRight.isDown || mc.options.keyJump.isDown

    private fun pillarAim(player: LocalPlayer, x: Int, z: Int): Rotation {
        val eye = player.eyePosition
        val horizontal = Rotation.lookingAt(Vec3(x + 0.5, eye.y, z + 0.5), eye)
        return Rotation(horizontal.yaw, 90f)
    }

    /** Owns only movement-event inputs; each block is recorded after both server proofs arrive. */
    private fun servicePillarClimb(
        player: LocalPlayer,
        world: ClientLevel,
        connection: ClientPacketListener,
    ): Boolean {
        val task = activePillarTask ?: return false
        if (task.generation != runGeneration || task.connectionId != System.identityHashCode(connection).toLong() ||
            task.worldId != System.identityHashCode(world).toLong() || task.playerId != System.identityHashCode(player).toLong()) {
            activePillarTask = null
            return true
        }
        if (mc.gui.screen() != null || player.containerMenu !== player.inventoryMenu) return true
        if (hasManualPillarMovement() && task.stage != SchematicPillarStage.WAITING_FOR_BLOCK_PROOF) {
            activePillarTask = null
            releaseSneakLease()
            message("Manual movement interrupted the climb; acknowledged scaffold blocks were retained.", true)
            return true
        }
        if (player.inventory.selectedSlot != task.menuSlot - 36) {
            activePillarTask = null
            message("The selected hotbar slot changed during a climb; retaining acknowledged scaffolds.", true)
            return true
        }
        val cell = BlockPos(task.x, task.baseY + task.step, task.z)
        when (task.stage) {
            SchematicPillarStage.CENTER -> {
                if (hasManualPillarMovement()) return true
                requestAim(pillarAim(player, task.x, task.z))
                val centered = SchematicPillarRules.centered(
                    player.x - (task.x + 0.5), player.z - (task.z + 0.5), PILLAR_CENTER_TOLERANCE,
                )
                val motion = player.deltaMovement
                if (centered && player.onGround() && kotlin.math.floor(player.y + 0.01).toInt() == task.baseY + task.step &&
                    motion.x * motion.x + motion.z * motion.z < PILLAR_MOTION_SQUARED_LIMIT &&
                    SchematicNativePlacement.rotationAcknowledged(
                        pillarAim(player, task.x, task.z).yaw, 90f,
                        RotationManager.currentRotation?.yaw, RotationManager.currentRotation?.pitch,
                        RotationManager.serverRotation.yaw, RotationManager.serverRotation.pitch,
                    )) {
                    task.stage = SchematicPillarStage.JUMP
                    task.stageStartedAt = tick
                    task.attempts++
                } else if (tick - task.stageStartedAt > PILLAR_PHASE_TIMEOUT_TICKS) {
                    failPillarStep(task, "could not center and settle over the scaffold column")
                }
                return true
            }
            SchematicPillarStage.JUMP -> {
                requestAim(pillarAim(player, task.x, task.z))
                if (!player.onGround()) {
                    task.stage = SchematicPillarStage.RISING
                    task.stageStartedAt = tick
                } else if (tick - task.stageStartedAt > PILLAR_JUMP_START_TICKS) {
                    failPillarStep(task, "jump input did not leave the ground")
                }
                return true
            }
            SchematicPillarStage.RISING -> {
                requestAim(pillarAim(player, task.x, task.z))
                if (SchematicPillarRules.reachedPlacementWindow(player.y, cell.y, PILLAR_PLACEMENT_CLEARANCE)) {
                    if (!world.hasChunkAt(cell) || !world.getBlockState(cell).canBeReplaced() ||
                        !world.getFluidState(cell).isEmpty || !projectedCellIsAirOrOutside(cell) || scaffoldObserverSensitive(world, cell)) {
                        activePillarTask = null
                        message("The climb cell is no longer safe or outside the schematic; no block was placed.", true)
                        return true
                    }
                    val held = player.mainHandItem
                    if (held.isEmpty || held.item != task.item || held.get(DataComponents.BLOCK_STATE) != null ||
                        held.get(DataComponents.BLOCK_ENTITY_DATA) != null) {
                        activePillarTask = null
                        message("The held scaffold item changed during the jump; no block was placed.", true)
                        return true
                    }
                    val current = InventorySlotValue.fromStack(player.inventoryMenu.getSlot(task.menuSlot).item)
                    if (current != task.before) {
                        activePillarTask = null
                        message("Scaffold inventory changed during the jump; no block was placed.", true)
                        return true
                    }
                    val scaffoldBlock = (task.item as? BlockItem)?.block ?: run {
                        activePillarTask = null
                        message("The selected climb material is no longer a block item.", true)
                        return true
                    }
                    val candidate = SchematicNativePlacement.findCandidate(world, player, cell, scaffoldBlock.defaultBlockState(), held)
                    if (candidate != null) {
                        task.candidate = candidate
                        task.expectedState = candidate.predictedState
                        task.stage = SchematicPillarStage.AIMING_PLACEMENT
                        task.stageStartedAt = tick
                    } else if (player.onGround() || tick - task.stageStartedAt > PILLAR_PHASE_TIMEOUT_TICKS) {
                        failPillarStep(task, "no native underfoot placement context became legal during the jump")
                    }
                } else if (player.onGround() || tick - task.stageStartedAt > PILLAR_PHASE_TIMEOUT_TICKS) {
                    failPillarStep(task, "jump did not reach the bounded underfoot placement height")
                }
                return true
            }
            SchematicPillarStage.AIMING_PLACEMENT -> {
                val candidate = task.candidate ?: run {
                    failPillarStep(task, "the native placement candidate was lost")
                    return true
                }
                requestAim(candidate.rotation)
                if (!SchematicNativePlacement.rotationAcknowledged(
                        candidate.rotation.yaw, candidate.rotation.pitch,
                        RotationManager.currentRotation?.yaw, RotationManager.currentRotation?.pitch,
                        RotationManager.serverRotation.yaw, RotationManager.serverRotation.pitch,
                    )) {
                    if (tick - task.stageStartedAt > PILLAR_PHASE_TIMEOUT_TICKS) failPillarStep(task, "placement aim did not settle in the jump window")
                    return true
                }
                val hit = currentRotationHit(world, player, candidate.rotation)
                if (hit == null || hit.blockPos != candidate.support || hit.direction != candidate.hit.direction ||
                    hit.location.distanceToSqr(candidate.hit.location) > 0.0004) {
                    if (tick - task.stageStartedAt > PILLAR_PHASE_TIMEOUT_TICKS) failPillarStep(task, "the jump window lost the planned support ray")
                    return true
                }
                if (!ensureSneakReady(task.target, hit.blockPos, player, world)) {
                    if (tick - task.stageStartedAt > PILLAR_PHASE_TIMEOUT_TICKS) failPillarStep(task, "sneak did not settle before the underfoot use")
                    return true
                }
                if (tickDelay > 0 && tick % (tickDelay + 1L) != 0L) {
                    if (tick - task.stageStartedAt > PILLAR_PHASE_TIMEOUT_TICKS) failPillarStep(task, "tick delay exhausted the jump window")
                    return true
                }
                val held = player.mainHandItem
                if (held.isEmpty || held.item != task.item ||
                    InventorySlotValue.fromStack(player.inventoryMenu.getSlot(task.menuSlot).item) != task.before) {
                    activePillarTask = null
                    message("The scaffold stack changed before underfoot use; no block was sent.", true)
                    return true
                }
                val oldYaw = player.yRot
                val oldPitch = player.xRot
                task.baselineStateId = player.inventoryMenu.stateId
                task.blockPacketBaseline = blockPacketOrdinal.get()
                val result = try {
                    player.yRot = candidate.rotation.yaw
                    player.xRot = candidate.rotation.pitch
                    mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit)
                } finally {
                    player.yRot = oldYaw
                    player.xRot = oldPitch
                }
                if (result?.consumesAction() == true) {
                    task.sentAt = tick.coerceAtLeast(1L)
                    task.stage = SchematicPillarStage.WAITING_FOR_BLOCK_PROOF
                    task.stageStartedAt = tick
                    return true
                }
                failPillarStep(task, "underfoot use did not consume a placement action")
                return true
            }
            SchematicPillarStage.WAITING_FOR_BLOCK_PROOF -> {
                if (task.blockRejected) {
                    activePillarTask = null
                    message("The server did not place the expected underfoot scaffold state; prior owned blocks were retained.", true)
                    return true
                }
                if (SchematicPillarRules.placementProven(task.blockAcknowledged, task.inventoryAcknowledged)) {
                    val state = task.acknowledgedState ?: run {
                        activePillarTask = null
                        return true
                    }
                    ownedScaffolds[cell.asLong()] = OwnedSchematicScaffold(cell.immutable(), state, task.target)
                    task.before = InventorySlotValue.fromStack(player.inventoryMenu.getSlot(task.menuSlot).item)
                    task.expectedState = null
                    task.acknowledgedState = state
                    task.candidate = null
                    task.blockAcknowledged = false
                    task.blockRejected = false
                    task.inventoryAcknowledged = false
                    task.baselineStateId = player.inventoryMenu.stateId
                    task.stage = SchematicPillarStage.WAITING_FOR_STAND
                    task.stageStartedAt = tick
                    task.blockPacketBaseline = 0L
                    return true
                }
                if (tick - task.sentAt >= SCAFFOLD_PROOF_TIMEOUT_TICKS) {
                    activePillarTask = null
                    message("The underfoot use was uncertain; it was not repeated and no unacknowledged block was claimed.", true)
                }
                return true
            }
            SchematicPillarStage.WAITING_FOR_STAND -> {
                if (!world.hasChunkAt(cell) || task.acknowledgedState == null ||
                    !sameScaffoldState(task.acknowledgedState!!, world.getBlockState(cell))) {
                    activePillarTask = null
                    message("An acknowledged pillar block changed before the player stood on it; it was retained.", true)
                    return true
                }
                val centered = kotlin.math.abs(player.x - (task.x + 0.5)) < 0.5 && kotlin.math.abs(player.z - (task.z + 0.5)) < 0.5
                if (player.onGround() && player.y >= cell.y + 0.99 && centered) {
                    task.step++
                    if (task.step >= task.height) {
                        pillarRunsByTarget[task.target] = (pillarRunsByTarget[task.target] ?: 0) + 1
                        releaseSneakLease()
                        activePillarTask = null
                        route = emptyList()
                        routeIndex = 0
                        routeTarget = null
                        message("The acknowledged underfoot climb reached ${task.height} block(s); replanning from the new stand.", false)
                    } else {
                        task.stage = SchematicPillarStage.CENTER
                        task.stageStartedAt = tick
                        task.attempts = 0
                        task.sentAt = 0L
                        task.expectedState = null
                        task.acknowledgedState = null
                        task.blockAcknowledged = false
                        task.blockRejected = false
                        task.inventoryAcknowledged = false
                        task.blockPacketBaseline = 0L
                    }
                } else if (tick - task.stageStartedAt > PILLAR_PHASE_TIMEOUT_TICKS) {
                    activePillarTask = null
                    ownedScaffolds.remove(cell.asLong())
                    retainedScaffolds += cell.asLong()
                    message("The player did not reach the server-confirmed pillar stand; the block was retained.", true)
                }
                return true
            }
        }
    }

    private fun failPillarStep(task: SchematicPillarTask, reason: String) {
        if (!SchematicPillarRules.mayRetryUnsent(task.attempts, PILLAR_MAX_TRIES)) {
            activePillarTask = null
            pillarRunsByTarget[task.target] = (pillarRunsByTarget[task.target] ?: 0) + 1
            releaseSneakLease()
            message("Stopped the climb after ${PILLAR_MAX_TRIES} bounded attempts: $reason. Any server-confirmed scaffold was retained.", true)
            return
        }
        task.stage = SchematicPillarStage.CENTER
        task.stageStartedAt = tick
        task.candidate = null
        task.expectedState = null
        task.sentAt = 0L
        releaseSneakLease()
        message("Retrying a bounded climb phase ($reason).", true)
    }

    private fun observePillarInventory(update: InventorySyncFrame, connectionId: Long, player: LocalPlayer) {
        val task = activePillarTask ?: return
        if (task.stage != SchematicPillarStage.WAITING_FOR_BLOCK_PROOF || task.sentAt == 0L ||
            update.connectionId != connectionId || update.menuId != task.menuId || task.inventoryAcknowledged ||
            !SchematicRefillRules.serverUpdateAfter(task.baselineStateId, update.stateId)) return
        val current = update.slots[task.menuSlot] ?: return
        val local = InventorySlotValue.fromStack(player.inventoryMenu.getSlot(task.menuSlot).item)
        if (local == current && SchematicScaffoldRules.inventoryDecrementAcknowledged(task.before, current)) {
            task.inventoryAcknowledged = true
        }
    }

    private fun newScaffoldTask(
        player: LocalPlayer,
        world: ClientLevel,
        target: BuildCellPos,
        stand: BuildCellPos?,
        purpose: SchematicScaffoldPurpose,
        supportSequence: List<BlockPos>,
        state: BlockState,
        item: Item,
        menuSlot: Int,
        before: InventorySlotValue,
    ): SchematicScaffoldTask? {
        val connection = activeConnection ?: return null
        return SchematicScaffoldTask(
            target = target,
            stand = stand,
            purpose = purpose,
            supportSequence = supportSequence.map(BlockPos::immutable),
            sequenceIndex = 0,
            support = supportSequence.firstOrNull()?.immutable() ?: return null,
            state = state,
            item = item,
            menuId = player.inventoryMenu.containerId,
            menuSlot = menuSlot,
            before = before,
            baselineStateId = player.inventoryMenu.stateId,
            connectionId = System.identityHashCode(connection).toLong(),
            worldId = System.identityHashCode(world).toLong(),
            playerId = System.identityHashCode(player).toLong(),
            generation = runGeneration,
            stageStartedAt = tick,
        )
    }

    private fun distanceSquared(first: BuildCellPos, second: BuildCellPos): Long {
        val dx = first.x.toLong() - second.x
        val dy = first.y.toLong() - second.y
        val dz = first.z.toLong() - second.z
        return dx * dx + dy * dy + dz * dz
    }

    private fun scaffoldCellAllowed(world: ClientLevel, player: LocalPlayer, pos: BlockPos): Boolean {
        if (!world.hasChunkAt(pos)) return false
        val state = world.getBlockState(pos)
        return SchematicScaffoldRules.cellCanBeScaffolded(
            replaceable = state.canBeReplaced(),
            projectedAir = projectedCellIsAirOrOutside(pos),
            emptyFluid = world.getFluidState(pos).isEmpty,
            intersectsPlayer = player.boundingBox.intersects(
                net.minecraft.world.phys.AABB.ofSize(Vec3.atCenterOf(pos), 0.98, 0.98, 0.98),
            ),
            observerSensitive = scaffoldObserverSensitive(world, pos),
        )
    }

    private fun projectedCellIsAirOrOutside(pos: BlockPos): Boolean {
        val currentFrame = frame ?: return false
        if (currentFrame.enabledRegions.none { it.contains(pos) }) return true
        val projected = LitematicaRuntimeAdapter.expectedStateAt(currentFrame, pos) ?: return false
        return projected.isAir
    }

    private fun scaffoldObserverSensitive(world: ClientLevel, pos: BlockPos): Boolean {
        val currentFrame = frame ?: return true
        for (candidate in sequenceOf(pos) + Direction.values().asSequence().map(pos::relative)) {
            if (world.hasChunkAt(candidate) && world.getBlockState(candidate).block == Blocks.OBSERVER) return true
            if (currentFrame.enabledRegions.any { it.contains(candidate) }) {
                val projected = LitematicaRuntimeAdapter.expectedStateAt(currentFrame, candidate) ?: return true
                if (projected.block == Blocks.OBSERVER) return true
            }
        }
        return false
    }

    /** Dispatches exactly one scaffold placement and waits for both world and inventory proof. */
    private fun serviceScaffoldTask(player: LocalPlayer, world: ClientLevel, connection: ClientPacketListener): Boolean {
        val task = activeScaffoldTask ?: return false
        if (task.generation != runGeneration || task.worldId != System.identityHashCode(world).toLong() ||
            task.playerId != System.identityHashCode(player).toLong() || task.connectionId != System.identityHashCode(connection).toLong()) {
            activeScaffoldTask = null
            return true
        }
        if (task.stage == SchematicScaffoldStage.WAITING_FOR_PROOF && task.sentAt == 0L) {
            if (tick - task.stageStartedAt >= SCAFFOLD_PROOF_TIMEOUT_TICKS) {
                activeScaffoldTask = null
                recordNoActionProgress(task.target, "the scaffold placement aim did not settle")
                return true
            }
            if (mc.gui.screen() != null || player.containerMenu !== player.inventoryMenu || player.inventory.selectedSlot != task.menuSlot - 36) return true
            val held = player.mainHandItem
            if (held.isEmpty || held.item != task.item || held.get(DataComponents.BLOCK_STATE) != null ||
                held.get(DataComponents.BLOCK_ENTITY_DATA) != null || !projectedCellIsAirOrOutside(task.support)) {
                activeScaffoldTask = null
                return true
            }
            val currentMenuStack = InventorySlotValue.fromStack(player.inventoryMenu.getSlot(task.menuSlot).item)
            if (currentMenuStack != task.before) {
                activeScaffoldTask = null
                return true
            }
            val supportState = world.getBlockState(task.support)
            if (!supportState.canBeReplaced() || !world.getFluidState(task.support).isEmpty) {
                activeScaffoldTask = null
                return true
            }
            val place = SchematicNativePlacement.findCandidate(world, player, task.support, task.state, held)
            if (place == null) {
                activeScaffoldTask = null
                recordNoActionProgress(task.target, "the planned scaffold foothold is no longer natively placeable")
                return true
            }
            if (!sameScaffoldState(task.state, place.predictedState)) {
                activeScaffoldTask = null
                recordNoActionProgress(task.target, "the scaffold's native placement state changed before use")
                return true
            }
            val desired = place.rotation
            requestAim(desired)
            if (!SchematicNativePlacement.rotationAcknowledged(
                    desired.yaw, desired.pitch,
                    RotationManager.currentRotation?.yaw, RotationManager.currentRotation?.pitch,
                    RotationManager.serverRotation.yaw, RotationManager.serverRotation.pitch,
                )) return true
            val hit = currentRotationHit(world, player, desired)
            if (hit == null || hit.type != HitResult.Type.BLOCK || hit.blockPos != place.support ||
                hit.direction != place.hit.direction || hit.location.distanceToSqr(place.hit.location) > 0.0004) return true
            if (!ensureSneakReady(task.target, hit.blockPos, player, world)) return true
            if (tickDelay > 0 && tick % (tickDelay + 1L) != 0L) return true
            val oldYaw = player.yRot
            val oldPitch = player.xRot
            task.baselineStateId = player.inventoryMenu.stateId
            val result = try {
                player.yRot = desired.yaw
                player.xRot = desired.pitch
                mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit)
            } finally {
                player.yRot = oldYaw
                player.xRot = oldPitch
            }
            if (result?.consumesAction() == true) {
                task.sentAt = tick.coerceAtLeast(1L)
                nextPlacementTick = tick + tickDelay + 1
                placementSentAt = tick
                return true
            }
            activeScaffoldTask = null
            recordNoActionProgress(task.target, "scaffold use did not consume an action")
            return true
        }
        if (task.stage == SchematicScaffoldStage.WAITING_FOR_PROOF) {
            if (task.blockRejected) {
                activeScaffoldTask = null
                recordNoActionProgress(task.target, "the server rejected the scaffold block state")
                return true
            }
            if (task.blockAcknowledged && task.inventoryAcknowledged) {
                val acknowledged = task.acknowledgedState
                if (acknowledged == null) {
                    activeScaffoldTask = null
                    recordNoActionProgress(task.target, "the scaffold update did not contain a usable server state")
                    return true
                }
                ownedScaffolds[task.support.asLong()] = OwnedSchematicScaffold(task.support.immutable(), acknowledged, task.target)
                if (task.purpose == SchematicScaffoldPurpose.TARGET_SUPPORT && nativeTargetPlacementAvailable(player, world, task.target)) {
                    task.stage = SchematicScaffoldStage.READY_TO_WALK
                    return true
                }
                val nextIndex = task.sequenceIndex + 1
                if (nextIndex < task.supportSequence.size) {
                    val nextSupport = task.supportSequence[nextIndex]
                    val nextBefore = InventorySlotValue.fromStack(player.inventoryMenu.getSlot(task.menuSlot).item)
                    if (nextBefore.itemId != BuiltInRegistries.ITEM.getKey(task.item).toString() || nextBefore.count <= 0) {
                        activeScaffoldTask = null
                        recordNoActionProgress(task.target, "the acknowledged scaffold chain exhausted its selected material")
                        return true
                    }
                    task.sequenceIndex = nextIndex
                    task.support = nextSupport.immutable()
                    task.state = acknowledged
                    task.before = nextBefore
                    task.baselineStateId = player.inventoryMenu.stateId
                    task.stageStartedAt = tick
                    task.stage = SchematicScaffoldStage.WAITING_FOR_PROOF
                    task.blockAcknowledged = false
                    task.blockRejected = false
                    task.acknowledgedState = null
                    task.inventoryAcknowledged = false
                    task.sentAt = 0L
                    return true
                }
                task.stage = SchematicScaffoldStage.READY_TO_WALK
            } else if (tick - task.sentAt >= SCAFFOLD_PROOF_TIMEOUT_TICKS) {
                activeScaffoldTask = null
                recordNoActionProgress(task.target, "server block and inventory acknowledgments did not both arrive; the foothold was left unowned")
                return true
            }
            return true
        }
        if (task.stage == SchematicScaffoldStage.READY_TO_WALK) {
            val acknowledged = task.acknowledgedState
            if (!world.hasChunkAt(task.support) || acknowledged == null || world.getBlockState(task.support) != acknowledged) {
                ownedScaffolds.remove(task.support.asLong())
                activeScaffoldTask = null
                recordNoActionProgress(task.target, "the acknowledged foothold changed before it could be used")
                return true
            }
            if (task.purpose == SchematicScaffoldPurpose.TARGET_SUPPORT) {
                if (nativeTargetPlacementAvailable(player, world, task.target)) {
                    releaseSneakLease()
                    activeScaffoldTask = null
                    return false
                }
                activeScaffoldTask = null
                recordNoActionProgress(task.target, "the acknowledged support did not satisfy the target block's native survival rule")
                return true
            }
            val stand = task.stand
            if (stand != null && canStandAt(world, player, stand) && SchematicRouteRules.allows(
                    player.blockPosition().let { BuildCellPos(it.x, it.y, it.z) }, stand,
                )) {
                releaseSneakLease()
                route = listOf(stand)
                routeIndex = 0
                routeTarget = task.target
                scaffoldStepsByTarget[task.target] = (scaffoldStepsByTarget[task.target] ?: 0) + 1
                activeScaffoldTask = null
                return false
            }
            if (tick - task.sentAt >= SCAFFOLD_PROOF_TIMEOUT_TICKS) {
                activeScaffoldTask = null
                recordNoActionProgress(task.target, "the acknowledged scaffold did not form a safe next stand")
            }
            return true
        }
        return true
    }

    private fun observeScaffoldInventory(update: InventorySyncFrame, connectionId: Long, player: LocalPlayer) {
        val task = activeScaffoldTask ?: return
        if (task.stage != SchematicScaffoldStage.WAITING_FOR_PROOF || task.sentAt == 0L ||
            update.connectionId != connectionId || update.menuId != task.menuId || task.inventoryAcknowledged ||
            !SchematicRefillRules.serverUpdateAfter(task.baselineStateId, update.stateId)) return
        val current = update.slots[task.menuSlot] ?: return
        val local = InventorySlotValue.fromStack(player.inventoryMenu.getSlot(task.menuSlot).item)
        if (local == current && SchematicScaffoldRules.inventoryDecrementAcknowledged(task.before, current)) {
            task.inventoryAcknowledged = true
        }
    }

    private fun sameScaffoldState(expected: BlockState, actual: BlockState): Boolean =
        SchematicScaffoldRules.acknowledgedStateMatches(
            BuiltInRegistries.BLOCK.getKey(expected.block).toString(), properties(expected),
            BuiltInRegistries.BLOCK.getKey(actual.block).toString(), properties(actual),
        )

    private fun serviceScaffoldCleanup(player: LocalPlayer, world: ClientLevel): Boolean {
        val retreat = scaffoldRetreatTarget
        if (retreat != null) {
            val center = Vec3(retreat.x + 0.5, retreat.y.toDouble(), retreat.z + 0.5)
            val dx = player.x - center.x
            val dz = player.z - center.z
            if (dx * dx + dz * dz < 0.22 && kotlin.math.abs(player.y - retreat.y) < 0.5) {
                scaffoldRetreatTarget = null
                scaffoldRetreatForPos = null
                route = emptyList()
                routeIndex = 0
                return true
            }
            if (tick - scaffoldRetreatStartedAt >= 100L) {
                val key = scaffoldRetreatForPos
                if (key != null && ownedScaffolds.remove(key) != null) retainedScaffolds += key
                scaffoldRetreatTarget = null
                scaffoldRetreatForPos = null
                route = emptyList()
                routeIndex = 0
                message("Could not safely step off a temporary scaffold; it was retained.", true)
                return false
            }
            return true
        }
        val pending = scaffoldCleanupPos
        if (pending != null) {
            if (!world.hasChunkAt(pending)) return true
            if (scaffoldCleanupRejected) {
                scaffoldCleanupRejected = false
                message("The server changed a scaffold during cleanup; ownership was dropped and the block was retained.", true)
                return false
            }
            if (scaffoldCleanupPos == null) return false
            if (tick - scaffoldCleanupSentAt >= SCAFFOLD_PROOF_TIMEOUT_TICKS) {
                ownedScaffolds.remove(pending.asLong())
                retainedScaffolds += pending.asLong()
                scaffoldCleanupPos = null
                message("A run-owned scaffold at ${pending.x}, ${pending.y}, ${pending.z} was not removed by the server; it was not retried.", true)
                return false
            }
            return true
        }

        for ((key, owned) in ownedScaffolds.toMap()) {
            if (!world.hasChunkAt(owned.pos)) continue
            if (world.getBlockState(owned.pos) != owned.state) {
                ownedScaffolds.remove(key)
                continue
            }
            val targetState = projectedExpectedState(owned.target)
            val targetPos = owned.target.toBlockPos()
            val adjacentDependency = Direction.values().any { direction ->
                val neighbor = owned.pos.relative(direction)
                if (direction == Direction.DOWN || !world.hasChunkAt(neighbor) || world.getBlockState(neighbor).isAir) return@any false
                if (neighbor == targetPos && targetState != null && targetState.isSolidRender &&
                    owned.targetComplete) return@any false
                true
            }
            val playerOverlaps = player.blockPosition().below() == owned.pos || player.boundingBox.intersects(
                net.minecraft.world.phys.AABB.ofSize(Vec3.atCenterOf(owned.pos), 0.98, 0.98, 0.98),
            )
            val safeWithoutPlayer = SchematicScaffoldRules.mayRemoveOwnedScaffold(
                ownedByThisRun = true,
                exactAcknowledgedStateStillPresent = world.getBlockState(owned.pos) == owned.state,
                dependentTargetComplete = owned.targetComplete,
                projectedSchematicCellIsAir = projectedCellIsAirOrOutside(owned.pos),
                playerIntersects = false,
                otherBlocksMayDependOnIt = adjacentDependency,
            )
            if (!safeWithoutPlayer) {
                ownedScaffolds.remove(key)
                retainedScaffolds += key
                continue
            }
            if (playerOverlaps) {
                val current = player.blockPosition().let { BuildCellPos(it.x, it.y, it.z) }
                val exit = Direction.Plane.HORIZONTAL.map { direction ->
                    current.offset(direction.stepX, 0, direction.stepZ)
                }.plus(Direction.Plane.HORIZONTAL.map { direction ->
                    current.offset(direction.stepX, 1, direction.stepZ)
                }).firstOrNull { candidate ->
                    val floor = candidate.toBlockPos().below()
                    candidate != current && SchematicRouteRules.allows(current, candidate) &&
                        floor.asLong() !in ownedScaffolds && canStandAt(world, player, candidate) &&
                        safeTransition(world, player, current, candidate)
                }
                if (exit == null) {
                    ownedScaffolds.remove(key)
                    retainedScaffolds += key
                    continue
                }
                scaffoldRetreatTarget = exit
                scaffoldRetreatForPos = key
                scaffoldRetreatStartedAt = tick
                route = listOf(exit)
                routeIndex = 0
                routeTarget = null
                return true
            }
            if (mc.gui.screen() != null || player.containerMenu !== player.inventoryMenu) return true
            if (!withinUseReach(player, owned.pos)) continue
            val desired = aimAt(player, Vec3.atCenterOf(owned.pos))
            if (!SchematicNativePlacement.rotationAcknowledged(
                    desired.yaw, desired.pitch,
                    RotationManager.currentRotation?.yaw, RotationManager.currentRotation?.pitch,
                    RotationManager.serverRotation.yaw, RotationManager.serverRotation.pitch,
                )) return true
            val hit = currentRotationHit(world, player, desired)
            if (hit == null || hit.type != HitResult.Type.BLOCK || hit.blockPos != owned.pos) return true
            if (!ensureSneakReady(owned.target, owned.pos, player, world)) return true
            val didStart = mc.gameMode?.destroyBlock(owned.pos) ?: false
            if (didStart) {
                releaseSneakLease()
                scaffoldCleanupPos = owned.pos.immutable()
                scaffoldCleanupSentAt = tick
                scaffoldCleanupRejected = false
            } else {
                ownedScaffolds.remove(key)
                retainedScaffolds += key
                message("Could not safely dispatch cleanup for the owned scaffold at ${owned.pos.x}, ${owned.pos.y}, ${owned.pos.z}; it was retained.", true)
            }
            return true
        }
        if (ownedScaffolds.isEmpty() && retainedScaffolds.isNotEmpty() && !retainedScaffoldReportSent) {
            retainedScaffoldReportSent = true
            message("Retained ${retainedScaffolds.size} scaffold block(s) because ownership, projection, player clearance, or support safety could not be proven.", true)
        }
        return false
    }

    @Suppress("unused")
    private val movement = handler<MovementInputEvent> { event ->
        if (mc.gui.screen() != null) return@handler
        val player = mc.player ?: return@handler
        val pillar = activePillarTask
        if (pillar != null) {
            if (hasManualPillarMovement()) return@handler
            event.directionalInput = DirectionalInput.NONE
            event.jump = pillar.stage == SchematicPillarStage.JUMP && player.onGround()
            if (pillar.stage == SchematicPillarStage.CENTER) {
                val centered = kotlin.math.abs(player.x - (pillar.x + 0.5)) < PILLAR_CENTER_TOLERANCE &&
                    kotlin.math.abs(player.z - (pillar.z + 0.5)) < PILLAR_CENTER_TOLERANCE
                if (!centered) {
                    requestAim(pillarAim(player, pillar.x, pillar.z))
                    event.directionalInput = DirectionalInput.FORWARDS
                }
            }
            return@handler
        }
        if (activeDigOutTask?.stage == SchematicDigOutStage.WAITING_EXIT && hasManualPillarMovement()) return@handler
        if (routeIndex >= route.size) return@handler
        val next = route[routeIndex]
        val world = mc.level as? ClientLevel ?: return@handler
        val surfaceY = standSurfaceY(world, next) ?: return@handler
        aimAt(player, Vec3(next.x + 0.5, surfaceY, next.z + 0.5))
        event.directionalInput = DirectionalInput.FORWARDS
        event.jump = surfaceY - player.y > WALK_STEP_HEIGHT
    }

    @Suppress("unused")
    private val rotation = handler<RotationUpdateEvent> {
        val player = mc.player ?: return@handler
        val pillar = activePillarTask
        if (pillar != null) {
            val desired = if (pillar.stage == SchematicPillarStage.AIMING_PLACEMENT) {
                pillar.candidate?.rotation
            } else {
                pillarAim(player, pillar.x, pillar.z)
            } ?: return@handler
            requestAim(desired)
            return@handler
        }
        val target = currentTarget
        val desired = when {
            eating -> Rotation.lookingAt(Vec3(player.x, player.y + 3.5, player.z), player.eyePosition)
            routeIndex < route.size -> route[routeIndex].let { Rotation.lookingAt(Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5), player.eyePosition) }
            target != null && placementCandidateFor == target -> placementCandidate?.rotation
            target != null -> findSupport(mc.level ?: return@handler, target.toBlockPos(), player)
                ?.second?.location?.let { Rotation.lookingAt(it, player.eyePosition) }
            else -> null
        } ?: return@handler
        requestAim(desired)
    }

    private fun observeSneakState(player: LocalPlayer) {
        val shifted = player.isShiftKeyDown
        val previous = lastSneakSample
        if (previous == null || previous != shifted) {
            lastSneakSample = shifted
            sneakStableTicks = 0
        } else {
            sneakStableTicks = (sneakStableTicks + 1).coerceAtMost(20)
        }
    }

    private fun ensureSneakReady(
        target: BuildCellPos,
        clickedSupport: BlockPos,
        player: LocalPlayer,
        world: ClientLevel,
    ): Boolean {
        val supportState = world.getBlockState(clickedSupport)
        val supportId = BuiltInRegistries.BLOCK.getKey(supportState.block).toString()
        val needsSneak = SchematicSneakRules.needsSneak(
            supportId,
            world.getBlockEntity(clickedSupport) != null || supportState.hasBlockEntity(),
        )
        if (!needsSneak) {
            if (sneakLeaseTarget != null) releaseSneakLease()
            return true
        }
        if (sneakLeaseTarget != target) {
            releaseSneakLease()
            sneakLeaseTarget = target
            sneakWasDownBeforeLease = mc.options.keyShift.isDown || player.isShiftKeyDown
            sneakPhysicalDownAtAcquire = physicalShiftDownNow()
            sneakWaitTicks = 0
            if (!player.isShiftKeyDown) {
                mc.options.keyShift.setDown(true)
                sneakLeaseOwned = true
            }
        } else if (!player.isShiftKeyDown && !sneakLeaseOwned) {
            sneakWasDownBeforeLease = mc.options.keyShift.isDown
            mc.options.keyShift.setDown(true)
            sneakLeaseOwned = true
        }
        if (SchematicSneakRules.settled(player.isShiftKeyDown, sneakStableTicks)) return true
        if (++sneakWaitTicks > 20) {
            releaseSneakLease()
            recordNoActionProgress(target, "sneak did not settle before placing against an interactive block")
        }
        return false
    }

    /** Opens/interacts with block entities only after owned sneak is released and observed settled. */
    private fun ensureSneakReleasedForInteraction(target: BuildCellPos, player: LocalPlayer): Boolean {
        if (sneakReleaseTarget != target) {
            if (sneakLeaseOwned) releaseSneakLease()
            sneakReleaseTarget = target
            sneakReleaseWaitTicks = 0
        }
        if (sneakLeaseOwned) releaseSneakLease()
        val manualShift = physicalShiftDownNow() || mc.options.keyShift.isDown && !sneakLeaseOwned
        if (manualShift || player.isShiftKeyDown || mc.options.keyShift.isDown) {
            if (++sneakReleaseWaitTicks > 20) {
                sneakReleaseTarget = null
                sneakReleaseWaitTicks = 0
            }
            return false
        }
        if (SchematicSneakRules.released(player.isShiftKeyDown, sneakStableTicks)) {
            sneakReleaseTarget = null
            sneakReleaseWaitTicks = 0
            return true
        }
        if (++sneakReleaseWaitTicks > 20) {
            sneakReleaseTarget = null
            sneakReleaseWaitTicks = 0
            message("Waiting for Shift to release before opening an interactive block.", true)
        }
        return false
    }

    private fun releaseSneakLease() {
        if (sneakLeaseOwned) {
            mc.options.keyShift.setDown(SchematicSneakRules.restoreShift(
                sneakWasDownBeforeLease, sneakPhysicalDownAtAcquire, physicalShiftDownNow(),
            ))
        }
        sneakLeaseTarget = null
        sneakLeaseOwned = false
        sneakWasDownBeforeLease = false
        sneakPhysicalDownAtAcquire = false
        sneakWaitTicks = 0
    }

    private fun physicalShiftDownNow(): Boolean =
        if (physicalShiftInputKnown) physicalShiftKeys.isNotEmpty() else physicalShiftHint

    private data class PlacementSupport(val direction: Direction, val location: Vec3)

    private fun findSupport(world: ClientLevel, target: BlockPos, player: LocalPlayer): Pair<BlockPos, PlacementSupport>? {
        val candidates = Direction.values().mapNotNull { direction ->
            val supportPos = target.relative(direction)
            if (!world.hasChunkAt(supportPos)) return@mapNotNull null
            val supportState = world.getBlockState(supportPos)
            val face = direction.opposite
            if (supportState.getShape(world, supportPos).isEmpty) return@mapNotNull null
            val point = Vec3.atCenterOf(supportPos).add(face.stepX * 0.499, face.stepY * 0.499, face.stepZ * 0.499)
            if (player.eyePosition.distanceToSqr(point) > 20.25) return@mapNotNull null
            supportPos to PlacementSupport(face, point)
        }
        return firstVisibleSupportCandidate(candidates) { (supportPos, support) ->
            val hit = world.clip(ClipContext(player.eyePosition, support.location, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
            hit is BlockHitResult && hit.type == HitResult.Type.BLOCK && hit.blockPos == supportPos && hit.direction == support.direction
        }
    }

    private fun currentViewHit(world: ClientLevel, player: LocalPlayer): BlockHitResult? {
        val eye = player.eyePosition
        // LiquidBounce's MixinItem redirects native POV raycasts to this active rotation.
        val direction = RotationManager.currentRotation?.directionVector ?: return null
        val end = eye.add(direction.scale(player.blockInteractionRange()))
        val hit = world.clip(ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        return hit as? BlockHitResult
    }

    private fun currentRotationHit(world: ClientLevel, player: LocalPlayer, rotation: Rotation): BlockHitResult? {
        val eye = player.eyePosition
        val end = eye.add(rotation.directionVector.scale(player.blockInteractionRange().toDouble()))
        val hit = world.clip(ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        return hit as? BlockHitResult
    }

    private fun currentFluidRotationHit(world: ClientLevel, player: LocalPlayer, rotation: Rotation): BlockHitResult? {
        val eye = player.eyePosition
        val end = eye.add(rotation.directionVector.scale(player.blockInteractionRange().toDouble()))
        val hit = world.clip(ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, player))
        return hit as? BlockHitResult
    }

    private fun interactExisting(target: BlockPos, player: LocalPlayer, world: ClientLevel, pos: BuildCellPos, expected: BlockState) {
        val point = Vec3.atCenterOf(target)
        if (player.eyePosition.distanceToSqr(point) > 20.25) {
            recordNoActionProgress(pos, "state correction target is out of reach")
            return
        }
        if (!ensureSneakReleasedForInteraction(pos, player)) return
        val desiredRotation = aimAt(player, point)
        placementCandidate = null
        placementCandidateFor = null
        if (rotationWaitTarget != pos) {
            rotationWaitTarget = pos
            rotationWaitSince = tick
        }
        if (!SchematicNativePlacement.rotationAcknowledged(
                desiredRotation.yaw,
                desiredRotation.pitch,
                RotationManager.currentRotation?.yaw,
                RotationManager.currentRotation?.pitch,
                RotationManager.serverRotation.yaw,
                RotationManager.serverRotation.pitch,
            )) {
            if (tick - rotationWaitSince >= PLACE_TIMEOUT_TICKS) {
                rotationWaitSince = tick
                recordNoActionProgress(pos, "server rotation did not reach the state-correction aim")
            }
            return
        }
        rotationWaitTarget = null
        val hit = currentRotationHit(world, player, desiredRotation)
        if (hit == null || hit.type != HitResult.Type.BLOCK || hit.blockPos != target) {
            recordNoActionProgress(pos, "state correction target is not ray-visible")
            return
        }
        val oldYaw = player.yRot
        val oldPitch = player.xRot
        val result = try {
            player.yRot = desiredRotation.yaw
            player.xRot = desiredRotation.pitch
            mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit)
        } finally {
            player.yRot = oldYaw
            player.xRot = oldPitch
        }
        if (result?.consumesAction() == true) {
            waitingForPlacement = pos
            placementSentAt = tick
            if (controlStateNeedsAdjustment(expected, world.getBlockState(target))) {
                controlAdjustmentClicks[pos] = (controlAdjustmentClicks[pos] ?: 0) + 1
            }
        } else {
            recordNoActionProgress(pos, "state correction use did not consume an action")
        }
    }

    private fun withinUseReach(player: LocalPlayer, target: BlockPos): Boolean =
        player.eyePosition.distanceToSqr(Vec3.atCenterOf(target)) <= 20.25

    private fun withinUseReach(world: Level, player: Player, feet: BuildCellPos, target: BlockPos): Boolean =
        eyeAtFeet(world, player, feet)?.distanceToSqr(Vec3.atCenterOf(target))?.let { it <= 20.25 } ?: false

    /** Resolves the actual top of a slab/partial collision floor under this feet cell. */
    private fun standSurfaceY(world: Level, feet: BuildCellPos): Double? {
        return SchematicStandSurface.surfaceY(world, feet)
    }

    private fun canStandAt(world: Level, player: Player, feet: BuildCellPos): Boolean =
        SchematicStandSurface.canStandAt(world, player, feet)

    private fun safeTransition(world: Level, player: Player, from: BuildCellPos, to: BuildCellPos): Boolean {
        val fromY = standSurfaceY(world, from) ?: return false
        val toY = standSurfaceY(world, to) ?: return false
        val rise = toY - fromY
        if (rise > WALK_STEP_HEIGHT || rise < -3.55) return false
        val horizontal = net.minecraft.world.phys.AABB(
            minOf(from.x, to.x) + 0.12, minOf(fromY, toY) + 0.01, minOf(from.z, to.z) + 0.12,
            maxOf(from.x, to.x) + 0.88, maxOf(fromY, toY) + 1.8, maxOf(from.z, to.z) + 0.88,
        )
        val low = kotlin.math.floor(minOf(fromY, toY)).toInt()
        val high = kotlin.math.floor(maxOf(fromY, toY) + 1.8).toInt()
        for (y in low..high) {
            val pos = BlockPos(to.x, y, to.z)
            if (!world.hasChunkAt(pos) || world.getFluidState(pos).isSource) return false
        }
        return world.noCollision(player, horizontal)
    }

    private fun aimAt(player: LocalPlayer, point: Vec3): Rotation =
        Rotation.lookingAt(point, player.eyePosition).normalize().also(::requestAim)

    private fun requestAim(rotation: Rotation) {
        RotationManager.setRotationTarget(
            RotationTarget(rotation, ticksUntilReset = 1, movementCorrection = MovementCorrection.STRICT),
            Priority.IMPORTANT_FOR_USAGE_1,
            this,
        )
    }

    private fun eyeAtFeet(world: Level, player: Player, feet: BuildCellPos): Vec3? =
        standSurfaceY(world, feet)?.let { surfaceY ->
            Vec3(feet.x + 0.5, surfaceY + (player.eyePosition.y - player.y), feet.z + 0.5)
        }

    private fun processServerUpdates(world: ClientLevel) {
        while (true) {
            val update = incomingBlockUpdates.poll() ?: break
            if (update.generation != runGeneration) continue
            val key = BuildCellPos(update.pos.x, update.pos.y, update.pos.z)
            val pillarTask = activePillarTask
            if (pillarTask?.stage == SchematicPillarStage.WAITING_FOR_BLOCK_PROOF &&
                update.pos == BlockPos(pillarTask.x, pillarTask.baseY + pillarTask.step, pillarTask.z) &&
                update.ordinal > pillarTask.blockPacketBaseline && pillarTask.sentAt != 0L) {
                val expectedPillarState = pillarTask.expectedState
                if (expectedPillarState != null && sameScaffoldState(expectedPillarState, update.state)) {
                    pillarTask.blockAcknowledged = true
                    pillarTask.acknowledgedState = update.state
                } else {
                    pillarTask.blockRejected = true
                }
            }
            val scaffoldTask = activeScaffoldTask
            if (scaffoldTask?.stage == SchematicScaffoldStage.WAITING_FOR_PROOF &&
                update.pos == scaffoldTask.support && scaffoldTask.sentAt != 0L) {
                if (sameScaffoldState(scaffoldTask.state, update.state)) {
                    scaffoldTask.blockAcknowledged = true
                    scaffoldTask.acknowledgedState = update.state
                }
                else scaffoldTask.blockRejected = true
            }
            val waterlog = waterlogTask
            if (waterlog != null && waterlog.sent && waterlog.generation == runGeneration &&
                waterlog.target.toBlockPos() == update.pos && update.ordinal > waterlog.blockPacketBaseline) {
                if (statesMatch(waterlog.expectedState, update.state, false) &&
                    update.state.hasProperty(BlockStateProperties.WATERLOGGED) &&
                    update.state.getValue(BlockStateProperties.WATERLOGGED)) {
                    waterlog.blockAcknowledged = true
                } else if (statesMatch(waterlog.expectedState, update.state, false)) {
                    waterlog.blockRejected = true
                }
            }
            val digOut = activeDigOutTask
            if (digOut != null && (digOut.stage == SchematicDigOutStage.DESTROYING || digOut.stage == SchematicDigOutStage.WAITING_ACK) &&
                digOut.generation == runGeneration &&
                digOut.target.toBlockPos() == update.pos && update.ordinal > digOut.blockPacketBaseline) {
                if (SchematicDigOutRules.shouldAcknowledgeAirUpdate(update.ordinal, digOut.blockPacketBaseline, update.state.isAir)) {
                    digOut.blockAcknowledged = true
                } else if (update.state != digOut.originalState) {
                    digOut.blockRejected = true
                }
            }
            if (scaffoldCleanupPos == update.pos) {
                if (update.state.isAir) {
                    ownedScaffolds.remove(update.pos.asLong())
                    scaffoldCleanupPos = null
                } else if (ownedScaffolds[update.pos.asLong()]?.state != update.state) {
                    ownedScaffolds.remove(update.pos.asLong())
                    retainedScaffolds += update.pos.asLong()
                    scaffoldCleanupPos = null
                    scaffoldCleanupRejected = true
                }
            } else {
                val owned = ownedScaffolds[update.pos.asLong()]
                if (owned != null && update.state != owned.state) {
                    if (sameScaffoldState(owned.state, update.state)) owned.state = update.state
                    else {
                        ownedScaffolds.remove(update.pos.asLong())
                        retainedScaffolds += update.pos.asLong()
                    }
                }
            }
            val expected = expectedStates[key] ?: continue
            if (waitingForPlacement == key) {
                if (statesMatch(expected, update.state, relaxedPass && key in relaxedCandidates)) {
                    if (relaxedPass && update.state.block == expected.block && update.state != expected) relaxedCompleted += key
                    provisionalChests -= key
                    provisionalChestAt -= key
                    completeTarget(key, key in relaxedCompleted)
                } else if (isDoubleSlabUpdatePending(expected, update.state)) {
                    waitingForPlacement = null
                    placementSentAt = 0
                    nextPlacementTick = tick + 2
                } else if (isChestUpdateProvisional(key, expected, update.state)) {
                    provisionalChests += key
                    provisionalChestAt[key] = tick
                    waitingForPlacement = null
                    currentTarget = SchematicStructureRules.provisionalChestPartnerTarget(key, properties(expected), missingCells.keys)
                    rebuildPlan()
                } else if (controlStateCanAdvance(expected, update.state)) {
                    waitingForPlacement = null
                    nextPlacementTick = tick + 4
                } else {
                    placementTimeout()
                }
            } else if (statesMatch(expected, update.state, key in relaxedCompleted)) {
                provisionalChests -= key
                provisionalChestAt -= key
                missingCells -= key
                markCellComplete(key)
            } else if (isChestUpdateProvisional(key, expected, update.state)) {
                provisionalChests += key
                provisionalChestAt[key] = tick
            }
        }
    }

    private fun placementTimeout() {
        val pos = waitingForPlacement ?: currentTarget ?: return
        waitingForPlacement = null
        releaseSneakLease()
        advancePlacementFailure(pos, "server did not confirm the requested block state")
    }

    private fun recordNoActionProgress(pos: BuildCellPos, reason: String) {
        if (!actionFailureGate.shouldCount(pos, tick)) return
        advancePlacementFailure(pos, reason)
    }

    private fun advancePlacementFailure(pos: BuildCellPos, reason: String) {
        releaseSneakLease()
        BaritoneGoalBridge.cancelOwned(this, pos)
        val decision = retryLedger.recordFailure(pos)
        when (decision) {
            RetryDecision.RETRY -> message("${pos.asText()}: $reason; retry ${retryLedger.failures(pos)}/8.", false)
            RetryDecision.DEFER -> {
                deferredCells += pos
                message("Cell ${pos.asText()} entered the stubborn/deferred pass after three failures.", true)
            }
            RetryDecision.UNRESOLVED -> {
                if (relaxFacingWhenStuck && pos !in relaxedCandidates && pos !in relaxedCompleted) {
                    relaxedCandidates += pos
                    message("Eight exact attempts failed at ${pos.asText()}; a single relaxed-facing pass is queued.", true)
                } else {
                    unresolvedCells += pos
                    missingCells -= pos
                    message("Cell ${pos.asText()} remains unresolved after bounded retries.", true)
                }
            }
        }
        currentTarget = null
        placementCandidate = null
        placementCandidateFor = null
        rotationWaitTarget = null
        route = emptyList()
        routeTarget = null
        rebuildPlan()
    }

    private fun controlStateCanAdvance(expected: BlockState, actual: BlockState): Boolean =
        SchematicStateRules.canonicalBlockId(BuiltInRegistries.BLOCK.getKey(expected.block).toString()) ==
            SchematicStateRules.canonicalBlockId(BuiltInRegistries.BLOCK.getKey(actual.block).toString()) &&
            expected != actual && controlStateNeedsAdjustment(expected, actual)

    private fun isDoubleSlabUpdatePending(expected: BlockState, actual: BlockState): Boolean =
        SchematicStructureRules.isDoubleSlabPending(
            BuiltInRegistries.BLOCK.getKey(expected.block).toString(), properties(expected),
            BuiltInRegistries.BLOCK.getKey(actual.block).toString(), properties(actual),
        )

    private fun isChestUpdateProvisional(pos: BuildCellPos, expected: BlockState, actual: BlockState): Boolean {
        val partner = chestPartner(pos, expected) ?: return false
        return SchematicStructureRules.isChestProvisional(
            BuiltInRegistries.BLOCK.getKey(expected.block).toString(), properties(expected),
            BuiltInRegistries.BLOCK.getKey(actual.block).toString(), properties(actual),
            partnerExpected = hasMissingChestPartner(pos, expected) &&
                projectedExpectedState(partner)?.let { !it.isAir && it.block == expected.block } == true,
        )
    }

    private fun completeTarget(pos: BuildCellPos, relaxed: Boolean) {
        BaritoneGoalBridge.cancelOwned(this, pos)
        releaseSneakLease()
        waitingForPlacement = null
        retryLedger.clearPosition(pos)
        controlAdjustmentClicks.remove(pos)
        missingCells -= pos
        markCellComplete(pos)
        ownedScaffolds.values.filter { it.target == pos }.forEach { it.targetComplete = true }
        placementProgressGeneration++
        currentTarget = null
        placementCandidate = null
        placementCandidateFor = null
        rotationWaitTarget = null
        route = emptyList()
        routeTarget = null
        placementAttempts = 0
        lastPlacementTick = tick
        nextPlacementTick = tick + tickDelay
        if (relaxed) message("Placed ${pos.asText()} in another legal facing; final report will include it.", true)
        rebuildPlan()
    }

    private fun markCellComplete(pos: BuildCellPos) {
        val expected = expectedStates[pos]
        val world = activeWorld
        if (expected != null && world != null && world.hasChunkAt(pos.toBlockPos())) {
            updateWaterlogRequirement(pos, expected, world.getBlockState(pos.toBlockPos()))
        }
        if (completedCells.add(pos)) confirmedCellCount++
    }

    private fun updateWaterlogRequirement(pos: BuildCellPos, expected: BlockState, actual: BlockState) {
        if (!expected.hasProperty(BlockStateProperties.WATERLOGGED) ||
            !expected.getValue(BlockStateProperties.WATERLOGGED)) {
            pendingWaterlogCells -= pos
            excludedWaterlogCells -= pos
            if (waterlogTask?.target == pos && !waterlogTask!!.sent) waterlogTask = null
            return
        }
        if (!statesMatch(expected, actual, pos in relaxedCompleted)) {
            pendingWaterlogCells -= pos
            excludedWaterlogCells -= pos
            if (waterlogTask?.target == pos && !waterlogTask!!.sent) waterlogTask = null
            return
        }
        if (actual.hasProperty(BlockStateProperties.WATERLOGGED) && actual.getValue(BlockStateProperties.WATERLOGGED)) {
            excludedWaterlogCells -= pos
            if (waterlogTask?.target != pos || waterlogTask?.sent != true) pendingWaterlogCells -= pos
            return
        }
        if (!placeWaterLava) {
            pendingWaterlogCells -= pos
            excludedWaterlogCells += pos
            return
        }
        excludedWaterlogCells -= pos
        pendingWaterlogCells += pos
    }

    private fun finishOrReport(world: ClientLevel) {
        val unscanned = pendingVirtualData.size
        val unresolved = unresolvedCountByLayerWindow.values.sum()
        excludedTntCount = excludedCountByLayerWindow.values.sumOf(Pair<Int, Int>::first)
        excludedFluidCount = excludedCountByLayerWindow.values.sumOf(Pair<Int, Int>::second)
        val countLeft = unresolved + unscanned
        if (countLeft == 0) {
            buildFullyComplete = true
            if (stagedLeases.isNotEmpty()) {
                phase = SchematicPhase.RESTORING
                message(buildScopeSummary("Build scope complete") + "; restoring inventory slots used for staging.", false)
            } else {
                val retained = retainedScaffolds.size
                message(
                    buildScopeSummary("Build scope complete") +
                        if (retained > 0) " Retained $retained temporary scaffold block(s) because safe removal could not be proven." else "",
                    retained > 0 || excludedTntCount + excludedFluidCount > 0,
                )
                phase = SchematicPhase.COMPLETE
            }
            return
        }
        buildFullyComplete = false
        val incompleteDetails = buildList {
            add("$unresolved unresolved")
            if (unscanned > 0) add("$unscanned projected cells unavailable")
            if (excludedTntCount > 0) add("$excludedTntCount TNT excluded by setting")
            if (excludedFluidCount > 0) add("$excludedFluidCount fluid blocks excluded by setting")
        }.joinToString(", ")
        message("Build finished incomplete: $incompleteDetails.", true)
        if (stagedLeases.isNotEmpty()) {
            phase = SchematicPhase.RESTORING
            return
        }
        phase = SchematicPhase.COMPLETE
    }

    private fun buildScopeSummary(prefix: String): String =
        "$prefix: $confirmedCellCount cells confirmed; ${relaxedCompleted.size} used a relaxed facing" +
            if (excludedTntCount + excludedFluidCount > 0) {
                "; schematic is partial because $excludedTntCount TNT and $excludedFluidCount fluid blocks were excluded by settings"
            } else ""

    private fun drainCrafterData(player: LocalPlayer) {
        while (true) {
            val open = incomingCrafterOpens.poll() ?: break
            if (open.generation != runGeneration) continue
            val task = activeCrafterTask ?: continue
            if (task.stage == SchematicCrafterStage.OPENING && open.ordinal > task.openPacketBaseline &&
                open.menuId != task.previousMenuId) {
                task.menuId = open.menuId
                task.screenIdentity = 0L
                task.serverData.clear()
                task.awaitingData.clear()
                task.acknowledgedData.clear()
            }
        }
        while (true) {
            val update = incomingCrafterData.poll() ?: break
            if (update.generation != runGeneration) continue
            val task = activeCrafterTask ?: continue
            // Data packets may beat the client's local menu replacement. Retain only the menu
            // identity announced while our own interaction is opening; the local CrafterMenu and
            // screen identity are still required before any toggle or acknowledgment can proceed.
            if (!SchematicCrafterRules.dataBelongsToAnnouncedMenu(task.menuId, update.menuId, update.slot)) continue
            task.serverData[update.slot] = update.value
            if (update.slot in task.awaitingData &&
                SchematicCrafterRules.dataValueMatches(task.expectedDisabled[update.slot], update.value)) {
                task.acknowledgedData += update.slot
            }
        }
    }

    /** Server-backed crafter mask synchronization, matching the source's empty-slot-only toggles. */
    private fun serviceCrafterConfiguration(player: LocalPlayer, world: ClientLevel): Boolean {
        if (!crafterSlots) {
            val task = activeCrafterTask
            if (task != null) {
                if (ownedCrafterMenu(player, task) != null) player.closeContainer()
                activeCrafterTask = null
                releaseSneakLease()
                route = emptyList()
                routeIndex = 0
                routeTarget = null
            }
            return false
        }
        if (phase !in setOf(SchematicPhase.SCANNING, SchematicPhase.BUILDING)) return false
        var task = activeCrafterTask
        if (task == null) {
            val next = SchematicCrafterProgressRules.nextReady(expectedCrafterMasks, completedCrafters) { pos ->
                val blockPos = pos.toBlockPos()
                world.hasChunkAt(blockPos) && world.getBlockState(blockPos).block == Blocks.CRAFTER
            } ?: return false
            task = SchematicCrafterTask(next.first, next.second.toList())
            task.rounds = 1
            activeCrafterTask = task
            task.since = tick
        }
        val pos = task.pos
        val blockPos = pos.toBlockPos()
        if (!world.hasChunkAt(blockPos) || world.getBlockState(blockPos).block != Blocks.CRAFTER) {
            if (tick - task.since >= PLACE_TIMEOUT_TICKS) failCrafterTask(task, "the target crafter is no longer loaded")
            return true
        }
        val blockEntity = world.getBlockEntity(blockPos) as? net.minecraft.world.level.block.entity.CrafterBlockEntity
        if (blockEntity == null) {
            failCrafterTask(task, "the placed crafter has no readable block entity")
            return true
        }
        val worldMask = (0 until 9).map(blockEntity::isSlotDisabled)
        if (task.stage == SchematicCrafterStage.PREPARE && SchematicCrafterRules.maskMatches(task.expectedDisabled, worldMask)) {
            completedCrafters += pos
            activeCrafterTask = null
            message("Crafter slot mask at ${pos.asText()} already matches the schematic.", false)
            return true
        }
        when (task.stage) {
            SchematicCrafterStage.PREPARE -> {
                if (tick - task.since >= PLACE_TIMEOUT_TICKS && task.rounds >= 2) {
                    failCrafterTask(task, "no safe visible interaction was available")
                    return true
                }
                if (mc.gui.screen() != null || player.containerMenu !== player.inventoryMenu) {
                    retryCrafterIfTimedOut(task, "another menu stayed open")
                    return true
                }
                if (!withinUseReach(player, blockPos)) {
                    if (!walkTowardCrafter(player, world, pos)) retryCrafterIfTimedOut(task, "no safe stand route reached the crafter")
                    return true
                }
                val desired = aimAt(player, Vec3.atCenterOf(blockPos))
                if (!SchematicNativePlacement.rotationAcknowledged(
                        desired.yaw, desired.pitch,
                        RotationManager.currentRotation?.yaw, RotationManager.currentRotation?.pitch,
                        RotationManager.serverRotation.yaw, RotationManager.serverRotation.pitch,
                    )) {
                    retryCrafterIfTimedOut(task, "the interaction rotation did not settle")
                    return true
                }
                val hit = currentRotationHit(world, player, desired)
                if (hit == null || hit.type != HitResult.Type.BLOCK || hit.blockPos != blockPos) {
                    retryCrafterIfTimedOut(task, "the crafter was not ray visible")
                    return true
                }
                if (!ensureSneakReleasedForInteraction(pos, player)) return true
                if (tickDelay > 0 && tick % (tickDelay + 1L) != 0L) return true
                val oldYaw = player.yRot
                val oldPitch = player.xRot
                task.openPacketBaseline = crafterOpenOrdinal.get()
                val result = try {
                    player.yRot = desired.yaw
                    player.xRot = desired.pitch
                    mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit)
                } finally {
                    player.yRot = oldYaw
                    player.xRot = oldPitch
                }
                if (result?.consumesAction() == true) {
                    task.previousMenuId = player.inventoryMenu.containerId
                    task.menuId = -1
                    task.screenIdentity = 0L
                    task.serverData.clear()
                    task.awaitingData.clear()
                    task.acknowledgedData.clear()
                    task.stage = SchematicCrafterStage.OPENING
                    task.since = tick
                } else retryCrafterIfTimedOut(task, "the crafter interaction did not consume an action")
                return true
            }
            SchematicCrafterStage.OPENING -> {
                val menu = player.containerMenu as? CrafterMenu
                val screen = mc.gui.screen() as? AbstractContainerScreen<*>
                if (menu != null && screen != null && menu.containerId != task.previousMenuId) {
                    if (task.menuId < 0) {
                        task.menuId = menu.containerId
                        task.screenIdentity = System.identityHashCode(screen).toLong()
                    }
                    if (task.menuId == menu.containerId && task.screenIdentity == 0L) {
                        task.screenIdentity = System.identityHashCode(screen).toLong()
                    }
                    if (SchematicCrafterRules.localMenuMatchesAnnouncement(
                            task.menuId, menu.containerId, isCrafterMenu = true, hasContainerScreen = true,
                        ) && task.screenIdentity == System.identityHashCode(screen).toLong()) {
                        task.stage = SchematicCrafterStage.INITIAL_SYNC
                        task.since = tick
                        releaseSneakLease()
                    }
                } else if (tick - task.since >= 40L) retryCrafterIfTimedOut(task, "a CrafterMenu did not open")
                return true
            }
            SchematicCrafterStage.INITIAL_SYNC -> {
                val menu = ownedCrafterMenu(player, task)
                if (menu == null) {
                    retryCrafterIfTimedOut(task, "the owned crafter menu changed before synchronization")
                    return true
                }
                if ((0..8).all { it in task.serverData }) {
                    val serverMask = (0..8).map { task.serverData.getValue(it) == 1 }
                    val changes = SchematicCrafterRules.toggles(
                        task.expectedDisabled, serverMask,
                        (0..8).filterTo(linkedSetOf()) { menu.getSlot(it).hasItem() },
                    )
                    task.awaitingData.clear()
                    task.acknowledgedData.clear()
                    for (change in changes) {
                        task.awaitingData += change.slot
                        menu.setSlotState(change.slot, change.enabled)
                        mc.gameMode?.handleSlotStateChanged(change.slot, menu.containerId, change.enabled)
                    }
                    task.stage = SchematicCrafterStage.WAIT_ACK
                    task.since = tick
                } else if (tick - task.since >= 12L) retryCrafterIfTimedOut(task, "the server did not synchronize all nine crafter slots")
                return true
            }
            SchematicCrafterStage.WAIT_ACK -> {
                val menu = ownedCrafterMenu(player, task)
                if (menu == null) {
                    retryCrafterIfTimedOut(task, "the owned crafter menu changed before acknowledgment")
                    return true
                }
                if (tick - task.since < 12L) return true
                val serverMask = (0..8).map { task.serverData[it] == 1 }
                val acked = SchematicCrafterRules.allDataAcknowledged(task.awaitingData, task.acknowledgedData)
                val matches = SchematicCrafterRules.maskMatches(task.expectedDisabled, serverMask) &&
                    SchematicCrafterRules.maskMatches(task.expectedDisabled, (0..8).map(menu::isSlotDisabled))
                if (acked && matches) {
                    player.closeContainer()
                    completedCrafters += pos
                    activeCrafterTask = null
                    releaseSneakLease()
                    route = emptyList()
                    routeIndex = 0
                    routeTarget = null
                    message("Crafter slot mask at ${pos.asText()} matched the schematic after server data updates.", false)
                } else retryCrafterIfTimedOut(task, "the server-confirmed crafter mask still differs")
                return true
            }
        }
    }

    private fun ownedCrafterMenu(player: LocalPlayer, task: SchematicCrafterTask): CrafterMenu? {
        val menu = player.containerMenu as? CrafterMenu ?: return null
        val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return null
        if (menu.containerId != task.menuId || task.screenIdentity != System.identityHashCode(screen).toLong()) return null
        return menu
    }

    private fun retryCrafterIfTimedOut(task: SchematicCrafterTask, reason: String) {
        if (tick - task.since < when (task.stage) {
                SchematicCrafterStage.OPENING -> 40L
                SchematicCrafterStage.INITIAL_SYNC, SchematicCrafterStage.WAIT_ACK -> 12L
                SchematicCrafterStage.PREPARE -> PLACE_TIMEOUT_TICKS.toLong()
            }) return
        if (task.menuId >= 0 && activePlayer != null) {
            val menu = ownedCrafterMenu(activePlayer!!, task)
            if (menu != null) activePlayer!!.closeContainer()
        }
        releaseSneakLease()
        route = emptyList()
        routeIndex = 0
        routeTarget = null
        if (task.rounds >= 2) {
            failCrafterTask(task, "$reason after two bounded rounds")
            return
        }
        task.stage = SchematicCrafterStage.PREPARE
        task.rounds++
        task.since = tick
        task.previousMenuId = -1
        task.menuId = -1
        task.screenIdentity = 0L
        task.openPacketBaseline = crafterOpenOrdinal.get()
        incomingCrafterOpens.clear()
        task.serverData.clear()
        task.awaitingData.clear()
        task.acknowledgedData.clear()
        message("Retrying crafter slot synchronization at ${task.pos.asText()}: $reason.", true)
    }

    private fun failCrafterTask(task: SchematicCrafterTask, reason: String) {
        if (task.menuId >= 0 && activePlayer != null) {
            val menu = ownedCrafterMenu(activePlayer!!, task)
            if (menu != null) activePlayer!!.closeContainer()
        }
        releaseSneakLease()
        completedCrafters += task.pos
        unresolvedCells += task.pos
        activeCrafterTask = null
        message("Crafter at ${task.pos.asText()} remains unresolved: $reason.", true)
    }

    private fun discardCrafterTaskForChangedMask(pos: BuildCellPos) {
        val task = activeCrafterTask?.takeIf { it.pos == pos } ?: return
        val player = mc.player
        if (player != null && ownedCrafterMenu(player, task) != null) player.closeContainer()
        activeCrafterTask = null
        incomingCrafterData.clear()
        incomingCrafterOpens.clear()
        releaseSneakLease()
        route = emptyList()
        routeIndex = 0
        routeTarget = null
    }

    private fun walkTowardCrafter(player: LocalPlayer, world: ClientLevel, target: BuildCellPos): Boolean {
        if (routeTarget != target || route.isEmpty()) {
            val start = player.blockPosition().let { BuildCellPos(it.x, it.y, it.z) }
            val result = SchematicStandPlanner.findRoute(
                start,
                isGoal = { candidate ->
                    val eye = eyeAtFeet(world, player, candidate) ?: return@findRoute false
                    canStandAt(world, player, candidate) && canSeeCrafterFrom(world, player, target.toBlockPos(), eye)
                },
                canStand = { candidate -> canStandAt(world, player, candidate) },
                canTransition = { from, to -> SchematicRouteRules.allows(from, to) && safeTransition(world, player, from, to) },
                visitBudget = ROUTE_BUDGET,
            )
            route = result.route.orEmpty()
            routeIndex = 0
            routeTarget = target
            if (result.route == null) return false
            if (route.isEmpty()) return false
        }
        if (routeIndex >= route.size) {
            route = emptyList()
            routeTarget = null
            return false
        }
        val next = route[routeIndex]
        val center = Vec3(next.x + 0.5, next.y.toDouble(), next.z + 0.5)
        val dx = player.x - center.x
        val dz = player.z - center.z
        if (dx * dx + dz * dz < 0.22 && kotlin.math.abs(player.y - next.y) < 0.5) routeIndex++
        if (routeIndex >= route.size) {
            route = emptyList()
            routeIndex = 0
            routeTarget = null
            return false
        }
        return true
    }

    private fun canSeeCrafterFrom(world: ClientLevel, player: LocalPlayer, target: BlockPos, eye: Vec3): Boolean {
        val reach = player.blockInteractionRange().toDouble()
        val point = Vec3.atCenterOf(target)
        if (eye.distanceToSqr(point) > reach * reach) return false
        val rotation = Rotation.lookingAt(point, eye).normalize()
        val hit = world.clip(ClipContext(eye, eye.add(rotation.directionVector.scale(reach)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        return hit is BlockHitResult && hit.type == HitResult.Type.BLOCK && hit.blockPos == target
    }

    private fun startScaffoldPurchase(player: LocalPlayer): Boolean {
        val queryId = "minecraft:glass"
        if (queryId in blockedPurchaseItems || lastPlacementTick?.let { tick - it < 40L } == true) return false
        val listCap = auctionListPriceCap ?: return false
        val allowed = BuiltInRegistries.ITEM.keySet().mapNotNull { key ->
            val item = BuiltInRegistries.ITEM.getValue(key)
            val blockItem = item as? BlockItem ?: return@mapNotNull null
            val valid = blockItem.block is TransparentBlock || ItemStack(item).`is`(ItemTags.LEAVES)
            if (valid) key.toString() else null
        }.toSet() + queryId
        val wanted = ItemStack(Items.GLASS)
        val capacity = SchematicProcurementRules.inventoryRoom(
            InventorySlotValue.fromStack(wanted), wanted.maxStackSize,
            (0..35).map { InventorySlotValue.fromStack(player.inventory.getItem(it)) },
            AuctionPurchaseListingRules::procurementLore,
        )
        val amount = minOf(8, capacity)
        if (amount <= 0) {
            waitingForInventoryRoom += queryId
            message("Glass scaffold purchase is waiting for compatible inventory room.", true)
            return false
        }
        waitingForInventoryRoom -= queryId
        val request = AuctionPurchaseRequest(
            itemId = queryId,
            amount = amount,
            maxListingTotal = listCap,
            listingPredicate = { slot -> AuctionPurchaseListingRules.ordinary(slot) },
            cumulativeBudget = auctionTotalSpendCap,
            maxRefreshes = 5,
            maxPages = 5,
            settleTicks = 2L,
            refreshDelayTicks = ((ahRefreshMs + 49) / 50).toLong(),
            ackTimeoutTicks = 80L,
            allowedItemIds = allowed,
        )
        if (!auctionPurchaseWorkflow.start(request)) {
            if (auctionPurchaseWorkflow.progress().status == AuctionPurchaseStatus.UNCERTAIN) {
                blockedPurchaseItems += queryId
                message("Glass scaffold purchase has an uncertain prior result; no repeat was sent.", true)
            }
            return false
        }
        activePurchaseItemId = queryId
        activePurchaseAllowedItems = allowed
        activePurchaseBaselineInventory = serverInventorySnapshot()
        message("Searching the Auction House for plain glass scaffold blocks within the configured listing and spend caps.", false)
        return true
    }

    private fun startMaterialPurchase(player: LocalPlayer, itemId: String, item: Item): Boolean {
        val safeId = MarketRules.safeItemId(itemId) ?: return false
        if (safeId in blockedPurchaseItems) return false
        if (lastPlacementTick?.let { tick - it < 40L } == true) return false
        val listCap = auctionListPriceCap ?: return false
        val requiredByItem = SchematicProcurementRules.requiredCounts(missingCells.values.mapNotNull { cell ->
            val state = expectedStates[cell.pos] ?: return@mapNotNull null
            val material = cell.itemId ?: return@mapNotNull null
            SchematicMaterialNeed(
                material,
                BuiltInRegistries.BLOCK.getKey(state.block).toString(),
                properties(state),
            )
        }).toMutableMap()
        if (itemId == "minecraft:water_bucket" && placeWaterLava) {
            requiredByItem[itemId] = (requiredByItem[itemId] ?: 0) + pendingWaterlogCells.size
        }
        val required = requiredByItem[itemId] ?: 0
        val have = (0..35).sumOf { slot ->
            val stack = player.inventory.getItem(slot)
            val value = InventorySlotValue.fromStack(stack)
            val exactWaterBucket = itemId != "minecraft:water_bucket" ||
                value == InventorySlotValue.fromStack(ItemStack(Items.WATER_BUCKET))
            if (!stack.isEmpty && exactWaterBucket && BuiltInRegistries.ITEM.getKey(stack.item).toString() == itemId) stack.count else 0
        }
        val shortage = SchematicProcurementRules.shortage(required, have)
        if (shortage <= 0) return false
        val wantedStack = ItemStack(item)
        val wantedValue = InventorySlotValue.fromStack(wantedStack)
        val inventoryValues = (0..35).map { InventorySlotValue.fromStack(player.inventory.getItem(it)) }
        var capacity = SchematicProcurementRules.inventoryRoom(
            wantedValue,
            wantedStack.maxStackSize,
            inventoryValues,
            AuctionPurchaseListingRules::procurementLore,
        )
        val amount = SchematicProcurementRules.purchaseTarget(
            shortage, capacity, wantedStack.maxStackSize, waterBucket = item == Items.WATER_BUCKET,
        )
        if (amount <= 0) {
            waitingForInventoryRoom += safeId
            message("Auction purchase for $safeId is waiting for compatible inventory room.", true)
            return false
        }
        waitingForInventoryRoom -= safeId
        val request = AuctionPurchaseRequest(
            itemId = safeId,
            amount = amount,
            maxListingTotal = listCap,
            cumulativeBudget = auctionTotalSpendCap,
            maxRefreshes = 5,
            maxPages = 5,
            settleTicks = 2L,
            refreshDelayTicks = ((ahRefreshMs + 49) / 50).toLong(),
            ackTimeoutTicks = 80L,
            allowedItemIds = setOf(safeId),
        )
        if (!auctionPurchaseWorkflow.start(request)) {
            val status = auctionPurchaseWorkflow.progress()
            if (status.status == AuctionPurchaseStatus.UNCERTAIN) {
                blockedPurchaseItems += safeId
                message("Auction purchase for $safeId is uncertain from an earlier click; no retry was sent.", true)
            }
            return false
        }
        activePurchaseItemId = safeId
        activePurchaseAllowedItems = request.allowedItemIds
        activePurchaseBaselineInventory = serverInventorySnapshot()
        message("Searching the Auction House for $safeId ($amount target) within the configured price and spend caps.", false)
        return true
    }

    private fun serviceAuctionPurchase(
        player: LocalPlayer,
        world: ClientLevel,
        connection: ClientPacketListener,
        capturedFrame: AuctionPurchaseFrame?,
    ): Boolean {
        val itemId = activePurchaseItemId ?: return false
        val frame = capturedFrame ?: captureAuctionFrame(player, world, connection)
        val beforeCancel = auctionPurchaseWorkflow.progress()
        if (!buyFromAh) {
            val stopped = if (beforeCancel.status == AuctionPurchaseStatus.RUNNING) {
                auctionPurchaseWorkflow.resetRequest()
                val afterCancel = auctionPurchaseWorkflow.progress()
                when {
                    afterCancel.status == AuctionPurchaseStatus.UNCERTAIN -> afterCancel
                    beforeCancel.acquiredAmount > 0 -> beforeCancel.copy(
                        status = AuctionPurchaseStatus.PARTIAL,
                        reason = "new purchases disabled after acknowledged partial acquisition",
                    )
                    else -> afterCancel
                }
            } else beforeCancel
            val menu = frame?.snapshot?.menu
            if (menu != null && menu.menuId in ownedAuctionMenus) {
                val closeEffect = AuctionPurchaseEffect.CloseMenu(frame.snapshot.session, menu.menuId, menu.screenIdentity)
                if (!dispatchAuctionEffect(frame, closeEffect)) return true
            }
            if (frame == null && ownedAuctionMenus.isNotEmpty()) return true
            finalizeAuctionRequest(itemId, stopped)
            return true
        }
        val progress = beforeCancel
        if (frame != null && progress.status == AuctionPurchaseStatus.RUNNING) {
            val menu = frame.snapshot.menu
            if (menu != null && menu.menuId !in ownedAuctionMenus) {
                when {
                    auctionCommandPending && MenuClassifier.classify(menu) == MenuKind.AUCTION -> {
                        ownedAuctionMenus += menu.menuId
                        auctionCommandPending = false
                    }
                    awaitingAuctionConfirmation && MenuClassifier.classify(menu) == MenuKind.CONFIRMATION -> {
                        ownedAuctionMenus += menu.menuId
                        awaitingAuctionConfirmation = false
                    }
                    else -> {
                        auctionPurchaseWorkflow.resetRequest()
                        blockedPurchaseItems += itemId
                        message("Auction purchase paused because a menu appeared outside the builder's Auction House transaction.", true)
                        finalizeAuctionRequest(itemId, auctionPurchaseWorkflow.progress())
                        return true
                    }
                }
            }
            val afterTick = auctionPurchaseWorkflow.tick(frame, auctionHost)
            if (afterTick.status != AuctionPurchaseStatus.RUNNING) finishAuctionRequestIfReady(player, frame, itemId, afterTick)
            return true
        }
        if (frame != null && progress.status != AuctionPurchaseStatus.RUNNING) {
            finishAuctionRequestIfReady(player, frame, itemId, progress)
            return true
        }
        if (frame == null && progress.status != AuctionPurchaseStatus.RUNNING) {
            finalizeAuctionRequest(itemId, progress)
            return true
        }
        return true
    }

    private fun serviceSchematicSale(
        player: LocalPlayer,
        world: ClientLevel,
        connection: ClientPacketListener,
        capturedFrame: AuctionPurchaseFrame?,
    ): Boolean {
        val active = sellWorkflow.status() != DonutSellStatus.IDLE
        if (!sellLeftovers) {
            if (!active) return false
            val frame = capturedFrame ?: captureAuctionFrame(player, world, connection) ?: return true
            val sellFrame = captureSellFrame(frame, player)
            val effect = sellWorkflow.tick(sellFrame)
            if (effect != null) sellWorkflow.reportDispatch(effect.id, false, sellFrame.nowNanos)
            val status = sellWorkflow.status()
            if (status in setOf(DonutSellStatus.WAITING_OFFER_ACK, DonutSellStatus.WAITING_SALE_ACK, DonutSellStatus.WAITING_MENU)) {
                return true
            }
            val menu = sellFrame.menu
            if (menu != null && menu.menuId in ownedSellMenus) {
                if (dispatchOwnedSellClose(frame, menu.menuId, menu.screenIdentity)) {
                    sellFinishedForRun = true
                    nextSellTick = tick + 200L
                }
                return true
            }
            if (status !in setOf(DonutSellStatus.IDLE, DonutSellStatus.COMPLETE, DonutSellStatus.FAILED, DonutSellStatus.UNCERTAIN, DonutSellStatus.PARTIAL)) return true
            return true
        }
        if (!buildFullyComplete || phase != SchematicPhase.COMPLETE || activePurchaseItemId != null) return active
        val frame = capturedFrame ?: captureAuctionFrame(player, world, connection) ?: return false
        if (sellWorkflow.status() == DonutSellStatus.IDLE) {
            if (sellFinishedForRun || tick < nextSellTick || frame.snapshot.menu != null) return false
            if (!startSaleCycle(frame)) {
                sellFinishedForRun = true
                nextSellTick = tick + 200L
                return false
            }
        }
        val sellFrame = captureSellFrame(frame, player)
        val effect = sellWorkflow.tick(sellFrame)
        if (effect != null) {
            val accepted = dispatchSchematicSellEffect(frame, sellFrame, effect)
            sellWorkflow.reportDispatch(effect.id, accepted, sellFrame.nowNanos)
            if (!accepted && effect !is DonutSellEffect.OpenSell) {
                message("A guarded leftover sale action was not sent; the sell workflow will recheck its owned menu state.", true)
            }
            return true
        }
        when (sellWorkflow.status()) {
            DonutSellStatus.COMPLETE -> {
                recordSellCycleTotals()
                val menu = sellFrame.menu
                if (menu != null && menu.menuId in ownedSellMenus) {
                    if (dispatchOwnedSellClose(frame, menu.menuId, menu.screenIdentity)) {
                        sellCloseRequested = true
                        return true
                    }
                    return true
                }
                sellFinishedForRun = true
                sellTerminalHandled = false
                nextSellTick = tick + 200L
                message(sellSummary("complete"), false)
                return false
            }
            DonutSellStatus.PARTIAL -> {
                recordSellCycleTotals()
                if (sellPartialReturnVerified) {
                    if (!sellLeftovers || sellCyclesStarted >= MAX_SELL_CYCLES || tick < nextSellTick) {
                        sellFinishedForRun = true
                        sellTerminalHandled = false
                        nextSellTick = tick + 200L
                        message(sellSummary("partial; remaining stacks were returned to inventory and left unsold"), true)
                        return false
                    }
                    if (!startSaleCycle(frame)) {
                        sellFinishedForRun = true
                        sellTerminalHandled = false
                        nextSellTick = tick + 200L
                        message(sellSummary("partial; no further whole acquired stacks could be safely offered"), true)
                        return false
                    }
                    sellPartialReturnVerified = false
                    return true
                }
                if (completePartialSellReturn(frame, player, sellFrame)) {
                    sellPartialReturnVerified = true
                    sellTerminalHandled = false
                    nextSellTick = tick + 200L
                    return false
                }
                return true
            }
            DonutSellStatus.FAILED -> {
                recordSellCycleTotals()
                val menu = sellFrame.menu
                if (menu != null && menu.menuId in ownedSellMenus) {
                    if (dispatchOwnedSellClose(frame, menu.menuId, menu.screenIdentity)) return true
                    return true
                }
                if (sellCyclesStarted < MAX_SELL_CYCLES && candidatesRemainForSale(player) &&
                    tick >= nextSellTick) {
                    sellTerminalHandled = false
                    return true
                }
                sellFinishedForRun = true
                nextSellTick = tick + 200L
                message("Leftover sale stopped safely: ${sellWorkflow.status()}.", true)
                return false
            }
            DonutSellStatus.UNCERTAIN -> {
                recordSellCycleTotals()
                val menu = sellFrame.menu
                if (menu != null && menu.menuId in ownedSellMenus) {
                    if (dispatchOwnedSellClose(frame, menu.menuId, menu.screenIdentity)) return true
                    return true
                }
                sellFinishedForRun = true
                nextSellTick = tick + 200L
                message(sellSummary("uncertain; no automatic retry was sent"), true)
                return false
            }
            DonutSellStatus.IDLE -> return false
            else -> return true
        }
    }

    private fun startSaleCycle(frame: AuctionPurchaseFrame): Boolean {
        val player = mc.player ?: return false
        val current = (0..35).mapNotNull { inventoryIndex ->
            val menuSlot = inventoryMenuSlot(inventoryIndex)
            val value = latestServerInventoryBySlot[menuSlot] ?: return@mapNotNull null
            if (value.count <= 0 || value.itemId.isBlank()) null else SchematicSurplusRules.SlotStack(inventoryIndex, value)
        }
        val remainingAcquired = acquiredSurplusByIdentity.mapValues { (identity, acquired) ->
            (acquired - (soldSurplusByIdentity[identity] ?: 0)).coerceAtLeast(0)
        }.filterValues { it > 0 }
        val eligible = SchematicSurplusRules.saleEligibleSlots(current, runStartStackCounts, remainingAcquired)
        if (eligible.isEmpty()) {
            if (acquiredSurplusByIdentity.isNotEmpty()) message("No whole acquired surplus stacks remain eligible for sale.", false)
            return false
        }
        val eligibleStacks = current.filter { it.slot in eligible.keys }
        val unambiguousItemIds = eligibleStacks.groupBy { it.value.itemId }.filterValues { stacks ->
            stacks.map { SchematicSurplusRules.stackIdentity(it.value) }.distinct().size == 1
        }.keys
        val candidates = eligibleStacks.filter { it.value.itemId in unambiguousItemIds }
            .sortedBy { it.slot }
            .map { DonutSellCandidate(it.slot, it.value) }
        if (candidates.isEmpty()) {
            message("Acquired surplus includes same-item variants the sell menu cannot attribute safely; they were kept.", true)
            return false
        }
        val request = DonutSellRequest(
            runId = sellRunId.coerceAtLeast(1L),
            session = frame.snapshot.session,
            candidates = candidates,
            sellItemSlots = SELL_ITEM_SLOTS,
            confirmationSlot = SELL_CONFIRM_SLOT,
            gridIdentityMode = DonutSellGridIdentityMode.ITEM_TYPE_ONLY,
        )
        if (!sellWorkflow.start(request, System.nanoTime())) {
            message("The previous sell request is still awaiting a terminal server outcome; it will not be repeated.", true)
            sellFinishedForRun = true
            return false
        }
        sellCyclesStarted++
        sellCommandPending = false
        sellCloseRequested = false
        sellTerminalHandled = false
        pendingSellOffer = null
        pendingSellConfirmation = null
        pendingSellReturn = null
        message("Selling ${candidates.size} whole acquired surplus stack(s) through the acknowledged sell menu.", false)
        return true
    }

    private fun candidatesRemainForSale(player: LocalPlayer): Boolean {
        val current = (0..35).mapNotNull { inventoryIndex ->
            val menuSlot = inventoryMenuSlot(inventoryIndex)
            val value = latestServerInventoryBySlot[menuSlot] ?: return@mapNotNull null
            if (value.count <= 0 || value.itemId.isBlank()) null else SchematicSurplusRules.SlotStack(inventoryIndex, value)
        }
        val remainingAcquired = acquiredSurplusByIdentity.mapValues { (identity, acquired) ->
            (acquired - (soldSurplusByIdentity[identity] ?: 0)).coerceAtLeast(0)
        }
        return SchematicSurplusRules.saleEligibleSlots(current, runStartStackCounts, remainingAcquired).isNotEmpty()
    }

    private fun captureSellFrame(frame: AuctionPurchaseFrame, player: LocalPlayer): DonutSellFrame {
        val menu = frame.snapshot.menu
        val nativeMenu = player.containerMenu
        val nativeScreen = mc.gui.screen() as? AbstractContainerScreen<*>
        val isSellMenu = menu != null && nativeScreen != null &&
            MenuClassifier.classify(menu) == MenuKind.SELL && nativeMenu !== player.inventoryMenu
        val sellSlots = if (isSellMenu) nativeMenu.slots.asSequence()
            .filter { it.index != SELL_CONFIRM_SLOT && it.container !== player.inventory }
            .map { it.index }.toSet() else emptySet()
        val playerSlots = if (menu == null) emptyMap() else frame.activeInventorySlotMap.mapNotNull { (activeSlot, inventoryMenuSlot) ->
            inventoryIndexFromMenuSlot(inventoryMenuSlot)?.let { it to activeSlot }
        }.toMap()
        return DonutSellFrame(
            session = frame.snapshot.session,
            nowNanos = System.nanoTime(),
            canIssueCommand = mc.gui.screen() == null && nativeMenu === player.inventoryMenu &&
                nativeMenu.containerId == player.inventoryMenu.containerId && nativeMenu.carried.isEmpty,
            menu = menu,
            menuMapEpoch = frame.menuSlotMapEpoch,
            menuOpenAcknowledged = menu?.menuId?.let { id ->
                isSellMenu && id in ownedSellMenus && auctionAcknowledgedMenuEpochs[id] == frame.menuSlotMapEpoch
            } == true,
            playerInventoryMenuSlots = playerSlots,
            sellItemSlots = sellSlots,
            confirmationSlot = if (isSellMenu && menu?.slot(SELL_CONFIRM_SLOT) != null) SELL_CONFIRM_SLOT else null,
        )
    }

    private fun dispatchSchematicSellEffect(
        frame: AuctionPurchaseFrame,
        sellFrame: DonutSellFrame,
        effect: DonutSellEffect,
    ): Boolean {
        val snapshot = frame.snapshot
        if (effect.session != snapshot.session || auctionCommittedTick == snapshot.tick) return false
        val planned: PlannedEffect
        val ticket: ActionTicket
        when (effect) {
            is DonutSellEffect.OpenSell -> {
                if (!sellFrame.canIssueCommand || snapshot.menu != null) return false
                planned = PlannedEffect.Command("sell")
                ticket = ActionTicket(++auctionTicketId, effect.session, snapshot.tick, planned)
            }
            is DonutSellEffect.OfferStack -> {
                if (effect.menuId !in ownedSellMenus || effect.menuMapEpoch != frame.menuSlotMapEpoch) return false
                val menu = snapshot.menu ?: return false
                val before = readSlotValues(menu.slots.associate { it.index to inventorySlotValue(it) }, SELL_ITEM_SLOTS)
                    ?: return false
                if (!sellDispatchEvidence(frame, effect.menuId, effect.menuMapEpoch, effect.baselineStateId,
                        effect.menuSlot, effect.expectedStack, before)) return false
                val click = PlannedEffect.Click(effect.menuId, effect.menuSlot, 0, quickMove = true)
                planned = click
                ticket = ActionTicket(++auctionTicketId, effect.session, snapshot.tick, click,
                    screenIdentity = effect.screenIdentity, menuId = effect.menuId,
                    slotIndex = effect.menuSlot, expectedSlotFingerprint = effect.expectedStack.fingerprint)
            }
            is DonutSellEffect.ConfirmSale -> {
                if (effect.menuId !in ownedSellMenus || effect.menuMapEpoch != frame.menuSlotMapEpoch) return false
                val menu = snapshot.menu ?: return false
                val liveButton = menu.slot(effect.slot)?.let(::inventorySlotValue) ?: return false
                if (liveButton != effect.expectedButton ||
                    !sellDispatchEvidence(frame, effect.menuId, effect.menuMapEpoch, effect.baselineStateId,
                        effect.slot, effect.expectedButton, null)) return false
                val click = PlannedEffect.Click(effect.menuId, effect.slot, 0, quickMove = false)
                planned = click
                ticket = ActionTicket(++auctionTicketId, effect.session, snapshot.tick, click,
                    screenIdentity = effect.screenIdentity, menuId = effect.menuId,
                    slotIndex = effect.slot, expectedSlotFingerprint = effect.expectedButton.fingerprint)
            }
        }
        if (!auctionActionGate.offer(ticket)) return false
        val acceptedTicket = auctionActionGate.consume(snapshot)
        if (acceptedTicket?.id != ticket.id || !dispatchSchematicSellNative(frame, effect, planned)) return false
        auctionCommittedTick = snapshot.tick
        when (effect) {
            is DonutSellEffect.OpenSell -> sellCommandPending = true
            is DonutSellEffect.OfferStack -> {
                val before = readSlotValues(snapshot.menu!!.slots.associate { it.index to inventorySlotValue(it) }, SELL_ITEM_SLOTS) ?: return false
                pendingSellOffer = SchematicSellOfferPending(effect, before, currentInventoryArrivalOrdinal())
            }
            is DonutSellEffect.ConfirmSale -> {
                val before = readSlotValues(snapshot.menu!!.slots.associate { it.index to inventorySlotValue(it) }, SELL_ITEM_SLOTS) ?: return false
                pendingSellConfirmation = SchematicSellConfirmPending(effect, before, currentInventoryArrivalOrdinal())
            }
        }
        return true
    }

    private fun sellDispatchEvidence(
        frame: AuctionPurchaseFrame,
        menuId: Int,
        mapEpoch: Long,
        baselineStateId: Int,
        sourceSlot: Int,
        expectedSource: InventorySlotValue,
        expectedGrid: Map<Int, InventorySlotValue>?,
    ): Boolean {
        val menu = frame.snapshot.menu ?: return false
        val evidence = serverMenuStates[menuId to mapEpoch] ?: return false
        if (evidence.fullContentsOrdinal <= 0L || !evidence.cursorKnown || evidence.cursor.count != 0 ||
            menu.menuId != menuId || menu.stateId != baselineStateId || !menu.cursorEmpty ||
            menuId !in ownedSellMenus || frame.menuSlotMapEpoch != mapEpoch) return false
        val expectedLive = menu.slot(sourceSlot)?.let(::inventorySlotValue) ?: return false
        if (expectedLive != expectedSource || evidence.slots[sourceSlot] != expectedSource) return false
        if (expectedGrid != null && (expectedGrid.keys != SELL_ITEM_SLOTS ||
                expectedGrid.any { (slot, value) -> evidence.slots[slot] != value })) return false
        return true
    }

    private fun dispatchSchematicSellNative(
        frame: AuctionPurchaseFrame,
        effect: DonutSellEffect,
        planned: PlannedEffect,
    ): Boolean {
        val player = mc.player ?: return false
        val world = mc.level ?: return false
        val connection = mc.connection ?: return false
        if (player !== activePlayer || world !== activeWorld || connection !== activeConnection ||
            System.identityHashCode(player).toLong() != frame.snapshot.session.player ||
            System.identityHashCode(world).toLong() != frame.snapshot.session.world ||
            System.identityHashCode(connection).toLong() != frame.snapshot.session.connection) return false
        return when (effect) {
            is DonutSellEffect.OpenSell -> {
                if (mc.gui.screen() != null || player.containerMenu !== player.inventoryMenu ||
                    !player.containerMenu.carried.isEmpty) false
                else {
                    connection.sendCommand("sell")
                    true
                }
            }
            is DonutSellEffect.OfferStack, is DonutSellEffect.ConfirmSale -> {
                val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return false
                val menu = player.containerMenu
                val click = planned as? PlannedEffect.Click ?: return false
                val screenId = when (effect) {
                    is DonutSellEffect.OfferStack -> effect.screenIdentity
                    is DonutSellEffect.ConfirmSale -> effect.screenIdentity
                    else -> return false
                }
                val menuId = when (effect) {
                    is DonutSellEffect.OfferStack -> effect.menuId
                    is DonutSellEffect.ConfirmSale -> effect.menuId
                    else -> return false
                }
                val slot = when (effect) {
                    is DonutSellEffect.OfferStack -> effect.menuSlot
                    is DonutSellEffect.ConfirmSale -> effect.slot
                    else -> return false
                }
                val expected = when (effect) {
                    is DonutSellEffect.OfferStack -> effect.expectedStack
                    is DonutSellEffect.ConfirmSale -> effect.expectedButton
                    else -> return false
                }
                if (screen.identityHash() != screenId || menu.containerId != menuId || mc.gui.screen() !== screen ||
                    menu.carried.isEmpty.not() || slot !in menu.slots.indices ||
                    InventorySlotValue.fromStack(menu.getSlot(slot).item) != expected ||
                    MenuClassifier.classify(frame.snapshot.menu ?: return false) != MenuKind.SELL) return false
                val input = when (effect) {
                    is DonutSellEffect.OfferStack -> ContainerInput.QUICK_MOVE
                    is DonutSellEffect.ConfirmSale -> ContainerInput.PICKUP
                    else -> return false
                }
                (mc.gameMode ?: return false).handleContainerInput(menu.containerId, slot, 0, input, player)
                true
            }
        }
    }

    private fun dispatchOwnedSellClose(frame: AuctionPurchaseFrame, menuId: Int, screenIdentity: Long): Boolean {
        val menu = frame.snapshot.menu ?: return false
        if (menuId !in ownedSellMenus || menu.menuId != menuId || menu.screenIdentity != screenIdentity) return false
        val effect = PlannedEffect.CloseMenu(menuId)
        val ticket = ActionTicket(++auctionTicketId, frame.snapshot.session, frame.snapshot.tick, effect,
            screenIdentity = screenIdentity, menuId = menuId)
        if (auctionCommittedTick == frame.snapshot.tick || !auctionActionGate.offer(ticket)) return false
        val accepted = auctionActionGate.consume(frame.snapshot)
        if (accepted?.id != ticket.id) return false
        val player = mc.player ?: return false
        val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return false
        if (player.containerMenu.containerId != menuId || screen.identityHash() != screenIdentity || mc.gui.screen() !== screen) return false
        player.closeContainer()
        auctionCommittedTick = frame.snapshot.tick
        ownedSellMenus.remove(menuId)
        ownedSellMenuScreens.remove(menuId)
        sellCommandPending = false
        return true
    }

    private fun recordSellCycleTotals() {
        if (sellTerminalHandled) return
        sellWorkflow.totals().sold.forEach { (identity, count) ->
            soldSurplusByIdentity[identity] = (soldSurplusByIdentity[identity] ?: 0) + count
        }
        sellTerminalHandled = true
    }

    private fun sellSummary(outcome: String): String {
        val soldCount = soldSurplusByIdentity.values.sum()
        val remaining = acquiredSurplusByIdentity.mapValues { (identity, acquired) ->
            (acquired - (soldSurplusByIdentity[identity] ?: 0)).coerceAtLeast(0)
        }.values.sum()
        return "Leftover sale $outcome: server acknowledged $soldCount acquired item(s) sold; $remaining acquired item(s) remain reserved."
    }

    private fun completePartialSellReturn(
        frame: AuctionPurchaseFrame,
        player: LocalPlayer,
        sellFrame: DonutSellFrame,
    ): Boolean {
        var capture = pendingSellReturn
        if (capture == null) {
            val menu = sellFrame.menu
            if (menu == null || menu.menuId !in ownedSellMenus || menu.screenIdentity <= 0L) return false
            val evidence = serverMenuStates[menu.menuId to sellFrame.menuMapEpoch] ?: return false
            if (evidence.fullContentsOrdinal <= 0L || !evidence.cursorKnown || evidence.cursor.count != 0) return false
            val before = canonicalInventoryFromActiveMenu(frame, evidence) ?: return false
            val packetOrdinal = inventoryAckChannel.currentArrivalOrdinal(inventoryGeneration, inventoryConnectionId) ?: return false
            if (packetOrdinal <= 0L) return false
            capture = SchematicSellReturnCapture(
                runId = sellRunId,
                session = sellFrame.session,
                sellMenuId = menu.menuId,
                sellScreenIdentity = menu.screenIdentity,
                menuMapEpoch = sellFrame.menuMapEpoch,
                inventoryBeforeCapturedAtNanos = sellFrame.nowNanos,
                inventoryBeforePacketOrdinal = packetOrdinal,
                inventoryMapEpoch = inventoryGeneration,
                inventoryBefore = before,
                menuClosedPacketOrdinal = packetOrdinal,
            )
            pendingSellReturn = capture
        }
        if (capture.closeDispatchedAtNanos == 0L) {
            val activeSellMenu = sellFrame.menu ?: return false
            if (activeSellMenu.menuId != capture.sellMenuId || activeSellMenu.screenIdentity != capture.sellScreenIdentity) return false
            if (!dispatchOwnedSellClose(frame, capture.sellMenuId, capture.sellScreenIdentity)) return false
            capture.closeDispatchedAtNanos = sellFrame.nowNanos
            capture.menuClosedPacketOrdinal = inventoryAckChannel.currentArrivalOrdinal(inventoryGeneration, inventoryConnectionId)
                ?: capture.inventoryBeforePacketOrdinal
        }
        val currentMenu = player.containerMenu
        if (currentMenu !== player.inventoryMenu || mc.gui.screen() != null) return false
        if (capture.menuClosedAtNanos == 0L) capture.menuClosedAtNanos = System.nanoTime()
        val afterOrdinal = inventoryFullSyncOrdinal
        if (inventoryFullSyncMenuId != player.inventoryMenu.containerId || afterOrdinal <= capture.menuClosedPacketOrdinal) return false
        val after = canonicalInventoryFromPlayerMenu() ?: return false
        if (!liveInventoryMatches(after, player)) return false
        val now = System.nanoTime()
        val status = sellWorkflow.acknowledgePartialReturn(DonutSellPartialReturnAck(
            runId = capture.runId,
            session = capture.session,
            sellMenuId = capture.sellMenuId,
            sellScreenIdentity = capture.sellScreenIdentity,
            menuMapEpoch = capture.menuMapEpoch,
            observedAtNanos = now,
            inventoryBeforeCapturedAtNanos = capture.inventoryBeforeCapturedAtNanos,
            menuClosedAtNanos = capture.menuClosedAtNanos,
            inventoryAfterCapturedAtNanos = now,
            inventoryBeforePacketOrdinal = capture.inventoryBeforePacketOrdinal,
            menuClosedPacketOrdinal = capture.menuClosedPacketOrdinal,
            inventoryAfterPacketOrdinal = afterOrdinal,
            inventoryMapEpochBeforeClose = capture.inventoryMapEpoch,
            inventoryMapEpochAfterClose = inventoryGeneration,
            inventoryBeforeMenuId = capture.sellMenuId,
            inventoryAfterMenuId = player.inventoryMenu.containerId,
            inventoryBefore = capture.inventoryBefore,
            inventoryAfter = after,
            menuClosed = true,
            cursorEmpty = player.inventoryMenu.carried.isEmpty,
            localReconciled = true,
        ))
        if (status != DonutSellStatus.PARTIAL) return false
        pendingSellReturn = null
        ownedSellMenus.remove(capture.sellMenuId)
        return true
    }

    private fun canonicalInventoryFromActiveMenu(
        frame: AuctionPurchaseFrame,
        evidence: SchematicServerMenuState,
    ): Map<Int, InventorySlotValue>? {
        if (frame.activeInventorySlotMap.size != 36) return null
        val result = linkedMapOf<Int, InventorySlotValue>()
        for (inventoryIndex in 0..35) {
            val inventorySlot = inventoryMenuSlot(inventoryIndex)
            val activeSlot = frame.activeInventorySlotMap.entries.firstOrNull { it.value == inventorySlot }?.key ?: return null
            result[inventoryIndex] = evidence.slots[activeSlot] ?: return null
        }
        return result
    }

    private fun canonicalInventoryFromPlayerMenu(): Map<Int, InventorySlotValue>? {
        val result = linkedMapOf<Int, InventorySlotValue>()
        for (inventoryIndex in 0..35) {
            result[inventoryIndex] = latestServerInventoryBySlot[inventoryMenuSlot(inventoryIndex)] ?: return null
        }
        return result
    }

    private fun liveInventoryMatches(values: Map<Int, InventorySlotValue>, player: LocalPlayer): Boolean =
        values.keys == (0..35).toSet() && (0..35).all { index ->
            InventorySlotValue.fromStack(player.inventory.getItem(index)) == values[index]
        }

    private fun inventoryMenuSlot(inventoryIndex: Int): Int = if (inventoryIndex < 9) 36 + inventoryIndex else inventoryIndex

    private fun currentInventoryArrivalOrdinal(): Long =
        inventoryAckChannel.currentArrivalOrdinal(inventoryGeneration, inventoryConnectionId) ?: inventorySyncOrdinal

    private fun inventoryIndexFromMenuSlot(menuSlot: Int): Int? = when (menuSlot) {
        in 9..35 -> menuSlot
        in 36..44 -> menuSlot - 36
        else -> null
    }

    private fun finishAuctionRequestIfReady(
        player: LocalPlayer,
        frame: AuctionPurchaseFrame,
        itemId: String,
        progress: AuctionPurchaseProgress,
    ) {
        val menu = frame.snapshot.menu
        if (menu != null && menu.menuId in ownedAuctionMenus) {
            val closed = dispatchAuctionEffect(
                frame,
                AuctionPurchaseEffect.CloseMenu(frame.snapshot.session, menu.menuId, menu.screenIdentity),
            )
            if (closed) return
        }
        if (progress.status == AuctionPurchaseStatus.RUNNING) return
        finalizeAuctionRequest(itemId, progress)
    }

    private fun finalizeAuctionRequest(itemId: String, progress: AuctionPurchaseProgress) {
        trackAcknowledgedPurchaseGrowth(progress)
        if (progress.acquiredAmount > 0 && progress.requestedItemId.isNotBlank()) {
            val purchasedId = progress.requestedItemId
            acknowledgedPurchaseCounts[purchasedId] =
                (acknowledgedPurchaseCounts[purchasedId] ?: 0) + progress.acquiredAmount
        }
        when (progress.status) {
            AuctionPurchaseStatus.COMPLETE -> message(
                "Auction House acknowledged ${progress.acquiredAmount} ${progress.requestedItemId} for ${money(progress.confirmedSpend)}.", false,
            )
            AuctionPurchaseStatus.PARTIAL -> {
                if (progress.acquiredAmount == 0) blockedPurchaseItems += itemId
                message(
                    "Auction House acknowledged ${progress.acquiredAmount} of ${progress.requestedAmount}; the remaining shortage will be recalculated.",
                    progress.acquiredAmount == 0,
                )
            }
            AuctionPurchaseStatus.UNAVAILABLE -> {
                blockedPurchaseItems += itemId
                message("No eligible Auction House listing appeared for $itemId within the bounded refreshes.", true)
            }
            AuctionPurchaseStatus.BUDGET_EXHAUSTED -> {
                blockedPurchaseItems += itemId
                message("Auction House purchase for $itemId stopped at the configured spend cap.", true)
            }
            AuctionPurchaseStatus.INSUFFICIENT_FUNDS -> {
                blockedPurchaseItems += itemId
                message("Auction House purchase for $itemId stopped because the server reported insufficient funds.", true)
            }
            AuctionPurchaseStatus.UNCERTAIN -> {
                blockedPurchaseItems += itemId
                message("Auction House result for $itemId is uncertain; the purchase will not be repeated.", true)
            }
            AuctionPurchaseStatus.FAILED -> {
                blockedPurchaseItems += itemId
                message("Auction House purchase for $itemId stopped: ${progress.reason}.", true)
            }
            AuctionPurchaseStatus.IDLE, AuctionPurchaseStatus.RUNNING -> Unit
        }
        auctionPurchaseWorkflow.resetRequest()
        auctionActionGate.clear()
        activePurchaseItemId = null
        activePurchaseAllowedItems = emptySet()
        activePurchaseBaselineInventory = emptyMap()
        auctionCommandPending = false
        awaitingAuctionConfirmation = false
        ownedAuctionMenus.clear()
        clearAuctionReceipts(resetCounter = false)
    }

    private fun trackAcknowledgedPurchaseGrowth(progress: AuctionPurchaseProgress) {
        val baseline = activePurchaseBaselineInventory
        if (progress.acquiredAmount <= 0 || baseline.isEmpty()) return
        val growth = SchematicSurplusRules.identifyAcknowledgedPurchaseGrowth(
            baseline,
            serverInventorySnapshot(),
            activePurchaseAllowedItems,
            progress.acquiredAmount,
        )
        if (growth == null) {
            message("The purchase was acknowledged, but its full-stack inventory growth was ambiguous; it will not be listed as a sellable leftover.", true)
            return
        }
        for ((identity, count) in growth) {
            val itemId = serverInventorySnapshot().values.firstOrNull { SchematicSurplusRules.stackIdentity(it) == identity }?.itemId
                ?: continue
            acquiredSurplusByIdentity[identity] = (acquiredSurplusByIdentity[identity] ?: 0) + count
            acquiredSurplusItemIds[identity] = itemId
        }
    }

    private fun money(amount: BigDecimal): String = "$" + amount.stripTrailingZeros().toPlainString()

    private fun captureAuctionFrame(
        player: LocalPlayer,
        world: ClientLevel,
        connection: ClientPacketListener,
    ): AuctionPurchaseFrame? {
        if (mc.player !== player || mc.level !== world || mc.connection !== connection) return null
        val screen = mc.gui.screen()
        val liveMenu = player.containerMenu
        val inventoryMenu = player.inventoryMenu
        val menu = if (screen is AbstractContainerScreen<*>) {
            snapshotMenu(liveMenu, System.identityHashCode(screen).toLong(), screen.title.string)
        } else null
        val inventorySnapshot = snapshotMenu(
            inventoryMenu,
            System.identityHashCode(inventoryMenu).toLong(),
            "Player Inventory",
        )
        var activeSlotMap = if (menu == null) emptyMap() else liveMenu.slots.mapNotNull { activeSlot ->
            if (activeSlot.container !== player.inventory) return@mapNotNull null
            val inventorySlot = inventoryMenu.slots.firstOrNull { candidate ->
                candidate.container === player.inventory && candidate.containerSlot == activeSlot.containerSlot
            } ?: return@mapNotNull null
            activeSlot.index to inventorySlot.index
        }.toMap()

        val menuId = menu?.menuId
        val mapEpoch = menuId?.let { id -> auctionMenuEpochs.computeIfAbsent(id) { auctionEpochCounter.incrementAndGet() } }
            ?: Long.MIN_VALUE
        if (menuId != null && mapEpoch != Long.MIN_VALUE) {
            val key = menuId to mapEpoch
            val signature = activeSlotMap.toSortedMap().entries.joinToString("|") { "${it.key}>${it.value}" }
            val previous = auctionLayoutSignatures[key]
            if (previous == null) auctionLayoutSignatures[key] = signature
            else if (previous != signature) activeSlotMap = emptyMap()
            if (activeSlotMap.size == 36) {
                menuInventoryMappings[key] = activeSlotMap.toMap()
                while (menuInventoryMappings.size > 8) menuInventoryMappings.remove(menuInventoryMappings.keys.first())
            }
            if (auctionAcknowledgedMenuEpochs[menuId] == mapEpoch && menu != null) {
                when {
                    sellCommandPending && MenuClassifier.classify(menu) == MenuKind.SELL -> {
                        ownedSellMenus += menuId
                        ownedSellMenuScreens[menuId] = menu.screenIdentity
                        sellCommandPending = false
                    }
                    auctionCommandPending && MenuClassifier.classify(menu) == MenuKind.AUCTION -> {
                        ownedAuctionMenus += menuId
                        auctionCommandPending = false
                    }
                    awaitingAuctionConfirmation && MenuClassifier.classify(menu) == MenuKind.CONFIRMATION -> {
                        ownedAuctionMenus += menuId
                        awaitingAuctionConfirmation = false
                    }
                }
            }
        }
        val containerSlotCount = if (menu == null) 0 else activeSlotMap.keys.minOrNull() ?: 0
        val currentMenuEpoch = menuId?.let { auctionMenuEpochs[it] } ?: Long.MIN_VALUE
        val acknowledgedMenuId = menuId?.takeIf { auctionAcknowledgedMenuEpochs[it] == currentMenuEpoch }
        val contentEpoch = if (menuId == null || currentMenuEpoch == Long.MIN_VALUE) 0L
            else auctionMenuContentEpochs[menuId to currentMenuEpoch] ?: 0L
        val session = SessionStamp(
            generation = inventoryGeneration,
            connection = System.identityHashCode(connection).toLong(),
            world = System.identityHashCode(world).toLong(),
            player = System.identityHashCode(player).toLong(),
        )
        drainAuctionChatReceipts(session)
        val inventoryFingerprint = (0..35).joinToString("|") { index ->
            InventorySlotValue.fromStack(player.inventory.getItem(index)).fingerprint
        }
        val dialog = DonutDialogSnapshotCapture.capture(screen)
        val snapshot = AutomationSnapshot(
            session = session,
            tick = tick,
            menu = menu,
            inventoryFingerprint = inventoryFingerprint,
            dialog = dialog,
            playerInventoryMenu = inventorySnapshot,
        )
        val nextPage = menu?.slots?.firstOrNull { slot ->
            val label = slot.searchableText
            label.contains("next page") || label == "next" || label == "next »" || label == "next ›"
        }?.let { slot ->
            AuctionPurchasePageAction(menu.menuId, menu.screenIdentity, slot.index, slot.fingerprint)
        }
        val confirmation = menu?.takeIf { MenuClassifier.classify(it) == MenuKind.CONFIRMATION }
            ?.let(::confirmationEvidence)
        return AuctionPurchaseFrame(
            snapshot = snapshot,
            playerName = player.gameProfile.name,
            acknowledgedMenuId = acknowledgedMenuId,
            activeInventorySlotMap = activeSlotMap.toMap(),
            menuSlotMapEpoch = currentMenuEpoch,
            menuContentEpoch = contentEpoch,
            containerSlotCount = containerSlotCount,
            nextPageAction = nextPage,
            confirmation = confirmation,
            transactionReceipts = auctionReceipts.filter { it.session == session },
        )
    }

    private fun drainAuctionChatReceipts(session: SessionStamp) {
        while (true) {
            val queued = incomingAuctionReceipts.poll() ?: break
            if (queued.session != session || queued.attemptId <= 0L) continue
            val parsed = SchematicAuctionReceiptParser.parse(queued.text) ?: continue
            val id = auctionReceiptIds.incrementAndGet().let { value ->
                if (value > 0L) value else {
                    auctionReceiptIds.set(1L)
                    1L
                }
            }
            val kind = when (parsed.outcome) {
                SchematicAuctionOutcome.PURCHASED -> AuctionPurchaseReceiptKind.PURCHASED
                SchematicAuctionOutcome.LISTING_GONE -> AuctionPurchaseReceiptKind.LISTING_GONE
                SchematicAuctionOutcome.OWN_LISTING -> AuctionPurchaseReceiptKind.OWN_LISTING
                SchematicAuctionOutcome.INSUFFICIENT_FUNDS -> AuctionPurchaseReceiptKind.INSUFFICIENT_FUNDS
            }
            auctionReceipts += AuctionPurchaseReceipt(
                receiptId = id,
                session = queued.session,
                attemptId = queued.attemptId,
                kind = kind,
                reportedName = parsed.reportedName,
                count = parsed.count,
                totalPrice = parsed.totalPrice,
            )
            if (auctionReceipts.size > 16) auctionReceipts.removeAt(0)
        }
    }

    private fun snapshotMenu(
        menu: net.minecraft.world.inventory.AbstractContainerMenu,
        screenIdentity: Long,
        title: String,
    ): MenuSnapshot = MenuSnapshot(
        screenIdentity = screenIdentity,
        menuId = menu.containerId,
        title = title,
        slots = menu.slots.map(::snapshotAuctionSlot),
        cursorEmpty = menu.carried.isEmpty,
        stateId = menu.stateId,
        cursorStack = InventorySlotValue.fromStack(menu.carried),
    )

    private fun snapshotAuctionSlot(slot: net.minecraft.world.inventory.Slot): SlotSnapshot {
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

    private fun snapshotAuctionDialog(screen: net.minecraft.client.gui.screens.Screen?): DialogSnapshot? {
        if (screen == null || !screen.javaClass.simpleName.contains("DialogScreen")) return null
        val widgets = screen.children().mapIndexedNotNull { index, _ -> snapshotAuctionWidget(screen, index) }
        return if (widgets.isEmpty()) null else DialogSnapshot(
            System.identityHashCode(screen).toLong(), screen.title.string, widgets,
        )
    }

    private fun snapshotAuctionWidget(
        screen: net.minecraft.client.gui.screens.Screen,
        index: Int,
    ): DialogWidgetSnapshot? = when (val child = screen.children().getOrNull(index)) {
        is Button -> DialogWidgetSnapshot(index, "button", child.message.string)
        is EditBox -> DialogWidgetSnapshot(index, "input", child.message.string, child.value)
        else -> null
    }

    private fun confirmationEvidence(menu: MenuSnapshot): AuctionPurchaseConfirmation? {
        if (activePurchaseAllowedItems.isEmpty()) return null
        val products = menu.slots.filter { slot ->
            slot.count > 0 && MarketRules.safeItemId(slot.itemId) in activePurchaseAllowedItems
        }.distinctBy { listOf(it.itemId, it.count, it.procurementFingerprint, it.actualEnchantments.toSortedMap()) }
        val product = products.singleOrNull() ?: return null
        val actionSlots = menu.slots.filter { slot -> isPositiveConfirmationLabel(slot.searchableText) }
        val actionSlot = actionSlots.singleOrNull() ?: return null
        val price = confirmationTotalPrice(menu) ?: return null
        val action = AuctionPurchaseConfirmationAction.MenuSlot(
            menu.menuId, menu.screenIdentity, actionSlot.index, actionSlot.fingerprint,
        )
        val evidenceFingerprint = listOf(
            menu.menuId, menu.screenIdentity, product.fingerprint, price.toPlainString(), actionSlot.fingerprint,
        ).joinToString("|")
        return AuctionPurchaseConfirmation(
            itemId = product.itemId,
            count = product.count,
            enchantments = product.actualEnchantments,
            totalPrice = price,
            screenIdentity = menu.screenIdentity,
            menuId = menu.menuId,
            evidenceFingerprint = evidenceFingerprint,
            action = action,
            procurementFingerprint = product.procurementFingerprint,
        )
    }

    private fun isPositiveConfirmationLabel(value: String): Boolean {
        val label = value.lowercase()
        if ("cancel" in label || label == "no" || "go back" in label || "close" in label) return false
        return "buy" in label || "confirm" in label || label == "yes" || "purchase" in label
    }

    private fun confirmationTotalPrice(menu: MenuSnapshot): BigDecimal? {
        val amounts = menu.slots.flatMap { slot -> listOf(slot.name) + slot.lore }.mapNotNull { raw ->
            val line = raw.replace(Regex("§."), "").lowercase()
            if (listOf("balance", "seller", "listed by", "owner", "time left", "expires", "ends in").any(line::contains)) {
                return@mapNotNull null
            }
            val match = CONFIRMATION_PRICE.find(line) ?: return@mapNotNull null
            val amount = match.groupValues[1].replace(",", "").replace(Regex("\\s+"), "")
            val token = Regex("^([0-9]+(?:\\.[0-9]+)?)([kmb])?$", RegexOption.IGNORE_CASE).matchEntire(amount)
                ?: return@mapNotNull null
            val base = token.groupValues[1].toBigDecimalOrNull() ?: return@mapNotNull null
            val multiplier = when (token.groupValues[2].lowercase()) {
                "k" -> BigDecimal("1000")
                "m" -> BigDecimal("1000000")
                "b" -> BigDecimal("1000000000")
                else -> BigDecimal.ONE
            }
            base.multiply(multiplier).setScale(0, RoundingMode.HALF_UP).takeIf { it.signum() > 0 }
        }.distinctBy { it.stripTrailingZeros() }
        return amounts.singleOrNull()
    }

    private fun dispatchAuctionEffect(frame: AuctionPurchaseFrame, effect: AuctionPurchaseEffect): Boolean {
        val session = frame.snapshot.session
        val mapped: Pair<PlannedEffect, ActionTicket> = when (effect) {
            is AuctionPurchaseEffect.OpenSearch -> PlannedEffect.Command("ah ${effect.query}") to ActionTicket(
                ++auctionTicketId, effect.session, frame.snapshot.tick, PlannedEffect.Command("ah ${effect.query}"),
            )
            is AuctionPurchaseEffect.ClickListing -> {
                if (effect.menuId !in ownedAuctionMenus) return false
                val click = PlannedEffect.Click(effect.menuId, effect.slotIndex)
                click to ActionTicket(
                    ++auctionTicketId, effect.session, frame.snapshot.tick, click,
                    screenIdentity = effect.screenIdentity, menuId = effect.menuId,
                    slotIndex = effect.slotIndex, expectedSlotFingerprint = effect.expectedFingerprint,
                )
            }
            is AuctionPurchaseEffect.NextPage -> {
                if (effect.menuId !in ownedAuctionMenus) return false
                val click = PlannedEffect.Click(effect.menuId, effect.slotIndex)
                click to ActionTicket(
                    ++auctionTicketId, effect.session, frame.snapshot.tick, click,
                    screenIdentity = effect.screenIdentity, menuId = effect.menuId,
                    slotIndex = effect.slotIndex, expectedSlotFingerprint = effect.expectedFingerprint,
                )
            }
            is AuctionPurchaseEffect.ClickConfirmation -> when (val action = effect.action) {
                is AuctionPurchaseConfirmationAction.MenuSlot -> {
                    if (action.menuId !in ownedAuctionMenus) return false
                    val click = PlannedEffect.Click(action.menuId, action.slotIndex)
                    click to ActionTicket(
                        ++auctionTicketId, effect.session, frame.snapshot.tick, click,
                        screenIdentity = action.screenIdentity, menuId = action.menuId,
                        slotIndex = action.slotIndex, expectedSlotFingerprint = action.expectedFingerprint,
                    )
                }
                is AuctionPurchaseConfirmationAction.DialogWidget -> {
                    val press = PlannedEffect.DialogPress(action.widgetIndex, action.expectedFingerprint, action.expectedLabel)
                    press to ActionTicket(
                        ++auctionTicketId, effect.session, frame.snapshot.tick, press,
                        dialogIdentity = action.screenIdentity, dialogWidgetIndex = action.widgetIndex,
                        expectedDialogWidgetFingerprint = action.expectedFingerprint,
                    )
                }
            }
            is AuctionPurchaseEffect.CloseMenu -> {
                if (effect.menuId !in ownedAuctionMenus) return false
                val close = PlannedEffect.CloseMenu(effect.menuId)
                close to ActionTicket(
                    ++auctionTicketId, effect.session, frame.snapshot.tick, close,
                    screenIdentity = effect.screenIdentity, menuId = effect.menuId,
                )
            }
        }
        val (plannedEffect, ticket) = mapped
        if (ticket.session != session || auctionCommittedTick == frame.snapshot.tick || !auctionActionGate.offer(ticket)) return false
        val accepted = auctionActionGate.consume(frame.snapshot)
        if (accepted?.id != ticket.id || !dispatchAuctionNative(frame, effect, plannedEffect)) return false
        auctionCommittedTick = frame.snapshot.tick
        when (effect) {
            is AuctionPurchaseEffect.OpenSearch -> auctionCommandPending = true
            is AuctionPurchaseEffect.ClickListing -> {
                ownedAuctionMenus += effect.menuId
                awaitingAuctionConfirmation = true
            }
            is AuctionPurchaseEffect.CloseMenu -> ownedAuctionMenus -= effect.menuId
            else -> Unit
        }
        return true
    }

    private fun dispatchAuctionNative(
        frame: AuctionPurchaseFrame,
        effect: AuctionPurchaseEffect,
        planned: PlannedEffect,
    ): Boolean {
        val player = mc.player ?: return false
        val world = mc.level ?: return false
        val connection = mc.connection ?: return false
        if (System.identityHashCode(connection).toLong() != frame.snapshot.session.connection ||
            System.identityHashCode(world).toLong() != frame.snapshot.session.world ||
            System.identityHashCode(player).toLong() != frame.snapshot.session.player ||
            player !== activePlayer || world !== activeWorld || connection !== activeConnection) return false
        return when (effect) {
            is AuctionPurchaseEffect.OpenSearch -> {
                val query = MarketRules.safeCommandFragment(effect.query) ?: return false
                if (query != effect.query || query.length > 80 || query.any { it.isISOControl() || it == ';' } ||
                    mc.gui.screen() != null || player.containerMenu !== player.inventoryMenu ||
                    player.containerMenu.containerId != effect.previousMenuId) false
                else {
                    connection.sendCommand("ah $query")
                    true
                }
            }
            is AuctionPurchaseEffect.ClickListing,
            is AuctionPurchaseEffect.NextPage,
            is AuctionPurchaseEffect.CloseMenu,
            is AuctionPurchaseEffect.ClickConfirmation -> {
                when (planned) {
                    is PlannedEffect.Click -> {
                        val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return false
                        val menu = player.containerMenu
                        val expectedScreen = when (effect) {
                            is AuctionPurchaseEffect.ClickListing -> effect.screenIdentity
                            is AuctionPurchaseEffect.NextPage -> effect.screenIdentity
                            is AuctionPurchaseEffect.ClickConfirmation -> (effect.action as? AuctionPurchaseConfirmationAction.MenuSlot)?.screenIdentity
                            else -> null
                        } ?: return false
                        val expectedMenu = when (effect) {
                            is AuctionPurchaseEffect.ClickListing -> effect.menuId
                            is AuctionPurchaseEffect.NextPage -> effect.menuId
                            is AuctionPurchaseEffect.ClickConfirmation -> (effect.action as? AuctionPurchaseConfirmationAction.MenuSlot)?.menuId
                            else -> null
                        } ?: return false
                        val expectedSlot = when (effect) {
                            is AuctionPurchaseEffect.ClickListing -> effect.slotIndex to effect.expectedFingerprint
                            is AuctionPurchaseEffect.NextPage -> effect.slotIndex to effect.expectedFingerprint
                            is AuctionPurchaseEffect.ClickConfirmation -> (effect.action as? AuctionPurchaseConfirmationAction.MenuSlot)
                                ?.let { it.slotIndex to it.expectedFingerprint }
                            else -> null
                        } ?: return false
                        if (screen.identityHash() != expectedScreen || menu.containerId != expectedMenu ||
                            mc.gui.screen() !== screen || !menu.carried.isEmpty || expectedSlot.first !in menu.slots.indices ||
                            snapshotAuctionSlot(menu.getSlot(expectedSlot.first)).fingerprint != expectedSlot.second) return false
                        val gameMode = mc.gameMode ?: return false
                        if (effect is AuctionPurchaseEffect.ClickListing) {
                            activeAuctionReceiptAttempt = SchematicAuctionAttempt(effect.session, effect.attemptId)
                        }
                        gameMode.handleContainerInput(menu.containerId, expectedSlot.first, 0, ContainerInput.PICKUP, player)
                        true
                    }
                    is PlannedEffect.CloseMenu -> {
                        val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return false
                        val menu = player.containerMenu
                        val screenId = when (effect) {
                            is AuctionPurchaseEffect.CloseMenu -> effect.screenIdentity
                            else -> return false
                        }
                        val menuId = when (effect) {
                            is AuctionPurchaseEffect.CloseMenu -> effect.menuId
                            else -> return false
                        }
                        if (screen.identityHash() != screenId || menu.containerId != menuId || mc.gui.screen() !== screen) return false
                        player.closeContainer()
                        true
                    }
                    is PlannedEffect.DialogPress -> {
                        val screen = mc.gui.screen() ?: return false
                        val action = (effect as? AuctionPurchaseEffect.ClickConfirmation)?.action
                            as? AuctionPurchaseConfirmationAction.DialogWidget ?: return false
                        val button = screen.children().getOrNull(action.widgetIndex) as? Button ?: return false
                        if (System.identityHashCode(screen).toLong() != action.screenIdentity ||
                            button.message.string != action.expectedLabel || !isPositiveConfirmationLabel(button.message.string)) return false
                        button.onPress(KeyEvent(257, 0, 0))
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun net.minecraft.client.gui.screens.Screen.identityHash(): Long = System.identityHashCode(this).toLong()

    private fun clearAuctionMenuTracking() {
        auctionMenuEpochs.clear()
        auctionAcknowledgedMenuEpochs.clear()
        auctionMenuContentEpochs.clear()
        auctionLayoutSignatures.clear()
        ownedAuctionMenus.clear()
        ownedSellMenus.clear()
        ownedSellMenuScreens.clear()
        serverMenuStates.clear()
        menuInventoryMappings.clear()
        inventorySyncOrdinal = 0L
        inventoryFullSyncOrdinal = 0L
        inventoryFullSyncMenuId = -1
        auctionEpochCounter.set(0L)
        auctionCommandPending = false
        sellCommandPending = false
        awaitingAuctionConfirmation = false
        clearAuctionReceipts(resetCounter = true)
    }

    private fun clearAuctionReceipts(resetCounter: Boolean) {
        activeAuctionReceiptAttempt = null
        incomingAuctionReceipts.clear()
        auctionReceipts.clear()
        if (resetCounter) auctionReceiptIds.set(0L)
    }

    private fun beginInventorySwap(
        player: LocalPlayer,
        world: ClientLevel,
        sourceMenuSlot: Int,
        item: Item,
        destinationMenuSlot: Int = 36 + player.inventory.selectedSlot,
    ): Boolean {
        if (mc.gui.screen() != null || player.containerMenu !== player.inventoryMenu || !player.containerMenu.carried.isEmpty) return false
        val menu = player.inventoryMenu
        val destinationSlot = destinationMenuSlot
        if (destinationSlot !in 36..45) return false
        val gameMode = mc.gameMode ?: return false
        val source = menu.getSlot(sourceMenuSlot).item
        val destination = menu.getSlot(destinationSlot).item
        if (source.isEmpty || source.item != item || sourceMenuSlot == destinationSlot) return false
        val connection = activeConnection ?: return false
        val id = System.identityHashCode(connection).toLong()
        val sourceValue = InventorySlotValue.fromStack(source)
        val destinationValue = InventorySlotValue.fromStack(destination)
        inventoryAckTracker.begin(SchematicInventoryRules.swapExpectation(
            id, menu.containerId, menu.stateId, sourceMenuSlot, destinationSlot, sourceValue, destinationValue,
        ))
        val lease = StagedSlotLease(sourceMenuSlot, destinationSlot, sourceValue, destinationValue, BuiltInRegistries.ITEM.getKey(item).toString())
        pendingSwapLease = lease
        pendingSwapStarted = tick
        pendingSwapRestore = false
        pendingExpectedSource = destinationValue
        pendingExpectedDestination = sourceValue
        val button = if (destinationSlot == SchematicInventoryRules.OFFHAND_MENU_SLOT) 40 else destinationSlot - 36
        gameMode.handleContainerInput(menu.containerId, sourceMenuSlot, button, ContainerInput.SWAP, player)
        return true
    }

    private fun recordServerInventorySync(update: InventorySyncFrame, player: LocalPlayer, packetOrdinal: Long) {
        inventorySyncOrdinal = maxOf(inventorySyncOrdinal, packetOrdinal)
        val inventoryMenuId = player.inventoryMenu.containerId
        val epoch = if (update.menuId == inventoryMenuId) 0L else auctionMenuEpochs[update.menuId] ?: Long.MIN_VALUE
        if (epoch == Long.MIN_VALUE) return
        val key = update.menuId to epoch
        val evidence = serverMenuStates.getOrPut(key, ::SchematicServerMenuState)
        val canApplyFull = evidence.stateId < 0 || update.stateId == evidence.stateId ||
            SchematicRefillRules.serverUpdateAfter(evidence.stateId, update.stateId)
        if (update.fullContents && canApplyFull) {
            evidence.slots.clear()
            evidence.slots.putAll(update.slots)
            evidence.slotStateIds.clear()
            update.slots.keys.forEach { evidence.slotStateIds[it] = update.stateId }
            evidence.stateId = update.stateId
            evidence.fullContentsOrdinal = packetOrdinal
            evidence.cursor = update.cursor ?: InventorySlotValue("", 0)
            evidence.cursorKnown = true
        } else if (!update.fullContents) {
            for ((slot, value) in update.slots) {
                val previousState = evidence.slotStateIds[slot]
                if (previousState == null || previousState == update.stateId ||
                    SchematicRefillRules.serverUpdateAfter(previousState, update.stateId)) {
                    evidence.slots[slot] = value
                    evidence.slotStateIds[slot] = update.stateId
                }
            }
            if (update.stateId == evidence.stateId || evidence.stateId < 0 ||
                SchematicRefillRules.serverUpdateAfter(evidence.stateId, update.stateId)) evidence.stateId = update.stateId
            val cursor = update.cursor
            if (cursor != null) {
                evidence.cursor = cursor
                evidence.cursorKnown = true
            }
        }
        evidence.packetOrdinal = packetOrdinal
        if (update.menuId == inventoryMenuId) {
            if (update.fullContents && evidence.fullContentsOrdinal == packetOrdinal) {
                inventoryFullSyncOrdinal = packetOrdinal
                inventoryFullSyncMenuId = update.menuId
            }
            update.slots.forEach { (menuSlot, value) ->
                if (menuSlot in 9..44 && evidence.slotStateIds[menuSlot] == update.stateId && evidence.slots[menuSlot] == value) {
                    latestServerInventoryBySlot[menuSlot] = value
                }
            }
        } else {
            menuInventoryMappings[key].orEmpty().forEach { (activeSlot, inventoryMenuSlot) ->
                update.slots[activeSlot]?.takeIf { value ->
                    evidence.slotStateIds[activeSlot] == update.stateId && evidence.slots[activeSlot] == value
                }?.let { value ->
                    if (inventoryMenuSlot in 9..44) latestServerInventoryBySlot[inventoryMenuSlot] = value
                }
            }
        }
        while (serverMenuStates.size > 8) serverMenuStates.remove(serverMenuStates.keys.first())
    }

    private fun observeSellInventoryUpdate(update: InventorySyncFrame, player: LocalPlayer, packetOrdinal: Long) {
        val pendingOffer = pendingSellOffer
        if (pendingOffer != null && update.menuId == pendingOffer.effect.menuId &&
            packetOrdinal > pendingOffer.packetOrdinalBefore) {
            val effect = pendingOffer.effect
            val evidence = serverMenuStates[effect.menuId to effect.menuMapEpoch]
            val live = liveMenuSnapshot(player)
            if (evidence != null && live != null && live.menuId == effect.menuId &&
                live.screenIdentity == effect.screenIdentity && SchematicRefillRules.serverUpdateAfter(
                    effect.baselineStateId, evidence.stateId,
                )) {
                val sourceAfter = evidence.slots[effect.menuSlot]
                val gridAfter = readSlotValues(evidence.slots, pendingOffer.sellItemsBefore.keys)
                val changedGridSlots = pendingOffer.sellItemsBefore.keys.filter { slot ->
                    evidence.slots[slot] != pendingOffer.sellItemsBefore[slot]
                }
                val freshSlots = listOf(effect.menuSlot) + changedGridSlots
                val updatedAfterClick = freshSlots.all { slot ->
                    evidence.slotStateIds[slot]?.let { SchematicRefillRules.serverUpdateAfter(effect.baselineStateId, it) } == true
                }
                val localReconciled = sourceAfter != null && gridAfter != null &&
                    live.slot(effect.menuSlot)?.let(::inventorySlotValue) == sourceAfter &&
                    gridAfter.all { (slot, value) -> live.slot(slot)?.let(::inventorySlotValue) == value }
                if (sourceAfter != null && gridAfter != null && updatedAfterClick && localReconciled &&
                    evidence.cursorKnown && evidence.cursor.count == 0 && live.cursorEmpty) {
                    sellWorkflow.acknowledgeOffer(DonutSellOfferAck(
                        effectId = effect.id,
                        session = effect.session,
                        menuId = effect.menuId,
                        screenIdentity = effect.screenIdentity,
                        menuMapEpoch = effect.menuMapEpoch,
                        serverStateId = evidence.stateId,
                        observedAtNanos = System.nanoTime(),
                        inventorySlot = effect.inventorySlot,
                        sourceBefore = effect.expectedStack,
                        sourceAfter = sourceAfter,
                        sellItemsBefore = pendingOffer.sellItemsBefore,
                        sellItemsAfter = gridAfter,
                        cursorEmpty = evidence.cursor.count == 0,
                        localReconciled = true,
                    ))
                    pendingSellOffer = null
                }
            }
        }

        val pendingConfirm = pendingSellConfirmation
        if (pendingConfirm != null && update.menuId == pendingConfirm.effect.menuId &&
            packetOrdinal > pendingConfirm.packetOrdinalBefore) {
            val effect = pendingConfirm.effect
            val evidence = serverMenuStates[effect.menuId to effect.menuMapEpoch]
            val live = liveMenuSnapshot(player)
            if (evidence != null && live != null && live.menuId == effect.menuId &&
                live.screenIdentity == effect.screenIdentity && SchematicRefillRules.serverUpdateAfter(
                    effect.baselineStateId, evidence.stateId,
                )) {
                val gridAfter = readSlotValues(evidence.slots, pendingConfirm.sellItemsBefore.keys)
                val changedSlots = pendingConfirm.sellItemsBefore.keys.filter { slot ->
                    evidence.slots[slot] != pendingConfirm.sellItemsBefore[slot]
                }
                val updatedAfterClick = changedSlots.isNotEmpty() && changedSlots.all { slot ->
                    evidence.slotStateIds[slot]?.let { SchematicRefillRules.serverUpdateAfter(effect.baselineStateId, it) } == true
                }
                val localReconciled = gridAfter != null &&
                    gridAfter.all { (slot, value) -> live.slot(slot)?.let(::inventorySlotValue) == value }
                if (gridAfter != null && updatedAfterClick && localReconciled && evidence.cursorKnown &&
                    evidence.cursor.count == 0 && live.cursorEmpty) {
                    sellWorkflow.acknowledgeSale(DonutSellResultAck(
                        effectId = effect.id,
                        session = effect.session,
                        menuId = effect.menuId,
                        screenIdentity = effect.screenIdentity,
                        menuMapEpoch = effect.menuMapEpoch,
                        serverStateId = evidence.stateId,
                        observedAtNanos = System.nanoTime(),
                        sellItemsBefore = pendingConfirm.sellItemsBefore,
                        sellItemsAfter = gridAfter,
                        cursorEmpty = true,
                        localReconciled = true,
                    ), System.nanoTime())
                    pendingSellConfirmation = null
                }
            }
        }
    }

    private fun liveMenuSnapshot(player: LocalPlayer): MenuSnapshot? {
        val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return null
        return snapshotMenu(player.containerMenu, screen.identityHash(), screen.title.string)
    }

    private fun inventorySlotValue(slot: SlotSnapshot): InventorySlotValue = InventorySlotValue(
        slot.itemId, slot.count, slot.name, slot.lore, slot.componentFingerprint, slot.actualEnchantments,
        slot.procurementFingerprint,
    )

    private fun readSlotValues(slots: Map<Int, InventorySlotValue>, indexes: Set<Int>): Map<Int, InventorySlotValue>? =
        indexes.associateWith { index -> slots[index] ?: return null }

    private fun drainInventoryAcks(player: LocalPlayer, world: ClientLevel, connection: ClientPacketListener) {
        val connectionId = System.identityHashCode(connection).toLong()
        inventoryAckChannel.setSession(inventoryGeneration, connectionId)
        inventoryAckChannel.drainStamped(inventoryGeneration, connectionId) { envelope ->
            val update = envelope.update
            recordServerInventorySync(update, player, envelope.arrivalOrdinal)
            inventoryAckTracker.observe(update)
            if (update.menuId == player.inventoryMenu.containerId) {
                update.slots.forEach { (menuSlot, value) ->
                    if (menuSlot in 9..44) latestServerInventoryBySlot[menuSlot] = value
                }
            }
            observeWaterRefillInventory(update, player)
            observeWaterlogInventory(update, player, envelope.arrivalOrdinal)
            observeScaffoldInventory(update, connectionId, player)
            observePillarInventory(update, connectionId, player)
            if (eating && update.connectionId == connectionId && update.menuId == player.inventoryMenu.containerId) {
                val current = update.slots[eatingMenuSlot]
                val original = eatingBeforeStack
                if (current != null && original != null && SchematicFoodRules.serverConsumptionAcknowledged(original, current)) {
                    eatingInventoryAcknowledged = true
                    eatingUseFinished = true
                    mc.options.keyUse.setDown(false)
                }
            }
            val menuEpoch = auctionMenuEpochs[update.menuId] ?: Long.MIN_VALUE
            auctionPurchaseWorkflow.observeInventory(update, menuEpoch)
            if (update.fullContents && update.menuId != player.inventoryMenu.containerId && menuEpoch != Long.MIN_VALUE) {
                auctionAcknowledgedMenuEpochs[update.menuId] = menuEpoch
                val key = update.menuId to menuEpoch
                auctionMenuContentEpochs.merge(key, 1L, Long::plus)
            }
            observeSellInventoryUpdate(update, player, envelope.arrivalOrdinal)
        }
        if (inventoryAckTracker.status() == InventoryAckStatus.ACKNOWLEDGED && pendingSwapLease != null) {
            val lease = pendingSwapLease!!
            val menu = player.inventoryMenu
            val source = InventorySlotValue.fromStack(menu.getSlot(lease.sourceSlot).item)
            val destination = InventorySlotValue.fromStack(menu.getSlot(lease.destinationSlot).item)
            val expectedSource = pendingExpectedSource
            val expectedDestination = pendingExpectedDestination
            if (!menu.carried.isEmpty || source != expectedSource || destination != expectedDestination) {
                if (pendingSwapRestore && stagedLeases.lastOrNull() == lease) {
                    stagedLeases.removeLast()
                    message("Inventory restore changed after dispatch; dropped this lease rather than retrying an uncertain swap.", true)
                }
                inventoryAckTracker.reset()
                pendingSwapLease = null
                pendingSwapRestore = false
                pendingExpectedSource = null
                pendingExpectedDestination = null
                message("Inventory swap acknowledgement changed before use; staging cancelled.", true)
            } else {
                if (pendingSwapRestore) {
                    if (stagedLeases.lastOrNull() == lease) stagedLeases.removeLast()
                    pendingSwapRestore = false
                } else {
                    if (lease.destinationSlot == SchematicInventoryRules.OFFHAND_MENU_SLOT &&
                        !player.offhandItem.`is`(Items.TOTEM_OF_UNDYING)) {
                        recordTotemSwapFailure()
                        inventoryAckTracker.reset()
                        pendingSwapLease = null
                        pendingExpectedSource = null
                        pendingExpectedDestination = null
                        message("Server acknowledged the offhand swap, but the totem was not equipped.", true)
                        return
                    }
                    stagedLeases.addLast(lease)
                    if (lease.destinationSlot in 36..44) player.inventory.selectedSlot = lease.destinationSlot - 36
                }
                inventoryAckTracker.reset()
                pendingSwapLease = null
                pendingExpectedSource = null
                pendingExpectedDestination = null
            }
        }
    }

    private fun processPendingSwap(player: LocalPlayer, world: ClientLevel, connection: ClientPacketListener) {
        if (pendingSwapLease == null) return
        if (tick - pendingSwapStarted > SWAP_TIMEOUT_TICKS) {
            val lease = pendingSwapLease
            val restoring = pendingSwapRestore
            if (lease?.destinationSlot == SchematicInventoryRules.OFFHAND_MENU_SLOT && !restoring) recordTotemSwapFailure()
            if (restoring && lease != null && stagedLeases.lastOrNull() == lease) stagedLeases.removeLast()
            inventoryAckTracker.abandon()
            inventoryAckTracker.reset()
            pendingSwapLease = null
            pendingSwapRestore = false
            pendingExpectedSource = null
            pendingExpectedDestination = null
            message(
                if (restoring) "Inventory restore was not acknowledged; dropped this lease rather than repeating the click."
                else "Inventory staging received no authoritative acknowledgement; no placement was attempted.",
                true,
            )
            return
        }
        if (inventoryAckTracker.status() == InventoryAckStatus.REJECTED || inventoryAckTracker.status() == InventoryAckStatus.ABANDONED) {
            val lease = pendingSwapLease
            val restoring = pendingSwapRestore
            if (lease?.destinationSlot == SchematicInventoryRules.OFFHAND_MENU_SLOT && !restoring) recordTotemSwapFailure()
            if (restoring && lease != null && stagedLeases.lastOrNull() == lease) stagedLeases.removeLast()
            inventoryAckTracker.reset()
            pendingSwapLease = null
            pendingSwapRestore = false
            pendingExpectedSource = null
            pendingExpectedDestination = null
            message(
                if (restoring) "Server rejected inventory restoration; dropped this lease rather than retrying it."
                else "Server rejected inventory staging; no placement was attempted.",
                true,
            )
        }
    }

    private fun observeWaterRefillInventory(update: InventorySyncFrame, player: LocalPlayer) {
        val task = waterRefillTask ?: return
        if (!task.waiting || update.connectionId != task.connectionId || update.menuId != task.menuId) return
        val current = update.slots[task.menuSlot] ?: return
        if (!SchematicRefillRules.serverUpdateAfter(task.baselineStateId, update.stateId)) return
        val defaultWater = InventorySlotValue.fromStack(ItemStack(Items.WATER_BUCKET))
        val defaultEmpty = InventorySlotValue.fromStack(ItemStack(Items.BUCKET))
        task.outcome = when {
            SchematicRefillRules.waterBucketAcknowledged(task.before, current, defaultEmpty, defaultWater) ->
                SchematicRefillOutcome.FILLED
            SchematicRefillRules.unchangedUseAcknowledged(task.before, current, task.baselineStateId, update.stateId) ->
                SchematicRefillOutcome.UNCHANGED
            else -> SchematicRefillOutcome.WAITING
        }
    }

    private fun observeWaterlogInventory(update: InventorySyncFrame, player: LocalPlayer, arrivalOrdinal: Long) {
        val task = waterlogTask ?: return
        if (!task.sent || task.generation != runGeneration || arrivalOrdinal <= task.inventoryOrdinalBaseline ||
            update.connectionId != task.connectionId || update.menuId != player.inventoryMenu.containerId ||
            !SchematicRefillRules.serverUpdateAfter(task.baselineStateId, update.stateId)) return
        val current = update.slots[task.menuSlot] ?: return
        val before = task.before ?: return
        val defaultWater = InventorySlotValue.fromStack(ItemStack(Items.WATER_BUCKET))
        val defaultEmpty = InventorySlotValue.fromStack(ItemStack(Items.BUCKET))
        if (SchematicWaterlogRules.waterBucketUseAcknowledged(before, current, defaultWater, defaultEmpty)) {
            task.inventoryAcknowledged = true
        } else if (current != before) {
            task.inventoryRejected = true
        }
    }

    private fun walkTowardWaterlog(player: LocalPlayer, world: ClientLevel, target: BuildCellPos): Boolean {
        if (walkWithBaritone) {
            val tracked = BaritoneGoalBridge.trackedStand(this, target)
            val stand = tracked?.takeIf { feet ->
                val eye = eyeAtFeet(world, player, feet) ?: return@takeIf false
                canStandAt(world, player, feet) && withinUseReach(world, player, feet, target.toBlockPos()) &&
                    findWaterlogAim(world, player, target, eye) != null
            } ?: run {
                var checks = 0
                val start = player.blockPosition().let { BuildCellPos(it.x, it.y, it.z) }
                val routeResult = SchematicStandPlanner.findRoute(
                    start,
                    isGoal = { feet ->
                        if (++checks > 96 || !canStandAt(world, player, feet)) return@findRoute false
                        val eye = eyeAtFeet(world, player, feet) ?: return@findRoute false
                        withinUseReach(world, player, feet, target.toBlockPos()) &&
                            findWaterlogAim(world, player, target, eye) != null
                    },
                    canStand = { feet -> canStandAt(world, player, feet) },
                    canTransition = { from, to -> SchematicRouteRules.allows(from, to) && safeTransition(world, player, from, to) },
                    visitBudget = ROUTE_BUDGET,
                )
                routeResult.route?.lastOrNull() ?: if (routeResult.route != null && start == target) start else null
            }
            if (stand != null) {
                val current = player.blockPosition().let { BuildCellPos(it.x, it.y, it.z) }
                if (current == stand) {
                    BaritoneGoalBridge.cancelOwned(this, target)
                    route = emptyList()
                    routeTarget = null
                    return false
                }
                if (BaritoneGoalBridge.walkTo(this, target, stand)) return true
            } else {
                BaritoneGoalBridge.cancelOwned(this)
            }
        } else {
            BaritoneGoalBridge.cancelOwned(this)
        }

        if (routeTarget != target || route.isEmpty()) {
            var checks = 0
            val start = player.blockPosition().let { BuildCellPos(it.x, it.y, it.z) }
            val result = SchematicStandPlanner.findRoute(
                start,
                isGoal = { feet ->
                    if (++checks > 96 || !canStandAt(world, player, feet)) return@findRoute false
                    val eye = eyeAtFeet(world, player, feet) ?: return@findRoute false
                    withinUseReach(world, player, feet, target.toBlockPos()) && findWaterlogAim(world, player, target, eye) != null
                },
                canStand = { feet -> canStandAt(world, player, feet) },
                canTransition = { from, to -> SchematicRouteRules.allows(from, to) && safeTransition(world, player, from, to) },
                visitBudget = ROUTE_BUDGET,
            )
            route = result.route.orEmpty()
            routeIndex = 0
            routeTarget = target
            if (result.route == null || route.isEmpty()) return false
        }
        if (routeIndex >= route.size) {
            route = emptyList()
            routeTarget = null
            return false
        }
        val next = route[routeIndex]
        val surfaceY = standSurfaceY(world, next) ?: return false
        val center = Vec3(next.x + 0.5, surfaceY, next.z + 0.5)
        val dx = player.x - center.x
        val dz = player.z - center.z
        if (dx * dx + dz * dz < 0.22 && kotlin.math.abs(player.y - surfaceY) < 0.15) {
            routeIndex++
            if (routeIndex >= route.size) {
                route = emptyList()
                routeTarget = null
                return false
            }
        }
        return routeIndex < route.size
    }

    private fun findWaterlogAim(
        world: ClientLevel,
        player: LocalPlayer,
        target: BuildCellPos,
        eye: Vec3,
    ): Pair<Rotation, BlockHitResult>? {
        val pos = target.toBlockPos()
        if (!world.hasChunkAt(pos)) return null
        val state = world.getBlockState(pos)
        if (!state.hasProperty(BlockStateProperties.WATERLOGGED) || state.getValue(BlockStateProperties.WATERLOGGED)) return null
        val reachSquared = player.blockInteractionRange().toDouble().let { it * it }
        val points = state.getShape(world, pos).toAabbs().flatMap { box ->
            Direction.values().map { direction ->
                val centerX = (box.minX + box.maxX) * 0.5
                val centerY = (box.minY + box.maxY) * 0.5
                val centerZ = (box.minZ + box.maxZ) * 0.5
                val localX = when (direction) { Direction.WEST -> box.minX + 0.001; Direction.EAST -> box.maxX - 0.001; else -> centerX }
                val localY = when (direction) { Direction.DOWN -> box.minY + 0.001; Direction.UP -> box.maxY - 0.001; else -> centerY }
                val localZ = when (direction) { Direction.NORTH -> box.minZ + 0.001; Direction.SOUTH -> box.maxZ - 0.001; else -> centerZ }
                Vec3(pos.x + localX, pos.y + localY, pos.z + localZ)
            }
        }.distinct()
        for (point in points) {
            if (eye.distanceToSqr(point) > reachSquared) continue
            val desired = Rotation.lookingAt(point, eye).normalize()
            val end = eye.add(desired.directionVector.scale(player.blockInteractionRange().toDouble()))
            val hit = world.clip(ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)) as? BlockHitResult
            if (hit != null && hit.type == HitResult.Type.BLOCK && hit.blockPos == pos) return desired to hit
        }
        return null
    }

    private fun failWaterlogTask(task: SchematicWaterlogTask, reason: String) {
        pendingWaterlogCells -= task.target
        unresolvedCells += task.target
        waterlogTask = null
        BaritoneGoalBridge.cancelOwned(this, task.target)
        route = emptyList()
        routeIndex = 0
        routeTarget = null
        message("Waterlogging at ${task.target.asText()} remains unresolved: $reason; no repeat bucket use was sent.", true)
    }

    private fun serviceWaterlogTask(player: LocalPlayer, world: ClientLevel, connection: ClientPacketListener): Boolean {
        var task = waterlogTask
        if (task != null && (task.generation != runGeneration || task.connectionId != System.identityHashCode(connection).toLong() ||
                task.worldId != System.identityHashCode(world).toLong() || task.playerId != System.identityHashCode(player).toLong())) {
            waterlogTask = null
            return false
        }
        if (task == null) {
            val target = pendingWaterlogCells.minOrNull() ?: return false
            val expected = expectedStates[target] ?: run {
                pendingWaterlogCells -= target
                return true
            }
            val blockPos = target.toBlockPos()
            if (!world.hasChunkAt(blockPos)) {
                unresolvedCells += target
                pendingWaterlogCells -= target
                message("Waterlogging at ${target.asText()} could not run because its world chunk is unavailable.", true)
                return true
            }
            val actual = world.getBlockState(blockPos)
            if (!statesMatch(expected, actual, target in relaxedCompleted)) {
                pendingWaterlogCells -= target
                completedCells -= target
                missingCells[target] = buildCell(target, expected)
                return true
            }
            if (!actual.hasProperty(BlockStateProperties.WATERLOGGED) || actual.getValue(BlockStateProperties.WATERLOGGED)) {
                pendingWaterlogCells -= target
                return true
            }
            if (!placeWaterLava) {
                pendingWaterlogCells -= target
                excludedWaterlogCells += target
                return true
            }
            if (mc.gui.screen() != null || player.containerMenu !== player.inventoryMenu || !player.containerMenu.carried.isEmpty) return false
            val defaultWater = InventorySlotValue.fromStack(ItemStack(Items.WATER_BUCKET))
            val hotbar = (0..8).firstOrNull { slot ->
                InventorySlotValue.fromStack(player.inventory.getItem(slot)) == defaultWater
            }
            if (hotbar == null) {
                val storage = (9..35).firstOrNull { slot ->
                    InventorySlotValue.fromStack(player.inventory.getItem(slot)) == defaultWater
                }
                if (storage != null) {
                    beginInventorySwap(player, world, storage, Items.WATER_BUCKET)
                    return true
                }
                if (tryStartWaterRefill(player, world)) return true
                if (buyFromAh && lastPlacementTick?.let { tick - it < 40L } == true) return true
                if (buyFromAh && startMaterialPurchase(player, "minecraft:water_bucket", Items.WATER_BUCKET)) return true
                pendingWaterlogCells -= target
                unresolvedCells += target
                val reason = if ("minecraft:water_bucket" in waitingForInventoryRoom) "compatible inventory room is unavailable" else "no exact water bucket or safe refill source is available"
                message("Waterlogging at ${target.asText()} remains unresolved: $reason.", true)
                return true
            }
            player.inventory.selectedSlot = hotbar
            val menu = player.inventoryMenu
            val slot = 36 + hotbar
            val before = InventorySlotValue.fromStack(menu.getSlot(slot).item)
            if (before != defaultWater) return true
            task = SchematicWaterlogTask(
                target = target,
                expectedState = expected,
                connectionId = System.identityHashCode(connection).toLong(),
                worldId = System.identityHashCode(world).toLong(),
                playerId = System.identityHashCode(player).toLong(),
                generation = runGeneration,
                menuId = menu.containerId,
                menuSlot = slot,
                before = before,
                baselineStateId = menu.stateId,
                inventoryOrdinalBaseline = currentInventoryArrivalOrdinal(),
                blockPacketBaseline = blockPacketOrdinal.get(),
                startedAt = tick,
            )
            waterlogTask = task
        }

        if (!pendingWaterlogCells.contains(task.target)) {
            waterlogTask = null
            return true
        }
        val targetPos = task.target.toBlockPos()
        if (!world.hasChunkAt(targetPos)) {
            failWaterlogTask(task, "the target chunk unloaded")
            return true
        }
        val currentState = world.getBlockState(targetPos)
        if (!statesMatch(task.expectedState, currentState, task.target in relaxedCompleted)) {
            pendingWaterlogCells -= task.target
            completedCells -= task.target
            missingCells[task.target] = buildCell(task.target, task.expectedState)
            waterlogTask = null
            return true
        }
        if (currentState.hasProperty(BlockStateProperties.WATERLOGGED) && currentState.getValue(BlockStateProperties.WATERLOGGED) && !task.sent) {
            pendingWaterlogCells -= task.target
            waterlogTask = null
            return true
        }
        if (task.sent) {
            if (SchematicWaterlogRules.acknowledgmentsComplete(task.blockAcknowledged, task.inventoryAcknowledged)) {
                if (currentState.hasProperty(BlockStateProperties.WATERLOGGED) && currentState.getValue(BlockStateProperties.WATERLOGGED)) {
                    pendingWaterlogCells -= task.target
                    waterlogTask = null
                    BaritoneGoalBridge.cancelOwned(this, task.target)
                    route = emptyList()
                    routeTarget = null
                    message("Waterlogging at ${task.target.asText()} was confirmed by both block and bucket inventory updates.", false)
                    return true
                }
            }
            if (task.blockRejected || task.inventoryRejected || tick - task.sentAt >= PLACE_TIMEOUT_TICKS) {
                failWaterlogTask(task, "the bucket consumption and waterlogged block state did not both receive server confirmation")
            }
            return true
        }
        if (task.attempts >= 3) {
            failWaterlogTask(task, "no visible safe bucket target was available after three bounded attempts")
            return true
        }
        if (tick < task.retryAt) return true
        if (mc.gui.screen() != null || player.containerMenu !== player.inventoryMenu || player.containerMenu.containerId != task.menuId ||
            !player.containerMenu.carried.isEmpty) return false
        if (player.inventory.selectedSlot != task.menuSlot - 36) player.inventory.selectedSlot = task.menuSlot - 36
        val held = InventorySlotValue.fromStack(player.inventoryMenu.getSlot(task.menuSlot).item)
        if (held != task.before || held != InventorySlotValue.fromStack(ItemStack(Items.WATER_BUCKET))) {
            waterlogTask = null
            return true
        }
        val currentAim = findWaterlogAim(world, player, task.target, player.eyePosition)
        if (currentAim == null) {
            if (walkTowardWaterlog(player, world, task.target)) return true
            task.attempts++
            task.retryAt = tick + 20L
            return true
        }
        route = emptyList()
        routeIndex = 0
        routeTarget = null
        val desired = currentAim.first
        requestAim(desired)
        if (!SchematicNativePlacement.rotationAcknowledged(
                desired.yaw, desired.pitch,
                RotationManager.currentRotation?.yaw, RotationManager.currentRotation?.pitch,
                RotationManager.serverRotation.yaw, RotationManager.serverRotation.pitch,
            )) {
            if (tick - task.startedAt >= PLACE_TIMEOUT_TICKS.toLong()) failWaterlogTask(task, "the water bucket aim did not settle")
            return true
        }
        if (!ensureSneakReleasedForInteraction(task.target, player)) return true
        val hit = currentRotationHit(world, player, desired)
        if (hit == null || hit.type != HitResult.Type.BLOCK || hit.blockPos != targetPos) {
            task.attempts++
            task.retryAt = tick + 20L
            return true
        }
        val baselineState = player.inventoryMenu.stateId
        val inventoryOrdinal = currentInventoryArrivalOrdinal()
        val blockOrdinal = blockPacketOrdinal.get()
        val oldYaw = player.yRot
        val oldPitch = player.xRot
        val result = try {
            player.yRot = desired.yaw
            player.xRot = desired.pitch
            mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
        } finally {
            player.yRot = oldYaw
            player.xRot = oldPitch
        }
        if (result?.consumesAction() == true) {
            task.sent = true
            task.attempts++
            task.baselineStateId = baselineState
            task.inventoryOrdinalBaseline = inventoryOrdinal
            task.blockPacketBaseline = blockOrdinal
            task.sentAt = tick
        } else {
            task.attempts++
            task.retryAt = tick + 20L
        }
        return true
    }

    private fun restoreNextLease(player: LocalPlayer, world: ClientLevel, connection: ClientPacketListener) {
        val lease = stagedLeases.lastOrNull() ?: run { phase = SchematicPhase.COMPLETE; return }
        if (mc.gui.screen() != null || player.containerMenu !== player.inventoryMenu || !player.containerMenu.carried.isEmpty) return
        val menu = player.inventoryMenu
        val source = menu.getSlot(lease.sourceSlot).item
        val destination = menu.getSlot(lease.destinationSlot).item
        val defaultFilledBucket = when (lease.itemId) {
            "minecraft:water_bucket" -> InventorySlotValue.fromStack(ItemStack(Items.WATER_BUCKET))
            "minecraft:lava_bucket" -> InventorySlotValue.fromStack(ItemStack(Items.LAVA_BUCKET))
            else -> null
        }
        val defaultEmptyBucket = InventorySlotValue.fromStack(ItemStack(Items.BUCKET))
        val defaultWaterBucket = InventorySlotValue.fromStack(ItemStack(Items.WATER_BUCKET))
        if (!SchematicInventoryRules.isOwnedRestoreRemainder(
                lease.originalSource,
                InventorySlotValue.fromStack(destination),
                defaultFilledBucket,
                defaultEmptyBucket,
                defaultWaterBucket,
            )) {
            message("Skipped restoring a staged slot because its item, components, enchantments, or count changed.", true)
            stagedLeases.removeLast()
            return
        }
        if (InventorySlotValue.fromStack(source) != lease.originalDestination) {
            message("Skipped restoring a staged hotbar slot because its source slot changed after building.", true)
            stagedLeases.removeLast()
            return
        }
        val sourceValue = InventorySlotValue.fromStack(source)
        val destinationValue = InventorySlotValue.fromStack(destination)
        val gameMode = mc.gameMode ?: return
        val connectionId = System.identityHashCode(connection).toLong()
        inventoryAckTracker.begin(SchematicInventoryRules.swapExpectation(
            connectionId, menu.containerId, menu.stateId, lease.sourceSlot, lease.destinationSlot, sourceValue, destinationValue,
        ))
        pendingSwapLease = lease
        pendingSwapStarted = tick
        pendingSwapRestore = true
        pendingExpectedSource = destinationValue
        pendingExpectedDestination = sourceValue
        val button = if (lease.destinationSlot == SchematicInventoryRules.OFFHAND_MENU_SLOT) 40 else lease.destinationSlot - 36
        gameMode.handleContainerInput(menu.containerId, lease.sourceSlot, button, ContainerInput.SWAP, player)
    }

    private fun findHotbarItem(player: LocalPlayer, item: Item): Int? =
        (0..8).firstOrNull { player.inventory.getItem(it).item == item && !player.inventory.getItem(it).isEmpty }

    private fun serverInventorySnapshot(): Map<String, InventorySlotValue> = latestServerInventoryBySlot
        .filterValues { it.count > 0 && it.itemId.isNotBlank() }
        .mapKeys { (menuSlot, _) -> "menu-$menuSlot" }

    private fun ensureTotemInOffhand(player: LocalPlayer, world: ClientLevel): Boolean {
        val offhandId = player.offhandItem.takeUnless { it.isEmpty }?.let { BuiltInRegistries.ITEM.getKey(it.item).toString() }
        if (!SchematicInventoryRules.shouldEquipTotem(keepTotem, offhandId)) {
            missingTotemReported = false
            totemSwapFailures = 0
            return false
        }
        if (totemSwapFailures >= 3) {
            if (!missingTotemReported) message("Offhand totem swap failed three times; building remains paused safely.", true)
            missingTotemReported = true
            return true
        }
        if (tick < nextTotemAttemptTick) return true
        val source = (9..44).firstOrNull { slot ->
            val stack = player.inventoryMenu.getSlot(slot).item
            !stack.isEmpty && stack.`is`(Items.TOTEM_OF_UNDYING)
        }
        if (source == null) {
            if (!missingTotemReported) message("Keep Totem In Offhand is waiting for an available totem.", true)
            missingTotemReported = true
            return true
        }
        if (beginInventorySwap(player, world, source, Items.TOTEM_OF_UNDYING, SchematicInventoryRules.OFFHAND_MENU_SLOT)) {
            missingTotemReported = false
            return true
        }
        return true
    }

    private fun recordTotemSwapFailure() {
        totemSwapFailures++
        nextTotemAttemptTick = tick + (20L * totemSwapFailures).coerceAtMost(100L)
        if (totemSwapFailures >= 3) {
            missingTotemReported = false
            message("Offhand totem swap failed three times; building remains paused safely.", true)
        }
    }

    private fun tryStartWaterRefill(player: LocalPlayer, world: ClientLevel): Boolean {
        if (waterRefillTask != null) return true
        val waterNeed = missingCells.keys.count { expectedStates[it]?.block == Blocks.WATER } + pendingWaterlogCells.size
        val waterHave = (0..35).sumOf { slot ->
            val stack = InventorySlotValue.fromStack(player.inventory.getItem(slot))
            if (stack == InventorySlotValue.fromStack(ItemStack(Items.WATER_BUCKET))) stack.count else 0
        }
        if (waterNeed <= waterHave) return false
        val defaultEmpty = InventorySlotValue.fromStack(ItemStack(Items.BUCKET))
        val candidateSlots = (0..35).filter { slot ->
            val value = InventorySlotValue.fromStack(player.inventory.getItem(slot))
            SchematicRefillRules.eligibleEmptyBucket(value, defaultEmpty)
        }
        val hotbarSlot = candidateSlots.firstOrNull { it in 0..8 }
        if (hotbarSlot == null) {
            val storageSlot = candidateSlots.firstOrNull { it in 9..35 } ?: return false
            return beginInventorySwap(player, world, storageSlot, Items.BUCKET)
        }
        val target = findEndlessWaterSource(player, world) ?: return false
        player.inventory.selectedSlot = hotbarSlot
        val menu = player.inventoryMenu
        val menuSlot = 36 + hotbarSlot
        val before = InventorySlotValue.fromStack(menu.getSlot(menuSlot).item)
        if (!SchematicRefillRules.eligibleEmptyBucket(before, defaultEmpty)) return false
        val connection = activeConnection ?: return false
        waterRefillTask = SchematicWaterRefillTask(
            target = target,
            menuId = menu.containerId,
            menuSlot = menuSlot,
            before = before,
            connectionId = System.identityHashCode(connection).toLong(),
            worldId = System.identityHashCode(world).toLong(),
            playerId = System.identityHashCode(player).toLong(),
            baselineStateId = menu.stateId,
            createdAt = tick,
        )
        message("Refilling one water bucket from a verified renewable source before buying more buckets.", false)
        return true
    }

    private fun serviceWaterRefill(player: LocalPlayer, world: ClientLevel): Boolean {
        val task = waterRefillTask ?: return false
        val connection = mc.connection
        if (player !== activePlayer || world !== activeWorld || connection !== activeConnection ||
            System.identityHashCode(world).toLong() != task.worldId ||
            System.identityHashCode(player).toLong() != task.playerId ||
            System.identityHashCode(connection).toLong() != task.connectionId) {
            waterRefillTask = null
            return false
        }
        when (task.outcome) {
            SchematicRefillOutcome.FILLED -> {
                waterRefillCooldowns[task.target.asLong()] = tick + 1_200L
                refilledWaterBucketCount++
                waterRefillTask = null
                message("Water bucket refill was confirmed by the inventory update.", false)
                return true
            }
            SchematicRefillOutcome.UNCHANGED -> {
                task.waiting = false
                if (task.attempts >= 2) {
                    waterRefillCooldowns[task.target.asLong()] = tick + 40L
                    waterRefillTask = null
                    message("The water source did not fill the bucket after two acknowledged attempts.", true)
                    return true
                }
                task.retryAt = tick + 12L
                task.outcome = SchematicRefillOutcome.WAITING
                return true
            }
            SchematicRefillOutcome.WAITING -> Unit
        }
        if (task.waiting) {
            if (tick - task.sentAt >= PLACE_TIMEOUT_TICKS) {
                waterRefillCooldowns[task.target.asLong()] = tick + 40L
                waterRefillTask = null
                message("Water bucket refill has no authoritative inventory result; no repeat click was sent.", true)
            }
            return true
        }
        if (tick - task.createdAt >= PLACE_TIMEOUT_TICKS) {
            waterRefillCooldowns[task.target.asLong()] = tick + 40L
            waterRefillTask = null
            message("Water bucket refill did not reach a safe interaction; the builder will use the capped purchase path.", true)
            return true
        }
        if (tick < task.retryAt) return true
        if (mc.gui.screen() != null || player.containerMenu !== player.inventoryMenu ||
            player.inventoryMenu.containerId != task.menuId || player.inventory.selectedSlot != task.menuSlot - 36) return true
        val current = InventorySlotValue.fromStack(player.inventoryMenu.getSlot(task.menuSlot).item)
        val defaultEmpty = InventorySlotValue.fromStack(ItemStack(Items.BUCKET))
        if (!SchematicRefillRules.eligibleEmptyBucket(current, defaultEmpty) || !isEndlessWaterSource(world, task.target)) {
            waterRefillTask = null
            return false
        }
        val desired = aimAt(player, Vec3.atCenterOf(task.target))
        if (!SchematicNativePlacement.rotationAcknowledged(
                desired.yaw, desired.pitch,
                RotationManager.currentRotation?.yaw, RotationManager.currentRotation?.pitch,
                RotationManager.serverRotation.yaw, RotationManager.serverRotation.pitch,
            )) return true
        val hit = currentFluidRotationHit(world, player, desired)
        if (hit == null || hit.type != HitResult.Type.BLOCK || hit.blockPos != task.target) return true
        task.baselineStateId = player.inventoryMenu.stateId
        val oldYaw = player.yRot
        val oldPitch = player.xRot
        val result = try {
            player.yRot = desired.yaw
            player.xRot = desired.pitch
            mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
        } finally {
            player.yRot = oldYaw
            player.xRot = oldPitch
        }
        task.attempts++
        if (result?.consumesAction() == true) {
            task.waiting = true
            task.sentAt = tick
        } else if (task.attempts >= 2) {
            waterRefillCooldowns[task.target.asLong()] = tick + 40L
            waterRefillTask = null
            message("The water bucket use did not consume an action after two tries.", true)
        } else {
            task.retryAt = tick + 12L
        }
        return true
    }

    private fun findEndlessWaterSource(player: LocalPlayer, world: ClientLevel): BlockPos? {
        val center = BlockPos.containing(player.eyePosition)
        val candidates = ArrayList<BlockPos>()
        for (x in center.x - 5..center.x + 5) for (y in center.y - 5..center.y + 5) for (z in center.z - 5..center.z + 5) {
            val pos = BlockPos(x, y, z)
            if (!world.hasChunkAt(pos) || waterRefillCooldowns[pos.asLong()]?.let { tick < it } == true) continue
            if (!isEndlessWaterSource(world, pos)) continue
            if (player.eyePosition.distanceToSqr(Vec3.atCenterOf(pos)) > 20.25) continue
            val rotation = Rotation.lookingAt(Vec3.atCenterOf(pos), player.eyePosition).normalize()
            val hit = currentFluidRotationHit(world, player, rotation)
            if (hit?.type == HitResult.Type.BLOCK && hit.blockPos == pos) candidates += pos.immutable()
        }
        return candidates.minByOrNull { player.eyePosition.distanceToSqr(Vec3.atCenterOf(it)) }
    }

    private fun isEndlessWaterSource(world: ClientLevel, pos: BlockPos): Boolean {
        if (!world.hasChunkAt(pos) || !world.hasChunkAt(pos.below())) return false
        val state = world.getBlockState(pos)
        val fluid = state.fluidState
        if (!fluid.isSource || !fluid.`is`(net.minecraft.tags.FluidTags.WATER)) return false
        val horizontalSources = Direction.Plane.HORIZONTAL.count { direction ->
            val neighbor = pos.relative(direction)
            world.hasChunkAt(neighbor) && world.getFluidState(neighbor).isSource &&
                world.getFluidState(neighbor).`is`(net.minecraft.tags.FluidTags.WATER)
        }
        if (horizontalSources < 2) return false
        val below = world.getBlockState(pos.below())
        return below.isSolid || world.getFluidState(pos.below()).isSource &&
            world.getFluidState(pos.below()).`is`(net.minecraft.tags.FluidTags.WATER)
    }

    internal fun resolveItem(id: String?): Item? = SchematicItemResolver.resolve(id)

    private fun statesMatch(expected: BlockState, actual: BlockState, allowFacing: Boolean): Boolean {
        if (expected == actual) return true
        val expectedId = BuiltInRegistries.BLOCK.getKey(expected.block).toString()
        val ignoredProperties = buildSet {
            if (expected.block == Blocks.LEVER && (!flipLeversOn || !expected.getValue(BlockStateProperties.POWERED))) {
                add("powered")
            }
            if (expected.block in setOf(Blocks.REPEATER, Blocks.COMPARATOR) && !repeatersComparators) {
                add("delay")
                add("mode")
                add("mode_comparator")
            }
            if (expectedId == "minecraft:iron_door" || expectedId == "minecraft:iron_trapdoor") add("open")
        }
        return SchematicStateRules.matches(
            expectedId,
            properties(expected).filterKeys { it !in ignoredProperties },
            BuiltInRegistries.BLOCK.getKey(actual.block).toString(),
            properties(actual).filterKeys { it !in ignoredProperties },
            allowFacing,
        )
    }

    private fun tryEat(player: LocalPlayer): Boolean {
        val food = player.foodData.foodLevel
        if (eating) {
            eatingTicks++
            if (food > eatingStartFood) {
                eatingUseFinished = true
                mc.options.keyUse.setDown(false)
            } else if (!eatingUseFinished) {
                mc.options.keyUse.setDown(true)
            }
            if (eatingUseFinished && eatingInventoryAcknowledged) {
                eating = false
                eatingTicks = 0
                eatingStartFood = -1
                eatingMenuSlot = -1
                eatingBeforeStack = null
                eatingInventoryAcknowledged = false
                eatingUseFinished = false
                return false
            }
            if (eatingTicks >= 70) {
                mc.options.keyUse.setDown(false)
                eating = false
                eatingTicks = 0
                eatingStartFood = -1
                eatingMenuSlot = -1
                eatingBeforeStack = null
                eatingInventoryAcknowledged = false
                eatingUseFinished = false
                nextEatAttemptTick = tick + 200
                message("Food use timed out without both hunger and server inventory confirmation; pausing further eating briefly.", true)
                return false
            }
            return true
        }
        if (tick < nextEatAttemptTick) return false
        if (food > 14 && !(player.health < player.maxHealth && food < 18)) return false
        val hasFood = { index: Int ->
            val stack = player.inventory.getItem(index)
            val properties = stack.get(DataComponents.FOOD)
            !stack.isEmpty && properties != null && SchematicFoodRules.eligible(
                BuiltInRegistries.ITEM.getKey(stack.item).toString(),
                properties.nutrition(),
                stack.get(DataComponents.USE_REMAINDER) != null,
            )
        }
        val slot = SchematicInventoryRules.preferredFoodSlot(0..8, 9..35, hasFood) ?: return false
        if (slot >= 9) {
            val world = mc.level as? ClientLevel ?: return false
            val food = player.inventory.getItem(slot).item
            return beginInventorySwap(player, world, slot, food)
        }
        player.inventory.selectedSlot = slot
        eating = true
        eatingStartFood = food
        eatingTicks = 0
        eatingMenuSlot = 36 + slot
        eatingBeforeStack = InventorySlotValue.fromStack(player.inventoryMenu.getSlot(eatingMenuSlot).item)
        eatingInventoryAcknowledged = false
        eatingUseFinished = false
        mc.options.keyUse.setDown(true)
        return true
    }

    private fun processServerUpdatesFromNative(world: ClientLevel) = processServerUpdates(world)

    private fun setInventoryEpoch(connection: ClientPacketListener) {
        inventoryGeneration++
        inventoryConnectionId = System.identityHashCode(connection).toLong()
        inventoryAckChannel.setSession(inventoryGeneration, inventoryConnectionId)
    }

    private fun resetForSessionChange() {
        runGeneration++
        mc.options.keyUse.setDown(false)
        activeScaffoldTask = null
        activePillarTask = null
        cancelDigOutTask()
        nextDigOutAllowedTick = tick
        ownedScaffolds.clear()
        retainedScaffolds.clear()
        scaffoldCleanupPos = null
        retainedScaffoldReportSent = false
        inventoryAckTracker.reset()
        inventoryAckChannel.reset()
        incomingBlockUpdates.clear()
        resetRun()
        activePlayer = null
        activeWorld = null
        activeConnection = null
    }

    private fun resetRun() {
        BaritoneGoalBridge.cancelOwned(this)
        cancelDigOutTask()
        releaseSneakLease()
        resetBuildData()
        phase = SchematicPhase.SCANNING
        frame = null
        layerScanCursor = null
        semanticWindowKey = null
        semanticWindowCursor = 0
        activeLayerWindow = null
        prefetchedLayerWindow = null
        blockedLayerWindows.clear()
        retryLayerWindows.clear()
        unresolvedLayerWindows.clear()
        unresolvedLayerFinalization = false
        finalAuditDone = false
        currentTarget = null
        placementCandidate = null
        placementCandidateFor = null
        rotationWaitTarget = null
        route = emptyList()
        routeIndex = 0
        waitingForPlacement = null
        placementSentAt = 0
        placementAttempts = 0
        lastPlacementTick = null
        sneakReleaseTarget = null
        sneakReleaseWaitTicks = 0
        nextVirtualRetryTick = 0
        relaxedPass = false
        deferredPass = false
        pendingSwapLease = null
        stagedLeases.clear()
        actionFailureGate.clear()
        eatingTicks = 0
        eating = false
        eatingStartFood = -1
        eatingMenuSlot = -1
        eatingBeforeStack = null
        eatingInventoryAcknowledged = false
        eatingUseFinished = false
        nextEatAttemptTick = 0L
        waterRefillTask = null
        waterRefillCooldowns.clear()
        nextDigOutAllowedTick = 0L
        refilledWaterBucketCount = 0
        missingTotemReported = false
        totemSwapFailures = 0
        nextTotemAttemptTick = 0
        activeScaffoldTask = null
        activePillarTask = null
        scaffoldCleanupPos = null
        scaffoldCleanupSentAt = 0L
        scaffoldStepsByTarget.clear()
        pillarRunsByTarget.clear()
    }

    private fun cancelDigOutTask() {
        if (activeDigOutTask?.stage == SchematicDigOutStage.DESTROYING) mc.gameMode?.stopDestroyBlock()
        activeDigOutTask = null
    }

    private fun resetBuildData() {
        cancelDigOutTask()
        val task = activeCrafterTask
        val player = mc.player
        if (task != null && player != null && task.menuId >= 0 && ownedCrafterMenu(player, task) != null) {
            player.closeContainer()
        }
        activeCrafterTask = null
        waterRefillTask = null
        incomingCrafterData.clear()
        incomingCrafterOpens.clear()
        expectedStates.clear()
        planningWindows.clear()
        planningWindowByPos.clear()
        placementProgressGeneration = 0L
        planningIndexRevision = 0L
        resetPlanningPass()
        expectedCrafterMasks.clear()
        completedCrafters.clear()
        waterRefillTask = null
        waterlogTask = null
        pendingWaterlogCells.clear()
        excludedWaterlogCells.clear()
        missingCells.clear()
        completedCells.clear()
        pendingVirtualData.clear()
        unresolvedCells.clear()
        deferredCells.clear()
        relaxedCandidates.clear()
        relaxedCompleted.clear()
        provisionalChests.clear()
        provisionalChestAt.clear()
        controlAdjustmentClicks.clear()
        retryLedger.clear()
        layerScanCursor = null
        activeLayerWindow = null
        prefetchedLayerWindow = null
        blockedLayerWindows.clear()
        retryLayerWindows.clear()
        unresolvedLayerWindows.clear()
        unresolvedCountByLayerWindow.clear()
        excludedCountByLayerWindow.clear()
        unresolvedLayerFinalization = false
        confirmedCellCount = 0L
        excludedTntCount = 0
        excludedFluidCount = 0
        finalAuditDone = false
        scanFinalized = false
        truncatedReported = false
        buildFullyComplete = false
    }

    private fun BuildCellPos.toBlockPos() = BlockPos(x, y, z)
    private fun BuildCellPos.asText() = "$x, $y, $z"

    private fun message(text: String, warn: Boolean) {
        if (!warn && !chatFeedback) return
        if (text == lastMessage && tick - lastMessageTick < 40) return
        lastMessage = text
        lastMessageTick = tick
        net.ccbluex.liquidbounce.utils.client.chat(Component.literal("[Donut Schematic Builder] $text"), this)
    }
}

/** Optional Baritone API, resolved only when requested; the builder remains bounded without it. */
private object BaritoneGoalBridge {
    private var owner: Any? = null
    private var target: BuildCellPos? = null
    private var stand: BuildCellPos? = null
    private var process: Any? = null
    private var goal: Any? = null

    fun trackedStand(ownerToken: Any, targetPos: BuildCellPos): BuildCellPos? {
        if (owner !== ownerToken || target != targetPos) return null
        val currentProcess = process ?: return null
        return if (currentGoal(currentProcess) === goal) stand else null
    }

    fun walkTo(ownerToken: Any, targetPos: BuildCellPos, safeStand: BuildCellPos): Boolean = runCatching {
        val loader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
        val api = Class.forName("baritone.api.BaritoneAPI", false, loader)
        val provider = api.getMethod("getProvider").invoke(null)
        val baritone = provider.javaClass.getMethod("getPrimaryBaritone").invoke(provider)
        val goalProcess = baritone.javaClass.getMethod("getCustomGoalProcess").invoke(baritone)
        val currentSettings = api.getMethod("getSettings").invoke(null)
        if (readBooleanSetting(currentSettings, "allowBreak") != false ||
            readBooleanSetting(currentSettings, "allowPlace") != false) {
            cancelOwned(ownerToken)
            return@runCatching false
        }

        if (owner === ownerToken && target == targetPos && stand == safeStand) {
            if (process === goalProcess && currentGoal(goalProcess) === goal) return@runCatching true
            clearTracking()
        } else if (owner != null) {
            cancelOwned(ownerToken)
        }
        val current = currentGoal(goalProcess)
        if (current != null) return@runCatching false
        val goalClass = Class.forName("baritone.api.pathing.goals.GoalBlock", false, loader)
        val goal = goalClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(safeStand.x, safeStand.y, safeStand.z)
        goalProcess.javaClass.methods.first { it.name == "setGoalAndPath" && it.parameterCount == 1 }.invoke(goalProcess, goal)
        this.owner = ownerToken
        this.target = targetPos
        this.stand = safeStand
        this.process = goalProcess
        this.goal = goal
        true
    }.getOrDefault(false)

    fun cancelOwned(ownerToken: Any, targetPos: BuildCellPos? = null) {
        if (owner !== ownerToken || targetPos != null && target != targetPos) return
        val currentProcess = process
        val expectedGoal = goal
        if (currentProcess != null && expectedGoal != null && currentGoal(currentProcess) === expectedGoal) {
            runCatching {
                currentProcess.javaClass.methods.first { it.name == "setGoalAndPath" && it.parameterCount == 1 }
                    .invoke(currentProcess, null)
            }
        }
        clearTracking()
    }

    private fun currentGoal(goalProcess: Any): Any? = runCatching {
        goalProcess.javaClass.methods.first { it.name == "getGoal" && it.parameterCount == 0 }.invoke(goalProcess)
    }.getOrNull()

    private fun readBooleanSetting(settings: Any, name: String): Boolean? = runCatching {
        val setting = settings.javaClass.getField(name).get(settings)
        val valueField = setting.javaClass.getField("value")
        valueField.get(setting) as? Boolean
    }.getOrNull()

    private fun clearTracking() {
        owner = null
        target = null
        stand = null
        process = null
        goal = null
    }
}
