package dev.donut.addon.expansion.automation.core

import java.math.BigDecimal
import java.util.Locale

data class AuctionPurchaseRequest @JvmOverloads constructor(
    val itemId: String,
    val amount: Int,
    val maxListingTotal: BigDecimal,
    val requiredEnchantments: Map<String, Int> = emptyMap(),
    val exactEnchantments: Boolean = false,
    /** Override to support source-approved transparent-block variants; ordinary purchases reject augmented blocks. */
    val listingPredicate: (SlotSnapshot) -> Boolean = AuctionPurchaseListingRules::ordinary,
    val cumulativeBudget: BigDecimal? = null,
    val maxRefreshes: Int = 5,
    val maxPages: Int = 5,
    val settleTicks: Long = 2L,
    val refreshDelayTicks: Long = 10L,
    val ackTimeoutTicks: Long = 60L,
    /** Concrete listing IDs allowed by a source-approved family predicate; defaults to the query item. */
    val allowedItemIds: Set<String> = setOf(itemId),
)

object AuctionPurchaseListingRules {
    private val unsafeBlockComponents = Regex("(?i)\\b(block_state|block_entity_data)\\b")
    private val listingPriceMetadata = Regex("(?i)\\b(price|cost|buy now)\\b|\\$\\s*[0-9]")
    private val listingOwnerMetadata = Regex("(?i)\\b(seller|listed by|owner)\\b\\s*[: ]")
    private val listingTimerMetadata = Regex("(?i)\\b(ends? in|time left|expires? in)\\s*[: ]*\\d+(?:\\.\\d+)?\\s*(d|days?|h|hours?|hrs?|m|minutes?|mins?)\\b")

    /** Prevents accidental purchase of placed-block data or block-entity contents. */
    @JvmStatic
    fun ordinary(slot: SlotSnapshot): Boolean = !unsafeBlockComponents.containsMatchIn(slot.componentFingerprint)

    /** Listing lore is removed only from purchase matching; full inventory fingerprints remain untouched. */
    @JvmStatic
    fun procurementLore(lore: List<String>): List<String> = lore.filterNot { line ->
        val clean = normalize(line)
        listingPriceMetadata.containsMatchIn(clean) || listingOwnerMetadata.containsMatchIn(clean) ||
            listingTimerMetadata.containsMatchIn(clean)
    }
}

data class AuctionPurchaseFrame @JvmOverloads constructor(
    val snapshot: AutomationSnapshot,
    val playerName: String,
    val acknowledgedMenuId: Int?,
    /** Active menu slot index -> player inventory menu slot index, copied for this menu-map epoch. */
    val activeInventorySlotMap: Map<Int, Int>,
    val menuSlotMapEpoch: Long,
    val menuContentEpoch: Long,
    val containerSlotCount: Int,
    val nextPageAction: AuctionPurchasePageAction? = null,
    val confirmation: AuctionPurchaseConfirmation? = null,
    /** Newly observed transaction outcomes correlated to the active listing attempt. */
    val transactionReceipts: List<AuctionPurchaseReceipt> = emptyList(),
)

data class AuctionPurchasePageAction(
    val menuId: Int,
    val screenIdentity: Long,
    val slotIndex: Int,
    val expectedFingerprint: String,
)

sealed interface AuctionPurchaseConfirmationAction {
    data class MenuSlot(
        val menuId: Int,
        val screenIdentity: Long,
        val slotIndex: Int,
        val expectedFingerprint: String,
    ) : AuctionPurchaseConfirmationAction

    data class DialogWidget(
        val screenIdentity: Long,
        val widgetIndex: Int,
        val expectedFingerprint: String,
        val expectedLabel: String,
    ) : AuctionPurchaseConfirmationAction
}

data class AuctionPurchaseConfirmation @JvmOverloads constructor(
    val itemId: String,
    val count: Int,
    val enchantments: Map<String, Int>,
    val totalPrice: BigDecimal,
    val screenIdentity: Long,
    val menuId: Int?,
    val evidenceFingerprint: String,
    val action: AuctionPurchaseConfirmationAction,
    /** Optional normalized native identity when the confirmation UI exposes its item stack. */
    val procurementFingerprint: String? = null,
)

enum class AuctionPurchaseReceiptKind { PURCHASED, LISTING_GONE, OWN_LISTING, INSUFFICIENT_FUNDS }

/** Parsed transaction text correlated by the host to a ClickListing attempt. */
data class AuctionPurchaseReceipt @JvmOverloads constructor(
    val receiptId: Long,
    val session: SessionStamp,
    val attemptId: Long,
    val kind: AuctionPurchaseReceiptKind,
    /** Item name parsed from the transaction text; hosts must not substitute the clicked item's name. */
    val reportedName: String,
    val itemId: String? = null,
    val count: Int? = null,
    val totalPrice: BigDecimal? = null,
)

sealed interface AuctionPurchaseEffect {
    data class OpenSearch(
        val session: SessionStamp,
        val query: String,
        val previousMenuId: Int,
    ) : AuctionPurchaseEffect

    data class ClickListing(
        val session: SessionStamp,
        val menuId: Int,
        val screenIdentity: Long,
        val slotIndex: Int,
        val expectedFingerprint: String,
        val attemptId: Long = 0L,
    ) : AuctionPurchaseEffect

    data class NextPage(
        val session: SessionStamp,
        val menuId: Int,
        val screenIdentity: Long,
        val slotIndex: Int,
        val expectedFingerprint: String,
    ) : AuctionPurchaseEffect

    data class ClickConfirmation(
        val session: SessionStamp,
        val evidenceFingerprint: String,
        val action: AuctionPurchaseConfirmationAction,
    ) : AuctionPurchaseEffect

    data class CloseMenu(
        val session: SessionStamp,
        val menuId: Int,
        val screenIdentity: Long,
    ) : AuctionPurchaseEffect
}

fun interface AuctionPurchaseHost {
    /** Dispatches one guarded effect through this owner's existing action gate. */
    fun dispatch(frame: AuctionPurchaseFrame, effect: AuctionPurchaseEffect): Boolean
}

enum class AuctionPurchaseStatus {
    IDLE, RUNNING, COMPLETE, PARTIAL, UNAVAILABLE, INSUFFICIENT_FUNDS, BUDGET_EXHAUSTED, UNCERTAIN, FAILED,
}

data class AuctionPurchaseProgress(
    val status: AuctionPurchaseStatus,
    val requestedItemId: String,
    val requestedAmount: Int,
    val acquiredAmount: Int,
    /** Confirmed spend across the current run. */
    val confirmedSpend: BigDecimal,
    /** Confirmed spend plus purchases whose result is uncertain. */
    val reservedSpend: BigDecimal,
    val refreshes: Int,
    val reason: String = "",
)

