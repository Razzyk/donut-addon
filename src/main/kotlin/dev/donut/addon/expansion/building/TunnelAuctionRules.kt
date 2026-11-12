package dev.donut.addon.expansion.building

import dev.donut.addon.expansion.automation.core.AuctionPurchasePageAction
import dev.donut.addon.expansion.automation.core.AuctionPurchaseConfirmation
import dev.donut.addon.expansion.automation.core.AuctionPurchaseConfirmationAction
import dev.donut.addon.expansion.automation.core.AuctionPurchaseReceiptKind
import dev.donut.addon.expansion.automation.core.DonutSellCandidate
import dev.donut.addon.expansion.automation.core.DonutSellIdentity
import dev.donut.addon.expansion.automation.core.DialogSnapshot
import dev.donut.addon.expansion.automation.core.InventorySlotValue
import dev.donut.addon.expansion.automation.core.InventorySyncFrame
import dev.donut.addon.expansion.automation.core.MarketRules
import dev.donut.addon.expansion.automation.core.MenuSnapshot
import dev.donut.addon.expansion.automation.core.MenuClassifier
import dev.donut.addon.expansion.automation.core.MenuKind
import dev.donut.addon.expansion.automation.core.SlotSnapshot
import dev.donut.addon.expansion.automation.core.TextNormalization
import java.math.BigDecimal
import java.util.Locale

internal data class TunnelPurchaseNotice(
    val kind: AuctionPurchaseReceiptKind,
    val reportedName: String,
    val itemId: String?,
    val count: Int?,
    val totalPrice: BigDecimal?,
)

/** Source-shaped parsing helpers kept independent from the native menu/action adapter. */
internal object TunnelAuctionRules {
    private val countPrefix = Regex("^\\s*([0-9]{1,4})\\s*x?\\s+")
    private val exactMoney = Regex("(?i)^\\s*\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmb])?\\s*$")
    private val moneyToken = Regex("(?i)\\$?\\s*[0-9][0-9,]*(?:\\.[0-9]+)?\\s*[kmb]?(?![a-z])")
    private val dollarAmount = Regex("(?i)\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmb])?(?![a-z])")
    private val labelledAmount = Regex("(?i)\\b(?:total(?: price)?|price|cost|buy now)\\s*[:=]?\\s*\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmb])?(?![a-z])")

    fun parseNotice(raw: String): TunnelPurchaseNotice? {
        val line = TextNormalization.stripFormatting(raw).trim()
        val normalized = line.lowercase(Locale.ROOT)
        if (normalized.startsWith("you bought")) {
            var rest = line.substring("you bought".length).trim()
            var count = 1
            val amount = countPrefix.find(rest)
            if (amount != null) {
                count = amount.groupValues[1].toIntOrNull()?.coerceAtLeast(1) ?: return null
                rest = rest.substring(amount.range.last + 1).trim()
            }
            val forAt = rest.lowercase(Locale.ROOT).lastIndexOf(" for ")
            val name = (if (forAt >= 0) rest.substring(0, forAt) else rest).trim()
            if (name.isBlank()) return null
            val total = if (forAt >= 0) moneyToken.find(rest.substring(forAt + 5))?.value?.let(::parseMoney) else null
            return TunnelPurchaseNotice(
                AuctionPurchaseReceiptKind.PURCHASED,
                name,
                itemIdFromName(name),
                count,
                total,
            )
        }
        return when {
            "enough money" in normalized -> TunnelPurchaseNotice(AuctionPurchaseReceiptKind.INSUFFICIENT_FUNDS, "", null, null, null)
            "your own" in normalized -> TunnelPurchaseNotice(AuctionPurchaseReceiptKind.OWN_LISTING, "", null, null, null)
            "already bought" in normalized || "no longer available" in normalized || "not available" in normalized ->
                TunnelPurchaseNotice(AuctionPurchaseReceiptKind.LISTING_GONE, "", null, null, null)
            else -> null
        }
    }

    fun itemIdFromName(value: String): String? {
        val name = TextNormalization.stripFormatting(value).lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9']+"), " ").trim()
        return when {
            name == "totem" || name.contains("totem of undying") -> "minecraft:totem_of_undying"
            name == "golden apple" || name.contains("golden apple") -> "minecraft:golden_apple"
            name.contains("bottle o enchanting") || name.contains("experience bottle") || name.contains("xp bottle") ->
                "minecraft:experience_bottle"
            else -> null
        }
    }

