package dev.donut.addon.expansion.automation.core

enum class InventoryTransferProgress { IDLE, WAITING, READY, FAILED }

data class InventoryPickupAction(
    val menuSlot: Int,
    val button: Int,
    /** Slots whose server updates are required to acknowledge this click. */
    val expectedServerSlots: Map<Int, InventorySlotValue>,
    /** All affected local slots checked after the server acknowledgement. */
    val expectedLocalSlots: Map<Int, InventorySlotValue>,
    val expectedCursor: InventorySlotValue,
    /** Pre-click values used to reject manual interference before sending the pickup. */
    val expectedBeforeSlots: Map<Int, InventorySlotValue> = emptyMap(),
    val expectedBeforeCursor: InventorySlotValue = InventorySlotValue("", 0),
)

/** Plans a bounded take, merge/split, and cursor return sequence without assuming a click succeeded. */
class InventoryTransferMachine {
    private enum class Phase { IDLE, TAKE, MOVE, RETURN, COMPLETE, FAILED }

    private var phase = Phase.IDLE
    private var plan: InventoryStagePlan.Transfer? = null
    private var pending: InventoryPickupAction? = null
    private var moved = 0
    private var ownedCarrySlots: Map<Int, InventorySlotValue>? = null
    private var ownedCarry: InventorySlotValue? = null
    private var unresolvedPending = false

    fun begin(next: InventoryStagePlan.Transfer): Boolean {
        if (next.sourceMenuSlot !in 9..44 || next.targetMenuSlot !in 36..44 ||
            next.sourceMenuSlot == next.targetMenuSlot || next.sourceStack.count <= 0 ||
            next.targetStack.count < 0 || next.requiredCount !in 1..64 ||
            next.transferCount !in 1..next.sourceStack.count ||
            next.targetStack.count + next.transferCount > next.requiredCount) return false
        plan = next
        pending = null
        moved = 0
        ownedCarrySlots = null
        ownedCarry = null
        unresolvedPending = false
        phase = Phase.TAKE
        return true
    }

    fun nextAction(): InventoryPickupAction? {
        if (pending != null) return null
        val transfer = plan ?: return null
        val empty = InventorySlotValue("", 0)
        val source = transfer.sourceStack
        return when (phase) {
            Phase.TAKE -> InventoryPickupAction(
                menuSlot = transfer.sourceMenuSlot,
                button = 0,
                expectedServerSlots = mapOf(transfer.sourceMenuSlot to empty),
                expectedLocalSlots = mapOf(
                    transfer.sourceMenuSlot to empty,
                    transfer.targetMenuSlot to transfer.targetStack,
                ),
                expectedCursor = source,
                expectedBeforeSlots = mapOf(
                    transfer.sourceMenuSlot to source,
                    transfer.targetMenuSlot to transfer.targetStack,
                ),
                expectedBeforeCursor = empty,
            )
            Phase.MOVE -> if (source.count <= transfer.transferCount) {
                val targetAfter = source.copy(count = transfer.targetStack.count + source.count)
                InventoryPickupAction(
                    menuSlot = transfer.targetMenuSlot,
                    button = 0,
                    expectedServerSlots = mapOf(transfer.targetMenuSlot to targetAfter),
                    expectedLocalSlots = mapOf(
                        transfer.sourceMenuSlot to empty,
                        transfer.targetMenuSlot to targetAfter,
                    ),
                    expectedCursor = empty,
                    expectedBeforeSlots = mapOf(
                        transfer.sourceMenuSlot to empty,
                        transfer.targetMenuSlot to transfer.targetStack,
                    ),
                    expectedBeforeCursor = source,
                )
            } else {
                val targetAfter = source.copy(count = transfer.targetStack.count + moved + 1)
                val cursorAfter = source.copy(count = source.count - moved - 1)
                val targetBefore = if (moved == 0) {
                    transfer.targetStack
                } else {
                    source.copy(count = transfer.targetStack.count + moved)
                }
                InventoryPickupAction(
                    menuSlot = transfer.targetMenuSlot,
                    button = 1,
                    expectedServerSlots = mapOf(transfer.targetMenuSlot to targetAfter),
                    expectedLocalSlots = mapOf(
                        transfer.sourceMenuSlot to empty,
                        transfer.targetMenuSlot to targetAfter,
                    ),
                    expectedCursor = cursorAfter,
                    expectedBeforeSlots = mapOf(
                        transfer.sourceMenuSlot to empty,
                        transfer.targetMenuSlot to targetBefore,
                    ),
                    expectedBeforeCursor = source.copy(count = source.count - moved),
                )
            }
            Phase.RETURN -> {
                val remainder = source.copy(count = source.count - transfer.transferCount)
                InventoryPickupAction(
                    menuSlot = transfer.sourceMenuSlot,
                    button = 0,
                    expectedServerSlots = mapOf(transfer.sourceMenuSlot to remainder),
                    expectedLocalSlots = mapOf(
                        transfer.sourceMenuSlot to remainder,
                        transfer.targetMenuSlot to source.copy(count = transfer.targetStack.count + transfer.transferCount),
                    ),
                    expectedCursor = empty,
                    expectedBeforeSlots = mapOf(
                        transfer.sourceMenuSlot to empty,
                        transfer.targetMenuSlot to source.copy(count = transfer.targetStack.count + transfer.transferCount),
                    ),
                    expectedBeforeCursor = source.copy(count = transfer.sourceStack.count - transfer.transferCount),
                )
            }
            Phase.IDLE, Phase.COMPLETE, Phase.FAILED -> null
        }
    }