/**
 * Bounded, composable AH purchasing state machine. It never treats listing removal as a purchase and
 * never repeats a click whose inventory result is uncertain.
 */
class AuctionPurchaseWorkflow {
    private enum class Phase { IDLE, NEED_REFRESH, WAIT_MENU, WAIT_PAGE, SCAN, WAIT_PURCHASE, TERMINAL }
    private data class Candidate(
        val slot: SlotSnapshot,
        val listing: ListingData,
        val totalPrice: BigDecimal,
    )
    private data class PendingPurchase(
        val identity: String,
        val attemptId: Long,
        val itemId: String,
        val name: String,
        val count: Int,
        val enchantments: Map<String, Int>,
        val procurementFingerprint: String,
        var totalPrice: BigDecimal,
        val seenReceipts: MutableMap<Long, AuctionPurchaseReceipt> = mutableMapOf(),
        var confirmationTotalPrice: BigDecimal? = null,
        var confirmDispatched: Boolean = false,
        var confirmationScreenIdentity: Long? = null,
    )

    private var request: AuctionPurchaseRequest? = null
    private var phase = Phase.IDLE
    private var status = AuctionPurchaseStatus.IDLE
    private var reason = ""
    private var session: SessionStamp? = null
    private var expectedPreviousMenuId = -1
    private var activeMenuId = -1
    private var refreshes = 0
    private var pagesTurned = 0
    private var acquiredAmount = 0
    private var nextActionTick = 0L
    private var deadlineTick = 0L
    private var pending: PendingPurchase? = null
    private var nextPurchaseAttemptId = 1L
    private var pageContentEpoch = 0L
    private var scanFingerprint = ""
    private var scanStartedAtTick = 0L
    private var confirmedSpend = BigDecimal.ZERO
    private var uncertainSpend = BigDecimal.ZERO
    private val uncertainByIdentity = mutableMapOf<String, BigDecimal>()
    private val uncertainRequests = mutableSetOf<String>()
    private val inventoryAck = InventoryIncreaseAckTracker()

    /** Starts or replaces a terminal request without resetting the run-level spend ledger. */
    fun start(next: AuctionPurchaseRequest): Boolean {
        if (phase != Phase.IDLE && phase != Phase.TERMINAL) return false
        request = next
        phase = Phase.IDLE
        status = AuctionPurchaseStatus.IDLE
        reason = ""
        session = null
        expectedPreviousMenuId = -1
        activeMenuId = -1
        refreshes = 0
        pagesTurned = 0
        acquiredAmount = 0
        nextActionTick = 0L
        deadlineTick = 0L
        pending = null
        pageContentEpoch = 0L
        scanFingerprint = ""
        scanStartedAtTick = 0L
        inventoryAck.reset()
        val safeItem = MarketRules.safeItemId(next.itemId)
        val safeAllowedItems = next.allowedItemIds.mapNotNull(MarketRules::safeItemId).toSortedSet()
        val invalid = when {
            safeItem == null -> "invalid item ID"
            safeAllowedItems.isEmpty() || safeAllowedItems.size != next.allowedItemIds.size -> "allowed listing IDs are invalid or empty"
            safeItem !in safeAllowedItems -> "query item ID must be one of the allowed listing IDs"
            next.amount <= 0 -> "requested amount must be positive"
            next.maxListingTotal.signum() <= 0 -> "listing total cap must be positive"
            next.cumulativeBudget != null && next.cumulativeBudget.signum() <= 0 -> "cumulative budget must be positive or empty"
            next.maxRefreshes !in 1..100 -> "refresh count must be between 1 and 100"
            next.maxPages !in 0..20 -> "page count must be between 0 and 20"
            next.settleTicks < 0L || next.refreshDelayTicks < 0L || next.ackTimeoutTicks <= 0L -> "settle, refresh delay or acknowledgement timeout is invalid"
            next.requiredEnchantments.values.any { it <= 0 } -> "enchantment levels must be positive"
            else -> null
        }
        if (invalid != null) {
            status = AuctionPurchaseStatus.FAILED
            phase = Phase.TERMINAL
            reason = invalid
            return false
        }
        val normalized = next.copy(
            itemId = safeItem!!,
            requiredEnchantments = next.requiredEnchantments.toSortedMap(),
            allowedItemIds = safeAllowedItems,
        )
        val identity = purchaseIdentity(normalized)
        if (identity in uncertainRequests || identity in uncertainByIdentity) {
            request = normalized
            status = AuctionPurchaseStatus.UNCERTAIN
            phase = Phase.TERMINAL
            reason = "a previous click for this item has an uncertain result"
            return false
        }
        request = normalized
        phase = Phase.NEED_REFRESH
        status = AuctionPurchaseStatus.RUNNING
        reason = ""
        return true
    }

    fun tick(frame: AuctionPurchaseFrame, host: AuctionPurchaseHost): AuctionPurchaseProgress {
        val currentRequest = request ?: return progress()
        if (phase == Phase.IDLE || phase == Phase.TERMINAL) return progress()
        if (session == null) session = frame.snapshot.session
        if (session != frame.snapshot.session) {
            if (pending != null) becomeUncertain("session changed after a purchase click")
            else finish(AuctionPurchaseStatus.FAILED, "session changed during the request")
            return progress()
        }
        if (phase == Phase.WAIT_PURCHASE && frame.snapshot.tick > deadlineTick) {
            becomeUncertain("server inventory increase was not acknowledged before timeout")
            return progress()
        }
        if (frame.snapshot.tick < nextActionTick) return progress()

        when (phase) {
            Phase.NEED_REFRESH -> openOrRefresh(frame, host, currentRequest)
            Phase.WAIT_MENU -> waitForAuctionMenu(frame, currentRequest)
            Phase.WAIT_PAGE -> waitForPageContents(frame, host, currentRequest)
            Phase.SCAN -> scanMenu(frame, host, currentRequest)
            Phase.WAIT_PURCHASE -> waitForPurchase(frame, host, currentRequest)
            Phase.IDLE, Phase.TERMINAL -> Unit
        }
        return progress()
    }

    /** Feed only incoming, decoded frames from the owning module's InventoryAckChannel. */
    fun observeInventory(update: InventorySyncFrame, menuSlotMapEpoch: Long) {
        inventoryAck.observe(update, menuSlotMapEpoch)
    }

    /** Capture a validated confirmation container map before applying packets decoded from that menu. */
    fun captureConfirmationMenuMap(frame: AuctionPurchaseFrame): Boolean {
        val current = pending ?: return false
        val currentRequest = request ?: return false
        val confirmation = frame.confirmation ?: return false
        if (phase != Phase.WAIT_PURCHASE || current.confirmDispatched ||
            !confirmationMatches(frame, confirmation, current, currentRequest)) return false
        return inventoryAck.registerMenuMap(frame)
    }

