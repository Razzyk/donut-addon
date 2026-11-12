package dev.donut.addon.expansion.automation.core

import java.util.Locale

/** A deliberately small host contract for selling an exact, caller-approved set of stacks. */
enum class DonutSellStatus {
    IDLE, WAITING_MENU, OFFERING, WAITING_OFFER_ACK, READY_TO_CONFIRM, WAITING_SALE_ACK,
    COMPLETE, PARTIAL, FAILED, UNCERTAIN,
}

data class DonutSellCandidate(
    /** Player inventory container slot (0..35), not an absolute active-menu slot. */
    val inventorySlot: Int,
    val stack: InventorySlotValue,
)

enum class DonutSellGridIdentityMode { FULL_STACK, ITEM_TYPE_ONLY }

data class DonutSellRequest @JvmOverloads constructor(
    /** Stable identity of the owning user/build run; a completed sale may start another cycle. */
    val runId: Long,
    val session: SessionStamp,
    /** Only candidates explicitly approved by the owning feature may be included. */
    val candidates: List<DonutSellCandidate>,
    /** Indices of the sell menu's item staging slots, excluding buttons and decoration. */
    val sellItemSlots: Set<Int>,
    /** Source SellRoutine uses slot 53 in its sell menu. Hosts must validate this control. */
    val confirmationSlot: Int,
    /** Normalization applies only to server sell-grid count evidence, never source inventory ownership. */
    val gridIdentityMode: DonutSellGridIdentityMode = DonutSellGridIdentityMode.FULL_STACK,
)

data class DonutSellFrame @JvmOverloads constructor(
    val session: SessionStamp,
    val nowNanos: Long,
    val canIssueCommand: Boolean,
    val menu: MenuSnapshot?,
    val menuMapEpoch: Long,
    /** True only after MenuOpenAckTracker observed a full server content sync for this menu. */
    val menuOpenAcknowledged: Boolean,
    /** Maps player inventory container slots (0..35) to active-menu absolute slot indices. */
    val playerInventoryMenuSlots: Map<Int, Int>,
    /** The host's source-verified set of item staging slots for the active sell menu. */
    val sellItemSlots: Set<Int>,
    /** The host's source-verified sell confirmation control for the active sell menu. */
    val confirmationSlot: Int?,
    /** Host proof that this acknowledged menu belongs to its /sell command and matches the source's container shape. */
    val sellMenuHostVerified: Boolean = false,
)

sealed interface DonutSellEffect {
    val id: Long
    val session: SessionStamp

    data class OpenSell(override val id: Long, override val session: SessionStamp) : DonutSellEffect

    data class OfferStack(
        override val id: Long,
        override val session: SessionStamp,
        val menuId: Int,
        val screenIdentity: Long,
        val menuMapEpoch: Long,
        val baselineStateId: Int,
        val inventorySlot: Int,
        val menuSlot: Int,
        val expectedStack: InventorySlotValue,
        val clickType: DonutSellClickType = DonutSellClickType.QUICK_MOVE,
    ) : DonutSellEffect

    data class ConfirmSale(
        override val id: Long,
        override val session: SessionStamp,
        val menuId: Int,
        val screenIdentity: Long,
        val menuMapEpoch: Long,
        val baselineStateId: Int,
        val slot: Int,
        val expectedButton: InventorySlotValue,
        val clickNumber: Int,
        val clickType: DonutSellClickType = DonutSellClickType.PICKUP,
    ) : DonutSellEffect
}

enum class DonutSellClickType { QUICK_MOVE, PICKUP }

/**
 * A host creates this only after the same-menu server slot updates have been observed through its
 * InventoryAckChannel and the active native menu has reconciled to those values. The evidence
 * includes both sides of the quick-move so the core can verify that exactly the approved item
 * quantity entered the sell area.
 */
data class DonutSellOfferAck(
    val effectId: Long,
    val session: SessionStamp,
    val menuId: Int,
    val screenIdentity: Long,
    val menuMapEpoch: Long,
    val serverStateId: Int,
    val observedAtNanos: Long,
    val inventorySlot: Int,
    val sourceBefore: InventorySlotValue,
    val sourceAfter: InventorySlotValue,
    val sellItemsBefore: Map<Int, InventorySlotValue>,
    val sellItemsAfter: Map<Int, InventorySlotValue>,
    val cursorEmpty: Boolean,
    val localReconciled: Boolean,
)

