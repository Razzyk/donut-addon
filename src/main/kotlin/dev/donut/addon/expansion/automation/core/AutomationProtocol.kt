package dev.donut.addon.expansion.automation.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** Immutable, game-independent pieces used by the automation planners and tests. */
data class SessionStamp(
    val generation: Long,
    val connection: Long,
    val world: Long,
    val player: Long,
)

data class SlotSnapshot @JvmOverloads constructor(
    val index: Int,
    val itemId: String,
    val count: Int,
    val name: String,
    val lore: List<String>,
    val componentFingerprint: String = "",
    val actualEnchantments: Map<String, Int> = emptyMap(),
    val maxStackSize: Int = 64,
    /** Purchase matching excludes AH-generated lore; transfer/swap fingerprints keep full components. */
    val procurementFingerprint: String = componentFingerprint,
) {
    val stackFingerprint: String by lazy(LazyThreadSafetyMode.NONE) {
        listOf(
            itemId, count.toString(), normalize(name), lore.joinToString("|") { normalize(it) }, componentFingerprint,
            actualEnchantments.toSortedMap().entries.joinToString("|") { (key, value) -> "$key=$value" },
        ).joinToString("#")
    }
    val fingerprint: String by lazy(LazyThreadSafetyMode.NONE) {
        listOf(
            index.toString(), itemId, count.toString(), normalize(name), lore.joinToString("|") { normalize(it) },
            componentFingerprint, actualEnchantments.toSortedMap().entries.joinToString("|") { (key, value) -> "$key=$value" },
        ).joinToString("#")
    }

    val searchableText: String get() = normalize((listOf(name) + lore).joinToString(" "))
}

data class MenuSnapshot @JvmOverloads constructor(
    val screenIdentity: Long,
    val menuId: Int,
    val title: String,
    val slots: List<SlotSnapshot>,
    val cursorEmpty: Boolean,
    val stateId: Int = -1,
    val cursorStack: InventorySlotValue? = null,
) {
    val normalizedTitle: String get() = normalize(title)
    fun slot(index: Int): SlotSnapshot? = slots.firstOrNull { it.index == index }
}

data class DialogWidgetSnapshot(
    val index: Int,
    val kind: String,
    val label: String,
    val value: String = "",
) {
    val fingerprint: String get() = listOf(index.toString(), kind, normalize(label), normalize(value)).joinToString("#")
}

data class DialogSnapshot @JvmOverloads constructor(
    val screenIdentity: Long,
    val title: String,
    val widgets: List<DialogWidgetSnapshot>,
    /** Server-authored static text from the active dialog model, including item descriptions. */
    val bodyText: List<String> = emptyList(),
    /** Native ItemBody stacks captured separately from their presentation text. */
    val itemStacks: List<InventorySlotValue> = emptyList(),
) {
    val searchableText: String get() = normalize(
        (listOf(title) + widgets.flatMap { listOf(it.label, it.value) } + bodyText + itemStacks.map { it.name })
            .joinToString(" "),
    )
    fun widget(index: Int): DialogWidgetSnapshot? = widgets.firstOrNull { it.index == index }
}

/** Review-page evidence required before placing a source-style order. */
object OrderReviewRules {
    private val moneyToken = Regex("(?i)\\$?([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmb])?")

    fun matches(
        dialog: DialogSnapshot,
        configuredItem: String,
        requiredEnchantments: Map<String, Int>,
        amount: Int,
        price: BigDecimal,
    ): Boolean {
        if (amount <= 0 || price.signum() <= 0 || !hasExpectedItem(dialog, configuredItem, requiredEnchantments)) return false
        return hasLabeledNumber(dialog.bodyText, "amount", BigDecimal(amount), allowOneDisplayStep = false) &&
            hasLabeledNumber(dialog.bodyText, "price", price, allowOneDisplayStep = true)
    }

    private fun hasExpectedItem(
        dialog: DialogSnapshot,
        configuredItem: String,
        requiredEnchantments: Map<String, Int>,
    ): Boolean {
        val wantedId = MarketRules.safeItemId(configuredItem)
        val wantedName = words(configuredItem.removePrefix("minecraft:"))
        if (dialog.itemStacks.isNotEmpty()) return dialog.itemStacks.any { stack ->
            val itemMatches = wantedId?.let { MarketRules.safeItemId(stack.itemId) == it }
                ?: (wantedName.isNotEmpty() && containsWords(stack.name, wantedName))
            itemMatches && requiredEnchantments.all { (name, level) ->
                val enchant = name.substringAfter(':').lowercase(Locale.ROOT)
                stack.actualEnchantments[enchant] == level
            }
        }
        // Some server dialogs render the selected item as text instead of an ItemBody.
        return wantedName.isNotEmpty() && dialog.bodyText.any { containsWords(it, wantedName) } &&
            requiredEnchantments.all { (name, level) ->
                val enchantmentWords = words(name.substringAfter(':'))
                val roman = romanLevel(level) ?: return@all false
                dialog.bodyText.any { line ->
                    containsWords(line, enchantmentWords + level.toString()) ||
                        containsWords(line, enchantmentWords + roman)
                }
            }
    }

