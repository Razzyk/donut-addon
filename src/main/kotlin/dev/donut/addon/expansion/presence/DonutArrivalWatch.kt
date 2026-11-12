package dev.donut.addon.expansion.presence

import dev.donut.addon.AddonCategories
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.notification
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.network.chat.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import net.minecraft.client.player.RemotePlayer

/** Alerts or takes configured actions when a new non-ignored player enters the loaded world. */
class DonutArrivalWatch : DonutPresenceModule("Donut Arrival Watch") {
    private enum class NoticeStyle(override val tag: String) : Tagged {
        CHAT("Chat"), TOAST("Toast"), BOTH("Both")
    }

    private val requestedModules by text("Modules To Toggle", "").doNotIncludeAlways()
    private val notifyMode by enumChoice("Notification", NoticeStyle.BOTH)
    private val disconnectAfterDetection by boolean("Disconnect", true)
    private val disableThisModule by boolean("Disable After Detection", true)
    private val whitelist by text("Player Whitelist", "").doNotIncludeAlways()
    private val sendWebhook by boolean("Webhook", false)
    private val webhookAddress by text("Webhook URL", "").doNotIncludeAlways()
    private val pingDiscord by boolean("Ping Discord User", false)
    private val discordUserId by text("Discord User ID", "").doNotIncludeAlways()
    private val panicTransfer by boolean("Panic Pay", false).doNotIncludeAlways()
    private val transferTarget by text("Pay Target", "").doNotIncludeAlways()
    private val transferAmount by text("Pay Amount", "").doNotIncludeAlways()

    private val entryTracker = NearbyPlayerEdges()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build()
    private var executor: ScheduledExecutorService? = null
    private val runGuard = PresenceRunGuard()
    private var taskToken: Any? = null

    init {
        literalDescription { "Notifies and runs configured actions when a non-whitelisted player appears nearby." }
    }

    override fun onEnabled() { onDisabled() }
    override fun onDisabled() {
        runGuard.cancel()
        taskToken = null
        executor?.shutdownNow()
        executor = null
        entryTracker.clear()
    }

    internal override fun shutdownPresence() {
        onDisabled()
        http.shutdownNow()
    }