    fun actionIssued(action: InventoryPickupAction): Boolean {
        if (phase == Phase.IDLE || phase == Phase.COMPLETE || phase == Phase.FAILED || pending != null) return false
        val expected = nextAction() ?: return false
        if (expected != action) return false
        pending = action
        return true
    }

    fun acknowledge(
        ack: InventoryAckStatus,
        localSlots: Map<Int, InventorySlotValue>,
        localCursor: InventorySlotValue,
    ): InventoryTransferProgress {
        if (phase == Phase.FAILED) return InventoryTransferProgress.FAILED
        if (phase == Phase.COMPLETE) return InventoryTransferProgress.READY
        if (phase == Phase.IDLE || pending == null) return InventoryTransferProgress.IDLE
        if (ack == InventoryAckStatus.PENDING) return InventoryTransferProgress.WAITING
        if (ack != InventoryAckStatus.ACKNOWLEDGED) {
            phase = Phase.FAILED
            pending = null
            unresolvedPending = ack == InventoryAckStatus.ABANDONED || ack == InventoryAckStatus.IDLE
            return InventoryTransferProgress.FAILED
        }
        val action = pending!!
        if (action.expectedLocalSlots.any { (slot, value) -> localSlots[slot] != value } ||
            localCursor != action.expectedCursor) {
            phase = Phase.FAILED
            pending = null
            return InventoryTransferProgress.FAILED
        }

        if (action.expectedCursor.count > 0) {
            ownedCarrySlots = action.expectedLocalSlots.toMap()
            ownedCarry = action.expectedCursor
        } else {
            ownedCarrySlots = null
            ownedCarry = null
        }

        pending = null
        when (phase) {
            Phase.TAKE -> phase = Phase.MOVE
            Phase.MOVE -> {
                val transfer = plan ?: run { phase = Phase.FAILED; return InventoryTransferProgress.FAILED }
                if (transfer.sourceStack.count <= transfer.transferCount) {
                    phase = Phase.COMPLETE
                } else {
                    moved++
                    if (moved >= transfer.transferCount) phase = Phase.RETURN
                }
            }
            Phase.RETURN -> phase = Phase.COMPLETE
            Phase.IDLE, Phase.COMPLETE, Phase.FAILED -> Unit
        }
        return if (phase == Phase.COMPLETE) InventoryTransferProgress.READY else InventoryTransferProgress.WAITING
    }

    fun abandon(): InventoryTransferProgress {
        if (phase != Phase.COMPLETE && phase != Phase.IDLE) phase = Phase.FAILED
        if (pending != null) unresolvedPending = true
        pending = null
        return if (phase == Phase.FAILED) InventoryTransferProgress.FAILED else InventoryTransferProgress.IDLE
    }

    /** Returns the acknowledged remainder to its original slot only while all owned slots still match. */
    fun safeCursorReturnAction(
        localSlots: Map<Int, InventorySlotValue>,
        localCursor: InventorySlotValue,
    ): InventoryPickupAction? {
        if (unresolvedPending || pending != null) return null
        val transfer = plan ?: return null
        val carry = ownedCarry ?: return null
        val expectedOwnedSlots = ownedCarrySlots ?: return null
        if (carry.count <= 0 || localCursor != carry ||
            expectedOwnedSlots.any { (slot, value) -> localSlots[slot] != value }) return null

        val empty = InventorySlotValue("", 0)
        val expectedLocal = expectedOwnedSlots + (transfer.sourceMenuSlot to carry)
        return InventoryPickupAction(
            menuSlot = transfer.sourceMenuSlot,
            button = 0,
            expectedServerSlots = mapOf(transfer.sourceMenuSlot to carry),
            expectedLocalSlots = expectedLocal,
            expectedCursor = empty,
            expectedBeforeSlots = expectedOwnedSlots,
            expectedBeforeCursor = carry,
        )
    }

    /** Records a guarded click that was never sent; it is safe to abandon without losing recovery state. */
    fun actionNotIssued(action: InventoryPickupAction): Boolean {
        if (pending != action) return false
        pending = null
        phase = Phase.FAILED
        return true
    }

    fun recoveryIssued() {
        ownedCarrySlots = null
        ownedCarry = null
        unresolvedPending = true
    }

    fun reset() {
        phase = Phase.IDLE
        plan = null
        pending = null
        moved = 0
        ownedCarrySlots = null
        ownedCarry = null
        unresolvedPending = false
    }

    fun hasPendingClick(): Boolean = pending != null
    fun pendingAction(): InventoryPickupAction? = pending
    fun isCarryingAfterAck(): Boolean = phase == Phase.MOVE || phase == Phase.RETURN
    fun status(): InventoryTransferProgress = when (phase) {
        Phase.IDLE -> InventoryTransferProgress.IDLE
        Phase.TAKE, Phase.MOVE, Phase.RETURN -> InventoryTransferProgress.WAITING
        Phase.COMPLETE -> InventoryTransferProgress.READY
        Phase.FAILED -> InventoryTransferProgress.FAILED
    }
}