    /** Source dialog parser accepts a localized amount with k, m, or b suffixes. */
    fun parseMoney(value: String): BigDecimal? {
        val match = exactMoney.matchEntire(value) ?: return null
        val amount = match.groupValues[1].replace(",", "").toBigDecimalOrNull() ?: return null
        val exponent = when (match.groupValues[2].lowercase(Locale.ROOT)) {
            "k" -> 3
            "m" -> 6
            "b" -> 9
            else -> 0
        }
        return amount.scaleByPowerOfTen(exponent).setScale(0, java.math.RoundingMode.HALF_UP)
            .takeIf { it.signum() >= 0 }
    }

    fun confirmationFromDialog(dialog: DialogSnapshot, expected: SlotSnapshot): AuctionPurchaseConfirmation? {
        val title = normalizeText(dialog.title)
        if (listOf("buy", "purchase", "confirm", "sure").none(title::contains)) return null

        val positives = dialog.widgets.asSequence()
            .filter { it.kind.equals("button", ignoreCase = true) }
            .filter { positiveConfirmationLabel(it.label) }
            .toList()
        val button = positives.singleOrNull() ?: return null

        if (dialog.itemStacks.isNotEmpty()) {
            val matchingItems = dialog.itemStacks.filter { value ->
                value.itemId == expected.itemId && value.count == expected.count &&
                    value.actualEnchantments.toSortedMap() == expected.actualEnchantments.toSortedMap() &&
                    value.procurementFingerprint == expected.procurementFingerprint
            }
            if (matchingItems.size != 1 || dialog.itemStacks.size != 1) return null
        } else {
            val wanted = dialogItemNames(expected)
            if (wanted.isEmpty() || wanted.none { containsWords(dialog.searchableText, it) }) return null
        }

        val total = confirmationTotal(dialog) ?: return null
        val action = AuctionPurchaseConfirmationAction.DialogWidget(
            screenIdentity = dialog.screenIdentity,
            widgetIndex = button.index,
            expectedFingerprint = button.fingerprint,
            expectedLabel = button.label,
        )
        val evidence = listOf(
            dialog.screenIdentity.toString(),
            normalizeText(dialog.title),
            dialog.widgets.joinToString("|") { it.fingerprint },
            dialog.bodyText.joinToString("|") { normalizeText(it) },
            dialog.itemStacks.joinToString("|") { it.fingerprint },
            expected.itemId,
            expected.count.toString(),
            expected.procurementFingerprint,
            total.stripTrailingZeros().toPlainString(),
        ).joinToString("#")
        return AuctionPurchaseConfirmation(
            itemId = expected.itemId,
            count = expected.count,
            enchantments = expected.actualEnchantments,
            totalPrice = total,
            screenIdentity = dialog.screenIdentity,
            menuId = null,
            evidenceFingerprint = evidence,
            action = action,
            procurementFingerprint = expected.procurementFingerprint,
        )
    }

    private fun confirmationTotal(dialog: DialogSnapshot): BigDecimal? {
        val lines = buildList {
            add(dialog.title)
            addAll(dialog.widgets.flatMap { listOf(it.label, it.value) })
            addAll(dialog.bodyText)
            dialog.itemStacks.forEach { addAll(it.lore) }
        }.distinct()
        return uniqueConfirmationTotal(lines)
    }

    private fun uniqueConfirmationTotal(lines: List<String>): BigDecimal? {
        val amounts = lines.asSequence()
            .map(TextNormalization::stripFormatting)
            .filter { line ->
                val clean = normalizeText(line)
                listOf("balance", "you have", "your money", "wallet", "bank", "per item", "each", "unit price")
                    .none(clean::contains)
            }
            .flatMap { line ->
                buildList {
                    val matches = if ('$' in line) dollarAmount.findAll(line) else labelledAmount.findAll(line)
                    matches.forEach { match ->
                        val amount = if ('$' in line) match.value else match.groupValues[1] + match.groupValues[2]
                        parseMoney(amount)?.let(::add)
                    }
                }.asSequence()
            }
            .filter { it.signum() > 0 }
            .distinctBy { it.stripTrailingZeros().toPlainString() }
            .toList()
        return amounts.singleOrNull()
    }