    private fun hasLabeledNumber(
        lines: List<String>,
        label: String,
        expected: BigDecimal,
        allowOneDisplayStep: Boolean,
    ): Boolean {
        var found = false
        for (line in lines) {
            if (!words(line).joinToString(" ").startsWith(label)) continue
            val match = moneyToken.find(TextNormalization.stripFormatting(line)) ?: continue
            found = true
            val number = match.groupValues[1].replace(",", "").toBigDecimalOrNull() ?: return false
            val suffix = match.groupValues[2].lowercase(Locale.ROOT)
            val decimals = match.groupValues[1].substringAfter('.', "").length
            val multiplierExponent = when (suffix) { "k" -> 3; "m" -> 6; "b" -> 9; else -> 0 }
            val actual = number.scaleByPowerOfTen(multiplierExponent)
            val displayStep = BigDecimal.ONE.scaleByPowerOfTen((multiplierExponent - decimals).coerceAtLeast(0))
                .max(BigDecimal.ONE)
            val difference = expected.subtract(actual).abs()
            if (if (allowOneDisplayStep) difference > displayStep else difference >= displayStep) return false
        }
        return found
    }

    private fun containsWords(value: String, wanted: List<String>): Boolean {
        val haystack = " ${words(value).joinToString(" ")} "
        val needle = " ${wanted.joinToString(" ").trim()} "
        return needle in haystack
    }

    private fun words(value: String): List<String> = TextNormalization.stripFormatting(value)
        .lowercase(Locale.ROOT).replace(Regex("(?i)^minecraft:"), "")
        .replace('_', ' ').replace(Regex("[^a-z0-9]+"), " ").trim()
        .split(Regex("\\s+")).filter(String::isNotEmpty)

    private fun romanLevel(level: Int): String? = when (level) {
        1 -> "i"; 2 -> "ii"; 3 -> "iii"; 4 -> "iv"; 5 -> "v"; 6 -> "vi"
        7 -> "vii"; 8 -> "viii"; 9 -> "ix"; 10 -> "x"
        else -> null
    }
}

object OrderAcknowledgementRules {
    fun isSuccess(message: String, receivedAtNanos: Long, attemptStartedAtNanos: Long): Boolean {
        if (attemptStartedAtNanos == Long.MIN_VALUE || receivedAtNanos - attemptStartedAtNanos < 0L) return false
        val clean = TextNormalization.stripFormatting(message).lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ").trim()
        return clean.startsWith("you ordered")
    }

    fun isFailure(message: String): Boolean {
        val clean = TextNormalization.stripFormatting(message).lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ").trim()
        return clean.contains("order") &&
            (clean.contains("invalid") || clean.contains("not enough") || clean.contains("failed"))
    }
}

data class AutomationSnapshot @JvmOverloads constructor(
    val session: SessionStamp,
    val tick: Long,
    val menu: MenuSnapshot?,
    val onlineNames: Set<String> = emptySet(),
    val nearbyNames: Set<String> = emptySet(),
    val inventoryFingerprint: String = "",
    val dialog: DialogSnapshot? = null,
    /** Player inventory menu captured even while a container screen is closed. */
    val playerInventoryMenu: MenuSnapshot? = null,
)

enum class MenuKind { AUCTION, ORDER, SELL, PAYMENT, CONFIRMATION, UNKNOWN }

object MenuClassifier {
    @JvmStatic
    fun classify(menu: MenuSnapshot): MenuKind {
        val title = menu.normalizedTitle
        return when {
            "payment" in title || "pay to" in title || "paying " in title -> MenuKind.PAYMENT
            "confirm" in title || "are you sure" in title -> MenuKind.CONFIRMATION
            "order" in title || "buy order" in title -> MenuKind.ORDER
            "auction" in title || "market" in title || title == "ah" || title.startsWith("ah ") -> MenuKind.AUCTION
            "sell" in title -> MenuKind.SELL
            else -> MenuKind.UNKNOWN
        }
    }
}

sealed interface InventoryStagePlan {
    data class Ready(val hotbarSlot: Int, val fingerprint: String) : InventoryStagePlan
    data class Swap(
        val sourceMenuSlot: Int,
        val hotbarSlot: Int,
        val sourceFingerprint: String,
        val displacedFingerprint: String,
    ) : InventoryStagePlan
    data class Transfer(
        val sourceMenuSlot: Int,
        val targetMenuSlot: Int,
        val sourceFingerprint: String,
        val targetFingerprint: String,
        val sourceStack: InventorySlotValue,
        val targetStack: InventorySlotValue,
        val transferCount: Int,
        val requiredCount: Int,
    ) : InventoryStagePlan
    data object Missing : InventoryStagePlan
    data object Unsafe : InventoryStagePlan
}

