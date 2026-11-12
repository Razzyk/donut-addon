package dev.donut.addon.expansion.utility

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.input.InputTracker.isPressedOnAny
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Reacts to nearby removal evidence, collects exposed spawners, and stores them in ender chests. */
class DonutSpawnerProtect : DonutUtilityModule("Donut Spawner Protect", "Collects nearby spawners after repeated local block-removal evidence; nearby players only trigger optional webhook notices.") {
    private enum class Phase { WATCHING, COLLECTING, WALKING_TO_CHEST, OPENING_CHEST, DEPOSITING }

    private val targetStacks by int("Stack To Deposit", 35, 1..35)
    private val triggerRange by float("Trigger Range", 64f, 16f..128f)
    private val rotationSpeed by float("Rotation Speed", 15f, 1f..30f)
    private val detectUpdates by boolean("Detect Block Updates", true)
    private val whitelist by text("Whitelist", "").doNotIncludeAlways()
    private val breakRange by float("Break Range", 5.5f, 1f..8f)
    private val evidenceThreshold by int("Block Update Threshold", 5, 1..20)
    private val evidenceWindow by int("Block Update Window", 60, 1..600)
    private val webhookUrl by text("Webhook URL", "").doNotIncludeAlways()

    private val evidence = SpawnerTriggerLedger()
    private val trustedNames = ConcurrentHashMap.newKeySet<String>()
    private val webClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "Donut spawner alerts").apply { isDaemon = true } }
    private val webhookGeneration = AtomicLong()
    private var phase = Phase.WATCHING
    private var activeWorld: Any? = null
    private var knownPlayers = emptySet<UUID>()
    private var suspiciousBreaks = 0
    private var targetBlock: BlockPos? = null
    private var targetChest: BlockPos? = null
    private var miningTarget: BlockPos? = null
    private var scanTicks = 0
    private var idleTicks = 0
    private var menuTicks = 0
    private var reportedMissingChest = false
    private var triggerPosition: BlockPos? = null

    override fun onEnabled() = startSession(mc.level)

    override fun onUtilitySessionStarted(world: net.minecraft.client.multiplayer.ClientLevel) = startSession(world)

    private fun startSession(world: net.minecraft.client.multiplayer.ClientLevel?) {
        reset()
        activeWorld = world
        phase = Phase.WATCHING
    }

    override fun onUtilitySessionEnded() = reset()
    override fun onDisabled() = onUtilitySessionEnded()

    override fun onUtilityShutdown() {
        onUtilitySessionEnded()
        worker.shutdownNow()
    }

    private fun reset() {
        webhookGeneration.incrementAndGet()
        stopMining()
        phase = Phase.WATCHING
        activeWorld = null
        knownPlayers = emptySet()
        evidence.clear()
        suspiciousBreaks = 0
        targetBlock = null
        targetChest = null
        miningTarget = null
        scanTicks = 0
        idleTicks = 0
        menuTicks = 0
        reportedMissingChest = false
        triggerPosition = null
        trustedNames.clear()
    }

    @Suppress("unused")
    private val blockUpdates = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING || !detectUpdates || phase != Phase.WATCHING) return@handler
        val packet = event.packet as? ClientboundBlockUpdatePacket ?: return@handler
        val level = mc.level ?: return@handler
        val player = mc.player ?: return@handler
        val old = level.getBlockState(packet.pos)
        val pos = packet.pos
        val dx = player.x - pos.x
        val dz = player.z - pos.z
        val horizontal = sqrt(dx * dx + dz * dz)
        val protected = old.block == Blocks.BEDROCK
        val nearTrusted = level.players().any { other ->
            other !== player && other.distanceToSqr(Vec3.atCenterOf(pos)) < 64.0 &&
                trustedNames.contains(other.gameProfile.name.lowercase())
        }
        val thresholdReached = evidence.observe(
            System.nanoTime(), old.isAir, protected, packet.blockState.isAir, pos.y,
            horizontal, triggerRange.toDouble(), player.position().distanceTo(Vec3.atCenterOf(pos)),
            nearTrusted, TimeUnit.SECONDS.toNanos(evidenceWindow.toLong()), evidenceThreshold,
        )
        if (thresholdReached) {
            phase = Phase.COLLECTING
            triggerPosition = pos.immutable()
            suspiciousBreaks = evidence.size(System.nanoTime(), TimeUnit.SECONDS.toNanos(evidenceWindow.toLong()))
            chat("Spawner protection started after $suspiciousBreaks nearby block updates.", this)
            notifyWebhook("Protection started", "Nearby block-removal evidence reached $suspiciousBreaks events.", pos)
        }
    }

    @Suppress("unused")
    private val movement = handler<MovementInputEvent> { event ->
        if (mc.gui.screen() != null) return@handler
        if (phase == Phase.WALKING_TO_CHEST) {
            event.directionalInput = DirectionalInput.FORWARDS
            event.sneak = true
        }
    }

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        val player = mc.player
        val world = mc.level
        val screen = mc.gui.screen()
        val containerFlow = phase == Phase.OPENING_CHEST || phase == Phase.DEPOSITING
        if (player == null || world == null || mc.gameMode == null || mc.connection == null || screen != null && !containerFlow) {
            if (phase in setOf(Phase.COLLECTING, Phase.WALKING_TO_CHEST)) stopMining()
            return@handler
        }
        if (activeWorld !== world) {
            reset()
            activeWorld = world
            return@handler
        }
        inspectArrivals(mc.connection!!)
        if (phase == Phase.WATCHING) return@handler

        scanTicks++
        val spawnerCount = (0..40).sumOf { slot ->
            val stack = player.inventory.getItem(slot)
            if (stack.item == Items.SPAWNER) stack.count else 0
        }
        if (spawnerCount >= targetStacks * 64 || inventoryNeedsSpace()) {
            stopMining()
            if (targetChest == null || scanTicks % 20 == 0) targetChest = findNearest(Blocks.ENDER_CHEST, 4)
            if (targetChest == null) {
                if (!reportedMissingChest) {
                    reportedMissingChest = true
                    chat("Spawners are held safely; no nearby ender chest was found to deposit them.", this)
                    notifyWebhook("Ender chest missing", "Collected spawners remain in your inventory; no nearby ender chest was found.", triggerPosition)
                }
                phase = Phase.COLLECTING
                return@handler
            }
            phase = if (player.blockPosition().distSqr(targetChest!!) <= 16.0) Phase.OPENING_CHEST else Phase.WALKING_TO_CHEST
        }

        when (phase) {
            Phase.WATCHING -> Unit
            Phase.COLLECTING -> collectTick()
            Phase.WALKING_TO_CHEST -> walkToChest()
            Phase.OPENING_CHEST -> openChest()
            Phase.DEPOSITING -> depositTick()
        }
    }

    private fun inspectArrivals(connection: ClientPacketListener) {
        val player = mc.player ?: return
        val level = mc.level ?: return
        val list = whitelist.split(',', ';', '\n', ' ').map { it.trim().lowercase() }.filter(String::isNotEmpty).toSet()
        trustedNames.clear()
        trustedNames.addAll(list)
        val present = level.players().asSequence().filter { it !== player && !it.isSpectator && player.distanceTo(it) <= triggerRange }
            .filterNot { trustedNames.contains(it.gameProfile.name.lowercase()) }
            .map { it.uuid }.toSet()
        for (newId in present - knownPlayers) {
            val found = level.getPlayerByUUID(newId) ?: continue
            // Presence notices remain informational; arrivals never activate collection.
            notifyWebhook("Player nearby", "${found.gameProfile.name} entered the configured range at ${found.blockPosition().x}, ${found.blockPosition().y}, ${found.blockPosition().z}.", found.blockPosition())
        }
        knownPlayers = present
    }

    private fun collectTick() {
        val player = mc.player ?: return
        if (++scanTicks % 20 == 0 || targetBlock == null || mc.level?.getBlockState(targetBlock!!)?.block != Blocks.SPAWNER) {
            targetBlock = findNearest(Blocks.SPAWNER, 16)
        }
        val target = targetBlock ?: run {
            stopMining()
            if (++idleTicks > 40) {
                chat("No loaded spawners remain in the collection radius.", this)
                notifyWebhook("Collection paused", "No loaded spawners remain nearby.", triggerPosition)
                enabled = false
            }
            return
        }
        idleTicks = 0
        if (player.position().distanceTo(Vec3.atCenterOf(target)) > breakRange) {
            stopMining()
            face(target)
            return
        }
        face(target)
        val gameMode = mc.gameMode ?: return
        if (miningTarget != target) {
            stopMining()
            gameMode.startDestroyBlock(target, Direction.UP)
            miningTarget = target.immutable()
        }
        gameMode.continueDestroyBlock(target, Direction.UP)
    }

    private fun walkToChest() {
        val player = mc.player ?: return
        val chest = targetChest ?: run { phase = Phase.COLLECTING; return }
        face(chest)
        if (player.blockPosition().distSqr(chest) <= 16.0) {
            stopMining()
            phase = Phase.OPENING_CHEST
        }
    }

    private fun openChest() {
        val player = mc.player ?: return
        val chest = targetChest ?: run { phase = Phase.COLLECTING; return }
        if (player.containerMenu != player.inventoryMenu) {
            phase = Phase.DEPOSITING
            menuTicks = 0
            return
        }
        val gameMode = mc.gameMode ?: return
        if (menuTicks++ % 12 == 0) {
            val hit = BlockHitResult(Vec3.atCenterOf(chest), Direction.UP, chest, false)
            gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit)
        }
        if (menuTicks > 200) {
            chat("Ender chest did not open; collection paused with items in your inventory.", this)
            phase = Phase.COLLECTING
            menuTicks = 0
        }
    }

    private fun depositTick() {
        val player = mc.player ?: return
        val menu = player.containerMenu
        if (menu === player.inventoryMenu) {
            phase = Phase.COLLECTING
            targetChest = null
            return
        }
        val spawnerSlot = menu.slots.firstOrNull { slot -> slot.container === player.inventory && slot.item.item == Items.SPAWNER }
        if (spawnerSlot != null) {
            mc.gameMode?.handleContainerInput(menu.containerId, spawnerSlot.index, 0, ContainerInput.QUICK_MOVE, player)
            return
        }
        player.closeContainer()
        phase = Phase.COLLECTING
        targetChest = null
    }

    private fun findNearest(block: net.minecraft.world.level.block.Block, radius: Int): BlockPos? {
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        val center = player.blockPosition()
        val low = center.offset(-radius, -radius, -radius)
        val high = center.offset(radius, radius, radius)
        return BlockPos.betweenClosedStream(low, high).use { positions ->
            positions.iterator().asSequence()
                .map { it.immutable() }
                .filter { level.getBlockState(it).block == block }
                .minByOrNull { it.distToCenterSqr(player.x, player.y, player.z) }
        }
    }

    private fun inventoryNeedsSpace(): Boolean {
        val player = mc.player ?: return false
        val storage = (0..35).map(player.inventory::getItem)
        return storage.none { it.isEmpty } || storage.sumOf { if (it.item == Items.SPAWNER) it.count else 0 } >= targetStacks * 64
    }

    private fun face(pos: BlockPos) {
        val player = mc.player ?: return
        val delta = Vec3.atCenterOf(pos).subtract(player.eyePosition)
        val targetYaw = Math.toDegrees(atan2(-delta.x, delta.z)).toFloat()
        val horizontal = sqrt(delta.x * delta.x + delta.z * delta.z)
        val targetPitch = (-Math.toDegrees(atan2(delta.y, horizontal))).toFloat()
        val step = rotationSpeed.coerceIn(1f, 30f)
        player.yRot += net.minecraft.util.Mth.clamp(net.minecraft.util.Mth.wrapDegrees(targetYaw - player.yRot), -step, step)
        player.xRot += net.minecraft.util.Mth.clamp(net.minecraft.util.Mth.wrapDegrees(targetPitch - player.xRot), -step, step)
    }

    private fun stopMining() {
        val player = mc.player
        if (miningTarget != null && mc.gameMode?.isDestroying == true) mc.gameMode?.stopDestroyBlock()
        miningTarget = null
        if (player != null) mc.options.keyAttack.setDown(mc.options.keyAttack.isPressedOnAny)
    }

    private fun notifyWebhook(title: String, detail: String, pos: BlockPos?) {
        val uri = runCatching { URI(webhookUrl.trim()) }.getOrNull() ?: return
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return
        val suffix = pos?.let { " (${it.x}, ${it.y}, ${it.z})" }.orEmpty()
        val content = "$title: $detail$suffix"
        val requestGeneration = webhookGeneration.get()
        worker.execute {
            if (!enabled || requestGeneration != webhookGeneration.get()) return@execute
            runCatching {
                val body = "{\"content\":\"${json(content)}\"}"
                val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build()
                if (!enabled || requestGeneration != webhookGeneration.get()) return@runCatching
                webClient.send(request, HttpResponse.BodyHandlers.discarding())
            }
        }
    }

    private fun json(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