    /** Cancelling a pending click is uncertain and keeps its spend reservation. */
    fun resetRequest() {
        if (pending != null) {
            becomeUncertain("request cancelled after a purchase click")
            return
        }
        request = null
        phase = Phase.IDLE
        status = AuctionPurchaseStatus.IDLE
        reason = ""
        session = null
        inventoryAck.reset()
        refreshes = 0
        acquiredAmount = 0
        activeMenuId = -1
        expectedPreviousMenuId = -1
    }

    /** Clears per-run confirmed and uncertain spend. Call only when the host starts a new user run. */
    fun resetRun() {
        resetRequest()
        confirmedSpend = BigDecimal.ZERO
        uncertainSpend = BigDecimal.ZERO
        uncertainByIdentity.clear()
        uncertainRequests.clear()
    }

    fun progress(): AuctionPurchaseProgress {
        val current = request
        return AuctionPurchaseProgress(
            status = status,
            requestedItemId = current?.itemId.orEmpty(),
            requestedAmount = current?.amount ?: 0,
            acquiredAmount = acquiredAmount,
            confirmedSpend = confirmedSpend,
            reservedSpend = confirmedSpend + uncertainSpend,
            refreshes = refreshes,
            reason = reason,
        )
    }

    private fun openOrRefresh(frame: AuctionPurchaseFrame, host: AuctionPurchaseHost, request: AuctionPurchaseRequest) {
        val menu = frame.snapshot.menu
        if (menu != null) {
            if (host.dispatch(frame, AuctionPurchaseEffect.CloseMenu(frame.snapshot.session, menu.menuId, menu.screenIdentity))) {
                activeMenuId = -1
                nextActionTick = frame.snapshot.tick + 1L
            }
            return
        }
        if (refreshes >= request.maxRefreshes) {
            finish(if (acquiredAmount > 0) AuctionPurchaseStatus.PARTIAL else AuctionPurchaseStatus.UNAVAILABLE,
                "no eligible listing appeared within the refresh limit")
            return
        }
        val previousMenu = frame.snapshot.playerInventoryMenu?.menuId ?: 0
        val query = MarketRules.safeCommandFragment(request.itemId) ?: run {
            finish(AuctionPurchaseStatus.FAILED, "item query is not safe")
            return
        }
        expectedPreviousMenuId = previousMenu
        if (host.dispatch(frame, AuctionPurchaseEffect.OpenSearch(frame.snapshot.session, query, previousMenu))) {
            refreshes++
            pagesTurned = 0
            phase = Phase.WAIT_MENU
            deadlineTick = frame.snapshot.tick + request.ackTimeoutTicks
        }
    }

    private fun waitForAuctionMenu(frame: AuctionPurchaseFrame, request: AuctionPurchaseRequest) {
        if (frame.snapshot.tick > deadlineTick) {
            finish(if (acquiredAmount > 0) AuctionPurchaseStatus.PARTIAL else AuctionPurchaseStatus.UNAVAILABLE,
                "auction menu did not receive a full-content acknowledgement")
            return
        }
        val opened = frame.acknowledgedMenuId ?: return
        val menu = frame.snapshot.menu ?: return
        if (menu.menuId != opened || opened == expectedPreviousMenuId) return
        if (MenuClassifier.classify(menu) != MenuKind.AUCTION) {
            finish(AuctionPurchaseStatus.FAILED, "acknowledged menu is not an auction")
            return
        }
        if (!menu.cursorEmpty) {
            finish(AuctionPurchaseStatus.FAILED, "auction menu opened with a non-empty cursor")
            return
        }
        activeMenuId = menu.menuId
        phase = Phase.SCAN
        deadlineTick = 0L
        scanFingerprint = ""
        scanStartedAtTick = frame.snapshot.tick
    }

    private fun waitForPageContents(frame: AuctionPurchaseFrame, host: AuctionPurchaseHost, request: AuctionPurchaseRequest) {
        val menu = frame.snapshot.menu
        if (frame.snapshot.tick > deadlineTick) {
            if (menu != null && menu.menuId == activeMenuId && frame.acknowledgedMenuId == activeMenuId) {
                host.dispatch(frame, AuctionPurchaseEffect.CloseMenu(frame.snapshot.session, menu.menuId, menu.screenIdentity))
            }
            finish(if (acquiredAmount > 0) AuctionPurchaseStatus.PARTIAL else AuctionPurchaseStatus.UNAVAILABLE,
                "next auction page did not receive a full-content acknowledgement")
            return
        }
        if (menu == null || menu.menuId != activeMenuId || frame.acknowledgedMenuId != activeMenuId ||
            frame.menuContentEpoch <= pageContentEpoch) return
        if (!menu.cursorEmpty || MenuClassifier.classify(menu) != MenuKind.AUCTION) {
            finish(AuctionPurchaseStatus.FAILED, "auction page changed into an unexpected menu")
            return
        }
        phase = Phase.SCAN
        scanFingerprint = ""
        scanStartedAtTick = frame.snapshot.tick
        deadlineTick = 0L
    }