/** Selects exact stock or a compatible partial-stack merge plan in the player's 36 inventory slots. */
object InventoryStaging {
    @JvmOverloads
    fun plan(
        menu: MenuSnapshot,
        configuredItem: String,
        exactCount: Int,
        requiredEnchantments: Map<String, Int> = emptyMap(),
    ): InventoryStagePlan {
        if (exactCount !in 1..64 || !menu.cursorEmpty || configuredItem.isBlank()) return InventoryStagePlan.Unsafe
        fun matches(slot: SlotSnapshot): Boolean = MarketRules.itemMatches(configuredItem, slot) &&
            MarketRules.enchantmentsMatch(requiredEnchantments, slot.actualEnchantments, true)
        val hotbar = (0..8).mapNotNull { hotbarIndex ->
            val menuSlot = menu.slot(36 + hotbarIndex) ?: return@mapNotNull null
            if (menuSlot.count == exactCount && matches(menuSlot)) hotbarIndex to menuSlot
            else null
        }.firstOrNull()
        if (hotbar != null) return InventoryStagePlan.Ready(hotbar.first, hotbar.second.stackFingerprint)

        val source = (9..35).mapNotNull { slotIndex ->
            val slot = menu.slot(slotIndex) ?: return@mapNotNull null
            if (slot.count == exactCount && matches(slot)) slotIndex to slot
            else null
        }.firstOrNull()

        val destination = (0..8).asSequence().mapNotNull { hotbarIndex ->
            menu.slot(36 + hotbarIndex)?.let { hotbarIndex to it }
        }.firstOrNull { (_, slot) -> slot.count == 0 || slot.itemId.isBlank() }
            ?: (0..8).asSequence().mapNotNull { hotbarIndex ->
                menu.slot(36 + hotbarIndex)?.let { hotbarIndex to it }
            }.firstOrNull { (_, slot) -> !MarketRules.itemMatches(configuredItem, slot) }
        if (source != null && destination != null) {
            return InventoryStagePlan.Swap(
                source.first, destination.first, source.second.stackFingerprint, destination.second.stackFingerprint,
            )
        }

        val matching = (9..44).mapNotNull { slot -> menu.slot(slot)?.takeIf(::matches) }
        val stackGroups = matching.groupBy { it.itemId + "#" + it.componentFingerprint }
        for ((_, group) in stackGroups.entries.sortedByDescending { entry -> entry.value.sumOf { it.count } }) {
            if (group.first().maxStackSize < exactCount) continue
            if (group.sumOf { it.count } < exactCount) continue
            val compatibleTarget = group.firstOrNull { it.index in 36..44 && it.count in 1 until exactCount }
            val emptyTarget = (36..44).asSequence().mapNotNull(menu::slot).firstOrNull { it.count == 0 || it.itemId.isBlank() }
            val target = compatibleTarget ?: emptyTarget ?: continue
            if (target.maxStackSize < exactCount) continue
            val stack = group.filter { it.index != target.index }.maxByOrNull { it.count } ?: continue
            val needed = exactCount - target.count
            val transfer = minOf(stack.count, needed)
            if (transfer <= 0) continue
            return InventoryStagePlan.Transfer(
                sourceMenuSlot = stack.index,
                targetMenuSlot = target.index,
                sourceFingerprint = stack.stackFingerprint,
                targetFingerprint = target.stackFingerprint,
                sourceStack = stack.toInventoryValue(),
                targetStack = target.toInventoryValue(),
                transferCount = transfer,
                requiredCount = exactCount,
            )
        }
        return InventoryStagePlan.Missing
    }

    fun swapObserved(menu: MenuSnapshot, plan: InventoryStagePlan.Swap): Boolean =
        menu.cursorEmpty &&
            menu.slot(plan.hotbarSlot + 36)?.stackFingerprint == plan.sourceFingerprint &&
            menu.slot(plan.sourceMenuSlot)?.stackFingerprint == plan.displacedFingerprint

    private fun SlotSnapshot.toInventoryValue() =
        InventorySlotValue(itemId, count, name, lore, componentFingerprint, actualEnchantments, procurementFingerprint)
}

enum class PriceAction { WAIT, KEEP, SET, RELIST, IGNORE_DEEP_DROP }

data class PriceDecision(val action: PriceAction, val nextUnitPrice: BigDecimal? = null)

/** Reconciles an observed market quote with the current unit ask while protecting against bad drops. */
object ListingPriceReconciler {
    fun decide(
        currentAsk: BigDecimal,
        cheapestUnit: BigDecimal?,
        quoteConfirmed: Boolean,
        hasMatchingListings: Boolean,
        minimumUnit: BigDecimal,
        undercut: BigDecimal,
        relistGap: BigDecimal,
        ignoreDrops: BigDecimal,
        openListings: Int,
    ): PriceDecision {
        if (!quoteConfirmed) return PriceDecision(PriceAction.WAIT)
        val step = undercut.max(BigDecimal.ZERO)
        val cheapest = cheapestUnit?.takeIf { it.signum() > 0 }
        if (cheapest == null) {
            return if (currentAsk.signum() <= 0 && !hasMatchingListings)
                PriceDecision(PriceAction.SET, minimumUnit.max(BigDecimal.ONE).multiply(BigDecimal(2)))
            else PriceDecision(PriceAction.KEEP, currentAsk.takeIf { it.signum() > 0 })
        }

        val next = (cheapest - step).max(minimumUnit.max(BigDecimal.ONE))
        if (currentAsk.signum() <= 0) return PriceDecision(PriceAction.SET, next)

        val drop = currentAsk - cheapest
        val dropLimit = if (step.signum() > 0) step.multiply(ignoreDrops.max(BigDecimal.ONE)) else currentAsk.divide(BigDecimal(2))
        if (drop > dropLimit) return PriceDecision(PriceAction.IGNORE_DEEP_DROP, currentAsk)
        if (next < currentAsk) return PriceDecision(PriceAction.RELIST, next)

        val riseThreshold = currentAsk + step.multiply(relistGap.max(BigDecimal.ZERO))
        if (openListings > 0 && next > riseThreshold) return PriceDecision(PriceAction.RELIST, next)
        return PriceDecision(PriceAction.SET, next)
    }
}

