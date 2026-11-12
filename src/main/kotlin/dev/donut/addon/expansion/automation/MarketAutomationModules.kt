package dev.donut.addon.expansion.automation

import dev.donut.addon.expansion.automation.core.ListingData
import dev.donut.addon.expansion.automation.core.MarketRules
import dev.donut.addon.expansion.automation.core.MenuClassifier
import dev.donut.addon.expansion.automation.core.MenuKind
import dev.donut.addon.expansion.automation.core.SlotSnapshot
import dev.donut.addon.expansion.automation.core.InventoryStagePlan
import dev.donut.addon.expansion.automation.core.InventoryStaging
import dev.donut.addon.expansion.automation.core.InventoryAckStatus
import dev.donut.addon.expansion.automation.core.InventoryTransferMachine
import dev.donut.addon.expansion.automation.core.InventoryTransferProgress
import dev.donut.addon.expansion.automation.core.InventoryPickupAction
import dev.donut.addon.expansion.automation.core.InventorySlotValue
import dev.donut.addon.expansion.automation.core.ListingLedger
import dev.donut.addon.expansion.automation.core.ListingLedgerState
import dev.donut.addon.expansion.automation.core.ListingPriceReconciler
import dev.donut.addon.expansion.automation.core.OwnedListingRules
import dev.donut.addon.expansion.automation.core.PriceAction
import dev.donut.addon.expansion.automation.core.TextNormalization
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot
import java.math.BigDecimal
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

private enum class SellPhase { IDLE, WAIT_MENU, OFFER_ITEMS, CONFIRM, WAIT_RESULT }

/** Shared client-thread seller used by the explicit sell modules and Visitor Guard. */
internal class SellWorkflow {
    private var phase = SellPhase.IDLE
    private var deadline = 0L
    private var nextStartAt = 0L

    fun reset() { phase = SellPhase.IDLE; deadline = 0L; nextStartAt = 0L }

    fun tick(
        module: DonutAutomationModule,
        frame: LiveAutomationFrame,
        protectedItems: Collection<String>,
        includeArmor: Boolean = false,
        includeOffhand: Boolean = false,
    ): Boolean {
        val now = System.nanoTime()
        val menu = frame.snapshot.menu
        when (phase) {
            SellPhase.IDLE -> {
                if (menu != null || now < nextStartAt || eligiblePlayerSlots(frame, protectedItems, includeArmor, includeOffhand).isEmpty()) return false
                if (module.command(frame, "sell")) { phase = SellPhase.WAIT_MENU; deadline = now + 8_000_000_000L; return true }
            }
            SellPhase.WAIT_MENU -> {
                if (menu != null && MenuClassifier.classify(menu) == MenuKind.SELL) phase = SellPhase.OFFER_ITEMS
                else if (now > deadline) reset()
            }
            SellPhase.OFFER_ITEMS -> {
                val current = menu ?: run { reset(); nextStartAt = now + 1_000_000_000L; return false }
                if (MenuClassifier.classify(current) != MenuKind.SELL) { reset(); return false }
                val eligible = eligiblePlayerSlots(frame, protectedItems, includeArmor, includeOffhand)
                val slot = eligible.firstOrNull()
                if (slot != null) {
                    if (module.click(frame, slot.index, quickMove = true)) return true
                }
                val button = current.slots.firstOrNull { candidate ->
                    val text = candidate.searchableText
                    (text.contains("sell") || text.contains("confirm sale") || text.contains("sell all")) &&
                        !text.contains("sell history") && !text.contains("cancel")
                }
                if (button != null && button.itemId.isNotBlank() && module.click(frame, button.index)) {
                    phase = SellPhase.WAIT_RESULT; deadline = now + 6_000_000_000L; return true
                }
                if (eligible.isEmpty()) { phase = SellPhase.CONFIRM; deadline = now + 4_000_000_000L }
            }
            SellPhase.CONFIRM -> {
                val current = menu ?: run { phase = SellPhase.WAIT_RESULT; deadline = now + 6_000_000_000L; return false }
                if (MenuClassifier.classify(current) == MenuKind.CONFIRMATION &&
                    current.searchTextContainsAny("sell", "sale", "items")) {
                    val yes = current.slots.firstOrNull { it.searchableText in setOf("confirm", "yes", "sell") || it.searchableText.startsWith("confirm ") }
                    if (yes != null && module.click(frame, yes.index)) { phase = SellPhase.WAIT_RESULT; deadline = now + 6_000_000_000L; return true }
                } else if (MenuClassifier.classify(current) == MenuKind.SELL) {
                    // Wait for the server's completion transition instead of guessing at a button.
                    if (now > deadline) { reset(); nextStartAt = now + 1_000_000_000L }
                } else { phase = SellPhase.WAIT_RESULT; deadline = now + 6_000_000_000L }
            }
            SellPhase.WAIT_RESULT -> {
                if (menu == null || MenuClassifier.classify(menu) != MenuKind.SELL || now > deadline) {
                    reset(); nextStartAt = now + 1_000_000_000L
                }
            }
        }
        return false
    }

    private fun eligiblePlayerSlots(
        frame: LiveAutomationFrame,
        protectedItems: Collection<String>,
        includeArmor: Boolean,
        includeOffhand: Boolean,
    ): List<SlotSnapshot> {
        val menu = frame.snapshot.menu ?: return emptyList()
        val protected = protectedItems.mapNotNull(MarketRules::safeItemId).toSet()
        val allowed = frame.menu.slots.asSequence()
            .filter { it.container === frame.player.inventory }
            .filter { slot -> slot.containerSlot in 0..35 || includeArmor && slot.containerSlot in 36..39 || includeOffhand && slot.containerSlot == 40 }
            .map { it.index }.toSet()
        return menu.slots.filter { it.index in allowed && it.itemId.isNotBlank() && MarketRules.safeItemId(it.itemId) !in protected }
    }
}

internal fun dev.donut.addon.expansion.automation.core.MenuSnapshot.searchTextContainsAny(vararg phrases: String): Boolean =
    (listOf(title) + slots.flatMap { listOf(it.name) + it.lore }).joinToString(" ").lowercase(Locale.ROOT)
        .let { text -> phrases.any(text::contains) }

class DonutSellLoop : DonutAutomationModule("Donut Sell Loop") {
    private val neverSell by textList("Never Sell", mutableListOf("minecraft:netherite_sword", "minecraft:golden_apple", "minecraft:totem_of_undying"))
    private val workflow = SellWorkflow()

