package dev.donut.addon.expansion.automation

import dev.donut.addon.expansion.automation.core.MarketRules
import dev.donut.addon.expansion.automation.core.VisitorNames
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.player.RemotePlayer
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket
import net.minecraft.world.item.Items
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Prefix-driven visitor workflow with bounded waits, local join priority and separately gated actions. */
class DonutVisitorGuard : DonutAutomationModule("Donut Visitor Guard") {
    private enum class RequestCommand(override val tag: String) : Tagged { TPAHERE("tpahere"), TPA("tpa") }
    private enum class NameOrder(override val tag: String) : Tagged { ASC("A to Z"), DESC("Z to A"), RANDOM("Random") }

    private val requestCommand by enumChoice("Command", RequestCommand.TPAHERE)
    private val order by enumChoice("Order", NameOrder.ASC)
    private val letters by text("Letters", "").doNotIncludeAlways()
    private val startLetter by text("Start Letter", "").doNotIncludeAlways()
    private val includeNumbers by boolean("Include 0-9 and _", false)
    private val bedrockOnly by boolean("Bedrock Players Only", false)
    private val repeatForever by boolean("Repeat Forever", true)
    private val delaySeconds by float("Delay (s)", 1.0f, 0.25f..30.0f)
    private val skipOffline by boolean("Skip Offline Players", true)
    private val joinersFirst by boolean("New Players First", false)
    private val neverRequest by textList("Never Request", mutableListOf<String>())
    private val sendMinutes by float("Send For (min, 0 = always)", 0.0f, 0.0f..240.0f)
    private val restMinutes by float("Then Rest (min)", 0.0f, 0.0f..240.0f)
    private val pauseRadius by float("Pause Radius", 6.0f, 1.0f..64.0f)
    private val resumeAfter by float("Resume After (s)", 2.5f, 0.0f..60.0f)
    private val attackVisitors by boolean("Attack", true)
    private val attackRadius by float("Attack Radius", 1.5f, 0.5f..6.0f)
    private val swordSlot by int("Sword Slot (0 = auto)", 0, 0..9)
    private val eatBelow by float("Eat Below (hearts, 0 = never)", 5.0f, 0.0f..10.0f)
    private val appleSlot by int("Apple Slot (0 = auto)", 0, 0..9)
    private val eatCooldown by float("Eat Cooldown (s)", 2.5f, 0.5f..30.0f)
    private val enchantedFirst by boolean("Enchanted Apples First", false)
    private val autoRespawn by boolean("Auto Respawn", true)
    private val respawnCommand by text("Respawn Command", "").doNotIncludeAlways()
    private val autoSell by boolean("Auto Sell", true)
    private val neverSell by textList("Never Sell", mutableListOf("diamond_sword", "golden_apple", "totem_of_undying"))

    private data class CompletionResult(val requestId: Int, val names: List<String>)
    private val completions = ConcurrentLinkedQueue<CompletionResult>()
    private val joinQueue = ArrayDeque<String>()
    private val knownOnline = hashSetOf<String>()
    private val requestedNames = hashSetOf<String>()
    private val seller = SellWorkflow()
    private var pendingRequestId = 0
    private var waitingForCompletionUntil = 0L
    private var activePrefix = ""
    private var candidates = ArrayDeque<String>()
    private var prefixList = emptyList<String>()
    private var prefixIndex = 0
    private var cycleStart = 0L
    private var restUntil = 0L
    private var lastSendAt = 0L
    private var lastNearAt = 0L
    private var lastAttackTick = 0L
    private var lastEatAt = 0L
    private var pendingAppleSlot = -1
    private var restoreSlot = -1
    private var restoreAfterTick = 0L
    private var deathCommandPending = false
    private var deathCommandAt = 0L
    private var lastInventoryActionAt = 0L
    private var stopped = false

    init { literalDescription { "Cycles validated player completions, pauses near visitors, and runs configured visitor actions." } }

