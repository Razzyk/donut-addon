package dev.donut.addon.expansion.utility

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.notification
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.phys.BlockHitResult
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.Executors

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