    private fun taskExecutor(): ScheduledExecutorService = executor ?: Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "Donut arrival actions").apply { isDaemon = true }
    }.also { executor = it }

    private fun currentTaskToken(): Any = taskToken ?: runGuard.begin().also { taskToken = it }

    @Suppress("unused")
    private val scan = handler<GameTickEvent> {
        val self = mc.player
        val level = mc.level
        val connection = mc.connection
        if (self == null || level == null || connection == null) {
            entryTracker.clear()
            return@handler
        }
        val ignored = parsedWhitelist()
        val selfName = self.gameProfile.name.lowercase(Locale.ROOT)
        val others = level.players().asSequence()
            .filterIsInstance<RemotePlayer>()
            .filter { it.uuid != self.uuid && !it.gameProfile.name.equals(selfName, true) }
            .map { it.gameProfile.name }
            .filter { it.lowercase(Locale.ROOT) != "freecamera" && it.lowercase(Locale.ROOT) !in ignored }
            .toList()
        val entered = entryTracker.update(level, others)
        if (entered.isEmpty()) return@handler
        handleEntry(entered, connection)
    }

    private fun parsedWhitelist(): Set<String> = whitelist.split(',', '\n', ';')
        .map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotEmpty).toSet()

    private fun handleEntry(names: List<String>, observedConnection: ClientPacketListener) {
        val joinedNames = names.joinToString(", ")
        if (notifyMode == NoticeStyle.CHAT || notifyMode == NoticeStyle.BOTH) {
            chat("Player${if (names.size == 1) "" else "s"} detected: $joinedNames", this)
        }
        if (notifyMode == NoticeStyle.TOAST || notifyMode == NoticeStyle.BOTH) {
            notification("Donut Arrival Watch", "$joinedNames entered the world", NotificationEvent.Severity.INFO)
        }
        toggleSelectedModules()
        if (panicTransfer) runPanicPay()
        if (disableThisModule) enabled = false
        // The detection's requested actions survive its own auto-disable, but not a world change or teardown.
        if (sendWebhook) submitWebhook(names)
        if (disconnectAfterDetection) scheduleDisconnect(observedConnection, names)
    }

    private fun toggleSelectedModules() {
        val requested = requestedModules.split(',').map { it.trim() }.filter(String::isNotEmpty)
        if (requested.isEmpty()) return
        val targets = ModuleManager.filter { module ->
            module !== this && requested.any { it.equals(module.name, ignoreCase = true) || module.aliases.any { alias -> it.equals(alias, true) } }
        }.distinct()
        targets.forEach { it.enabled = !it.enabled }
    }

    private fun runPanicPay() {
        val target = transferTarget.trim()
        val amount = transferAmount.trim()
        if (!target.matches(Regex("\\.?[A-Za-z0-9_]{1,16}")) || !amount.matches(Regex("[0-9]+(?:\\.[0-9]+)?[kKmMbBtT]?"))) {
            chat("Panic Pay needs a valid player name and amount.", this)
            return
        }
        val connection = mc.connection ?: return
        val command = "pay $target $amount"
        connection.sendCommand(command)
        chat("Panic Pay sent $amount to $target.", this)
    }

    private fun scheduleDisconnect(observedConnection: ClientPacketListener, names: List<String>) {
        val token = currentTaskToken()
        val observedSession = observedConnection.connection
        val observedWorld = mc.level
        val observedPlayer = mc.player
        taskExecutor().schedule({
            if (!runGuard.accepts(token)) return@schedule
            mc.execute {
                if (!runGuard.accepts(token) || mc.connection !== observedConnection ||
                    observedConnection.connection !== observedSession || mc.level !== observedWorld || mc.player !== observedPlayer ||
                    !observedSession.isConnected || observedWorld == null
                ) return@execute
                observedWorld.disconnect(Component.literal("Player detected: ${names.joinToString(", ")}"))
            }
        }, 500, TimeUnit.MILLISECONDS)
    }

    private fun submitWebhook(names: List<String>) {
        val address = webhookAddress.trim()
        val uri = runCatching { URI(address) }.getOrNull()
        if (uri?.host == null || uri.scheme !in setOf("https", "http")) {
            chat("Webhook URL is invalid.", this)
            return
        }
        val pingId = discordUserId.trim().takeIf { pingDiscord && it.matches(Regex("[0-9]{5,30}")) }
        val content = if (pingId != null) "<@$pingId> Player alert" else "Player alert"
        val serverName = mc.currentServer?.ip ?: "Unknown Server"
        val timestamp = System.currentTimeMillis() / 1_000
        val transfer = if (panicTransfer && transferTarget.isNotBlank() && transferAmount.isNotBlank())
            "\n**Panic Pay:** Target ${markdownEscape(transferTarget.trim())}, amount ${markdownEscape(transferAmount.trim())}" else ""
        val description = "Player(s) detected in the world!\n\n**Players:** ${markdownEscape(names.joinToString(", "))}\n" +
            "**Server:** ${markdownEscape(serverName)}\n**Time:** <t:$timestamp:R>$transfer"
        val body = "{\"content\":\"${jsonEscape(content)}\",\"embeds\":[{\"title\":\"Donut player alert\",\"description\":\"${jsonEscape(description)}\",\"color\":15158332}]}"
        val token = currentTaskToken()
        taskExecutor().execute {
            if (!runGuard.accepts(token)) return@execute
            runCatching {
                val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build()
                val response = http.send(request, HttpResponse.BodyHandlers.discarding())
                if (response.statusCode() !in 200..299) throw IllegalStateException("HTTP ${response.statusCode()}")
            }.onFailure { failure ->
                mc.execute {
                    if (runGuard.accepts(token) && mc.player != null) {
                        chat("Webhook request failed (${failure.javaClass.simpleName}).", this)
                    }
                }
            }
        }
    }

    private fun jsonEscape(value: String): String = buildString(value.length + 16) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
    }

    private fun markdownEscape(value: String): String = buildString(value.length + 8) {
        value.forEach { char ->
            if (char == '\\' || char == '*' || char == '_' || char == '~' || char == '`' || char == '|') append('\\')
            append(char)
        }
    }
}
