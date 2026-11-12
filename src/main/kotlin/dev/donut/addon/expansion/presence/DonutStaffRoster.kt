package dev.donut.addon.expansion.presence

import dev.donut.addon.AddonCategories
import dev.donut.addon.hud.region.RegionMapData
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.notification
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.player.RemotePlayer
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.world.level.GameType
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.floor

/** Tracks the Donut roster, tab visibility, and evidence that staff are physically nearby. */
class DonutStaffRoster : DonutPresenceModule("Donut Staff Roster") {
    enum class Presence { ONLINE, SPECTATOR, HIDDEN }
    enum class AlarmStyle(override val tag: String) : Tagged {
        OFF("Off"), PING("Ping"), DOUBLE("Double"), LOW("Low")
    }
    data class Row(
        val name: String,
        val id: UUID,
        val rank: String,
        val presence: Presence,
        val nearby: Boolean,
        val distance: Float,
        val watching: Boolean,
        val watchingForMs: Long,
        val alertLevel: Float,
        val hiddenName: Boolean,
    )

    private data class StaffRecord(
        val id: UUID,
        var name: String,
        var rank: String,
        var rankOrder: Int,
        var presence: Presence,
        var missingScans: Int = 0,
    )
    private data class Evidence(
        var provedAt: Long = 0,
        var watchingSince: Long = 0,
        var alarmedAt: Long = 0,
        var highlightUntil: Long = 0,
        var nearby: Boolean = false,
        var distance: Float = -1f,
        var lastBodyTick: Int = -1,
        var bodyLostAt: Long = 0,
        var lastSwitchTick: Int = -1000,
        var switchedToSpectator: Boolean = false,
    )
    private data class ModeChange(val id: UUID, val mode: GameType?)

    private val regionAlert by boolean("Region Alert", true).onChanged { active ->
        if (!active) clearRegionProofs()
    }
    private val showHud by boolean("Show HUD", true).onChanged { PresenceFeatures.syncHudState() }
    private val alarmStyle by enumChoice("Alarm", AlarmStyle.PING)
    private val alarmVolume by float("Alarm Volume", 1f, 0f..1f)
    private val matcher = DonutStaffMatcher()
    private val tracked = linkedMapOf<UUID, StaffRecord>()
    private val proofs = linkedMapOf<UUID, Evidence>()
    private val incomingModes = ConcurrentLinkedQueue<ModeChange>()
    private var activeConnection: ClientPacketListener? = null
    private var activeWorld: Any? = null
    private var localShard: String? = null
    private var settleTicks = 0
    private var ticksUntilScan = 0
    private var tickNumber = 0
    private var primed = false
    private var gatherTicks = 0
    private val batchedAlerts = linkedMapOf<UUID, String>()
    private var alarmTicksLeft = 0
    private var alarmStep = 0

    init { literalDescription { "Shows Donut staff presence and alerts when rostered staff appear nearby." } }

    val shouldShowHud get() = showHud
    override fun onEnabled() { clearAll(); activeConnection = mc.connection; activeWorld = mc.level; settleTicks = 0; PresenceFeatures.syncHudState() }
    override fun onDisabled() { clearAll(); activeConnection = null; activeWorld = null; PresenceFeatures.syncHudState() }

