package dev.donut.addon.expansion.building

import dev.donut.addon.expansion.search.growth.DonutWorldFlagSnapshot

/** A snapshot source for optional suspect-chunk modules used by the tunnel planner. */
fun interface TunnelFlaggedChunkProvider {
    fun snapshot(): DonutWorldFlagSnapshot?
}

/**
 * Integration point for the world-scoped Donut suspect and growth scans.
 * Consumers must validate the snapshot against their exact current world before using its keys.
 */
object TunnelChunkTargetProviders {
    @Volatile
    var susChunk: TunnelFlaggedChunkProvider? = null

    @Volatile
    var betterSusChunk: TunnelFlaggedChunkProvider? = null

    fun snapshotFor(world: Any, dimensionId: String): DonutWorldFlagSnapshot? {
        val snapshot = betterSusChunk?.snapshot() ?: return null
        return snapshot.takeIf { it.belongsTo(world, dimensionId) }
    }

    fun susSnapshotFor(world: Any, dimensionId: String): DonutWorldFlagSnapshot? {
        val snapshot = susChunk?.snapshot() ?: return null
        return snapshot.takeIf { it.belongsTo(world, dimensionId) }
    }
}
