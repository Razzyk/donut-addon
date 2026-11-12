package dev.donut.addon.expansion.presence

import dev.donut.addon.AddonCategories
import dev.donut.addon.hud.region.RegionMapData
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.notification
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.player.RemotePlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundExplodePacket
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.Vec3
import net.minecraft.sounds.SoundEvents
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.floor
import kotlin.math.sqrt

/** Combines tab transitions, nearby entity checks and filtered world feedback into presence alerts. */
class DonutPresenceWatch : DonutPresenceModule("Donut Presence Watch") {
    data class HudEntry(val name: String, val reason: String, val score: Int)
    private enum class HintKind { TAB_ORDER, TEAM_ADDED, TEAM_REMOVED, TAB_ADDED, TAB_REMOVED }
    private data class Hint(val kind: HintKind, val id: UUID?, val name: String?, val rank: Int, val at: Long)
    private data class GameModeHint(val id: UUID, val mode: net.minecraft.world.level.GameType?)
    private data class SignalNotice(val kind: PresenceEvidenceLedger.Kind, val message: String, val who: String, val weight: Int,
                                    val at: Long, val id: UUID? = null, val detail: String = "")
    private data class RecentBlast(val center: Vec3, val range: Double, val at: Long)
    private data class HiddenRecord(val id: UUID, val name: String, val spectator: Boolean, val detail: String, val proofId: UUID?, val createdTick: Int,
                                    val createdAt: Long = System.currentTimeMillis(), var announced: Boolean = false)
    private data class Detection(val name: String, var reason: String, var score: Int, var expiresAt: Long)

    private val showHud by boolean("Show HUD", true).onChanged { PresenceFeatures.syncHudState() }
    private val vanishState = DonutVanishState()
    private val ledger = PresenceEvidenceLedger()
    private val packetQueue = ConcurrentLinkedQueue<SignalNotice>()
    private val hints = ConcurrentLinkedQueue<Hint>()
    private val gameModes = ConcurrentLinkedQueue<GameModeHint>()
    private val deferredHints = arrayListOf<Hint>()
    private val tabMoves = ArrayDeque<Hint>()
    private val recentTeamEvents = ArrayDeque<Long>()
    private val tabNames = linkedMapOf<UUID, String>()
    private val tabArrivals = hashMapOf<String, Long>()
    private val recentSignalMessages = ArrayDeque<Long>()
    private val announceCooldowns = hashMapOf<String, Long>()
    private val detections = linkedMapOf<String, Detection>()
    private val hiddenRecords = linkedMapOf<UUID, HiddenRecord>()
    private val recentChats = ArrayDeque<Pair<String, Long>>()
    private val placedByMe = hashMapOf<Long, Long>()
    private val knownListedAt = hashMapOf<UUID, Long>()
    private val blastHistory = ArrayDeque<RecentBlast>()
    private val weakParticleBursts = hashMapOf<String, ArrayDeque<Long>>()
    private val mechanismClues = hashMapOf<Long, Long>()
    private var connected: ClientPacketListener? = null
    private var activeWorld: Any? = null
    private var activePlayer: Any? = null
    private var shard: String? = null
    private var ticks = 0
    private var lastLocalActionAt = 0L
    private var lastContainerAt = 0L
    private var quietUntil = 0L
    private var serverEmitsLeaves = false
    private var lastCriticalAt = 0L
    private var criticalUntil = 0L
    private var criticalName = ""
    private var totalTicks = 0

    init { literalDescription { "Combines Donut staff tab, visibility and nearby world signals into short lived alerts." } }

