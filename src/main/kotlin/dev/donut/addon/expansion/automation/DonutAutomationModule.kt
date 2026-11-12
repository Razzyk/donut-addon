package dev.donut.addon.expansion.automation

import com.google.gson.JsonObject
import dev.donut.addon.expansion.configuration.LegacyMacroProfileMigration

import dev.donut.addon.AddonCategories
import dev.donut.addon.expansion.automation.core.ActionGate
import dev.donut.addon.expansion.automation.core.ActionTicket
import dev.donut.addon.expansion.automation.core.AutomationSnapshot
import dev.donut.addon.expansion.automation.core.DialogSnapshot
import dev.donut.addon.expansion.automation.core.InventoryAckStatus
import dev.donut.addon.expansion.automation.core.InventoryAckTracker
import dev.donut.addon.expansion.automation.core.InventoryAckChannel
import dev.donut.addon.expansion.automation.core.InventoryMutationAckTracker
import dev.donut.addon.expansion.automation.core.InventoryMutationExpectation
import dev.donut.addon.expansion.automation.core.MenuSlotChangeAckTracker
import dev.donut.addon.expansion.automation.core.MenuSlotChangeExpectation
import dev.donut.addon.expansion.automation.core.MenuOpenAckTracker
import dev.donut.addon.expansion.automation.core.MenuOpenExpectation
import dev.donut.addon.expansion.automation.core.MenuPageAckTracker
import dev.donut.addon.expansion.automation.core.MenuPageExpectation
import dev.donut.addon.expansion.automation.core.InventoryPickupAction
import dev.donut.addon.expansion.automation.core.InventoryTransferMachine
import dev.donut.addon.expansion.automation.core.InventoryTransferProgress
import dev.donut.addon.expansion.automation.core.InventorySlotValue
import dev.donut.addon.expansion.automation.core.InventorySwapExpectation
import dev.donut.addon.expansion.automation.core.MenuSnapshot
import dev.donut.addon.expansion.automation.core.PlannedEffect
import dev.donut.addon.expansion.automation.core.SessionStamp
import dev.donut.addon.expansion.automation.core.SlotSnapshot
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.handler
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.DeathScreen
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.core.BlockPos
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/** A live client-thread view paired with the immutable planner snapshot. */
data class LiveAutomationFrame internal constructor(
    val snapshot: AutomationSnapshot,
    internal val connection: ClientPacketListener,
    internal val world: ClientLevel,
    internal val player: LocalPlayer,
    internal val screen: net.minecraft.client.gui.screens.Screen?,
    internal val menu: net.minecraft.world.inventory.AbstractContainerMenu,
)