/** Same-menu server slot evidence captured after a sell confirmation click. */
data class DonutSellResultAck(
    val effectId: Long,
    val session: SessionStamp,
    val menuId: Int,
    val screenIdentity: Long,
    val menuMapEpoch: Long,
    val serverStateId: Int,
    val observedAtNanos: Long,
    val sellItemsBefore: Map<Int, InventorySlotValue>,
    val sellItemsAfter: Map<Int, InventorySlotValue>,
    val cursorEmpty: Boolean,
    val localReconciled: Boolean,
)

/**
 * Host evidence that a PARTIAL sale's remaining stacks were safely returned to player inventory.
 * Inventory maps use canonical player-inventory container indices (0..35), never active-menu
 * absolute slot indices. Packet ordinals come from InventoryAckChannel's per-session incoming-
 * packet counter; they are not Minecraft state IDs and must not be
 * compared with the sell menu's state counter. The player-inventory map epoch describes the
 * canonical player-container mapping and therefore remains stable across closing the sell screen.
 */
data class DonutSellPartialReturnAck(
    val runId: Long,
    val session: SessionStamp,
    val sellMenuId: Int,
    val sellScreenIdentity: Long,
    val menuMapEpoch: Long,
    val observedAtNanos: Long,
    val inventoryBeforeCapturedAtNanos: Long,
    val menuClosedAtNanos: Long,
    val inventoryAfterCapturedAtNanos: Long,
    val inventoryBeforePacketOrdinal: Long,
    val menuClosedPacketOrdinal: Long,
    val inventoryAfterPacketOrdinal: Long,
    val inventoryMapEpochBeforeClose: Long,
    val inventoryMapEpochAfterClose: Long,
    val inventoryBeforeMenuId: Int,
    val inventoryAfterMenuId: Int,
    val inventoryBefore: Map<Int, InventorySlotValue>,
    val inventoryAfter: Map<Int, InventorySlotValue>,
    val menuClosed: Boolean,
    val cursorEmpty: Boolean,
    val localReconciled: Boolean,
)

data class DonutSellTotals(
    /** Count confirmed removed from the sell menu, credited back to full approved source identity. */
    val sold: Map<String, Int>,
    /** Count still visible in the sell menu when the bounded confirmation attempts ended. */
    val remaining: Map<String, Int>,
)

object DonutSellIdentity {
    /** Stable full-stack key; each arbitrary text field is length-prefixed to prevent collisions. */
    @JvmStatic
    fun of(stack: InventorySlotValue): String = buildString {
        fun field(value: String) { append(value.length).append(':').append(value) }
        field(stack.itemId)
        field(stack.name)
        append(stack.lore.size).append(':')
        stack.lore.forEach(::field)
        field(stack.componentFingerprint)
        val enchantments = stack.actualEnchantments.toSortedMap()
        append(enchantments.size).append(':')
        enchantments.forEach { (id, level) -> field(id); field(level.toString()) }
    }

    /** Source SellRoutine aggregates the sell grid by Item; this is explicit and lossy by design. */
    @JvmStatic
    fun itemTypeOf(stack: InventorySlotValue): String = "item:${stack.itemId.length}:${stack.itemId}"
}

/**
 * Pure, compositional sale state machine. It never reads Minecraft state and never dispatches.
 * The owning host supplies exact candidates, validates menu ownership/layout, dispatches one
 * effect through its ActionGate, then reports server-acknowledged slot evidence.
 */
class DonutSellWorkflow {
    private var request: DonutSellRequest? = null
    private var status = DonutSellStatus.IDLE
    private var nextEffectId = 1L
    private var pendingEffect: DonutSellEffect? = null
    private var pendingDeadline = 0L
    private var dispatchedAt = 0L
    private var nextActionAt = 0L
    private var activeMenuId = -1
    private var activeScreenIdentity = -1L
    private var activeMapEpoch = -1L
    private var activeStateId = -1
    private var itemSlots: Set<Int> = emptySet()
    private var confirmationSlot = -1
    private var offeredItems: Map<Int, InventorySlotValue> = emptyMap()
    private var offeredCounts = mutableMapOf<String, Int>()
    private var soldCounts = mutableMapOf<String, Int>()
    private var gridToSourceIdentity = mutableMapOf<String, String>()
    private var partialReturnVerified = false
    private var partialAtNanos = 0L
    private var candidateCursor = 0
    private var confirmations = 0