    private fun scanMenu(frame: AuctionPurchaseFrame, host: AuctionPurchaseHost, request: AuctionPurchaseRequest) {
        val menu = frame.snapshot.menu
        if (menu == null || menu.menuId != activeMenuId || frame.acknowledgedMenuId != activeMenuId ||
            MenuClassifier.classify(menu) != MenuKind.AUCTION) {
            finish(AuctionPurchaseStatus.FAILED, "auction menu identity changed before listing scan")
            return
        }
        if (!menu.cursorEmpty) {
            finish(AuctionPurchaseStatus.FAILED, "auction cursor is not empty")
            return
        }
        val pageFingerprint = purchaseMenuFingerprint(menu)
        if (pageFingerprint != scanFingerprint) {
            scanFingerprint = pageFingerprint
            scanStartedAtTick = frame.snapshot.tick
            return
        }
        if (frame.snapshot.tick - scanStartedAtTick < request.settleTicks) return
        val playerInventory = frame.snapshot.playerInventoryMenu
        if (playerInventory == null || playerInventory.stateId < 0 || menu.stateId < 0 || frame.containerSlotCount <= 0) {
            finish(AuctionPurchaseStatus.FAILED, "inventory or auction slot state is unavailable")
            return
        }
        var budgetBlocked = false
        val candidates = menu.slots.asSequence()
            .filter { it.index in 0 until minOf(45, frame.containerSlotCount) }
            .filter { MarketRules.safeItemId(it.itemId) in request.allowedItemIds }
            .filter(request.listingPredicate)
            .filter { MarketRules.enchantmentsMatch(request.requiredEnchantments, it.actualEnchantments, !request.exactEnchantments) }
            .mapNotNull { slot ->
                val listing = MarketRules.parseListing(slot)
                val total = parseListingTotal(slot, listing.count) ?: return@mapNotNull null
                if (listing.count <= 0) return@mapNotNull null
                if (listing.seller?.equals(frame.playerName, ignoreCase = true) == true) return@mapNotNull null
                if (total > request.maxListingTotal) return@mapNotNull null
                if (request.cumulativeBudget != null && confirmedSpend + uncertainSpend + total > request.cumulativeBudget) {
                    budgetBlocked = true
                    return@mapNotNull null
                }
                Candidate(slot, listing, total)
            }
            .sortedWith(compareBy<Candidate> {
                it.totalPrice.divide(BigDecimal(it.listing.count), 8, java.math.RoundingMode.HALF_UP)
            }.thenBy { it.totalPrice }.thenBy { it.slot.index })
            .toList()
        val candidate = candidates.firstOrNull { canReceiveListing(it, playerInventory.slots) }
        if (candidate == null) {
            val page = frame.nextPageAction
            if (page != null && pagesTurned < request.maxPages && page.slotIndex in 0 until frame.containerSlotCount &&
                page.menuId == menu.menuId &&
                page.screenIdentity == menu.screenIdentity && menu.slot(page.slotIndex)?.fingerprint == page.expectedFingerprint) {
                val effect = AuctionPurchaseEffect.NextPage(
                    frame.snapshot.session, page.menuId, page.screenIdentity, page.slotIndex, page.expectedFingerprint,
                )
                if (host.dispatch(frame, effect)) {
                    pagesTurned++
                    pageContentEpoch = frame.menuContentEpoch
                    phase = Phase.WAIT_PAGE
                    deadlineTick = frame.snapshot.tick + request.ackTimeoutTicks
                }
                return
            }
            if (candidates.isNotEmpty()) {
                finish(if (acquiredAmount > 0) AuctionPurchaseStatus.PARTIAL else AuctionPurchaseStatus.FAILED,
                    "available inventory capacity cannot receive the full listing")
                return
            }
            if (budgetBlocked) {
                finish(if (acquiredAmount > 0) AuctionPurchaseStatus.PARTIAL else AuctionPurchaseStatus.BUDGET_EXHAUSTED,
                    "eligible listings exceed the remaining cumulative run budget")
                return
            }
            if (request.cumulativeBudget != null && confirmedSpend + uncertainSpend >= request.cumulativeBudget) {
                finish(if (acquiredAmount > 0) AuctionPurchaseStatus.PARTIAL else AuctionPurchaseStatus.BUDGET_EXHAUSTED,
                    "cumulative run budget is exhausted")
                return
            }
            closeAndRefresh(frame, host, request)
            return
        }
        if (frame.activeInventorySlotMap.isEmpty()) {
            finish(AuctionPurchaseStatus.FAILED, "active inventory slot map is unavailable")
            return
        }
        val selectedEnchants = candidate.slot.actualEnchantments.toSortedMap()
        val identity = OwnedListingRules.productKey(candidate.slot.itemId, selectedEnchants)
        if (!inventoryAck.begin(
                frame,
                candidate.slot.itemId,
                selectedEnchants,
                candidate.slot.procurementFingerprint,
                candidate.slot.name,
                AuctionPurchaseListingRules.procurementLore(candidate.slot.lore),
                candidate.listing.count,
            )) {
            finish(AuctionPurchaseStatus.FAILED, "authoritative inventory acknowledgement could not start")
            return
        }
        val attemptId = nextPurchaseAttemptId++
        if (nextPurchaseAttemptId <= 0L) nextPurchaseAttemptId = 1L
        val effect = AuctionPurchaseEffect.ClickListing(
            frame.snapshot.session, menu.menuId, menu.screenIdentity, candidate.slot.index,
            candidate.slot.fingerprint, attemptId,
        )
        if (!host.dispatch(frame, effect)) {
            inventoryAck.reset()
            finish(AuctionPurchaseStatus.FAILED, "purchase click was not dispatched")
            return
        }
        pending = PendingPurchase(
            identity = identity,
            attemptId = attemptId,
            itemId = MarketRules.safeItemId(candidate.slot.itemId) ?: candidate.slot.itemId,
            name = candidate.slot.name,
            count = candidate.listing.count,
            enchantments = selectedEnchants,
            procurementFingerprint = candidate.slot.procurementFingerprint,
            totalPrice = candidate.totalPrice,
        )
        uncertainSpend += candidate.totalPrice
        phase = Phase.WAIT_PURCHASE
        deadlineTick = frame.snapshot.tick + request.ackTimeoutTicks
    }

    private fun waitForPurchase(frame: AuctionPurchaseFrame, host: AuctionPurchaseHost, request: AuctionPurchaseRequest) {
        val current = pending ?: run {
            becomeUncertain("purchase state was lost after click")
            return
        }
        observeTransactionReceipts(frame, current, request)
        if (phase == Phase.TERMINAL) return
        val ack = inventoryAck.status(frame.snapshot.playerInventoryMenu)
        if (ack == InventoryAckStatus.ACKNOWLEDGED) {
            acquiredAmount += current.count
            confirmedSpend += current.totalPrice
            uncertainSpend = (uncertainSpend - current.totalPrice).max(BigDecimal.ZERO)
            pending = null
            inventoryAck.reset()
            if (acquiredAmount >= request.amount) {
                finish(AuctionPurchaseStatus.COMPLETE, "requested inventory increase was acknowledged")
            } else {
                phase = Phase.NEED_REFRESH
                nextActionTick = frame.snapshot.tick + request.refreshDelayTicks
                activeMenuId = -1
            }
            return
        }
        if (ack == InventoryAckStatus.REJECTED || ack == InventoryAckStatus.ABANDONED || ack == InventoryAckStatus.IDLE) {
            becomeUncertain("authoritative inventory acknowledgement was rejected or abandoned")
            return
        }
        val confirmation = frame.confirmation
        if (confirmation != null && !current.confirmDispatched) {
            if (!confirmationMatches(frame, confirmation, current, request)) {
                becomeUncertain("purchase confirmation did not match the clicked item and price limits")
                return
            }
            val menu = frame.snapshot.menu
            if (menu != null && !inventoryAck.acceptsMenuMap(menu.menuId, frame.menuSlotMapEpoch) &&
                !inventoryAck.registerMenuMap(frame)) {
                becomeUncertain("validated confirmation container map could not be captured")
                return
            }
            val oldTotal = current.totalPrice
            val delta = confirmation.totalPrice - oldTotal
            if (delta.signum() > 0 && request.cumulativeBudget != null &&
                confirmedSpend + uncertainSpend + delta > request.cumulativeBudget) {
                becomeUncertain("confirmation price exceeds the remaining run budget")
                return
            }
            current.totalPrice = confirmation.totalPrice
            uncertainSpend = (uncertainSpend + delta).max(BigDecimal.ZERO)
            current.confirmationTotalPrice = confirmation.totalPrice
            val effect = AuctionPurchaseEffect.ClickConfirmation(
                frame.snapshot.session, confirmation.evidenceFingerprint, confirmation.action,
            )
            if (!host.dispatch(frame, effect)) {
                becomeUncertain("validated purchase confirmation click was not dispatched")
                return
            }
            current.confirmDispatched = true
            current.confirmationScreenIdentity = confirmation.screenIdentity
            return
        }
        val currentMenu = frame.snapshot.menu
        if (currentMenu != null && !inventoryAck.acceptsMenuMap(currentMenu.menuId, frame.menuSlotMapEpoch)) {
            becomeUncertain("container layout changed without a validated purchase confirmation")
            return
        }
        if (hasConfirmationSurface(frame) && (!current.confirmDispatched || current.confirmationScreenIdentity != currentConfirmationIdentity(frame))) {
            becomeUncertain("purchase confirmation opened without verifiable item and price evidence")
            return
        }
        if (frame.snapshot.tick > deadlineTick) {
            becomeUncertain("server inventory increase was not acknowledged before timeout")
        }
    }

