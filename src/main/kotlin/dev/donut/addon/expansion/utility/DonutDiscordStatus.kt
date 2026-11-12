package dev.donut.addon.expansion.utility

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.client.mc
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/** Publishes the current client context through the user's local Discord IPC connection. */
class DonutDiscordStatus : DonutUtilityModule(
    "Donut Discord Status",
    "Shows your current Minecraft context in Discord using an application ID you provide."
) {
    private val applicationId by text("Application ID", "").doNotIncludeAlways()
    private val showServerAddress by boolean("Show Server Address", true)
    private val includeButton by boolean("Community Button", false)
    private val buttonLabel by text("Button Label", "Community").doNotIncludeAlways().visibleWhen { includeButton }
    private val buttonUrl by text("Button URL", "").doNotIncludeAlways().visibleWhen { includeButton }

    @Volatile private var desiredId = ""
    @Volatile private var desiredActivity = ""
    @Volatile private var worker: PresenceLink? = null
    private var currentSession: Any? = null
    private var joinedAt = 0L

    override fun onEnabled() = startSession()

    override fun onUtilitySessionStarted(world: net.minecraft.client.multiplayer.ClientLevel) = startSession()

    private fun startSession() {
        currentSession = null
        joinedAt = 0L
        publish()
    }

    override fun onUtilitySessionEnded() {
        worker?.stop()
        worker = null
        desiredActivity = ""
        desiredId = ""
        currentSession = null
    }

    override fun onDisabled() = onUtilitySessionEnded()

    @Suppress("unused")
    private val tick = handler<GameTickEvent> { publish() }

    private fun publish() {
        val app = applicationId.trim().takeIf { it.matches(Regex("[0-9]{17,22}")) }.orEmpty()
        desiredId = app
        val now = System.currentTimeMillis() / 1000L
        val connection = mc.connection
        if (connection !== currentSession) {
            currentSession = connection
            joinedAt = if (connection == null) PROCESS_STARTED else now
        }
        val details = when {
            connection == null -> "In Main Menu"
            mc.isLocalServer -> "Singleplayer"
            mc.currentServer == null -> "Multiplayer"
            mc.currentServer?.isRealm == true -> "Realms"
            mc.currentServer?.isLan == true -> "LAN World"
            showServerAddress -> mc.currentServer?.ip?.take(80)?.filterNot { Character.isISOControl(it) }?.ifBlank { "Multiplayer" } ?: "Multiplayer"
            else -> "Multiplayer"
        }
        val button = if (includeButton) validButton(buttonLabel, buttonUrl) else null
        desiredActivity = makeActivity(details, if (connection == null) PROCESS_STARTED else joinedAt.takeIf { it > 0L } ?: now, button)
        if (app.isNotEmpty() && worker == null) {
            worker = PresenceLink(this).also { it.start() }
        }
        worker?.wake()
    }

    private fun validButton(label: String, rawUrl: String): Pair<String, String>? {
        val shown = label.trim().take(32)
        if (shown.isEmpty()) return null
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.toString().length > 200) return null
        return shown to uri.toASCIIString()
    }

    private fun makeActivity(details: String, startedAt: Long, button: Pair<String, String>?): String = buildString {
        append("{\"details\":").append(DonutDiscordIpc.quote(details))
        append(",\"timestamps\":{\"start\":").append(startedAt).append('}')
        if (button != null) {
            append(",\"buttons\":[{\"label\":").append(DonutDiscordIpc.quote(button.first))
            append(",\"url\":").append(DonutDiscordIpc.quote(button.second)).append("}]")
        }
        append('}')
    }

    private class PresenceLink(private val owner: DonutDiscordStatus) : Runnable {
        @Volatile private var running = true
        @Volatile private var ipc: DonutDiscordIpc? = null
        private val thread = Thread(this, "Donut Discord Status IPC").apply { isDaemon = true }
        private var connectedId = ""
        private var sent: String? = null
        private var sentAt = 0L
        private var retryMillis = RETRY_START_MS
        private var lastError = ""

        fun start() = thread.start()
        fun wake() = LockSupport.unpark(thread)

        fun stop() {
            running = false
            ipc?.let { runCatching { it.close() } }
            LockSupport.unpark(thread)
        }

        override fun run() {
            while (running) {
                val requestedId = owner.desiredId
                if (requestedId.isEmpty()) {
                    closeCurrent(clear = true)
                    pause(IDLE_POLL_MS)
                    continue
                }
                if (connectedId != requestedId) closeCurrent(clear = true)
                if (ipc == null) {
                    try {
                        ipc = DonutDiscordIpc.connect(requestedId)
                        connectedId = requestedId
                        retryMillis = RETRY_START_MS
                        lastError = ""
                        sent = null
                    } catch (problem: Exception) {
                        report(problem)
                        pause(retryMillis)
                        retryMillis = (retryMillis * 2L).coerceAtMost(RETRY_MAX_MS)
                        continue
                    }
                }
                val activity = owner.desiredActivity
                val now = System.nanoTime()
                val stale = now - sentAt >= TimeUnit.SECONDS.toNanos(KEEPALIVE_SECONDS)
                val changed = activity != sent && now - sentAt >= TimeUnit.SECONDS.toNanos(MIN_UPDATE_SECONDS)
                if (activity.isNotEmpty() && (sent == null || changed || stale)) {
                    try {
                        ipc?.setActivity(activity)
                        sent = activity
                        sentAt = System.nanoTime()
                    } catch (problem: Exception) {
                        report(problem)
                        closeCurrent(clear = false)
                        pause(retryMillis)
                        retryMillis = (retryMillis * 2L).coerceAtMost(RETRY_MAX_MS)
                        continue
                    }
                }
                pause(IDLE_POLL_MS)
            }
            closeCurrent(clear = true)
        }

        private fun closeCurrent(clear: Boolean) {
            val connection = ipc ?: run { connectedId = ""; sent = null; return }
            if (clear) runCatching { connection.setActivity("null") }
            runCatching { connection.close() }
            ipc = null
            connectedId = ""
            sent = null
        }

        private fun report(problem: Exception) {
            val message = problem.message ?: problem.javaClass.simpleName
            if (message == lastError) return
            lastError = message
            LOGGER.warn("Donut Discord Status could not update local Discord presence: {}", message)
        }

        private fun pause(millis: Long) = LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(millis))
    }

    companion object {
        private const val IDLE_POLL_MS = 1_000L
        private const val RETRY_START_MS = 3_000L
        private const val RETRY_MAX_MS = 30_000L
        private const val MIN_UPDATE_SECONDS = 4L
        private const val KEEPALIVE_SECONDS = 60L
        private val PROCESS_STARTED = Instant.now().epochSecond
        private val LOGGER = LoggerFactory.getLogger("Donut Discord Status")

        @JvmStatic fun makeActivityForTest(details: String, startedAt: Long): String =
            "{\"details\":${DonutDiscordIpc.quote(details)},\"timestamps\":{\"start\":$startedAt}}"
    }
}