/** Native module base that turns a captured state into one guarded game action per tick. */
abstract class DonutAutomationModule(name: String) :
    ClientModule(name, AddonCategories.MACROS, disableOnQuit = true) {
    override fun prepareDeserialize(jsonObject: JsonObject) {
        LegacyMacroProfileMigration.flattenOptions(name, jsonObject)
    }
    private val actions = ActionGate(16)
    private var connection: ClientPacketListener? = null
    private var observedWorld: ClientLevel? = null
    private var observedPlayer: LocalPlayer? = null
    @Volatile private var generation = 0L
    private var tick = 0L
    private var nextTicket = 0L
    private var committedTick = Long.MIN_VALUE
    private val inventoryAckTracker = InventoryAckTracker()
    private val inventoryMutationAckTracker = InventoryMutationAckTracker()
    private val menuSlotChangeAckTracker = MenuSlotChangeAckTracker()
    private val menuOpenAckTracker = MenuOpenAckTracker()
    private val menuPageAckTracker = MenuPageAckTracker()
    private val inventoryAckChannel = InventoryAckChannel()

    @Suppress("unused")
    private val inventoryAcks = handler<PacketEvent> { event ->
        inventoryAckChannel.onPacket(event.origin, event.original, event.packet)
    }

    init {
        enabledValue.doNotIncludeAlways().notPersistent()
    }

    final override fun onEnabled() {
        invalidate()
        onAutomationReset()
    }

    final override fun onDisabled() {
        onAutomationDisabled()
        invalidate()
        onAutomationReset()
        onAutomationSessionEnded()
    }

    /** Called after disable, connection/world replacement, or local-player replacement. */
    protected open fun onAutomationReset() = Unit

    /** Read-only immutable packet evidence for derived workflows that need a different ACK shape. */
    protected open fun onInventorySyncFrame(update: dev.donut.addon.expansion.automation.core.InventorySyncFrame) = Unit

    /** Gives modules one guarded chance to return a cursor stack they own before disable cleanup. */
    protected open fun onAutomationDisabled() = Unit

    /** Called when the active player/world/connection session ends or the module is disabled. */
    protected open fun onAutomationSessionEnded() = Unit

    internal fun captureFrame(): LiveAutomationFrame? {
        val currentConnection = mc.connection
        val currentWorld = mc.level
        val currentPlayer = mc.player
        if (currentConnection == null || currentWorld == null || currentPlayer == null) {
            if (connection != null || observedWorld != null || observedPlayer != null) {
                invalidate()
                onAutomationReset()
                onAutomationSessionEnded()
            }
            return null
        }

        if (connection !== currentConnection || observedWorld !== currentWorld || observedPlayer !== currentPlayer) {
            val endedSession = connection != null || observedWorld != null || observedPlayer != null
            invalidate()
            connection = currentConnection
            observedWorld = currentWorld
            observedPlayer = currentPlayer
            onAutomationReset()
            if (endedSession) onAutomationSessionEnded()
        }

        tick++
        val connectionIdentity = System.identityHashCode(currentConnection).toLong()
        inventoryAckChannel.setSession(generation, connectionIdentity)
        inventoryAckChannel.drain(generation, connectionIdentity) { update ->
            inventoryAckTracker.observe(update)
            inventoryMutationAckTracker.observe(update)
            menuSlotChangeAckTracker.observe(update)
            menuOpenAckTracker.observe(update)
            menuPageAckTracker.observe(update)
            onInventorySyncFrame(update)
        }
        val screen = mc.gui.screen()
        val menu = currentPlayer.containerMenu
        val menuSnapshot = if (screen is AbstractContainerScreen<*>) {
            MenuSnapshot(System.identityHashCode(screen).toLong(), menu.containerId, screen.title.string,
                menu.slots.map(::snapshotSlot), menu.carried.isEmpty, menu.stateId, inventoryValue(menu.carried))
        } else null
        val inventoryMenu = currentPlayer.inventoryMenu
        val inventoryMenuSnapshot = MenuSnapshot(
            System.identityHashCode(inventoryMenu).toLong(), inventoryMenu.containerId, "Player Inventory",
            inventoryMenu.slots.map(::snapshotSlot), inventoryMenu.carried.isEmpty, inventoryMenu.stateId,
            inventoryValue(inventoryMenu.carried),
        )

        val inventoryFingerprint = (0 until 36).joinToString("|") { index ->
            val stack = currentPlayer.inventory.getItem(index)
            if (stack.isEmpty) "-" else "${BuiltInRegistries.ITEM.getKey(stack.item)}:${stack.count}:${stack.hoverName.string}"
        }
        val stamp = SessionStamp(
            generation = generation,
            connection = System.identityHashCode(currentConnection).toLong(),
            world = System.identityHashCode(currentWorld).toLong(),
            player = System.identityHashCode(currentPlayer).toLong(),
        )
        val snapshot = AutomationSnapshot(
            session = stamp,
            tick = tick,
            menu = menuSnapshot,
            onlineNames = currentConnection.onlinePlayers.mapNotNull { it.profile?.name?.lowercase() }.toSet(),
            nearbyNames = currentWorld.players().asSequence().filter { it !== currentPlayer }
                .map { it.gameProfile.name.lowercase() }.toSet(),
            inventoryFingerprint = inventoryFingerprint,
            dialog = DonutDialogSnapshotCapture.capture(screen),
            playerInventoryMenu = inventoryMenuSnapshot,
        )
        return LiveAutomationFrame(snapshot, currentConnection, currentWorld, currentPlayer, screen, menu)
    }

    /** Reset runtime-only state even when ClientModule has stopped dispatching its own hooks. */
    internal fun onAutomationWorldChanged() {
        invalidate()
        onAutomationReset()
        onAutomationSessionEnded()
    }

    internal fun command(frame: LiveAutomationFrame, value: String): Boolean =
        perform(frame, PlannedEffect.Command(value))

    internal fun userCommand(frame: LiveAutomationFrame, value: String): Boolean =
        perform(frame, PlannedEffect.UserCommand(value))

    internal fun requestSuggestions(frame: LiveAutomationFrame, requestId: Int, prefix: String): Boolean =
        perform(frame, PlannedEffect.Suggestion(requestId, prefix))

    internal fun chatText(frame: LiveAutomationFrame, value: String): Boolean =
        perform(frame, PlannedEffect.Chat(value))

    internal fun openChat(frame: LiveAutomationFrame, value: String): Boolean =
        perform(frame, PlannedEffect.OpenChat(value))

    internal fun click(
        frame: LiveAutomationFrame,
        slot: Int,
        button: Int = 0,
        quickMove: Boolean = false,
    ): Boolean {
        val menu = frame.snapshot.menu ?: return false
        val slotState = menu.slot(slot) ?: return false
        if (!menu.cursorEmpty) return false
        return perform(
            frame,
            PlannedEffect.Click(menu.menuId, slot, button, quickMove),
            screenIdentity = menu.screenIdentity,
            menuId = menu.menuId,
            slotIndex = slot,
            expectedSlotFingerprint = slotState.fingerprint,
        )
    }

    internal fun selectHotbar(frame: LiveAutomationFrame, slot: Int): Boolean =
        if (slot in 0..8) perform(frame, PlannedEffect.Hotbar(slot)) else false

    internal fun beginInventorySwap(frame: LiveAutomationFrame, plan: dev.donut.addon.expansion.automation.core.InventoryStagePlan.Swap): Boolean {
        val menu = frame.snapshot.playerInventoryMenu ?: return false
        if (menu.stateId < 0) return false
        val source = menu.slot(plan.sourceMenuSlot) ?: return false
        val destination = menu.slot(36 + plan.hotbarSlot) ?: return false
        inventoryAckTracker.reset()
        inventoryMutationAckTracker.reset()
        inventoryAckTracker.begin(
            InventorySwapExpectation(
                frame.snapshot.session.connection, menu.menuId, menu.stateId,
                plan.sourceMenuSlot, 36 + plan.hotbarSlot,
                source.toInventoryValue(), destination.toInventoryValue(),
            ),
        )
        val accepted = perform(
            frame,
            PlannedEffect.InventorySwap(
                menu.menuId, menu.stateId, plan.sourceMenuSlot, plan.hotbarSlot,
                plan.sourceFingerprint, plan.displacedFingerprint,
            ),
        )
        if (!accepted) inventoryAckTracker.reset()
        return accepted
    }

    internal fun inventorySwapStatus(frame: LiveAutomationFrame): InventoryAckStatus {
        val menu = frame.snapshot.playerInventoryMenu ?: return InventoryAckStatus.REJECTED
        val expectation = inventoryAckTracker.expectation() ?: return inventoryAckTracker.status()
        val source = menu.slot(expectation.sourceMenuSlot)
        val destination = menu.slot(expectation.destinationMenuSlot)
        return inventoryAckTracker.reconcileLocal(
            source?.toInventoryValue(), destination?.toInventoryValue(), menu.cursorEmpty,
        )
    }

    internal fun abandonInventorySwap() { inventoryAckTracker.abandon() }

    internal fun beginInventoryPickup(frame: LiveAutomationFrame, action: InventoryPickupAction): Boolean {
        val menu = frame.snapshot.playerInventoryMenu ?: return false
        val slot = menu.slot(action.menuSlot) ?: return false
        val cursor = menu.cursorStack ?: InventorySlotValue("", 0)
        if (menu.stateId < 0 || frame.player.containerMenu !== frame.player.inventoryMenu) return false
        val liveMenu = frame.player.inventoryMenu
        if (action.expectedBeforeSlots.any { (index, value) ->
                index !in liveMenu.slots.indices || inventoryValue(liveMenu.getSlot(index).item) != value
            } || inventoryValue(liveMenu.carried) != action.expectedBeforeCursor) return false
        inventoryMutationAckTracker.reset()
        inventoryAckTracker.reset()
        inventoryMutationAckTracker.begin(
            InventoryMutationExpectation(
                frame.snapshot.session.connection, menu.menuId, menu.stateId,
                action.expectedServerSlots, action.expectedCursor,
            ),
        )
        val accepted = perform(
            frame,
            PlannedEffect.InventoryPickup(
                menu.menuId, menu.stateId, action.menuSlot, action.button,
                slot.fingerprint, cursor.fingerprint,
            ),
        )
        if (!accepted) inventoryMutationAckTracker.reset()
        return accepted
    }

    internal fun inventoryPickupStatus(frame: LiveAutomationFrame): InventoryAckStatus {
        val menu = frame.snapshot.playerInventoryMenu ?: return InventoryAckStatus.REJECTED
        val expected = inventoryMutationAckTracker.expectation() ?: return inventoryMutationAckTracker.status()
        val local = expected.expectedSlots.keys.mapNotNull { index ->
            menu.slot(index)?.let { index to it.toInventoryValue() }
        }.toMap()
        return inventoryMutationAckTracker.reconcileLocal(local, menu.cursorStack ?: InventorySlotValue("", 0))
    }

    internal fun abandonInventoryPickup() { inventoryMutationAckTracker.abandon() }

    /** Returns an acknowledged module-owned cursor remainder into its original slot on disable. */
    protected fun restoreOwnedInventoryCursor(machine: InventoryTransferMachine): Boolean {
        val currentConnection = mc.connection ?: return false
        val currentWorld = mc.level ?: return false
        val player = mc.player ?: return false
        if (connection !== currentConnection || observedWorld !== currentWorld || observedPlayer !== player ||
            mc.gui.screen() != null || player.containerMenu !== player.inventoryMenu) return false

        val menu = player.inventoryMenu
        val localSlots = menu.slots.associate { it.index to inventoryValue(it.item) }
        val action = machine.safeCursorReturnAction(localSlots, inventoryValue(menu.carried)) ?: return false
        if (action.menuSlot !in 9..44 || action.button !in 0..1) return false
        val gameMode = mc.gameMode ?: return false
        gameMode.handleContainerInput(menu.containerId, action.menuSlot, action.button, ContainerInput.PICKUP, player)
        return true
    }

    /** Advances no more than one acknowledged inventory pickup step per tick. */
    internal fun tickInventoryTransfer(
        frame: LiveAutomationFrame,
        machine: InventoryTransferMachine,
    ): InventoryTransferProgress {
        if (machine.hasPendingClick()) {
            val action = machine.pendingAction() ?: return InventoryTransferProgress.FAILED
            val status = inventoryPickupStatus(frame)
            val menu = frame.snapshot.playerInventoryMenu ?: return InventoryTransferProgress.FAILED
            val local = action.expectedLocalSlots.keys.mapNotNull { index ->
                menu.slot(index)?.let { index to it.toInventoryValue() }
            }.toMap()
            return machine.acknowledge(status, local, menu.cursorStack ?: InventorySlotValue("", 0))
        }
        val action = machine.nextAction() ?: return machine.status()
        if (!machine.actionIssued(action)) return machine.abandon()
        if (!beginInventoryPickup(frame, action)) {
            inventoryMutationAckTracker.reset()
            machine.actionNotIssued(action)
            return InventoryTransferProgress.FAILED
        }
        return InventoryTransferProgress.WAITING
    }

    internal fun attack(frame: LiveAutomationFrame, entity: Entity, range: Double): Boolean =
        perform(frame, PlannedEffect.Attack(entity.id, range.coerceIn(0.5, 8.0)))

    internal fun useMainHand(frame: LiveAutomationFrame): Boolean =
        perform(frame, PlannedEffect.UseMainHand)

    internal fun useOnBlock(frame: LiveAutomationFrame, hit: BlockHitResult): Boolean {
        val currentHit = mc.hitResult as? BlockHitResult ?: return false
        if (hit.blockPos != currentHit.blockPos || hit.direction != currentHit.direction) return false
        val block = frame.world.getBlockState(hit.blockPos).block
        val held = frame.player.mainHandItem
        if (held.isEmpty) return false
        return perform(
            frame,
            PlannedEffect.UseOnBlock(
                hit.blockPos.x, hit.blockPos.y, hit.blockPos.z, hit.direction.name,
                hit.location.x, hit.location.y, hit.location.z,
                BuiltInRegistries.BLOCK.getKey(block).toString(),
                BuiltInRegistries.ITEM.getKey(held.item).toString(),
            ),
        )
    }

    internal fun respawn(frame: LiveAutomationFrame): Boolean = perform(frame, PlannedEffect.Respawn)

    internal fun closeMenu(frame: LiveAutomationFrame): Boolean {
        val menu = frame.snapshot.menu ?: return false
        return perform(
            frame,
            PlannedEffect.CloseMenu(menu.menuId),
            screenIdentity = menu.screenIdentity,
            menuId = menu.menuId,
        )
    }

    /** Begin server acknowledgement for one owned listing slot before clicking it. */
    internal fun beginMenuSlotChangeAck(frame: LiveAutomationFrame, slotIndex: Int): Boolean {
        val menu = frame.snapshot.menu ?: return false
        val slot = menu.slot(slotIndex) ?: return false
        if (menu.stateId < 0 || !menu.cursorEmpty || slot.count <= 0 || slot.itemId.isBlank()) return false
        menuSlotChangeAckTracker.reset()
        menuSlotChangeAckTracker.begin(
            MenuSlotChangeExpectation(
                frame.snapshot.session.connection, menu.menuId, menu.stateId, slotIndex,
                slot.toInventoryValue(),
            ),
        )
        return true
    }

    internal fun menuSlotChangeAckStatus(frame: LiveAutomationFrame): InventoryAckStatus {
        val menu = frame.snapshot.menu ?: return InventoryAckStatus.REJECTED
        if (menuSlotChangeAckTracker.status() == InventoryAckStatus.ACKNOWLEDGED) {
            val expected = menuSlotChangeAckTracker.expectation() ?: return InventoryAckStatus.REJECTED
            if (menu.menuId != expected.menuId) return InventoryAckStatus.REJECTED
            if (!menu.cursorEmpty) return InventoryAckStatus.PENDING
            menuSlotChangeAckTracker.reconcileLocal(
                menu.slot(expected.slot)?.toInventoryValue(), menu.cursorEmpty,
            )
        }
        return menuSlotChangeAckTracker.status()
    }

    internal fun resetMenuSlotChangeAck() = menuSlotChangeAckTracker.reset()

    internal fun beginMenuOpenAck(frame: LiveAutomationFrame, previousMenuId: Int): Boolean {
        menuOpenAckTracker.reset()
        menuOpenAckTracker.begin(
            MenuOpenExpectation(frame.snapshot.session.connection, previousMenuId),
        )
        return true
    }

    internal fun openedMenuId(): Int? = menuOpenAckTracker.openedMenuId()

    internal fun beginMenuPageAck(frame: LiveAutomationFrame, containerSlotCount: Int, pageToken: String): Boolean {
        val menu = frame.snapshot.menu ?: return false
        if (containerSlotCount <= 0 || menu.stateId < 0 || !menu.cursorEmpty ||
            (0 until containerSlotCount).any { menu.slot(it) == null }) return false
        val slots = (0 until containerSlotCount).associateWith { index ->
            val slot = menu.slot(index) ?: return false
            slot.toInventoryValue()
        }
        return menuPageAckTracker.begin(
            MenuPageExpectation(
                frame.snapshot.session.connection, menu.menuId, menu.screenIdentity, menu.stateId,
                containerSlotCount, pageToken, slots,
            ),
        )
    }

    internal fun menuPageAckStatus(frame: LiveAutomationFrame, pageToken: String): InventoryAckStatus =
        menuPageAckTracker.status(frame.snapshot.menu, pageToken)

    internal fun resetMenuPageAck() = menuPageAckTracker.reset()

    internal fun setDialogText(frame: LiveAutomationFrame, index: Int, value: String): Boolean {
        val dialog = frame.snapshot.dialog ?: return false
        val widget = dialog.widget(index) ?: return false
        if (widget.kind != "input" || value.length > 120 || value.any(Char::isISOControl)) return false
        return perform(
            frame,
            PlannedEffect.DialogSetText(index, widget.fingerprint, value),
            dialogIdentity = dialog.screenIdentity,
            dialogWidgetIndex = index,
            expectedDialogWidgetFingerprint = widget.fingerprint,
        )
    }

    internal fun pressDialog(frame: LiveAutomationFrame, index: Int): Boolean {
        val dialog = frame.snapshot.dialog ?: return false
        val widget = dialog.widget(index) ?: return false
        if (widget.kind != "button") return false
        return perform(
            frame,
            PlannedEffect.DialogPress(index, widget.fingerprint, widget.label),
            dialogIdentity = dialog.screenIdentity,
            dialogWidgetIndex = index,
            expectedDialogWidgetFingerprint = widget.fingerprint,
        )
    }

    private fun perform(
        frame: LiveAutomationFrame,
        effect: PlannedEffect,
        screenIdentity: Long? = null,
        menuId: Int? = null,
        slotIndex: Int? = null,
        expectedSlotFingerprint: String? = null,
        dialogIdentity: Long? = null,
        dialogWidgetIndex: Int? = null,
        expectedDialogWidgetFingerprint: String? = null,
    ): Boolean {
        val id = ++nextTicket
        val ticket = ActionTicket(
            id = id,
            session = frame.snapshot.session,
            expiresAtTick = frame.snapshot.tick,
            effect = effect,
            screenIdentity = screenIdentity,
            menuId = menuId,
            slotIndex = slotIndex,
            expectedSlotFingerprint = expectedSlotFingerprint,
            dialogIdentity = dialogIdentity,
            dialogWidgetIndex = dialogWidgetIndex,
            expectedDialogWidgetFingerprint = expectedDialogWidgetFingerprint,
        )
        if (!actions.offer(ticket)) return false
        val committed = actions.consume(frame.snapshot) ?: return false
        if (committed.id != id || committedTick == frame.snapshot.tick || !validLiveFrame(frame)) return false
        val success = when (effect) {
            is PlannedEffect.Command -> {
                val clean = effect.value.trim().removePrefix("/")
                val root = clean.substringBefore(' ').lowercase()
                if (clean != effect.value.trim().removePrefix("/") || root !in COMMANDS ||
                    clean.length !in 1..240 || clean.any { it.isISOControl() || it == ';' }) false
                else { frame.connection.sendCommand(clean); true }
            }
            is PlannedEffect.UserCommand -> {
                val clean = effect.value.trim().removePrefix("/")
                if (clean.isEmpty() || clean.length > 240 || clean.any { it.isISOControl() || it == ';' }) false
                else { frame.connection.sendCommand(clean); true }
            }
            is PlannedEffect.Suggestion -> {
                val clean = effect.prefix.trim()
                if (effect.requestId < 0 || clean.isEmpty() || clean.length > 100 || clean.any { it.isISOControl() || it == ';' }) false
                else { frame.connection.send(ServerboundCommandSuggestionPacket(effect.requestId, clean)); true }
            }
            is PlannedEffect.Chat -> {
                val clean = effect.value.trim()
                if (clean.isEmpty() || clean.length > 256 || clean.any { it.isISOControl() }) false
                else { frame.connection.sendChat(clean); true }
            }
            is PlannedEffect.OpenChat -> {
                if (mc.gui.screen() != null || effect.value.length > 256 || effect.value.any { it.isISOControl() }) false
                else { mc.setScreenAndShow(ChatScreen(effect.value, false, true)); true }
            }
            is PlannedEffect.Click -> {
                val menuNow = frame.snapshot.menu ?: return false
                val liveScreen = mc.gui.screen()
                val liveMenu = frame.player.containerMenu
                if (liveScreen !== frame.screen || liveMenu !== frame.menu ||
                    liveMenu.containerId != effect.menuId || !menuNow.cursorEmpty ||
                    fingerprint(liveMenu.getSlot(effect.slot)) != expectedSlotFingerprint) false
                else {
                    val gameMode = mc.gameMode ?: return false
                    gameMode.handleContainerInput(
                        effect.menuId, effect.slot, effect.button,
                        if (effect.quickMove) ContainerInput.QUICK_MOVE else ContainerInput.PICKUP,
                        frame.player,
                    )
                    true
                }
            }
            is PlannedEffect.CloseMenu -> {
                val currentMenu = mc.gui.screen()
                val liveMenu = frame.player.containerMenu
                if (currentMenu !== frame.screen || liveMenu !== frame.menu ||
                    liveMenu.containerId != effect.menuId || mc.gui.screen() !is AbstractContainerScreen<*>) false
                else { frame.player.closeContainer(); true }
            }
            is PlannedEffect.DialogSetText -> {
                val screen = mc.gui.screen()
                val textField = screen?.children()?.getOrNull(effect.widgetIndex) as? EditBox
                val current = DonutDialogSnapshotCapture.widget(screen, effect.widgetIndex)
                if (screen !== frame.screen || textField == null || current?.fingerprint != effect.expectedFingerprint ||
                    effect.value.length > 120 || effect.value.any(Char::isISOControl)) false
                else { textField.value = effect.value; true }
            }
            is PlannedEffect.DialogPress -> {
                val screen = mc.gui.screen()
                val button = screen?.children()?.getOrNull(effect.widgetIndex) as? Button
                val current = DonutDialogSnapshotCapture.widget(screen, effect.widgetIndex)
                if (screen !== frame.screen || button == null || current?.fingerprint != effect.expectedFingerprint ||
                    button.message.string != effect.expectedLabel) false
                else { button.onPress(KeyEvent(257, 0, 0)); true }
            }
            is PlannedEffect.Hotbar -> {
                if (effect.slot !in 0..8 || frame.player.isDeadOrDying) false
                else { frame.player.inventory.selectedSlot = effect.slot; true }
            }
            is PlannedEffect.Attack -> {
                val target = frame.world.getEntity(effect.entityId)
                if (target == null || target === frame.player || !target.isAlive ||
                    frame.player.distanceToSqr(target) > effect.range * effect.range ||
                    mc.gui.screen() != null) false
                else {
                    val gameMode = mc.gameMode ?: return false
                    gameMode.attack(frame.player, target)
                    net.ccbluex.liquidbounce.utils.block.SwingMode.DO_NOT_HIDE.swing(InteractionHand.MAIN_HAND)
                    true
                }
            }
            is PlannedEffect.UseOnBlock -> {
                val currentHit = mc.hitResult as? BlockHitResult
                val pos = BlockPos(effect.x, effect.y, effect.z)
                val currentBlock = frame.world.getBlockState(pos).block
                val held = frame.player.mainHandItem
                if (currentHit == null || currentHit.blockPos != pos || currentHit.direction.name != effect.side ||
                    BuiltInRegistries.BLOCK.getKey(currentBlock).toString() != effect.expectedBlockId || held.isEmpty ||
                    BuiltInRegistries.ITEM.getKey(held.item).toString() != effect.heldItemId ||
                    frame.player.isDeadOrDying || mc.gui.screen() != null ||
                    frame.player.distanceToSqr(Vec3(effect.hitX, effect.hitY, effect.hitZ)) >
                    (frame.player.blockInteractionRange() + 0.25) * (frame.player.blockInteractionRange() + 0.25)) false
                else {
                    val gameMode = mc.gameMode ?: return false
                    gameMode.useItemOn(frame.player, InteractionHand.MAIN_HAND, currentHit).consumesAction()
                }
            }
            PlannedEffect.UseMainHand -> {
                if (mc.gui.screen() != null) false
                else {
                    val gameMode = mc.gameMode ?: return false
                    gameMode.useItem(frame.player, InteractionHand.MAIN_HAND)
                    true
                }
            }
            PlannedEffect.Respawn -> {
                if (!frame.player.isDeadOrDying || mc.gui.screen() !is DeathScreen) false
                else { frame.player.respawn(); true }
            }
            is PlannedEffect.InventorySwap -> {
                val menuNow = frame.snapshot.playerInventoryMenu
                val inventoryMenu = frame.player.inventoryMenu
                val source = menuNow?.slot(effect.sourceSlot)
                val destinationIndex = 36 + effect.hotbarSlot
                val destination = menuNow?.slot(destinationIndex)
                if (effect.sourceSlot !in 9..35 || effect.hotbarSlot !in 0..8 ||
                    mc.gui.screen() != null || frame.player.containerMenu !== inventoryMenu ||
                    inventoryMenu.containerId != effect.menuId || menuNow?.menuId != effect.menuId ||
                    menuNow?.stateId != effect.expectedStateId || inventoryMenu.stateId != effect.expectedStateId ||
                    menuNow?.cursorEmpty != true || source?.stackFingerprint != effect.expectedSource ||
                    destination?.stackFingerprint != effect.expectedHotbar ||
                    snapshotSlot(inventoryMenu.getSlot(effect.sourceSlot)).stackFingerprint != effect.expectedSource ||
                    snapshotSlot(inventoryMenu.getSlot(destinationIndex)).stackFingerprint != effect.expectedHotbar) false
                else {
                    val gameMode = mc.gameMode ?: return false
                    gameMode.handleContainerInput(
                        effect.menuId, effect.sourceSlot, effect.hotbarSlot, ContainerInput.SWAP, frame.player,
                    )
                    true
                }
            }
            is PlannedEffect.InventoryPickup -> {
                val menuNow = frame.snapshot.playerInventoryMenu
                val inventoryMenu = frame.player.inventoryMenu
                val currentSlot = if (effect.menuSlot in inventoryMenu.slots.indices)
                    snapshotSlot(inventoryMenu.getSlot(effect.menuSlot)) else null
                val currentCursor = inventoryValue(inventoryMenu.carried)
                if (effect.menuSlot !in 9..44 || effect.button !in 0..1 ||
                    mc.gui.screen() != null || frame.player.containerMenu !== inventoryMenu ||
                    inventoryMenu.containerId != effect.menuId || menuNow?.menuId != effect.menuId ||
                    menuNow?.stateId != effect.expectedStateId || inventoryMenu.stateId != effect.expectedStateId ||
                    currentSlot?.fingerprint != effect.expectedSlotFingerprint ||
                    currentCursor.fingerprint != effect.expectedCursorFingerprint) false
                else {
                    val gameMode = mc.gameMode ?: return false
                    gameMode.handleContainerInput(
                        effect.menuId, effect.menuSlot, effect.button, ContainerInput.PICKUP, frame.player,
                    )
                    true
                }
            }
        }
        if (success) committedTick = frame.snapshot.tick
        return success
    }

    private fun validLiveFrame(frame: LiveAutomationFrame): Boolean =
        enabled && mc.connection === frame.connection && mc.level === frame.world && mc.player === frame.player &&
            connection === frame.connection && observedWorld === frame.world && observedPlayer === frame.player &&
            frame.snapshot.session.generation == generation

    private fun fingerprint(slot: net.minecraft.world.inventory.Slot): String {
        return snapshotSlot(slot).fingerprint
    }

    private fun snapshotSlot(slot: net.minecraft.world.inventory.Slot): SlotSnapshot {
        val value = inventoryValue(slot.item)
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

    private fun inventoryValue(stack: net.minecraft.world.item.ItemStack): InventorySlotValue =
        InventorySlotValue.fromStack(stack)

    private fun SlotSnapshot.toInventoryValue(): InventorySlotValue =
        InventorySlotValue(itemId, count, name, lore, componentFingerprint, actualEnchantments, procurementFingerprint)

    private fun invalidate() {
        generation++
        tick = 0
        committedTick = Long.MIN_VALUE
        actions.clear()
        inventoryAckTracker.reset()
        inventoryMutationAckTracker.reset()
        menuSlotChangeAckTracker.reset()
        menuOpenAckTracker.reset()
        menuPageAckTracker.reset()
        inventoryAckChannel.reset()
        connection = null
        observedWorld = null
        observedPlayer = null
    }

    companion object {
        private val COMMANDS = setOf("ah", "sell", "orders", "tpa", "tpahere", "pay", "bal", "balance", "home")
    }
}