    private fun confirmationMatches(
        frame: AuctionPurchaseFrame,
        evidence: AuctionPurchaseConfirmation,
        current: PendingPurchase,
        request: AuctionPurchaseRequest,
    ): Boolean {
        val dialogIdentity = frame.snapshot.dialog?.screenIdentity
        val menu = frame.snapshot.menu
        val actionIdentityMatches = when (val action = evidence.action) {
            is AuctionPurchaseConfirmationAction.MenuSlot -> menu != null && menu.menuId == action.menuId &&
                MenuClassifier.classify(menu) == MenuKind.CONFIRMATION &&
                menu.screenIdentity == action.screenIdentity && evidence.screenIdentity == action.screenIdentity &&
                evidence.menuId == action.menuId && menu.slot(action.slotIndex)?.let { slot ->
                    slot.fingerprint == action.expectedFingerprint && isPositiveConfirmationLabel(slot.searchableText)
                } == true
            is AuctionPurchaseConfirmationAction.DialogWidget -> frame.snapshot.dialog?.let { dialog ->
                hasConfirmationDialog(dialog) && dialog.screenIdentity == action.screenIdentity && evidence.screenIdentity == action.screenIdentity &&
                    dialog.widget(action.widgetIndex)?.let { widget ->
                        widget.fingerprint == action.expectedFingerprint && normalize(widget.label) == normalize(action.expectedLabel) &&
                            isPositiveConfirmationLabel(widget.label)
                    } == true
            } == true && dialogIdentity == action.screenIdentity
        }
        return actionIdentityMatches && MarketRules.safeItemId(evidence.itemId) == current.itemId &&
            evidence.count == current.count && evidence.enchantments.toSortedMap() == current.enchantments &&
            (evidence.procurementFingerprint == null || evidence.procurementFingerprint == current.procurementFingerprint) &&
            MarketRules.enchantmentsMatch(request.requiredEnchantments, evidence.enchantments, !request.exactEnchantments) &&
            evidence.totalPrice.signum() > 0 && evidence.totalPrice <= request.maxListingTotal &&
            (request.cumulativeBudget == null || confirmedSpend + uncertainSpend - current.totalPrice + evidence.totalPrice <= request.cumulativeBudget)
    }

    private fun observeTransactionReceipts(
        frame: AuctionPurchaseFrame,
        current: PendingPurchase,
        request: AuctionPurchaseRequest,
    ) {
        val incoming = frame.transactionReceipts.asSequence()
            .filter { it.attemptId == current.attemptId && it.session == session }
            .sortedBy { it.receiptId }
            .toList()
        if (incoming.isEmpty()) return

        val fresh = mutableListOf<AuctionPurchaseReceipt>()
        for (receipt in incoming) {
            if (receipt.receiptId <= 0L) {
                reserveUncertainCost(current, receipt.totalPrice)
                becomeUncertain("transaction receipt had an invalid identity")
                return
            }
            val old = current.seenReceipts[receipt.receiptId]
            if (old != null && old != receipt) {
                reserveUncertainCost(current, receipt.totalPrice)
                becomeUncertain("transaction receipt changed after it was first observed")
                return
            }
            if (old == null) {
                current.seenReceipts[receipt.receiptId] = receipt
                fresh += receipt
            }
        }
        if (fresh.isEmpty()) return

        val first = fresh.first()
        fun sameOutcome(receipt: AuctionPurchaseReceipt): Boolean =
            receipt.session == first.session && receipt.attemptId == first.attemptId && receipt.kind == first.kind &&
                receipt.reportedName == first.reportedName && receipt.itemId == first.itemId &&
                receipt.count == first.count && sameMoney(receipt.totalPrice, first.totalPrice)
        if (fresh.any { !sameOutcome(it) }) {
            fresh.mapNotNull { it.totalPrice }.maxOrNull()?.let { reserveUncertainCost(current, it) }
            becomeUncertain("transaction receipts disagree about the purchase result")
            return
        }
        if (first.itemId != null && MarketRules.safeItemId(first.itemId) != current.itemId) {
            reserveUncertainCost(current, first.totalPrice)
            becomeUncertain("purchase receipt named a different item than the clicked listing")
            return
        }
        if ((first.kind == AuctionPurchaseReceiptKind.PURCHASED || first.reportedName.isNotBlank()) &&
            !receiptNameMatches(first.reportedName, current)) {
            reserveUncertainCost(current, first.totalPrice)
            becomeUncertain("purchase receipt name did not match the clicked listing")
            return
        }

        when (first.kind) {
            AuctionPurchaseReceiptKind.PURCHASED -> {
                val actualSpend = first.totalPrice
                if (first.count != current.count || actualSpend == null || actualSpend.signum() <= 0) {
                    reserveUncertainCost(current, actualSpend)
                    becomeUncertain("purchase receipt did not confirm the clicked item and full listing count")
                    return
                }
                if (current.confirmationTotalPrice != null && !sameMoney(current.confirmationTotalPrice, actualSpend)) {
                    reserveUncertainCost(current, actualSpend)
                    becomeUncertain("purchase receipt spend differed from the confirmed dialog total")
                    return
                }
                val totalWithReceipt = confirmedSpend + uncertainSpend - current.totalPrice + actualSpend
                if (actualSpend > request.maxListingTotal ||
                    (request.cumulativeBudget != null && totalWithReceipt > request.cumulativeBudget)) {
                    reserveUncertainCost(current, actualSpend)
                    becomeUncertain("purchase receipt exceeded the listing cap or remaining run budget")
                    return
                }
                uncertainSpend = (uncertainSpend - current.totalPrice + actualSpend).max(BigDecimal.ZERO)
                current.totalPrice = actualSpend
            }
            AuctionPurchaseReceiptKind.LISTING_GONE,
            AuctionPurchaseReceiptKind.OWN_LISTING,
            AuctionPurchaseReceiptKind.INSUFFICIENT_FUNDS -> {
                if (inventoryAck.status(frame.snapshot.playerInventoryMenu) == InventoryAckStatus.ACKNOWLEDGED) {
                    becomeUncertain("transaction rejection conflicted with the full inventory increase")
                    return
                }
                uncertainSpend = (uncertainSpend - current.totalPrice).max(BigDecimal.ZERO)
                pending = null
                inventoryAck.reset()
                val result = if (first.kind == AuctionPurchaseReceiptKind.INSUFFICIENT_FUNDS) {
                    AuctionPurchaseStatus.INSUFFICIENT_FUNDS
                } else AuctionPurchaseStatus.UNAVAILABLE
                val resultReason = when (first.kind) {
                    AuctionPurchaseReceiptKind.LISTING_GONE -> "server reported that the listing was no longer available"
                    AuctionPurchaseReceiptKind.OWN_LISTING -> "server rejected the purchase because the listing belongs to this player"
                    AuctionPurchaseReceiptKind.INSUFFICIENT_FUNDS -> "server reported insufficient funds for the purchase"
                    AuctionPurchaseReceiptKind.PURCHASED -> error("handled above")
                }
                finish(result, resultReason)
            }
        }
    }

