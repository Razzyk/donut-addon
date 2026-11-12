package dev.donut.addon.expansion.utility

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.MouseRotationEvent
import net.ccbluex.liquidbounce.event.events.PerspectiveEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.minecraft.client.Minecraft
import net.minecraft.client.CameraType
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.player.PlayerSkin
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import com.mojang.authlib.GameProfile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.function.Supplier
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/** Retains the local block model briefly after a predicted break, until the server answers. */
class DonutBlockSync : DonutUtilityModule("Donut Block Sync", "Keeps a mined block visible while waiting for the server to confirm the break.") {
    private data class Pending(val position: BlockPos, val state: BlockState, val startedAt: Long)
    private val pending = LinkedHashMap<Long, Pending>()
    private var activeLevel: ClientLevel? = null
    private var pingTimeoutNanos = TimeUnit.SECONDS.toNanos(5)

    override fun onEnabled() = startSession(mc.level)

    override fun onUtilitySessionStarted(world: ClientLevel) = startSession(world)

    private fun startSession(world: ClientLevel?) {
        activeLevel = world
        pending.clear()
        active = this
    }

    override fun onUtilitySessionEnded() {
        pending.clear()
        activeLevel = null
        if (active === this) active = null
    }

    override fun onDisabled() = onUtilitySessionEnded()

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        val level = mc.level
        if (level !== activeLevel) {
            pending.clear()
            activeLevel = level
        }
        val player = mc.player ?: return@handler
        val info = mc.connection?.getPlayerInfo(player.uuid)
        val ping = info?.latency?.coerceIn(0, 5_000) ?: 1_500
        pingTimeoutNanos = TimeUnit.MILLISECONDS.toNanos((ping * 3L + 500L).coerceIn(2_000L, 15_500L))
        val now = System.nanoTime()
        pending.values.removeIf { now - it.startedAt > pingTimeoutNanos }
    }

    @Suppress("unused")
    private val packet = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING) return@handler
        val update = event.packet as? ClientboundBlockUpdatePacket ?: return@handler
        pending.remove(update.pos.asLong())
    }

    internal fun begin(level: ClientLevel?, pos: BlockPos): Boolean {
        if (level == null || level !== mc.level || mc.player == null) return false
        val state = level.getBlockState(pos)
        if (state.isAir) return false
        if (pending.size >= MAX_PENDING) pending.remove(pending.entries.first().key)
        pending.putIfAbsent(pos.asLong(), Pending(pos.immutable(), state, System.nanoTime()))
        return true
    }

    internal fun finish(level: ClientLevel?, pos: BlockPos, success: Boolean) {
        val entry = pending[pos.asLong()] ?: return
        if (!success || level == null || level !== activeLevel || level.getBlockState(pos) == entry.state) {
            if (!success) pending.remove(pos.asLong())
            return
        }
        // Flag 2 updates the client render/collision view without sending a second block action.
        level.setBlock(pos, entry.state, 2)
    }

    internal fun isWaiting(pos: BlockPos): Boolean = pending.containsKey(pos.asLong())

    companion object {
        private const val MAX_PENDING = 256
        @Volatile private var active: DonutBlockSync? = null

        @JvmStatic fun beginBreak(level: ClientLevel?, pos: BlockPos): Boolean = active?.begin(level, pos) ?: false
        @JvmStatic fun finishBreak(level: ClientLevel?, pos: BlockPos, success: Boolean) = active?.finish(level, pos, success)
        @JvmStatic fun waitingForAnswer(pos: BlockPos): Boolean = active?.isWaiting(pos) ?: false
    }
}