    private fun dialogItemNames(expected: SlotSnapshot): List<String> = when (expected.itemId) {
        "minecraft:totem_of_undying" -> listOf("totem", "totem of undying")
        "minecraft:golden_apple" -> listOf("golden apple", "golden apples")
        "minecraft:experience_bottle" -> listOf("experience bottle", "bottle o enchanting", "xp bottle")
        else -> listOf(expected.name).filter(String::isNotBlank)
    }

    private fun containsWords(text: String, phrase: String): Boolean {
        val normalizedText = normalizeText(text).replace(Regex("[^a-z0-9]+"), " ").trim()
        val normalizedPhrase = normalizeText(phrase).replace(Regex("[^a-z0-9]+"), " ").trim()
        return normalizedPhrase.isNotBlank() && " $normalizedText ".contains(" $normalizedPhrase ")
    }

    private fun positiveConfirmationLabel(raw: String): Boolean {
        val label = normalizeText(raw)
        if (label == "no" || label.startsWith("no ") ||
            listOf("cancel", "decline", "no thanks", "go back", "close", "dont", "don t", "do not")
                .any(label::contains)) return false
        return listOf("confirm", "purchase", "buy", "yes").any(label::contains)
    }

    private fun normalizeText(raw: String): String = TextNormalization.stripFormatting(raw)
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9._:/ -]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Only recognizes explicit forward-page controls beyond the listing grid. */
    fun nextPageAction(menu: MenuSnapshot, containerSlotCount: Int): AuctionPurchasePageAction? {
        val candidates = menu.slots.asSequence()
            .filter { it.index >= 45 && it.index < containerSlotCount }
            .filter { slot ->
                val text = slot.searchableText
                val previous = listOf("previous", "prev", "back").any(text::contains)
                !previous && ("next" in text || "next page" in text ||
                    slot.itemId.endsWith(":arrow") && "page" in text)
            }
            .toList()
        if (candidates.size != 1) return null
        val slot = candidates.single()
        return AuctionPurchasePageAction(menu.menuId, menu.screenIdentity, slot.index, slot.fingerprint)
    }

    fun positiveConfirmationAction(menu: MenuSnapshot, containerSlotCount: Int): SlotSnapshot? {
        val actions = menu.slots.asSequence()
            .filter { it.index in 0 until containerSlotCount }
            .filter { slot ->
                val text = slot.searchableText
                val positive = listOf("confirm", "purchase", "buy", "yes").any(text::contains)
                val negative = listOf("cancel", "decline", "no thanks", "go back").any(text::contains)
                positive && !negative
            }
            .toList()
        return actions.singleOrNull()
    }

    /** Returns a price only when the menu has one unambiguous total-like value. */
    fun confirmationTotal(menu: MenuSnapshot, containerSlotCount: Int): BigDecimal? {
        val lines = menu.slots.asSequence()
            .filter { it.index in 0 until containerSlotCount }
            .flatMap { (listOf(it.name) + it.lore).asSequence() }
            .toList()
        return uniqueConfirmationTotal(lines)
    }
}

internal object TunnelSellRules {
    val junkItemIds = setOf(
        "cobblestone", "cobbled_deepslate", "deepslate", "stone", "tuff", "granite", "diorite", "andesite",
        "dirt", "gravel", "flint", "netherrack", "calcite", "smooth_basalt", "basalt", "blackstone",
    )

    /** Source scans inventory slots 35 down to 0, excluding the selected slot and taking four. */
    fun approvedStacks(
        stacksByInventoryIndex: List<Pair<Int, InventorySlotValue>>,
        selectedHotbarSlot: Int,
    ): List<DonutSellCandidate> {
        val chosenIdentityByItem = HashMap<String, String>()
        val result = ArrayList<DonutSellCandidate>(4)
        for ((inventorySlot, stack) in stacksByInventoryIndex.sortedByDescending { it.first }) {
            if (result.size == 4) break
            if (inventorySlot !in 0..35 || inventorySlot == selectedHotbarSlot || stack.count <= 0 ||
                MarketRules.safeItemId(stack.itemId)?.substringAfter(':') !in junkItemIds) continue
            val identity = DonutSellIdentity.of(stack)
            val acceptedIdentity = chosenIdentityByItem[stack.itemId]
            if (acceptedIdentity != null && acceptedIdentity != identity) continue
            chosenIdentityByItem.putIfAbsent(stack.itemId, identity)
            result += DonutSellCandidate(inventorySlot, stack)
        }
        return result
    }

