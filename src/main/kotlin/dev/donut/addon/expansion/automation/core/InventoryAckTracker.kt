package dev.donut.addon.expansion.automation.core

import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.world.item.ItemStack
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentLinkedQueue

data class InventorySlotValue @JvmOverloads constructor(
    val itemId: String,
    val count: Int,
    val name: String = "",
    val lore: List<String> = emptyList(),
    val componentFingerprint: String = "",
    val actualEnchantments: Map<String, Int> = emptyMap(),
    /** Procurement-only identity with server AH lore removed; full componentFingerprint remains authoritative elsewhere. */
    val procurementFingerprint: String = componentFingerprint,
) {
    /** Procurement normalization is supplemental and must not change transfer/lease equality. */
    override fun equals(other: Any?): Boolean = other is InventorySlotValue &&
        itemId == other.itemId && count == other.count && name == other.name && lore == other.lore &&
        componentFingerprint == other.componentFingerprint && actualEnchantments == other.actualEnchantments

    override fun hashCode(): Int {
        var result = itemId.hashCode()
        result = 31 * result + count
        result = 31 * result + name.hashCode()
        result = 31 * result + lore.hashCode()
        result = 31 * result + componentFingerprint.hashCode()
        result = 31 * result + actualEnchantments.hashCode()
        return result
    }

    val fingerprint: String get() = listOf(
        itemId, count.toString(), normalize(name), lore.joinToString("|") { normalize(it) }, componentFingerprint,
        actualEnchantments.toSortedMap().entries.joinToString("|") { (key, value) -> "$key=$value" },
    ).joinToString("#")

    companion object {
        /** Immutable stack evidence shared by automation and inventory-aware utility features. */
        @JvmStatic
        fun fromStack(stack: ItemStack): InventorySlotValue {
            if (stack.isEmpty) return InventorySlotValue("", 0)
            val lore = stack.get(DataComponents.LORE)?.lines()?.map { it.string }.orEmpty()
            val enchantments = sortedMapOf<String, Int>()
            for (component in listOf(DataComponents.ENCHANTMENTS, DataComponents.STORED_ENCHANTMENTS)) {
                val values = stack.get(component) ?: continue
                for (holder in values.keySet()) {
                    val id = holder.unwrapKey().orElse(null)?.identifier()?.toString()
                        ?.removePrefix("minecraft:") ?: continue
                    val level = values.getLevel(holder)
                    if (level > 0) enchantments[id] = maxOf(enchantments[id] ?: 0, level)
                }
            }
            val componentFingerprint = stack.getComponents().toString()
            val procurementStack = stack.copy()
            procurementStack.remove(DataComponents.LORE)
            return InventorySlotValue(
                BuiltInRegistries.ITEM.getKey(stack.item).toString(), stack.count,
                stack.hoverName.string, lore, componentFingerprint,
                Collections.unmodifiableMap(enchantments), procurementStack.getComponents().toString(),
            )
        }
    }
}

data class InventorySyncFrame(
    val connectionId: Long,
    val menuId: Int,
    val stateId: Int,
    val slots: Map<Int, InventorySlotValue>,
    val cursor: InventorySlotValue?,
    val fullContents: Boolean,
)

data class InventoryAckEnvelope @JvmOverloads constructor(
    val generation: Long,
    val connectionId: Long,
    val update: InventorySyncFrame,
    /** Monotonic incoming-packet ordinal within this active generation/connection. */
    val arrivalOrdinal: Long = 0L,
)

object InventoryAckEpoch {
    @JvmStatic
    fun accepts(envelope: InventoryAckEnvelope, activeGeneration: Long, activeConnectionId: Long): Boolean =
        envelope.generation == activeGeneration && envelope.connectionId == activeConnectionId &&
            envelope.update.connectionId == activeConnectionId
}