    val shouldShowHud get() = showHud
    override fun onEnabled() { resetState(); connected = mc.connection; activeWorld = mc.level; activePlayer = mc.player; PresenceFeatures.syncHudState() }
    override fun onDisabled() { resetState(); connected = null; activeWorld = null; activePlayer = null; PresenceFeatures.syncHudState() }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        val packet = event.packet
        if (event.origin == TransferOrigin.OUTGOING) {
            observeLocalAction(packet)
            return@handler
        }
        if (event.origin != TransferOrigin.INCOMING) return@handler
        when (packet) {
            is ClientboundPlayerInfoRemovePacket -> {
                val ids = packet.profileIds
                ids.forEach { id ->
                    packetQueue += SignalNotice(PresenceEvidenceLedger.Kind.HIDDEN_PLAYER, "", "", 0, System.currentTimeMillis(), id, "tab-remove:${ids.size}")
                    hints += Hint(HintKind.TAB_REMOVED, id, null, -1, System.currentTimeMillis())
                }
            }
            is ClientboundPlayerInfoUpdatePacket -> observeTabUpdate(packet)
            is ClientboundSetEntityDataPacket -> packetQueue += SignalNotice(PresenceEvidenceLedger.Kind.INVISIBLE_BODY, "", "", 0, System.currentTimeMillis(), detail = "entity:${packet.id}")
            is ClientboundExplodePacket -> packetQueue += SignalNotice(PresenceEvidenceLedger.Kind.SOUND, "", "", 0, System.currentTimeMillis(), detail = "blast:${packet.center.x},${packet.center.y},${packet.center.z},${packet.radius}")
            is ClientboundSoundPacket -> {
                val soundId = runCatching { packet.sound.value().location.toString() }.getOrDefault("")
                packetQueue += SignalNotice(PresenceEvidenceLedger.Kind.SOUND, "", "", 0, System.currentTimeMillis(), detail = "pos:${packet.x},${packet.y},${packet.z}:$soundId")
            }
            is ClientboundSoundEntityPacket -> {
                val soundId = runCatching { packet.sound.value().location.toString() }.getOrDefault("")
                packetQueue += SignalNotice(PresenceEvidenceLedger.Kind.SOUND, "", "", 0, System.currentTimeMillis(), detail = "entity:${packet.id}:$soundId")
            }
            is ClientboundLevelParticlesPacket -> {
                val particleId = runCatching { BuiltInRegistries.PARTICLE_TYPE.getKey(packet.particle.type).toString() }.getOrDefault("")
                packetQueue += SignalNotice(PresenceEvidenceLedger.Kind.PARTICLE, "", "", 0, System.currentTimeMillis(), detail = "pos:${packet.x},${packet.y},${packet.z}:$particleId")
            }
            else -> {
                if (looksLikeSystemChat(packet)) {
                    reflectComponent(packet)?.let { line ->
                        val type = if (reflectTranslationKey(packet) == "multiplayer.player.left") "left:" else "chat:"
                        packetQueue += SignalNotice(PresenceEvidenceLedger.Kind.HIDDEN_PLAYER, "", "", 0, System.currentTimeMillis(), detail = "$type$line")
                    }
                }
                if (packet.javaClass.simpleName.contains("SetPlayerTeam")) observeTeamPacket(packet)
            }
        }
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val self = mc.player
        val level = mc.level
        val network = mc.connection
        if (self == null || level == null || network == null) { resetState(); connected = null; activeWorld = null; activePlayer = null; return@handler }
        val nextShard = shardKey(self.x, self.z)
        if (network !== connected || level !== activeWorld || self !== activePlayer || shard != null && shard != nextShard) {
            resetState(); connected = network; activeWorld = level; activePlayer = self
        }
        shard = nextShard
        ticks++
        totalTicks++
        if (self.containerMenu !== self.inventoryMenu) lastContainerAt = System.currentTimeMillis()
        if (totalTicks % 10 == 0) trackTabIdentities()
        processPackets(network)
        processGameModes(network, self.uuid)
        processHints(network, self.uuid)
        processDelayedRemovals(network)
        if (totalTicks % 5 == 0) scanForInvisibleStaff()
        processHiddenRecords(network)
        prune()
    }

    fun hudRows(): List<HudEntry> {
        val now = System.currentTimeMillis()
        return buildList {
            if (now < criticalUntil) add(HudEntry("CRITICAL", criticalName, 100))
            detections.values.asSequence().filter { it.expiresAt > now }
                .sortedWith(compareByDescending<Detection> { it.score }.thenBy { it.name.lowercase() })
                .distinctBy { it.name.lowercase() to it.reason }.take(4).forEach { add(HudEntry(it.name, it.reason, it.score)) }
        }
    }

    private fun observeTabUpdate(packet: ClientboundPlayerInfoUpdatePacket) {
        val actions = packet.actions()
        val entries = packet.entries()
        val now = System.currentTimeMillis()
        if (ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER in actions) {
            entries.forEach { entry ->
                val profile = entry.profile ?: return@forEach
                hints += Hint(HintKind.TAB_ADDED, profile.id, profile.name, -1, now)
            }
        } else if (actions.containsAll(setOf(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER)) && entries.size < 4) {
            entries.forEach { entry -> hints += Hint(HintKind.TAB_ORDER, entry.profileId, null, -1, now) }
        }
        if (ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED in actions) {
            val hidden = entries.filterNot { it.listed }
            if (hidden.isNotEmpty() && hidden.size < 4) hidden.forEach { packetQueue += SignalNotice(
                PresenceEvidenceLedger.Kind.HIDDEN_PLAYER, "", "", 0, now, it.profileId, "tab-hidden"
            ) }
        }
        if (ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE in actions && ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER !in actions) {
            entries.forEach { if (it.gameMode != null) gameModes += GameModeHint(it.profileId, it.gameMode) }
        }
    }

    private fun observeTeamPacket(packet: Any) {
        val values = packet.javaClass.methods.filter { it.parameterCount == 0 }.mapNotNull { method ->
            runCatching { method.invoke(packet) }.getOrNull()
        }
        val rankTeam = values.filterIsInstance<String>().firstOrNull { TEAM_NAME.matches(it) } ?: return
        val action = values.filterIsInstance<Enum<*>>().firstOrNull()?.name?.uppercase().orEmpty()
        val rank = rankTeam.substringBefore('-').toIntOrNull() ?: return
        if (rank > 8) return
        val playerNames = values.filterIsInstance<Collection<*>>().flatten().filterIsInstance<String>()
        if (action.contains("ADD") || action.contains("CREATE")) {
            if (playerNames.isNotEmpty()) playerNames.forEach { hints += Hint(HintKind.TEAM_ADDED, null, it, rank, System.currentTimeMillis()) }
            else rankTeam.substringAfter('-').substringBeforeLast('-').takeIf(String::isNotBlank)?.let { hints += Hint(HintKind.TEAM_ADDED, null, it, rank, System.currentTimeMillis()) }
        } else if (action.contains("REMOVE") || action.contains("DELETE")) {
            rankTeam.substringAfter('-').substringBeforeLast('-').takeIf(String::isNotBlank)?.let { hints += Hint(HintKind.TEAM_REMOVED, null, it, rank, System.currentTimeMillis()) }
        }
    }

    private fun processPackets(network: ClientPacketListener) {
        while (true) {
            val notice = packetQueue.poll() ?: break
            if (notice.detail.startsWith("chat:") || notice.detail.startsWith("left:")) {
                val translatedDeparture = notice.detail.startsWith("left:")
                val message = notice.detail.substringAfter(':')
                recentChats.addLast(message to System.currentTimeMillis())
                if (translatedDeparture) {
                    serverEmitsLeaves = true
                    val departed = vanishState.observeLeaveMessage(message, System.currentTimeMillis())
                    departed.forEach { id -> detections.remove(id.toString()); hiddenRecords.remove(id) }
                }
                continue
            }
            when {
                notice.detail.startsWith("blast:") -> rememberBlast(notice.detail)
                notice.detail.startsWith("entity:") && notice.detail.substringAfter(':').substringBefore(':').toIntOrNull() != null -> {
                    if (notice.detail.count { it == ':' } == 1) checkInvisibleEntity(notice.detail.substringAfter(':').toInt())
                    else checkEntitySound(notice.detail)
                }
                notice.detail.startsWith("pos:") -> inspectPositionSignal(notice)
                notice.detail == "tab-hidden" -> notice.id?.let { inspectHiddenTab(it, immediate = true) }
                notice.detail.startsWith("tab-remove:") -> {
                    if (notice.detail.substringAfter(':').toIntOrNull() == 1) notice.id?.let { vanishState.observeRemoval(it, ticks, System.currentTimeMillis()) }
                }
                else -> Unit
            }
        }
        while (packetQueue.size > 4096) packetQueue.poll()
    }

    private fun processGameModes(network: ClientPacketListener, selfId: UUID) {
        while (true) {
            val change = gameModes.poll() ?: break
            if (change.id == selfId) continue
            val info = network.getPlayerInfo(change.id) ?: continue
            val name = info.profile.name ?: continue
            if (!isStaffCandidate(change.id, name, info)) continue
            val spectator = change.mode == net.minecraft.world.level.GameType.SPECTATOR
            if (spectator) {
                if (credible(change.id, name)) reportHidden(change.id, name, true, "switched to spectator", 100)
                else chat("$name switched to spectator mode.", this)
            } else {
                val recovered = vanishState.visibleAgain(change.id)
                val wasHidden = hiddenRecords.remove(change.id)
                if (recovered != null || wasHidden?.announced == true) news(name, "is visible again", "")
                else chat("$name switched game mode to ${change.mode?.name?.lowercase() ?: "unknown"}.", this)
            }
            tabArrivals[name.lowercase()] = System.currentTimeMillis()
            PresenceFeatures.staffRoster.proveNearby(change.id, name)
        }
    }

    private fun processHints(network: ClientPacketListener, selfId: UUID) {
        while (true) {
            val hint = hints.poll() ?: break
            when (hint.kind) {
                HintKind.TAB_ORDER, HintKind.TAB_REMOVED -> tabMoves.addLast(hint)
                HintKind.TEAM_ADDED, HintKind.TEAM_REMOVED -> { recentTeamEvents.addLast(hint.at); deferredHints += hint }
                HintKind.TAB_ADDED -> deferredHints += hint
            }
        }
        val now = System.currentTimeMillis()
        val iter = deferredHints.iterator()
        while (iter.hasNext()) {
            val hint = iter.next()
            if (now - hint.at < 1_000) continue
            iter.remove()
            if (hint.at < quietUntil) continue
            val info = hint.id?.let(network::getPlayerInfo)
            val name = hint.name ?: info?.profile?.name ?: tabNames[hint.id] ?: continue
            if (name.equals(mc.player?.gameProfile?.name, true)) continue
            val rank = hint.rank.takeIf { it >= 0 } ?: staffRank(info, name)
            if (!isStaffCandidate(hint.id, name, info) && rank !in 0..8) continue
            when (hint.kind) {
                HintKind.TAB_ORDER, HintKind.TAB_ADDED -> announceArrival(hint.id, name, "tab-list update")
                HintKind.TEAM_ADDED -> if (teamEventIsSolo(hint)) announceArrival(hint.id, name, "scoreboard team appeared")
                HintKind.TEAM_REMOVED -> if (teamEventIsSolo(hint)) announceDeparture(name, "scoreboard team removed")
                HintKind.TAB_REMOVED -> Unit
            }
        }
        while (tabMoves.isNotEmpty() && now - tabMoves.first().at > 5_000) tabMoves.removeFirst()
        while (recentTeamEvents.isNotEmpty() && now - recentTeamEvents.first() > 5_000) recentTeamEvents.removeFirst()
        if (tabNames.size > 4096) tabNames.clear()
        network.onlinePlayers.forEach { info -> info.profile?.let { tabNames[it.id] = it.name } }
    }

    private fun teamEventIsSolo(hint: Hint): Boolean {
        val batch = recentTeamEvents.count { kotlin.math.abs(it - hint.at) <= 50 }
        if (batch >= 5) return false
        return tabMoves.none { move -> move.id != null && hint.name != null && tabNames[move.id]?.equals(hint.name, true) == true && kotlin.math.abs(move.at - hint.at) <= 1_000 }
    }

    private fun announceArrival(id: UUID?, name: String, how: String) {
        val now = System.currentTimeMillis()
        val key = name.lowercase()
        val previous = tabArrivals.put(key, now)
        if (id != null) PresenceFeatures.staffRoster.proveNearby(id, name)
        if (previous != null && now - previous < 60_000) return
        news(name, "may be in your region", how)
    }

    private fun announceDeparture(name: String, how: String) {
        if (tabArrivals.remove(name.lowercase()) == null) return
        news(name, "left your region", how)
    }

    private fun processDelayedRemovals(network: ClientPacketListener) {
        val selfId = mc.player?.uuid ?: return
        val alerts = vanishState.advance(ticks, System.currentTimeMillis(),
            stillInPlayerList = { network.getPlayerInfo(it) != null },
            stillInWorld = { mc.level?.getPlayerByUUID(it)?.isAlive == true })
        alerts.forEach { alert ->
            if (alert.weight >= 100 || serverEmitsLeaves) reportHidden(alert.id, alert.name, false, alert.detail, if (serverEmitsLeaves) 100 else alert.weight)
            else if (PresenceFeatures.staffRoster.isMarked(alert.id)) emitEvidence(
                PresenceEvidenceLedger.Kind.HIDDEN_PLAYER, alert.name, "Vanish Event: silent tab disappearance", alert.weight, alert.id
            )
        }
    }

    private fun inspectHiddenTab(id: UUID, immediate: Boolean) {
        val name = tabNames[id] ?: mc.connection?.getPlayerInfo(id)?.profile?.name ?: return
        if (!credible(id, name) || id == mc.player?.uuid || recentLeaveFor(name)) return
        val entry = vanishState.observeHidden(id, System.currentTimeMillis()) ?: return
        if (immediate) reportHidden(entry.id, name, false, entry.detail, entry.weight)
    }

    private fun trackTabIdentities() {
        val network = mc.connection ?: return
        val now = System.currentTimeMillis()
        val listed = network.onlinePlayers.mapNotNull { info ->
            val profile = info.profile ?: return@mapNotNull null
            val id = profile.id ?: return@mapNotNull null
            if (id == mc.player?.uuid) return@mapNotNull null
            knownListedAt.putIfAbsent(id, now)
            tabNames[id] = profile.name
            if (!credible(id, profile.name)) return@mapNotNull null
            DonutVanishState.Candidate(id, profile.name, info.gameMode == net.minecraft.world.level.GameType.SPECTATOR)
        }
        vanishState.observeListed(listed, now)
        if (knownListedAt.size > 1024) knownListedAt.keys.retainAll(network.onlinePlayers.mapNotNull { it.profile?.id }.toSet())
    }

    private fun scanForInvisibleStaff() {
        val level = mc.level ?: return
        val self = mc.player ?: return
        val maxDistanceSq = 64.0 * 64.0
        for (candidate in level.players()) {
            if (candidate === self || !candidate.isInvisible || candidate.distanceToSqr(self) > maxDistanceSq) continue
            val profile = candidate.gameProfile
            val id = candidate.uuid
            val name = profile.name ?: continue
            if (!credible(id, name) || !vanishState.isTrusted(id, System.currentTimeMillis())) continue
            emitEvidence(PresenceEvidenceLedger.Kind.INVISIBLE_BODY, name, "Invisible player entity", 35, id)
        }
    }

    private fun checkInvisibleEntity(entityId: Int) {
        val entity = mc.level?.getEntity(entityId) as? RemotePlayer ?: return
        val self = mc.player ?: return
        val name = entity.gameProfile.name ?: return
        if (entity === self || !entity.isInvisible || entity.distanceToSqr(self) > 64.0 * 64.0 || !credible(entity.uuid, name)) return
        emitEvidence(PresenceEvidenceLedger.Kind.INVISIBLE_BODY, name, "Invisible player entity", 35, entity.uuid)
    }

    private fun checkEntitySound(detail: String) {
        val pieces = detail.split(':', limit = 3)
        if (pieces.size < 3) return
        val entityId = pieces[1].toIntOrNull() ?: return
        val sound = pieces[2]
        if (!isSuspiciousSound(sound)) return
        val entity = mc.level?.getEntity(entityId) as? RemotePlayer ?: return
        val self = mc.player ?: return
        val name = entity.gameProfile.name ?: return
        if (entity === self || !entity.isInvisible || entity.distanceToSqr(self) > 64.0 * 64.0 || !credible(entity.uuid, name)) return
        emitEvidence(PresenceEvidenceLedger.Kind.SOUND, name, "Suspicious sound from invisible player", 16, entity.uuid)
    }

    private fun inspectPositionSignal(notice: SignalNotice) {
        val parts = notice.detail.removePrefix("pos:").split(':', limit = 2)
        if (parts.size < 2) return
        val coords = parts[0].split(',').mapNotNull(String::toDoubleOrNull)
        if (coords.size != 3) return
        val at = Vec3(coords[0], coords[1], coords[2])
        val signal = parts[1]
        val level = mc.level ?: return
        val self = mc.player ?: return
        val awaySq = at.distanceToSqr(self.position())
        if (awaySq > 64.0 * 64.0 || hasVisibleCause(at) || nearRecentBlast(at) || System.currentTimeMillis() - lastLocalActionAt < 1_000) return
        when {
            isSuspiciousSound(signal) -> {
                if (recentSelfPlacement(at) || selfContainerOpenForChest(signal) || doorNearVillager(signal, at) || isPoweredDoor(signal, at)) return
                emitEvidence(PresenceEvidenceLedger.Kind.SOUND, locationLabel(at), "Suspicious Sound: ${path(signal)}", 14, null)
            }
            isSuspiciousParticle(signal) -> {
                if (hasAmbientSource(at, signal)) return
                val particle = path(signal)
                if ((particle == "block" || particle.contains("smoke")) && awaySq < 6.25) return
                if ((particle == "block" || particle.contains("smoke")) && !weakBurstComplete(particle)) return
                emitEvidence(PresenceEvidenceLedger.Kind.PARTICLE, locationLabel(at), "Ghost Particle: $particle", 16, null)
            }
        }
    }

    private fun emitEvidence(kind: PresenceEvidenceLedger.Kind, subject: String, reason: String, score: Int, id: UUID?) {
        val alert = ledger.add(PresenceEvidenceLedger.Evidence(kind, subject, reason, score, System.currentTimeMillis(), id), System.currentTimeMillis(),
            when (kind) { PresenceEvidenceLedger.Kind.INVISIBLE_BODY -> 5_000; PresenceEvidenceLedger.Kind.HIDDEN_PLAYER -> 0; else -> 3_000 }) ?: return
        upsert(subject, reason, score, System.currentTimeMillis() + 20_000)
        announceSignal(kind.name + "|" + subject.lowercase(), subject, reason, id != null)
        val criticalSubject = alert.criticalSubject
        if (criticalSubject != null && System.currentTimeMillis() - lastCriticalAt >= 12_000) {
            lastCriticalAt = System.currentTimeMillis()
            criticalUntil = lastCriticalAt + 7_000
            criticalName = criticalSubject
            if (id == null || !PresenceFeatures.staffRoster.wasRecentlyAlerted(id)) {
                mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f))
            }
        }
    }

    private fun reportHidden(id: UUID, name: String, spectator: Boolean, detail: String, score: Int) {
        upsert(id.toString(), name, if (spectator) "Vanish Event: switched to spectator" else "Vanish Event: $detail", score,
            System.currentTimeMillis() + 20_000)
        val spell = hiddenRecords[id]
        if (spell == null || spell.spectator != spectator) {
            val hasNearbyProof = PresenceFeatures.staffRoster.isMarked(id) ||
                mc.level?.getPlayerByUUID(id)?.let { it !== mc.player && it.isAlive } == true
            hiddenRecords[id] = HiddenRecord(id, name, spectator, detail, id.takeIf { hasNearbyProof }, ticks)
        }
    }

    private fun processHiddenRecords(network: ClientPacketListener) {
        val now = System.currentTimeMillis()
        val iterator = hiddenRecords.values.iterator()
        while (iterator.hasNext()) {
            val spell = iterator.next()
            if (spell.spectator && network.getPlayerInfo(spell.id) == null) { iterator.remove(); continue }
            val isListedAgain = network.listedOnlinePlayers.any { it.profile.id == spell.id }
            if (!spell.spectator && isListedAgain && now - spell.createdAt >= 1_000) {
                detections.remove(spell.id.toString())
                if (spell.announced) news(spell.name, "is visible again", "")
                iterator.remove()
                vanishState.visibleAgain(spell.id)
                continue
            }
            if (spell.announced || spell.createdTick >= ticks) continue
            val marked = PresenceFeatures.staffRoster.isMarked(spell.id, now) ||
                mc.level?.getPlayerByUUID(spell.id)?.let { it !== mc.player && it.isAlive } == true
            if (!marked && spell.proofId == null) {
                spell.announced = true
                news(spell.name, if (spell.spectator) "switched to spectator" else "vanished", spell.detail)
            } else {
                spell.announced = true
                emitEvidence(PresenceEvidenceLedger.Kind.HIDDEN_PLAYER, spell.name, "Vanish Event: a hidden player is in your region", 100, spell.id)
            }
        }
    }

    private fun rememberBlast(raw: String) {
        val values = raw.removePrefix("blast:").split(',').mapNotNull(String::toDoubleOrNull)
        if (values.size < 4) return
        blastHistory.addLast(RecentBlast(Vec3(values[0], values[1], values[2]), (values[3] + 4.0).coerceAtLeast(2.0), System.currentTimeMillis()))
        while (blastHistory.size > 8) blastHistory.removeFirst()
    }

    private fun nearRecentBlast(pos: Vec3): Boolean {
        val now = System.currentTimeMillis()
        while (blastHistory.isNotEmpty() && now - blastHistory.first().at > 3_000) blastHistory.removeFirst()
        return blastHistory.any { it.center.distanceToSqr(pos) <= it.range * it.range }
    }

    private fun observeLocalAction(packet: Any) {
        val now = System.currentTimeMillis()
        if (packet is ServerboundUseItemOnPacket) {
            lastLocalActionAt = now
            val hit = packet.hitResult
            val clicked = hit.blockPos
            val neighbors = listOf(clicked, clicked.relative(hit.direction))
            val itemPath = mc.player?.getItemInHand(packet.hand)?.item?.let { BuiltInRegistries.ITEM.getKey(it).path }.orEmpty()
            neighbors.forEach { pos ->
                placedByMe[pos.asLong()] = now + 6_000
                markMultiPartBlocks(pos, itemPath, now + 6_000)
            }
        } else if (packet is ServerboundPlayerActionPacket || packet is ServerboundUseItemPacket || packet is ServerboundInteractPacket) {
            lastLocalActionAt = now
        }
    }

    private fun markMultiPartBlocks(pos: BlockPos, path: String, expiresAt: Long) {
        if ((path.endsWith("_door") && !path.endsWith("trapdoor")) || path in setOf("sunflower", "lilac", "rose_bush", "peony", "tall_grass", "large_fern", "pitcher_plant")) {
            placedByMe[pos.above().asLong()] = expiresAt; placedByMe[pos.below().asLong()] = expiresAt
        }
        if (path.endsWith("_bed")) listOf(pos.east(), pos.west(), pos.north(), pos.south()).forEach { placedByMe[it.asLong()] = expiresAt }
    }

    private fun recentSelfPlacement(pos: Vec3): Boolean = placedByMe[BlockPos.containing(pos).asLong()]?.let { it > System.currentTimeMillis() } == true

    private fun hasVisibleCause(pos: Vec3): Boolean {
        val level = mc.level ?: return false
        val self = mc.player ?: return false
        return level.entitiesForRendering().any { entity ->
            entity !== self && (entity !is RemotePlayer || !entity.isInvisible) &&
                (entity is RemotePlayer || entity is Projectile) && entity.distanceToSqr(pos) <= 16.0
        }
    }

    private fun selfContainerOpenForChest(sound: String): Boolean = System.currentTimeMillis() - lastContainerAt < 2_500 &&
        listOf("chest", "barrel", "shulker").any { path(sound).contains(it) }

    private fun doorNearVillager(sound: String, pos: Vec3): Boolean {
        val normalized = path(sound)
        if (!(normalized.contains("door") || normalized.contains("fence_gate")) || mc.level == null) return false
        return mc.level!!.entitiesForRendering().any { it.type == EntityTypes.VILLAGER && it.distanceToSqr(pos) <= 9.0 }
    }

    private fun isPoweredDoor(sound: String, pos: Vec3): Boolean {
        val normalized = path(sound)
        if (!(normalized.contains("door") || normalized.contains("trapdoor"))) return false
        val level = mc.level ?: return false
        val base = BlockPos.containing(pos)
        for (dy in -1..1) {
            val check = base.offset(0, dy, 0)
            val state = level.getBlockState(check)
            if (state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED)) {
                mechanismClues[check.asLong()] = System.currentTimeMillis() + 5_000
                return true
            }
            if ((state.`is`(Blocks.OAK_DOOR) || state.`is`(Blocks.IRON_DOOR) || state.`is`(Blocks.OAK_TRAPDOOR) || state.`is`(Blocks.IRON_TRAPDOOR)) && mechanismClues[check.asLong()]?.let { it > System.currentTimeMillis() } == true) return true
        }
        return false
    }

    private fun hasAmbientSource(pos: Vec3, particle: String): Boolean {
        if (!path(particle).contains("smoke")) return false
        val level = mc.level ?: return false
        val center = BlockPos.containing(pos)
        for (x in -2..2) for (y in -2..2) for (z in -2..2) {
            val block = level.getBlockState(center.offset(x, y, z)).block
            if (block == Blocks.CAMPFIRE || block == Blocks.SOUL_CAMPFIRE || block == Blocks.FURNACE || block == Blocks.SMOKER ||
                block == Blocks.TORCH || block == Blocks.SOUL_TORCH || block == Blocks.FIRE || block == Blocks.SOUL_FIRE || block == Blocks.CANDLE || block == Blocks.RESPAWN_ANCHOR) return true
        }
        return false
    }

    private fun weakBurstComplete(particle: String): Boolean {
        val now = System.currentTimeMillis()
        val queue = weakParticleBursts.getOrPut(particle) { ArrayDeque() }
        queue.addLast(now)
        while (queue.isNotEmpty() && now - queue.first() > 2_000) queue.removeFirst()
        if (queue.size < 2) return false
        queue.clear()
        return true
    }

    private fun credible(id: UUID, name: String): Boolean = id.version() != 2 && PresenceText.isUsernameLike(name) &&
        DonutStaffMatcher.DONUT_NAMES.any { it.equals(name, true) } &&
        System.currentTimeMillis() - (knownListedAt[id] ?: Long.MAX_VALUE) >= 1_500

    private fun staffRank(info: net.minecraft.client.multiplayer.PlayerInfo?, name: String): Int {
        val teamName = info?.let { mc.level?.scoreboard?.getPlayersTeam(name)?.name }.orEmpty()
        return Regex("^(\\d{2})-").find(teamName)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun isStaffCandidate(id: UUID?, name: String, info: net.minecraft.client.multiplayer.PlayerInfo?): Boolean {
        if (id != null && credible(id, name)) return true
        if (DonutStaffMatcher.DONUT_NAMES.any { it.equals(name, true) }) return true
        val teamName = mc.level?.scoreboard?.getPlayersTeam(name)?.name.orEmpty()
        val teamRank = Regex("^(\\d{2})-").find(teamName)?.groupValues?.get(1)?.toIntOrNull()
        return teamRank != null && teamRank <= 8
    }

    private fun news(name: String, outcome: String, detail: String) {
        val suffix = if (detail.isBlank()) "" else " ($detail)"
        chat("$name $outcome$suffix", this)
    }

    private fun announceSignal(key: String, subject: String, reason: String, hasPlayerProof: Boolean) {
        val now = System.currentTimeMillis()
        if (now - (announceCooldowns[key] ?: 0L) < 1_500) return
        while (recentSignalMessages.isNotEmpty() && now - recentSignalMessages.first() > 4_000) recentSignalMessages.removeFirst()
        if (recentSignalMessages.size >= 6) return
        announceCooldowns[key] = now
        recentSignalMessages.addLast(now)
        val where = if (subject.isBlank() || subject == "Unknown") "" else " ($subject)"
        notification("Donut Presence Watch", "$reason$where", net.ccbluex.liquidbounce.event.events.NotificationEvent.Severity.INFO)
        chat("$reason$where", this)
    }

    private fun upsert(name: String, reason: String, score: Int, expire: Long) = upsert(name, name, reason, score, expire)
    private fun upsert(key: String, name: String, reason: String, score: Int, expire: Long) {
        val found = detections[key]
        if (found == null) detections[key] = Detection(name, reason, score, expire)
        else { found.expiresAt = maxOf(found.expiresAt, expire); if (score >= found.score) { found.score = score; found.reason = reason } }
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        detections.entries.removeIf { it.value.expiresAt <= now }
        announceCooldowns.entries.removeIf { now - it.value > 60_000 }
        placedByMe.entries.removeIf { it.value <= now }
        mechanismClues.entries.removeIf { it.value <= now }
        weakParticleBursts.values.removeIf { queue -> while (queue.isNotEmpty() && now - queue.first() > 2_000) queue.removeFirst(); queue.isEmpty() }
        while (recentChats.isNotEmpty() && now - recentChats.first().second > 8_000) recentChats.removeFirst()
        while (blastHistory.isNotEmpty() && now - blastHistory.first().at > 3_000) blastHistory.removeFirst()
        ledger.prune(now)
    }

    private fun recentLeaveFor(name: String) = recentChats.any { System.currentTimeMillis() - it.second <= 8_000 && PresenceText.looksLikeDeparture(it.first, name) }

    private fun locationLabel(pos: Vec3): String {
        val self = mc.player?.position() ?: return "nearby"
        val dx = pos.x - self.x; val dy = pos.y - self.y; val dz = pos.z - self.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz).toInt()
        val heading = (if (dz < -1) "N" else if (dz > 1) "S" else "") + (if (dx > 1) "E" else if (dx < -1) "W" else "")
        return "$distance m${if (heading.isBlank()) "" else " $heading"}"
    }

    private fun isSuspiciousSound(id: String): Boolean {
        val p = path(id)
        return listOf("chest.open", "chest.close", "chest.locked", "barrel.open", "barrel.close", "shulker_box.open", "shulker_box.close",
            "door.open", "door.close", "trapdoor.open", "trapdoor.close", "fence_gate.open", "fence_gate.close", "button.click", "lever.click").any(p::contains)
    }

    private fun isSuspiciousParticle(id: String): Boolean {
        val p = path(id)
        return p.contains("crit") || p.contains("enchanted_hit") || p.contains("damage_indicator") || p.contains("smoke") || p == "block"
    }

    private fun path(value: String) = value.substringAfter(':').lowercase()

    private fun looksLikeSystemChat(packet: Any): Boolean = packet.javaClass.simpleName.contains("Chat", true) || packet.javaClass.simpleName.contains("GameMessage", true)

    private fun reflectComponent(packet: Any): String? = packet.javaClass.methods.asSequence()
        .filter { it.parameterCount == 0 && (it.name == "content" || it.name == "getContent" || it.name == "message") }
        .mapNotNull { runCatching { it.invoke(packet) }.getOrNull() }
        .firstNotNullOfOrNull { value -> runCatching { value.javaClass.getMethod("getString").invoke(value) as? String }.getOrNull() }

    private fun reflectTranslationKey(packet: Any): String? = packet.javaClass.methods.asSequence()
        .filter { it.parameterCount == 0 && (it.name == "content" || it.name == "getContent" || it.name == "message") }
        .mapNotNull { runCatching { it.invoke(packet) }.getOrNull() }
        .mapNotNull { component -> runCatching { component.javaClass.getMethod("getContents").invoke(component) }.getOrNull() }
        .filter { it.javaClass.simpleName.contains("Translatable") }
        .firstNotNullOfOrNull { value -> runCatching { value.javaClass.getMethod("getKey").invoke(value) as? String }.getOrNull() }

    private fun shardKey(x: Double, z: Double): String {
        if (mc.level?.dimension() != net.minecraft.world.level.Level.OVERWORLD) return "outside-overworld"
        val col = floor((x + 225_000.0) / 12_500.0).toInt(); val row = floor((z + 225_000.0) / 12_500.0).toInt()
        if (col !in 0 until RegionMapData.GRID_SIZE || row !in 0 until RegionMapData.GRID_SIZE) return "outside-map"
        return RegionMapData.cells[row][col]?.let { "${it.region}-${it.number}" } ?: "unmapped"
    }

    private fun resetState() {
        packetQueue.clear(); hints.clear(); gameModes.clear(); deferredHints.clear(); tabMoves.clear(); recentTeamEvents.clear()
        tabNames.clear(); tabArrivals.clear(); recentSignalMessages.clear(); announceCooldowns.clear(); detections.clear()
        hiddenRecords.clear(); recentChats.clear(); placedByMe.clear(); knownListedAt.clear(); blastHistory.clear()
        weakParticleBursts.clear(); mechanismClues.clear(); vanishState.clear(); ledger.clear()
        ticks = 0; totalTicks = 0; lastLocalActionAt = 0; lastContainerAt = 0; quietUntil = System.currentTimeMillis() + 4_000
        serverEmitsLeaves = false; lastCriticalAt = 0; criticalUntil = 0; criticalName = ""
    }

    companion object { private val TEAM_NAME = Regex("(\\d{2})-(.+)-(?:true|false)") }
}