    /**
     * Start a sale cycle. Keep runId stable for one actual builder run; another same-run cycle is
     * allowed only after COMPLETE, while PARTIAL/UNCERTAIN require host reconciliation or a new run.
     */
    fun start(next: DonutSellRequest, nowNanos: Long): Boolean {
        if (next.runId <= 0L || next.session.connection == 0L || next.candidates.isEmpty() ||
            next.candidates.size > MAX_CANDIDATES || next.sellItemSlots.isEmpty() ||
            next.confirmationSlot < 0 || next.candidates.any { it.inventorySlot !in 0..35 || it.stack.count <= 0 || it.stack.itemId.isBlank() } ||
            next.candidates.map { it.inventorySlot }.toSet().size != next.candidates.size ||
            next.sellItemSlots.any { it < 0 } || next.confirmationSlot in next.sellItemSlots) return false
        if (next.gridIdentityMode == DonutSellGridIdentityMode.ITEM_TYPE_ONLY &&
            next.candidates.groupBy { it.stack.itemId }.values.any { candidates ->
                candidates.map { DonutSellIdentity.of(it.stack) }.distinct().size > 1
            }) return false
        val previous = request
        if (previous != null && status !in TERMINAL) return false
        if (previous?.runId == next.runId && status == DonutSellStatus.UNCERTAIN) return false
        if (previous?.runId == next.runId && status == DonutSellStatus.PARTIAL && !partialReturnVerified) return false
        clearState()
        request = next.copy(candidates = next.candidates.toList(), sellItemSlots = next.sellItemSlots.toSet())
        for (candidate in request!!.candidates) {
            gridToSourceIdentity[gridIdentity(candidate.stack)] = DonutSellIdentity.of(candidate.stack)
        }
        status = DonutSellStatus.IDLE
        nextActionAt = nowNanos
        return true
    }

