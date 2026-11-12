package dev.donut.addon.expansion.automation

import dev.donut.addon.expansion.automation.core.MenuClassifier
import dev.donut.addon.expansion.automation.core.MenuKind
import dev.donut.addon.expansion.automation.core.MarketRules
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import kotlin.math.round
import java.util.concurrent.ConcurrentLinkedQueue

class DonutTravelRequests : DonutAutomationModule("Donut Travel Requests") {
    private enum class RequestKind(override val tag: String) : Tagged { TPA("tpa"), TPAHERE("tpahere") }
    private val request by enumChoice("Command", RequestKind.TPA)
    private val target by text("Player", "")
    private val interval by float("Interval (s)", 1.0f, 0.25f..60.0f).onChange { value ->
        if (!value.isFinite()) 1.0f else (0.25f + round((value - 0.25f) / 0.25f) * 0.25f).coerceIn(0.25f, 60.0f)
    }
    private var nextSendAt = 0L
    private var lastSentAt = 0L
    @Volatile private var holdUntil = 0L
    private var pressedConfirmDialog = 0L
    private var sentCount = 0
    private val incoming = ConcurrentLinkedQueue<String>()

    init { literalDescription { "Sends paced teleport requests and honors server rate limits and recent confirmation dialogs." } }
    override fun onAutomationReset() {
        nextSendAt = 0L; lastSentAt = 0L; holdUntil = 0L; pressedConfirmDialog = 0L; sentCount = 0; incoming.clear()
    }

    @Suppress("unused")
    private val packet = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING) return@handler
        val text = (event.packet as? ClientboundSystemChatPacket)?.content?.string ?: return@handler
        incoming.offer(text)
    }

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        val frame = captureFrame() ?: return@handler
        while (true) {
            val message = incoming.poll() ?: break
            val normalized = message.replace(Regex("(?i)§[0-9A-FK-ORX]"), "").trim().lowercase()
            val wait = RATE_LIMIT.matchEntire(normalized) ?: continue
            val seconds = wait.groupValues[1].toDoubleOrNull()?.coerceIn(0.0, MAX_SERVER_HOLD_SECONDS) ?: continue
            holdUntil = maxOf(holdUntil, System.nanoTime() + (seconds * 1_000_000_000.0).toLong() + RATE_LIMIT_PADDING_NANOS)
        }
        val now = System.nanoTime()
        val dialog = frame.snapshot.dialog
        if (dialog != null && now - lastSentAt in 0..CONFIRM_WINDOW_NANOS && dialog.screenIdentity != pressedConfirmDialog &&
            dialog.title.contains("teleport", ignoreCase = true)) {
            val confirm = dialog.widgets.firstOrNull { it.kind == "button" &&
                (it.label.equals("confirm", true) || it.label.equals("yes", true)) }
            if (confirm != null && pressDialog(frame, confirm.index)) pressedConfirmDialog = dialog.screenIdentity
            return@handler
        }
        if (now < nextSendAt || now < holdUntil) return@handler
        val name = target.trim().takeIf { PLAYER_NAME.matches(it) } ?: return@handler
        val verb = if (request == RequestKind.TPA) "tpa" else "tpahere"
        if (command(frame, "$verb $name")) {
            lastSentAt = now
            pressedConfirmDialog = 0L
            sentCount++
            nextSendAt = now + (interval * 1_000_000_000.0).toLong()
        }
    }

    companion object {
        private val PLAYER_NAME = Regex("\\.?[A-Za-z0-9_]{1,16}")
        private val RATE_LIMIT = Regex("^you need to wait another ([0-9]+(?:\\.[0-9]+)?) seconds? to execute a command.*$")
        private const val MAX_SERVER_HOLD_SECONDS = 30.0
        private const val RATE_LIMIT_PADDING_NANOS = 80_000_000L
        private const val CONFIRM_WINDOW_NANOS = 3_000_000_000L
    }
}

