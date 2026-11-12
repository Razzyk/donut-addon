package dev.donut.addon.expansion.utility

import dev.donut.addon.hud.region.RegionMapData
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.client.chat
import net.minecraft.world.level.Level
import kotlin.math.floor

data class DonutGoliath(val region: String, val number: Int)

/** Converts the existing shard map into target and route lookups for the RTP workflow. */
object DonutGoliathRoutes {
    private const val WORLD_RADIUS = 225_000.0
    private const val SHARD_SIZE = 12_500.0
    private val shardCounts = RegionMapData.shards.groupingBy { it.region }.eachCount()
    private val commandByRegion = mapOf(
        "EU Central" to "eu central", "EU West" to "eu west", "NA West" to "west",
        "NA East" to "east", "Asia" to "asia", "Oceania" to "oceania",
    )
    private val fallbackHop = mapOf("EU Central" to "EU West", "EU West" to "EU Central",
        "NA West" to "NA East", "NA East" to "NA West", "Asia" to "Oceania", "Oceania" to "Asia",
        "Europe" to "EU Central")

    fun parse(raw: List<String>): Set<DonutGoliath> = raw.mapNotNull(::parseOne).toSet()

    private fun parseOne(raw: String): DonutGoliath? {
        val text = raw.trim()
        val shard = RegionMapData.shards.firstOrNull { "${it.region} ${it.number}".equals(text, true) }
            ?: return null
        return DonutGoliath(shard.region, shard.number)
    }

    fun at(worldX: Double, worldZ: Double, dimension: String): DonutGoliath? {
        if (dimension == Level.END.identifier().toString()) return null
        val scale = if (dimension == Level.NETHER.identifier().toString()) 8.0 else 1.0
        val column = floor((worldX * scale + WORLD_RADIUS) / SHARD_SIZE).toInt()
        val row = floor((worldZ * scale + WORLD_RADIUS) / SHARD_SIZE).toInt()
        if (column !in 0 until RegionMapData.GRID_SIZE || row !in 0 until RegionMapData.GRID_SIZE) return null
        val shard = RegionMapData.cells[row][column] ?: return null
        return DonutGoliath(shard.region, shard.number)
    }

    fun nextRegion(current: String?, targets: Set<DonutGoliath>): String? {
        val counts = targets.groupingBy { it.region }.eachCount()
        val best = counts.keys.asSequence().filter { it != current && commandByRegion.containsKey(it) }
            .maxByOrNull { region -> counts.getValue(region).toDouble() / shardCounts.getValue(region).coerceAtLeast(1) }
        if (best != null) return best
        var region = current ?: "Oceania"
        repeat(8) {
            region = fallbackHop[region] ?: "Oceania"
            if (commandByRegion.containsKey(region)) return region
        }
        return null
    }

    fun commandFor(region: String): String? = commandByRegion[region]
}

/** Selects configured shard targets, cycles /rtp regions and confirms a landing after movement. */
class DonutGoliathRtp : DonutUtilityModule("Donut Goliath RTP", "Cycles /rtp regions until you land in one of the selected DonutSMP shards.") {
    private val targets by textList("Targets", mutableListOf())
    private var pendingRegion: String? = null
    private var ticksSinceSend = 0
    private var settleTicks = 0
    private var sentCount = 0
    private var lastPosition: Pair<Double, Double>? = null
    private var lastWorld: Any? = null

    override fun onEnabled() {
        pendingRegion = null
        ticksSinceSend = 0
        settleTicks = 0
        sentCount = 0
        lastPosition = null
        lastWorld = null
        if (DonutGoliathRoutes.parse(targets).isEmpty()) {
            chat("Add one or more shard labels such as ‘EU Central 1’ to Targets, then enable this module.", this)
            enabled = false
        }
    }

    override fun onUtilitySessionEnded() {
        pendingRegion = null
        ticksSinceSend = 0
        settleTicks = 0
        sentCount = 0
        lastPosition = null
        lastWorld = null
    }

    override fun onDisabled() = onUtilitySessionEnded()

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        val player = mc.player ?: return@handler
        val world = mc.level ?: return@handler
        val connection = mc.connection ?: return@handler
        val chosen = DonutGoliathRoutes.parse(targets)
        if (chosen.isEmpty()) { enabled = false; return@handler }
        if (lastWorld !== world) {
            lastWorld = world
            lastPosition = player.x to player.z
            pendingRegion = null
            settleTicks = 15
            return@handler
        }

        val previous = lastPosition
        val teleported = previous != null && (player.x - previous.first).let { dx ->
            val dz = player.z - previous.second
            dx * dx + dz * dz > 10_000.0
        }
        lastPosition = player.x to player.z

        if (pendingRegion != null && !teleported) {
            if (++ticksSinceSend >= 100) sendRtp(connection, pendingRegion!!)
            return@handler
        }
        if (teleported) {
            pendingRegion = null
            settleTicks = 15
        }

        val dimension = world.dimension().identifier().toString()
        val here = DonutGoliathRoutes.at(player.x, player.z, dimension)
        if (here != null && here in chosen) {
            chat("Landed in ${here.region} ${here.number} after $sentCount RTP attempts.", this)
            enabled = false
            return@handler
        }
        if (settleTicks > 0) { settleTicks--; return@handler }

        val next = DonutGoliathRoutes.nextRegion(here?.region, chosen) ?: run { enabled = false; return@handler }
        val command = DonutGoliathRoutes.commandFor(next) ?: run { enabled = false; return@handler }
        sendRtp(connection, command)
        pendingRegion = command
    }

    private fun sendRtp(connection: net.minecraft.client.multiplayer.ClientPacketListener, region: String) {
        connection.sendChat("rtp $region")
        ticksSinceSend = 0
        sentCount++
    }
}