    private fun reserveUncertainCost(current: PendingPurchase, observedSpend: BigDecimal?) {
        val reserve = observedSpend?.takeIf { it.signum() > 0 }?.max(current.totalPrice) ?: current.totalPrice
        uncertainSpend = (uncertainSpend - current.totalPrice + reserve).max(BigDecimal.ZERO)
        current.totalPrice = reserve
    }

    private fun sameMoney(first: BigDecimal?, second: BigDecimal?): Boolean = when {
        first == null -> second == null
        second == null -> false
        else -> first.compareTo(second) == 0
    }

    private fun receiptNameMatches(reportedName: String, current: PendingPurchase): Boolean {
        val reported = normalize(reportedName)
        if (reported.isBlank()) return false
        val displayed = normalize(current.name)
        val plainItem = current.itemId.substringAfter(':').replace('_', ' ')
        return reported.contains(displayed) || displayed.contains(reported) ||
            reported.contains(plainItem) || plainItem.contains(reported)
    }

    /** Uses compatible partial stacks plus empty slots; the listing's whole count must fit. */
    private fun canReceiveListing(candidate: Candidate, inventory: List<SlotSnapshot>): Boolean {
        val listing = candidate.slot
        val itemId = MarketRules.safeItemId(listing.itemId) ?: return false
        val expectedName = normalize(listing.name)
        val expectedLore = AuctionPurchaseListingRules.procurementLore(listing.lore).map(::normalize)
        var capacity = 0L
        for (slot in inventory) {
            if (slot.index !in 9..44) continue
            if (slot.itemId.isBlank() || slot.count <= 0) {
                capacity += listing.maxStackSize.coerceAtLeast(1)
                continue
            }
            if (MarketRules.safeItemId(slot.itemId) != itemId ||
                slot.procurementFingerprint != listing.procurementFingerprint ||
                normalize(slot.name) != expectedName ||
                slot.actualEnchantments.toSortedMap() != listing.actualEnchantments.toSortedMap() ||
                AuctionPurchaseListingRules.procurementLore(slot.lore).map(::normalize) != expectedLore) continue
            val maxStackSize = minOf(listing.maxStackSize, slot.maxStackSize).coerceAtLeast(1)
            capacity += (maxStackSize - slot.count).coerceAtLeast(0)
        }
        return capacity >= candidate.listing.count
    }

    private fun hasConfirmationSurface(frame: AuctionPurchaseFrame): Boolean {
        val menu = frame.snapshot.menu
        if (menu != null && MenuClassifier.classify(menu) == MenuKind.CONFIRMATION) return true
        val dialog = frame.snapshot.dialog ?: return false
        return hasConfirmationDialog(dialog)
    }

    private fun hasConfirmationDialog(dialog: DialogSnapshot): Boolean {
        val title = normalize(dialog.title)
        return "buy" in title || "purchase" in title || "confirm" in title || "sure" in title
    }

    private fun isPositiveConfirmationLabel(value: String): Boolean {
        val label = normalize(value)
        if ("cancel" in label || "no" == label || "go back" in label || "close" in label) return false
        return "buy" in label || "confirm" in label || "yes" == label || "purchase" in label
    }

    private fun currentConfirmationIdentity(frame: AuctionPurchaseFrame): Long? =
        frame.snapshot.dialog?.screenIdentity ?: frame.snapshot.menu?.takeIf { MenuClassifier.classify(it) == MenuKind.CONFIRMATION }?.screenIdentity

    private fun closeAndRefresh(frame: AuctionPurchaseFrame, host: AuctionPurchaseHost, request: AuctionPurchaseRequest) {
        val menu = frame.snapshot.menu
        if (menu == null) {
            phase = Phase.NEED_REFRESH
            nextActionTick = frame.snapshot.tick + request.refreshDelayTicks
            activeMenuId = -1
            pagesTurned = 0
            return
        }
        if (menu.menuId != activeMenuId) {
            finish(AuctionPurchaseStatus.FAILED, "menu changed during auction scan")
            return
        }
        if (host.dispatch(frame, AuctionPurchaseEffect.CloseMenu(frame.snapshot.session, menu.menuId, menu.screenIdentity))) {
            phase = Phase.NEED_REFRESH
            nextActionTick = frame.snapshot.tick + request.refreshDelayTicks
            activeMenuId = -1
            pagesTurned = 0
        }
    }

    private fun purchaseMenuFingerprint(menu: MenuSnapshot): String =
        menu.normalizedTitle + "|" + menu.slots.joinToString(";") { slot ->
            val stableLore = slot.lore.filterNot { DYNAMIC_TIMER.containsMatchIn(normalize(it)) }
                .joinToString("|") { normalize(it) }
            listOf(slot.index, slot.itemId, slot.count, normalize(slot.name), stableLore,
                slot.procurementFingerprint, slot.actualEnchantments.toSortedMap()).joinToString("#")
        }