    @Suppress("unused")
    private val receivedPackets = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING) return@handler
        val packet = event.packet
        if (packet is ClientboundPlayerInfoUpdatePacket &&
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE in packet.actions() &&
            ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER !in packet.actions()
        ) {
            packet.entries().forEach { entry ->
                if (entry.gameMode != null) incomingModes += ModeChange(entry.profileId, entry.gameMode)
            }
        }
    }

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        val self = mc.player
        val level = mc.level
        val connection = mc.connection
        if (self == null || level == null || connection == null) {
            clearAll(); activeConnection = null; activeWorld = null
            return@handler
        }

        val currentShard = shardKey(self.x, self.z)
        if (connection !== activeConnection || level !== activeWorld) {
            clearAll()
            activeConnection = connection
            activeWorld = level
            settleTicks = 60
        } else if (localShard != null && currentShard != localShard) {
            clearRegionProofs()
        }
        localShard = currentShard
        tickNumber++
        if (settleTicks > 0) settleTicks--
        readModeChanges(connection, self.uuid)
        if (regionAlert) trackNearby(level.players().filterIsInstance<RemotePlayer>(), self.uuid)

        if (--ticksUntilScan <= 0) {
            ticksUntilScan = 10
            val changes = updateRoster(connection, self.uuid)
            if (primed) announceChanges(changes)
            else if (settleTicks == 0) primed = true
        }
        if (batchedAlerts.isNotEmpty() && --gatherTicks <= 0) raiseAlertBatch()
        tickAlarm()
        removeExpiredProofs()
    }

    fun rows(now: Long = System.currentTimeMillis()): List<Row> = tracked.values.mapNotNull { person ->
        val proof = proofs[person.id]
        val shown = nameForDisplay(person.name)
        Row(
            shown, person.id, person.rank, person.presence,
            proof?.nearby == true, proof?.distance ?: -1f,
            proof?.let { it.nearby || it.provedAt > 0L && now - it.provedAt < 10_000L } == true,
            proof?.let { if (it.watchingSince > 0) (now - it.watchingSince).coerceAtLeast(0) else 0 } ?: 0,
            proof?.let { ((it.highlightUntil - now) / 4_000f).coerceIn(0f, 1f) } ?: 0f,
            shown != person.name,
        )
    }.sortedWith(compareBy<Row> { if (it.nearby) 0 else if (isMarked(it.id, now)) 1 else if (it.presence != Presence.ONLINE) 2 else 3 }
        .thenBy { tracked[it.id]?.rankOrder ?: Int.MAX_VALUE }.thenBy { it.name.lowercase() })

    fun isMarked(id: UUID, now: Long = System.currentTimeMillis()): Boolean = proofs[id]?.let {
        it.nearby || it.provedAt > 0 && now - it.provedAt < 600_000L
    } == true

    fun proveNearby(id: UUID, name: String) {
        if (!regionAlert || id == mc.player?.uuid) return
        val row = proofs.getOrPut(id) { Evidence() }
        if (!isMarked(id)) row.watchingSince = System.currentTimeMillis()
        row.provedAt = System.currentTimeMillis()
        if (row.alarmedAt == 0L || System.currentTimeMillis() - row.alarmedAt >= 120_000L) queueAlert(id, name, row)
    }

    fun wasRecentlyAlerted(id: UUID): Boolean = proofs[id]?.let { it.alarmedAt > 0 && System.currentTimeMillis() - it.alarmedAt < 5_000 } == true

    private fun applyNameProtection(name: String): Pair<String, Boolean> {
        val protection = net.ccbluex.liquidbounce.features.module.ModuleManager["NameProtect"]
        return if (protection?.enabled == true) "Protected" to true else name to false
    }

    private fun updateRoster(connection: ClientPacketListener, selfId: UUID): List<String> {
        val allInfos = connection.onlinePlayers
        val listedIds = connection.listedOnlinePlayers.mapTo(hashSetOf()) { it.profile.id }
        val current = linkedSetOf<UUID>()
        val changes = arrayListOf<String>()
        for (info in allInfos) {
            val profile = info.profile ?: continue
            val id = profile.id ?: continue
            val name = profile.name ?: continue
            if (id == selfId || name.lowercase() !in STAFF_NAME_KEYS ||
                id.version() == 2 || !ACCOUNT.matches(name)) continue
            current += id
            val team = mc.level?.scoreboard?.getPlayersTeam(name)
            val classification = matcher.classify(name, team?.name, info.tabListDisplayName?.string, team?.playerPrefix?.string, team?.playerSuffix?.string)
                ?: DonutStaffMatcher.Classification("Staff", 700)
            val presence = when {
                info.gameMode == GameType.SPECTATOR -> Presence.SPECTATOR
                id !in listedIds -> Presence.HIDDEN
                else -> Presence.ONLINE
            }
            val old = tracked[id]
            if (old == null) {
                tracked[id] = StaffRecord(id, name, classification.label, classification.order, presence)
                if (primed) changes += "$name (${classification.label}) joined${presenceNote(presence)}"
            } else {
                val prior = old.presence
                old.name = name; old.rank = classification.label; old.rankOrder = classification.order
                old.missingScans = 0
                old.presence = presence
                if (prior != presence && primed) changes += "$name (${classification.label}) ${presenceChange(prior, presence)}"
            }
        }

        val it = tracked.values.iterator()
        while (it.hasNext()) {
            val currentStaff = it.next()
            if (currentStaff.id in current) continue
            if (mc.level?.getPlayerByUUID(currentStaff.id) != null) {
                currentStaff.missingScans++
                if (currentStaff.missingScans >= 2 && currentStaff.presence != Presence.HIDDEN) {
                    val previous = currentStaff.presence
                    currentStaff.presence = Presence.HIDDEN
                    if (primed) changes += "${currentStaff.name} (${currentStaff.rank}) ${presenceChange(previous, Presence.HIDDEN)}"
                }
            } else {
                it.remove()
                if (primed) changes += "${currentStaff.name} (${currentStaff.rank}) left"
            }
        }
        return changes
    }

    private fun readModeChanges(connection: ClientPacketListener, selfId: UUID) {
        if (!regionAlert) {
            incomingModes.clear()
            return
        }
        while (true) {
            val change = incomingModes.poll() ?: break
            if (change.id == selfId) continue
            val existing = tracked[change.id]
            val name = existing?.name ?: connection.getPlayerInfo(change.id)?.profile?.name ?: continue
            if (name.lowercase() !in STAFF_NAME_KEYS) continue
            val spectator = change.mode == GameType.SPECTATOR
            if (existing?.presence == (if (spectator) Presence.SPECTATOR else Presence.ONLINE)) continue
            existing?.presence = if (spectator) Presence.SPECTATOR else Presence.ONLINE
            val proof = proofs.getOrPut(change.id) { Evidence() }
            proof.lastSwitchTick = tickNumber
            proof.switchedToSpectator = spectator
            if (regionAlert) proveNearby(change.id, name)
            if (primed) announceChanges(listOf("$name (${existing?.rank ?: "Staff"}) switched ${if (spectator) "to spectator" else "back to survival/adventure"}"))
        }
    }

    private fun trackNearby(players: List<RemotePlayer>, selfId: UUID) {
        val self = mc.player ?: return
        val currentTick = tickNumber
        for (entity in players) {
            if (entity.uuid == selfId) continue
            val record = tracked[entity.uuid] ?: continue
            val proof = proofs.getOrPut(record.id) { Evidence() }
            val wasWatching = proof.nearby || proof.provedAt > 0 && System.currentTimeMillis() - proof.provedAt < 10_000
            if (!wasWatching) proof.watchingSince = System.currentTimeMillis()
            proof.provedAt = System.currentTimeMillis()
            proof.lastBodyTick = currentTick
            proof.distance = self.distanceTo(entity)
            if (!proof.nearby) {
                proof.nearby = true
                val newVisit = proof.bodyLostAt == 0L || System.currentTimeMillis() - proof.bodyLostAt > 10_000L
                if (newVisit && !isCoolingDown(proof)) queueAlert(record.id, record.name, proof)
            }
        }
        proofs.values.filter { it.nearby && it.lastBodyTick != currentTick }.forEach {
            it.nearby = false
            it.bodyLostAt = System.currentTimeMillis()
        }
    }

    private fun queueAlert(id: UUID, name: String, proof: Evidence) {
        proof.alarmedAt = System.currentTimeMillis()
        proof.highlightUntil = proof.alarmedAt + 4_000
        if (batchedAlerts.isEmpty()) gatherTicks = 5
        batchedAlerts[id] = name
    }

    private fun raiseAlertBatch() {
        val names = batchedAlerts.values.toList()
        batchedAlerts.clear()
        chat("Staff nearby: ${names.joinToString(", ")}", this)
        notification("Donut Staff Roster", "${names.joinToString(", ")} appeared in your area", net.ccbluex.liquidbounce.event.events.NotificationEvent.Severity.INFO)
        alarmTicksLeft = if (alarmStyle == AlarmStyle.OFF || alarmVolume <= 0f) 0 else 60
        alarmStep = 0
    }

    private fun tickAlarm() {
        if (alarmTicksLeft <= 0) return
        val interval = when (alarmStyle) { AlarmStyle.PING -> 3; AlarmStyle.DOUBLE -> 10; AlarmStyle.LOW -> 4; AlarmStyle.OFF -> Int.MAX_VALUE }
        if (alarmStep % interval == 0) {
            val pitch = when (alarmStyle) { AlarmStyle.DOUBLE -> if (alarmStep / interval % 2 == 0) 1.5f else 1f; AlarmStyle.LOW -> 0.8f; else -> 1.25f }
            mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, alarmVolume.coerceIn(0f, 1f), pitch))
        }
        alarmStep++
        alarmTicksLeft--
    }

    private fun announceChanges(changes: List<String>) {
        if (changes.isEmpty()) return
        val grouped = if (changes.size > 3) "${changes.size} staff updates: ${changes.joinToString(", ")}" else changes.joinToString("\n")
        chat(grouped, this)
        if (changes.any { it.contains("joined") || it.contains("hidden") || it.contains("spectator") }) {
        mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1.25f))
        }
    }

    private fun removeExpiredProofs() {
        val now = System.currentTimeMillis()
        proofs.entries.removeIf { (id, proof) -> id !in tracked && !proof.nearby && now - maxOf(proof.provedAt, proof.bodyLostAt) > 10_000 }
    }

    private fun isCoolingDown(proof: Evidence) = proof.alarmedAt > 0 && System.currentTimeMillis() - proof.alarmedAt < 120_000
    private fun nameForDisplay(name: String) = applyNameProtection(name).first
    private fun presenceNote(state: Presence) = when (state) { Presence.ONLINE -> ""; Presence.SPECTATOR -> " (spectating)"; Presence.HIDDEN -> " (hidden from tab)" }
    private fun presenceChange(previous: Presence, next: Presence) = when (next) {
        Presence.HIDDEN -> "vanished from tab"
        Presence.SPECTATOR -> "switched to spectator"
        Presence.ONLINE -> if (previous == Presence.HIDDEN) "is visible again" else "left spectator mode"
    }

    private fun shardKey(x: Double, z: Double): String {
        if (mc.level?.dimension() != net.minecraft.world.level.Level.OVERWORLD) return "non-overworld"
        val col = floor((x + 225_000.0) / 12_500.0).toInt()
        val row = floor((z + 225_000.0) / 12_500.0).toInt()
        return if (col in 0 until RegionMapData.GRID_SIZE && row in 0 until RegionMapData.GRID_SIZE) {
            RegionMapData.cells[row][col]?.let { "${it.region}-${it.number}" } ?: "unmapped"
        } else "outside-map"
    }

    private fun clearAll() {
        tracked.clear(); ticksUntilScan = 0; tickNumber = 0; primed = false; localShard = null
        clearRegionProofs()
    }

    private fun clearRegionProofs() {
        proofs.clear(); incomingModes.clear(); batchedAlerts.clear()
        gatherTicks = 0; alarmTicksLeft = 0; alarmStep = 0
    }

    companion object {
        private val ACCOUNT = Regex("[A-Za-z0-9_]{1,16}")
        private val STAFF_NAME_KEYS = DonutStaffMatcher.DONUT_NAMES.mapTo(hashSetOf()) { it.lowercase() }
        @JvmStatic fun formatAge(milliseconds: Long): String {
            val seconds = milliseconds.coerceAtLeast(0) / 1_000
            val hours = seconds / 3_600
            val minutes = seconds / 60 % 60
            val remainder = seconds % 60
            return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:${remainder.toString().padStart(2, '0')}"
            else "$minutes:${remainder.toString().padStart(2, '0')}"
        }
    }
}
