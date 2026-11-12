package dev.donut.addon.expansion.automation

import dev.donut.addon.expansion.automation.core.DialogSnapshot
import dev.donut.addon.expansion.automation.core.MenuClassifier
import dev.donut.addon.expansion.automation.core.MenuKind
import dev.donut.addon.expansion.automation.core.MarketRules
import dev.donut.addon.expansion.automation.core.MenuSnapshot
import dev.donut.addon.expansion.automation.core.ListingPriceReconciler
import dev.donut.addon.expansion.automation.core.PriceAction
import dev.donut.addon.expansion.automation.core.SlotSnapshot
import dev.donut.addon.expansion.automation.core.TextNormalization
import dev.donut.addon.expansion.automation.core.InventoryStagePlan
import dev.donut.addon.expansion.automation.core.InventoryStaging
import dev.donut.addon.expansion.automation.core.InventoryAckStatus
import dev.donut.addon.expansion.automation.core.InventoryIncreaseAckTracker
import dev.donut.addon.expansion.automation.core.InventorySyncFrame
import dev.donut.addon.expansion.automation.core.AuctionPurchaseFrame
import dev.donut.addon.expansion.automation.core.AuctionPurchaseListingRules
import dev.donut.addon.expansion.automation.core.OrderAcknowledgementRules
import dev.donut.addon.expansion.automation.core.DonutOrderCollectionRules
import dev.donut.addon.expansion.automation.core.DonutOrderDetailState
import dev.donut.addon.expansion.automation.core.OrderReviewRules
import dev.donut.addon.expansion.automation.core.InventoryTransferMachine
import dev.donut.addon.expansion.automation.core.InventoryTransferProgress
import dev.donut.addon.expansion.automation.core.InventoryPickupAction
import dev.donut.addon.expansion.automation.core.InventorySlotValue
import dev.donut.addon.expansion.automation.core.ListingLedger
import dev.donut.addon.expansion.automation.core.ListingLedgerState
import dev.donut.addon.expansion.automation.core.OwnedListingRules
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Items
import java.math.BigDecimal
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.roundToInt

private data class TradeListingReply(val atNanos: Long, val text: String)

enum class TradeListingReplyKind { LISTED, FULL, REJECTED, UNRELATED }

/** Counts inventory the way the source Product predicate does, before safe staging groups variants. */
object DonutTradeOrderStockRules {
    private val orderMoney = Regex("(?i)\\$?[ \\t]*([0-9][0-9,]*(?:\\.[0-9]+)?)[ \\t]*([kmb])?(?![a-z])")
    private const val SOURCE_SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘʀѕꜱᴛᴜᴠᴡʏᴢ"
    private const val SOURCE_SMALL_CAPS_PLAIN = "abcdefghijklmnoprsstuvwyz"

    /** OrderRestock searches the first45container slots by item type only, independent of enchant filters. */
    fun firstOwnOrderItem(menu: MenuSnapshot, containerSlotCount: Int, configuredItem: String): SlotSnapshot? {
        val limit = minOf(45, containerSlotCount.coerceAtLeast(0))
        return menu.slots.asSequence()
            .filter { it.index in 0 until limit && it.count > 0 }
            .firstOrNull { MarketRules.itemMatches(configuredItem, it) }
    }

    /** OrderRestock collects every stack of the order's item type, matching its source predicate. */
    fun isCollectedOrderItem(configuredItem: String, slot: SlotSnapshot): Boolean =
        slot.count > 0 && MarketRules.itemMatches(configuredItem, slot)

    /**
     * OrderRestock waits for the first matching item in the order menu and parses its lore with
     * ServerText.orderPrice. The player inventory tail is excluded, as Gui.containerNow does.
     */
    fun firstTopOrderPrice(
        menu: MenuSnapshot,
        containerSlotCount: Int,
        configuredItem: String,
        requiredEnchantments: Map<String, Int>,
    ): BigDecimal? {
        val limit = containerSlotCount.coerceAtLeast(0)
        val first = menu.slots.asSequence()
            .filter { it.index in 0 until limit && it.count > 0 }
            .sortedBy { it.index }
            .firstOrNull { slot ->
                MarketRules.itemMatches(configuredItem, slot) &&
                    MarketRules.enchantmentsMatch(requiredEnchantments, slot.actualEnchantments, true)
            } ?: return null
        return sourceOrderPrice(first.lore)
    }

    /** Mirrors ServerText.orderPrice's minimum qualifying token, then first dollar token fallback. */
    fun sourceOrderPrice(lore: List<String>): BigDecimal? {
        var best: BigDecimal? = null
        val normalized = lore.map(::sourceNormalize)
        for (line in normalized) {
            if ('$' !in line || listOf("price", "each", "per item", "pay").none { line.contains(it) }) continue
            for (match in orderMoney.findAll(line)) {
                val value = parseSourceMoney(match) ?: continue
                if (value.signum() > 0 && (best == null || value < best)) best = value
            }
        }
        if (best != null) return best
        for (line in normalized) {
            if ('$' !in line) continue
            val first = orderMoney.find(line) ?: continue
            return parseSourceMoney(first)?.takeIf { it.signum() > 0 }
        }
        return null
    }

    private fun parseSourceMoney(match: MatchResult): BigDecimal? {
        val base = match.groupValues[1].replace(",", "").toBigDecimalOrNull() ?: return null
        val scale = when (match.groupValues[2].lowercase(Locale.ROOT)) {
            "k" -> BigDecimal("1000")
            "m" -> BigDecimal("1000000")
            "b" -> BigDecimal("1000000000")
            else -> BigDecimal.ONE
        }
        return base.multiply(scale)
    }

    private fun sourceNormalize(value: String): String = buildString(value.length) {
        val clean = TextNormalization.stripFormatting(value)
        for (character in clean) {
            val index = SOURCE_SMALL_CAPS.indexOf(character)
            append(when {
                index >= 0 -> SOURCE_SMALL_CAPS_PLAIN[index]
                character == '➤' || character == '→' -> '-'
                else -> character
            })
        }
    }.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

    fun countMatching(menu: MenuSnapshot, configuredItem: String, requiredEnchantments: Map<String, Int>): Int =
        menu.slots.asSequence()
            .filter { it.index in 9..44 && it.count > 0 }
            .filter { MarketRules.itemMatches(configuredItem, it) }
            .filter { MarketRules.enchantmentsMatch(requiredEnchantments, it.actualEnchantments, true) }
            .sumOf { it.count }

    fun shouldCreateRestockOrder(
        matchingStock: Int,
        perListing: Int,
        stagingPlanAllowsRestock: Boolean,
        cursorEmpty: Boolean,
    ): Boolean = matchingStock >= 0 && perListing > 0 && matchingStock < perListing &&
        stagingPlanAllowsRestock && cursorEmpty
}

/** Reproduces the recovered NumberSetting range clamp and stepped value grid. */
object DonutTradeOrderSettingRules {
    fun snapInt(value: Int, minimum: Int, maximum: Int, step: Int): Int {
        require(minimum <= maximum && step > 0)
        val clamped = value.coerceIn(minimum, maximum)
        val index = ((clamped - minimum).toDouble() / step + 1.0e-9).roundToInt()
        val snapped = minimum + index * step
        return if (snapped > maximum) minimum + (index - 1) * step else snapped
    }

    fun snapFloat(value: Float, minimum: Float, maximum: Float, step: Float, default: Float): Float {
        require(minimum.isFinite() && maximum.isFinite() && step.isFinite() && minimum <= maximum && step > 0f)
        if (value.isNaN()) return default.coerceIn(minimum, maximum)
        fun decimal(value: Double) = kotlin.math.round(value * 10_000.0) / 10_000.0
        val gridMin = decimal(minimum.toDouble())
        val gridMax = decimal(maximum.toDouble())
        val gridStep = decimal(step.toDouble())
        val clamped = decimal(value.coerceIn(minimum, maximum).toDouble())
        val index = ((clamped - gridMin) / gridStep + 1.0e-9).roundToInt()
        val snapped = gridMin + index * gridStep
        val result = if (snapped > gridMax) gridMin + (index - 1) * gridStep else snapped
        return result.toFloat().coerceIn(minimum, maximum)
    }
}

/** Only source-style server outcomes can change the open listing ledger. */
object TradeListingReplyRules {
    fun classify(message: String): TradeListingReplyKind {
        val line = TextNormalization.stripFormatting(message).lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ").trim()
        return when {
            line.startsWith("you listed") -> TradeListingReplyKind.LISTED
            "too many listed items" in line -> TradeListingReplyKind.FULL
            "you cannot do this" in line || "you need to wait another" in line ||
                "don't have enough" in line || "dont have enough" in line -> TradeListingReplyKind.REJECTED
            else -> TradeListingReplyKind.UNRELATED
        }
    }
}

/** Selected-product order manager with separate observed phases for pricing, restock, and listing. */
class DonutTradeOrders : DonutAutomationModule("Donut Trade Orders") {
    private enum class OrderPrice(override val tag: String) : Tagged { BEAT("Beat Top Order"), FIXED("Fixed") }
    private enum class ProductChoice(override val tag: String) : Tagged { ONE("1"), TWO("2"), THREE("3"), FOUR("4"), FIVE("5") }
    private enum class Phase {
        IDLE, CHECK_AUCTION, TOP_ORDER, OPEN_ORDERS, CHECK_ORDER, WAIT_MY_ORDERS,
        WAIT_ORDER_DETAIL, WAIT_ORDER_COLLECT_MENU, COLLECT_ORDER, WAIT_ORDER_PRODUCT_ACK,
        WAIT_ORDER_COLLECTION_CLOSE, CREATE_ORDER, WAIT_ORDER_ACK,
        OPEN_OWN_AUCTION, WAIT_OWN_AUCTION, WAIT_OWN_LISTINGS, WAIT_OWN_LISTINGS_PAGE, WAIT_OWN_COLLECT,
        LIST, WAIT_STAGE, RECOVER_STAGE, WAIT_CONFIRM, WAIT_RESULT, STOPPED,
    }
    private enum class OwnedListingPurpose { INITIAL_SYNC, PERIODIC_SYNC, RELIST }

    private data class ProductPlan(
        val item: String,
        val query: String,
        val minimum: BigDecimal,
        val undercut: BigDecimal,
        val enchants: Map<String, Int>,
        val restock: Boolean,
        val amount: Int,
        val priceMode: OrderPrice,
        val fixedPrice: BigDecimal,
        val bidIncrement: BigDecimal,
        val maxOrderPrice: BigDecimal?,
    )

