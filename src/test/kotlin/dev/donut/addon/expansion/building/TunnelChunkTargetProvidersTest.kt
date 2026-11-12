package dev.donut.addon.expansion.building

import dev.donut.addon.expansion.search.growth.DonutWorldFlagSnapshot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TunnelChunkTargetProvidersTest {
    @AfterEach
    fun clearProvider() {
        TunnelChunkTargetProviders.susChunk = null
        TunnelChunkTargetProviders.betterSusChunk = null
    }

    @Test
    fun `accepts flags only for the exact world and dimension`() {
        val world = Any()
        val keys = setOf(17L, -3L)
        TunnelChunkTargetProviders.betterSusChunk = TunnelFlaggedChunkProvider {
            DonutWorldFlagSnapshot("minecraft:the_nether", world, 8L, keys)
        }

        assertEquals(keys, TunnelChunkTargetProviders.snapshotFor(world, "minecraft:the_nether")?.chunkKeys())
        assertNull(TunnelChunkTargetProviders.snapshotFor(Any(), "minecraft:the_nether"))
        assertNull(TunnelChunkTargetProviders.snapshotFor(world, "minecraft:overworld"))
    }

    @Test
    fun `an unavailable provider has no target snapshot`() {
        assertNull(TunnelChunkTargetProviders.snapshotFor(Any(), "minecraft:the_nether"))
        assertNull(TunnelChunkTargetProviders.susSnapshotFor(Any(), "minecraft:the_nether"))
    }

    @Test
    fun `sus and better sources keep their own flags and reject stale worlds`() {
        val world = Any()
        TunnelChunkTargetProviders.susChunk = TunnelFlaggedChunkProvider {
            DonutWorldFlagSnapshot("minecraft:overworld", world, 1L, setOf(41L))
        }
        TunnelChunkTargetProviders.betterSusChunk = TunnelFlaggedChunkProvider {
            DonutWorldFlagSnapshot("minecraft:overworld", world, 2L, setOf(82L))
        }
        assertEquals(setOf(41L), TunnelChunkTargetProviders.susSnapshotFor(world, "minecraft:overworld")?.chunkKeys())
        assertEquals(setOf(82L), TunnelChunkTargetProviders.snapshotFor(world, "minecraft:overworld")?.chunkKeys())
        assertNull(TunnelChunkTargetProviders.susSnapshotFor(Any(), "minecraft:overworld"))
        assertNull(TunnelChunkTargetProviders.susSnapshotFor(world, "minecraft:the_nether"))
    }
}
