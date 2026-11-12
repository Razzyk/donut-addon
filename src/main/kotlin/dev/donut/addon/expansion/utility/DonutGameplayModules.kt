package dev.donut.addon.expansion.utility

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.UseCooldownEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.input.InputTracker.isPressedOnAny
import net.minecraft.client.KeyMapping
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.tags.ItemTags
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import kotlin.random.Random

/** Selects the best real hotbar tool for the block the player is actively mining. */
class DonutToolCycle : DonutUtilityModule("Donut Tool Cycle", "Selects the fastest suitable hotbar tool while you mine and returns to your original slot afterward.") {
    private val drillInstabreak by boolean("Drill Instabreak", true)
    private val delayedSwitch by boolean("Delay", true)
    private var originalSlot = -1
    private var ownedSlot = -1
    private var target: Any? = null
    private var pendingSlot = -1
    private var pendingTicks = 0

    override fun onDisabled() = reset()

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        val player = mc.player
        val level = mc.level
        if (player == null || level == null || mc.gui.screen() != null || !mc.options.keyAttack.isPressedOnAny) {
            restoreSelection()
            target = null
            pendingSlot = -1
            return@handler
        }
        val hit = mc.hitResult as? BlockHitResult ?: run { restoreSelection(); target = null; return@handler }
        val pos = hit.blockPos
        val state = level.getBlockState(pos)
        if (state.isAir) { restoreSelection(); target = null; return@handler }
        if (target != pos.asLong()) { target = pos.asLong(); pendingSlot = -1; pendingTicks = 0 }
        val inventory = player.inventory
        val current = inventory.selectedSlot
        if (ownedSlot >= 0 && current != ownedSlot) { originalSlot = -1; ownedSlot = -1 }

        val special = (0..8).firstOrNull { isShard(inventory.getItem(it), state) && (!drillInstabreak || breakDelta(inventory.getItem(it), state, level, pos) >= 1f) }
        val best = special ?: (0..8).maxByOrNull { index ->
            val stack = inventory.getItem(index)
            if (stack.isEmpty) -1f else breakDelta(stack, state, level, pos)
        } ?: current
        if (best == current) { pendingSlot = -1; return@handler }
        if (pendingSlot != best) { pendingSlot = best; pendingTicks = if (delayedSwitch) 2 else 0 }
        if (pendingTicks > 0) { pendingTicks--; return@handler }
        if (originalSlot < 0) originalSlot = current
        inventory.selectedSlot = best
        ownedSlot = best
        pendingSlot = -1
    }

    private fun breakDelta(stack: ItemStack, state: BlockState, level: net.minecraft.client.multiplayer.ClientLevel, pos: net.minecraft.core.BlockPos): Float {
        val hardness = state.getDestroySpeed(level, pos)
        if (hardness <= 0f) return if (hardness == 0f) 1f else 0f
        val suitable = stack.isCorrectToolForDrops(state)
        val speed = stack.getDestroySpeed(state)
        return speed / hardness / if (suitable) 30f else 100f
    }

    private fun isShard(stack: ItemStack, state: BlockState): Boolean = !stack.isEmpty && stack.`is`(ItemTags.PICKAXES) &&
        stack.isCorrectToolForDrops(state) && stack.hoverName.string.contains("shard pickaxe", true)

    private fun restoreSelection() {
        val player = mc.player ?: return reset()
        if (originalSlot >= 0 && ownedSlot >= 0 && player.inventory.selectedSlot == ownedSlot) player.inventory.selectedSlot = originalSlot
        reset()
    }

    private fun reset() { originalSlot = -1; ownedSlot = -1; target = null; pendingSlot = -1; pendingTicks = 0 }
}