/** Match aliases by full identifier boundaries so short names do not rewrite unrelated words. */
object DonutAliasRules {
    fun replaceText(input: String, aliases: Map<String, String>): String {
        if (input.isEmpty() || aliases.isEmpty()) return input
        val ordered = aliases.entries.filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .distinctBy { it.key.lowercase(Locale.ROOT) }
            .sortedByDescending { it.key.length }
        if (ordered.isEmpty()) return input
        val alternatives = ordered.joinToString("|") { Pattern.quote(it.key) }
        val expression = Pattern.compile("(?<![\\p{L}\\p{N}_])($alternatives)(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        val matcher = expression.matcher(input)
        val output = StringBuffer(input.length)
        while (matcher.find()) {
            val replacement = ordered.firstOrNull { it.key.equals(matcher.group(1), ignoreCase = true) }?.value ?: matcher.group(1)
            matcher.appendReplacement(output, java.util.regex.Matcher.quoteReplacement(replacement))
        }
        matcher.appendTail(output)
        return output.toString()
    }

    fun parseNames(raw: String): List<String> = raw.split(',', ';', '\n', '\r', '\t', ' ')
        .map(String::trim).filter { it.matches(Regex("[A-Za-z0-9_]{3,16}")) }
        .distinctBy { it.lowercase(Locale.ROOT) }
}

/** Rewrites on-screen player labels and tab entries without changing chat packets or server data. */
class DonutNameShield : DonutUtilityModule("Donut Name Shield", "Replaces your own and selected player names in rendered labels and the player list.") {
    private enum class OtherStyle(override val tag: String) : Tagged { KEEP("Keep"), SAME("Same Label"), NUMBERED("Numbered") }
    private enum class AnonymousLabel(override val tag: String) : Tagged { PLAYER("Player"), ANONYMOUS("Anonymous"), UNKNOWN("Unknown") }

    private val selfAlias by text("Your Alias", "You")
    private val extraNames by text("Extra Names", "").doNotIncludeAlways()
    private val others by enumChoice("Others", OtherStyle.KEEP)
    private val otherLabel by enumChoice("Others Label", AnonymousLabel.PLAYER)
    private val explicitNames by boolean("Replace Extra Names", true)
    private val assigned = LinkedHashMap<String, Int>()
    @Volatile private var aliasTable: Map<String, String> = emptyMap()
    private var nextNumber = 1

    override fun onEnabled() = startSession()
    override fun onUtilitySessionStarted(world: ClientLevel) = startSession()
    private fun startSession() { assigned.clear(); nextNumber = 1; active = this; publish() }
    override fun onUtilitySessionEnded() { aliasTable = emptyMap(); assigned.clear(); if (active === this) active = null }
    override fun onDisabled() = onUtilitySessionEnded()

    @Suppress("unused")
    private val tick = handler<GameTickEvent> { publish() }

    private fun publish() {
        val player = mc.player
        val table = LinkedHashMap<String, String>()
        val ownAlias = selfAlias.trim().ifBlank { "You" }
        val profileName = player?.gameProfile?.name
        if (!profileName.isNullOrBlank()) table[profileName] = ownAlias
        mc.connection?.onlinePlayers?.forEach { info ->
            val name = info.profile.name ?: return@forEach
            if (name.equals(profileName, true)) return@forEach
            if (others != OtherStyle.KEEP) {
                val key = name.lowercase(Locale.ROOT)
                val number = assigned.getOrPut(key) { nextNumber++ }
                val label = when (otherLabel) {
                    AnonymousLabel.PLAYER -> "Player"
                    AnonymousLabel.ANONYMOUS -> "Anonymous"
                    AnonymousLabel.UNKNOWN -> "Unknown"
                }
                table[name] = if (others == OtherStyle.NUMBERED) "$label $number" else label
            }
        }
        if (explicitNames) DonutAliasRules.parseNames(extraNames).forEach { table[it] = ownAlias }
        if (assigned.size > 256) {
            val live = mc.connection?.onlinePlayers?.mapNotNull { it.profile.name?.lowercase(Locale.ROOT) }?.toSet().orEmpty()
            assigned.keys.retainAll(live)
        }
        aliasTable = table
    }

    fun protect(component: Component?): Component? {
        if (component == null || !enabled) return component
        val text = DonutAliasRules.replaceText(component.string, aliasTable)
        return if (text == component.string) component else Component.literal(text).withStyle(component.style)
    }