data class ListingLedgerState @JvmOverloads constructor(val open: Int = 0, val listedLifetime: Int = 0, val soldLifetime: Int = 0)

/** Server listing acknowledgements and own-menu counts remain the authority for listing totals. */
object ListingLedger {
    fun listed(state: ListingLedgerState, count: Int = 1): ListingLedgerState {
        val amount = count.coerceAtLeast(0)
        return state.copy(open = state.open + amount, listedLifetime = state.listedLifetime + amount)
    }

    fun sold(state: ListingLedgerState, count: Int = 1): ListingLedgerState {
        val amount = count.coerceIn(0, state.open)
        return state.copy(open = state.open - amount, soldLifetime = state.soldLifetime + amount)
    }

    fun reconcileOpenCount(state: ListingLedgerState, authoritativeOpenCount: Int): ListingLedgerState =
        state.copy(open = authoritativeOpenCount.coerceAtLeast(0))

    /** Reconcile a complete Your Items scan; missing listings count as sales only outside a relist collect pass. */
    fun reconcileOwnedListings(
        state: ListingLedgerState,
        authoritativeOpenCount: Int,
        countMissingAsSold: Boolean,
    ): ListingLedgerState {
        val open = authoritativeOpenCount.coerceAtLeast(0)
        val disappeared = (state.open - open).coerceAtLeast(0)
        return state.copy(
            open = open,
            soldLifetime = state.soldLifetime + if (countMissingAsSold) disappeared else 0,
        )
    }

    /** Acknowledged collection removes the listing from the open count without marking it sold. */
    fun collected(state: ListingLedgerState, count: Int = 1): ListingLedgerState =
        state.copy(open = (state.open - count.coerceAtLeast(0)).coerceAtLeast(0))
}

sealed interface PlannedEffect {
    data class Command(val value: String) : PlannedEffect
    data class UserCommand(val value: String) : PlannedEffect
    data class Suggestion(val requestId: Int, val prefix: String) : PlannedEffect
    data class Click(val menuId: Int, val slot: Int, val button: Int = 0, val quickMove: Boolean = false) : PlannedEffect
    data class CloseMenu(val menuId: Int) : PlannedEffect
    data class DialogSetText(val widgetIndex: Int, val expectedFingerprint: String, val value: String) : PlannedEffect
    data class DialogPress(val widgetIndex: Int, val expectedFingerprint: String, val expectedLabel: String) : PlannedEffect
    data class Chat(val value: String) : PlannedEffect
    data class OpenChat(val value: String) : PlannedEffect
    data class Hotbar(val slot: Int) : PlannedEffect
    data class Attack(val entityId: Int, val range: Double) : PlannedEffect
    data class UseOnBlock(
        val x: Int,
        val y: Int,
        val z: Int,
        val side: String,
        val hitX: Double,
        val hitY: Double,
        val hitZ: Double,
        val expectedBlockId: String,
        val heldItemId: String,
    ) : PlannedEffect
    data object UseMainHand : PlannedEffect
    data object Respawn : PlannedEffect
    data class InventorySwap(
        val menuId: Int,
        val expectedStateId: Int,
        val sourceSlot: Int,
        val hotbarSlot: Int,
        val expectedSource: String,
        val expectedHotbar: String,
    ) : PlannedEffect
    data class InventoryPickup(
        val menuId: Int,
        val expectedStateId: Int,
        val menuSlot: Int,
        val button: Int,
        val expectedSlotFingerprint: String,
        val expectedCursorFingerprint: String,
    ) : PlannedEffect
}

data class ActionTicket(
    val id: Long,
    val session: SessionStamp,
    val expiresAtTick: Long,
    val effect: PlannedEffect,
    val screenIdentity: Long? = null,
    val menuId: Int? = null,
    val slotIndex: Int? = null,
    val expectedSlotFingerprint: String? = null,
    val dialogIdentity: Long? = null,
    val dialogWidgetIndex: Int? = null,
    val expectedDialogWidgetFingerprint: String? = null,
)

enum class TicketDecision { READY, WRONG_SESSION, EXPIRED, WRONG_MENU, CHANGED_SLOT, DUPLICATE_TICK }

/** A one-action-per-tick queue that rejects work planned against an earlier client state. */
class ActionGate(private val capacity: Int = 32) {
    private val queue = ArrayDeque<ActionTicket>()
    private val lastActionAt = HashMap<Long, Long>()

    init { require(capacity > 0) }

    fun offer(ticket: ActionTicket): Boolean {
        if (queue.any { it.id == ticket.id } || queue.size >= capacity) return false
        queue.addLast(ticket)
        return true
    }

