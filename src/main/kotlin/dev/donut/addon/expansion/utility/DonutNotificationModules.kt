package dev.donut.addon.expansion.utility

import dev.donut.addon.expansion.presence.PresenceText
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.notification
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.chat.Component
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.phys.BlockHitResult
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.Executors

/** Repeats configured messages at a controlled pace, with optional split and text variation. */
class DonutChatFaucet : DonutUtilityModule("Donut Chat Faucet", "Sends configured chat messages on a timer with optional splitting and text variation.") {
    private val messages by textList("Messages", mutableListOf())
    private val delay by float("Delay", 20f, 0f..1_200f)
    private val disableOnLeave by boolean("Disable On Leave", true)
    private val disableOnDisconnect by boolean("Disable On Disconnect", true)
    private val randomize by boolean("Randomise", false)
    private val autoSplit by boolean("Auto Split Messages", false)
    private val splitLength by int("Split Length", 256, 1..256)
    private val splitDelay by float("Split Delay", 20f, 0f..200f)
    private val varyText by boolean("Bypass", false)
    private val uppercase by boolean("Include Uppercase Characters", true)
    private val variationLength by int("Length", 16, 1..256)
    private var connection: Any? = null
    private var lastWorld: Any? = null
    private var nextSendAt = 0L
    private var messageIndex = 0
    private var pieces = emptyList<String>()
    private var pieceIndex = 0

    override fun onEnabled() { connection = mc.connection; lastWorld = mc.level; nextSendAt = 0; messageIndex = 0; pieces = emptyList(); pieceIndex = 0 }
    override fun onDisabled() { connection = null; lastWorld = null; pieces = emptyList(); pieceIndex = 0 }

    override fun onUtilityWorldChanging(world: ClientLevel?) {
        val hadConnection = connection != null
        val disconnected = world == null || mc.connection == null
        val changedSession = connection != null && (
            mc.connection !== connection || world != null && lastWorld != null && world !== lastWorld
        )
        val disableForTransition = hadConnection &&
            (if (disconnected) disableOnDisconnect else changedSession && disableOnLeave)
        super.onUtilityWorldChanging(world)
        if (disableForTransition && enabled) enabled = false
    }

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        val network = mc.connection
        val level = mc.level
        if (network == null || level == null) {
            if (connection != null && disableOnDisconnect) enabled = false
            connection = null; lastWorld = null
            return@handler
        }
        if (connection != null && (network !== connection || level !== lastWorld) && disableOnLeave) { enabled = false; return@handler }
        connection = network; lastWorld = level
        val now = System.nanoTime()
        if (now < nextSendAt) return@handler
        if (pieceIndex >= pieces.size) {
            val source = messages.map(String::trim).filter(String::isNotEmpty)
            if (source.isEmpty()) return@handler
            val selected = if (randomize) source.random() else source[messageIndex++ % source.size]
            val transformed = if (varyText) vary(selected) else selected
            pieces = if (autoSplit) transformed.chunked(splitLength.coerceIn(1, 256)) else listOf(transformed.take(256))
            pieceIndex = 0
        }
        val connectionNow = mc.connection ?: return@handler
        connectionNow.sendChat(pieces[pieceIndex++])
        nextSendAt = now + ((if (pieceIndex < pieces.size) splitDelay else delay).coerceIn(0f, 1_200f) * 1_000_000_000.0).toLong()
    }

    private fun vary(value: String): String {
        val length = variationLength.coerceIn(1, 256)
        val upper = uppercase
        val random = kotlin.random.Random(System.nanoTime())
        return buildString(value.length.coerceAtMost(length)) {
            value.take(length).forEach { ch ->
                when {
                    ch.isLetter() && random.nextInt(4) == 0 -> append(if (upper) ch.uppercaseChar() else ch.lowercaseChar())
                    ch.isLetter() && !upper && random.nextInt(5) == 0 -> append(ch.lowercaseChar())
                    else -> append(ch)
                }
            }
        }
    }
}