object InventorySyncFrameDecoder {
    @JvmStatic
    fun decode(packet: Packet<*>, connectionId: Long): InventorySyncFrame? = when (packet) {
        is ClientboundContainerSetSlotPacket -> {
            val slots = if (packet.slot >= 0) mapOf(packet.slot to InventorySlotValue.fromStack(packet.item)) else emptyMap()
            val cursor = if (packet.slot == -1) InventorySlotValue.fromStack(packet.item) else null
            InventorySyncFrame(connectionId, packet.containerId, packet.stateId, slots, cursor, false)
        }
        is ClientboundContainerSetContentPacket -> {
            val slots = packet.items().mapIndexed { index, stack -> index to InventorySlotValue.fromStack(stack) }.toMap()
            val carried = packet.carriedItem()
            val cursor = carried.takeUnless { it.isEmpty }?.let { InventorySlotValue.fromStack(it) }
            InventorySyncFrame(connectionId, packet.containerId(), packet.stateId(), slots, cursor, true)
        }
        else -> null
    }
}

/** Thread-safe packet intake that queues immutable snapshots for client-thread tracker updates. */
class InventoryAckChannel {
    private val queue = ConcurrentLinkedQueue<InventoryAckEnvelope>()
    private val sessionLock = Any()
    private class ActivePacketSession(val generation: Long, val connectionId: Long) {
        val arrivalOrdinal = AtomicLong(0L)
    }
    @Volatile private var activeSession = ActivePacketSession(Long.MIN_VALUE, 0L)

    fun setSession(generation: Long, connectionId: Long) {
        synchronized(sessionLock) {
            val current = activeSession
            if (generation != current.generation || connectionId != current.connectionId) {
                queue.clear()
                activeSession = ActivePacketSession(generation, connectionId)
            }
        }
    }

    fun onPacket(origin: TransferOrigin, original: Boolean, packet: Packet<*>) {
        if (origin != TransferOrigin.INCOMING || !original) return
        val session = activeSession
        if (session.connectionId == 0L || session.generation == Long.MIN_VALUE) return
        val ordinal = session.arrivalOrdinal.incrementAndGet()
        val update = InventorySyncFrameDecoder.decode(packet, session.connectionId) ?: return
        synchronized(sessionLock) {
            if (session !== activeSession) return
            if (queue.size >= 64) queue.poll()
            queue.offer(InventoryAckEnvelope(session.generation, session.connectionId, update, ordinal))
        }
    }

    fun drain(generation: Long, connectionId: Long, observe: (InventorySyncFrame) -> Unit) {
        drainStamped(generation, connectionId) { observe(it.update) }
    }

    /**
     * Drains accepted incoming frames together with their packet-arrival ordinal. The ordinal is
     * assigned in [onPacket], before decoding and queueing, so hosts can distinguish an actual
     * post-menu-close server update from a client tick or a drain-time timestamp.
     */
    fun drainStamped(generation: Long, connectionId: Long, observe: (InventoryAckEnvelope) -> Unit) {
        val session = activeSession
        while (true) {
            val envelope = queue.poll() ?: return
            if (InventoryAckEpoch.accepts(envelope, generation, connectionId) &&
                session === activeSession && envelope.generation == session.generation &&
                envelope.connectionId == session.connectionId) observe(envelope)
        }
    }

    /** Current accepted incoming packet ordinal, available only for the active session. */
    fun currentArrivalOrdinal(generation: Long, connectionId: Long): Long? {
        val session = activeSession
        return if (generation == session.generation && connectionId == session.connectionId && connectionId != 0L) {
            session.arrivalOrdinal.get()
        } else null
    }

    fun reset() {
        synchronized(sessionLock) {
            queue.clear()
            activeSession = ActivePacketSession(Long.MIN_VALUE, 0L)
        }
    }
}

/**
 * Captures the pre-click source and destination values for one vanilla SWAP action. After the server
 * acknowledges the swap, the source slot must contain `expectedDestination` and the destination
 * slot must contain `expectedSource`; the field names describe where each value came from before
 * the click, not where it is expected to land afterward.
 */
data class InventorySwapExpectation(
    val connectionId: Long,
    val menuId: Int,
    val baselineStateId: Int,
    val sourceMenuSlot: Int,
    val destinationMenuSlot: Int,
    /** Original stack at [sourceMenuSlot] before the click; it must land at [destinationMenuSlot]. */
    val expectedSource: InventorySlotValue,
    /** Original stack at [destinationMenuSlot] before the click; it must land at [sourceMenuSlot]. */
    val expectedDestination: InventorySlotValue,
)

