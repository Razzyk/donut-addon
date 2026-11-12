package dev.donut.addon.expansion.presence

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.donut.addon.AddonCategories
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.client.chat
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.sounds.SoundEvents
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler

/** Checks public creator live pages for transitions and publishes a small live panel. */
class DonutStaffPulse : DonutPresenceModule("Donut Staff Pulse") {
    data class LiveRow(val channel: String, val platform: String, val title: String, val since: Long, val url: String)
    private data class Channel(val youtube: Boolean, val handle: String) {
        val key get() = (if (youtube) "yt:" else "tt:") + handle.lowercase(Locale.ROOT)
    }
    private data class Probe(val live: Boolean, val title: String?, val url: String?, val nick: String? = null, val started: Long = 0L)
    private data class PollSettings(val channels: List<Channel>, val intervalMillis: Long)

    private val channelInputs by textList("Channels", mutableListOf(
        "yt:wizoxlol", "yt:pastagamer08", "tt:pastagamer08", "tt:theauzzi", "tt:u_vv7_", "yt:DrDonutt"
    )).doNotIncludeAlways()
    private val pollSeconds by float("Check Every (s)", 60f, 15f..600f)
    private val showHud by boolean("Show HUD", true).onChanged { PresenceFeatures.syncHudState() }
    private val displayScale by float("Scale", 1f, 0.5f..3f)
    private val panelOpacity by float("Opacity", 0.9f, 0.1f..1f)

    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10)).build()
    private val liveStates = ConcurrentHashMap<String, Boolean>()
    private val visibleRows = ConcurrentHashMap<String, LiveRow>()
    @Volatile private var worker: Thread? = null
    @Volatile private var currentlyEnabled = false
    private val runGuard = PresenceRunGuard()
    @Volatile private var pollSettings = PollSettings(emptyList(), 60_000L)

    init { literalDescription { "Checks configured YouTube and TikTok channels for new live streams." } }

    override fun onEnabled() {
        onDisabled()
        if (mc.player == null || mc.level == null || mc.connection == null) return
        refreshSettings()
        val token = runGuard.begin()
        currentlyEnabled = true
        val next = Thread({ pollLoop(token) }, "Donut public live checks").apply { isDaemon = true }
        worker = next
        next.start()
        PresenceFeatures.syncHudState()
    }

    override fun onDisabled() {
        runGuard.cancel()
        currentlyEnabled = false
        worker?.interrupt()
        worker = null
        liveStates.clear()
        visibleRows.clear()
        PresenceFeatures.syncHudState()
    }

    internal override fun shutdownPresence() {
        onDisabled()
        client.shutdownNow()
    }

    val shouldShowHud get() = showHud
    fun rows(): List<LiveRow> = if (!currentlyEnabled) emptyList() else visibleRows.values.sortedBy(LiveRow::since)

    private val settingsTick = handler<GameTickEvent> { refreshSettings() }

    private fun refreshSettings() {
        pollSettings = PollSettings(parseChannels(), (pollSeconds.coerceIn(15f, 600f) * 1_000).toLong())
    }

    private fun pollLoop(token: Any) {
        val self = Thread.currentThread()
        var nextPollAt = 0L
        while (runGuard.accepts(token) && worker === self) {
            val now = System.currentTimeMillis()
            if (now >= nextPollAt) {
                val snapshot = pollSettings
                val channels = snapshot.channels
                val configured = channels.mapTo(hashSetOf()) { it.key }
                channels.forEach { channel ->
                    if (!runGuard.accepts(token) || worker !== self) return
                    check(channel, token)
                }
                runGuard.apply(token) {
                    liveStates.keys.retainAll(configured)
                    visibleRows.keys.retainAll(configured)
                }
                nextPollAt = System.currentTimeMillis() + snapshot.intervalMillis
            }
            try { Thread.sleep(500) } catch (_: InterruptedException) { return }
        }
    }

    private fun parseChannels(): List<Channel> {
        val regex = Regex("^(yt|tt):@?([A-Za-z0-9._-]+)$", RegexOption.IGNORE_CASE)
        return channelInputs.mapNotNull { raw ->
            val match = regex.matchEntire(raw.trim()) ?: return@mapNotNull null
            Channel(match.groupValues[1].equals("yt", true), match.groupValues[2])
        }.distinctBy(Channel::key)
    }

    private fun check(channel: Channel, token: Any) {
        val result = try { if (channel.youtube) checkYoutube(channel.handle) else checkTikTok(channel.handle) }
        catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); return }
        catch (failure: Exception) {
            logger.debug("Channel check failed for {} ({})", channel.key, failure.javaClass.simpleName)
            return // An unreachable or changed page must not be treated as an offline transition.
        } ?: return

        runGuard.apply(token) {
            val wasLive = liveStates.put(channel.key, result.live)
            if (result.live && wasLive != true) announce(channel, result, token)
            if (!result.live) visibleRows.remove(channel.key)
        }
    }

    private fun checkYoutube(handle: String): Probe? {
        val uri = URI.create("https://www.youtube.com/@$handle/live")
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept-Encoding", "gzip")
            .header("Cookie", "SOCS=CAI; CONSENT=YES+")
            .GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { body ->
            if (response.statusCode() != 200) return null
            val decoded = if (response.headers().firstValue("Content-Encoding").orElse("").equals("gzip", true)) GZIPInputStream(body) else body
            val html = PresenceHttpBody.readUtf8(decoded, 1_500_000)
            val canonical = html.substringAfter("<link rel=\"canonical\" href=\"", "").substringBefore('"').takeIf(String::isNotBlank)
                ?: Regex("<link[^>]+href=[\"']([^\"']+)[\"'][^>]*rel=[\"']canonical[\"']", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            if (canonical == null) return null
            if (!canonical.contains("/watch?v=")) return Probe(false, null, canonical)
            val isLive = html.contains("\"isLiveNow\":true")
            if (!isLive) return Probe(false, null, canonical)
            val title = html.substringAfter("<meta name=\"title\" content=\"", "").substringBefore('"').takeIf(String::isNotBlank)
                ?: Regex("<meta[^>]+name=[\"']title[\"'][^>]+content=[\"']([^\"']*)", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            val start = Regex("\"startTimestamp\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            return Probe(true, cleanText(title), canonical, started = startedAt(start))
        }
    }

    private fun checkTikTok(user: String): Probe? {
        val url = "https://www.tiktok.com/api-live/user/room/?aid=1988&app_language=en&app_name=tiktok_web&sourceType=54&uniqueId=" +
            URLEncoder.encode(user, StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
            .header("User-Agent", USER_AGENT).header("Accept", "application/json").GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val body = response.body().use { input ->
            if (response.statusCode() != 200) return null
            val decoded = if (response.headers().firstValue("Content-Encoding").orElse("").equals("gzip", true)) GZIPInputStream(input) else input
            PresenceHttpBody.readUtf8(decoded, 1_500_000)
        }
        val root = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val data = root.objectAt("data") ?: return null
        val room = data.objectAt("liveRoom") ?: return null
        val status = room.get("status")?.takeIf { it.isJsonPrimitive }?.asInt ?: return null
        val userData = data.objectAt("user")
        val nickname = userData?.get("nickname")?.takeIf { it.isJsonPrimitive }?.asString
        val title = room.get("title")?.takeIf { it.isJsonPrimitive }?.asString
        val started = room.get("startTime")?.takeIf { it.isJsonPrimitive }?.asLong?.times(1_000) ?: 0L
        return Probe(status == 2, cleanText(title), "https://www.tiktok.com/@$user/live", nickname, started)
    }

    private fun announce(channel: Channel, result: Probe, token: Any) {
        val platform = if (channel.youtube) "YouTube" else "TikTok"
        val who = if (result.nick.isNullOrBlank() || result.nick.equals(channel.handle, true)) channel.handle
            else "${channel.handle} (${result.nick})"
        val fallback = if (channel.youtube) "https://www.youtube.com/@${channel.handle}/live" else "https://www.tiktok.com/@${channel.handle}/live"
        val url = result.url?.let(::trustedWatchUrl) ?: fallback
        val now = System.currentTimeMillis()
        val since = result.started.takeIf { it in 1..now } ?: now
        visibleRows[channel.key] = LiveRow(who, platform, result.title.orEmpty(), since, url)
        mc.execute {
            if (!runGuard.accepts(token) || !currentlyEnabled || !running || mc.player == null) return@execute
            val title = result.title?.takeIf(String::isNotBlank)?.let { ": ${shortTitle(it)}" }.orEmpty()
            val linked = Component.literal("[watch]").withStyle(
                Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true).withClickEvent(ClickEvent.OpenUrl(URI(url)))
            )
            chat(Component.literal("$who is LIVE on $platform$title ").append(linked), this)
            mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.25f))
        }
    }

    private fun trustedWatchUrl(raw: String): String? = runCatching {
        val uri = URI(raw)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val allowed = host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "tiktok.com" || host.endsWith(".tiktok.com")
        uri.takeIf { it.scheme.equals("https", true) && allowed }?.toString()
    }.getOrNull()

    private fun shortTitle(value: String): String = if (value.length <= 48) value else value.take(47).trimEnd() + "…"

    private fun JsonObject.objectAt(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun cleanText(value: String?): String? = value?.replace(Regex("&(?:quot|#39|lt|gt|amp);")) { match ->
        when (match.value) { "&quot;" -> "\""; "&#39;" -> "'"; "&lt;" -> "<"; "&gt;" -> ">"; else -> "&" }
    }?.codePoints()?.filter { it >= 32 && it < 8192 || it in 8208..9727 }?.toArray()?.let { String(it, 0, it.size) }
        ?.replace(Regex("\\s+"), " ")?.trim()
    private fun startedAt(value: String?): Long = runCatching { value?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() } ?: 0L }.getOrDefault(0L)

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36"
    }
}