    /** Returns at most one guarded effect. A host must dispatch it and immediately report dispatch. */
    fun tick(frame: DonutSellFrame): DonutSellEffect? {
        val current = request ?: return null
        if (status in TERMINAL) return null
        if (frame.session != current.session) {
            status = if (status == DonutSellStatus.WAITING_SALE_ACK || status == DonutSellStatus.WAITING_OFFER_ACK ||
                offeredCounts.isNotEmpty())
                DonutSellStatus.UNCERTAIN else DonutSellStatus.FAILED
            pendingEffect = null
            return null
        }
        if (pendingEffect != null) {
            when (val pending = pendingEffect) {
                is DonutSellEffect.OpenSell -> {
                    if (frame.nowNanos > pendingDeadline) {
                        pendingEffect = null
                        status = DonutSellStatus.FAILED
                        return null
                    }
                    if (status == DonutSellStatus.WAITING_MENU && frame.session == pending.session &&
                        frame.menu != null && frame.menuOpenAcknowledged) pendingEffect = null
                    else return null
                }
                is DonutSellEffect.OfferStack, is DonutSellEffect.ConfirmSale -> {
                    if (frame.nowNanos > pendingDeadline) status = DonutSellStatus.UNCERTAIN
                    val expectedMap = when (pending) {
                        is DonutSellEffect.OfferStack -> pending.menuMapEpoch
                        is DonutSellEffect.ConfirmSale -> pending.menuMapEpoch
                    }
                    if (frame.menu == null || frame.menuMapEpoch != expectedMap || frame.menu.menuId != activeMenuId ||
                        frame.menu.screenIdentity != activeScreenIdentity) status = DonutSellStatus.UNCERTAIN
                    if (status == DonutSellStatus.UNCERTAIN) pendingEffect = null
                    return null
                }
                null -> Unit
            }
        }
        when (status) {
            DonutSellStatus.IDLE -> {
                if (frame.menu != null || !frame.canIssueCommand || frame.nowNanos < nextActionAt) return null
                return DonutSellEffect.OpenSell(nextEffectId++, current.session).also { pendingEffect = it }
            }
            DonutSellStatus.WAITING_MENU -> {
                if (frame.menu == null) {
                    if (frame.nowNanos > pendingDeadline) status = DonutSellStatus.FAILED
                    return null
                }
                val menu = frame.menu
                if (MenuClassifier.classify(menu) != MenuKind.SELL && !frame.sellMenuHostVerified) {
                    status = DonutSellStatus.FAILED
                    return null
                }
                if (!frame.menuOpenAcknowledged) {
                    if (frame.nowNanos > pendingDeadline) status = DonutSellStatus.FAILED
                    return null
                }
                if (!menu.cursorEmpty || menu.stateId < 0 || frame.menuMapEpoch <= 0L ||
                    frame.playerInventoryMenuSlots.keys.intersect(0..35).size != 36 ||
                    frame.playerInventoryMenuSlots.values.toSet().size != 36 ||
                    frame.sellItemSlots != current.sellItemSlots || frame.confirmationSlot != current.confirmationSlot ||
                    frame.confirmationSlot !in menu.slots.map { it.index } ||
                    frame.sellItemSlots.any { it !in menu.slots.map { slot -> slot.index } } ||
                    frame.confirmationSlot in frame.playerInventoryMenuSlots.values) {
                    status = DonutSellStatus.FAILED
                    return null
                }
                val staging = slotsAsValues(menu, frame.sellItemSlots) ?: run { status = DonutSellStatus.FAILED; return null }
                if (staging.values.any { it.count > 0 }) { status = DonutSellStatus.FAILED; return null }
                val confirm = value(menu.slot(current.confirmationSlot)) ?: run { status = DonutSellStatus.FAILED; return null }
                if (confirm.count <= 0 || !isSafeConfirmation(menu.slot(current.confirmationSlot), current.confirmationSlot)) {
                    status = DonutSellStatus.FAILED
                    return null
                }
                activeMenuId = menu.menuId
                activeScreenIdentity = menu.screenIdentity
                activeMapEpoch = frame.menuMapEpoch
                activeStateId = menu.stateId
                itemSlots = current.sellItemSlots
                confirmationSlot = current.confirmationSlot
                offeredItems = staging
                status = DonutSellStatus.OFFERING
                return null
            }
            DonutSellStatus.OFFERING -> {
                val menu = validActiveMenu(frame) ?: return null
                if (candidateCursor >= current.candidates.size) {
                    if (offeredCounts.isEmpty()) status = DonutSellStatus.COMPLETE
                    else status = DonutSellStatus.READY_TO_CONFIRM
                    return null
                }
                val candidate = current.candidates[candidateCursor]
                val slotIndex = frame.playerInventoryMenuSlots[candidate.inventorySlot]
                    ?: run { failBeforeSale(); return null }
                val live = value(menu.slot(slotIndex))
                    ?: run { failBeforeSale(); return null }
                if (live != candidate.stack) { failBeforeSale(); return null }
                return DonutSellEffect.OfferStack(
                    nextEffectId++, current.session, menu.menuId, menu.screenIdentity, frame.menuMapEpoch,
                    menu.stateId, candidate.inventorySlot, slotIndex, candidate.stack,
                ).also { pendingEffect = it }
            }
            DonutSellStatus.READY_TO_CONFIRM -> {
                val menu = validActiveMenu(frame) ?: return null
                if (frame.nowNanos < nextActionAt) return null
                val currentContents = slotsAsValues(menu, itemSlots) ?: run { status = DonutSellStatus.UNCERTAIN; return null }
                if (currentContents != offeredItems || currentContents.values.none { it.count > 0 }) {
                    status = DonutSellStatus.UNCERTAIN
                    return null
                }
                val button = value(menu.slot(confirmationSlot))
                    ?: run { status = DonutSellStatus.UNCERTAIN; return null }
                if (button.count <= 0 || !isSafeConfirmation(menu.slot(confirmationSlot), confirmationSlot)) {
                    status = DonutSellStatus.UNCERTAIN
                    return null
                }
                return DonutSellEffect.ConfirmSale(
                    nextEffectId++, current.session, menu.menuId, menu.screenIdentity, frame.menuMapEpoch,
                    menu.stateId, confirmationSlot, button, confirmations + 1,
                ).also { pendingEffect = it }
            }
            DonutSellStatus.WAITING_OFFER_ACK, DonutSellStatus.WAITING_SALE_ACK -> return null
            else -> return null
        }
    }