enum class InventoryAckStatus { IDLE, PENDING, ACKNOWLEDGED, REJECTED, ABANDONED }

/** Requires post-action server content for both affected slots before stock is considered staged. */
class InventoryAckTracker {
    private var expectation: InventorySwapExpectation? = null
    private val observedStateBySlot = mutableMapOf<Int, Int>()
    private val observedBySlot = mutableMapOf<Int, InventorySlotValue>()
    private var status = InventoryAckStatus.IDLE

    fun begin(next: InventorySwapExpectation) {
        expectation = next
        observedStateBySlot.clear()
        observedBySlot.clear()
        status = InventoryAckStatus.PENDING
    }

    fun observe(update: InventorySyncFrame): InventoryAckStatus {
        val expected = expectation ?: return status
        if (status != InventoryAckStatus.PENDING) return status
        if (update.connectionId != expected.connectionId || update.menuId != expected.menuId ||
            !stateIsAfter(update.stateId, expected.baselineStateId)) return status

        if (update.cursor?.count?.let { it > 0 } == true) {
            status = InventoryAckStatus.REJECTED
            return status
        }

        if (update.fullContents) {
            val source = update.slots[expected.sourceMenuSlot]
            val destination = update.slots[expected.destinationMenuSlot]
            return finishIfComplete(source, destination, update.cursor == null || update.cursor.count == 0)
        }

        for ((slot, stack) in update.slots) {
            val previousState = observedStateBySlot[slot]
            val previousStack = observedBySlot[slot]
            when {
                previousState == null || stateIsAfter(update.stateId, previousState) -> {
                    observedStateBySlot[slot] = update.stateId
                    observedBySlot[slot] = stack
                }
                update.stateId == previousState && previousStack != stack -> {
                    status = InventoryAckStatus.REJECTED
                    return status
                }
            }
        }
        val source = observedBySlot[expected.sourceMenuSlot]
        val destination = observedBySlot[expected.destinationMenuSlot]
        if (source == null || destination == null) return status
        val sourceState = observedStateBySlot.getValue(expected.sourceMenuSlot)
        val destinationState = observedStateBySlot.getValue(expected.destinationMenuSlot)
        if (!stateIsAfter(sourceState, expected.baselineStateId) ||
            !stateIsAfter(destinationState, expected.baselineStateId)) return status
        val pairMatches = source == expected.expectedDestination && destination == expected.expectedSource
        if (pairMatches) return finishIfComplete(source, destination, update.cursor == null || update.cursor.count == 0)
        if (sourceState == destinationState) return finishIfComplete(source, destination, update.cursor == null || update.cursor.count == 0)
        // Separate revisions can arrive out of order; wait for both slots at a common revision or a full sync.
        return status
    }

    fun abandon(): InventoryAckStatus {
        if (status == InventoryAckStatus.PENDING) status = InventoryAckStatus.ABANDONED
        return status
    }

    /** Rejects a server-acknowledged swap if the client view was changed by a later manual action. */
    fun reconcileLocal(
        source: InventorySlotValue?,
        destination: InventorySlotValue?,
        cursorEmpty: Boolean,
    ): InventoryAckStatus {
        if (status != InventoryAckStatus.ACKNOWLEDGED) return status
        val expected = expectation ?: return status
        if (!cursorEmpty || source != expected.expectedDestination || destination != expected.expectedSource) {
            status = InventoryAckStatus.REJECTED
        }
        return status
    }

    fun status(): InventoryAckStatus = status

    fun expectation(): InventorySwapExpectation? = expectation

    fun reset() {
        expectation = null
        observedStateBySlot.clear()
        observedBySlot.clear()
        status = InventoryAckStatus.IDLE
    }