    init { literalDescription { "Offers eligible inventory through the server sell flow and keeps protected stacks untouched." } }
    override fun onAutomationReset() = workflow.reset()

    @Suppress("unused")
    private val tick = handler<GameTickEvent> { captureFrame()?.let { workflow.tick(this, it, neverSell) } }
}

private enum class EnchantPolicy(override val tag: String) : Tagged { EXACT("Exact"), AT_LEAST("At Least") }

class DonutAuctionWatch : DonutAutomationModule("Donut Auction Watch") {
    private val item by text("Item", "")
    private val maxPrice by text("Buy Below (each)", "")
    private val enchants by text("Enchantments", "")
    private val enchantMatch by enumChoice("Enchant Match", EnchantPolicy.EXACT)
    private val minHours by float("Min Time Left (h)", 0.0f, 0.0f..48.0f)
    private val refreshMs by int("Refresh Every (ms)", 300, 100..2000)
    private val settleMs by int("Wait After Page Change (ms)", 200, 50..1000)
    private val budget by text("Max Total Spend", "")
    private val requireConfirm by boolean("Only Buy With Confirm", false)
    private val maxBuys by int("Stop After (buys, 0 = never)", 0, 0..100)
    private val autoSell by boolean("Auto Sell", false)
    private val sellPrice by text("Sell Price (each)", "")

    private var nextRefreshAt = 0L
    private var currentScreen = 0L
    private var screenChangedAt = 0L
    private var pendingListing: ListingData? = null
    private var pendingName = ""
    private var pendingTotal = BigDecimal.ZERO
    private var buys = 0
    private var spent = BigDecimal.ZERO
    private var waitingForConfirm = false
    private var confirmDeadline = 0L
    private var pendingAutoSellPrice: BigDecimal? = null

    init { literalDescription { "Finds configured auction listings while enforcing price, enchantment, budget and count limits." } }
    override fun onAutomationReset() {
        nextRefreshAt = 0L; currentScreen = 0L; screenChangedAt = 0L; pendingListing = null
        pendingName = ""; pendingTotal = BigDecimal.ZERO; buys = 0; spent = BigDecimal.ZERO
        waitingForConfirm = false; confirmDeadline = 0L; pendingAutoSellPrice = null
    }

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        val frame = captureFrame() ?: return@handler
        val now = System.nanoTime()
        pendingAutoSellPrice?.let { price ->
            if (frame.snapshot.menu == null && mc.gui.screen() == null && command(frame, "ah sell ${price.toPlainString()}")) {
                pendingAutoSellPrice = null
                return@handler
            }
        }
        val wantedPrice = MarketRules.parseAmount(maxPrice) ?: return@handler
        val wantedBudget = budget.trim().takeIf(String::isNotEmpty)?.let(MarketRules::parseAmount)
        val requiredEnchants = MarketRules.parseEnchantments(enchants) ?: return@handler
        val query = MarketRules.safeCommandFragment(item) ?: return@handler
        if (maxBuys > 0 && buys >= maxBuys) return@handler

        val menu = frame.snapshot.menu
        if (waitingForConfirm) {
            if (menu != null && MenuClassifier.classify(menu) == MenuKind.CONFIRMATION &&
                menu.searchTextContainsAny(pendingName, pendingListing?.itemId.orEmpty())) {
                val confirm = menu.slots.firstOrNull { slot ->
                    val text = slot.searchableText
                    (text.contains("buy") || text.contains("confirm")) && !text.contains("cancel")
                }
                if (confirm != null && frame.menu.slots.getOrNull(confirm.index)?.let { it.container !== frame.player.inventory } == true && click(frame, confirm.index)) {
                    recordPurchase(frame, pendingTotal, wantedBudget)
                }
            } else if (now > confirmDeadline) {
                waitingForConfirm = false; pendingListing = null; nextRefreshAt = now + refreshMs * 1_000_000L
            }
            return@handler
        }

        if (menu == null) {
            if (now >= nextRefreshAt && mc.gui.screen() == null && command(frame, "ah $query")) {
                nextRefreshAt = now + refreshMs * 1_000_000L
                currentScreen = 0L
            }
            return@handler
        }
        if (MenuClassifier.classify(menu) != MenuKind.AUCTION) return@handler
        if (menu.screenIdentity != currentScreen) { currentScreen = menu.screenIdentity; screenChangedAt = now; return@handler }
        if (now - screenChangedAt < settleMs * 1_000_000L) return@handler

        val candidate = menu.slots.asSequence().filter { MarketRules.itemMatches(item, it) }
            .map { slot -> slot to MarketRules.parseListing(slot) }
            .filter { (_, listing) -> MarketRules.listingMatches(
                listing, if (MarketRules.safeItemId(item) != null) item else listing.itemId,
                wantedPrice, BigDecimal(minHours.toString()), requiredEnchants, enchantMatch == EnchantPolicy.AT_LEAST,
            ) }
            .filter { (_, listing) ->
                val total = listing.unitPrice!!.multiply(BigDecimal(listing.count))
                wantedBudget == null || spent + total <= wantedBudget
            }
            .minByOrNull { (_, listing) -> listing.unitPrice!! }
            ?: run { if (now >= nextRefreshAt) command(frame, "ah $query").also { if (it) nextRefreshAt = now + refreshMs * 1_000_000L }; return@handler }
        val (slot, listing) = candidate
        val total = listing.unitPrice!!.multiply(BigDecimal(listing.count))
        pendingListing = listing; pendingName = slot.name; pendingTotal = total
        if (click(frame, slot.index)) {
            if (requireConfirm) { waitingForConfirm = true; confirmDeadline = now + 5_000_000_000L }
            else recordPurchase(frame, total, wantedBudget)
        }
    }

    private fun recordPurchase(frame: LiveAutomationFrame, total: BigDecimal, limit: BigDecimal?) {
        if (limit != null && spent + total > limit) { waitingForConfirm = false; pendingListing = null; return }
        buys++; spent += total; waitingForConfirm = false; pendingListing = null
        if (autoSell) pendingAutoSellPrice = MarketRules.parseAmount(sellPrice)?.takeIf { it.signum() > 0 }
        nextRefreshAt = System.nanoTime() + refreshMs * 1_000_000L
    }
}