    fun inspect(snapshot: AutomationSnapshot): Pair<TicketDecision, ActionTicket?> {
        while (queue.isNotEmpty()) {
            val ticket = queue.first()
            val decision = when {
                ticket.session != snapshot.session -> TicketDecision.WRONG_SESSION
                snapshot.tick > ticket.expiresAtTick -> TicketDecision.EXPIRED
                ticket.screenIdentity != null && snapshot.menu?.screenIdentity != ticket.screenIdentity -> TicketDecision.WRONG_MENU
                ticket.menuId != null && snapshot.menu?.menuId != ticket.menuId -> TicketDecision.WRONG_MENU
                ticket.slotIndex != null && snapshot.menu?.slot(ticket.slotIndex)?.fingerprint != ticket.expectedSlotFingerprint -> TicketDecision.CHANGED_SLOT
                ticket.dialogIdentity != null && snapshot.dialog?.screenIdentity != ticket.dialogIdentity -> TicketDecision.WRONG_MENU
                ticket.dialogWidgetIndex != null && snapshot.dialog?.widget(ticket.dialogWidgetIndex)?.fingerprint != ticket.expectedDialogWidgetFingerprint -> TicketDecision.CHANGED_SLOT
                lastActionAt[ticket.session.generation] == snapshot.tick -> TicketDecision.DUPLICATE_TICK
                else -> TicketDecision.READY
            }
            if (decision == TicketDecision.READY) return decision to ticket
            queue.removeFirst()
            if (decision == TicketDecision.DUPLICATE_TICK) return decision to null
        }
        return TicketDecision.EXPIRED to null
    }

    fun consume(snapshot: AutomationSnapshot): ActionTicket? {
        val (decision, ticket) = inspect(snapshot)
        if (decision != TicketDecision.READY || ticket == null) return null
        queue.removeFirst()
        lastActionAt[ticket.session.generation] = snapshot.tick
        return ticket
    }

    fun clear() {
        queue.clear()
        lastActionAt.clear()
    }

    val size: Int get() = queue.size
}

data class ListingData(
    val itemId: String,
    val count: Int,
    val unitPrice: BigDecimal?,
    val timeRemainingHours: BigDecimal?,
    val enchantments: Map<String, Int>,
    val seller: String?,
)

object MarketRules {
    private val amountPattern = Regex("(?i)^\\s*\\$?([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([km])?\\s*$")
    private val playerPattern = Regex("[A-Za-z0-9_]{1,16}")
    private val itemPattern = Regex("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+")

    fun parseAmount(value: String): BigDecimal? {
        val match = amountPattern.matchEntire(value) ?: return null
        val base = match.groupValues[1].replace(",", "").toBigDecimalOrNull() ?: return null
        val multiplier = when (match.groupValues[2].lowercase(Locale.ROOT)) {
            "k" -> BigDecimal("1000")
            "m" -> BigDecimal("1000000")
            else -> BigDecimal.ONE
        }
        return base.multiply(multiplier).setScale(0, RoundingMode.HALF_UP)
    }

    fun safePlayerName(value: String): String? = value.trim().takeIf(playerPattern::matches)

    fun safeCommandFragment(value: String): String? {
        val clean = value.trim()
        return clean.takeIf {
            it.isNotEmpty() && it.length <= 80 &&
                it.all { ch -> ch.code <= 127 && (ch.isLetterOrDigit() || ch in " _-:./") } &&
                !it.contains("..") && !it.startsWith('/') && !it.startsWith('.')
        }
    }

    fun safeItemId(value: String): String? {
        val clean = value.trim().lowercase(Locale.ROOT).removePrefix("minecraft:")
        return clean.takeIf { itemPattern.matches(it) && !it.contains("..") }
    }

