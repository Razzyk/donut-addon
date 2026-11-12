package dev.donut.addon.expansion.search

import dev.donut.addon.compat.DonutEventListener

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket

/** Always-on packet intake: the view module may be disabled when a report first arrives. */
object SearchPacketCapture : DonutEventListener {
    @Volatile private var stopped = false
    @Volatile private var generation = 0L
    private val boundary = Any()
    private var lastWorld: Any? = null
    private var worldInitialized = false

    @Suppress("unused")
    private val packets = handler<PacketEvent> { event ->
        if (stopped || event.origin != TransferOrigin.INCOMING || !event.original) return@handler
        val epoch = generation
        when (val packet = event.packet) {
            is ClientboundBlockEntityDataPacket -> {
                val typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(packet.type).toString()
                val pos = packet.pos
                val preview = packet.tag?.toString()?.take(240).orEmpty()
                offerCurrent(epoch) { SearchFeatures.offerBlockEntityReport(pos.x, pos.y, pos.z, typeId, preview) }
            }
            is ClientboundLevelChunkWithLightPacket -> {
                packet.chunkData.forEachBlockEntityTag(packet.x, packet.z) { pos, type, tag ->
                    val typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).toString()
                    val preview = tag?.toString()?.take(240).orEmpty()
                    offerCurrent(epoch) { SearchFeatures.offerBlockEntityReport(pos.x, pos.y, pos.z, typeId, preview) }
                }
            }
            is ClientboundBlockEventPacket -> {
                val typeId = entityTypeForBlock(packet.block) ?: return@handler
                val pos = packet.pos
                offerCurrent(epoch) { SearchFeatures.offerBlockClue(pos.x, pos.y, pos.z, typeId,
                    dev.donut.addon.expansion.search.evidence.BlockSignalLedger.Source.BLOCK_EVENT) }
            }
            is ClientboundSoundPacket -> {
                val soundId = runCatching { packet.sound.value().location.toString() }.getOrDefault("")
                val typeId = entityTypeForSound(soundId) ?: return@handler
                offerCurrent(epoch) { SearchFeatures.offerBlockClue(kotlin.math.floor(packet.x).toInt(), kotlin.math.floor(packet.y).toInt(),
                    kotlin.math.floor(packet.z).toInt(), typeId,
                    dev.donut.addon.expansion.search.evidence.BlockSignalLedger.Source.SOUND) }
            }
            else -> return@handler
        }
    }

    private val worlds = handler<WorldChangeEvent> { event ->
        synchronized(boundary) {
            if (stopped) return@handler
            generation++
            SearchFeatures.clearPendingEvidence()
            lastWorld = event.world
            worldInitialized = true
        }
    }

    @Suppress("unused")
    private val ledgerTick = handler<GameTickEvent> {
        if (stopped) return@handler
        val client = Minecraft.getInstance() ?: return@handler
        val level = client.level
        if (!worldInitialized) {
            // Packets may already be queued when the first tick establishes the world identity.
            lastWorld = level
            worldInitialized = true
        } else if (level !== lastWorld) {
            SearchFeatures.clearPendingEvidence()
            lastWorld = level
        }
        for (signal in SearchFeatures.BLOCK_SIGNAL_INBOX.drain(256)) {
            SearchFeatures.BLOCK_SIGNAL_LEDGER.observe(
                signal.x(), signal.y(), signal.z(), signal.typeId(), signal.payloadPreview(),
                signal.source(), signal.receivedNanos(),
            )
        }
        SearchFeatures.BLOCK_SIGNAL_LEDGER.pruneOlderThan(System.nanoTime(), 10L * 60L * 1_000_000_000L)
    }

    fun shutdown() {
        synchronized(boundary) {
            stopped = true
            generation++
            lastWorld = null
            worldInitialized = false
            SearchFeatures.clearPendingEvidence()
        }
    }

    /** A packet decoded across a world transition cannot enter the next world's ledger. */
    private inline fun offerCurrent(epoch: Long, offer: () -> Unit) {
        synchronized(boundary) { if (!stopped && epoch == generation) offer() }
    }

    override fun unregister() {
        shutdown()
        super<DonutEventListener>.unregister()
    }

    private fun entityTypeForBlock(block: net.minecraft.world.level.block.Block): String? = when (
        BuiltInRegistries.BLOCK.getKey(block).path
    ) {
        "chest", "trapped_chest" -> "minecraft:chest"
        "ender_chest" -> "minecraft:ender_chest"
        "barrel" -> "minecraft:barrel"
        "hopper" -> "minecraft:hopper"
        "dispenser" -> "minecraft:dispenser"
        "dropper" -> "minecraft:dropper"
        "crafter" -> "minecraft:crafter"
        "furnace" -> "minecraft:furnace"
        "blast_furnace" -> "minecraft:blast_furnace"
        "smoker" -> "minecraft:smoker"
        "brewing_stand" -> "minecraft:brewing_stand"
        "spawner" -> "minecraft:mob_spawner"
        else -> null
    }

    private fun entityTypeForSound(soundId: String): String? = when {
        soundId.startsWith("minecraft:block.chest.") || soundId.startsWith("minecraft:block.trapped_chest.") -> "minecraft:chest"
        soundId.startsWith("minecraft:block.ender_chest.") -> "minecraft:ender_chest"
        soundId.startsWith("minecraft:block.barrel.") -> "minecraft:barrel"
        soundId.startsWith("minecraft:block.shulker_box.") -> "minecraft:shulker_box"
        soundId.startsWith("minecraft:block.hopper.") -> "minecraft:hopper"
        soundId.startsWith("minecraft:block.dispenser.") -> "minecraft:dispenser"
        soundId.startsWith("minecraft:block.dropper.") -> "minecraft:dropper"
        soundId.startsWith("minecraft:block.crafter.") -> "minecraft:crafter"
        soundId.startsWith("minecraft:block.brewing_stand.") -> "minecraft:brewing_stand"
        else -> null
    }
}