    /** A rejected dispatch is guaranteed by the host to have sent no command/click. */
    fun reportDispatch(effectId: Long, accepted: Boolean, nowNanos: Long): DonutSellStatus {
        val effect = pendingEffect ?: return status
        if (effect.id != effectId) return status
        pendingEffect = null
        if (!accepted) {
            status = when (effect) {
                is DonutSellEffect.OpenSell -> DonutSellStatus.IDLE
                is DonutSellEffect.OfferStack -> DonutSellStatus.OFFERING
                is DonutSellEffect.ConfirmSale -> DonutSellStatus.READY_TO_CONFIRM
            }
            nextActionAt = nowNanos + DISPATCH_BACKOFF_NANOS
            return status
        }
        when (effect) {
            is DonutSellEffect.OpenSell -> {
                status = DonutSellStatus.WAITING_MENU
                pendingEffect = effect
                dispatchedAt = nowNanos
                pendingDeadline = nowNanos + MENU_TIMEOUT_NANOS
            }
            is DonutSellEffect.OfferStack -> {
                status = DonutSellStatus.WAITING_OFFER_ACK
                pendingEffect = effect
                dispatchedAt = nowNanos
                pendingDeadline = nowNanos + ACTION_ACK_TIMEOUT_NANOS
            }
            is DonutSellEffect.ConfirmSale -> {
                status = DonutSellStatus.WAITING_SALE_ACK
                pendingEffect = effect
                dispatchedAt = nowNanos
                pendingDeadline = nowNanos + ACTION_ACK_TIMEOUT_NANOS
            }
        }
        return status
    }

    /**
     * A host may report a server-confirmed rate-limit rejection for an already-dispatched /sell
     * command. This is narrower than a dispatch failure: it cannot rewind offer or sale effects,
     * and the host retains ownership of its overall lifecycle and retry-count policy.
     */
    fun rejectOpenCommand(effectId: Long, observedAtNanos: Long, retryAtNanos: Long): DonutSellStatus {
        val effect = pendingEffect as? DonutSellEffect.OpenSell ?: return status
        val current = request ?: return status
        if (status != DonutSellStatus.WAITING_MENU || effect.id != effectId || effect.session != current.session ||
            observedAtNanos < dispatchedAt || observedAtNanos > pendingDeadline || retryAtNanos < observedAtNanos) return status
        pendingEffect = null
        pendingDeadline = 0L
        status = DonutSellStatus.IDLE
        nextActionAt = retryAtNanos
        return status
    }

    fun acknowledgeOffer(ack: DonutSellOfferAck): DonutSellStatus {
        val effect = pendingEffect as? DonutSellEffect.OfferStack ?: return status
        val current = request ?: return status
        if (status != DonutSellStatus.WAITING_OFFER_ACK || ack.effectId != effect.id || ack.session != current.session ||
            ack.menuId != activeMenuId || ack.screenIdentity != activeScreenIdentity || ack.menuMapEpoch != activeMapEpoch ||
            ack.inventorySlot != effect.inventorySlot || !ack.cursorEmpty || !ack.localReconciled ||
            ack.observedAtNanos < dispatchedAt || ack.observedAtNanos > pendingDeadline ||
            !stateIsAfter(ack.serverStateId, effect.baselineStateId) || ack.sourceBefore != effect.expectedStack ||
            !sameStackIdentity(ack.sourceBefore, ack.sourceAfter) || ack.sourceAfter.count >= ack.sourceBefore.count ||
            !sameSlotKeys(ack.sellItemsBefore, offeredItems) || !sameSlotKeys(ack.sellItemsAfter, offeredItems) ||
            ack.sellItemsBefore != offeredItems) {
            status = DonutSellStatus.UNCERTAIN
            pendingEffect = null
            return status
        }
        val moved = ack.sourceBefore.count - ack.sourceAfter.count
        if (!exactContainerAddition(ack.sellItemsBefore, ack.sellItemsAfter, ack.sourceBefore, moved)) {
            status = DonutSellStatus.UNCERTAIN
            pendingEffect = null
            return status
        }
        offeredItems = immutableSnapshot(ack.sellItemsAfter)
        val identity = DonutSellIdentity.of(ack.sourceBefore)
        offeredCounts[identity] = (offeredCounts[identity] ?: 0) + moved
        candidateCursor++
        activeStateId = ack.serverStateId
        pendingEffect = null
        status = DonutSellStatus.OFFERING
        return status
    }