    private fun finishIfComplete(
        source: InventorySlotValue?,
        destination: InventorySlotValue?,
        cursorEmpty: Boolean,
    ): InventoryAckStatus {
        val expected = expectation ?: return status
        if (source == null || destination == null) return status
        if (source == expected.expectedDestination && destination == expected.expectedSource && cursorEmpty) {
            status = InventoryAckStatus.ACKNOWLEDGED
        } else if (source != expected.expectedDestination || destination != expected.expectedSource || !cursorEmpty) {
            status = InventoryAckStatus.REJECTED
        }
        return status
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

data class InventoryMutationExpectation(
    val connectionId: Long,
    val menuId: Int,
    val baselineStateId: Int,
    val expectedSlots: Map<Int, InventorySlotValue>,
    val expectedCursor: InventorySlotValue,
)

/** Ack tracker for one cursor pickup step. Every changed slot and the carried stack must be observed. */
class InventoryMutationAckTracker {
    private var expected: InventoryMutationExpectation? = null
    private val observedSlots = mutableMapOf<Int, InventorySlotValue>()
    private val slotRevisions = mutableMapOf<Int, Int>()
    private var observedCursor: InventorySlotValue? = null
    private var cursorRevision: Int? = null
    private var status = InventoryAckStatus.IDLE

    fun begin(next: InventoryMutationExpectation) {
        require(next.expectedSlots.isNotEmpty())
        expected = next
        observedSlots.clear()
        slotRevisions.clear()
        observedCursor = null
        cursorRevision = null
        status = InventoryAckStatus.PENDING
    }

    fun observe(update: InventorySyncFrame): InventoryAckStatus {
        val current = expected ?: return status
        val cursorOnlyUpdate = update.menuId == -1 && update.slots.isEmpty() && update.cursor != null && !update.fullContents
        if (status != InventoryAckStatus.PENDING || update.connectionId != current.connectionId ||
            (update.menuId != current.menuId && !cursorOnlyUpdate) || !after(update.stateId, current.baselineStateId)) return status

        if (update.fullContents) {
            val slots = current.expectedSlots.mapValues { (slot, _) -> update.slots[slot] }
            if (slots.values.any { it == null }) return status
            return finish(
                slots.mapValues { it.value!! },
                update.cursor ?: InventorySlotValue("", 0),
                mutableMapOf<Int, Int>().apply { current.expectedSlots.keys.forEach { put(it, update.stateId) } },
                update.stateId,
            )
        }

        for ((slot, stack) in update.slots) {
            if (slot !in current.expectedSlots) continue
            val previousRevision = slotRevisions[slot]
            val previousStack = observedSlots[slot]
            if (previousRevision == null || after(update.stateId, previousRevision)) {
                slotRevisions[slot] = update.stateId
                observedSlots[slot] = stack
            } else if (update.stateId == previousRevision && previousStack != stack) {
                status = InventoryAckStatus.REJECTED
                return status
            }
        }
        update.cursor?.let { stack ->
            val previousRevision = cursorRevision
            if (previousRevision == null || after(update.stateId, previousRevision)) {
                cursorRevision = update.stateId
                observedCursor = stack
            } else if (update.stateId == previousRevision && observedCursor != stack) {
                status = InventoryAckStatus.REJECTED
                return status
            }
        }
        if (observedSlots.keys != current.expectedSlots.keys || observedCursor == null) return status
        return finish(observedSlots.toMap(), observedCursor!!, slotRevisions.toMap(), cursorRevision!!)
    }

    fun reconcileLocal(slots: Map<Int, InventorySlotValue>, cursor: InventorySlotValue?): InventoryAckStatus {
        if (status != InventoryAckStatus.ACKNOWLEDGED) return status
        val current = expected ?: return status
        if (current.expectedSlots.any { (slot, value) -> slots[slot] != value } || cursor != current.expectedCursor) {
            status = InventoryAckStatus.REJECTED
        }
        return status
    }

    fun expectation(): InventoryMutationExpectation? = expected

    fun status(): InventoryAckStatus = status

    fun abandon(): InventoryAckStatus {
        if (status == InventoryAckStatus.PENDING) status = InventoryAckStatus.ABANDONED
        return status
    }
    fun reset() {
        expected = null
        observedSlots.clear()
        slotRevisions.clear()
        observedCursor = null
        cursorRevision = null
        status = InventoryAckStatus.IDLE
    }

    private fun finish(
        slots: Map<Int, InventorySlotValue>,
        cursor: InventorySlotValue,
        revisions: Map<Int, Int>,
        cursorRevision: Int,
    ): InventoryAckStatus {
        val current = expected ?: return status
        if (current.expectedSlots.all { (slot, value) -> slots[slot] == value } && cursor == current.expectedCursor) {
            status = InventoryAckStatus.ACKNOWLEDGED
            return status
        }
        val relevantRevisions = revisions.values + cursorRevision
        if (relevantRevisions.distinct().size == 1) status = InventoryAckStatus.REJECTED
        return status
    }

    private fun after(candidate: Int, baseline: Int): Boolean {
        val delta = ((candidate and STATE_MASK) - (baseline and STATE_MASK)) and STATE_MASK
        return delta in 1 until STATE_HALF_RANGE
    }

    private companion object {
        const val STATE_MASK = 0x7FFF
        const val STATE_HALF_RANGE = 0x4000
    }
}

data class MenuSlotChangeExpectation(
    val connectionId: Long,
    val menuId: Int,
    val baselineStateId: Int,
    val slot: Int,
    val before: InventorySlotValue,
)

/** A menu action is accepted only after the server changes that slot in the same menu epoch. */
class MenuSlotChangeAckTracker {
    private var expected: MenuSlotChangeExpectation? = null
    private var observed: InventorySlotValue? = null
    private var status = InventoryAckStatus.IDLE

    fun begin(next: MenuSlotChangeExpectation) {
        expected = next
        observed = null
        status = InventoryAckStatus.PENDING
    }

    fun observe(update: InventorySyncFrame): InventoryAckStatus {
        val current = expected ?: return status
        if (status != InventoryAckStatus.PENDING || update.connectionId != current.connectionId ||
            update.menuId != current.menuId || !after(update.stateId, current.baselineStateId)) return status

        if (update.cursor?.count?.let { it > 0 } == true) {
            status = InventoryAckStatus.REJECTED
            return status
        }
        val changed = update.slots[current.slot] ?: return status
        if (changed == current.before) return status
        observed = changed
        status = InventoryAckStatus.ACKNOWLEDGED
        return status
    }

    /** Check that local prediction has converged to the exact server-observed result. */
    fun reconcileLocal(slot: InventorySlotValue?, cursorEmpty: Boolean): InventoryAckStatus {
        if (status != InventoryAckStatus.ACKNOWLEDGED) return status
        if (!cursorEmpty || slot == null || slot != observed) status = InventoryAckStatus.REJECTED
        return status
    }

    fun observedValue(): InventorySlotValue? = observed
    fun expectation(): MenuSlotChangeExpectation? = expected
    fun status(): InventoryAckStatus = status

    fun abandon(): InventoryAckStatus {
        if (status == InventoryAckStatus.PENDING) status = InventoryAckStatus.ABANDONED
        return status
    }

    fun reset() {
        expected = null
        observed = null
        status = InventoryAckStatus.IDLE
    }

    private fun after(candidate: Int, baseline: Int): Boolean {
        val delta = ((candidate and STATE_MASK) - (baseline and STATE_MASK)) and STATE_MASK
        return delta in 1 until STATE_HALF_RANGE
    }

    private companion object {
        const val STATE_MASK = 0x7FFF
        const val STATE_HALF_RANGE = 0x4000
    }
}

data class MenuOpenExpectation(val connectionId: Long, val previousMenuId: Int)

/** Waits for a server content sync for a newly opened container before scanning its contents. */
class MenuOpenAckTracker {
    private var expected: MenuOpenExpectation? = null
    private var openedMenuId: Int? = null

    fun begin(next: MenuOpenExpectation) {
        expected = next
        openedMenuId = null
    }

    fun observe(update: InventorySyncFrame): Boolean {
        val current = expected ?: return false
        if (openedMenuId != null || update.connectionId != current.connectionId || !update.fullContents ||
            update.menuId == current.previousMenuId) return false
        openedMenuId = update.menuId
        return true
    }

    fun openedMenuId(): Int? = openedMenuId
    fun isWaiting(): Boolean = expected != null && openedMenuId == null
    fun reset() { expected = null; openedMenuId = null }
}

data class MenuPageExpectation(
    val connectionId: Long,
    val menuId: Int,
    val screenIdentity: Long,
    val baselineStateId: Int,
    val containerSlotCount: Int,
    val pageToken: String,
    val baselineSlots: Map<Int, InventorySlotValue>,
)

/**
 * Acknowledges one bounded same-menu pagination click. A client-side page mutation alone is never
 * sufficient: the tracker requires a later full-content packet on the same connection and menu,
 * then waits until the live menu reflects that exact server content. An explicit page token lets
 * legitimately identical pages progress when their numbered page identity changes.
 */
class MenuPageAckTracker {
    private var expectation: MenuPageExpectation? = null
    private var observedStateId: Int? = null
    private var observedSlots: Map<Int, InventorySlotValue>? = null
    private var status = InventoryAckStatus.IDLE

    fun begin(next: MenuPageExpectation): Boolean {
        reset()
        if (next.connectionId == 0L || next.menuId < 0 || next.baselineStateId < 0 ||
            next.containerSlotCount <= 0 || next.pageToken.isBlank() ||
            (0 until next.containerSlotCount).any { it !in next.baselineSlots }) return false
        expectation = next.copy(baselineSlots = next.baselineSlots.toMap())
        status = InventoryAckStatus.PENDING
        return true
    }

    fun observe(update: InventorySyncFrame): InventoryAckStatus {
        val current = expectation ?: return status
        if (status != InventoryAckStatus.PENDING || update.connectionId != current.connectionId ||
            update.menuId != current.menuId || !update.fullContents) return status
        if (update.cursor?.count?.let { it > 0 } == true) {
            status = InventoryAckStatus.REJECTED
            return status
        }
        if (!stateIsAfter(update.stateId, current.baselineStateId)) return status
        val container = (0 until current.containerSlotCount).mapNotNull { index ->
            update.slots[index]?.let { index to it }
        }.toMap()
        if (container.size != current.containerSlotCount) return status
        val previous = observedStateId
        if (previous == null || stateIsAfter(update.stateId, previous)) {
            observedStateId = update.stateId
            observedSlots = container
        } else if (update.stateId == previous && observedSlots != container) {
            status = InventoryAckStatus.REJECTED
        }
        return status
    }

    fun status(menu: MenuSnapshot?, currentPageToken: String): InventoryAckStatus {
        val current = expectation ?: return status
        if (status != InventoryAckStatus.PENDING) return status
        if (menu == null || menu.menuId != current.menuId || menu.screenIdentity != current.screenIdentity ||
            !menu.cursorEmpty) return InventoryAckStatus.REJECTED
        val observedState = observedStateId ?: return InventoryAckStatus.PENDING
        val observed = observedSlots ?: return InventoryAckStatus.PENDING
        if (menu.stateId < 0 || !stateIsAfterOrEqual(menu.stateId, observedState)) return InventoryAckStatus.PENDING
        val local = (0 until current.containerSlotCount).mapNotNull { index ->
            menu.slot(index)?.let { slot ->
                index to InventorySlotValue(
                    slot.itemId, slot.count, slot.name, slot.lore,
                    slot.componentFingerprint, slot.actualEnchantments, slot.procurementFingerprint,
                )
            }
        }.toMap()
        if (local.size != current.containerSlotCount || local != observed) return InventoryAckStatus.PENDING
        if (currentPageToken == current.pageToken && local == current.baselineSlots) return InventoryAckStatus.PENDING
        status = InventoryAckStatus.ACKNOWLEDGED
        return status
    }

    fun expectation(): MenuPageExpectation? = expectation
    fun status(): InventoryAckStatus = status
    fun reset() {
        expectation = null
        observedStateId = null
        observedSlots = null
        status = InventoryAckStatus.IDLE
    }

    private fun stateIsAfter(candidate: Int, baseline: Int): Boolean {
        val delta = ((candidate and STATE_MASK) - (baseline and STATE_MASK)) and STATE_MASK
        return delta in 1 until STATE_HALF_RANGE
    }

    private fun stateIsAfterOrEqual(candidate: Int, baseline: Int): Boolean =
        candidate == baseline || stateIsAfter(candidate, baseline)

    private companion object {
        const val STATE_MASK = 0x7FFF
        const val STATE_HALF_RANGE = 0x4000
    }
}