    private val selectedProduct by enumChoice("Product", ProductChoice.ONE)
    private val items = (1..5).map { text("Product $it Item", if (it == 1) "barrel" else "") }
    private val minPrices = (1..5).map { text("Product $it Min Price (each)", if (it == 1) "3k" else "1k") }
    private val enchants = (1..5).map { text("Product $it Enchantments", "") }
    private val restock = (1..5).map { boolean("Product $it Restock With Orders", false) }
    private val orderAmounts = (1..5).map { text("Product $it Order Amount", "64") }
    private val orderPriceModes = (1..5).map { enumChoice("Product $it Order Price", OrderPrice.BEAT) }
    private val fixedPrices = (1..5).map { text("Product $it Fixed Order Price", "100") }
    private val bidIncrements = (1..5).map { text("Product $it Beat By", "1") }
    private val maxPrices = (1..5).map { text("Product $it Max Order Price", "") }
    private val undercut by text("Undercut By", "100")
    private val priceCheck by int("Price Check (s)", 120, 10..600).onChange { DonutTradeOrderSettingRules.snapInt(it, 10, 600, 10) }
    private val salesCheck by int("Sales Check (s)", 20, 5..300).onChange { DonutTradeOrderSettingRules.snapInt(it, 5, 300, 5) }
    private val perListing by int("Items Per Listing", 1, 1..64)
    private val maxListings by int("Max Listings", 25, 1..50)
    private val listingDelay by float("Listing Delay (s)", 0.0f, 0.0f..5.0f)
        .onChange { DonutTradeOrderSettingRules.snapFloat(it, 0.0f, 5.0f, 0.25f, 0.0f) }
    private val relistGap by float("Relist Higher At (x undercut)", 3.0f, 1.0f..20.0f)
        .onChange { DonutTradeOrderSettingRules.snapFloat(it, 1.0f, 20.0f, 0.5f, 3.0f) }
    private val ignoreDrops by float("Ignore Drops Over (x undercut)", 20.0f, 1.0f..100.0f)
        .onChange { DonutTradeOrderSettingRules.snapFloat(it, 1.0f, 100.0f, 1.0f, 20.0f) }
    private val collectSlots by int("Collect Up To (slots)", 18, 1..36).visibleWhen { restock[selectedProduct.ordinal].get() }
    private val orderCheck by int("Order Check (s)", 15, 5..300)
        .onChange { DonutTradeOrderSettingRules.snapInt(it, 5, 300, 5) }
        .visibleWhen { restock[selectedProduct.ordinal].get() }

    private var activeProduct = -1
    private var phase = Phase.IDLE
    private var activeSellPrice = BigDecimal.ZERO
    private var lastObservedCheapest = BigDecimal.ZERO
    private var auctionScanAttempts = 0
    private var orderScanAttempts = 0
    private var nextPriceCheckAt = 0L
    private var nextOrderCheckAt = 0L
    private var nextActionAt = 0L
    private var deadline = 0L
    private var listingCount = 0
    private var listingLedger = ListingLedgerState()
    private var ledgerProductKey = ""
    private var ownedListingCount = 0
    private var ownershipSynced = false
    private var lastOwnedRefreshAt = 0L
    private var ownershipRetryAt = 0L
    private var ownedPurpose: OwnedListingPurpose? = null
    private var ownedRootMenuId = -1
    private var ownedListingsMenuId = -1
    private var ownedDeadline = 0L
    private var ownedTargetItem = ""
    private var ownedTargetEnchants: Map<String, Int> = emptyMap()
    private var ownedExpectedPage = 1
    private var ownedPageTotal: Int? = null
    private var ownedPagesVisited = 0
    private var ownedScannedListingCount = 0
    private var ownedCurrentPageToken = ""
    private val ownedPageTokens = mutableSetOf<String>()
    private val ownedInventoryIncreaseAck = InventoryIncreaseAckTracker()
    private var ownedMenuMapEpoch = 0L
    private var ownedMenuMapKey = ""
    private var ownedMenuSlotMap: Map<Int, Int> = emptyMap()
    private val ownedVisitedSlots = mutableSetOf<Int>()
    private var ownedPendingSlot = -1
    private var ownedCollectStartedAt = 0L
    private var ownedCollectedThisPass = 0
    private var ownedCollectUnresolved = false
    private var pendingRelistPrice = BigDecimal.ZERO
    private var relistRemaining = 0
    private val listingReplies = ConcurrentLinkedQueue<TradeListingReply>()
    private val ownedCollectReplies = ConcurrentLinkedQueue<TradeListingReply>()
    private var listingStartedAt = 0L
    private var restoreListingSelectionPending = false
    private var beforeListing = ""
    private var pendingListingTotal = BigDecimal.ZERO
    private var selectedHotbar = -1
    private var originalPlayer: LocalPlayer? = null
    private var originalSlot = -1
    private var ownedSlot = -1
    private var stagingPlan: InventoryStagePlan.Swap? = null
    private var stagingTransferPlan: InventoryStagePlan.Transfer? = null
    private val stagingTransfer = InventoryTransferMachine()
    private var stagingRecoveryAction: InventoryPickupAction? = null
    private var stagingRecoveryIssued = false
    private var stagingMenuId = -1
    private var stagingIssuedAt = 0L
    private var stagingStableAt = 0L
    private var stagingAbandonedItem = ""
    private var stagingItemKey = ""
    private var orderPrice = BigDecimal.ZERO
    private var lastMenuFingerprint = ""
    private var orderDetailFingerprint = ""
    private var orderDetailReadyAt = 0L
    private var orderDetailMenuId = -1
    private var orderDetailScreenIdentity = -1L
    private var orderDetailSourceMenuId = -1
    private var orderDetailSourceScreenIdentity = -1L
    private var orderDetailOpenPageToken = ""
    private var orderCollectPageToken = ""
    private var orderCollectContainerSlots = 0
    private var orderCollectReadyAt = 0L
    private var orderCollectPendingSlot = -1
    private var orderCollectSourceBefore: InventorySlotValue? = null
    private var orderCollectActions = 0
    private var retryOrderAfterMenuClose = false
    private var createSearchSubmitted = false
    private var orderAttemptStartedAt = Long.MIN_VALUE
    private val chosenEnchants = mutableSetOf<String>()
    private val orderMessages = ConcurrentLinkedQueue<TradeListingReply>()

    private companion object {
        const val OWNED_MENU_TIMEOUT_NANOS = 10_000_000_000L
        const val OWNED_COLLECT_TIMEOUT_NANOS = 15_000_000_000L
        const val MAX_OWNED_COLLECTIONS = 65
        const val MAX_OWNED_PAGES = 50
        const val MAX_ORDER_COLLECT_ACTIONS = 36
    }

    init {
        for (index in 0 until 5) {
            val selected = { selectedProduct.ordinal == index }
            items[index].visibleWhen { selected() }
            minPrices[index].visibleWhen { selected() }
            enchants[index].visibleWhen { selected() }
            restock[index].visibleWhen { selected() }
            orderAmounts[index].visibleWhen { selected() && restock[index].get() }
            orderPriceModes[index].visibleWhen { selected() && restock[index].get() }
            fixedPrices[index].visibleWhen { selected() && restock[index].get() && orderPriceModes[index].get() == OrderPrice.FIXED }
            bidIncrements[index].visibleWhen { selected() && restock[index].get() && orderPriceModes[index].get() == OrderPrice.BEAT }
            maxPrices[index].visibleWhen { selected() && restock[index].get() }
        }
        literalDescription { "Prices the selected product, manages its order stock, and lists it within configured limits." }
    }

    override fun onAutomationReset() {
        restoreOwnedSelection()
        activeProduct = -1
        phase = Phase.IDLE
        activeSellPrice = BigDecimal.ZERO
        lastObservedCheapest = BigDecimal.ZERO
        auctionScanAttempts = 0
        orderScanAttempts = 0
        nextPriceCheckAt = 0L
        nextOrderCheckAt = 0L
        nextActionAt = 0L
        deadline = 0L
        listingCount = 0
        listingLedger = ListingLedgerState()
        ledgerProductKey = ""
        ownedListingCount = 0
        ownershipSynced = false
        lastOwnedRefreshAt = 0L
        ownershipRetryAt = 0L
        ownedPurpose = null
        ownedRootMenuId = -1
        ownedListingsMenuId = -1
        ownedDeadline = 0L
        ownedTargetItem = ""
        ownedTargetEnchants = emptyMap()
        resetOwnedPages()
        ownedInventoryIncreaseAck.reset()
        ownedMenuMapEpoch = 0L
        ownedMenuMapKey = ""
        ownedMenuSlotMap = emptyMap()
        ownedVisitedSlots.clear()
        ownedPendingSlot = -1
        ownedCollectStartedAt = 0L
        ownedCollectedThisPass = 0
        ownedCollectUnresolved = false
        pendingRelistPrice = BigDecimal.ZERO
        relistRemaining = 0
        listingReplies.clear()
        ownedCollectReplies.clear()
        listingStartedAt = 0L
        restoreListingSelectionPending = false
        beforeListing = ""
        pendingListingTotal = BigDecimal.ZERO
        selectedHotbar = -1
        originalPlayer = null
        originalSlot = -1
        ownedSlot = -1
        stagingPlan = null
        stagingTransferPlan = null
        stagingTransfer.reset()
        stagingRecoveryAction = null
        stagingRecoveryIssued = false
        stagingMenuId = -1
        stagingIssuedAt = 0L
        stagingStableAt = 0L
        stagingAbandonedItem = ""
        stagingItemKey = ""
        orderPrice = BigDecimal.ZERO
        lastMenuFingerprint = ""
        orderDetailFingerprint = ""
        orderDetailReadyAt = 0L
        orderDetailMenuId = -1
        orderDetailScreenIdentity = -1L
        orderDetailSourceMenuId = -1
        orderDetailSourceScreenIdentity = -1L
        orderDetailOpenPageToken = ""
        orderCollectPageToken = ""
        orderCollectContainerSlots = 0
        orderCollectReadyAt = 0L
        orderCollectPendingSlot = -1
        orderCollectSourceBefore = null
        orderCollectActions = 0
        retryOrderAfterMenuClose = false
        resetMenuPageAck()
        resetMenuSlotChangeAck()
        createSearchSubmitted = false
        orderAttemptStartedAt = Long.MIN_VALUE
        chosenEnchants.clear()
        orderMessages.clear()
    }

    override fun onAutomationDisabled() { restoreOwnedInventoryCursor(stagingTransfer) }