    fun candidates(
        menu: MenuSnapshot,
        activeInventorySlotMap: Map<Int, Int>,
        selectedHotbarSlot: Int,
    ): List<SlotSnapshot> = menu.slots.asSequence()
        .filter { slot ->
            val inventorySlot = activeInventorySlotMap[slot.index] ?: return@filter false
            inventorySlot in 9..44 && inventorySlot != 36 + selectedHotbarSlot && slot.count > 0 &&
                MarketRules.safeItemId(slot.itemId)?.substringAfter(':') in junkItemIds
        }
        .sortedByDescending { slot ->
            val inventorySlot = activeInventorySlotMap.getValue(slot.index)
            if (inventorySlot in 36..44) inventorySlot - 36 else inventorySlot
        }
        .take(4)
        .toList()

    /** The source owns the /sell command and accepts its opened non-inventory container by shape, not title. */
    fun isSellMenu(menu: MenuSnapshot, containerSlotCount: Int): Boolean =
        containerSlotCount > 53 && menu.slot(53) != null

    fun isConfirmSaleButton(slot: SlotSnapshot): Boolean {
        val text = slot.searchableText
        if ("cancel" in text || "decline" in text || "back" in text) return false
        // The primary source clicks its fixed slot 53 even when its native item has no readable label.
        return text.isBlank() || "sell" in text || "confirm sale" in text || "sell all" in text
    }

    /** Returns the still-present acknowledged offer, or null if any unrelated sell-menu item changed. */
    fun remainingOffer(
        baseline: Map<String, Int>,
        current: Map<String, Int>,
        offered: Map<String, Int>,
    ): Map<String, Int>? {
        if (offered.isEmpty() || offered.values.any { it <= 0 }) return null
        val remaining = linkedMapOf<String, Int>()
        for (item in baseline.keys + current.keys + offered.keys) {
            val before = baseline[item] ?: 0
            val now = current[item] ?: 0
            val amount = offered[item]
            if (amount == null) {
                if (now != before) return null
            } else {
                val stillThere = now - before
                if (stillThere !in 0..amount) return null
                if (stillThere > 0) remaining[item] = stillThere
            }
        }
        return remaining
    }
}

internal enum class TunnelSellTransferStatus { IDLE, PENDING, ACKNOWLEDGED, REJECTED }

/** Requires server observation of both the source removal and matching sell-container increase. */
internal class TunnelSellTransferAckTracker {
    private var connectionId = 0L
    private var menuId = -1
    private var baselineStateId = -1
    private var menuMapEpoch = Long.MIN_VALUE
    private var sourceActiveSlot = -1
    private var sourceValue: InventorySlotValue? = null
    private var expectedItemId = ""
    private var expectedCount = 0
    private var containerSlotCount = 0
    private var baselineSlots = emptyMap<Int, InventorySlotValue>()
    private val observedSlots = mutableMapOf<Int, InventorySlotValue>()
    private val revisions = mutableMapOf<Int, Int>()
    private var status = TunnelSellTransferStatus.IDLE

    fun begin(
        connectionId: Long,
        menuId: Int,
        baselineStateId: Int,
        menuMapEpoch: Long,
        activeInventorySlotMap: Map<Int, Int>,
        baselineSlots: Map<Int, InventorySlotValue>,
        sourceActiveSlot: Int,
        containerSlotCount: Int,
    ): Boolean {
        reset()
        val source = baselineSlots[sourceActiveSlot] ?: return false
        val sourceInventorySlot = activeInventorySlotMap[sourceActiveSlot] ?: return false
        if (connectionId <= 0L || menuId < 0 || baselineStateId < 0 || containerSlotCount <= 0 ||
            sourceInventorySlot !in 9..44 || source.count <= 0 || source.itemId.isBlank() ||
            baselineSlots.keys.none { it in 0 until containerSlotCount }) return false
        this.connectionId = connectionId
        this.menuId = menuId
        this.baselineStateId = baselineStateId
        this.menuMapEpoch = menuMapEpoch
        this.sourceActiveSlot = sourceActiveSlot
        this.sourceValue = source
        this.expectedItemId = source.itemId
        this.expectedCount = source.count
        this.containerSlotCount = containerSlotCount
        this.baselineSlots = baselineSlots.toMap()
        status = TunnelSellTransferStatus.PENDING
        return true
    }