class DonutChatKeys : DonutAutomationModule("Donut Chat Keys") {
    private enum class PressStyle(override val tag: String) : Tagged { SEND("Send"), OPEN("Open in Chat") }
    private val slotCount by int("Slots", 3, 1..8)
    private val pressStyle by enumChoice("On Press", PressStyle.SEND)
    private val cooldown by float("Cooldown (s)", 1.0f, 1.0f..10.0f).onChange { value ->
        if (!value.isFinite()) 1.0f else (1.0f + round((value - 1.0f) / 0.5f) * 0.5f).coerceIn(1.0f, 10.0f)
    }
    private val keys = (1..8).map { key("Macro $it Key") }
    private val messages = (1..8).map { text("Macro $it Text", "") }
    private var lastSendAt = 0L
    private var sentAny = false

    init { literalDescription { "Sends a configured message or opens it in chat when a bound key is pressed." } }
    override fun onAutomationReset() { lastSendAt = 0L; sentAny = false }

    @Suppress("unused")
    private val keyboard = handler<KeyboardKeyEvent> { event ->
        if (!event.isPressed || event.isRepeat) return@handler
        handlePress(event.key, event.screen != null)
    }

    @Suppress("unused")
    private val mouse = handler<MouseButtonEvent> { event ->
        if (!event.isPressed) return@handler
        handlePress(event.key, event.screen != null)
    }

    private fun handlePress(key: InputConstants.Key, hasEventScreen: Boolean) {
        if (hasEventScreen || mc.gui.screen() != null) return
        val frame = captureFrame() ?: return
        val index = (0 until slotCount.coerceIn(1, 8)).firstOrNull { keys[it].get() == key } ?: return
        val message = messages[index].get().trim()
        if (message.isEmpty() || message.length > 256 || message.any(Char::isISOControl)) return
        val now = System.nanoTime()
        val minimumGap = (cooldown * 1_000_000_000.0).toLong()
        if (pressStyle == PressStyle.SEND && sentAny && now - lastSendAt < minimumGap) return
        val sent = when (pressStyle) {
            PressStyle.OPEN -> openChat(frame, message)
            PressStyle.SEND -> if (message.startsWith('/')) userCommand(frame, message) else chatText(frame, message)
        }
        if (sent && pressStyle == PressStyle.SEND) { lastSendAt = now; sentAny = true }
    }
}

class DonutOrderCollector : DonutAutomationModule("Donut Order Collector") {
    private val orderSlot by int("Order Slot (0 = first)", 0, 0..44)
    private val pages by int("Pages To Drop", 1, 1..100)
    private val delaySeconds by float("Drop Delay (s)", 0.5f, 0.25f..5.0f)
    private var phase = Phase.OPEN_MENU
    private var pagesDropped = 0
    private var emptyPageFlips = 0
    private var deadline = 0L
    private var nextActionAt = 0L
    private var selectedOrderItem = ""
    private var selectedOrderName = ""
    private var originalMenuId = -1
    private var originalMenuTitle = ""
    private var previousPageFingerprint = ""

    private enum class Phase { OPEN_MENU, WAIT_MENU, OPEN_ORDER, WAIT_ORDER, OPEN_COLLECT, WAIT_COLLECT, PAGE, WAIT_PAGE, NEXT_PAGE, STOPPED }

