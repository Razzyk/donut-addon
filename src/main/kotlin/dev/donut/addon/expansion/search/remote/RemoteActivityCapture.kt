package dev.donut.addon.expansion.search.remote

import dev.donut.addon.compat.DonutEventListener

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData
import net.minecraft.world.level.chunk.status.ChunkStatus
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.floor

/** Orders remote packet capture tasks with vanilla client-thread packet handling. */
object RemoteActivityCapture : DonutEventListener {
    private data class PendingPacket(val packet: Any, val receivedNanos: Long)
    private data class PendingEntityActivity(
        val packet: Any,
        val receivedNanos: Long,
        val sessionGeneration: Long,
        val worldGeneration: Long,
    )
    private const val MAX_PENDING_PACKET_TASKS = 8192
    private const val MAX_PENDING_ENTITY_PACKETS = 8192
    private const val MAX_ENTITY_PACKETS_PER_TICK = 512
    private val boundaryLock = Any()
    private val pendingEntityLock = Any()
    private val pendingEntityPackets = ArrayDeque<PendingEntityActivity>()
    private var pendingEntityOverflow = false
    private val sessionGeneration = AtomicLong(1L)
    private val connectionEpoch = AtomicLong(1L)
    private val worldEpoch = AtomicLong(1L)
    @Volatile private var stopped = false
    private var lastLevel: Any? = null
    private var levelInitialized = false

    private val packetExecutor = Executor { task ->
        Minecraft.getInstance().execute(task)
    }

    private val dispatchGuard = object : RemoteOrderedPacketDispatcher.Guard {
        override fun stopped() = this@RemoteActivityCapture.stopped
        override fun connection(): Any? = Minecraft.getInstance().connection
        override fun connectionEpoch() = this@RemoteActivityCapture.connectionEpoch.get()
        override fun sessionEpoch() = this@RemoteActivityCapture.sessionGeneration.get()
        override fun worldGeneration() = this@RemoteActivityCapture.worldEpoch.get()
    }

    private val packetDispatcher = RemoteOrderedPacketDispatcher<PendingPacket>(
        MAX_PENDING_PACKET_TASKS,
        packetExecutor,
        Runnable { },
    )

    private val dispatchCapture = RemoteOrderedPacketDispatcher.Capture<PendingPacket> { pending, generation ->
        capturePacket(pending, generation)
    }

    @Suppress("unused")
    private val packets = handler<PacketEvent> { event ->
        if (stopped) return@handler
        if (event.origin != TransferOrigin.INCOMING || !event.original) return@handler
        val packet = event.packet
        if (!isCapturedPacket(packet)) return@handler
        val connection = Minecraft.getInstance().connection
        packetDispatcher.enqueue(
            PendingPacket(packet, System.nanoTime()),
            connection,
            connectionEpoch.get(),
            sessionGeneration.get(),
            dispatchGuard,
            dispatchCapture,
        )
    }

    private fun isCapturedPacket(packet: Any): Boolean = when (packet) {
        is ClientboundLightUpdatePacket,
        is ClientboundBlockUpdatePacket,
        is ClientboundSectionBlocksUpdatePacket,
        is ClientboundBlockEntityDataPacket,
        is ClientboundSoundPacket,
        is ClientboundLevelParticlesPacket,
        is ClientboundAddEntityPacket,
        is ClientboundMoveEntityPacket,
        is ClientboundEntityPositionSyncPacket,
        is ClientboundRotateHeadPacket,
        is ClientboundSetEntityDataPacket,
        is ClientboundEntityEventPacket,
        is ClientboundSetEquipmentPacket,
        is ClientboundLevelChunkWithLightPacket -> true
        else -> false
    }