class DonutListings : DonutAutomationModule("Donut Listings") {
    private data class MarketReply(val atNanos: Long, val text: String)
    private enum class PricePolicy(override val tag: String) : Tagged { FIXED("Fixed"), AUTO("Auto (beat cheapest)") }
    private val itemCount by int("Items", 1, 1..9)
    private val pricing by enumChoice("Pricing", PricePolicy.FIXED)
    private val itemNames = (1..9).map { text("Item $it", "") }
    private val enchantNames = (1..9).map { text("Enchantments $it", "") }
    private val prices = (1..9).map { text("Price $it (each)", "") }
    private val undercut by text("Undercut By", "100")
    private val priceCheck by int("Price Check (s)", 120, 10..600)
    private val relistGap by float("Relist Higher At (x undercut)", 3.0f, 1.0f..20.0f)
    private val ignoreDrops by float("Ignore Drops Over (x undercut)", 20.0f, 1.0f..100.0f)
    private val salesCheck by int("Sales Check (s)", 20, 5..300)
    private val perListing by int("Items Per Listing", 1, 1..64)
    private val maxListings by int("Max Listings", 25, 1..50)
    private val listingDelay by float("Listing Delay (s)", 0.0f, 0.0f..5.0f)
    private var productIndex = 0
    private var nextActionAt = 0L
    private var listings = 0
    private var state = ListingState.IDLE
    private var stagingPlan: InventoryStagePlan.Swap? = null
    private var stagingTransferPlan: InventoryStagePlan.Transfer? = null
    private val stagingTransfer = InventoryTransferMachine()
    private var stagingMenuId = -1
    private var stagingIssuedAt = 0L
    private var stagingStableAt = 0L
    private var stagingAbandonedItem = ""
    private var stagingItemKey = ""
    private var originalPlayer: LocalPlayer? = null
    private var originalSlot = -1
    private var ownedSlot = -1
    private var listingStartedAt = 0L
    private var pendingListingItem = ""
    private var pendingListingProductKey = ""
    private val marketReplies = ConcurrentLinkedQueue<MarketReply>()
    private val listingLedger = mutableMapOf<String, ListingLedgerState>()
    private var selectedHotbar = -1
    private var heldBefore = ""
    private var pricePerItem = BigDecimal.ZERO
    private var lastPriceCheckAt = 0L
    private var lastObservedCheapest = BigDecimal.ZERO
    private val askByProduct = mutableMapOf<String, BigDecimal>()
    private enum class ListingState {
        IDLE, WAIT_AUCTION, WAIT_STAGE, RECOVER_STAGE, PREPARE_LIST, WAIT_CONFIRM, WAIT_RESULT,
        OPEN_OWN_MENU, WAIT_OWN_ROOT, WAIT_OWN_LISTINGS, COLLECT_OWN_LISTINGS, WAIT_OWN_COLLECT,
    }
    private enum class OwnedListingPurpose { INITIAL_SYNC, PERIODIC_SYNC, RELIST }
    private var ownedPurpose: OwnedListingPurpose? = null
    private var ownedRootMenuId = -1
    private var ownedListingMenuId = -1
    private var ownedDeadline = 0L
    private var ownershipSynced = false
    private var ownershipRetryAt = 0L
    private var lastOwnedRefreshAt = 0L
    private val ownedSyncedItems = mutableSetOf<String>()
    private var ownedTargetIndex = -1
    private var ownedPendingSlot = -1
    private var ownedPendingFingerprint = ""
    private var ownedCollectStartedAt = 0L
    private var ownedCollectedThisPass = 0
    private var relistRemaining = 0
    private var ownedCollectUnresolved = false
    private val ownedVisitedSlots = mutableSetOf<Int>()
    private var pendingRelistPrice = BigDecimal.ZERO
    private var stagingRecoveryAction: InventoryPickupAction? = null
    private var stagingRecoveryIssued = false

    init { literalDescription { "Lists configured inventory items using fixed prices or the live lowest matching price." } }
    override fun onAutomationReset() {
        productIndex = 0; nextActionAt = 0L; listings = 0; state = ListingState.IDLE
        selectedHotbar = -1; heldBefore = ""; pricePerItem = BigDecimal.ZERO; askByProduct.clear()
        lastPriceCheckAt = 0L; lastObservedCheapest = BigDecimal.ZERO
        stagingPlan = null; stagingMenuId = -1; stagingIssuedAt = 0L; stagingStableAt = 0L
        stagingTransferPlan = null; stagingTransfer.reset()
        stagingRecoveryAction = null
        stagingRecoveryIssued = false
        stagingAbandonedItem = ""; stagingItemKey = ""
        restoreOwnedSelection()
        originalPlayer = null; originalSlot = -1; ownedSlot = -1
        listingStartedAt = 0L; pendingListingItem = ""; pendingListingProductKey = ""; marketReplies.clear(); listingLedger.clear()
        ownedPurpose = null; ownedRootMenuId = -1; ownedListingMenuId = -1; ownedDeadline = 0L
        ownershipSynced = false; ownershipRetryAt = 0L; lastOwnedRefreshAt = 0L
        ownedSyncedItems.clear()
        ownedTargetIndex = -1; ownedPendingSlot = -1; ownedPendingFingerprint = ""
        ownedCollectStartedAt = 0L; ownedCollectedThisPass = 0; relistRemaining = 0
        ownedCollectUnresolved = false; ownedVisitedSlots.clear()
        pendingRelistPrice = BigDecimal.ZERO
    }

    override fun onAutomationDisabled() { restoreOwnedInventoryCursor(stagingTransfer) }