    companion object {
        @Volatile private var active: DonutNameShield? = null
        @JvmStatic fun protectRenderedName(component: Component?): Component? = active?.protect(component) ?: component
        @JvmStatic fun setActive(module: DonutNameShield?) { active = module }
    }
}

/** Detached view movement is kept client-side; the player remains the network-controlled entity. */
class DonutDetachedCamera : DonutUtilityModule("Donut Detached Camera", "Moves the view independently from your player while leaving the player body in the world.") {
    private val speed by float("Speed", 1f, 0.1f..50f)
    private val verticalSpeed by float("Vertical", 1f, 0.2f..5f)
    private val smoothing by boolean("Smooth", true)
    private val showBody by boolean("Body", true)
    private val showHands by boolean("Hands", true)
    private val bodyUsesKeys by boolean("Keys Move Body", false)
    private val keepWalking by boolean("Keep Walking", true)
    private val scrollSpeed by boolean("Scroll Speed", true)
    private val scrollResets by boolean("Reset Scroll Speed", true)
    private val chunkDistance by int("Chunks", 12, 2..32)
    private var position = Vec3.ZERO
    private var priorPosition = Vec3.ZERO
    private var yaw = 0f
    private var pitch = 0f
    private var startPosition = Vec3.ZERO
    private var scrollScale = 1f

    override fun onEnabled() {
        if (!initializeForSession()) enabled = false
    }

    override fun onUtilitySessionStarted(world: ClientLevel) {
        initializeForSession()
    }

    private fun initializeForSession(): Boolean {
        val player = mc.player ?: return false
        position = player.eyePosition
        priorPosition = position
        startPosition = player.position()
        yaw = player.yRot
        pitch = player.xRot
        scrollScale = 1f
        live = this
        return true
    }

    override fun onUtilitySessionEnded() { if (live === this) live = null; scrollScale = 1f }
    override fun onDisabled() = onUtilitySessionEnded()

    @Suppress("unused")
    private val movement = handler<MovementInputEvent> {
        if (live !== this) return@handler
        if (!keepWalking || !bodyUsesKeys) {
            it.directionalInput = DirectionalInput.NONE
            it.jump = false
            it.sneak = false
        }
    }

    @Suppress("unused")
    private val mouse = handler<MouseRotationEvent> { event ->
        if (live !== this) return@handler
        yaw += event.cursorDeltaX.toFloat() * 0.15f
        pitch = (pitch + event.cursorDeltaY.toFloat() * 0.15f).coerceIn(-90f, 90f)
        event.cancelEvent()
    }

    @Suppress("unused")
    private val perspective = handler<PerspectiveEvent> { event ->
        if (live !== this) return@handler
        event.perspective = if (showBody) CameraType.THIRD_PERSON_BACK else CameraType.FIRST_PERSON
        event.distance = 0f
    }

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        val player = mc.player ?: return@handler
        if (mc.level == null || mc.gui.screen() != null) return@handler
        if (live !== this && !initializeForSession()) return@handler
        priorPosition = position
        val options = mc.options
        val forward = (if (options.keyUp.isDown) 1.0 else 0.0) - (if (options.keyDown.isDown) 1.0 else 0.0)
        val side = (if (options.keyRight.isDown) 1.0 else 0.0) - (if (options.keyLeft.isDown) 1.0 else 0.0)
        val rise = (if (options.keyJump.isDown) 1.0 else 0.0) - (if (options.keyShift.isDown) 1.0 else 0.0)
        val scale = if (smoothing) 0.5f else 1f
        val currentSpeed = (speed * scrollScale * scale).coerceIn(0.05f, 50f).toDouble()
        val radians = Math.toRadians(yaw.toDouble())
        val dx = (-kotlin.math.sin(radians) * forward + kotlin.math.cos(radians) * side) * currentSpeed
        val dz = (kotlin.math.cos(radians) * forward + kotlin.math.sin(radians) * side) * currentSpeed
        val dy = rise * currentSpeed * verticalSpeed.coerceIn(0.2f, 5f).toDouble()
        val proposed = position.add(dx, dy, dz)
        val maxDistance = chunkDistance.coerceIn(2, 32) * 16.0
        val offset = proposed.subtract(startPosition)
        position = if (offset.lengthSqr() > maxDistance * maxDistance) startPosition.add(offset.normalize().scale(maxDistance)) else proposed
        if (!keepWalking || !bodyUsesKeys) {
            player.setPos(startPosition.x, startPosition.y, startPosition.z)
            player.deltaMovement = Vec3.ZERO
        }
    }

    fun handsVisible() = showHands && !showBody