    fun observe(update: InventorySyncFrame, currentMenuMapEpoch: Long): TunnelSellTransferStatus {
        if (status != TunnelSellTransferStatus.PENDING || update.connectionId != connectionId || update.menuId != menuId ||
            currentMenuMapEpoch != menuMapEpoch || !stateIsAfter(update.stateId, baselineStateId)) return status
        if (update.cursor?.count?.let { it > 0 } == true) {
            status = TunnelSellTransferStatus.REJECTED
            return status
        }
        val incoming = if (update.fullContents) baselineSlots.keys.associateWith { index ->
            update.slots[index] ?: InventorySlotValue("", 0)
        } else update.slots.filterKeys { it in baselineSlots }
        for ((index, value) in incoming) {
            val priorRevision = revisions[index]
            val priorValue = observedSlots[index]
            when {
                priorRevision == null || stateIsAfter(update.stateId, priorRevision) -> {
                    revisions[index] = update.stateId
                    observedSlots[index] = value
                }
                update.stateId == priorRevision && priorValue != value -> {
                    status = TunnelSellTransferStatus.REJECTED
                    return status
                }
            }
        }
        val sourceRevision = revisions[sourceActiveSlot]
        val sourceNow = observedSlots[sourceActiveSlot]
        val destinationChanges = revisions.keys.any { index ->
            index in 0 until containerSlotCount &&
                observedSlots[index] != baselineSlots[index] && stateIsAfter(revisions.getValue(index), baselineStateId)
        }
        if (sourceRevision == null || sourceNow == null || !stateIsAfter(sourceRevision, baselineStateId) || !destinationChanges) return status
        val projected = baselineSlots.toMutableMap().apply { putAll(observedSlots) }
        val containerIncrease = (0 until containerSlotCount).sumOf { index ->
            val now = projected[index] ?: InventorySlotValue("", 0)
            val before = baselineSlots[index] ?: InventorySlotValue("", 0)
            (if (now.itemId == expectedItemId) now.count else 0) - (if (before.itemId == expectedItemId) before.count else 0)
        }
        val removed = (sourceValue!!.count - sourceNow.count).coerceAtLeast(0)
        if (sourceNow.count == 0 && containerIncrease == expectedCount && removed == expectedCount) {
            status = TunnelSellTransferStatus.ACKNOWLEDGED
        }
        return status
    }

    fun status(): TunnelSellTransferStatus = status

    fun reconcileLocal(localSlots: Map<Int, InventorySlotValue>, cursorEmpty: Boolean): TunnelSellTransferStatus {
        if (status != TunnelSellTransferStatus.ACKNOWLEDGED) return status
        if (!cursorEmpty || observedSlots.any { (index, value) -> localSlots[index] != value }) {
            status = TunnelSellTransferStatus.REJECTED
        }
        return status
    }

    fun reset() {
        connectionId = 0L
        menuId = -1
        baselineStateId = -1
        menuMapEpoch = Long.MIN_VALUE
        sourceActiveSlot = -1
        sourceValue = null
        expectedItemId = ""
        expectedCount = 0
        containerSlotCount = 0
        baselineSlots = emptyMap()
        observedSlots.clear()
        revisions.clear()
        status = TunnelSellTransferStatus.IDLE
    }

    private fun stateIsAfter(candidate: Int, baseline: Int): Boolean {
        val delta = ((candidate and STATE_MASK) - (baseline and STATE_MASK)) and STATE_MASK
        return delta in 1 until STATE_HALF_RANGE
    }

    private companion object {
        const val STATE_MASK = 0x7FFF
        const val STATE_HALF_RANGE = 0x4000
    }
}