    fun parseEnchantments(value: String): Map<String, Int>? {
        val result = linkedMapOf<String, Int>()
        val input = value.trim()
        if (input.isEmpty()) return result
        for (part in input.split(',', ';')) {
            val tokens = part.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotBlank)
            if (tokens.isEmpty() || tokens.size > 2) return null
            val level = if (tokens.size == 2) tokens[1].toIntOrNull() ?: return null else 1
            if (level !in 1..255) return null
            val name = tokens[0].removePrefix("minecraft:")
            if (!Regex("[a-z0-9_]+(?:[.-][a-z0-9_]+)*").matches(name)) return null
            result[name] = level
        }
        return result
    }

    fun enchantmentsMatch(required: Map<String, Int>, actual: Map<String, Int>, atLeast: Boolean): Boolean =
        required.isEmpty() || (required.all { (name, level) -> (actual[name] ?: return@all false) >= level } &&
            (atLeast || required.keys == actual.filterValues { it > 0 }.keys))

    fun listingMatches(
        listing: ListingData,
        itemId: String,
        maxUnitPrice: BigDecimal?,
        minHours: BigDecimal,
        requiredEnchants: Map<String, Int>,
        atLeast: Boolean,
    ): Boolean {
        val configuredId = safeItemId(itemId)
        if (configuredId != null && safeItemId(listing.itemId) != configuredId) return false
        if (listing.count <= 0 || listing.unitPrice == null) return false
        if (listing.timeRemainingHours == null && minHours > BigDecimal.ZERO) return false
        if (maxUnitPrice != null && listing.unitPrice > maxUnitPrice) return false
        if (listing.timeRemainingHours != null && listing.timeRemainingHours < minHours) return false
        return enchantmentsMatch(requiredEnchants, listing.enchantments, atLeast)
    }

    fun parseListing(slot: SlotSnapshot): ListingData {
        val lines = listOf(slot.name) + slot.lore
        var price: BigDecimal? = null
        var perUnit = false
        var hours: BigDecimal? = null
        var seller: String? = null
        val enchants = linkedMapOf<String, Int>()
        for (line in lines) {
            val clean = normalize(line)
            val priceText = TextNormalization.stripFormatting(line).lowercase(Locale.ROOT)
            if (price == null && PRICE_LINE.containsMatchIn(priceText)) {
                val amount = AMOUNT_TOKEN.find(priceText)?.value?.let(::parseAmount)
                if (amount != null) {
                    price = amount
                    perUnit = "each" in priceText || "per item" in priceText || "unit" in priceText
                }
            }
            if (hours == null) {
                val match = TIME_LINE.find(clean)
                if (match != null) {
                    val value = match.groupValues[1].toBigDecimalOrNull()
                    if (value != null) hours = when (match.groupValues[2]) {
                        "m", "min", "mins", "minute", "minutes" -> value.divide(BigDecimal(60), 4, RoundingMode.HALF_UP)
                        "d", "day", "days" -> value.multiply(BigDecimal(24))
                        else -> value
                    }
                }
            }
            if (seller == null) {
                seller = SELLER_LINE.find(line)?.groupValues?.get(1)?.takeIf(playerPattern::matches)
            }
            parseEnchantLine(line)?.let { (name, level) -> enchants[name] = level }
        }
        val unitPrice = when {
            price == null -> null
            perUnit -> price
            slot.count <= 1 -> price
            else -> price!!.divide(BigDecimal(slot.count), 2, RoundingMode.HALF_UP)
        }
        enchants.putAll(slot.actualEnchantments)
        return ListingData(slot.itemId, slot.count, unitPrice, hours, enchants, seller)
    }

    fun itemMatches(configured: String, slot: SlotSnapshot): Boolean {
        val wanted = configured.trim()
        if (wanted.isEmpty()) return false
        val itemId = safeItemId(wanted)
        if (itemId != null && safeItemId(slot.itemId) == itemId) return true
        val label = normalize(wanted)
        return label.length >= 3 && normalize(slot.name).contains(label)
    }

    private fun parseEnchantLine(value: String): Pair<String, Int>? {
        val match = ENCHANT_LINE.matchEntire(normalize(value)) ?: return null
        val level = match.groupValues[2].toIntOrNull() ?: romanLevel(match.groupValues[2]) ?: return null
        val key = match.groupValues[1].removePrefix("minecraft:")
        return key to level
    }

    private fun romanLevel(value: String): Int? = when (value.uppercase(Locale.ROOT)) {
        "I" -> 1; "II" -> 2; "III" -> 3; "IV" -> 4; "V" -> 5; "VI" -> 6; "VII" -> 7; "VIII" -> 8; "IX" -> 9; "X" -> 10
        else -> null
    }

    private val PRICE_LINE = Regex("(?i)(price|cost|buy now|worth|each|per item|unit|\\$)")
    private val AMOUNT_TOKEN = Regex("\\$?\\d[\\d,]*(?:\\.\\d+)?\\s*[km]?")
    private val TIME_LINE = Regex("(?i)(?:ends? in|time left|expires? in|left)\\s*[: ]*([0-9]+(?:\\.[0-9]+)?)\\s*(d|days?|h|hours?|hrs?|m|minutes?|mins?)")
    private val SELLER_LINE = Regex("(?i)(?:seller|listed by|owner)\\s*[: ]+([A-Za-z0-9_]{1,16})")
    private val ENCHANT_LINE = Regex("^([a-z0-9_:.-]+)\\s+([0-9]+|i{1,3}|iv|v|vi{0,3}|ix|x)$", RegexOption.IGNORE_CASE)
}

/** Identity and safety checks for an acknowledged Donut Your Items listing scan. */
object OwnedListingRules {
    private val pageNumber = Regex("(?i)\\bpage\\s+(\\d+)\\s*(?:/|of)\\s*(\\d+)\\b")
    private val barePageNumber = Regex("(?i)(?:\\(|\\b)(\\d+)\\s*/\\s*(\\d+)(?:\\)|\\b)")
    private val pageControl = Regex("(?i)\\b(next|previous|prev)\\s+page\\b|\\bpage\\s+\\d+\\s*(?:/|of)\\s*\\d+\\b")
    private val inactiveListing = Regex("(?i)\\b(sold|expired|ended|collected|not active)\\b")

    data class YourItemsPage(val pageNumber: Int?, val totalPages: Int?, val nextSlot: Int?, val token: String)

    /** Same item with different configured enchantments has a distinct ledger entry. */
    fun productKey(configuredItem: String, requiredEnchantments: Map<String, Int>): String {
        val item = MarketRules.safeItemId(configuredItem)?.let { "id:$it" }
            ?: "label:${normalize(configuredItem).trim()}"
        val enchants = requiredEnchantments.entries
            .map { it.key.lowercase(Locale.ROOT) to it.value }
            .sortedBy { it.first }
            .joinToString(",") { (name, level) -> "$name=$level" }
        return "$item|ench:$enchants"
    }

    fun isAuctionRoot(menu: MenuSnapshot): Boolean {
        val title = menu.normalizedTitle
        return "order" !in title && menu.slot(51)?.itemId?.let {
            it.endsWith(":chest", ignoreCase = true) || it.endsWith(":trapped_chest", ignoreCase = true)
        } == true
    }

    fun isYourItemsMenu(menu: MenuSnapshot): Boolean = "your items" in menu.normalizedTitle

