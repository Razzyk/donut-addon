package dev.donut.addon.expansion.utility

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.world.level.block.state.BlockState
import java.util.Locale
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