/** Left and right click scheduling with independent humanized rates and input rules. */
class DonutRhythm : DonutUtilityModule("Donut Rhythm", "Schedules left and right clicks at randomized human rates.") {
    private enum class ActionStyle(override val tag: String) : Tagged { CLICK("Click"), HOLD("Hold"), OFF("Off") }
    private val leftStyle by enumChoice("Left", ActionStyle.CLICK)
    private val leftMin by int("Left Min CPS", 9, 1..20)
    private val leftMax by int("Left Max CPS", 13, 1..20)
    private val rightStyle by enumChoice("Right", ActionStyle.OFF)
    private val rightMin by int("Right Min CPS", 8, 1..20)
    private val rightMax by int("Right Max CPS", 12, 1..20)
    private val randomization by float("Randomization", 15f, 0f..40f)
    private val onlyWhileHeld by boolean("Only While Held", false)
    private val breakBlocks by boolean("Break Blocks", true)
    private val random = Random(System.nanoTime())
    private var nextLeft = 0L
    private var nextRight = 0L
    private var leftReleaseAt = 0L
    private var rightReleaseAt = 0L
    private var leftVirtual = false
    private var rightVirtual = false

    override fun onDisabled() { releaseVirtualKeys(); nextLeft = 0L; nextRight = 0L }

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        val player = mc.player
        val now = System.nanoTime()
        if (player == null || mc.gui.screen() != null || mc.isPaused) { releaseVirtualKeys(); return@handler }
        schedule(ActionStyle.CLICK.takeIf { leftStyle == ActionStyle.CLICK } ?: leftStyle, mc.options.keyAttack, leftMin, leftMax, now, true)
        schedule(ActionStyle.CLICK.takeIf { rightStyle == ActionStyle.CLICK } ?: rightStyle, mc.options.keyUse, rightMin, rightMax, now, false)
    }

    private fun schedule(style: ActionStyle, key: KeyMapping, min: Int, max: Int, now: Long, attack: Boolean) {
        val next = if (attack) nextLeft else nextRight
        val isHeld = key.isPressedOnAny
        val virtual = if (attack) leftVirtual else rightVirtual
        val releaseAt = if (attack) leftReleaseAt else rightReleaseAt
        if (style == ActionStyle.OFF || onlyWhileHeld && !isHeld) {
            if (virtual) key.setDown(isHeld)
            if (attack) leftVirtual = false else rightVirtual = false
            return
        }
        if (attack && !breakBlocks && mc.hitResult is BlockHitResult) return
        if (virtual) {
            if (now >= releaseAt) {
                key.setDown(isHeld)
                if (attack) leftVirtual = false else rightVirtual = false
            } else return
        }
        if (now < next) return
        val low = min.coerceIn(1, 20)
        val high = max.coerceIn(low, 20)
        val cps = random.nextInt(low, high + 1).toDouble()
        val jitter = 1.0 + random.nextDouble(-randomization.coerceIn(0f, 40f) / 100.0, randomization.coerceIn(0f, 40f) / 100.0)
        val interval = (1_000_000_000.0 / cps * jitter).toLong().coerceAtLeast(1L)
        if (style == ActionStyle.HOLD) {
            key.setDown(true)
            val holdMs = random.nextLong(20, 111)
            if (attack) { leftVirtual = true; leftReleaseAt = now + holdMs * 1_000_000 } else { rightVirtual = true; rightReleaseAt = now + holdMs * 1_000_000 }
        } else {
            key.setDown(true)
            val clickMs = random.nextLong(20, 46)
            if (attack) { leftVirtual = true; leftReleaseAt = now + clickMs * 1_000_000 } else { rightVirtual = true; rightReleaseAt = now + clickMs * 1_000_000 }
        }
        if (attack) nextLeft = now + interval else nextRight = now + interval
    }

    private fun releaseVirtualKeys() {
        if (leftVirtual) mc.options.keyAttack.setDown(mc.options.keyAttack.isPressedOnAny)
        if (rightVirtual) mc.options.keyUse.setDown(mc.options.keyUse.isPressedOnAny)
        leftVirtual = false; rightVirtual = false
    }
}

/** Applies a configurable use cooldown while the selected block or item is held. */
class DonutRapidUse : DonutUtilityModule("Donut Rapid Use", "Reduces right-click delay for selected blocks or items while use is held.") {
    private val delay by int("Delay", 0, 0..3)
    private val blocks by boolean("Blocks", true)
    private val items by boolean("Items", true)