    /** Parse and fingerprint a single acknowledged page; callers must visit every next-page control. */
    fun yourItemsPage(menu: MenuSnapshot, containerSlotCount: Int): YourItemsPage? {
        if (!isYourItemsMenu(menu) || containerSlotCount <= 0 || menu.stateId < 0 || !menu.cursorEmpty) return null
        val byIndex = menu.slots.filter { it.index in 0 until containerSlotCount }.associateBy { it.index }
        if (byIndex.size != containerSlotCount || (0 until containerSlotCount).any { it !in byIndex }) return null

        val title = menu.normalizedTitle
        val pageMatch = pageNumber.find(title) ?: barePageNumber.find(title)
        val currentPage: Int?
        val totalPages: Int?
        if (pageMatch != null) {
            currentPage = pageMatch.groupValues[1].toIntOrNull() ?: return null
            totalPages = pageMatch.groupValues[2].toIntOrNull() ?: return null
            if (currentPage < 1 || totalPages < currentPage || totalPages > MAX_PAGES) return null
        } else {
            if (Regex("(?i)\\bpage\\s+\\d+\\b").containsMatchIn(title)) return null
            currentPage = null
            totalPages = null
        }

        val nextSlots = byIndex.values.filter(::isNextPageControl)
        if (nextSlots.size > 1) return null
        val nextSlot = nextSlots.singleOrNull()?.index
        if (currentPage != null && ((currentPage < totalPages!!) != (nextSlot != null))) return null

        val gridSize = minOf(containerSlotCount, LISTING_GRID_SLOTS)
        val grid = (0 until gridSize).mapNotNull(byIndex::get)
        if (grid.size != gridSize) return null
        val token = if (currentPage != null) "page:$currentPage/$totalPages"
            else "content:" + grid.joinToString("|") { it.fingerprint }
        return YourItemsPage(currentPage, totalPages, nextSlot, token)
    }

    fun isTerminalYourItemsPage(menu: MenuSnapshot, containerSlotCount: Int, page: YourItemsPage): Boolean {
        if (page.nextSlot != null) return false
        if (page.pageNumber != null) return page.pageNumber == page.totalPages
        val gridSize = minOf(containerSlotCount, LISTING_GRID_SLOTS)
        return (0 until gridSize).any { index ->
            menu.slot(index)?.let { it.itemId.isBlank() || it.count <= 0 } == true
        }
    }

    fun isCompleteYourItemsScan(menu: MenuSnapshot, containerSlotCount: Int): Boolean {
        val page = yourItemsPage(menu, containerSlotCount) ?: return false
        return page.pageNumber == 1 && page.totalPages == 1 && isTerminalYourItemsPage(menu, containerSlotCount, page) ||
            page.pageNumber == null && page.nextSlot == null && isTerminalYourItemsPage(menu, containerSlotCount, page)
    }

    fun isNextPageControl(slot: SlotSnapshot): Boolean =
        Regex("(?i)\\bnext\\s+page\\b|\\bpage\\s+next\\b").containsMatchIn(slot.searchableText)

    private const val LISTING_GRID_SLOTS = 45
    private const val MAX_PAGES = 50

    /** Conservative capacity estimate that never assumes two stacks with differing components merge. */
    fun fitsUsingEmptySlots(listingCount: Int, maxStackSize: Int, emptyPlayerSlots: Int): Boolean {
        if (listingCount <= 0 || maxStackSize <= 0 || emptyPlayerSlots < 0) return false
        return listingCount <= maxStackSize.toLong() * emptyPlayerSlots
    }

    /** Includes only partial player stacks with the same delivered-item identity as the listing. */
    fun compatibleInventoryCapacity(listing: SlotSnapshot, inventory: List<SlotSnapshot>): Long {
        if (listing.count <= 0 || listing.maxStackSize <= 0) return 0L
        val storage = inventory.filter { it.index in 9..44 }
        val partial = storage.asSequence()
            .filter { it.count > 0 && it.itemId == listing.itemId && it.name == listing.name &&
                it.procurementFingerprint == listing.procurementFingerprint &&
                it.actualEnchantments == listing.actualEnchantments }
            .sumOf { (it.maxStackSize - it.count).coerceAtLeast(0).toLong() }
        val empty = storage.count { it.count <= 0 || it.itemId.isBlank() }
        return partial + listing.maxStackSize.toLong() * empty
    }

    /** Validates the visible owner, active state and exact expected per-item ask before a collect click. */
    fun isCollectable(
        slot: SlotSnapshot,
        owner: String,
        expectedUnitPrice: BigDecimal,
    ): Boolean {
        if (isInactiveListing(slot)) return false
        val listing = MarketRules.parseListing(slot)
        if (listing.seller != null && !listing.seller.equals(owner, ignoreCase = true)) return false
        val price = listing.unitPrice ?: return false
        return price.signum() > 0 && expectedUnitPrice.signum() > 0 && price.compareTo(expectedUnitPrice) == 0
    }

    fun isInactiveListing(slot: SlotSnapshot): Boolean {
        val visibleText = normalize((listOf(slot.name) + slot.lore).joinToString(" "))
        return inactiveListing.containsMatchIn(visibleText)
    }

    /** Matches the source seller-name marker, then its one-dollar known-ask fallback. */
    fun isOwnAuctionListing(slot: SlotSnapshot, owner: String, knownAsk: BigDecimal, hasOpenListings: Boolean): Boolean {
        val ownerKey = normalize(owner).replace(Regex("[^a-z0-9]"), "")
        if (ownerKey.isNotEmpty() && slot.lore.any { normalize(it).replace(Regex("[^a-z0-9]"), "").contains(ownerKey) }) {
            return true
        }
        if (!hasOpenListings || knownAsk.signum() <= 0) return false
        val unitPrice = MarketRules.parseListing(slot).unitPrice ?: return false
        return unitPrice.subtract(knownAsk).abs() < BigDecimal.ONE
    }
}