    fun acknowledgeSale(ack: DonutSellResultAck, nowNanos: Long): DonutSellStatus {
        val effect = pendingEffect as? DonutSellEffect.ConfirmSale ?: return status
        val current = request ?: return status
        if (status != DonutSellStatus.WAITING_SALE_ACK || ack.effectId != effect.id || ack.session != current.session ||
            ack.menuId != activeMenuId || ack.screenIdentity != activeScreenIdentity || ack.menuMapEpoch != activeMapEpoch ||
            ack.observedAtNanos < dispatchedAt || ack.observedAtNanos > pendingDeadline ||
            !ack.cursorEmpty || !ack.localReconciled || !stateIsAfter(ack.serverStateId, effect.baselineStateId) ||
            ack.sellItemsBefore != offeredItems || !sameSlotKeys(ack.sellItemsAfter, offeredItems) ||
            !onlyRemoval(ack.sellItemsBefore, ack.sellItemsAfter)) {
            status = DonutSellStatus.UNCERTAIN
            pendingEffect = null
            return status
        }
        val removed = quantityDifference(ack.sellItemsBefore, ack.sellItemsAfter)
        val credits = mutableMapOf<String, Int>()
        for ((gridIdentity, count) in removed) {
            val sourceIdentity = gridToSourceIdentity[gridIdentity]
            if (sourceIdentity == null || count <= 0 ||
                (soldCounts[sourceIdentity] ?: 0) + count > (offeredCounts[sourceIdentity] ?: 0)) {
                status = DonutSellStatus.UNCERTAIN
                pendingEffect = null
                return status
            }
            credits[sourceIdentity] = (credits[sourceIdentity] ?: 0) + count
        }
        for ((identity, count) in credits) soldCounts[identity] = (soldCounts[identity] ?: 0) + count
        offeredItems = immutableSnapshot(ack.sellItemsAfter)
        confirmations++
        activeStateId = ack.serverStateId
        pendingEffect = null
        val remaining = totals( offeredItems )
        if (remaining.values.sum() == 0) {
            status = DonutSellStatus.COMPLETE
        } else if (confirmations >= MAX_CONFIRMATIONS) {
            status = DonutSellStatus.PARTIAL
            partialAtNanos = nowNanos
        } else {
            status = DonutSellStatus.READY_TO_CONFIRM
            nextActionAt = nowNanos + if (confirmations == 1) FIRST_CONFIRM_DELAY_NANOS else LATER_CONFIRM_DELAY_NANOS
        }
        return status
    }

    fun acknowledgePartialReturn(ack: DonutSellPartialReturnAck): DonutSellStatus {
        val current = request ?: return status
        if (status != DonutSellStatus.PARTIAL || ack.runId != current.runId || ack.session != current.session ||
            ack.sellMenuId != activeMenuId || ack.sellScreenIdentity != activeScreenIdentity ||
            ack.menuMapEpoch != activeMapEpoch || ack.observedAtNanos < partialAtNanos ||
            ack.inventoryBeforeCapturedAtNanos < partialAtNanos ||
            ack.menuClosedAtNanos < ack.inventoryBeforeCapturedAtNanos ||
            ack.inventoryAfterCapturedAtNanos <= ack.menuClosedAtNanos ||
            ack.observedAtNanos < ack.inventoryAfterCapturedAtNanos ||
            ack.inventoryBeforePacketOrdinal <= 0L ||
            ack.menuClosedPacketOrdinal < ack.inventoryBeforePacketOrdinal ||
            ack.inventoryAfterPacketOrdinal <= ack.menuClosedPacketOrdinal ||
            ack.inventoryMapEpochBeforeClose <= 0L ||
            ack.inventoryMapEpochBeforeClose != ack.inventoryMapEpochAfterClose ||
            ack.inventoryBeforeMenuId != activeMenuId || ack.inventoryAfterMenuId != 0 ||
            !ack.menuClosed || !ack.cursorEmpty || !ack.localReconciled ||
            ack.inventoryBefore.keys != (0..35).toSet() || ack.inventoryAfter.keys != (0..35).toSet()) return status
        val returned = positiveDifferencesByIdentity(ack.inventoryBefore, ack.inventoryAfter) ?: return status
        if (returned != totals().remaining) return status
        partialReturnVerified = true
        return status
    }