    /** Reads the server's full listing total from its price line, avoiding rounded unit-price drift. */
    private fun parseListingTotal(slot: SlotSnapshot, count: Int): BigDecimal? {
        if (count <= 0) return null
        var maximum: BigDecimal? = null
        val lines = listOf(slot.name) + slot.lore
        for (line in lines) {
            val clean = TextNormalization.stripFormatting(line).lowercase(Locale.ROOT)
            if (!PRICE_HINT.containsMatchIn(clean) || PRICE_EXCLUSION.containsMatchIn(clean)) continue
            val perUnit = "each" in clean || "per item" in clean || Regex("\\bunit\\b").containsMatchIn(clean)
            for (match in PRICE_AMOUNT.findAll(clean)) {
                val amount = parseSourceMoney(match.value)?.takeIf { it.signum() > 0 } ?: continue
                val total = if (perUnit) amount.multiply(BigDecimal(count)) else amount
                if (maximum == null || total > maximum) maximum = total
            }
        }
        return maximum
    }

    private fun becomeUncertain(why: String) {
        val current = pending
        if (current != null) {
            uncertainByIdentity[current.identity] = (uncertainByIdentity[current.identity] ?: BigDecimal.ZERO) + current.totalPrice
            request?.let { uncertainRequests += purchaseIdentity(it) }
        }
        pending = null
        finish(AuctionPurchaseStatus.UNCERTAIN, why)
    }

    private fun finish(next: AuctionPurchaseStatus, why: String) {
        status = next
        reason = why
        phase = Phase.TERMINAL
    }

    private fun purchaseIdentity(request: AuctionPurchaseRequest): String =
        OwnedListingRules.productKey(request.itemId, request.requiredEnchantments)

    private fun parseSourceMoney(value: String): BigDecimal? {
        val match = SOURCE_MONEY.matchEntire(value) ?: return null
        val base = match.groupValues[1].replace(",", "").toBigDecimalOrNull() ?: return null
        val multiplier = when (match.groupValues[2].lowercase(Locale.ROOT)) {
            "k" -> BigDecimal("1000")
            "m" -> BigDecimal("1000000")
            "b" -> BigDecimal("1000000000")
            else -> BigDecimal.ONE
        }
        return base.multiply(multiplier).setScale(0, java.math.RoundingMode.HALF_UP)
    }

    private companion object {
        val DYNAMIC_TIMER = Regex("(?i)(ends? in|time left|expires? in|left)\\s*[: ]*\\d+(?:\\.\\d+)?\\s*(d|days?|h|hours?|hrs?|m|minutes?|mins?)")
        val PRICE_HINT = Regex("(?i)(price|cost|buy now|worth|\\$)")
        val PRICE_EXCLUSION = Regex("(?i)(time left|expires? in|ends? in|seller|listed by|owner)")
        val PRICE_AMOUNT = Regex("\\$?\\d[\\d,]*(?:\\.\\d+)?\\s*[kmb]?")
        val SOURCE_MONEY = Regex("(?i)^\\s*\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmb])?\\s*$")
    }
}

data class InventoryIncreaseExpectation(
    val connectionId: Long,
    val inventoryMenuId: Int,
    val inventoryBaselineStateId: Int,
    val activeMenuId: Int,
    val activeBaselineStateId: Int,
    val menuSlotMapEpoch: Long,
    val activeInventorySlotMap: Map<Int, Int>,
    val baselineInventory: Map<Int, InventorySlotValue>,
    val itemId: String,
    val requiredEnchantments: Map<String, Int>,
    val procurementFingerprint: String,
    val expectedName: String,
    val expectedLore: List<String>,
    val expectedIncrease: Int,
)

/** Requires an incoming server slot/content update to account for the full purchased listing. */
class InventoryIncreaseAckTracker {
    private data class MenuMapKey(val menuId: Int, val epoch: Long)
    private data class MenuLayout(val baselineStateId: Int, val slotMap: Map<Int, Int>, val screenIdentity: Long)

    private var expected: InventoryIncreaseExpectation? = null
    private val layouts = mutableMapOf<MenuMapKey, MenuLayout>()
    private val observedByStream = mutableMapOf<MenuMapKey, MutableMap<Int, InventorySlotValue>>()
    private val revisionByStream = mutableMapOf<MenuMapKey, MutableMap<Int, Int>>()
    private var serverAcknowledged = false
    private var status = InventoryAckStatus.IDLE

    fun begin(
        frame: AuctionPurchaseFrame,
        itemId: String,
        requiredEnchantments: Map<String, Int>,
        procurementFingerprint: String,
        expectedName: String,
        expectedLore: List<String>,
        expectedIncrease: Int,
    ): Boolean {
        reset()
        val menu = frame.snapshot.menu ?: return false
        val inventory = frame.snapshot.playerInventoryMenu ?: return false
        if (menu.stateId < 0 || inventory.stateId < 0 || !menu.cursorEmpty || expectedIncrease <= 0 ||
            frame.activeInventorySlotMap.isEmpty()) return false
        val baseline = inventory.slots.asSequence()
            .filter { it.index in 9..44 }
            .associate {
                it.index to InventorySlotValue(
                    it.itemId, it.count, it.name, it.lore, it.componentFingerprint, it.actualEnchantments, it.procurementFingerprint,
                )
            }
        if (baseline.isEmpty()) return false
        val usableSlotMap = frame.activeInventorySlotMap.filterValues { it in baseline }
        if (usableSlotMap.isEmpty()) return false
        expected = InventoryIncreaseExpectation(
            connectionId = frame.snapshot.session.connection,
            inventoryMenuId = inventory.menuId,
            inventoryBaselineStateId = inventory.stateId,
            activeMenuId = menu.menuId,
            activeBaselineStateId = menu.stateId,
            menuSlotMapEpoch = frame.menuSlotMapEpoch,
            activeInventorySlotMap = usableSlotMap.toMap(),
            baselineInventory = baseline.toMap(),
            itemId = MarketRules.safeItemId(itemId) ?: return false,
            requiredEnchantments = requiredEnchantments.toSortedMap(),
            procurementFingerprint = procurementFingerprint,
            expectedName = normalize(expectedName),
            expectedLore = expectedLore.map(::normalize),
            expectedIncrease = expectedIncrease,
        )
        layouts[MenuMapKey(menu.menuId, frame.menuSlotMapEpoch)] =
            MenuLayout(menu.stateId, usableSlotMap.toMap(), menu.screenIdentity)
        serverAcknowledged = false
        status = InventoryAckStatus.PENDING
        return true
    }