    private fun capturePacket(pending: PendingPacket, generation: Long) {
        if (stopped || generation != worldEpoch.get()) return
        val packet = pending.packet
        val now = pending.receivedNanos
        val session = sessionGeneration.get()
        when (packet) {
            is ClientboundLightUpdatePacket -> offerLight(packet.x(), packet.z(), packet.lightData(), now, generation = session, worldGeneration = generation)
            is ClientboundBlockUpdatePacket -> offer(packet.pos.x, packet.pos.z, RemoteActivityLedger.Kind.BLOCK, now, session, generation)
            is ClientboundSectionBlocksUpdatePacket -> packet.runUpdates { pos, _ ->
                offer(pos.x, pos.z, RemoteActivityLedger.Kind.BLOCK, now, session, generation)
            }
            is ClientboundBlockEntityDataPacket -> offer(packet.pos.x, packet.pos.z, RemoteActivityLedger.Kind.BLOCK, now, session, generation)
            is ClientboundSoundPacket -> offer(floor(packet.x).toInt(), floor(packet.z).toInt(), RemoteActivityLedger.Kind.SOUND, now, session, generation)
            is ClientboundLevelParticlesPacket -> offer(floor(packet.x).toInt(), floor(packet.z).toInt(), RemoteActivityLedger.Kind.PARTICLE, now, session, generation)
            is ClientboundAddEntityPacket -> offer(floor(packet.x).toInt(), floor(packet.z).toInt(), RemoteActivityLedger.Kind.ENTITY, now, session, generation)
            is ClientboundMoveEntityPacket,
            is ClientboundEntityPositionSyncPacket,
            is ClientboundRotateHeadPacket,
            is ClientboundSetEntityDataPacket,
            is ClientboundEntityEventPacket,
            is ClientboundSetEquipmentPacket -> offerEntityActivity(packet, now, session, generation)
            is ClientboundLevelChunkWithLightPacket -> offerLight(packet.x(), packet.z(), packet.lightData(), now, RemoteActivityLedger.Kind.CHUNK, session, generation)
            else -> Unit
        }
    }

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        if (stopped) return@handler
        val client = Minecraft.getInstance()
        val level = client.level
        if (!levelInitialized) {
            // Preserve incoming observations until the first client tick establishes identity.
            lastLevel = level
            levelInitialized = true
        } else if (level !== lastLevel) {
            onWorldChanging(level)
        }
        val dispatcherOverflowed = packetDispatcher.consumeOverflow()
        if (dispatcherOverflowed) {
            synchronized(boundaryLock) {
                if (!stopped) clearSessionState()
            }
        }
        val activeLevel = level ?: return@handler
        val player = client.player ?: return@handler
        if (player.level() !== activeLevel || client.connection == null) return@handler
        // The bucket is selected only after the new world has a matching player and connection.
        RemoteKnownBaseStore.get().selectCurrent(client, activeLevel)
        val centerX = player.blockX shr 4
        val centerZ = player.blockZ shr 4
        val simulation = client.options.simulationDistance().get().coerceAtLeast(1)
        val ringStart = simulation + 2
        val view = client.options.renderDistance().get().coerceAtLeast(ringStart + 1)
        val overflowed = synchronized(pendingEntityLock) {
            val value = pendingEntityOverflow
            pendingEntityOverflow = false
            value
        }
        if (overflowed) RemoteActivityState.FROZEN.clear()
        var entityPackets = 0
        while (entityPackets < MAX_ENTITY_PACKETS_PER_TICK) {
            val pending = synchronized(pendingEntityLock) { pendingEntityPackets.pollFirst() } ?: break
            if (pending.sessionGeneration != sessionGeneration.get() || pending.worldGeneration != worldEpoch.get()) {
                entityPackets++
                continue
            }
            val entityId = when (val packet = pending.packet) {
                is ClientboundMoveEntityPacket -> packet.getEntity(activeLevel)?.id
                is ClientboundEntityPositionSyncPacket -> packet.id()
                is ClientboundRotateHeadPacket -> packet.getEntity(activeLevel)?.id
                is ClientboundSetEntityDataPacket -> packet.id()
                is ClientboundEntityEventPacket -> packet.getEntity(activeLevel)?.id
                is ClientboundSetEquipmentPacket -> packet.getEntity()
                else -> null
            }
            if (entityId != null) RemoteActivityState.FROZEN.offerActivity(entityId, pending.receivedNanos)
            entityPackets++
        }
        RemoteActivityState.FROZEN.drainActivities(MAX_ENTITY_PACKETS_PER_TICK)
        for (signal in RemoteActivityState.INBOX.drain(256)) {
            if (signal.sessionGeneration() != sessionGeneration.get() || signal.worldGeneration() != worldEpoch.get()) continue
            val chunkX = signal.x() shr 4
            val chunkZ = signal.z() shr 4
            val distance = maxOf(abs(chunkX - centerX), abs(chunkZ - centerZ))
            if (distance < ringStart || distance > view) continue
            if (activeLevel.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null) continue
            RemoteActivityState.LEDGER.observe(signal.x(), signal.z(), signal.kind(), signal.receivedNanos())
            signal.lightSummary()?.let { summary ->
                val chunkKey = RemoteKnownBaseStore.chunkKey(chunkX, chunkZ)
                val wasAlreadyFlagged = RemoteActivityState.LIGHT.isFlaggedOrHeld(
                    chunkKey, activeLevel.getSectionsCount(), activeLevel.getMinSectionY()
                )
                RemoteActivityState.LIGHT.observe(chunkX, chunkZ, summary, signal.receivedNanos(), player.y < 0.0)
                val knownBases = RemoteKnownBaseStore.get()
                if (!knownBases.flagAllowed(chunkKey, wasAlreadyFlagged)
                    && RemoteActivityState.LIGHT.isFlaggedOrHeld(
                        chunkKey, activeLevel.getSectionsCount(), activeLevel.getMinSectionY()
                    )) {
                    RemoteActivityState.LIGHT.removeChunk(chunkKey)
                }
            }
        }
        RemoteActivityState.LEDGER.pruneOlderThan(System.nanoTime(), 120_000_000_000L)
        RemoteActivityState.LIGHT.processPending(activeLevel.getSectionsCount(), activeLevel.getMinSectionY(), { x, z, sectionY ->
            isAirlessAround(activeLevel, x, z, sectionY)
        }, 2)
    }

    private fun offer(
        x: Int,
        z: Int,
        kind: RemoteActivityLedger.Kind,
        now: Long,
        generation: Long,
        worldGeneration: Long,
    ) {
        synchronized(boundaryLock) {
            if (stopped || generation != sessionGeneration.get() || worldGeneration != worldEpoch.get()) return
            RemoteActivityState.INBOX.offer(RemoteActivityInbox.Signal(x, z, kind, now, null, generation, worldGeneration))
        }
    }

    private fun offerEntityActivity(packet: Any, now: Long, generation: Long, worldGeneration: Long) {
        synchronized(boundaryLock) {
            if (stopped || generation != sessionGeneration.get() || worldGeneration != worldEpoch.get()) return
            synchronized(pendingEntityLock) {
                if (pendingEntityPackets.size >= MAX_PENDING_ENTITY_PACKETS) {
                    pendingEntityOverflow = true
                    return
                }
                pendingEntityPackets.addLast(PendingEntityActivity(packet, now, generation, worldGeneration))
            }
        }
    }

    private fun clearPendingEntityActivities() {
        synchronized(pendingEntityLock) {
            pendingEntityPackets.clear()
            pendingEntityOverflow = false
        }
    }

    private fun clearSessionState() {
        RemoteActivityState.INBOX.clear()
        RemoteActivityState.LEDGER.clear()
        RemoteActivityState.LIGHT.clear()
        RemoteActivityState.FROZEN.clear()
        clearPendingEntityActivities()
    }

    /** Clears packet capture state at a search-session boundary without toggling the listener. */
    fun endSearchSession() {
        synchronized(boundaryLock) {
            if (stopped) return
            sessionGeneration.incrementAndGet()
            clearSessionState()
            lastLevel = null
            levelInitialized = false
        }
    }

    /**
     * Clears world-scoped evidence after vanilla applies a world change. A non-null respawn keeps
     * queued captures on the same connection and lets them read the new world generation in order.
     */
    fun onWorldChanging(world: net.minecraft.client.multiplayer.ClientLevel?) {
        synchronized(boundaryLock) {
            if (stopped) return
            worldEpoch.incrementAndGet()
            if (world == null) connectionEpoch.incrementAndGet()
            clearSessionState()
            lastLevel = world
            levelInitialized = true
        }
        if (world == null) RemoteKnownBaseStore.endSharedSession()
    }

    /** Permanently prevents late packet callbacks after listener teardown from restoring session state. */
    fun shutdown() {
        synchronized(boundaryLock) {
            if (stopped) return
            stopped = true
            sessionGeneration.incrementAndGet()
            connectionEpoch.incrementAndGet()
            worldEpoch.incrementAndGet()
            clearSessionState()
            lastLevel = null
            levelInitialized = false
        }
    }

    private fun offerLight(
        chunkX: Int,
        chunkZ: Int,
        data: ClientboundLightUpdatePacketData?,
        now: Long,
        kind: RemoteActivityLedger.Kind = RemoteActivityLedger.Kind.LIGHT,
        generation: Long,
        worldGeneration: Long,
    ) {
        if (data == null) return
        val summary = RemoteLightClassifier.summarize(data.skyYMask(), data.emptySkyYMask(), data.skyUpdates())
        synchronized(boundaryLock) {
            if (stopped || generation != sessionGeneration.get() || worldGeneration != worldEpoch.get()) return
            RemoteActivityState.INBOX.offer(RemoteActivityInbox.Signal(
                chunkX * 16 + 8, chunkZ * 16 + 8, kind, now, summary, generation, worldGeneration
            ))
        }
    }

    private fun isAirlessAround(level: net.minecraft.client.multiplayer.ClientLevel, chunkX: Int, chunkZ: Int, sectionY: Int): Boolean {
        val sides = arrayOf(
            intArrayOf(0, 0, 0), intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0),
            intArrayOf(0, 1, 0), intArrayOf(0, -1, 0), intArrayOf(0, 0, 1), intArrayOf(0, 0, -1)
        )
        for (side in sides) {
            val cx = chunkX + side[0]
            val cz = chunkZ + side[2]
            val sy = sectionY + side[1]
            if (sy < level.getMinSectionY() || sy >= level.getMinSectionY() + level.getSectionsCount()) continue
            val access = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false)
            if (access !is net.minecraft.world.level.chunk.LevelChunk) return false
            val index = sy - (access.getMinY() shr 4)
            val section = access.getSections().getOrNull(index) ?: return false
            if (section.hasOnlyAir()) return false
            if (!section.getStates().maybeHas(net.minecraft.world.level.block.state.BlockState::isAir)) continue
            for (y in 0..15) for (z in 0..15) for (x in 0..15) {
                if (section.getBlockState(x, y, z).isAir) return false
            }
        }
        return true
    }
}