/** Key-triggered coordinate copy with raycast, formatting, dimension label, and opt-in webhook. */
class DonutCoordinateSnapper : DonutUtilityModule("Donut Coordinate Snapper", "Copies your position or a looked-at block position when its key is pressed.") {
    private enum class Target(override val tag: String) : Tagged { LOOK("Looked At"), FEET("Own Position") }
    private enum class Format(override val tag: String) : Tagged { SPACES("X Y Z"), COMMAS("X, Y, Z"), COMMAND("/tp Command") }
    private val trigger by key("Key")
    private val target by enumChoice("Target", Target.LOOK)
    private val range by float("Range", 256f, 16f..512f)
    private val stopAtFluids by boolean("Stop at Fluids", false)
    private val format by enumChoice("Format", Format.SPACES)
    private val includeDimension by boolean("Include Dimension", true)
    private val confirm by boolean("Chat Confirmation", true)
    private val webhook by boolean("Webhook", false)
    private val webhookUrl by text("Webhook URL", "").doNotIncludeAlways()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "Donut coordinate notice").apply { isDaemon = true } }

    override fun onUtilityShutdown() {
        worker.shutdownNow()
    }

    @Suppress("unused")
    private val keyboard = handler<KeyboardKeyEvent> { event ->
        if (!event.isPressed || event.isRepeat || event.screen != null || mc.gui.screen() != null || event.key != trigger) return@handler
        val player = mc.player ?: return@handler
        val level = mc.level ?: return@handler
        val position = when (target) {
            Target.FEET -> player.blockPosition()
            Target.LOOK -> {
                val hit = mc.hitResult as? BlockHitResult ?: return@handler
                val state = level.getBlockState(hit.blockPos)
                if (stopAtFluids && !state.fluidState.isEmpty) return@handler
                if (player.eyePosition.distanceTo(hit.location) > range) return@handler
                hit.blockPos
            }
        }
        val dimension = level.dimension().identifier().toString()
        val coords = when (format) {
            Format.SPACES -> "${position.x} ${position.y} ${position.z}"
            Format.COMMAS -> "${position.x}, ${position.y}, ${position.z}"
            Format.COMMAND -> "/tp ${position.x} ${position.y} ${position.z}"
        }
        val clipboard = coords + if (includeDimension) " [$dimension]" else ""
        mc.keyboardHandler.clipboard = clipboard
        if (confirm) chat("Coordinates copied: $clipboard", this)
        if (webhook) submitWebhook(clipboard)
    }

    private fun submitWebhook(value: String) {
        val uri = runCatching { URI(webhookUrl.trim()) }.getOrNull()
        if (uri?.host == null || uri.scheme !in setOf("https", "http")) { chat("Coordinate webhook URL is invalid.", this); return }
        worker.execute {
            runCatching {
                val body = "{\"content\":\"Coordinates copied: ${jsonEscape(value)}\"}"
                val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build()
                val response = http.send(request, HttpResponse.BodyHandlers.discarding())
                if (response.statusCode() !in 200..299) error("HTTP ${response.statusCode()}")
            }.onFailure { error -> mc.execute { chat("Coordinate webhook failed (${error.javaClass.simpleName}).", this) } }
        }
    }

    private fun jsonEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}

/** Watches stable world weather changes and notifies when rain begins. */
class DonutWeatherNotice : DonutUtilityModule("Donut Weather Notice", "Shows an advancement-style alert when rain begins in the current world.") {
    private val sound by boolean("Sound", true)
    private var observedWorld: Any? = null
    private var raining = false
    private var changedTicks = 0

    override fun onEnabled() { observedWorld = null; changedTicks = 0 }
    override fun onDisabled() { observedWorld = null; changedTicks = 0 }

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        val level = mc.level ?: run { observedWorld = null; return@handler }
        if (observedWorld !== level) { observedWorld = level; raining = level.isRaining; changedTicks = 0; return@handler }
        if (level.isRaining == raining) { changedTicks = 0; return@handler }
        if (++changedTicks < 20) return@handler
        raining = level.isRaining; changedTicks = 0
        if (raining) {
            notification("Weather Changed!", "It started raining.", NotificationEvent.Severity.INFO)
            if (sound) mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1f))
        }
    }
}

/** Announces first sightings of chosen block types from server block-update packets. */
class DonutBlockNotice : DonutUtilityModule("Donut Block Notice", "Shows a toast with a configured block and its coordinates when a new block update matches.") {
    private val blocks by textList("Blocks", mutableListOf("minecraft:spawner"))
    private val sound by boolean("Sound", true)
    private val seen = LinkedHashMap<Long, String>()
    private var observedWorld: Any? = null

    override fun onDisabled() { seen.clear(); observedWorld = null }

    @Suppress("unused")
    private val packet = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING) return@handler
        val level = mc.level ?: return@handler
        if (observedWorld !== level) { seen.clear(); observedWorld = level }
        val update = event.packet as? ClientboundBlockUpdatePacket ?: return@handler
        val pos = update.pos
        val key = BuiltInRegistries.BLOCK.getKey(update.blockState.block).toString()
        if (update.blockState.isAir) { seen.remove(pos.asLong()); return@handler }
        if (key !in blocks.map { it.trim().lowercase(Locale.ROOT) }) return@handler
        if (seen.putIfAbsent(pos.asLong(), key) != null) return@handler
        val text = "$key at ${pos.x}, ${pos.y}, ${pos.z}"
        mc.execute {
            notification("Block Found", text, NotificationEvent.Severity.INFO)
            if (sound) mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1.25f))
        }
        while (seen.size > 50_000) seen.remove(seen.keys.first())
    }
}