    @Suppress("unused")
    private val incoming = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING) return@handler
        val text = (event.packet as? ClientboundSystemChatPacket)?.content?.string ?: return@handler
        if (orderMessages.size >= 64) orderMessages.poll()
        orderMessages.offer(TradeListingReply(System.nanoTime(), text))
        if (ownedCollectReplies.size >= 64) ownedCollectReplies.poll()
        ownedCollectReplies.offer(TradeListingReply(System.nanoTime(), text))
        if (listingReplies.size >= 64) listingReplies.poll()
        listingReplies.offer(TradeListingReply(System.nanoTime(), text))
    }

    override fun onInventorySyncFrame(update: InventorySyncFrame) {
        if (ownedInventoryIncreaseAck.status() == InventoryAckStatus.PENDING) {
            ownedInventoryIncreaseAck.observe(update, ownedMenuMapEpoch)
        }
    }

    private fun updateOwnedMenuSlotMap(frame: LiveAutomationFrame) {
        val menu = frame.snapshot.menu
        val mapping = menu?.let {
            frame.menu.slots.mapIndexedNotNull { menuIndex, slot ->
                if (slot.container === frame.player.inventory) menuIndex to slot.containerSlot else null
            }.filter { it.second in 9..44 }.toMap()
        }.orEmpty()
        val key = buildString {
            append(frame.snapshot.session.generation).append(':')
            append(frame.snapshot.session.connection).append(':')
            append(menu?.menuId ?: -1).append(':')
            append(menu?.screenIdentity ?: 0L).append(':')
            append(mapping.entries.sortedBy { it.key }.joinToString(",") { "${it.key}>${it.value}" })
        }
        if (key != ownedMenuMapKey) {
            ownedMenuMapKey = key
            ownedMenuMapEpoch++
            ownedMenuSlotMap = mapping.toMap()
        }
    }

    private fun purchaseFrame(frame: LiveAutomationFrame): AuctionPurchaseFrame? {
        val menu = frame.snapshot.menu ?: return null
        if (ownedMenuSlotMap.isEmpty() || ownedMenuMapEpoch <= 0L) return null
        val containerSlots = frame.menu.slots.count { it.container !== frame.player.inventory }
        return AuctionPurchaseFrame(
            frame.snapshot, frame.player.gameProfile.name, menu.menuId, ownedMenuSlotMap.toMap(),
            ownedMenuMapEpoch, menu.stateId.toLong(), containerSlots,
        )
    }

    private fun beginOwnedInventoryIncreaseAck(
        frame: LiveAutomationFrame,
        listing: SlotSnapshot,
        expectedIncrease: Int = listing.count,
    ): Boolean {
        val snapshot = purchaseFrame(frame) ?: return false
        return ownedInventoryIncreaseAck.begin(
            snapshot,
            listing.itemId,
            listing.actualEnchantments,
            listing.procurementFingerprint,
            listing.name,
            AuctionPurchaseListingRules.procurementLore(listing.lore),
            expectedIncrease,
        )
    }

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        val frame = captureFrame() ?: return@handler
        updateOwnedMenuSlotMap(frame)
        val now = System.nanoTime()
        if (restoreListingSelectionPending && frame.snapshot.menu == null && frame.snapshot.dialog == null) {
            restoreListingSelectionPending = false
            restoreSelection(frame)
            return@handler
        }
        val selected = selectedProduct.ordinal.coerceIn(0, 4)
        if (activeProduct != selected) {
            activeProduct = selected
            phase = Phase.IDLE
            activeSellPrice = BigDecimal.ZERO
            lastObservedCheapest = BigDecimal.ZERO
            auctionScanAttempts = 0
            orderScanAttempts = 0
            nextPriceCheckAt = 0L
            nextOrderCheckAt = 0L
            nextActionAt = 0L
            deadline = 0L
            listingCount = 0
            listingLedger = ListingLedgerState()
            ledgerProductKey = ""
            ownedListingCount = 0
            ownershipSynced = false
            lastOwnedRefreshAt = 0L
            ownershipRetryAt = 0L
            ownedPurpose = null
            ownedRootMenuId = -1
            ownedListingsMenuId = -1
            ownedDeadline = 0L
            ownedTargetItem = ""
            ownedTargetEnchants = emptyMap()
            resetOwnedPages()
            ownedInventoryIncreaseAck.reset()
            ownedVisitedSlots.clear()
            ownedPendingSlot = -1
            ownedCollectedThisPass = 0
            ownedCollectUnresolved = false
            pendingRelistPrice = BigDecimal.ZERO
            relistRemaining = 0
            listingReplies.clear()
            ownedCollectReplies.clear()
            listingStartedAt = 0L
            restoreListingSelectionPending = false
            if (frame.snapshot.menu != null || frame.snapshot.dialog != null) return@handler
        }
        val plan = productPlan(selected) ?: return@handler
        val productKey = OwnedListingRules.productKey(plan.item, plan.enchants)
        if (ledgerProductKey != productKey) {
            restoreOwnedSelection()
            phase = Phase.IDLE
            activeSellPrice = BigDecimal.ZERO
            listingLedger = ListingLedgerState()
            auctionScanAttempts = 0
            orderScanAttempts = 0
            listingCount = 0
            ownedListingCount = 0
            ownershipSynced = false
            lastOwnedRefreshAt = 0L
            ownershipRetryAt = 0L
            ownedPurpose = null
            ownedRootMenuId = -1
            ownedListingsMenuId = -1
            resetOwnedPages()
            ownedInventoryIncreaseAck.reset()
            ownedVisitedSlots.clear()
            ownedPendingSlot = -1
            ownedCollectedThisPass = 0
            ownedCollectUnresolved = false
            listingReplies.clear()
            ownedCollectReplies.clear()
            listingStartedAt = 0L
            pendingRelistPrice = BigDecimal.ZERO
            relistRemaining = 0
            ledgerProductKey = productKey
            if (frame.snapshot.menu != null || frame.snapshot.dialog != null) return@handler
        }
        if (phase == Phase.STOPPED) return@handler
        if (now < nextActionAt) return@handler
        if (deadline > 0L && now > deadline) {
            onPhaseTimeout(now, frame)
            return@handler
        }

        val menu = frame.snapshot.menu
        when (phase) {
            Phase.IDLE -> {
                if (menu != null || frame.snapshot.dialog != null) return@handler
                if (!ownershipSynced) {
                    if (now >= ownershipRetryAt) beginOwnedListingScan(frame, OwnedListingPurpose.INITIAL_SYNC, now)
                    return@handler
                }
                if (now - lastOwnedRefreshAt >= salesCheck * 1_000_000_000L) {
                    beginOwnedListingScan(frame, OwnedListingPurpose.PERIODIC_SYNC, now)
                    return@handler
                }
                if (activeSellPrice.signum() <= 0 || now >= nextPriceCheckAt) {
                    if (command(frame, "ah ${plan.query}")) {
                        if (auctionScanAttempts == 0) auctionScanAttempts = 1
                        phase = Phase.CHECK_AUCTION
                        nextPriceCheckAt = now + priceCheck * 1_000_000_000L
                        deadline = now + 12_000_000_000L
                    }
                    return@handler
                }
                if (ownedListingCount < maxListings) {
                    val inventory = frame.snapshot.playerInventoryMenu ?: return@handler
                    val stock = stockPlan(frame, plan.item, plan.enchants)
                    when (stock) {
                        is InventoryStagePlan.Ready -> { selectedHotbar = stock.hotbarSlot; phase = Phase.LIST; return@handler }
                        is InventoryStagePlan.Swap -> {
                            issueStockSwap(frame, stock, plan.item)
                            return@handler
                        }
                        is InventoryStagePlan.Transfer -> {
                            issueStockTransfer(frame, stock, plan.item)
                            return@handler
                        }
                        InventoryStagePlan.Missing, InventoryStagePlan.Unsafe -> Unit
                    }
                    val matchingStock = DonutTradeOrderStockRules.countMatching(inventory, plan.item, plan.enchants)
                    if (!DonutTradeOrderStockRules.shouldCreateRestockOrder(
                            matchingStock, perListing, stock is InventoryStagePlan.Missing, inventory.cursorEmpty,
                        )) {
                        // A failed safe staging plan is not a stock shortage. Buying another order cannot resolve
                        // incompatible variants, a full hotbar, a held cursor stack, or incomplete inventory data.
                        nextActionAt = now + 1_000_000_000L
                        return@handler
                    }
                }
                if (plan.restock && ownedListingCount < maxListings && now >= nextOrderCheckAt) {
                    beginRestock(frame, plan, now)
                }
            }
            Phase.CHECK_AUCTION -> checkAuction(frame, plan, now)
            Phase.TOP_ORDER -> checkTopOrder(frame, plan, now)
            Phase.OPEN_ORDERS -> {
                if (menu != null || frame.snapshot.dialog != null) return@handler
                if (command(frame, "orders")) { phase = Phase.CHECK_ORDER; deadline = now + 10_000_000_000L }
            }
            Phase.CHECK_ORDER -> checkOrdersMenu(frame, plan, now)
            Phase.WAIT_MY_ORDERS -> waitForOwnOrders(frame, plan, now)
            Phase.WAIT_ORDER_DETAIL -> inspectOwnOrder(frame, plan, now)
            Phase.WAIT_ORDER_COLLECT_MENU -> waitForOrderCollectMenu(frame, now)
            Phase.COLLECT_ORDER -> collectOwnedOrder(frame, plan, now)
            Phase.WAIT_ORDER_PRODUCT_ACK -> waitForOrderProductAck(frame, now)
            Phase.WAIT_ORDER_COLLECTION_CLOSE -> waitForOrderCollectionClose(frame, now)
            Phase.CREATE_ORDER -> createOrderDialog(frame, plan, now)
            Phase.WAIT_ORDER_ACK -> waitForOrderAcknowledgement(now)
            Phase.OPEN_OWN_AUCTION -> {
                if (menu == null && frame.snapshot.dialog == null) {
                    beginOwnedListingScan(frame, ownedPurpose ?: OwnedListingPurpose.RELIST, now)
                }
            }
            Phase.WAIT_OWN_AUCTION -> openOwnedListingsRoot(frame, now)
            Phase.WAIT_OWN_LISTINGS -> finishOwnedListingsOpen(frame, now)
            Phase.WAIT_OWN_LISTINGS_PAGE -> waitForOwnedListingsPage(frame, now)
            Phase.WAIT_OWN_COLLECT -> waitForOwnedListingCollection(frame, now)
            Phase.LIST -> listProduct(frame, plan, now)
            Phase.WAIT_STAGE -> when {
                stagingPlan != null -> verifyStockSwap(frame, now)
                stagingTransferPlan != null -> verifyStockTransfer(frame, now)
                else -> failStockStage(frame)
            }
            Phase.RECOVER_STAGE -> verifyStockRecovery(frame, now)
            Phase.WAIT_CONFIRM -> confirmListing(frame, plan, now)
            Phase.WAIT_RESULT -> verifyListing(frame, now)
            Phase.STOPPED -> Unit
        }
    }

    private fun productPlan(index: Int): ProductPlan? {
        val item = items[index].get().trim()
        val query = MarketRules.safeCommandFragment(item) ?: return null
        if (!resolveProductItem(item)) return null
        val minimum = MarketRules.parseAmount(minPrices[index].get())?.takeIf { it.signum() > 0 } ?: return null
        val undercutAmount = MarketRules.parseAmount(undercut)?.takeIf { it.signum() >= 0 } ?: return null
        val required = MarketRules.parseEnchantments(enchants[index].get()) ?: return null
        val useRestock = restock[index].get()
        val amount = orderAmounts[index].get().trim().toIntOrNull() ?: 0
        val fixed = MarketRules.parseAmount(fixedPrices[index].get()) ?: BigDecimal.ZERO
        val bid = MarketRules.parseAmount(bidIncrements[index].get()) ?: BigDecimal.ZERO
        val max = maxPrices[index].get().trim().takeIf(String::isNotEmpty)?.let(MarketRules::parseAmount)
        if (useRestock && (amount !in 1..1_000_000 || bid.signum() <= 0 ||
                orderPriceModes[index].get() == OrderPrice.FIXED && fixed.signum() <= 0 ||
                maxPrices[index].get().isNotBlank() && (max == null || max.signum() <= 0))) return null
        return ProductPlan(item, query, minimum, undercutAmount, required, useRestock, amount,
            orderPriceModes[index].get(), fixed, bid, max)
    }

    private fun resolveProductItem(configured: String): Boolean {
        val itemId = MarketRules.safeItemId(configured) ?: return false
        val identifier = Identifier.tryParse(if (':' in itemId) itemId else "minecraft:$itemId") ?: return false
        val item = BuiltInRegistries.ITEM.get(identifier).orElse(null)?.value() ?: return false
        return item != Items.AIR
    }

    private fun checkAuction(frame: LiveAutomationFrame, plan: ProductPlan, now: Long) {
        val menu = frame.snapshot.menu ?: return
        if (MenuClassifier.classify(menu) != MenuKind.AUCTION) return
        if (!hasAuctionResultsContent(frame)) return
        val containerSlots = frame.menu.slots.count { it.container !== frame.player.inventory }
        val matching = menu.slots.asSequence()
            .filter { it.index in 0 until minOf(containerSlots, 45) }
            .filter { matchesProductItem(plan.item, it) }
            .map { it to MarketRules.parseListing(it) }
            .filter { (_, listing) -> MarketRules.enchantmentsMatch(plan.enchants, listing.enchantments, true) }
            .toList()
        val myName = frame.player.gameProfile.name
        val cheapest = matching.asSequence().filterNot { (slot, _) ->
            OwnedListingRules.isOwnAuctionListing(slot, myName, activeSellPrice, ownedListingCount > 0)
        }.mapNotNull { (_, listing) -> listing.unitPrice }.minOrNull()
        val decision = ListingPriceReconciler.decide(
            currentAsk = activeSellPrice,
            cheapestUnit = cheapest,
            quoteConfirmed = true,
            hasMatchingListings = matching.isNotEmpty(),
            minimumUnit = plan.minimum,
            undercut = plan.undercut,
            relistGap = BigDecimal(relistGap.toString()),
            ignoreDrops = BigDecimal(ignoreDrops.toString()),
            openListings = ownedListingCount,
        )
        when (decision.action) {
            PriceAction.WAIT, PriceAction.KEEP, PriceAction.IGNORE_DEEP_DROP -> Unit
            PriceAction.SET -> activeSellPrice = decision.nextUnitPrice ?: activeSellPrice
            PriceAction.RELIST -> {
                val nextAsk = decision.nextUnitPrice ?: activeSellPrice
                if (ownedListingCount > 0) {
                    pendingRelistPrice = nextAsk
                    ownedTargetItem = plan.item
                    ownedTargetEnchants = plan.enchants
                    ownedPurpose = OwnedListingPurpose.RELIST
                    ownedVisitedSlots.clear()
                    ownedCollectedThisPass = 0
                    ownedCollectUnresolved = false
                    if (closeMenu(frame)) {
                        phase = Phase.OPEN_OWN_AUCTION
                        nextActionAt = 0L
                        deadline = 0L
                    }
                    return
                }
                activeSellPrice = nextAsk
            }
        }
        auctionScanAttempts = 0
        if (closeMenu(frame)) {
            deadline = 0L
            nextActionAt = now + (listingDelay * 1_000_000_000.0).toLong()
            phase = Phase.IDLE
        }
    }

    /** Mirrors the source's bounded auction-content probe before treating an empty page as a quote. */
    private fun hasAuctionResultsContent(frame: LiveAutomationFrame): Boolean {
        val menu = frame.snapshot.menu ?: return false
        val containerSlots = frame.menu.slots.count { it.container !== frame.player.inventory }
        val slot49 = menu.slot(49)
        if (containerSlots > 49 && slot49 != null && slot49.itemId.isNotBlank() && slot49.count > 0) return true
        return (0..10).any { index -> menu.slot(index)?.let { it.itemId.isNotBlank() && it.count > 0 } == true }
    }

    private fun beginOwnedListingScan(frame: LiveAutomationFrame, purpose: OwnedListingPurpose, now: Long) {
        if (frame.snapshot.menu != null || frame.snapshot.dialog != null) return
        val selected = activeProduct.coerceIn(0, 4)
        ownedTargetItem = items[selected].get().trim()
        ownedTargetEnchants = MarketRules.parseEnchantments(enchants[selected].get()).orEmpty()
        val owner = MarketRules.safePlayerName(frame.player.gameProfile.name)
        if (owner == null) {
            failOwnedListingScan(now, frame)
            return
        }
        ownedPurpose = purpose
        resetOwnedPages()
        ownedInventoryIncreaseAck.reset()
        ownedVisitedSlots.clear()
        ownedCollectUnresolved = false
        if (purpose != OwnedListingPurpose.RELIST) ownedCollectedThisPass = 0
        ownedRootMenuId = -1
        ownedListingsMenuId = -1
        ownedDeadline = now + OWNED_MENU_TIMEOUT_NANOS
        if (!beginMenuOpenAck(frame, frame.menu.containerId) || !command(frame, "ah $owner")) {
            ownershipRetryAt = now + salesCheck * 1_000_000_000L
            failOwnedListingScan(now, frame)
            return
        }
        phase = Phase.WAIT_OWN_AUCTION
        deadline = ownedDeadline
    }

    private fun openOwnedListingsRoot(frame: LiveAutomationFrame, now: Long) {
        val menuId = openedMenuId()
        val menu = frame.snapshot.menu
        if (menuId == null || menu?.menuId != menuId) {
            if (now > ownedDeadline) failOwnedListingScan(now, frame)
            return
        }
        val root = menu.slot(51)
        if (!OwnedListingRules.isAuctionRoot(menu) || MenuClassifier.classify(menu) != MenuKind.AUCTION ||
            root == null || !(root.itemId.endsWith(":chest", true) || root.itemId.endsWith(":trapped_chest", true))) {
            failOwnedListingScan(now, frame)
            return
        }
        ownedRootMenuId = menu.menuId
        beginMenuOpenAck(frame, menu.menuId)
        if (!click(frame, 51)) {
            failOwnedListingScan(now, frame)
            return
        }
        phase = Phase.WAIT_OWN_LISTINGS
        deadline = now + OWNED_MENU_TIMEOUT_NANOS
        ownedDeadline = deadline
    }

    private fun finishOwnedListingsOpen(frame: LiveAutomationFrame, now: Long) {
        val openedId = openedMenuId()
        val menu = frame.snapshot.menu
        if (openedId == null) {
            if (now > ownedDeadline) failOwnedListingScan(now, frame)
            return
        }
        if (menu == null || menu.menuId != openedId || menu.menuId == ownedRootMenuId) return
        ownedListingsMenuId = menu.menuId
        processOwnedListingsPage(frame, now)
    }

    private fun waitForOwnedListingsPage(frame: LiveAutomationFrame, now: Long) {
        val menu = frame.snapshot.menu
        if (menu == null || menu.menuId != ownedListingsMenuId) {
            if (now > ownedDeadline) failOwnedListingScan(now, frame)
            return
        }
        val containerSlots = frame.menu.slots.count { it.container !== frame.player.inventory }
        val currentPage = OwnedListingRules.yourItemsPage(menu, containerSlots)
        val token = currentPage?.token.orEmpty()
        when (menuPageAckStatus(frame, token)) {
            InventoryAckStatus.ACKNOWLEDGED -> {
                resetMenuPageAck()
                processOwnedListingsPage(frame, now)
            }
            InventoryAckStatus.PENDING -> if (now > ownedDeadline) failOwnedListingScan(now, frame)
            InventoryAckStatus.REJECTED, InventoryAckStatus.ABANDONED, InventoryAckStatus.IDLE ->
                failOwnedListingScan(now, frame)
        }
    }

    private fun processOwnedListingsPage(frame: LiveAutomationFrame, now: Long) {
        val menu = frame.snapshot.menu
        val containerSlots = frame.menu.slots.count { it.container !== frame.player.inventory }
        val purpose = ownedPurpose
        if (menu == null || menu.menuId != ownedListingsMenuId || !menu.cursorEmpty || purpose == null) {
            failOwnedListingScan(now, frame)
            return
        }
        val page = OwnedListingRules.yourItemsPage(menu, containerSlots)
        if (page == null || ownedPagesVisited >= MAX_OWNED_PAGES) {
            failOwnedListingScan(now, frame)
            return
        }
        if (page.pageNumber != null) {
            if (page.pageNumber != ownedExpectedPage || ownedPageTotal?.let { it != page.totalPages } == true) {
                failOwnedListingScan(now, frame)
                return
            }
            ownedPageTotal = page.totalPages
        } else if (ownedPageTotal != null) {
            failOwnedListingScan(now, frame)
            return
        }
        if (!ownedPageTokens.add(page.token)) {
            failOwnedListingScan(now, frame)
            return
        }
        ownedPagesVisited++
        ownedCurrentPageToken = page.token
        if (page.pageNumber != null) ownedExpectedPage = page.pageNumber + 1

        ownedScannedListingCount += countOwnedListingsOnPage(menu, containerSlots)
        if (purpose == OwnedListingPurpose.RELIST) {
            val candidate = collectableOwnedListing(menu, containerSlots, frame.player.gameProfile.name)
            if (candidate != null) {
                beginOwnedListingCollection(frame, candidate, now)
                return
            }
        }

        val nextSlot = page.nextSlot
        if (nextSlot != null) {
            if (ownedPagesVisited >= MAX_OWNED_PAGES || page.pageNumber != null && page.pageNumber >= page.totalPages!! ||
                !beginMenuPageAck(frame, containerSlots, page.token) || !click(frame, nextSlot)) {
                resetMenuPageAck()
                failOwnedListingScan(now, frame)
                return
            }
            ownedCurrentPageToken = page.token
            deadline = now + OWNED_MENU_TIMEOUT_NANOS
            ownedDeadline = deadline
            phase = Phase.WAIT_OWN_LISTINGS_PAGE
            return
        }
        if (!OwnedListingRules.isTerminalYourItemsPage(menu, containerSlots, page) ||
            page.totalPages != null && page.pageNumber != page.totalPages) {
            failOwnedListingScan(now, frame)
            return
        }
        completeOwnedListingScan(frame, now, purpose)
    }

    private fun countOwnedListingsOnPage(menu: dev.donut.addon.expansion.automation.core.MenuSnapshot, containerSlots: Int): Int =
        menu.slots.asSequence()
            .filter { it.index in 0 until minOf(containerSlots, 45) }
            .filter { matchesProductItem(ownedTargetItem, it) }
            .filterNot(OwnedListingRules::isInactiveListing)
            .count { MarketRules.enchantmentsMatch(ownedTargetEnchants, MarketRules.parseListing(it).enchantments, true) }

    private fun collectableOwnedListing(
        menu: dev.donut.addon.expansion.automation.core.MenuSnapshot,
        containerSlots: Int,
        owner: String,
    ): SlotSnapshot? = menu.slots.asSequence()
        .filter { it.index in 0 until minOf(containerSlots, 45) && it.index !in ownedVisitedSlots }
        .filter { matchesProductItem(ownedTargetItem, it) }
        .filter { MarketRules.enchantmentsMatch(ownedTargetEnchants, MarketRules.parseListing(it).enchantments, true) }
        .filter { slot ->
            val ask = MarketRules.parseListing(slot).unitPrice ?: return@filter false
            OwnedListingRules.isCollectable(slot, owner, ask)
        }
        .firstOrNull()

    private fun beginOwnedListingCollection(frame: LiveAutomationFrame, candidate: SlotSnapshot, now: Long) {
        if (ownedCollectedThisPass >= MAX_OWNED_COLLECTIONS || !hasOwnedInventorySpace(frame, candidate) ||
            !beginMenuSlotChangeAck(frame, candidate.index) || !beginOwnedInventoryIncreaseAck(frame, candidate)) {
            resetMenuSlotChangeAck()
            ownedInventoryIncreaseAck.reset()
            failOwnedListingScan(now, frame)
            return
        }
        ownedPendingSlot = candidate.index
        ownedCollectStartedAt = System.nanoTime()
        if (click(frame, candidate.index)) {
            deadline = now + OWNED_COLLECT_TIMEOUT_NANOS
            ownedDeadline = deadline
            phase = Phase.WAIT_OWN_COLLECT
        } else {
            resetMenuSlotChangeAck()
            ownedInventoryIncreaseAck.reset()
            failOwnedListingScan(now, frame)
        }
    }

    private fun completeOwnedListingScan(frame: LiveAutomationFrame, now: Long, purpose: OwnedListingPurpose) {
        // This method is reached only after every acknowledged page is visited, so a missing
        // listing can now be reconciled as a sale. Our own collection is already debited above.
        listingLedger = ListingLedger.reconcileOwnedListings(listingLedger, ownedScannedListingCount, true)
        ownedListingCount = listingLedger.open
        listingCount = listingLedger.listedLifetime
        ownershipSynced = true
        lastOwnedRefreshAt = now
        ownershipRetryAt = 0L

        if (purpose == OwnedListingPurpose.RELIST) {
            if (ownedListingCount > 0 || ownedCollectUnresolved || pendingRelistPrice.signum() <= 0) {
                failOwnedListingScan(now, frame)
                return
            }
            if (!closeMenu(frame)) {
                failOwnedListingScan(now, frame)
                return
            }
            activeSellPrice = pendingRelistPrice
            relistRemaining = minOf(ownedCollectedThisPass, (maxListings - ownedListingCount).coerceAtLeast(0))
            pendingRelistPrice = BigDecimal.ZERO
            ownedPurpose = null
            ownedRootMenuId = -1
            ownedListingsMenuId = -1
            deadline = 0L
            phase = if (relistRemaining > 0) Phase.LIST else Phase.IDLE
            nextActionAt = now + (listingDelay * 1_000_000_000.0).toLong()
        } else if (closeMenu(frame)) {
            ownedPurpose = null
            ownedRootMenuId = -1
            ownedListingsMenuId = -1
            phase = Phase.IDLE
            deadline = 0L
        } else failOwnedListingScan(now, frame)
    }

    private fun waitForOwnedListingCollection(frame: LiveAutomationFrame, now: Long) {
        val menu = frame.snapshot.menu
        if (menu == null || menu.menuId != ownedListingsMenuId || !menu.cursorEmpty || ownedPurpose != OwnedListingPurpose.RELIST) {
            failOwnedListingScan(now, frame)
            return
        }
        if (consumeAlreadyBought()) {
            ownedCollectUnresolved = true
            failOwnedListingScan(now, frame)
            return
        }
        val slotStatus = menuSlotChangeAckStatus(frame)
        val inventoryStatus = ownedInventoryIncreaseAck.status(frame.snapshot.playerInventoryMenu)
        when {
            slotStatus == InventoryAckStatus.ACKNOWLEDGED && inventoryStatus == InventoryAckStatus.ACKNOWLEDGED ->
                completeOwnedListingClick(now, frame)
            slotStatus in setOf(InventoryAckStatus.REJECTED, InventoryAckStatus.ABANDONED, InventoryAckStatus.IDLE) ||
                inventoryStatus in setOf(InventoryAckStatus.REJECTED, InventoryAckStatus.ABANDONED, InventoryAckStatus.IDLE) ->
                failOwnedListingScan(now, frame)
            now > ownedDeadline -> failOwnedListingScan(now, frame)
        }
    }

    private fun completeOwnedListingClick(now: Long, frame: LiveAutomationFrame) {
        listingLedger = ListingLedger.collected(listingLedger)
        ownedListingCount = listingLedger.open
        ownedCollectedThisPass++
        ownedInventoryIncreaseAck.reset()
        resetMenuSlotChangeAck()
        if (!closeMenu(frame)) {
            failOwnedListingScan(now, frame)
            return
        }
        ownedRootMenuId = -1
        ownedListingsMenuId = -1
        resetOwnedPages()
        phase = Phase.OPEN_OWN_AUCTION
        deadline = 0L
        nextActionAt = now + 200_000_000L
    }

    private fun hasOwnedInventorySpace(frame: LiveAutomationFrame, listing: SlotSnapshot): Boolean {
        val inventory = frame.snapshot.playerInventoryMenu ?: return false
        return OwnedListingRules.compatibleInventoryCapacity(listing, inventory.slots) >= listing.count
    }

    private fun resetOwnedPages() {
        resetMenuPageAck()
        ownedExpectedPage = 1
        ownedPageTotal = null
        ownedPagesVisited = 0
        ownedScannedListingCount = 0
        ownedCurrentPageToken = ""
        ownedPageTokens.clear()
        ownedVisitedSlots.clear()
    }

    private fun consumeAlreadyBought(): Boolean {
        val deferred = ArrayList<TradeListingReply>()
        var found = false
        while (true) {
            val message = ownedCollectReplies.poll() ?: break
            val clean = normalized(message.text)
            if (!found && message.atNanos >= ownedCollectStartedAt && clean.contains("already bought")) found = true
            else deferred += message
        }
        deferred.forEach(ownedCollectReplies::offer)
        return found
    }

    private fun failOwnedListingScan(now: Long, frame: LiveAutomationFrame? = null) {
        resetMenuSlotChangeAck()
        resetMenuPageAck()
        ownedInventoryIncreaseAck.reset()
        if (frame != null) {
            val id = frame.snapshot.menu?.menuId
            if (id != null && id in setOf(ownedRootMenuId, ownedListingsMenuId)) closeMenu(frame)
        }
        ownedPurpose = null
        ownedRootMenuId = -1
        ownedListingsMenuId = -1
        ownedDeadline = 0L
        ownedPendingSlot = -1
        resetOwnedPages()
        pendingRelistPrice = BigDecimal.ZERO
        relistRemaining = 0
        deadline = 0L
        phase = Phase.IDLE
        ownershipSynced = false
        ownershipRetryAt = now + salesCheck * 1_000_000_000L
        lastOwnedRefreshAt = now
        nextActionAt = maxOf(nextActionAt, ownershipRetryAt)
    }

    private fun beginRestock(frame: LiveAutomationFrame, plan: ProductPlan, now: Long) {
        nextOrderCheckAt = now + orderCheck * 1_000_000_000L
        if (plan.priceMode == OrderPrice.FIXED) {
            orderPrice = applyOrderCap(plan.fixedPrice, plan) ?: run { phase = Phase.IDLE; return }
            if (activeSellPrice.signum() > 0 && orderPrice >= activeSellPrice) { phase = Phase.IDLE; return }
            phase = Phase.OPEN_ORDERS
            deadline = now + 10_000_000_000L
        } else if (userCommand(frame, "order ${plan.query}")) {
            orderScanAttempts++
            phase = Phase.TOP_ORDER
            deadline = now + 8_000_000_000L
        }
    }

    private fun checkTopOrder(frame: LiveAutomationFrame, plan: ProductPlan, now: Long) {
        val menu = frame.snapshot.menu ?: return
        if (MenuClassifier.classify(menu) != MenuKind.ORDER) return
        val containerSlots = frame.menu.slots.count { it.container !== frame.player.inventory }
        val top = DonutTradeOrderStockRules.firstTopOrderPrice(menu, containerSlots, plan.item, plan.enchants)
        if (top == null) {
            if (now >= deadline && closeMenu(frame)) finishOrderPriceAttempt(now)
            return
        }
        orderScanAttempts = 0
        val roundedTop = top.setScale(0, java.math.RoundingMode.FLOOR)
        orderPrice = applyOrderCap(roundedTop + plan.bidIncrement, plan) ?: run {
            if (closeMenu(frame)) { phase = Phase.IDLE; deadline = 0L }
            return
        }
        if (activeSellPrice.signum() > 0 && orderPrice >= activeSellPrice) {
            if (closeMenu(frame)) { phase = Phase.IDLE; deadline = 0L; nextActionAt = now + orderCheck * 1_000_000_000L }
            return
        }
        if (closeMenu(frame)) { phase = Phase.OPEN_ORDERS; deadline = now + 10_000_000_000L }
    }

    private fun applyOrderCap(value: BigDecimal, plan: ProductPlan): BigDecimal? {
        val capped = plan.maxOrderPrice?.let(value::min) ?: value
        return capped.takeIf { it.signum() > 0 }
    }

    private fun checkOrdersMenu(frame: LiveAutomationFrame, plan: ProductPlan, now: Long) {
        val menu = frame.snapshot.menu ?: return
        if (MenuClassifier.classify(menu) != MenuKind.ORDER) return
        if (isOwnOrdersMenu(menu)) {
            inspectOrderList(frame, plan, now)
            return
        }
        val yourOrders = menu.slots.firstOrNull { slot ->
            slot.index < 54 && slot.searchableText.contains("your order") && !slot.searchableText.contains("new order")
        } ?: menu.slot(51)?.takeIf { it.itemId.isNotBlank() }
        if (yourOrders != null && click(frame, yourOrders.index)) {
            lastMenuFingerprint = menuFingerprint(menu)
            phase = Phase.WAIT_MY_ORDERS
            deadline = now + 6_000_000_000L
        }
    }

    private fun waitForOwnOrders(frame: LiveAutomationFrame, plan: ProductPlan, now: Long) {
        val menu = frame.snapshot.menu ?: return
        if (menuFingerprint(menu) == lastMenuFingerprint) return
        if (MenuClassifier.classify(menu) != MenuKind.ORDER) { retryLater(now); return }
        inspectOrderList(frame, plan, now)
    }

    private fun inspectOrderList(frame: LiveAutomationFrame, plan: ProductPlan, now: Long) {
        val menu = frame.snapshot.menu ?: return
        val containerSlots = frame.menu.slots.count { it.container !== frame.player.inventory }
        val existing = DonutTradeOrderStockRules.firstOwnOrderItem(menu, containerSlots, plan.item)
        if (existing != null) {
            lastMenuFingerprint = menuFingerprint(menu)
            orderDetailFingerprint = ""
            orderDetailReadyAt = 0L
            orderDetailMenuId = -1
            orderDetailScreenIdentity = -1L
            orderDetailSourceMenuId = menu.menuId
            orderDetailSourceScreenIdentity = menu.screenIdentity
            orderDetailOpenPageToken = "order-detail:${menu.menuId}:${menu.screenIdentity}:$now"
            orderCollectPageToken = ""
            orderCollectContainerSlots = 0
            orderCollectReadyAt = 0L
            orderCollectPendingSlot = -1
            orderCollectSourceBefore = null
            orderCollectActions = 0
            retryOrderAfterMenuClose = false
            beginMenuOpenAck(frame, menu.menuId)
            val containerSlots = frame.menu.slots.count { it.container !== frame.player.inventory }
            if (containerSlots > 0) beginMenuPageAck(frame, containerSlots, orderDetailOpenPageToken)
            if (click(frame, existing.index)) { phase = Phase.WAIT_ORDER_DETAIL; deadline = now + 6_000_000_000L }
            else { resetMenuPageAck(); resetMenuSlotChangeAck() }
            return
        }
        val newOrder = menu.slots.firstOrNull { it.searchableText.contains("new order") }
        if (newOrder == null) { retryLater(now); return }
        createSearchSubmitted = false
        chosenEnchants.clear()
        if (click(frame, newOrder.index)) { phase = Phase.CREATE_ORDER; deadline = now + 15_000_000_000L }
    }

    private fun inspectOwnOrder(frame: LiveAutomationFrame, plan: ProductPlan, now: Long) {
        val menu = frame.snapshot.menu ?: run { retryLater(now); return }
        if (orderDetailMenuId < 0) {
            val openedNewMenu = menu.menuId != orderDetailSourceMenuId && openedMenuId() == menu.menuId
            val acknowledgedSameMenuPage = menu.menuId == orderDetailSourceMenuId &&
                menu.screenIdentity == orderDetailSourceScreenIdentity && orderDetailOpenPageToken.isNotBlank() &&
                menuPageAckStatus(frame, orderDetailOpenPageToken) == InventoryAckStatus.ACKNOWLEDGED
            if (!openedNewMenu && !acknowledgedSameMenuPage) {
                if (now > deadline) {
                    resetMenuPageAck()
                    phase = Phase.STOPPED
                    deadline = 0L
                }
                return
            }
            resetMenuPageAck()
            if (MenuClassifier.classify(menu) != MenuKind.ORDER) { phase = Phase.STOPPED; return }
            orderDetailMenuId = menu.menuId
            orderDetailScreenIdentity = menu.screenIdentity
            orderDetailFingerprint = ""
            lastMenuFingerprint = ""
        } else if (menu.menuId != orderDetailMenuId || menu.screenIdentity != orderDetailScreenIdentity) {
            phase = Phase.STOPPED
            return
        }
        if (MenuClassifier.classify(menu) != MenuKind.ORDER) { phase = Phase.STOPPED; return }
        val fingerprint = menuFingerprint(menu)
        if (fingerprint != orderDetailFingerprint) {
            orderDetailFingerprint = fingerprint
            orderDetailReadyAt = now + 1_000_000_000L
            lastMenuFingerprint = fingerprint
            return
        }
        if (now < orderDetailReadyAt) return
        if (inventoryCapReached(frame.player, plan)) {
            finishOrderCollectionAttempt(frame, now, retryable = true)
            return
        }
        val containerSlotCount = frame.menu.slots.count { it.container !== frame.player.inventory }
        val control = DonutOrderCollectionRules.collectionControl(menu, containerSlotCount)
        when (control.state) {
            DonutOrderDetailState.STILL_FILLING, DonutOrderDetailState.NO_CONTROL -> {
                finishOrderCollectionAttempt(frame, now, retryable = true)
                return
            }
            DonutOrderDetailState.COLLECT -> Unit
        }
        val collectSlot = control.slotIndex ?: run {
            finishOrderCollectionAttempt(frame, now, retryable = true)
            return
        }
        val token = "order-collect:${menu.menuId}:${menu.screenIdentity}:$now"
        if (!beginMenuPageAck(frame, containerSlotCount, token)) {
            finishOrderCollectionAttempt(frame, now, retryable = true)
            return
        }
        if (!click(frame, collectSlot)) {
            resetMenuPageAck()
            finishOrderCollectionAttempt(frame, now, retryable = true)
            return
        }
        orderCollectPageToken = token
        orderCollectContainerSlots = containerSlotCount
        orderCollectReadyAt = 0L
        orderCollectActions = 0
        phase = Phase.WAIT_ORDER_COLLECT_MENU
        deadline = now + 8_000_000_000L
    }

    private fun waitForOrderCollectMenu(frame: LiveAutomationFrame, now: Long) {
        val menu = frame.snapshot.menu ?: run {
            resetMenuPageAck()
            retryLater(now)
            return
        }
        if (menu.menuId != orderDetailMenuId || menu.screenIdentity != orderDetailScreenIdentity ||
            MenuClassifier.classify(menu) != MenuKind.ORDER) {
            resetMenuPageAck()
            phase = Phase.STOPPED
            deadline = 0L
            return
        }
        when (menuPageAckStatus(frame, orderCollectPageToken)) {
            InventoryAckStatus.ACKNOWLEDGED -> {
                resetMenuPageAck()
                lastMenuFingerprint = menuFingerprint(menu)
                orderCollectReadyAt = now + 400_000_000L
                phase = Phase.COLLECT_ORDER
                deadline = now + OWNED_COLLECT_TIMEOUT_NANOS
            }
            InventoryAckStatus.REJECTED, InventoryAckStatus.ABANDONED, InventoryAckStatus.IDLE ->
                finishOrderCollectionAttempt(frame, now, retryable = false)
            else -> if (now > deadline) finishOrderCollectionAttempt(frame, now, retryable = false)
        }
    }

    private fun collectOwnedOrder(frame: LiveAutomationFrame, plan: ProductPlan, now: Long) {
        val menu = frame.snapshot.menu ?: run { phase = Phase.STOPPED; deadline = 0L; return }
        if (menu.menuId != orderDetailMenuId || menu.screenIdentity != orderDetailScreenIdentity ||
            MenuClassifier.classify(menu) != MenuKind.ORDER || !menu.cursorEmpty) {
            phase = Phase.STOPPED
            deadline = 0L
            return
        }
        if (now < orderCollectReadyAt) return
        if (inventoryCapReached(frame.player, plan)) {
            finishOrderCollectionAttempt(frame, now, retryable = true)
            return
        }
        val playerSlot = frame.menu.slots.asSequence()
            .filter { it.container !== frame.player.inventory }
            .filter { !it.item.isEmpty }
            .mapNotNull { native -> menu.slot(native.index)?.let { native to it } }
            .firstOrNull { (_, snapshot) -> DonutTradeOrderStockRules.isCollectedOrderItem(plan.item, snapshot) }
        if (playerSlot != null) {
            if (orderCollectActions >= MAX_ORDER_COLLECT_ACTIONS) {
                finishOrderCollectionAttempt(frame, now, retryable = true)
                return
            }
            val before = toInventorySlotValue(playerSlot.second)
            if (!beginMenuSlotChangeAck(frame, playerSlot.first.index) ||
                !beginOwnedInventoryIncreaseAck(frame, playerSlot.second, expectedIncrease = 1)) {
                resetMenuSlotChangeAck()
                ownedInventoryIncreaseAck.reset()
                finishOrderCollectionAttempt(frame, now, retryable = false)
                return
            }
            orderCollectPendingSlot = playerSlot.first.index
            orderCollectSourceBefore = before
            if (click(frame, playerSlot.first.index, quickMove = true)) {
                phase = Phase.WAIT_ORDER_PRODUCT_ACK
                deadline = now + 8_000_000_000L
            } else {
                resetMenuSlotChangeAck()
                ownedInventoryIncreaseAck.reset()
                orderCollectPendingSlot = -1
                orderCollectSourceBefore = null
                finishOrderCollectionAttempt(frame, now, retryable = true)
            }
            return
        }
        finishOrderCollectionAttempt(frame, now, retryable = true)
    }

    private fun waitForOrderProductAck(frame: LiveAutomationFrame, now: Long) {
        val menu = frame.snapshot.menu ?: run { phase = Phase.STOPPED; deadline = 0L; return }
        if (menu.menuId != orderDetailMenuId || menu.screenIdentity != orderDetailScreenIdentity ||
            MenuClassifier.classify(menu) != MenuKind.ORDER || !menu.cursorEmpty) {
            phase = Phase.STOPPED
            deadline = 0L
            return
        }
        val menuStatus = menuSlotChangeAckStatus(frame)
        val inventoryStatus = ownedInventoryIncreaseAck.status(frame.snapshot.playerInventoryMenu)
        when {
            menuStatus == InventoryAckStatus.ACKNOWLEDGED && inventoryStatus == InventoryAckStatus.ACKNOWLEDGED -> {
                val before = orderCollectSourceBefore
                val after = menu.slot(orderCollectPendingSlot)?.let(::toInventorySlotValue)
                val moved = movedFromOrderSlot(before, after)
                val received = ownedInventoryIncreaseAck.confirmedIncrease(frame.snapshot.playerInventoryMenu)
                if (moved == null || received == null || moved != received || moved <= 0) {
                    finishOrderCollectionAttempt(frame, now, retryable = false)
                    return
                }
                orderCollectActions++
                resetMenuSlotChangeAck()
                ownedInventoryIncreaseAck.reset()
                orderCollectPendingSlot = -1
                orderCollectSourceBefore = null
                lastMenuFingerprint = menuFingerprint(menu)
                orderCollectReadyAt = now + 40_000_000L
                phase = Phase.COLLECT_ORDER
                deadline = now + OWNED_COLLECT_TIMEOUT_NANOS
            }
            menuStatus in setOf(InventoryAckStatus.REJECTED, InventoryAckStatus.ABANDONED, InventoryAckStatus.IDLE) ||
                inventoryStatus in setOf(InventoryAckStatus.REJECTED, InventoryAckStatus.ABANDONED, InventoryAckStatus.IDLE) ||
                now > deadline -> finishOrderCollectionAttempt(frame, now, retryable = false)
        }
    }

    private fun waitForOrderCollectionClose(frame: LiveAutomationFrame, now: Long) {
        if (frame.snapshot.menu == null) {
            val retry = retryOrderAfterMenuClose
            retryOrderAfterMenuClose = false
            orderDetailFingerprint = ""
            orderDetailReadyAt = 0L
            orderDetailMenuId = -1
            orderDetailScreenIdentity = -1L
            orderDetailSourceMenuId = -1
            orderDetailSourceScreenIdentity = -1L
            orderDetailOpenPageToken = ""
            orderCollectPageToken = ""
            orderCollectContainerSlots = 0
            if (retry) retryLater(now) else { phase = Phase.STOPPED; deadline = 0L }
        } else if (frame.snapshot.menu.menuId != orderDetailMenuId ||
            frame.snapshot.menu.screenIdentity != orderDetailScreenIdentity || now > deadline) {
            phase = Phase.STOPPED
            deadline = 0L
        }
    }

    private fun finishOrderCollectionAttempt(frame: LiveAutomationFrame, now: Long, retryable: Boolean) {
        resetMenuPageAck()
        resetMenuSlotChangeAck()
        ownedInventoryIncreaseAck.reset()
        orderCollectPendingSlot = -1
        orderCollectSourceBefore = null
        val menu = frame.snapshot.menu
        if (menu == null) {
            if (retryable) retryLater(now) else { phase = Phase.STOPPED; deadline = 0L }
            return
        }
        if (menu.menuId != orderDetailMenuId || menu.screenIdentity != orderDetailScreenIdentity || !menu.cursorEmpty) {
            phase = Phase.STOPPED
            deadline = 0L
            return
        }
        if (!closeMenu(frame)) {
            phase = Phase.STOPPED
            deadline = 0L
            return
        }
        retryOrderAfterMenuClose = retryable
        phase = Phase.WAIT_ORDER_COLLECTION_CLOSE
        deadline = now + 2_000_000_000L
    }

    private fun movedFromOrderSlot(before: InventorySlotValue?, after: InventorySlotValue?): Int? {
        if (before == null || before.count <= 0 || before.itemId.isBlank() || after == null) return null
        if (after.count == 0 && after.itemId.isBlank()) return before.count
        if (after.count < 0 || after.count >= before.count || after.itemId != before.itemId ||
            before.copy(count = after.count) != after) return null
        return before.count - after.count
    }

    private fun createOrderDialog(frame: LiveAutomationFrame, plan: ProductPlan, now: Long) {
        val dialog = frame.snapshot.dialog ?: return
        val title = normalized(dialog.title)
        when {
            "item" in title -> chooseOrderItem(frame, dialog, plan)
            "enchant" in title -> chooseOrderEnchants(frame, dialog, plan)
            "amount" in title || "howmany" in title -> fillOrderAmount(frame, dialog, plan)
            "price" in title -> fillOrderPrice(frame, dialog)
            "review" in title -> confirmOrderReview(frame, dialog, plan, now)
            else -> phase = Phase.STOPPED
        }
    }

    private fun chooseOrderItem(frame: LiveAutomationFrame, dialog: DialogSnapshot, plan: ProductPlan) {
        val input = dialog.widgets.firstOrNull { it.kind == "input" }
        if (input != null && input.value != plan.query) {
            setDialogText(frame, input.index, plan.query)
            return
        }
        val search = dialogButton(dialog, "search")
        if (input != null && !createSearchSubmitted && search != null) {
            if (pressDialog(frame, search.index)) createSearchSubmitted = true
            return
        }
        val matches = dialog.widgets.filter { it.kind == "button" && itemLabelMatches(it.label, plan.item) }
        if (matches.size != 1) return
        if (pressDialog(frame, matches.single().index)) {
            createSearchSubmitted = false
            chosenEnchants.clear()
        }
    }

    private fun chooseOrderEnchants(frame: LiveAutomationFrame, dialog: DialogSnapshot, plan: ProductPlan) {
        if (plan.enchants.isEmpty()) {
            val skip = dialogButton(dialog, "skip") ?: dialogButton(dialog, "continue", "confirm", "done", "next")
            if (skip != null) pressDialog(frame, skip.index) else phase = Phase.STOPPED
            return
        }
        for ((name, level) in plan.enchants) {
            if (name in chosenEnchants) continue
            val option = dialog.widgets.firstOrNull { it.kind == "button" && enchantLabelMatches(it.label, name, level) }
            if (option == null) { phase = Phase.STOPPED; return }
            if (pressDialog(frame, option.index)) chosenEnchants += name
            return
        }
        val next = dialogButton(dialog, "continue", "confirm", "done", "next")
        if (next != null) pressDialog(frame, next.index) else phase = Phase.STOPPED
    }

    private fun fillOrderAmount(frame: LiveAutomationFrame, dialog: DialogSnapshot, plan: ProductPlan) {
        val input = dialog.widgets.firstOrNull { it.kind == "input" } ?: run { phase = Phase.STOPPED; return }
        val amount = plan.amount.toString()
        if (input.value.trim() != amount) { setDialogText(frame, input.index, amount); return }
        val next = dialogButton(dialog, "next", "continue") ?: run { phase = Phase.STOPPED; return }
        pressDialog(frame, next.index)
    }

    private fun fillOrderPrice(frame: LiveAutomationFrame, dialog: DialogSnapshot) {
        val input = dialog.widgets.firstOrNull { it.kind == "input" } ?: run { phase = Phase.STOPPED; return }
        val amount = orderPrice.stripTrailingZeros().toPlainString()
        if (input.value.trim() != amount) { setDialogText(frame, input.index, amount); return }
        val next = dialogButton(dialog, "review order", "review", "next") ?: run { phase = Phase.STOPPED; return }
        pressDialog(frame, next.index)
    }

    private fun confirmOrderReview(frame: LiveAutomationFrame, dialog: DialogSnapshot, plan: ProductPlan, now: Long) {
        if (!reviewMatches(dialog, plan)) { phase = Phase.STOPPED; return }
        val create = dialogButton(dialog, "create order", "create", "confirm") ?: run { phase = Phase.STOPPED; return }
        orderMessages.clear()
        orderAttemptStartedAt = System.nanoTime()
        if (pressDialog(frame, create.index)) {
            phase = Phase.WAIT_ORDER_ACK
            deadline = now + 8_000_000_000L
        } else {
            orderAttemptStartedAt = Long.MIN_VALUE
        }
    }

    private fun reviewMatches(dialog: DialogSnapshot, plan: ProductPlan): Boolean =
        OrderReviewRules.matches(dialog, plan.item, plan.enchants, plan.amount, orderPrice)

    private fun waitForOrderAcknowledgement(now: Long) {
        while (true) {
            val reply = orderMessages.poll() ?: break
            if (OrderAcknowledgementRules.isSuccess(reply.text, reply.atNanos, orderAttemptStartedAt)) {
                phase = Phase.IDLE
                deadline = 0L
                orderAttemptStartedAt = Long.MIN_VALUE
                nextOrderCheckAt = now + orderCheck * 1_000_000_000L
                nextActionAt = now + (listingDelay * 1_000_000_000.0).toLong()
                return
            }
            if (reply.atNanos - orderAttemptStartedAt >= 0L && OrderAcknowledgementRules.isFailure(reply.text)) {
                retryLater(now)
                return
            }
        }
    }

    private fun listProduct(frame: LiveAutomationFrame, plan: ProductPlan, now: Long) {
        if (ownedListingCount >= maxListings || frame.snapshot.menu != null) { phase = Phase.IDLE; return }
        when (val stock = stockPlan(frame, plan.item, plan.enchants)) {
            is InventoryStagePlan.Ready -> selectedHotbar = stock.hotbarSlot
            is InventoryStagePlan.Swap -> { issueStockSwap(frame, stock, plan.item); return }
            is InventoryStagePlan.Transfer -> { issueStockTransfer(frame, stock, plan.item); return }
            else -> { phase = Phase.IDLE; return }
        }
        val slot = selectedHotbar
        if (frame.player.inventory.selectedSlot != slot) {
            if (originalSlot < 0 || originalPlayer !== frame.player) {
                originalPlayer = frame.player
                originalSlot = frame.player.inventory.selectedSlot
            }
            ownedSlot = slot
            selectHotbar(frame, slot)
            return
        }
        selectedHotbar = slot
        beforeListing = stackFingerprint(frame.player, slot)
        if (activeSellPrice.signum() <= 0) return
        val total = activeSellPrice.multiply(BigDecimal(perListing))
        pendingListingTotal = total
        val startedAt = System.nanoTime()
        if (command(frame, "ah sell ${total.stripTrailingZeros().toPlainString()}")) {
            listingStartedAt = startedAt
            phase = Phase.WAIT_CONFIRM
            deadline = now + 8_000_000_000L
        }
    }

    private fun confirmListing(frame: LiveAutomationFrame, plan: ProductPlan, now: Long) {
        val outcome = consumeListingOutcome()
        if (outcome != null) {
            settleListingOutcome(frame, now, outcome)
            return
        }
        val menu = frame.snapshot.menu
        if (menu == null) return
        if (MenuClassifier.classify(menu) != MenuKind.CONFIRMATION || !listingConfirmationMatches(menu, plan, pendingListingTotal)) {
            phase = Phase.STOPPED
            return
        }
        val confirm = menu.slots.firstOrNull { slot ->
            val text = slot.searchableText
            slot.itemId.isNotBlank() && (text == "confirm" || text == "yes" || text.startsWith("confirm "))
        } ?: run { phase = Phase.STOPPED; return }
        if (click(frame, confirm.index)) { phase = Phase.WAIT_RESULT; deadline = now + salesCheck * 1_000_000_000L }
    }

    private fun verifyListing(frame: LiveAutomationFrame, now: Long) {
        val outcome = consumeListingOutcome() ?: return
        settleListingOutcome(frame, now, outcome)
    }

    private fun consumeListingOutcome(): TradeListingReplyKind? {
        while (true) {
            val reply = listingReplies.poll() ?: break
            if (reply.atNanos < listingStartedAt) continue
            val candidate = TradeListingReplyRules.classify(reply.text)
            if (candidate != TradeListingReplyKind.UNRELATED) return candidate
        }
        return null
    }

    private fun settleListingOutcome(frame: LiveAutomationFrame, now: Long, outcome: TradeListingReplyKind) {
        when (outcome) {
            TradeListingReplyKind.LISTED -> {
                listingLedger = ListingLedger.listed(listingLedger)
                ownedListingCount = listingLedger.open
                listingCount = listingLedger.listedLifetime
            }
            TradeListingReplyKind.FULL -> {
                listingLedger = ListingLedger.reconcileOpenCount(listingLedger, maxListings)
                ownedListingCount = listingLedger.open
            }
            TradeListingReplyKind.REJECTED -> Unit
            TradeListingReplyKind.UNRELATED -> return
        }
        if (frame.snapshot.menu != null) {
            closeMenu(frame)
            restoreListingSelectionPending = true
        } else {
            restoreSelection(frame)
        }
        val changed = outcome == TradeListingReplyKind.LISTED
        phase = Phase.IDLE
        deadline = 0L
        val delay = if (changed) (listingDelay * 1_000_000_000.0).toLong() else salesCheck * 1_000_000_000L
        nextActionAt = now + delay
        if (changed) nextPriceCheckAt = now + salesCheck * 1_000_000_000L
    }

    private fun onPhaseTimeout(now: Long, frame: LiveAutomationFrame) {
        when (phase) {
            Phase.CHECK_AUCTION -> retryAuctionScan(now, frame)
            Phase.TOP_ORDER -> if (frame.snapshot.menu == null || closeMenu(frame)) finishOrderPriceAttempt(now)
            Phase.OPEN_ORDERS, Phase.CHECK_ORDER, Phase.WAIT_MY_ORDERS,
            Phase.WAIT_ORDER_ACK -> retryLater(now)
            Phase.WAIT_ORDER_DETAIL, Phase.COLLECT_ORDER -> finishOrderCollectionAttempt(frame, now, retryable = true)
            Phase.WAIT_ORDER_COLLECT_MENU, Phase.WAIT_ORDER_PRODUCT_ACK ->
                finishOrderCollectionAttempt(frame, now, retryable = false)
            Phase.WAIT_ORDER_COLLECTION_CLOSE -> { phase = Phase.STOPPED; deadline = 0L }
            Phase.CREATE_ORDER -> { phase = Phase.STOPPED; deadline = 0L }
            Phase.WAIT_CONFIRM -> {
                if (frame.snapshot.menu != null) {
                    closeMenu(frame)
                    restoreListingSelectionPending = true
                } else restoreSelection(frame)
                ownershipSynced = false
                ownershipRetryAt = now + salesCheck * 1_000_000_000L
                phase = Phase.IDLE
                deadline = 0L
                nextActionAt = ownershipRetryAt
            }
            Phase.WAIT_RESULT -> {
                if (frame.snapshot.menu != null) {
                    closeMenu(frame)
                    restoreListingSelectionPending = true
                } else restoreSelection(frame)
                phase = Phase.IDLE
                deadline = 0L
                ownershipSynced = false
                ownershipRetryAt = now + salesCheck * 1_000_000_000L
                nextActionAt = ownershipRetryAt
            }
            Phase.OPEN_OWN_AUCTION, Phase.WAIT_OWN_AUCTION, Phase.WAIT_OWN_LISTINGS,
            Phase.WAIT_OWN_LISTINGS_PAGE, Phase.WAIT_OWN_COLLECT -> failOwnedListingScan(now, frame)
            else -> { phase = Phase.IDLE; deadline = 0L }
        }
    }

    private fun retryAuctionScan(now: Long, frame: LiveAutomationFrame) {
        if (frame.snapshot.menu != null) closeMenu(frame)
        phase = Phase.IDLE
        deadline = 0L
        if (auctionScanAttempts < 3) {
            auctionScanAttempts++
            nextActionAt = now + 800_000_000L
        } else {
            auctionScanAttempts = 0
            activeSellPrice = BigDecimal.ZERO
            nextActionAt = now + minOf(5_000_000_000L, salesCheck * 1_000_000_000L)
        }
    }

    private fun finishOrderPriceAttempt(now: Long) {
        phase = Phase.IDLE
        deadline = 0L
        if (orderScanAttempts < 3) {
            val delay = if (orderScanAttempts == 1) 800_000_000L else 1_400_000_000L
            nextOrderCheckAt = now + delay
            nextActionAt = nextOrderCheckAt
        } else {
            orderScanAttempts = 0
            nextOrderCheckAt = now + orderCheck * 1_000_000_000L
            nextActionAt = nextOrderCheckAt
        }
    }

    private fun retryLater(now: Long) {
        phase = Phase.IDLE
        deadline = 0L
        orderAttemptStartedAt = Long.MIN_VALUE
        nextActionAt = now + orderCheck * 1_000_000_000L
        nextOrderCheckAt = nextActionAt
        orderMessages.clear()
    }

    private fun isOwnOrdersMenu(menu: dev.donut.addon.expansion.automation.core.MenuSnapshot): Boolean =
        menu.normalizedTitle.contains("your orders") || menu.slots.any { it.searchableText.contains("new order") }

    private fun dialogButton(dialog: DialogSnapshot, vararg words: String) = dialog.widgets.firstOrNull { widget ->
        widget.kind == "button" && words.any { normalized(widget.label).contains(normalized(it)) } &&
            !normalized(widget.label).contains("cancel")
    }

    private fun itemLabelMatches(label: String, configured: String): Boolean {
        val id = MarketRules.safeItemId(configured)?.substringAfter(':') ?: configured.trim()
        val expected = wordSequence(id)
        if (expected.isEmpty()) return false
        val candidate = wordSequence(label).dropWhile { it.matches(Regex("\\d+x?")) }
        return candidate.windowed(expected.size).any { it == expected }
    }

    private fun matchesProductItem(configured: String, slot: SlotSnapshot): Boolean {
        val wantedId = MarketRules.safeItemId(configured)
        if (wantedId != null) return MarketRules.safeItemId(slot.itemId) == wantedId
        val wanted = wordSequence(configured)
        val actual = wordSequence(slot.name)
        return wanted.isNotEmpty() && actual.windowed(wanted.size).any { it == wanted }
    }

    private fun listingConfirmationMatches(
        menu: dev.donut.addon.expansion.automation.core.MenuSnapshot,
        plan: ProductPlan,
        total: BigDecimal,
    ): Boolean {
        val title = menu.normalizedTitle
        if (listOf("buy", "purchase", "order", "page").any(title::contains)) return false
        val hasItem = menu.slots.any { itemLabelMatches(it.searchableText, plan.item) || matchesProductItem(plan.item, it) }
        val visibleNumbers = numericValues((listOf(menu.title) + menu.slots.flatMap { listOf(it.name) + it.lore }).joinToString(" "))
        return hasItem && visibleNumbers.any { it.compareTo(total) == 0 }
    }

    private fun wordSequence(value: String): List<String> = TextNormalization.stripFormatting(value)
        .lowercase().replace(Regex("(?i)^minecraft:"), "")
        .replace(Regex("[^a-z0-9]+"), " ").trim().split(Regex("\\s+")).filter(String::isNotEmpty)

    private fun enchantLabelMatches(label: String, enchant: String, level: Int): Boolean {
        val candidate = normalized(label)
        val name = normalized(enchant)
        val roman = roman(level)?.lowercase() ?: level.toString()
        return candidate.contains(name) && (candidate.endsWith(roman) || candidate.contains("${name}${roman}"))
    }

    private fun numericValues(text: String): List<BigDecimal> = Regex("(?i)\\$?[0-9][0-9,]*(?:\\.[0-9]+)?\\s*[km]?")
        .findAll(text).mapNotNull { MarketRules.parseAmount(it.value) }.toList()

    private fun normalized(text: String): String = TextNormalization.stripFormatting(text).lowercase()
        .replace(Regex("[^a-z0-9]"), "")

    private fun roman(value: Int): String? = when (value) {
        1 -> "I"; 2 -> "II"; 3 -> "III"; 4 -> "IV"; 5 -> "V"; 6 -> "VI"; 7 -> "VII"; 8 -> "VIII"; 9 -> "IX"; 10 -> "X"
        else -> null
    }

    private fun stockPlan(
        frame: LiveAutomationFrame,
        configured: String,
        requiredEnchantments: Map<String, Int> = emptyMap(),
    ): InventoryStagePlan =
        frame.snapshot.playerInventoryMenu?.let { menu ->
            InventoryStaging.plan(menu, configured, perListing, requiredEnchantments).let { plan ->
                if (stagingAbandonedItem == configured.trim().lowercase(Locale.ROOT)) InventoryStagePlan.Unsafe else plan
            }
        } ?: InventoryStagePlan.Unsafe

    private fun issueStockSwap(frame: LiveAutomationFrame, plan: InventoryStagePlan.Swap, configured: String) {
        val menu = frame.snapshot.playerInventoryMenu ?: return
        stagingItemKey = configured.trim().lowercase(Locale.ROOT)
        if (!beginInventorySwap(frame, plan)) {
            failStockStage()
            return
        }
        stagingPlan = plan
        stagingMenuId = menu.menuId
        stagingIssuedAt = frame.snapshot.tick
        stagingStableAt = 0L
        phase = Phase.WAIT_STAGE
        deadline = System.nanoTime() + 5_000_000_000L
    }

    private fun issueStockTransfer(frame: LiveAutomationFrame, plan: InventoryStagePlan.Transfer, configured: String) {
        val menu = frame.snapshot.playerInventoryMenu ?: return
        stagingItemKey = configured.trim().lowercase(Locale.ROOT)
        if (!stagingTransfer.begin(plan)) { failStockStage(); return }
        stagingTransferPlan = plan
        stagingMenuId = menu.menuId
        stagingIssuedAt = frame.snapshot.tick
        stagingStableAt = 0L
        phase = Phase.WAIT_STAGE
        deadline = System.nanoTime() + 10_000_000_000L
    }

    private fun verifyStockSwap(frame: LiveAutomationFrame, now: Long) {
        val menu = frame.snapshot.playerInventoryMenu
        val plan = stagingPlan
        if (menu == null || plan == null || menu.menuId != stagingMenuId || !menu.cursorEmpty) {
            failStockStage(frame); return
        }
        when (inventorySwapStatus(frame)) {
            InventoryAckStatus.ACKNOWLEDGED -> if (InventoryStaging.swapObserved(menu, plan)) {
            if (stagingStableAt == 0L) stagingStableAt = frame.snapshot.tick
            else if (frame.snapshot.tick > stagingStableAt) {
                selectedHotbar = plan.hotbarSlot
                stagingPlan = null; stagingMenuId = -1; stagingStableAt = 0L
                phase = Phase.LIST; deadline = 0L; nextActionAt = now
            }
            } else failStockStage(frame)
            InventoryAckStatus.REJECTED, InventoryAckStatus.ABANDONED, InventoryAckStatus.IDLE -> failStockStage(frame)
            InventoryAckStatus.PENDING -> if (frame.snapshot.tick - stagingIssuedAt > 100L || deadline > 0L && now > deadline) failStockStage(frame)
        }
    }

    private fun failStockStage(frame: LiveAutomationFrame? = null) {
        if (frame != null && stagingTransferPlan != null && startStockRecovery(frame)) {
            stagingTransfer.abandon()
            abandonInventorySwap()
            stagingPlan = null
            stagingTransferPlan = null
            phase = Phase.RECOVER_STAGE
            stagingIssuedAt = frame.snapshot.tick
            return
        }
        finishStockStageFailure()
    }

    private fun startStockRecovery(frame: LiveAutomationFrame): Boolean {
        val menu = frame.snapshot.playerInventoryMenu ?: return false
        val transfer = stagingTransferPlan ?: return false
        if (menu.menuId != stagingMenuId) return false
        val local = listOf(transfer.sourceMenuSlot, transfer.targetMenuSlot).distinct().mapNotNull { index ->
            menu.slot(index)?.let { index to InventorySlotValue(
                it.itemId, it.count, it.name, it.lore, it.componentFingerprint, it.actualEnchantments, it.procurementFingerprint,
            ) }
        }.toMap()
        val cursor = menu.cursorStack ?: InventorySlotValue("", 0)
        val action = stagingTransfer.safeCursorReturnAction(local, cursor) ?: return false
        stagingRecoveryAction = action
        stagingRecoveryIssued = false
        if (frame.screen == null) {
            if (!beginInventoryPickup(frame, action)) {
                stagingRecoveryAction = null
                return false
            }
            stagingTransfer.recoveryIssued()
            stagingRecoveryIssued = true
        }
        return true
    }

    private fun verifyStockRecovery(frame: LiveAutomationFrame, now: Long) {
        val action = stagingRecoveryAction
        val menu = frame.snapshot.playerInventoryMenu
        if (action == null || menu == null || menu.menuId != stagingMenuId) {
            finishStockStageFailure()
            return
        }
        if (!stagingRecoveryIssued) {
            if (frame.snapshot.tick - stagingIssuedAt > 100L) {
                finishStockStageFailure()
            } else if (frame.screen == null) {
                if (beginInventoryPickup(frame, action)) {
                    stagingTransfer.recoveryIssued()
                    stagingRecoveryIssued = true
                } else finishStockStageFailure()
            }
            return
        }
        when (inventoryPickupStatus(frame)) {
            InventoryAckStatus.ACKNOWLEDGED,
            InventoryAckStatus.REJECTED,
            InventoryAckStatus.ABANDONED,
            InventoryAckStatus.IDLE -> finishStockStageFailure()
            InventoryAckStatus.PENDING -> if (frame.snapshot.tick - stagingIssuedAt > 100L || deadline > 0L && now > deadline) {
                abandonInventoryPickup()
                finishStockStageFailure()
            }
        }
    }

    private fun finishStockStageFailure() {
        abandonInventorySwap()
        abandonInventoryPickup()
        stagingTransfer.recoveryIssued()
        stagingTransfer.abandon()
        if (stagingItemKey.isNotEmpty()) stagingAbandonedItem = stagingItemKey
        stagingItemKey = ""
        stagingPlan = null; stagingTransferPlan = null; stagingRecoveryAction = null; stagingRecoveryIssued = false
        stagingMenuId = -1; stagingStableAt = 0L
        phase = Phase.IDLE; deadline = 0L
    }

    private fun verifyStockTransfer(frame: LiveAutomationFrame, now: Long) {
        val menu = frame.snapshot.playerInventoryMenu
        if (menu == null || menu.menuId != stagingMenuId) { failStockStage(frame); return }
        when (tickInventoryTransfer(frame, stagingTransfer)) {
            InventoryTransferProgress.READY -> {
                val transfer = stagingTransferPlan ?: run { failStockStage(frame); return }
                selectedHotbar = transfer.targetMenuSlot - 36
                stagingTransferPlan = null; stagingMenuId = -1; stagingStableAt = 0L
                phase = Phase.LIST; deadline = 0L; nextActionAt = now
            }
            InventoryTransferProgress.FAILED, InventoryTransferProgress.IDLE -> failStockStage(frame)
            InventoryTransferProgress.WAITING -> if (frame.snapshot.tick - stagingIssuedAt > 200L ||
                deadline > 0L && now > deadline) failStockStage(frame)
        }
    }

    private fun restoreSelection(frame: LiveAutomationFrame) {
        if (originalPlayer === frame.player && originalSlot in 0..8 && ownedSlot in 0..8 &&
            frame.player.inventory.selectedSlot == ownedSlot) selectHotbar(frame, originalSlot)
        originalPlayer = null; originalSlot = -1; ownedSlot = -1
    }

    private fun restoreOwnedSelection() {
        val player = originalPlayer ?: return
        if (player === mc.player && originalSlot in 0..8 && ownedSlot in 0..8 && player.inventory.selectedSlot == ownedSlot) {
            player.inventory.selectedSlot = originalSlot
        }
    }

    private fun stackFingerprint(player: LocalPlayer, slot: Int): String {
        if (slot !in 0..8) return ""
        val stack = player.inventory.getItem(slot)
        return "${net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item)}:${stack.count}:${stack.hoverName.string}"
    }

    private fun inventoryCapReached(player: LocalPlayer, plan: ProductPlan): Boolean {
        var matchingStacks = 0
        var emptySlots = 0
        for (slot in 0 until 36) {
            val stack = player.inventory.getItem(slot)
            if (stack.isEmpty) { emptySlots++; continue }
            val id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item).toString()
            if (matchesProductItem(plan.item, SlotSnapshot(slot, id, stack.count, stack.hoverName.string, emptyList()))) matchingStacks++
        }
        return matchingStacks >= collectSlots || emptySlots == 0
    }

    private fun menuFingerprint(menu: dev.donut.addon.expansion.automation.core.MenuSnapshot): String =
        menu.slots.joinToString("|") { it.fingerprint }

    private fun toInventorySlotValue(slot: SlotSnapshot): InventorySlotValue = InventorySlotValue(
        slot.itemId, slot.count, slot.name, slot.lore, slot.componentFingerprint,
        slot.actualEnchantments, slot.procurementFingerprint,
    )

}