    fun status(): DonutSellStatus = status
    fun totals(): DonutSellTotals {
        val remaining = mutableMapOf<String, Int>()
        for ((gridIdentity, count) in totals(offeredItems)) {
            val sourceIdentity = gridToSourceIdentity[gridIdentity] ?: continue
            remaining[sourceIdentity] = (remaining[sourceIdentity] ?: 0) + count
        }
        return DonutSellTotals(soldCounts.toMap(), remaining.toMap())
    }
    fun pendingEffect(): DonutSellEffect? = pendingEffect

    private fun validActiveMenu(frame: DonutSellFrame): MenuSnapshot? {
        val menu = frame.menu
        if (menu == null || frame.session != request?.session || menu.menuId != activeMenuId ||
            menu.screenIdentity != activeScreenIdentity || frame.menuMapEpoch != activeMapEpoch ||
            (MenuClassifier.classify(menu) != MenuKind.SELL && !frame.sellMenuHostVerified) || !menu.cursorEmpty || menu.stateId < 0 ||
            !stateIsAfterOrEqual(menu.stateId, activeStateId)) {
            status = if (status == DonutSellStatus.WAITING_SALE_ACK || offeredCounts.isNotEmpty())
                DonutSellStatus.UNCERTAIN else DonutSellStatus.FAILED
            return null
        }
        return menu
    }

    private fun failBeforeSale() {
        status = if (offeredCounts.isNotEmpty()) DonutSellStatus.UNCERTAIN else DonutSellStatus.FAILED
    }

    private fun clearState() {
        request = null
        status = DonutSellStatus.IDLE
        pendingEffect = null
        pendingDeadline = 0L
        dispatchedAt = 0L
        nextActionAt = 0L
        activeMenuId = -1
        activeScreenIdentity = -1L
        activeMapEpoch = -1L
        activeStateId = -1
        itemSlots = emptySet()
        confirmationSlot = -1
        offeredItems = emptyMap()
        offeredCounts.clear()
        soldCounts.clear()
        gridToSourceIdentity.clear()
        partialReturnVerified = false
        partialAtNanos = 0L
        candidateCursor = 0
        confirmations = 0
    }

    private fun isSafeConfirmation(slot: SlotSnapshot?, index: Int): Boolean {
        val text = slot?.searchableText?.lowercase(Locale.ROOT).orEmpty()
        if (text.contains("cancel") || text.contains("history")) return false
        return index == 53 || text == "sell" || text == "sell all" || text == "confirm" || text == "confirm sale" || text == "yes"
    }

    private fun value(slot: SlotSnapshot?): InventorySlotValue? = slot?.let {
        InventorySlotValue(it.itemId, it.count, it.name, it.lore, it.componentFingerprint, it.actualEnchantments, it.procurementFingerprint)
    }

    private fun slotsAsValues(menu: MenuSnapshot, indexes: Set<Int>): Map<Int, InventorySlotValue>? {
        val result = linkedMapOf<Int, InventorySlotValue>()
        for (index in indexes) result[index] = value(menu.slot(index)) ?: return null
        return immutableSnapshot(result)
    }

    private fun exactContainerAddition(
        before: Map<Int, InventorySlotValue>,
        after: Map<Int, InventorySlotValue>,
        source: InventorySlotValue,
        expectedDelta: Int,
    ): Boolean {
        if (expectedDelta <= 0) return false
        val oldTotals = totals(before)
        val newTotals = totals(after)
        val identities = oldTotals.keys + newTotals.keys
        var sourceDelta = 0
        for (identity in identities) {
            val delta = (newTotals[identity] ?: 0) - (oldTotals[identity] ?: 0)
            if (identity == gridIdentity(source)) sourceDelta = delta
            else if (delta != 0) return false
        }
        return sourceDelta == expectedDelta
    }