    init { literalDescription { "Collects configured order pages while each menu and order identity remains stable." } }
    override fun onAutomationReset() {
        phase = Phase.OPEN_MENU; pagesDropped = 0; emptyPageFlips = 0; deadline = 0L; nextActionAt = 0L
        selectedOrderItem = ""; selectedOrderName = ""; originalMenuId = -1; originalMenuTitle = ""; previousPageFingerprint = ""
    }

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        val frame = captureFrame() ?: return@handler
        val now = System.nanoTime()
        if (phase == Phase.STOPPED) return@handler
        if (deadline > 0L && now > deadline && phase !in setOf(Phase.PAGE, Phase.NEXT_PAGE)) { phase = Phase.STOPPED; return@handler }
        if (now < nextActionAt) return@handler
        val menu = frame.snapshot.menu
        when (phase) {
            Phase.OPEN_MENU -> if (menu == null && command(frame, "orders")) {
                phase = Phase.WAIT_MENU; deadline = now + 8_000_000_000L
            }
            Phase.WAIT_MENU -> {
                if (menu != null && MenuClassifier.classify(menu) == MenuKind.ORDER) {
                    originalMenuId = menu.menuId; originalMenuTitle = menu.normalizedTitle; phase = Phase.OPEN_ORDER
                }
            }
            Phase.OPEN_ORDER -> {
                val candidate = menu?.slot(orderSlot)
                if (menu == null || MenuClassifier.classify(menu) != MenuKind.ORDER || candidate == null || candidate.itemId.isBlank()) {
                    phase = Phase.STOPPED
                } else {
                    selectedOrderItem = candidate.itemId
                    selectedOrderName = candidate.name
                    previousPageFingerprint = menuFingerprint(menu)
                    if (click(frame, candidate.index)) { phase = Phase.WAIT_ORDER; deadline = now + 5_000_000_000L }
                }
            }
            Phase.WAIT_ORDER -> {
                if (menu != null && (menu.menuId != originalMenuId || menu.normalizedTitle != originalMenuTitle) &&
                    menuFingerprint(menu) != previousPageFingerprint) phase = Phase.OPEN_COLLECT
            }
            Phase.OPEN_COLLECT -> {
                val current = menu ?: run { phase = Phase.STOPPED; return@handler }
                val collect = current.slots.firstOrNull { slot ->
                    val label = slot.searchableText
                    listOf("collect", "claim", "take items", "receive").any(label::contains)
                }
                if (collect == null) { phase = Phase.STOPPED; return@handler }
                if (click(frame, collect.index)) {
                    previousPageFingerprint = menuFingerprint(current)
                    phase = Phase.WAIT_COLLECT
                    deadline = now + 5_000_000_000L
                    nextActionAt = now + (delaySeconds * 1_000_000_000.0).toLong()
                }
            }
            Phase.WAIT_COLLECT -> {
                if (menu != null && menuFingerprint(menu) != previousPageFingerprint && hasDropButton(menu)) {
                    phase = Phase.PAGE; deadline = 0L; emptyPageFlips = 0
                }
            }
            Phase.PAGE -> {
                val current = menu ?: run { phase = Phase.STOPPED; return@handler }
                val itemCount = current.slots.filter { it.index in 0..44 }.sumOf { it.count }
                if (itemCount <= 0) { phase = Phase.NEXT_PAGE; return@handler }
                val drop = current.slots.firstOrNull { slot -> slot.index >= 45 &&
                    (slot.searchableText == "drop" || slot.searchableText.startsWith("drop ") || slot.searchableText.contains("drop page"))
                }
                if (drop == null) { phase = Phase.STOPPED; return@handler }
                previousPageFingerprint = menuFingerprint(current)
                if (click(frame, drop.index)) {
                    pagesDropped++
                    phase = Phase.WAIT_PAGE
                    deadline = now + 4_000_000_000L
                }
            }
            Phase.WAIT_PAGE -> {
                val changed = menu == null || menuFingerprint(menu) != previousPageFingerprint
                if (changed) {
                    if (pagesDropped >= pages) phase = Phase.STOPPED
                    else { phase = Phase.PAGE; nextActionAt = now + (delaySeconds * 1_000_000_000.0).toLong() }
                }
            }
            Phase.NEXT_PAGE -> {
                val current = menu ?: run { phase = Phase.STOPPED; return@handler }
                val next = current.slots.firstOrNull { slot ->
                    val label = slot.searchableText
                    label.contains("next page") || label.contains("next") || label == ">"
                }
                if (next == null || emptyPageFlips >= 2) phase = Phase.STOPPED
                else {
                    previousPageFingerprint = menuFingerprint(current)
                    if (click(frame, next.index)) {
                        emptyPageFlips++
                        phase = Phase.PAGE
                        nextActionAt = now + 250_000_000L
                    }
                }
            }
            Phase.STOPPED -> Unit
        }
    }

    private fun hasDropButton(menu: dev.donut.addon.expansion.automation.core.MenuSnapshot): Boolean =
        menu.slots.any { slot -> slot.index >= 45 && (slot.searchableText == "drop" || slot.searchableText.contains("drop page")) }

    private fun menuFingerprint(menu: dev.donut.addon.expansion.automation.core.MenuSnapshot): String =
        menu.slots.joinToString("|") { it.fingerprint }
}
