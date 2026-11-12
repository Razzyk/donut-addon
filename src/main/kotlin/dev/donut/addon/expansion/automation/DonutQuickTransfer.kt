package dev.donut.addon.expansion.automation

import dev.donut.addon.expansion.automation.core.MarketRules
import dev.donut.addon.expansion.automation.core.MenuClassifier
import dev.donut.addon.expansion.automation.core.MenuKind
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentLinkedQueue

/** One-shot sell-and-pay flow. The editable recipient is session-only; queued transfer state is reset on session changes. */
class DonutQuickTransfer : DonutAutomationModule("Donut Quick Transfer") {
    private enum class Phase { SELL_START, SELL_OPEN, SELL_ITEMS, SELL_CONFIRM, SELL_RESULT, BALANCE_START, BALANCE, PAY_START, PAY_CONFIRM, PAY_RESULT, STOPPED }

    private val payToValue = text("Pay To", "Razzy6").doNotIncludeAlways().notPersistent()
    private val sellArmor by boolean("Sell Armor", true)
    private val sellOffhand by boolean("Sell Offhand", true)
    private val protectedItems by textList("Never Sell", mutableListOf())
    private data class IncomingText(val atMillis: Long, val text: String)
    private val incomingText = ConcurrentLinkedQueue<IncomingText>()
    private val skippedTypes = HashMap<String, Int>()
    private val offeredTypes = HashSet<String>()
    private val attemptedSlots = HashSet<String>()
    private var phase = Phase.SELL_START
    private var target = ""
    private var balanceRequestedAt = 0L
    private var paymentRequestedAt = 0L
    private var phaseDeadline = 0L
    private var balance: BigDecimal? = null
    private var paymentAmount: BigDecimal? = null
    private var balanceStep = BigDecimal.ONE
    private var offeredItems = 0
    private var sellAttempts = 0
    private var saleRounds = 0
    private var balanceAttempts = 0
    private var paymentAttempts = 0
    private var lowerBalanceAttempts = 0
    private var beforeCounts: Map<String, Int> = emptyMap()
    private var commandSentAtMillis = 0L
    @Volatile private var rateLimitUntilMillis = 0L
    private var confirmationClickedIdentity: Long? = null
    private var sellMenuIdentity = 0L

    init { literalDescription { "Sells eligible inventory in bounded, reconciled rounds, reads the current balance, and completes a validated payment confirmation." } }

    override fun onAutomationReset() {
        phase = Phase.SELL_START
        target = ""
        balanceRequestedAt = 0L
        paymentRequestedAt = 0L
        phaseDeadline = 0L
        balance = null
        paymentAmount = null
        balanceStep = BigDecimal.ONE
        offeredItems = 0
        sellAttempts = 0
        saleRounds = 0
        balanceAttempts = 0
        paymentAttempts = 0
        lowerBalanceAttempts = 0
        beforeCounts = emptyMap()
        skippedTypes.clear()
        offeredTypes.clear()
        attemptedSlots.clear()
        commandSentAtMillis = 0L
        rateLimitUntilMillis = 0L
        confirmationClickedIdentity = null
        sellMenuIdentity = 0L
        incomingText.clear()
    }