    @Suppress("unused")
    private val incoming = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING) return@handler
        val text = (event.packet as? ClientboundSystemChatPacket)?.content?.string ?: return@handler
        if (marketReplies.size >= 64) marketReplies.poll()
        marketReplies.offer(MarketReply(System.nanoTime(), text))
    }

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        val frame = captureFrame() ?: return@handler
        val now = System.nanoTime()
        if (now < nextActionAt) return@handler
        val index = productIndex.coerceIn(0, itemCount.coerceIn(1, 9) - 1)
        val itemName = itemNames[index].get()
        val enchantName = enchantNames[index].get()
        val product = MarketRules.safeCommandFragment(itemName) ?: return@handler
        if (MarketRules.parseEnchantments(enchantName) == null) return@handler
        val currentProductKey = productKey(index)
        if (pricing == PricePolicy.FIXED) {
            pricePerItem = MarketRules.parseAmount(prices[index].get()) ?: BigDecimal.ZERO
            if (pricePerItem.signum() > 0) askByProduct[currentProductKey] = pricePerItem
        } else if (pricePerItem.signum() == 0) {
            pricePerItem = askByProduct[currentProductKey] ?: BigDecimal.ZERO
        }

        when (state) {
            ListingState.IDLE -> {
                if (frame.snapshot.menu != null) return@handler
                if (!ownershipSynced) {
                    if (now >= ownershipRetryAt) beginOwnedListingScan(frame, OwnedListingPurpose.INITIAL_SYNC, now)
                    return@handler
                }
                if (now - lastOwnedRefreshAt >= salesCheck * 1_000_000_000L) {
                    beginOwnedListingScan(frame, OwnedListingPurpose.PERIODIC_SYNC, now)
                    return@handler
                }
                if (openListingCount() >= maxListings && pricing == PricePolicy.FIXED) {
                    nextActionAt = now + salesCheck * 1_000_000_000L
                    return@handler
                }
                if (pricing == PricePolicy.FIXED) {
                    when (val stock = planStock(frame, itemName, MarketRules.parseEnchantments(enchantName).orEmpty())) {
                        is InventoryStagePlan.Swap -> { issueStage(frame, stock); return@handler }
                        is InventoryStagePlan.Transfer -> { issueTransfer(frame, stock); return@handler }
                        is InventoryStagePlan.Ready -> selectedHotbar = stock.hotbarSlot
                        else -> { advanceProduct(now); return@handler }
                    }
                    if (pricePerItem.signum() <= 0) { advanceProduct(now); return@handler }
                    val hotbar = selectedHotbar
                    if (frame.player.inventory.selectedSlot != hotbar) { selectListingSlot(frame, hotbar); return@handler }
                    if (command(frame, "ah sell ${(pricePerItem * BigDecimal(perListing)).toPlainString()}")) {
                        heldBefore = inventoryStackFingerprint(frame.player, hotbar)
                        beginListing(itemName, now)
                        state = ListingState.WAIT_CONFIRM
                        nextActionAt = now + 7_000_000_000L
                    }
                } else if (command(frame, "ah $product")) {
                    state = ListingState.WAIT_AUCTION; lastPriceCheckAt = now
                    nextActionAt = now + priceCheck * 1_000_000_000L
                }
            }
            ListingState.WAIT_STAGE -> {
                val menuNow = frame.snapshot.playerInventoryMenu
                val plan = stagingPlan
                if (menuNow == null || menuNow.menuId != stagingMenuId) {
                    failStaging(now, frame); return@handler
                }
                if (plan != null) {
                    if (!menuNow.cursorEmpty) { failStaging(now, frame); return@handler }
                    when (inventorySwapStatus(frame)) {
                        InventoryAckStatus.ACKNOWLEDGED -> if (InventoryStaging.swapObserved(menuNow, plan)) {
                            if (stagingStableAt == 0L) stagingStableAt = frame.snapshot.tick
                            else if (frame.snapshot.tick > stagingStableAt) {
                                selectedHotbar = plan.hotbarSlot
                                stagingPlan = null; stagingMenuId = -1; stagingStableAt = 0L
                                state = ListingState.PREPARE_LIST
                            }
                        } else failStaging(now, frame)
                        InventoryAckStatus.REJECTED, InventoryAckStatus.ABANDONED, InventoryAckStatus.IDLE -> failStaging(now, frame)
                        InventoryAckStatus.PENDING -> if (frame.snapshot.tick - stagingIssuedAt > 100L) failStaging(now, frame)
                    }
                } else if (stagingTransferPlan != null) {
                    verifyTransfer(frame, now)
                } else failStaging(now, frame)
            }
            ListingState.RECOVER_STAGE -> {
                val action = stagingRecoveryAction
                val menu = frame.snapshot.playerInventoryMenu
                if (action == null || menu == null || menu.menuId != stagingMenuId) {
                    finishStagingFailure(now)
                    return@handler
                }
                if (!stagingRecoveryIssued) {
                    if (frame.snapshot.tick - stagingIssuedAt > 100L) {
                        finishStagingFailure(now)
                    } else if (frame.screen == null) {
                        if (beginInventoryPickup(frame, action)) {
                            stagingTransfer.recoveryIssued()
                            stagingRecoveryIssued = true
                        } else finishStagingFailure(now)
                    }
                    return@handler
                }
                when (inventoryPickupStatus(frame)) {
                    InventoryAckStatus.ACKNOWLEDGED,
                    InventoryAckStatus.REJECTED,
                    InventoryAckStatus.ABANDONED,
                    InventoryAckStatus.IDLE -> finishStagingFailure(now)
                    InventoryAckStatus.PENDING -> if (frame.snapshot.tick - stagingIssuedAt > 100L) {
                        abandonInventoryPickup()
                        finishStagingFailure(now)
                    }
                }
            }
            ListingState.WAIT_AUCTION -> {
                val menu = frame.snapshot.menu ?: return@handler
                if (MenuClassifier.classify(menu) != MenuKind.AUCTION) return@handler
                val required = MarketRules.parseEnchantments(enchantName).orEmpty()
                val owner = frame.player.gameProfile.name
                val ownCount = listingLedger[currentProductKey]?.open ?: 0
                val match = menu.slots.asSequence().filter { MarketRules.itemMatches(itemName, it) }
                    .filter { slot -> MarketRules.enchantmentsMatch(required, MarketRules.parseListing(slot).enchantments, true) }
                    .map(MarketRules::parseListing)
                    .filter { listing ->
                        val ownSeller = listing.seller?.equals(owner, ignoreCase = true) == true
                        val sameKnownAsk = ownCount > 0 && pricePerItem.signum() > 0 &&
                            listing.unitPrice?.compareTo(pricePerItem) == 0
                        !ownSeller && !sameKnownAsk
                    }
                    .mapNotNull { it.unitPrice }.minOrNull()
                if (match != null) {
                    val undercutAmount = MarketRules.parseAmount(undercut) ?: BigDecimal.ZERO
                    val openCount = listingLedger[currentProductKey]?.open ?: 0
                    val decision = ListingPriceReconciler.decide(
                        currentAsk = pricePerItem,
                        cheapestUnit = match,
                        quoteConfirmed = true,
                        hasMatchingListings = true,
                        minimumUnit = BigDecimal.ONE,
                        undercut = undercutAmount,
                        relistGap = BigDecimal(relistGap.toString()),
                        ignoreDrops = BigDecimal(ignoreDrops.toString()),
                        openListings = openCount,
                    )
                    when (decision.action) {
                        PriceAction.WAIT, PriceAction.KEEP -> Unit
                        PriceAction.IGNORE_DEEP_DROP -> {
                            closeMenu(frame)
                            advanceProduct(now)
                            return@handler
                        }
                        PriceAction.SET -> setCurrentAsk(index, decision.nextUnitPrice ?: pricePerItem)
                        PriceAction.RELIST -> {
                            if (openCount > 0) {
                                pendingRelistPrice = decision.nextUnitPrice ?: pricePerItem
                                ownedTargetIndex = index
                                ownedPurpose = OwnedListingPurpose.RELIST
                                ownedVisitedSlots.clear()
                                ownedCollectedThisPass = 0
                                nextActionAt = 0L
                                if (closeMenu(frame)) state = ListingState.OPEN_OWN_MENU
                                return@handler
                            }
                            setCurrentAsk(index, decision.nextUnitPrice ?: pricePerItem)
                        }
                    }
                    if (openListingCount() >= maxListings && relistRemaining == 0 &&
                        (ownedPurpose != OwnedListingPurpose.RELIST)) {
                        closeMenu(frame)
                        advanceProduct(now)
                        return@handler
                    }
                    lastObservedCheapest = match
                    if (closeMenu(frame)) {
                        productIndex = index
                        state = ListingState.PREPARE_LIST
                        nextActionAt = now + (listingDelay * 1_000_000_000.0).toLong()
                    }
                } else if (now - lastPriceCheckAt >= priceCheck * 1_000_000_000L) {
                    advanceProduct(now)
                }
            }
            ListingState.OPEN_OWN_MENU -> {
                if (frame.snapshot.menu != null) return@handler
                val purpose = ownedPurpose ?: run { failOwnedListingScan(now, frame); return@handler }
                beginOwnedListingScan(frame, purpose, now)
            }
            ListingState.WAIT_OWN_ROOT -> {
                val menuId = openedMenuId()
                val menu = frame.snapshot.menu
                if (menuId != null && menu?.menuId == menuId) {
                    if (!OwnedListingRules.isAuctionRoot(menu)) {
                        failOwnedListingScan(now, frame)
                        return@handler
                    }
                    ownedRootMenuId = menu.menuId
                    beginMenuOpenAck(frame, menu.menuId)
                    if (click(frame, 51)) {
                        state = ListingState.WAIT_OWN_LISTINGS
                        ownedDeadline = now + OWNED_MENU_TIMEOUT_NANOS
                    } else failOwnedListingScan(now, frame)
                } else if (now > ownedDeadline) failOwnedListingScan(now, frame)
            }
            ListingState.WAIT_OWN_LISTINGS -> {
                val openedId = openedMenuId()
                val menu = frame.snapshot.menu
                if (openedId != null) {
                    if (menu == null || menu.menuId != openedId || menu.menuId == ownedRootMenuId) return@handler
                    if (!OwnedListingRules.isYourItemsMenu(menu)) {
                        failOwnedListingScan(now, frame)
                        return@handler
                    }
                    ownedListingMenuId = menu.menuId
                    if (!reconcileOwnedListingPage(frame, countMissingAsSold = true)) {
                        failOwnedListingScan(now, frame)
                        return@handler
                    }
                    resetMenuSlotChangeAck()
                    when (ownedPurpose) {
                        OwnedListingPurpose.RELIST -> state = ListingState.COLLECT_OWN_LISTINGS
                        OwnedListingPurpose.INITIAL_SYNC, OwnedListingPurpose.PERIODIC_SYNC -> finishOwnedListingScan(frame, now)
                        null -> failOwnedListingScan(now, frame)
                    }
                } else if (now > ownedDeadline) failOwnedListingScan(now, frame)
            }
            ListingState.COLLECT_OWN_LISTINGS -> collectNextOwnedListing(frame, now)
            ListingState.WAIT_OWN_COLLECT -> {
                val menu = frame.snapshot.menu
                if (menu == null || menu.menuId != ownedListingMenuId) {
                    failOwnedListingScan(now, frame)
                    return@handler
                }
                when (menuSlotChangeAckStatus(frame)) {
                    InventoryAckStatus.ACKNOWLEDGED -> {
                        val targetIndex = ownedTargetIndex
                        if (targetIndex in 0 until itemCount.coerceIn(1, 9)) {
                            val key = productKey(targetIndex)
                            listingLedger[key] = ListingLedger.collected(listingLedger[key] ?: ListingLedgerState())
                            val after = menu.slot(ownedPendingSlot)
                            val required = MarketRules.parseEnchantments(enchantNames[targetIndex].get()).orEmpty()
                            if (after != null && MarketRules.itemMatches(itemNames[targetIndex].get(), after) &&
                                MarketRules.enchantmentsMatch(required, MarketRules.parseListing(after).enchantments, true)) {
                                ownedCollectUnresolved = true
                            }
                        }
                        ownedVisitedSlots += ownedPendingSlot
                        ownedCollectedThisPass++
                        resetMenuSlotChangeAck()
                        state = ListingState.COLLECT_OWN_LISTINGS
                    }
                    InventoryAckStatus.REJECTED, InventoryAckStatus.ABANDONED, InventoryAckStatus.IDLE ->
                        failOwnedListingScan(now, frame)
                    InventoryAckStatus.PENDING -> {
                        if (alreadyBoughtReply(now)) {
                            val targetIndex = ownedTargetIndex
                            if (targetIndex in 0 until itemCount.coerceIn(1, 9)) {
                                val key = productKey(targetIndex)
                                listingLedger[key] = ListingLedger.sold(listingLedger[key] ?: ListingLedgerState())
                            }
                            ownedVisitedSlots += ownedPendingSlot
                            resetMenuSlotChangeAck()
                            state = ListingState.COLLECT_OWN_LISTINGS
                        } else if (now > ownedDeadline) failOwnedListingScan(now, frame)
                    }
                }
            }
            ListingState.PREPARE_LIST -> {
                if (frame.snapshot.menu != null) return@handler
                when (val stock = planStock(frame, itemName, MarketRules.parseEnchantments(enchantName).orEmpty())) {
                    is InventoryStagePlan.Swap -> { issueStage(frame, stock); return@handler }
                        is InventoryStagePlan.Transfer -> { issueTransfer(frame, stock); return@handler }
                    is InventoryStagePlan.Ready -> selectedHotbar = stock.hotbarSlot
                    else -> { advanceProduct(now); return@handler }
                }
                if (pricePerItem.signum() <= 0) { advanceProduct(now); return@handler }
                val hotbar = selectedHotbar
                if (frame.player.inventory.selectedSlot != hotbar) { selectListingSlot(frame, hotbar); return@handler }
                heldBefore = inventoryStackFingerprint(frame.player, hotbar)
                if (command(frame, "ah sell ${(pricePerItem * BigDecimal(perListing)).toPlainString()}")) {
                    beginListing(itemName, now)
                    state = ListingState.WAIT_CONFIRM
                    nextActionAt = now + 7_000_000_000L
                }
            }
            ListingState.WAIT_CONFIRM -> {
                when (listingReply()) {
                    true -> { recordListed(); listings++; restoreSelection(frame); finishListedUnit(now); return@handler }
                    false -> { relistRemaining = 0; restoreSelection(frame); advanceProduct(now); return@handler }
                    null -> Unit
                }
                val menu = frame.snapshot.menu
                if (menu != null && MenuClassifier.classify(menu) == MenuKind.CONFIRMATION &&
                    menu.searchTextContainsAny(itemName, pricePerItem.toPlainString())) {
                    val yes = menu.slots.firstOrNull { it.searchableText.contains("confirm") || it.searchableText == "yes" }
                    if (yes != null && click(frame, yes.index)) { state = ListingState.WAIT_RESULT; nextActionAt = now + salesCheck * 1_000_000_000L }
                } else if (now > nextActionAt && nextActionAt > 0L) {
                    relistRemaining = 0; restoreSelection(frame); advanceProduct(now)
                }
            }
            ListingState.WAIT_RESULT -> {
                when (listingReply()) {
                    true -> { recordListed(); listings++; restoreSelection(frame); finishListedUnit(now) }
                    false -> { relistRemaining = 0; restoreSelection(frame); advanceProduct(now) }
                    null -> if (now > nextActionAt) { relistRemaining = 0; restoreSelection(frame); advanceProduct(now) }
                }
            }
        }
    }

    private fun advanceProduct(now: Long) {
        productIndex = (productIndex + 1) % itemCount.coerceIn(1, 9)
        state = ListingState.IDLE; selectedHotbar = -1; heldBefore = ""; pricePerItem = BigDecimal.ZERO
        nextActionAt = now + (listingDelay * 1_000_000_000.0).toLong()
    }

    private fun beginOwnedListingScan(frame: LiveAutomationFrame, purpose: OwnedListingPurpose, now: Long) {
        if (frame.snapshot.menu != null) return
        val owner = MarketRules.safeCommandFragment(frame.player.gameProfile.name)
        if (owner == null) {
            failOwnedListingScan(now, frame)
            return
        }
        ownedPurpose = purpose
        ownedRootMenuId = -1
        ownedListingMenuId = -1
        ownedDeadline = now + OWNED_MENU_TIMEOUT_NANOS
        ownedVisitedSlots.clear()
        ownedCollectUnresolved = false
        if (!beginMenuOpenAck(frame, frame.menu.containerId) || !command(frame, "ah $owner")) {
            resetMenuSlotChangeAck()
            ownershipRetryAt = now + salesCheck * 1_000_000_000L
            if (purpose == OwnedListingPurpose.RELIST) failOwnedListingScan(now, frame)
            else state = ListingState.IDLE
            return
        }
        state = ListingState.WAIT_OWN_ROOT
    }

    private fun reconcileOwnedListingPage(frame: LiveAutomationFrame, countMissingAsSold: Boolean): Boolean {
        val menu = frame.snapshot.menu ?: return false
        drainSaleNotices()
        val containerSlots = frame.menu.slots.count { it.container !== frame.player.inventory }
        if (!OwnedListingRules.isCompleteYourItemsScan(menu, containerSlots)) return false
        val listingSlots = menu.slots.filter { it.index in 0 until containerSlots }
        val owner = frame.player.gameProfile.name
        val counts = mutableMapOf<String, Int>()
        for (slot in listingSlots) {
            val actual = MarketRules.parseListing(slot)
            val matches = (0 until itemCount.coerceIn(1, 9)).filter { index ->
                val configured = itemNames[index].get().trim()
                val required = MarketRules.parseEnchantments(enchantNames[index].get()) ?: return false
                configured.isNotEmpty() && MarketRules.itemMatches(configured, slot) &&
                    MarketRules.enchantmentsMatch(required, actual.enchantments, true)
            }
            if (matches.isEmpty() || OwnedListingRules.isInactiveListing(slot)) continue
            if (actual.unitPrice == null || actual.unitPrice.signum() <= 0) return false
            if (actual.seller != null && !actual.seller.equals(owner, ignoreCase = true)) return false
            val chosen = matches.maxWithOrNull(
                compareBy<Int> { productSpecificity(it) }.thenByDescending { it },
            ) ?: continue
            val key = productKey(chosen)
            counts[key] = (counts[key] ?: 0) + 1
        }
        for (index in 0 until itemCount.coerceIn(1, 9)) {
            val configured = itemNames[index].get().trim()
            if (configured.isEmpty()) continue
            val key = productKey(index)
            val count = counts[key] ?: 0
            val old = listingLedger[key] ?: ListingLedgerState()
            listingLedger[key] = if (key in ownedSyncedItems) {
                ListingLedger.reconcileOwnedListings(old, count, countMissingAsSold)
            } else {
                ListingLedger.reconcileOpenCount(old, count)
            }
            ownedSyncedItems += key
        }
        ownershipSynced = true
        listings = openListingCount()
        return true
    }

    private fun finishOwnedListingScan(frame: LiveAutomationFrame, now: Long) {
        if (!closeMenu(frame)) {
            failOwnedListingScan(now, frame)
            return
        }
        ownershipSynced = true
        lastOwnedRefreshAt = now
        ownershipRetryAt = 0L
        ownedPurpose = null
        ownedRootMenuId = -1
        ownedListingMenuId = -1
        ownedDeadline = 0L
        state = ListingState.IDLE
        nextActionAt = 0L
    }

    private fun collectNextOwnedListing(frame: LiveAutomationFrame, now: Long) {
        val menu = frame.snapshot.menu
        val targetIndex = ownedTargetIndex
        if (menu == null || menu.menuId != ownedListingMenuId || !menu.cursorEmpty ||
            targetIndex !in 0 until itemCount.coerceIn(1, 9)) {
            failOwnedListingScan(now, frame)
            return
        }
        val configured = itemNames[targetIndex].get().trim()
        val required = MarketRules.parseEnchantments(enchantNames[targetIndex].get()).orEmpty()
        val containerSlots = frame.menu.slots.count { it.container !== frame.player.inventory }
        val owner = frame.player.gameProfile.name
        val candidate = menu.slots.asSequence()
            .filter { it.index in 0 until containerSlots && it.index !in ownedVisitedSlots }
            .filter { MarketRules.itemMatches(configured, it) }
            .filter { MarketRules.enchantmentsMatch(required, MarketRules.parseListing(it).enchantments, true) }
            .filter { OwnedListingRules.isCollectable(it, owner, pricePerItem) }
            .filter { productIndexForListing(it) == targetIndex }
            .firstOrNull()
        if (candidate == null) {
            val key = productKey(targetIndex)
            if (ownedCollectUnresolved || (listingLedger[key]?.open ?: 0) > 0) {
                failOwnedListingScan(now, frame)
                return
            }
            if (!closeMenu(frame)) {
                failOwnedListingScan(now, frame)
                return
            }
            setCurrentAsk(targetIndex, pendingRelistPrice)
            productIndex = targetIndex
            relistRemaining = minOf(ownedCollectedThisPass, (maxListings - openListingCount()).coerceAtLeast(0))
            pendingRelistPrice = BigDecimal.ZERO
            ownedPurpose = null
            ownedRootMenuId = -1
            ownedListingMenuId = -1
            ownedDeadline = 0L
            state = ListingState.PREPARE_LIST
            nextActionAt = now + (listingDelay * 1_000_000_000.0).toLong()
            return
        }
        if (ownedCollectedThisPass >= MAX_OWNED_COLLECTIONS || !hasSpaceForOwnedListing(frame, candidate) ||
            !beginMenuSlotChangeAck(frame, candidate.index)) {
            failOwnedListingScan(now, frame)
            return
        }
        ownedPendingSlot = candidate.index
        ownedPendingFingerprint = candidate.stackFingerprint
        ownedCollectStartedAt = now
        if (click(frame, candidate.index)) {
            ownedDeadline = now + OWNED_COLLECT_TIMEOUT_NANOS
            state = ListingState.WAIT_OWN_COLLECT
        } else {
            resetMenuSlotChangeAck()
            failOwnedListingScan(now, frame)
        }
    }

    private fun alreadyBoughtReply(now: Long): Boolean {
        while (true) {
            val reply = marketReplies.poll() ?: return false
            if (reply.atNanos < ownedCollectStartedAt) continue
            val clean = TextNormalization.stripFormatting(reply.text).lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            when {
                "already bought" in clean -> return true
                "bought your " in clean -> applySaleNotice(clean)
                "too many listed items" in clean || "you cannot do this" in clean ||
                    "you need to wait another" in clean -> return false
            }
        }
    }

    private fun hasSpaceForOwnedListing(frame: LiveAutomationFrame, listing: SlotSnapshot): Boolean {
        val inventory = frame.snapshot.playerInventoryMenu ?: return false
        val emptySlots = inventory.slots.count { it.index in 9..44 && (it.itemId.isBlank() || it.count <= 0) }
        return OwnedListingRules.fitsUsingEmptySlots(listing.count, listing.maxStackSize, emptySlots)
    }

    private fun drainSaleNotices() {
        if (!ownershipSynced) return
        val deferred = ArrayList<MarketReply>()
        while (true) {
            val reply = marketReplies.poll() ?: break
            val clean = TextNormalization.stripFormatting(reply.text).lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            if ("bought your " in clean) applySaleNotice(clean) else deferred += reply
        }
        deferred.forEach(marketReplies::offer)
    }

    private fun failOwnedListingScan(now: Long, frame: LiveAutomationFrame? = null) {
        resetMenuSlotChangeAck()
        if (frame != null) {
            val currentId = frame.snapshot.menu?.menuId
            if (currentId != null && currentId in setOf(ownedRootMenuId, ownedListingMenuId)) closeMenu(frame)
        }
        val purpose = ownedPurpose
        if (purpose != null && purpose != OwnedListingPurpose.RELIST) {
            ownershipRetryAt = now + salesCheck * 1_000_000_000L
        }
        ownedPurpose = null
        ownedRootMenuId = -1
        ownedListingMenuId = -1
        ownedDeadline = 0L
        ownedPendingSlot = -1
        ownedPendingFingerprint = ""
        pendingRelistPrice = BigDecimal.ZERO
        if (purpose == OwnedListingPurpose.RELIST) {
            advanceProduct(now)
        } else {
            state = ListingState.IDLE
            nextActionAt = maxOf(nextActionAt, ownershipRetryAt)
        }
    }

    private fun productKey(index: Int): String = OwnedListingRules.productKey(
        itemNames[index].get(), MarketRules.parseEnchantments(enchantNames[index].get()).orEmpty(),
    )

    private fun productSpecificity(index: Int): Int {
        val enchants = MarketRules.parseEnchantments(enchantNames[index].get()).orEmpty()
        return enchants.size * 256 + enchants.values.sum()
    }

    private fun productIndexForListing(slot: SlotSnapshot): Int? {
        val actual = MarketRules.parseListing(slot).enchantments
        return (0 until itemCount.coerceIn(1, 9)).filter { index ->
            val item = itemNames[index].get().trim()
            val required = MarketRules.parseEnchantments(enchantNames[index].get()).orEmpty()
            item.isNotEmpty() && MarketRules.itemMatches(item, slot) &&
                MarketRules.enchantmentsMatch(required, actual, true)
        }.maxWithOrNull(compareBy<Int> { productSpecificity(it) }.thenByDescending { it })
    }

    private fun setCurrentAsk(index: Int, ask: BigDecimal) {
        pricePerItem = ask
        if (ask.signum() > 0) askByProduct[productKey(index)] = ask
    }

    private fun openListingCount(): Int = listingLedger.values.sumOf { it.open.coerceAtLeast(0) }

    private fun finishListedUnit(now: Long) {
        if (relistRemaining > 0) {
            relistRemaining--
            if (relistRemaining > 0) {
                state = ListingState.PREPARE_LIST
                nextActionAt = now + (listingDelay * 1_000_000_000.0).toLong()
                selectedHotbar = -1
                heldBefore = ""
                return
            }
        }
        relistRemaining = 0
        advanceProduct(now)
    }

    private fun planStock(
        frame: LiveAutomationFrame,
        configured: String,
        requiredEnchantments: Map<String, Int> = emptyMap(),
    ): InventoryStagePlan =
        frame.snapshot.playerInventoryMenu?.let { menu ->
            InventoryStaging.plan(menu, configured, perListing, requiredEnchantments).let { plan ->
                if (stagingAbandonedItem == configured.trim().lowercase(Locale.ROOT)) InventoryStagePlan.Unsafe else plan
            }
        } ?: InventoryStagePlan.Unsafe

    private fun issueStage(frame: LiveAutomationFrame, plan: InventoryStagePlan.Swap) {
        val menu = frame.snapshot.playerInventoryMenu ?: return
        stagingItemKey = itemNames[productIndex.coerceIn(0, itemCount.coerceIn(1, 9) - 1)].get().trim().lowercase(Locale.ROOT)
        if (beginInventorySwap(frame, plan)) {
            stagingPlan = plan
            stagingMenuId = menu.menuId
            stagingIssuedAt = frame.snapshot.tick
            stagingStableAt = 0L
            state = ListingState.WAIT_STAGE
        } else failStaging(System.nanoTime())
    }

    private fun issueTransfer(frame: LiveAutomationFrame, plan: InventoryStagePlan.Transfer) {
        val menu = frame.snapshot.playerInventoryMenu ?: return
        stagingItemKey = itemNames[productIndex.coerceIn(0, itemCount.coerceIn(1, 9) - 1)].get().trim().lowercase(Locale.ROOT)
        if (!stagingTransfer.begin(plan)) { failStaging(System.nanoTime()); return }
        stagingTransferPlan = plan
        stagingMenuId = menu.menuId
        stagingIssuedAt = frame.snapshot.tick
        stagingStableAt = 0L
        state = ListingState.WAIT_STAGE
    }

    private fun verifyTransfer(frame: LiveAutomationFrame, now: Long) {
        when (tickInventoryTransfer(frame, stagingTransfer)) {
            InventoryTransferProgress.READY -> {
                val transfer = stagingTransferPlan ?: run { failStaging(now, frame); return }
                selectedHotbar = transfer.targetMenuSlot - 36
                stagingTransferPlan = null; stagingMenuId = -1; stagingStableAt = 0L
                state = ListingState.PREPARE_LIST
            }
            InventoryTransferProgress.FAILED, InventoryTransferProgress.IDLE -> failStaging(now, frame)
            InventoryTransferProgress.WAITING -> if (frame.snapshot.tick - stagingIssuedAt > 100L) failStaging(now, frame)
        }
    }

    private fun failStaging(now: Long, frame: LiveAutomationFrame? = null) {
        if (frame != null && stagingTransferPlan != null && startTransferRecovery(frame)) {
            stagingTransfer.abandon()
            abandonInventorySwap()
            stagingPlan = null
            stagingTransferPlan = null
            state = ListingState.RECOVER_STAGE
            stagingIssuedAt = frame.snapshot.tick
            return
        }
        finishStagingFailure(now)
    }

    private fun startTransferRecovery(frame: LiveAutomationFrame): Boolean {
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

    private fun finishStagingFailure(now: Long) {
        abandonInventorySwap()
        abandonInventoryPickup()
        stagingTransfer.recoveryIssued()
        stagingTransfer.abandon()
        if (stagingItemKey.isNotEmpty()) stagingAbandonedItem = stagingItemKey
        stagingItemKey = ""
        stagingPlan = null; stagingTransferPlan = null; stagingRecoveryAction = null; stagingRecoveryIssued = false
        stagingMenuId = -1; stagingStableAt = 0L
        state = ListingState.IDLE
        advanceProduct(now)
    }

    private fun beginListing(item: String, now: Long) {
        marketReplies.clear()
        listingStartedAt = now
        pendingListingItem = item
        pendingListingProductKey = productKey(productIndex)
    }

    private fun listingReply(): Boolean? {
        while (true) {
            val reply = marketReplies.poll() ?: return null
            if (reply.atNanos < listingStartedAt) continue
            val clean = TextNormalization.stripFormatting(reply.text).lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            when {
                clean.startsWith("you listed") -> return true
                "too many listed items" in clean || "you cannot do this" in clean ||
                    "you need to wait another" in clean || "don't have enough" in clean ||
                    "not enough items" in clean -> return false
                "bought your " in clean -> applySaleNotice(clean)
            }
        }
    }

    private fun recordListed() {
        val key = pendingListingProductKey.ifEmpty {
            productKey(productIndex.coerceIn(0, itemCount.coerceIn(1, 9) - 1))
        }
        listingLedger[key] = ListingLedger.listed(listingLedger[key] ?: ListingLedgerState())
        pendingListingProductKey = ""
    }

    private fun applySaleNotice(clean: String) {
        val matched = (0 until itemCount.coerceIn(1, 9)).mapNotNull { index ->
            val item = itemNames[index].get().trim()
            if (item.isNotEmpty() && clean.contains(item.lowercase(Locale.ROOT))) productKey(index) else null
        }.distinct()
        if (matched.size == 1) {
            val key = matched.single()
            listingLedger[key] = ListingLedger.sold(listingLedger[key] ?: ListingLedgerState())
        }
    }

    private fun selectListingSlot(frame: LiveAutomationFrame, slot: Int) {
        if (originalSlot < 0 || originalPlayer !== frame.player) {
            originalPlayer = frame.player
            originalSlot = frame.player.inventory.selectedSlot
        }
        ownedSlot = slot
        selectHotbar(frame, slot)
    }

    private fun restoreSelection(frame: LiveAutomationFrame) {
        if (originalPlayer === frame.player && originalSlot in 0..8 && ownedSlot in 0..8 &&
            frame.player.inventory.selectedSlot == ownedSlot) selectHotbar(frame, originalSlot)
        originalPlayer = null; originalSlot = -1; ownedSlot = -1
    }

    private fun restoreOwnedSelection() {
        val player = originalPlayer ?: return
        if (player === mc.player && originalSlot in 0..8 && ownedSlot in 0..8 &&
            player.inventory.selectedSlot == ownedSlot) player.inventory.selectedSlot = originalSlot
    }

    private fun inventoryStackFingerprint(player: LocalPlayer, slot: Int): String {
        if (slot !in 0..8) return ""
        val stack = player.inventory.getItem(slot)
        return "${net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item)}:${stack.count}:${stack.hoverName.string}"
    }

    private companion object {
        const val OWNED_MENU_TIMEOUT_NANOS = 10_000_000_000L
        const val OWNED_COLLECT_TIMEOUT_NANOS = 5_000_000_000L
        const val MAX_OWNED_COLLECTIONS = 50
    }
}