    @Suppress("unused")
    private val cooldown = handler<UseCooldownEvent> { event ->
        val player = mc.player ?: return@handler
        if (mc.gui.screen() != null || !mc.options.keyUse.isPressedOnAny) return@handler
        val stack = if (!player.mainHandItem.isEmpty) player.mainHandItem else player.offhandItem
        if (stack.isEmpty) return@handler
        val isBlock = stack.item is BlockItem
        if (if (isBlock) blocks else items) event.cooldown = delay.coerceIn(0, 3) + 1
    }
}

/** Eats the best available food when the configured hunger or health threshold is reached. */
class DonutMealAssist : DonutUtilityModule("Donut Meal Assist", "Consumes suitable hotbar or offhand food when hunger or health drops below its threshold.") {
    private val hunger by int("Hunger", 16, 0..20)
    private val health by int("Health", 10, 0..20)
    private val harmful = setOf(Items.ROTTEN_FLESH, Items.POISONOUS_POTATO, Items.PUFFERFISH, Items.SPIDER_EYE)
    private var eating = false
    private var restoreSlot = -1

    override fun onDisabled() = stop()

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        val player = mc.player
        if (player == null || mc.level == null || mc.gameMode == null || mc.gui.screen() != null || player.isCreative || player.isSpectator) {
            stop(); return@handler
        }
        val food = player.foodData.foodLevel
        val lowHealth = health > 0 && player.health <= health
        if (food > hunger && !lowHealth) { stop(); return@handler }
        if (player.isUsingItem) {
            if (eating && player.useItem.get(DataComponents.FOOD) != null) mc.options.keyUse.setDown(true)
            else stop()
            return@handler
        }
        val best = bestFood(player, lowHealth && food > hunger) ?: run { stop(); return@handler }
        if (best.first == InteractionHand.MAIN_HAND) {
            val slot = best.second
            if (slot != player.inventory.selectedSlot) {
                if (restoreSlot < 0) restoreSlot = player.inventory.selectedSlot
                player.inventory.selectedSlot = slot
            }
        }
        eating = true
        mc.options.keyUse.setDown(true)
    }

    private fun bestFood(player: net.minecraft.client.player.LocalPlayer, healing: Boolean): Pair<InteractionHand, Int>? {
        var choice: Pair<InteractionHand, Int>? = null
        var score = Float.NEGATIVE_INFINITY
        fun examine(stack: ItemStack, hand: InteractionHand, slot: Int) {
            val food = stack.get(DataComponents.FOOD) ?: return
            if (stack.item in harmful || !food.canAlwaysEat() && player.foodData.foodLevel >= 20) return
            val healingFood = stack.item == Items.GOLDEN_APPLE || stack.item == Items.ENCHANTED_GOLDEN_APPLE
            val value = food.nutrition() + food.saturation() + if (healing && player.health < player.maxHealth && healingFood) 100f else 0f
            if (value > score) { score = value; choice = hand to slot }
        }
        for (slot in 0..8) examine(player.inventory.getItem(slot), InteractionHand.MAIN_HAND, slot)
        examine(player.offhandItem, InteractionHand.OFF_HAND, -1)
        return choice
    }

    private fun stop() {
        if (eating) mc.options.keyUse.setDown(mc.options.keyUse.isPressedOnAny)
        eating = false
        val player = mc.player
        if (player != null && restoreSlot >= 0 && player.inventory.selectedSlot != restoreSlot) player.inventory.selectedSlot = restoreSlot
        restoreSlot = -1
    }
}

/** Removes selected trap entities and rejects their spawn packets before they enter the client world. */
class DonutEscapeAssist : DonutUtilityModule("Donut Escape Assist", "Removes configured trap entities and can block them before client-side spawn.") {
    private val removeExisting by boolean("Remove Existing", true)
    private val preventSpawn by boolean("Prevent Spawn", true)
    private val armorStands by boolean("Armor Stands", true)
    private val chestMinecarts by boolean("Chest Minecarts", true)
    private var tickCounter = 0