    @Suppress("unused")
    private val packet = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING) return@handler
        val message = (event.packet as? ClientboundSystemChatPacket)?.content?.string ?: return@handler
        Regex("(?i)you need to wait another ([\\d.]+) seconds? to execute a command").find(message)
            ?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { wait ->
                val until = System.currentTimeMillis() + (wait.coerceAtMost(30.0) * 1000.0).toLong() + 100L
                rateLimitUntilMillis = maxOf(rateLimitUntilMillis, until)
            }
        incomingText.offer(IncomingText(System.currentTimeMillis(), message))
    }

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        val frame = captureFrame() ?: return@handler
        if (phase == Phase.STOPPED) return@handler
        if (mc.gui.screen() != null && frame.snapshot.menu == null && frame.snapshot.dialog == null &&
            phase !in setOf(Phase.BALANCE, Phase.PAY_CONFIRM, Phase.PAY_RESULT)) return@handler
        val now = System.nanoTime()
        if (target.isEmpty()) {
            val validated = safePayName(payToValue.get()) ?: run { phase = Phase.STOPPED; return@handler }
            if (validated.removePrefix(".").equals(frame.player.gameProfile.name, ignoreCase = true)) {
                phase = Phase.STOPPED
                return@handler
            }
            target = validated
        }

        when (phase) {
            Phase.SELL_START -> if (frame.snapshot.menu == null && sellAttempts < 3) {
                beforeCounts = inventoryCounts(frame)
                offeredItems = 0
                offeredTypes.clear()
                attemptedSlots.clear()
                if (beforeCounts.isEmpty() || saleRounds >= 6) {
                    phase = Phase.BALANCE_START
                } else if (marketCommand(frame, "sell")) {
                    sellAttempts++
                    phase = Phase.SELL_OPEN
                    phaseDeadline = now + 8_000_000_000L
                }
            } else if (frame.snapshot.menu != null) phase = Phase.STOPPED
            else if (sellAttempts >= 3) phase = Phase.BALANCE_START
            Phase.SELL_OPEN -> {
                val menu = frame.snapshot.menu
                if (menu != null && MenuClassifier.classify(menu) == MenuKind.SELL) {
                    sellMenuIdentity = menu.screenIdentity
                    phase = Phase.SELL_ITEMS
                    phaseDeadline = 0L
                } else if (menu != null) {
                    phase = Phase.STOPPED
                } else if (now > phaseDeadline) {
                    if (sellAttempts < 3) phase = Phase.SELL_START else phase = Phase.BALANCE_START
                }
            }
            Phase.SELL_ITEMS -> {
                val menu = frame.snapshot.menu ?: run { phase = Phase.STOPPED; return@handler }
                if (MenuClassifier.classify(menu) != MenuKind.SELL || menu.screenIdentity != sellMenuIdentity || !menu.cursorEmpty) {
                    phase = Phase.STOPPED
                    return@handler
                }
                val protected = protectedItems.mapNotNull(MarketRules::safeItemId).toSet()
                val playerSlot = frame.menu.slots.firstOrNull { slot ->
                    if (slot.container !== frame.player.inventory || slot.item.isEmpty) return@firstOrNull false
                    val containerSlot = slot.containerSlot
                    val allowed = containerSlot in 0..35 || sellArmor && containerSlot in 36..39 || sellOffhand && containerSlot == 40
                    if (!allowed) return@firstOrNull false
                    val id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(slot.item.item).toString()
                    val snapshotSlot = menu.slot(slot.index)
                    id !in protected && (id !in skippedTypes || (skippedTypes[id] ?: 0) < 2) &&
                        snapshotSlot != null && "${slot.index}:${snapshotSlot.fingerprint}" !in attemptedSlots
                }
                if (playerSlot != null) {
                    val id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(playerSlot.item.item).toString()
                    if (click(frame, playerSlot.index, quickMove = true)) {
                        offeredItems++
                        offeredTypes.add(id)
                        menu.slot(playerSlot.index)?.fingerprint?.let { attemptedSlots.add("${playerSlot.index}:$it") }
                    }
                    return@handler
                }
                if (offeredItems == 0) {
                    closeMenu(frame)
                    phase = Phase.SELL_RESULT
                    phaseDeadline = now + 2_000_000_000L
                    return@handler
                }
                val confirm = menu.slots.firstOrNull { candidate ->
                    val text = candidate.searchableText
                    candidate.itemId.isNotBlank() &&
                        (text.contains("confirm") || text == "sell" || text.contains("sell all")) &&
                        !text.contains("history") && !text.contains("cancel")
                }
                if (confirm == null) { closeMenu(frame); phase = Phase.BALANCE_START; return@handler }
                if (click(frame, confirm.index)) {
                    confirmationClickedIdentity = menu.screenIdentity
                    phase = Phase.SELL_CONFIRM
                    phaseDeadline = now + 8_000_000_000L
                }
            }
            Phase.SELL_CONFIRM -> {
                val menu = frame.snapshot.menu
                val dialog = frame.snapshot.dialog
                if (dialog != null && dialog.searchableText.contains("sell") &&
                    confirmationClickedIdentity != dialog.screenIdentity) {
                    val yes = dialog.widgets.firstOrNull { widget -> widget.kind == "button" &&
                        (widget.label.equals("yes", true) || widget.label.equals("confirm", true) || widget.label.equals("sell", true)) }
                    if (yes == null) { phase = Phase.STOPPED; return@handler }
                    if (pressDialog(frame, yes.index)) confirmationClickedIdentity = dialog.screenIdentity
                } else if (dialog != null) {
                    phase = Phase.STOPPED
                } else if (menu != null && MenuClassifier.classify(menu) == MenuKind.CONFIRMATION &&
                    menu.searchTextContainsAny("sell", "sale") && !menu.searchTextContainsAny("buy", "payment", "pay ")) {
                    val yes = menu.slots.firstOrNull { slot ->
                        slot.itemId.isNotBlank() && (slot.searchableText == "yes" || slot.searchableText == "confirm" ||
                            slot.searchableText.startsWith("confirm ") || slot.searchableText == "sell")
                    }
                    if (yes == null) phase = Phase.STOPPED
                    else if (confirmationClickedIdentity != menu.screenIdentity && click(frame, yes.index)) {
                        confirmationClickedIdentity = menu.screenIdentity
                    }
                } else if (menu == null) {
                    phase = Phase.SELL_RESULT
                    phaseDeadline = now + 5_000_000_000L
                } else if (menu.screenIdentity != sellMenuIdentity && MenuClassifier.classify(menu) != MenuKind.CONFIRMATION) {
                    phase = Phase.STOPPED
                } else if (MenuClassifier.classify(menu) != MenuKind.SELL &&
                    MenuClassifier.classify(menu) != MenuKind.CONFIRMATION && now > phaseDeadline) {
                    phase = Phase.SELL_RESULT
                } else if (now > phaseDeadline && confirmationClickedIdentity == menu.screenIdentity) {
                    phase = Phase.SELL_RESULT
                }
            }
            Phase.SELL_RESULT -> {
                if (frame.snapshot.dialog != null) {
                    phase = Phase.STOPPED
                    return@handler
                }
                if (frame.snapshot.menu != null) {
                    if (now <= phaseDeadline) return@handler
                    if (frame.snapshot.menu.screenIdentity == sellMenuIdentity &&
                        MenuClassifier.classify(frame.snapshot.menu) == MenuKind.SELL) {
                        closeMenu(frame)
                        phaseDeadline = now + 1_000_000_000L
                        return@handler
                    }
                    phase = Phase.STOPPED
                    return@handler
                }
                if (now < phaseDeadline) return@handler
                val after = inventoryCounts(frame)
                val soldAny = beforeCounts.any { (id, count) -> (after[id] ?: 0) < count }
                for ((id, count) in beforeCounts) {
                    if ((after[id] ?: 0) < count) skippedTypes.remove(id)
                    else if (id in offeredTypes) skippedTypes[id] = (skippedTypes[id] ?: 0) + 1
                }
                saleRounds++
                sellAttempts = 0
                confirmationClickedIdentity = null
                sellMenuIdentity = 0L
                if (!soldAny && offeredItems > 0) {
                    for (id in offeredTypes) if ((skippedTypes[id] ?: 0) >= 2) skippedTypes[id] = 2
                }
                phase = if (saleRounds < 6 && inventoryCounts(frame).isNotEmpty()) Phase.SELL_START else Phase.BALANCE_START
                phaseDeadline = 0L
            }
            Phase.BALANCE_START -> {
                if (frame.snapshot.menu != null || frame.snapshot.dialog != null || balanceAttempts >= 3) {
                    phase = Phase.STOPPED
                    return@handler
                }
                val requestAt = System.currentTimeMillis()
                if (marketCommand(frame, "bal")) {
                    balanceAttempts++
                    phase = Phase.BALANCE
                    balanceRequestedAt = requestAt
                    phaseDeadline = now + 6_000_000_000L
                }
            }
            Phase.BALANCE -> {
                val messages = drainIncoming()
                val relevant = messages.filter { it.atMillis >= balanceRequestedAt }
                val parsed = relevant.asSequence().mapNotNull { parseBalance(it.text) }.lastOrNull()
                if (parsed != null && parsed.signum() > 0) {
                    balance = parsed
                    paymentAmount = parsed
                    balanceStep = relevant.asSequence().mapNotNull { displayedBalanceStep(it.text) }.lastOrNull() ?: BigDecimal.ONE
                    phase = Phase.PAY_START
                    phaseDeadline = now + 6_000_000_000L
                } else if (relevant.any(::isRateLimit)) {
                    if (balanceAttempts < 3) phase = Phase.BALANCE_START else phase = Phase.STOPPED
                } else if (now > phaseDeadline) {
                    if (balanceAttempts < 3) phase = Phase.BALANCE_START else phase = Phase.STOPPED
                }
            }
            Phase.PAY_START -> {
                val amount = paymentAmount ?: run { phase = Phase.STOPPED; return@handler }
                val safeTarget = safePayName(target) ?: run { phase = Phase.STOPPED; return@handler }
                if (!safeTarget.equals(target, ignoreCase = true) || amount < BigDecimal.ONE || amount.scale() > 2) {
                    phase = Phase.STOPPED
                    return@handler
                }
                if (frame.snapshot.menu != null || frame.snapshot.dialog != null) { phase = Phase.STOPPED; return@handler }
                val requestAt = System.currentTimeMillis()
                if (paymentAttempts < 4 && marketCommand(frame, "pay $safeTarget ${amount.toPlainString()}")) {
                    paymentAttempts++
                    paymentRequestedAt = requestAt
                    phase = Phase.PAY_CONFIRM
                    phaseDeadline = now + 8_000_000_000L
                } else if (paymentAttempts >= 4) phase = Phase.STOPPED
            }
            Phase.PAY_CONFIRM -> {
                val paymentResponse = pollPaymentResponse()
                if (paymentResponse == PaymentResponse.PAID) { phase = Phase.STOPPED; return@handler }
                if (paymentResponse == PaymentResponse.FAILED) { phase = Phase.STOPPED; return@handler }
                if (paymentResponse == PaymentResponse.RATE_LIMIT) {
                    phase = if (paymentAttempts < 4) Phase.PAY_START else Phase.STOPPED
                    return@handler
                }
                if (paymentResponse == PaymentResponse.INSUFFICIENT) {
                    val current = paymentAmount ?: return@handler
                    lowerBalanceAttempts++
                    val lowered = balance?.subtract(balanceStep.multiply(BigDecimal(lowerBalanceAttempts)))
                        ?.setScale(0, RoundingMode.FLOOR)
                    if (lowerBalanceAttempts > 2 || lowered == null || lowered < BigDecimal.ONE) {
                        phase = Phase.STOPPED
                    } else {
                        paymentAmount = lowered
                        phase = if (paymentAttempts < 4) Phase.PAY_START else Phase.STOPPED
                    }
                    return@handler
                }
                val dialog = frame.snapshot.dialog
                if (dialog != null) {
                    if (!mentionsExactTarget(dialog.searchableText, target) || containsLongerTargetPrefix(dialog.searchableText, target) ||
                        !moneyMatches(dialog.searchableText, paymentAmount ?: return@handler)) {
                        phase = Phase.STOPPED
                        return@handler
                    }
                    val confirm = dialog.widgets.firstOrNull { it.kind == "button" &&
                        (it.label.equals("confirm", true) || it.label.equals("yes", true) || it.label.equals("pay", true)) }
                    if (confirm == null) phase = Phase.STOPPED
                    else if (pressDialog(frame, confirm.index)) { phase = Phase.PAY_RESULT; phaseDeadline = now + 8_000_000_000L }
                    return@handler
                }
                val menu = frame.snapshot.menu ?: run { if (now > phaseDeadline) phase = Phase.STOPPED; return@handler }
                if (MenuClassifier.classify(menu) !in setOf(MenuKind.PAYMENT, MenuKind.CONFIRMATION) ||
                    !paymentMatches(menu, target, paymentAmount ?: return@handler)) {
                    phase = Phase.STOPPED
                    return@handler
                }
                val confirm = menu.slots.firstOrNull { slot ->
                    val text = slot.searchableText
                    slot.itemId.isNotBlank() && (text == "confirm" || text == "yes" || text == "pay" || text.startsWith("confirm "))
                }
                if (confirm == null) phase = Phase.STOPPED
                else if (click(frame, confirm.index)) { phase = Phase.PAY_RESULT; phaseDeadline = now + 8_000_000_000L }
            }
            Phase.PAY_RESULT -> {
                when (pollPaymentResponse()) {
                    PaymentResponse.PAID -> phase = Phase.STOPPED
                    PaymentResponse.FAILED -> phase = Phase.STOPPED
                    PaymentResponse.RATE_LIMIT -> phase = if (paymentAttempts < 4) Phase.PAY_START else Phase.STOPPED
                    PaymentResponse.INSUFFICIENT -> {
                        val current = paymentAmount ?: return@handler
                        lowerBalanceAttempts++
                        val lowered = balance?.subtract(balanceStep.multiply(BigDecimal(lowerBalanceAttempts)))
                            ?.setScale(0, RoundingMode.FLOOR)
                        if (lowerBalanceAttempts > 2 || lowered == null || lowered < BigDecimal.ONE || paymentAttempts >= 4) phase = Phase.STOPPED
                        else { paymentAmount = lowered; phase = Phase.PAY_START }
                    }
                    PaymentResponse.PENDING -> if (now > phaseDeadline) phase = Phase.STOPPED
                }
            }
            Phase.STOPPED -> Unit
        }
    }

    private fun parseBalance(text: String): BigDecimal? {
        val clean = text.replace(Regex("(?i)§[0-9A-FK-ORX]"), "")
        if (!Regex("(?i)(you have|balance)").containsMatchIn(clean)) return null
        val token = Regex("\\$?([0-9][0-9,]*(?:\\.[0-9]{1,2})?\\s*[km]?)", RegexOption.IGNORE_CASE)
            .find(clean)?.value ?: return null
        return parseMoney(token)?.takeIf { it.signum() >= 0 }
    }

    private fun safePayName(value: String): String? = value.trim()
        .takeIf { Regex("\\.?[A-Za-z0-9_]{1,16}").matches(it) }

    private fun paymentMatches(
        menu: dev.donut.addon.expansion.automation.core.MenuSnapshot,
        recipient: String,
        amount: BigDecimal,
    ): Boolean {
        val text = (listOf(menu.title) + menu.slots.flatMap { listOf(it.name) + it.lore }).joinToString(" ").lowercase()
        if (!mentionsExactTarget(text, recipient) || containsLongerTargetPrefix(text, recipient)) return false
        return moneyMatches(text, amount)
    }

    private enum class PaymentResponse { PENDING, PAID, FAILED, RATE_LIMIT, INSUFFICIENT }

    private fun pollPaymentResponse(): PaymentResponse {
        var result = PaymentResponse.PENDING
        while (true) {
            val notice = incomingText.poll() ?: break
            if (notice.atMillis < paymentRequestedAt) continue
            val line = notice.text.replace(Regex("(?i)§[0-9A-FK-ORX]"), "").lowercase()
            if (isRateLimit(notice)) {
                result = PaymentResponse.RATE_LIMIT
            } else if (line.contains("not enough money") || line.contains("not enough")) {
                result = PaymentResponse.INSUFFICIENT
            } else if (line.contains("not online") || line.contains("not found") ||
                line.contains("invalid") || line.contains("cannot pay") || line.contains("can't pay")) {
                result = PaymentResponse.FAILED
            } else if (line.contains("you paid") && mentionsExactTarget(line, target) &&
                moneyMatches(line, paymentAmount ?: return PaymentResponse.PENDING)) {
                result = PaymentResponse.PAID
            }
        }
        return result
    }

    private fun marketCommand(frame: LiveAutomationFrame, value: String): Boolean {
        val now = System.currentTimeMillis()
        if (now < rateLimitUntilMillis || now - commandSentAtMillis < 350L) return false
        if (!command(frame, value)) return false
        commandSentAtMillis = now
        return true
    }

    private fun drainIncoming(): List<IncomingText> = buildList {
        while (true) add(incomingText.poll() ?: break)
    }

    private fun isRateLimit(notice: IncomingText): Boolean =
        Regex("(?i)you need to wait another [\\d.]+ seconds? to execute a command").containsMatchIn(notice.text)

    private fun mentionsExactTarget(text: String, rawTarget: String): Boolean {
        val targetName = rawTarget.removePrefix(".").lowercase()
        return Regex("[a-z0-9_]+", RegexOption.IGNORE_CASE).findAll(text).any { it.value.equals(targetName, true) }
    }

    private fun containsLongerTargetPrefix(text: String, rawTarget: String): Boolean {
        val targetName = rawTarget.removePrefix(".").lowercase()
        return Regex("[a-z0-9_]+", RegexOption.IGNORE_CASE).findAll(text)
            .any { it.value.length > targetName.length && it.value.startsWith(targetName, true) }
    }

    private fun inventoryCounts(frame: LiveAutomationFrame): Map<String, Int> {
        val counts = HashMap<String, Int>()
        val protected = protectedItems.mapNotNull(MarketRules::safeItemId).toSet()
        for (index in 0..40) {
            val allowed = index in 0..35 || sellArmor && index in 36..39 || sellOffhand && index == 40
            if (!allowed) continue
            val stack = frame.player.inventory.getItem(index)
            if (stack.isEmpty) continue
            val id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item).toString()
            if (id in protected || (skippedTypes[id] ?: 0) >= 2) continue
            counts[id] = (counts[id] ?: 0) + stack.count
        }
        return counts
    }

    private fun displayedBalanceStep(text: String): BigDecimal? {
        val clean = text.replace(Regex("(?i)§[0-9A-FK-ORX]"), "")
        if (!Regex("(?i)(you have|balance)").containsMatchIn(clean)) return null
        val token = Regex("\\$?([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([km]?)", RegexOption.IGNORE_CASE)
            .find(clean)?.value ?: return null
        val number = Regex("[0-9][0-9,]*(?:\\.[0-9]+)?").find(token)?.value ?: return null
        val decimals = number.substringAfter('.', "").length
        val multiplier = when (token.trim().lastOrNull()?.lowercaseChar()) {
            'k' -> BigDecimal("1000")
            'm' -> BigDecimal("1000000")
            else -> BigDecimal.ONE
        }
        return BigDecimal.ONE.scaleByPowerOfTen(-decimals).multiply(multiplier)
    }

    private fun moneyMatches(text: String, amount: BigDecimal): Boolean {
        val shown = Regex("\\$?([0-9][0-9,]*(?:\\.[0-9]{1,2})?\\s*[km]?)", RegexOption.IGNORE_CASE)
            .findAll(text).mapNotNull { parseMoney(it.value) }
        return shown.any { it.compareTo(amount) == 0 }
    }

    private fun parseMoney(value: String): BigDecimal? {
        val match = Regex("(?i)^\\s*\\$?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*([km])?\\s*$").matchEntire(value)
            ?: return null
        val base = match.groupValues[1].replace(",", "").toBigDecimalOrNull() ?: return null
        val multiplier = when (match.groupValues[2].lowercase()) {
            "k" -> BigDecimal("1000")
            "m" -> BigDecimal("1000000")
            else -> BigDecimal.ONE
        }
        return base.multiply(multiplier).stripTrailingZeros()
    }
}