    override fun onAutomationReset() {
        completions.clear(); joinQueue.clear(); knownOnline.clear(); requestedNames.clear()
        pendingRequestId = 0; waitingForCompletionUntil = 0L; activePrefix = ""; candidates.clear()
        prefixList = emptyList(); prefixIndex = 0; cycleStart = 0L; restUntil = 0L; lastSendAt = 0L
        lastNearAt = 0L; lastAttackTick = 0L; lastEatAt = 0L; pendingAppleSlot = -1; restoreSlot = -1
        restoreAfterTick = 0L; deathCommandPending = false; deathCommandAt = 0L; lastInventoryActionAt = 0L
        stopped = false; seller.reset()
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING) return@handler
        val packet = event.packet as? ClientboundCommandSuggestionsPacket ?: return@handler
        if (packet.id != pendingRequestId || pendingRequestId == 0) return@handler
        val names = packet.toSuggestions().list.map { it.text }
        completions.offer(CompletionResult(packet.id, names))
    }

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        val frame = captureFrame() ?: return@handler
        if (stopped) return@handler
        val now = System.nanoTime()
        if (cycleStart == 0L) {
            cycleStart = now
            prefixList = configuredPrefixes()
            prefixIndex = startIndex(prefixList)
        }

        updateJoiners(frame.connection)
        if (handleDeath(frame, now)) return@handler
        if (restoreSlot >= 0 && frame.snapshot.tick >= restoreAfterTick) {
            val slot = restoreSlot; restoreSlot = -1
            if (frame.player.inventory.selectedSlot != slot) selectHotbar(frame, slot)
        }

        val visitors = mc.level?.players().orEmpty().filterIsInstance<RemotePlayer>()
            .filter { it.uuid != frame.player.uuid }
            .map { it to frame.player.distanceTo(it).toDouble() }
            .sortedBy { it.second }
        val near = visitors.firstOrNull { it.second <= pauseRadius }
        if (near != null) lastNearAt = now
        val pausedForVisitor = near != null || (lastNearAt > 0L && now - lastNearAt < resumeAfter * 1_000_000_000.0)
        if (near != null && !pausedForVisitor) lastNearAt = now

        if (eatStep(frame, now)) return@handler
        if (attackVisitors && fight(frame, visitors, now)) return@handler
        if (autoSell && !pausedForVisitor && seller.tick(this, frame, neverSell)) return@handler
        if (pausedForVisitor || restStep(now)) return@handler
        if (pendingRequestId != 0) {
            val result = completions.poll()
            if (result != null && result.requestId == pendingRequestId) {
                pendingRequestId = 0
                waitingForCompletionUntil = 0L
                candidates = orderNames(result.names, activePrefix)
            } else if (now > waitingForCompletionUntil) {
                pendingRequestId = 0
                waitingForCompletionUntil = 0L
                candidates.clear()
            } else return@handler
        }

        if (candidates.isNotEmpty()) {
            val name = nextCandidate(frame) ?: run { candidates.clear(); return@handler }
            if (now - lastSendAt < delaySeconds * 1_000_000_000.0) return@handler
            val commandName = if (requestCommand == RequestCommand.TPA) "tpa" else "tpahere"
            if (command(frame, "$commandName $name")) {
                lastSendAt = now
                requestedNames += normalizeName(name)
                return@handler
            }
        }

        if (pendingRequestId == 0 && candidates.isEmpty() && mc.gui.screen() == null) requestNextPrefix(frame, now)
    }

    private fun configuredPrefixes(): List<String> {
        val values = VisitorNames.parsePrefixes(letters, includeNumbers).ifEmpty { VisitorNames.parsePrefixes("", includeNumbers) }
        val ordered = when (order) {
            NameOrder.ASC -> values
            NameOrder.DESC -> values.reversed()
            NameOrder.RANDOM -> values.shuffled()
        }
        return ordered
    }

    private fun startIndex(values: List<String>): Int {
        val start = startLetter.trim().lowercase(Locale.ROOT).firstOrNull() ?: return 0
        val found = values.indexOf(start.toString())
        return found.takeIf { it >= 0 } ?: 0
    }

    private fun requestNextPrefix(frame: LiveAutomationFrame, now: Long) {
        if (prefixList.isEmpty()) return
        if (prefixIndex >= prefixList.size) {
            if (!repeatForever) { stopped = true; enabled = false; return }
            prefixIndex = 0
            requestedNames.clear()
            candidates.clear()
            if (order == NameOrder.RANDOM) prefixList = configuredPrefixes()
        }
        activePrefix = prefixList[prefixIndex]
        val actualPrefix = if (bedrockOnly) ".${activePrefix}" else activePrefix
        val verb = if (requestCommand == RequestCommand.TPA) "tpa" else "tpahere"
        val id = NEXT_SUGGESTION_ID.getAndIncrement()
        pendingRequestId = id
        waitingForCompletionUntil = now + 5_000_000_000L
        if (!requestSuggestions(frame, id, "/$verb $actualPrefix")) {
            pendingRequestId = 0; waitingForCompletionUntil = 0L; advancePrefix()
        }
    }

    private fun orderNames(values: List<String>, prefix: String): ArrayDeque<String> {
        val normalizedPrefix = prefix.lowercase(Locale.ROOT)
        val unique = linkedMapOf<String, String>()
        values.map { it.trim().substringAfterLast(' ').trim().trim('"', '\'') }
            .forEach { raw ->
                val bedrockName = raw.startsWith('.')
                if (bedrockOnly != bedrockName) return@forEach
                val name = if (bedrockName) raw.drop(1) else raw
                if (MarketRules.safePlayerName(name) == null || !name.lowercase(Locale.ROOT).startsWith(normalizedPrefix)) return@forEach
                if (name.equals(mc.player?.gameProfile?.name, true)) return@forEach
                val key = normalizeName(name)
                if (key !in neverRequest.map { normalizeName(it) }.toSet()) unique.putIfAbsent(key, raw)
            }
        val sorted = when (order) {
            NameOrder.ASC -> unique.values.sortedBy { normalizeName(it) }
            NameOrder.DESC -> unique.values.sortedByDescending { normalizeName(it) }
            NameOrder.RANDOM -> unique.values.shuffled()
        }
        return ArrayDeque(sorted)
    }

    private fun nextCandidate(frame: LiveAutomationFrame): String? {
        if (joinersFirst && joinQueue.isNotEmpty()) {
            while (joinQueue.isNotEmpty()) {
                val candidate = joinQueue.removeFirst()
                val key = normalizeName(candidate)
                if (key in requestedNames || key in neverRequest.map { normalizeName(it) }.toSet()) continue
                if (skipOffline && key !in frame.snapshot.onlineNames.map(::normalizeName)) continue
                return candidate
            }
        }
        while (candidates.isNotEmpty()) {
            val raw = candidates.removeFirst()
            val key = normalizeName(raw)
            if (key in requestedNames) continue
            if (key in neverRequest.map { normalizeName(it) }.toSet()) continue
            if (skipOffline && key !in frame.snapshot.onlineNames.map(::normalizeName)) continue
            return raw
        }
        advancePrefix()
        return null
    }

    private fun advancePrefix() {
        prefixIndex++
        pendingRequestId = 0
        waitingForCompletionUntil = 0L
        candidates.clear()
    }

    private fun updateJoiners(connection: ClientPacketListener) {
        val now = connection.onlinePlayers.mapNotNull { it.profile?.name }.map(::normalizeName).toSet()
        if (joinersFirst) (now - knownOnline).forEach { joinQueue.addLast(it) }
        knownOnline.clear(); knownOnline.addAll(now)
    }

    private fun restStep(now: Long): Boolean {
        if (sendMinutes <= 0f || restMinutes <= 0f) return false
        val sendSpan = (sendMinutes * 60_000_000_000.0).toLong()
        val restSpan = (restMinutes * 60_000_000_000.0).toLong()
        if (restUntil > now) return true
        if (now - cycleStart >= sendSpan) { restUntil = now + restSpan; cycleStart = restUntil; return true }
        return false
    }

    private fun fight(frame: LiveAutomationFrame, visitors: List<Pair<RemotePlayer, Double>>, now: Long): Boolean {
        if (mc.gui.screen() != null || frame.player.isDeadOrDying) return false
        val target = visitors.firstOrNull { it.second <= attackRadius }?.first ?: return false
        if (frame.snapshot.tick - lastAttackTick < 8) return false
        val slot = when {
            swordSlot in 1..9 -> (swordSlot - 1).takeIf {
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(frame.player.inventory.getItem(it).item).path.endsWith("sword")
            } ?: return false
            else -> bestSwordSlot(frame) ?: return false
        }
        if (frame.player.inventory.selectedSlot != slot) {
            if (selectHotbar(frame, slot)) lastAttackTick = frame.snapshot.tick
            return true
        }
        val didAttack = attack(frame, target, attackRadius.toDouble())
        if (didAttack) lastAttackTick = frame.snapshot.tick
        return didAttack
    }

    private fun bestSwordSlot(frame: LiveAutomationFrame): Int? = (0..8).firstOrNull { slot ->
        val stack = frame.player.inventory.getItem(slot)
        !stack.isEmpty && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item).path.endsWith("sword")
    }

    private fun eatStep(frame: LiveAutomationFrame, now: Long): Boolean {
        if (eatBelow <= 0f || frame.player.health >= eatBelow * 2f || frame.player.health <= 0f ||
            mc.gui.screen() != null || now - lastEatAt < eatCooldown * 1_000_000_000.0) return false
        if (pendingAppleSlot < 0) pendingAppleSlot = findAppleSlot(frame) ?: return false
        if (frame.player.inventory.selectedSlot != pendingAppleSlot) {
            if (restoreSlot < 0) restoreSlot = frame.player.inventory.selectedSlot
            if (selectHotbar(frame, pendingAppleSlot)) return true
            return false
        }
        if (useMainHand(frame)) {
            lastEatAt = now
            restoreAfterTick = frame.snapshot.tick + 32
            pendingAppleSlot = -1
            return true
        }
        return false
    }

    private fun findAppleSlot(frame: LiveAutomationFrame): Int? {
        val candidates = (0..8).mapNotNull { slot ->
            val stack = frame.player.inventory.getItem(slot)
            if (stack.item != Items.GOLDEN_APPLE && stack.item != Items.ENCHANTED_GOLDEN_APPLE) null
            else slot to (stack.item == Items.ENCHANTED_GOLDEN_APPLE)
        }
        if (appleSlot in 1..9) return candidates.firstOrNull { it.first == appleSlot - 1 }?.first
        return candidates.sortedByDescending { (_, enchanted) -> enchanted == enchantedFirst }.firstOrNull()?.first
    }

    private fun handleDeath(frame: LiveAutomationFrame, now: Long): Boolean {
        if (frame.player.isDeadOrDying) {
            if (autoRespawn && frame.snapshot.tick % 12L == 0L) respawn(frame)
            deathCommandPending = true
            deathCommandAt = now + 1_000_000_000L
            return true
        }
        if (deathCommandPending && now >= deathCommandAt) {
            deathCommandPending = false
            val text = respawnCommand.trim()
            if (text.isNotEmpty()) userCommand(frame, text)
            return text.isNotEmpty()
        }
        return false
    }

    private fun normalizeName(value: String): String = value.trim().removePrefix(".").lowercase(Locale.ROOT)

    companion object { private val NEXT_SUGGESTION_ID = AtomicInteger(70_000) }
}