object TextNormalization {
    private const val SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘʀѕꜱᴛᴜᴠᴡʏᴢ"
    private const val SMALL_CAPS_PLAIN = "abcdefghijklmnoprsstuvwyz"

    fun stripFormatting(value: String): String = value.replace(Regex("(?i)§[0-9A-FK-ORX]"), "")
        .replace(Regex("(?i)&#[0-9A-F]{6}"), "")
        .replace(Regex("(?i)\\{#[0-9A-F]{6}}"), "")

    /** Source ServerText normalization before identifier filtering and UI label matching. */
    @JvmStatic
    fun normalizeServerText(value: String): String = buildString(value.length) {
        for (character in stripFormatting(value)) {
            val index = SMALL_CAPS.indexOf(character)
            append(when {
                index >= 0 -> SMALL_CAPS_PLAIN[index]
                character == '\u27A4' || character == '\u2192' -> '-'
                else -> character
            })
        }
    }.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
}

/** Unoccluded vanilla explosion upper bound; armor and block shielding are intentionally ignored. */
object ExplosionSafety {
    fun upperBoundDamageHearts(distance: Double, power: Double): Double {
        if (!distance.isFinite() || !power.isFinite() || distance < 0.0 || power <= 0.0) return Double.POSITIVE_INFINITY
        val diameter = power * 2.0
        if (distance >= diameter) return 0.0
        val impact = 1.0 - distance / diameter
        val damagePoints = ((impact * impact + impact) / 2.0) * (7.0 * diameter) + 1.0
        return damagePoints / 2.0
    }

    fun withinHeartLimit(distance: Double, power: Double, maxHearts: Double): Boolean =
        maxHearts.isFinite() && maxHearts >= 0.0 && upperBoundDamageHearts(distance, power) <= maxHearts

    /**
     * Conservative vanilla-style damage bound after the player's armor and toughness. Block exposure,
     * resistance effects, enchantments, and shields are intentionally omitted, so the result still
     * errs high when the environment offers additional protection.
     */
    fun estimatedDamageHearts(distance: Double, power: Double, armor: Double, toughness: Double): Double {
        val rawHearts = upperBoundDamageHearts(distance, power)
        if (!rawHearts.isFinite() || rawHearts <= 0.0) return rawHearts
        val rawPoints = rawHearts * 2.0
        val effective = (armor - rawPoints / (2.0 + toughness / 4.0)).coerceIn(armor * 0.2, 20.0)
        return (rawPoints * (1.0 - effective / 25.0)).coerceAtLeast(0.0) / 2.0
    }
}

object VisitorNames {
    private val alphabet = ('a'..'z').map(Char::toString)
    private val extended = alphabet + ('0'..'9').map(Char::toString) + "_"

    /** Accepts individual characters and inclusive forms such as `a-d`; blank means all letters. */
    fun parsePrefixes(specification: String, includeNumbers: Boolean): List<String> {
        val allowed = if (includeNumbers) extended else alphabet
        val requested = linkedSetOf<String>()
        val raw = specification.trim().lowercase(Locale.ROOT)
        if (raw.isEmpty()) return allowed
        raw.split(',', ';', ' ', '\n', '\t').filter(String::isNotBlank).forEach { part ->
            val range = Regex("^([a-z0-9_])-([a-z0-9_])$").matchEntire(part)
            if (range != null) {
                val from = range.groupValues[1].single()
                val to = range.groupValues[2].single()
                val start = allowed.indexOf(from.toString())
                val end = allowed.indexOf(to.toString())
                if (start >= 0 && end >= start) allowed.subList(start, end + 1).forEach(requested::add)
            } else {
                part.filter { it.isLetterOrDigit() || it == '_' }.forEach { char ->
                    char.toString().takeIf(allowed::contains)?.let(requested::add)
                }
            }
        }
        return requested.toList()
    }
}

object AutomationProfileAliases {
    val moduleIds: Map<String, String> = linkedMapOf(
        "ah-sniper" to "donut-auction-watch",
        "ah-sell" to "donut-listings",
        "auto-sell" to "donut-sell-loop",
        "order-ah-seller" to "donut-trade-orders",
        "order-dropper" to "donut-order-collector",
        "tp-macro" to "donut-travel-requests",
        "auto-tpahere" to "donut-visitor-guard",
        "panic-pay" to "donut-quick-transfer",
        "crystal-macro" to "donut-crystal-chain",
        "anchor-macro" to "donut-anchor-chain",
        "chat-macro" to "donut-chat-keys",
    )

    /** Unlisted settings retain their exact ID. */
    val changedSettings: Map<String, Map<String, String>> = mapOf(
        "crystal-macro" to mapOf("max-self-damage" to "max-self-damage", "humanize" to "humanize"),
        "anchor-macro" to mapOf("protect-loot" to "protect-loot"),
        "panic-pay" to emptyMap(),
    )

    const val SESSION_ONLY_PAYMENT_SETTING = "pay-to"
}

internal fun normalize(value: String): String = TextNormalization.normalizeServerText(value)
    .replace(Regex("[^a-z0-9._:/ -]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()