    fun scroll(amount: Double): Boolean {
        if (!enabled || !scrollSpeed || amount == 0.0) return false
        val factor = if (amount > 0) 1.2f else 1f / 1.2f
        scrollScale = if (scrollResets) (scrollScale * factor).coerceIn(0.1f, 50f) else 1f
        return true
    }

    companion object {
        @Volatile private var live: DonutDetachedCamera? = null
        @JvmStatic fun cameraPosition(): Vec3? = live?.takeIf { it.enabled }?.position
        @JvmStatic fun cameraYaw(): Float = live?.yaw ?: 0f
        @JvmStatic fun cameraPitch(): Float = live?.pitch ?: 0f
        @JvmStatic fun shouldShowHands(): Boolean = live?.handsVisible() ?: true
        @JvmStatic fun wheel(amount: Double): Boolean = live?.scroll(amount) ?: false
    }
}

/** Supplies per-viewer player skins using Mojang's public profile service and the game's skin cache. */
class DonutSkinWardrobe : DonutUtilityModule("Donut Skin Wardrobe", "Uses a selected public Minecraft profile skin for your own view or other players.") {
    private val selfPlayer by text("Self Player", "")
    private val othersPlayer by text("Others Player", "")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build()
    private val generation = AtomicLong()
    private var loadedSelfName = ""
    private var loadedOthersName = ""
    @Volatile private var selfSkin: Supplier<PlayerSkin>? = null
    @Volatile private var otherSkin: Supplier<PlayerSkin>? = null

    override fun onEnabled() = startSession()

    override fun onUtilitySessionStarted(world: ClientLevel) = startSession()

    private fun startSession() {
        generation.incrementAndGet()
        live = this
        loadedSelfName = ""
        loadedOthersName = ""
        selfSkin = null
        otherSkin = null
        refresh()
    }

    override fun onUtilitySessionEnded() {
        generation.incrementAndGet()
        if (live === this) live = null
        selfSkin = null
        otherSkin = null
        loadedSelfName = ""
        loadedOthersName = ""
    }

    override fun onDisabled() = onUtilitySessionEnded()

    @Suppress("unused")
    private val tick = handler<GameTickEvent> { refresh() }

    private fun refresh() {
        val self = selfPlayer.trim()
        val others = othersPlayer.trim()
        if (self != loadedSelfName) {
            loadedSelfName = self
            selfSkin = null
            if (self.isNotEmpty()) lookup(self, true)
        }
        if (others != loadedOthersName) {
            loadedOthersName = others
            otherSkin = null
            if (others.isNotEmpty()) lookup(others, false)
        }
    }

    private fun lookup(name: String, own: Boolean) {
        if (!name.matches(Regex("[A-Za-z0-9_]{1,16}"))) return
        val requestGeneration = generation.get()
        CompletableFuture.runAsync {
            runCatching {
                val request = HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/$name"))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json")
                    .GET().build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() != 200) return@runCatching null
                val raw = Regex("\\\"id\\\"\\s*:\\s*\\\"([0-9a-fA-F]{32})\\\"").find(response.body())?.groupValues?.get(1)
                    ?: return@runCatching null
                val uuid = UUID.fromString(raw.replaceFirst(Regex("^(.{8})(.{4})(.{4})(.{4})(.{12})$"), "$1-$2-$3-$4-$5"))
                Minecraft.getInstance().skinManager.createLookup(GameProfile(uuid, name), false)
            }.getOrNull()?.let { profile ->
                Minecraft.getInstance().execute {
                    if (requestGeneration != generation.get() || live !== this || !enabled ||
                        name != if (own) selfPlayer.trim() else othersPlayer.trim()) return@execute
                    if (own) selfSkin = profile else otherSkin = profile
                }
            }
        }
    }

    fun replacement(playerId: UUID, original: PlayerSkin): PlayerSkin {
        val mc = Minecraft.getInstance()
        val selected = if (playerId == mc.player?.uuid) selfSkin else otherSkin
        return selected?.let { runCatching { it.get() }.getOrNull() } ?: original
    }

    companion object {
        @Volatile private var live: DonutSkinWardrobe? = null
        @JvmStatic fun skinFor(playerId: UUID, original: PlayerSkin): PlayerSkin = live?.replacement(playerId, original) ?: original
    }
}