    @Suppress("unused")
    private val packet = handler<PacketEvent> { event ->
        if (!preventSpawn || event.origin != TransferOrigin.INCOMING) return@handler
        val spawn = event.packet as? ClientboundAddEntityPacket ?: return@handler
        if (isTrapType(spawn.type)) event.cancelEvent()
    }

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        if (!removeExisting || ++tickCounter % 20 != 0) return@handler
        val level = mc.level ?: return@handler
        val camera = mc.cameraEntity
        level.entitiesForRendering().filter { it !== camera && isTrap(it) }.forEach { it.discard() }
    }

    private fun isTrap(entity: Entity): Boolean = isTrapType(entity.type)
    private fun isTrapType(type: net.minecraft.world.entity.EntityType<*>): Boolean =
        armorStands && type == EntityTypes.ARMOR_STAND || chestMinecarts && BuiltInRegistries.ENTITY_TYPE.getKey(type).path.let { it.contains("chest_minecart") || it.contains("minecart_chest") }
}

/** Cancels outgoing movement packets until disabled, freezing the local avatar in place. */
class DonutHoldPosition : DonutUtilityModule("Donut Hold Position", "Stops client movement packets until the module is turned off.") {
    @Suppress("unused")
    private val packet = handler<PacketEvent> { event ->
        if (event.origin == TransferOrigin.OUTGOING && event.packet is ServerboundMovePlayerPacket ||
            event.origin == TransferOrigin.OUTGOING && event.packet is ServerboundMoveVehiclePacket ||
            event.origin == TransferOrigin.OUTGOING && event.packet is ServerboundPlayerCommandPacket
        ) event.cancelEvent()
    }
}

/** Uses a small movement packet ground-state cycle to suppress Donut fall damage. */
class DonutLandingGuard : DonutUtilityModule("Donut Landing Guard", "Spoofs safe ground state around a fall and resets after a short movement packet cycle.") {
    private var armed = false
    private var lastFall = 0f
    private var groundPackets = 0
    private var sendingReplacement = false

    override fun onEnabled() { armed = false; lastFall = 0f; groundPackets = 0 }
    override fun onDisabled() { armed = false; lastFall = 0f; groundPackets = 0 }

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        val player = mc.player ?: return@handler
        if (armed) player.setOnGround(false)
        if (lastFall > 3f) armed = true
        lastFall = player.fallDistance.toFloat()
    }

    @Suppress("unused")
    private val packets = handler<PacketEvent> { event ->
        if (sendingReplacement || event.origin != TransferOrigin.OUTGOING) return@handler
        val movement = event.packet as? ServerboundMovePlayerPacket ?: return@handler
        val player = mc.player ?: return@handler
        val connection = mc.connection ?: return@handler
        event.cancelEvent()
        if (armed && movement.isOnGround) groundPackets++
        if (groundPackets % 2 == 1) player.setPos(player.x, player.y + 1.0e-8, player.z)
        val replacement = ServerboundMovePlayerPacket.PosRot(
            player.x, player.y, player.z, player.yRot, player.xRot, !armed && player.onGround(), player.horizontalCollision
        )
        sendingReplacement = true
        try { connection.send(replacement) } finally { sendingReplacement = false }
        if (groundPackets >= 4) { armed = false; groundPackets = 0 }
    }
}

/** Sets the swing animation duration through a small mixin bridge. */
class DonutSwingRate : DonutUtilityModule("Donut Swing Rate", "Changes the duration of the local hand swing animation.") {
    private val speed by float("Speed", 0.25f, 0.1f..2f)

    fun adjustedDuration(vanillaTicks: Int): Int = if (!enabled) vanillaTicks else (vanillaTicks / speed.coerceIn(0.1f, 2f)).toInt().coerceAtLeast(1)
}

/** Shared callbacks for optional mixin hooks in this utility family. */
object UtilityRuntime {
    @JvmStatic fun swingDuration(vanillaTicks: Int): Int = UtilityFeatures.swingRate.adjustedDuration(vanillaTicks)
}