    /** Captures a separately validated confirmation container layout before its packets are decoded. */
    fun registerMenuMap(frame: AuctionPurchaseFrame): Boolean {
        val current = expected ?: return false
        if (status != InventoryAckStatus.PENDING) return false
        val menu = frame.snapshot.menu ?: return false
        val inventoryKeys = current.baselineInventory.keys
        if (menu.stateId < 0 || !menu.cursorEmpty || frame.activeInventorySlotMap.isEmpty()) return false
        val usableSlotMap = frame.activeInventorySlotMap.filterValues { it in inventoryKeys }
        if (usableSlotMap.isEmpty()) return false
        val key = MenuMapKey(menu.menuId, frame.menuSlotMapEpoch)
        val next = MenuLayout(menu.stateId, usableSlotMap.toMap(), menu.screenIdentity)
        val existing = layouts[key]
        if (existing != null) return existing.slotMap == next.slotMap && existing.screenIdentity == next.screenIdentity
        layouts[key] = next
        return true
    }

    fun acceptsMenuMap(menuId: Int, epoch: Long): Boolean = MenuMapKey(menuId, epoch) in layouts

    fun observe(update: InventorySyncFrame, currentMenuSlotMapEpoch: Long): InventoryAckStatus {
        val current = expected ?: return status
        if (status != InventoryAckStatus.PENDING || update.connectionId != current.connectionId) return status
        val baselineState: Int
        val slotMap: Map<Int, Int>
        val streamKey: MenuMapKey
        if (update.menuId == current.inventoryMenuId) {
            // The player's inventory menu has a fixed identity slot map and its own independent state counter.
            baselineState = current.inventoryBaselineStateId
            slotMap = current.baselineInventory.keys.associateWith { it }
            streamKey = MenuMapKey(current.inventoryMenuId, INVENTORY_MENU_EPOCH)
        } else {
            streamKey = MenuMapKey(update.menuId, currentMenuSlotMapEpoch)
            val layout = layouts[streamKey] ?: return status
            baselineState = layout.baselineStateId
            slotMap = layout.slotMap
        }
        if (!stateIsAfter(update.stateId, baselineState)) return status
        if (update.cursor?.count?.let { it > 0 } == true) {
            status = InventoryAckStatus.REJECTED
            return status
        }
        val observed = observedByStream.getOrPut(streamKey) { mutableMapOf() }
        val revisions = revisionByStream.getOrPut(streamKey) { mutableMapOf() }
        val updates = if (update.fullContents) slotMap.mapValues { (packetSlot, _) ->
            update.slots[packetSlot] ?: InventorySlotValue("", 0)
        } else update.slots
        for ((packetSlot, stack) in updates) {
            val inventorySlot = slotMap[packetSlot] ?: continue
            val previousRevision = revisions[inventorySlot]
            val previousStack = observed[inventorySlot]
            when {
                previousRevision == null || stateIsAfter(update.stateId, previousRevision) -> {
                    observed[inventorySlot] = stack
                    revisions[inventorySlot] = update.stateId
                }
                update.stateId == previousRevision && previousStack != stack -> {
                    status = InventoryAckStatus.REJECTED
                    return status
                }
            }
        }
        serverAcknowledged = serverIncreaseCounts(current).isNotEmpty()
        return status
    }

    fun status(localInventory: MenuSnapshot?): InventoryAckStatus {
        if (status != InventoryAckStatus.PENDING || !serverAcknowledged) return status
        val current = expected ?: return status
        val local = localInventory?.slots?.asSequence()?.filter { it.index in current.baselineInventory.keys }
            ?.associate {
                it.index to InventorySlotValue(
                    it.itemId, it.count, it.name, it.lore, it.componentFingerprint, it.actualEnchantments, it.procurementFingerprint,
                )
            }
            ?: return status
        val localIncrease = matchingCount(local, current) - matchingCount(current.baselineInventory, current)
        if (localIncrease >= current.expectedIncrease && localIncrease in serverIncreaseCounts(current)) {
            status = InventoryAckStatus.ACKNOWLEDGED
        }
        return status
    }

    fun expectation(): InventoryIncreaseExpectation? = expected
    fun status(): InventoryAckStatus = status
    fun expectedMapEpoch(): Long? = expected?.menuSlotMapEpoch

    /** Exact local inventory growth for a server-acknowledged increase, after local reconciliation. */
    fun confirmedIncrease(localInventory: MenuSnapshot?): Int? {
        if (status != InventoryAckStatus.ACKNOWLEDGED) return null
        val current = expected ?: return null
        val local = localInventory?.slots?.asSequence()?.filter { it.index in current.baselineInventory.keys }
            ?.associate {
                it.index to InventorySlotValue(
                    it.itemId, it.count, it.name, it.lore, it.componentFingerprint,
                    it.actualEnchantments, it.procurementFingerprint,
                )
            }
            ?: return null
        if (local.keys != current.baselineInventory.keys) return null
        val increase = matchingCount(local, current) - matchingCount(current.baselineInventory, current)
        return increase.takeIf { it >= current.expectedIncrease && it in serverIncreaseCounts(current) }
    }

    fun reset() {
        expected = null
        layouts.clear()
        observedByStream.clear()
        revisionByStream.clear()
        serverAcknowledged = false
        status = InventoryAckStatus.IDLE
    }

    private fun matchingCount(inventory: Map<Int, InventorySlotValue>, expectation: InventoryIncreaseExpectation): Int =
        inventory.values.asSequence()
            .filter { MarketRules.safeItemId(it.itemId) == expectation.itemId }
            .filter { it.actualEnchantments.toSortedMap() == expectation.requiredEnchantments }
            .filter { expectation.procurementFingerprint.isBlank() || it.procurementFingerprint == expectation.procurementFingerprint }
            .filter { normalize(it.name) == expectation.expectedName }
            .filter { it.lore.map(::normalize) == expectation.expectedLore }
            .sumOf { it.count.coerceAtLeast(0) }

    private fun serverIncreaseCounts(expectation: InventoryIncreaseExpectation): Set<Int> {
        val before = matchingCount(expectation.baselineInventory, expectation)
        return observedByStream.values.mapNotNull { values ->
            val combined = expectation.baselineInventory.toMutableMap().apply { putAll(values) }
            (matchingCount(combined, expectation) - before)
                .takeIf { it >= expectation.expectedIncrease }
        }.toSet()
    }

    private fun stateIsAfter(candidate: Int, baseline: Int): Boolean {
        val delta = ((candidate and STATE_MASK) - (baseline and STATE_MASK)) and STATE_MASK
        return delta in 1 until STATE_HALF_RANGE
    }

    private companion object {
        const val STATE_MASK = 0x7FFF
        const val STATE_HALF_RANGE = 0x4000
        const val INVENTORY_MENU_EPOCH = Long.MIN_VALUE
    }
}