    private fun onlyRemoval(before: Map<Int, InventorySlotValue>, after: Map<Int, InventorySlotValue>): Boolean {
        val oldTotals = totals(before)
        val newTotals = totals(after)
        return (oldTotals.keys + newTotals.keys).all { key ->
            (newTotals[key] ?: 0) <= (oldTotals[key] ?: 0)
        }
    }

    private fun quantityDifference(before: Map<Int, InventorySlotValue>, after: Map<Int, InventorySlotValue>): Map<String, Int> {
        val oldTotals = totals(before)
        val newTotals = totals(after)
        return (oldTotals.keys + newTotals.keys).mapNotNull { key ->
            val count = (oldTotals[key] ?: 0) - (newTotals[key] ?: 0)
            if (count > 0) key to count else null
        }.toMap()
    }

    private fun positiveDifferencesByIdentity(
        before: Map<Int, InventorySlotValue>,
        after: Map<Int, InventorySlotValue>,
    ): Map<String, Int>? {
        val oldTotals = sourceTotals(before)
        val newTotals = sourceTotals(after)
        val result = mutableMapOf<String, Int>()
        for (identity in oldTotals.keys + newTotals.keys) {
            val delta = (newTotals[identity] ?: 0) - (oldTotals[identity] ?: 0)
            if (delta < 0) return null
            if (delta > 0) result[identity] = delta
        }
        return result
    }

    private fun totals(stacks: Map<Int, InventorySlotValue>): Map<String, Int> = buildMap {
        stacks.values.filter { it.count > 0 }.forEach { stack ->
            val key = gridIdentity(stack)
            put(key, (get(key) ?: 0) + stack.count)
        }
    }

    private fun sourceTotals(stacks: Map<Int, InventorySlotValue>): Map<String, Int> = buildMap {
        stacks.values.filter { it.count > 0 }.forEach { stack ->
            val key = DonutSellIdentity.of(stack)
            put(key, (get(key) ?: 0) + stack.count)
        }
    }

    private fun gridIdentity(stack: InventorySlotValue): String = when (request?.gridIdentityMode) {
        DonutSellGridIdentityMode.ITEM_TYPE_ONLY -> DonutSellIdentity.itemTypeOf(stack)
        else -> DonutSellIdentity.of(stack)
    }

    private fun sameStackIdentity(first: InventorySlotValue, second: InventorySlotValue): Boolean =
        first.count >= second.count && (second.count == 0 || stackIdentity(first) == stackIdentity(second))

    private fun stackIdentity(stack: InventorySlotValue): String = DonutSellIdentity.of(stack)

    private fun sameSlotKeys(first: Map<Int, InventorySlotValue>, second: Map<Int, InventorySlotValue>): Boolean =
        first.keys == second.keys

    private fun immutableSnapshot(source: Map<Int, InventorySlotValue>): Map<Int, InventorySlotValue> =
        java.util.Collections.unmodifiableMap(source.toSortedMap())

    private fun stateIsAfter(candidate: Int, baseline: Int): Boolean {
        val delta = ((candidate and STATE_MASK) - (baseline and STATE_MASK)) and STATE_MASK
        return delta in 1 until STATE_HALF_RANGE
    }

    private fun stateIsAfterOrEqual(candidate: Int, baseline: Int): Boolean =
        candidate == baseline || stateIsAfter(candidate, baseline)

    private companion object {
        const val MAX_CANDIDATES = 36
        const val MAX_CONFIRMATIONS = 3
        const val MENU_TIMEOUT_NANOS = 10_000_000_000L
        const val ACTION_ACK_TIMEOUT_NANOS = 6_000_000_000L
        const val DISPATCH_BACKOFF_NANOS = 50_000_000L
        const val FIRST_CONFIRM_DELAY_NANOS = 250_000_000L
        const val LATER_CONFIRM_DELAY_NANOS = 900_000_000L
        const val STATE_MASK = 0x7FFF
        const val STATE_HALF_RANGE = 0x4000
        val TERMINAL = setOf(DonutSellStatus.COMPLETE, DonutSellStatus.PARTIAL, DonutSellStatus.FAILED, DonutSellStatus.UNCERTAIN)
    }
}
