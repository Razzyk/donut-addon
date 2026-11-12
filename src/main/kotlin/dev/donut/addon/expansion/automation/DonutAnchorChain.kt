package dev.donut.addon.expansion.automation

import dev.donut.addon.expansion.automation.core.AutomationCombatLock
import dev.donut.addon.expansion.automation.core.BlastShieldPlanner
import dev.donut.addon.expansion.automation.core.CombatDamageRules
import dev.donut.addon.expansion.automation.core.LootProtectionLedger
import dev.donut.addon.expansion.automation.core.ObservedPlayerDeath
import dev.donut.addon.expansion.automation.core.ShieldPlacementCandidate
import dev.donut.addon.expansion.automation.core.WorldPoint
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.input.InputTracker.isPressedOnAny
import net.ccbluex.liquidbounce.utils.entity.getDamageFromExplosion
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.ItemTags
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RespawnAnchorBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/** Right-click respawn-anchor chain with measured self damage and a ray-tested blast shield. */
class DonutAnchorChain : DonutAutomationModule("Donut Anchor Chain") {
    private enum class Mode(override val tag: String) : Tagged { SINGLE("Single"), DOUBLE("Double") }
    private enum class UnsafePolicy(override val tag: String) : Tagged { DETONATE("Detonate"), CANCEL("Cancel") }
    private enum class Phase { IDLE, WAIT_ANCHOR, GUARD_SELECT, GUARD_WAIT, ALIGN_ANCHOR, CHARGE_WAIT, EXPLOSION_WAIT, PLACE_SECOND }

    private data class GuardPlan(
        val candidate: ShieldPlacementCandidate,
        val position: BlockPos,
        val support: BlockPos,
        val face: Direction,
        val aimPoint: Vec3,
    )

    private val mode by enumChoice("Mode", Mode.SINGLE)
    private val delayTicks by int("Delay", 0, 0..5)
    private val safeAnchor by boolean("Safe Anchor", true)
    private val unguardedPolicy by enumChoice("If Unguarded", UnsafePolicy.DETONATE).visibleWhen { safeAnchor }
    private val switchBack by boolean("Switch Back", false)
    private val protectLoot by boolean("Protect Loot", true)

    private val lootLedger = LootProtectionLedger()
    private var phase = Phase.IDLE
    private var target: BlockPos? = null
    private var placementSupport: BlockHitResult? = null
    private var guardPlan: GuardPlan? = null
    private var guardTries = 0
    private var guardComplete = false
    private var pendingPlaceAnchor = false
    private var expectedGuardBlock = ""
    private var expectedCharge = -1
    private var deadline = 0L
    private var nextActionTick = 0L
    private var detonations = 0
    private var useWasDown = false
    private var aimTarget: Vec3? = null
    private var originalPlayer: LocalPlayer? = null
    private var originalSlot = -1
    private var automationSlot = -1

    init { literalDescription { "Places, charges and detonates anchors with measured blast safety and a placed exposure shield." } }

    override fun onAutomationReset() {
        restoreOwnedSelection()
        clearState()
        lootLedger.clear()
        AutomationCombatLock.release(this)
    }

    @Suppress("unused")
    private val rotation = handler<RotationUpdateEvent> {
        val point = aimTarget ?: return@handler
        val player = mc.player ?: return@handler
        RotationManager.setRotationTarget(
            RotationTarget(
                Rotation.lookingAt(point, player.eyePosition),
                ticksUntilReset = 1,
                movementCorrection = MovementCorrection.OFF,
            ),
            Priority.IMPORTANT_FOR_USAGE_2,
            this@DonutAnchorChain,
        )
    }

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        val frame = captureFrame() ?: return@handler
        updateLootLedger(frame)
        val useDown = mc.options.keyUse.isPressedOnAny
        if (!useDown) {
            useWasDown = false
            finish(frame)
            return@handler
        }
        val newPress = !useWasDown
        useWasDown = true
        if (mc.gui.screen() != null || frame.player.isDeadOrDying) { finish(frame); return@handler }
        if (frame.snapshot.tick < nextActionTick) return@handler
        if (phase != Phase.IDLE && protectLoot && target?.let { lootLedger.protects(dimensionId(frame), worldPoint(anchorCenter(it))) } == true) {
            finish(frame)
            return@handler
        }

        when (phase) {
            Phase.IDLE -> {
                if (!newPress) return@handler
                begin(frame) ?: return@handler
            }
            Phase.WAIT_ANCHOR -> {
                val anchor = target ?: run { finish(frame); return@handler }
                if (frame.world.getBlockState(anchor).`is`(Blocks.RESPAWN_ANCHOR)) {
                    pendingPlaceAnchor = false
                    phase = if (safeAnchor && !guardComplete) Phase.GUARD_SELECT else Phase.ALIGN_ANCHOR
                    deadline = 0L
                } else if (pendingPlaceAnchor) {
                    val support = placementSupport ?: run { finish(frame); return@handler }
                    val hit = mc.hitResult as? BlockHitResult
                    if (hit == null || hit.blockPos != support.blockPos || hit.direction != support.direction) {
                        aimTarget = support.location
                        if (frame.snapshot.tick > deadline) finish(frame)
                        return@handler
                    }
                    aimTarget = null
                    if (useOnBlock(frame, hit)) {
                        pendingPlaceAnchor = false
                        deadline = frame.snapshot.tick + 20
                        nextActionTick = frame.snapshot.tick + delayTicks + 1
                    }
                    return@handler
                } else if (frame.snapshot.tick > deadline) finish(frame) else return@handler
            }
            Phase.GUARD_SELECT -> { placeGuard(frame); return@handler }
            Phase.GUARD_WAIT -> { awaitGuard(frame); return@handler }
            Phase.ALIGN_ANCHOR -> { alignAndUseAnchor(frame); return@handler }
            Phase.CHARGE_WAIT -> {
                val anchor = target ?: run { finish(frame); return@handler }
                val state = frame.world.getBlockState(anchor)
                if (!state.`is`(Blocks.RESPAWN_ANCHOR)) { finish(frame); return@handler }
                if (state.getValue(RespawnAnchorBlock.CHARGE) > expectedCharge) {
                    phase = Phase.ALIGN_ANCHOR
                    nextActionTick = frame.snapshot.tick + delayTicks
                } else if (frame.snapshot.tick > deadline) finish(frame) else return@handler
            }
            Phase.EXPLOSION_WAIT -> {
                val anchor = target ?: run { finish(frame); return@handler }
                if (frame.world.getBlockState(anchor).`is`(Blocks.RESPAWN_ANCHOR)) {
                    if (frame.snapshot.tick > deadline) finish(frame)
                    return@handler
                }
                detonations++
                if (mode == Mode.DOUBLE && detonations == 1 &&
                    findHotbar(frame.player, Items.RESPAWN_ANCHOR) >= 0 && placementSupport != null) {
                    guardComplete = false
                    guardTries = 0
                    guardPlan = null
                    phase = Phase.PLACE_SECOND
                } else finish(frame)
                return@handler
            }
            Phase.PLACE_SECOND -> { placeSecondAnchor(frame); return@handler }
        }

        val anchor = target ?: return@handler
        if (protectLoot && lootLedger.protects(dimensionId(frame), worldPoint(anchorCenter(anchor)))) {
            finish(frame)
            return@handler
        }
        if (phase == Phase.ALIGN_ANCHOR) alignAndUseAnchor(frame)
    }

    private fun begin(frame: LiveAutomationFrame): Boolean? {
        if (frame.world.dimension() == Level.NETHER) return false
        val hit = mc.hitResult as? BlockHitResult ?: return false
        val clicked = frame.world.getBlockState(hit.blockPos)
        val clickedAnchor = clicked.`is`(Blocks.RESPAWN_ANCHOR)
        val anchor = if (clickedAnchor) hit.blockPos else hit.blockPos.relative(hit.direction)
        if (!clickedAnchor && (clicked.isAir || !frame.world.getBlockState(anchor).canBeReplaced())) return false
        if (protectLoot && lootLedger.protects(dimensionId(frame), worldPoint(anchorCenter(anchor)))) return false
        if (!AutomationCombatLock.tryAcquire(this)) return false

        target = anchor
        placementSupport = if (clickedAnchor) findPlacementSupport(frame, anchor) else hit
        guardTries = 0
        guardComplete = !safeAnchor
        detonations = 0
        expectedCharge = -1
        if (!clickedAnchor) {
            phase = Phase.WAIT_ANCHOR
            deadline = frame.snapshot.tick + 20
            pendingPlaceAnchor = true
            val slot = findHotbar(frame.player, Items.RESPAWN_ANCHOR)
            if (slot < 0) { finish(frame); return false }
            if (selectOwned(frame, slot)) return true
            if (useOnBlock(frame, hit)) {
                pendingPlaceAnchor = false
                nextActionTick = frame.snapshot.tick + delayTicks + 1
            }
            return true
        }
        phase = if (safeAnchor) Phase.GUARD_SELECT else Phase.ALIGN_ANCHOR
        deadline = 0L
        return true
    }

    private fun placeGuard(frame: LiveAutomationFrame) {
        val anchor = target ?: run { finish(frame); return }
        if (!frame.world.getBlockState(anchor).`is`(Blocks.RESPAWN_ANCHOR)) { finish(frame); return }
        if (guardPlan == null) {
            guardPlan = chooseGuardPlan(frame, anchor)
            if (guardPlan == null) { skipGuard(frame); return }
            deadline = frame.snapshot.tick + 12
        }
        val plan = guardPlan ?: return
        if (isSolidShield(frame, plan.position)) {
            guardComplete = true
            guardPlan = null
            phase = Phase.ALIGN_ANCHOR
            aimTarget = anchorCenter(anchor)
            nextActionTick = frame.snapshot.tick + delayTicks
            return
        }
        if (frame.snapshot.tick > deadline) {
            if (guardTries >= 2) skipGuard(frame)
            else { guardPlan = null; phase = Phase.GUARD_SELECT; deadline = frame.snapshot.tick + 12 }
            return
        }

        val hit = mc.hitResult as? BlockHitResult
        if (hit == null || hit.blockPos != plan.support || hit.direction != plan.face) {
            aimTarget = plan.aimPoint
            return
        }
        aimTarget = null
        val slot = findBlastShield(frame.player)
        if (slot < 0) { skipGuard(frame); return }
        if (selectOwned(frame, slot)) return
        if (useOnBlock(frame, hit)) {
            expectedGuardBlock = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                (frame.player.mainHandItem.item as BlockItem).block,
            ).toString()
            guardTries++
            phase = Phase.GUARD_WAIT
            deadline = frame.snapshot.tick + 10
        }
    }

    private fun awaitGuard(frame: LiveAutomationFrame) {
        val plan = guardPlan ?: run { skipGuard(frame); return }
        if (isSolidShield(frame, plan.position) &&
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(frame.world.getBlockState(plan.position).block).toString() == expectedGuardBlock) {
            guardComplete = true
            guardPlan = null
            phase = Phase.ALIGN_ANCHOR
            target?.let { aimTarget = anchorCenter(it) }
            nextActionTick = frame.snapshot.tick + delayTicks
            return
        }
        if (frame.snapshot.tick <= deadline) return
        if (guardTries < 2) {
            guardPlan = null
            phase = Phase.GUARD_SELECT
            deadline = frame.snapshot.tick + 12
        } else skipGuard(frame)
    }

    private fun skipGuard(frame: LiveAutomationFrame) {
        guardPlan = null
        if (unguardedPolicy == UnsafePolicy.CANCEL) finish(frame)
        else {
            guardComplete = true
            phase = Phase.ALIGN_ANCHOR
            target?.let { aimTarget = anchorCenter(it) }
        }
    }

    private fun alignAndUseAnchor(frame: LiveAutomationFrame) {
        val anchor = target ?: run { finish(frame); return }
        val state = frame.world.getBlockState(anchor)
        if (!state.`is`(Blocks.RESPAWN_ANCHOR) || frame.world.dimension() == Level.NETHER) { finish(frame); return }
        if (protectLoot && lootLedger.protects(dimensionId(frame), worldPoint(anchorCenter(anchor)))) { finish(frame); return }
        val hit = mc.hitResult as? BlockHitResult
        if (hit == null || hit.blockPos != anchor) {
            aimTarget = anchorCenter(anchor)
            return
        }
        aimTarget = null
        val charges = state.getValue(RespawnAnchorBlock.CHARGE)
        if (charges == 0) {
            val glowstone = findHotbar(frame.player, Items.GLOWSTONE)
            if (glowstone < 0) { finish(frame); return }
            if (selectOwned(frame, glowstone)) return
            expectedCharge = charges
            if (useOnBlock(frame, hit)) {
                phase = Phase.CHARGE_WAIT
                deadline = frame.snapshot.tick + 10
                nextActionTick = frame.snapshot.tick + delayTicks + 1
            }
            return
        }

        val detonator = findDetonator(frame.player)
        if (detonator < 0) { finish(frame); return }
        val hasTotem = frame.player.offhandItem.`is`(Items.TOTEM_OF_UNDYING) ||
            findHotbar(frame.player, Items.TOTEM_OF_UNDYING) >= 0
        val damage = frame.player.getDamageFromExplosion(anchorCenter(anchor), power = 5f).toDouble()
        val health = (frame.player.health + frame.player.absorptionAmount).toDouble()
        if (!CombatDamageRules.anchorAllowed(damage, health, hasTotem, frame.player.abilities.invulnerable)) {
            finish(frame); return
        }
        if (selectOwned(frame, detonator)) return
        if (useOnBlock(frame, hit)) {
            phase = Phase.EXPLOSION_WAIT
            deadline = frame.snapshot.tick + 20
            nextActionTick = frame.snapshot.tick + delayTicks + 1
        }
    }

    private fun placeSecondAnchor(frame: LiveAutomationFrame) {
        val anchor = target ?: run { finish(frame); return }
        if (frame.world.getBlockState(anchor).`is`(Blocks.RESPAWN_ANCHOR)) {
            phase = if (safeAnchor && !guardComplete) Phase.GUARD_SELECT else Phase.ALIGN_ANCHOR
            return
        }
        val support = placementSupport ?: run { finish(frame); return }
        val currentHit = mc.hitResult as? BlockHitResult
        if (currentHit == null || currentHit.blockPos != support.blockPos || currentHit.direction != support.direction) {
            aimTarget = support.location
            return
        }
        aimTarget = null
        val slot = findHotbar(frame.player, Items.RESPAWN_ANCHOR)
        if (slot < 0) { finish(frame); return }
        if (selectOwned(frame, slot)) return
        if (useOnBlock(frame, currentHit)) {
            phase = Phase.WAIT_ANCHOR
            deadline = frame.snapshot.tick + 20
            nextActionTick = frame.snapshot.tick + delayTicks + 1
        }
    }

    private fun chooseGuardPlan(frame: LiveAutomationFrame, anchor: BlockPos): GuardPlan? {
        if (findBlastShield(frame.player) < 0) return null
        val center = anchorCenter(anchor)
        val baseDamage = frame.player.getDamageFromExplosion(center, power = 5f).toDouble()
        if (!baseDamage.isFinite() || baseDamage <= 0.0) return null
        val feet = frame.player.blockPosition()
        val positions = linkedSetOf<BlockPos>()
        for (dx in -1..1) for (dz in -1..1) {
            if (dx != 0 || dz != 0) {
                positions += feet.offset(dx, 0, dz)
                positions += feet.offset(dx, 1, dz)
            }
        }
        Direction.values().forEach { positions += anchor.relative(it) }

        val plans = mutableMapOf<Long, GuardPlan>()
        val measured = mutableListOf<ShieldPlacementCandidate>()
        val eye = frame.player.eyePosition
        val reachSqr = frame.player.blockInteractionRange() * frame.player.blockInteractionRange()
        for (position in positions) {
            if (!frame.world.getBlockState(position).canBeReplaced() || AABB(position).intersects(frame.player.boundingBox)) continue
            val guardedDamage = frame.player.getDamageFromExplosion(center, power = 5f, include = position).toDouble()
            for (toward in Direction.values()) {
                val support = position.relative(toward)
                val supportState = frame.world.getBlockState(support)
                if (supportState.getCollisionShape(frame.world, support).isEmpty) continue
                val face = toward.opposite
                val hitPoint = Vec3(
                    support.x + 0.5 + face.stepX * 0.49,
                    support.y + 0.5 + face.stepY * 0.49,
                    support.z + 0.5 + face.stepZ * 0.49,
                )
                if (eye.distanceToSqr(hitPoint) > reachSqr) continue
                val trace = frame.world.clip(ClipContext(eye, hitPoint, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, frame.player)) as? BlockHitResult
                    ?: continue
                if (trace.blockPos != support || trace.direction != face) continue
                val candidate = ShieldPlacementCandidate(
                    position.asLong(), support.asLong(), face.name, baseDamage, guardedDamage,
                    open = true, reachable = true, intersectsPlayer = false,
                )
                measured += candidate
                plans[position.asLong()] = GuardPlan(candidate, position, support, face, hitPoint)
            }
        }
        val best = BlastShieldPlanner.choose(measured) ?: return null
        return plans[best.position]
    }

    private fun updateLootLedger(frame: LiveAutomationFrame) {
        val players = frame.world.players().map { other ->
            ObservedPlayerDeath(
                other.id,
                WorldPoint(other.x, other.y, other.z),
                other.isDeadOrDying,
                other === frame.player,
            )
        }
        val items = frame.world.entitiesForRendering().filterIsInstance<ItemEntity>().filter { it.isAlive }
            .map { WorldPoint(it.x, it.y, it.z) }
        lootLedger.update(
            System.currentTimeMillis(), dimensionId(frame), frame.player.id,
            WorldPoint(frame.player.x, frame.player.y, frame.player.z), players, items,
        )
    }

    private fun dimensionId(frame: LiveAutomationFrame): String = frame.world.dimension().identifier().toString()

    private fun anchorCenter(position: BlockPos): Vec3 = Vec3.atCenterOf(position)

    private fun worldPoint(point: Vec3): WorldPoint = WorldPoint(point.x, point.y, point.z)

    private fun findPlacementSupport(frame: LiveAutomationFrame, anchor: BlockPos): BlockHitResult? {
        val eye = frame.player.eyePosition
        val reachSqr = frame.player.blockInteractionRange() * frame.player.blockInteractionRange()
        for (toward in Direction.values()) {
            val support = anchor.relative(toward)
            val state = frame.world.getBlockState(support)
            if (state.getCollisionShape(frame.world, support).isEmpty) continue
            val face = toward.opposite
            val point = Vec3(
                support.x + 0.5 + face.stepX * 0.49,
                support.y + 0.5 + face.stepY * 0.49,
                support.z + 0.5 + face.stepZ * 0.49,
            )
            if (eye.distanceToSqr(point) > reachSqr) continue
            val hit = frame.world.clip(ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, frame.player)) as? BlockHitResult
                ?: continue
            if (hit.blockPos == support && hit.direction == face) return hit
        }
        return null
    }

    private fun findBlastShield(player: LocalPlayer): Int = (0..8).firstOrNull { slot ->
        val stack = player.inventory.getItem(slot)
        val blockItem = stack.item as? BlockItem ?: return@firstOrNull false
        val block = blockItem.block
        val state = block.defaultBlockState()
        block != Blocks.RESPAWN_ANCHOR && block != Blocks.GLOWSTONE &&
            state.isCollisionShapeFullBlock(player.level(), BlockPos.ZERO) && block.explosionResistance >= 6f
    } ?: -1

    private fun isSolidShield(frame: LiveAutomationFrame, pos: BlockPos): Boolean {
        val state = frame.world.getBlockState(pos)
        return !state.isAir && !state.canBeReplaced() && state.block != Blocks.RESPAWN_ANCHOR
    }

    private fun findDetonator(player: LocalPlayer): Int =
        findHotbar(player, Items.TOTEM_OF_UNDYING).takeIf { it >= 0 }
            ?: (0..8).firstOrNull { slot ->
                val stack = player.inventory.getItem(slot)
                !stack.isEmpty && (stack.`is`(ItemTags.SWORDS) || stack.`is`(ItemTags.AXES) ||
                    stack.`is`(Items.MACE) || stack.`is`(Items.TRIDENT))
            }
            ?: -1

    private fun findHotbar(player: LocalPlayer, item: Item): Int =
        (0..8).firstOrNull { player.inventory.getItem(it).`is`(item) } ?: -1

    private fun selectOwned(frame: LiveAutomationFrame, slot: Int): Boolean {
        if (frame.player.inventory.selectedSlot == slot) return false
        if (originalSlot < 0 || originalPlayer !== frame.player) {
            originalPlayer = frame.player
            originalSlot = frame.player.inventory.selectedSlot
        }
        automationSlot = slot
        selectHotbar(frame, slot)
        return true
    }

    private fun finish(frame: LiveAutomationFrame) {
        val keepHeld = mc.options.keyUse.isPressedOnAny
        restoreSelection(frame)
        clearState()
        useWasDown = keepHeld
        AutomationCombatLock.release(this)
    }

    private fun restoreSelection(frame: LiveAutomationFrame) {
        if (switchBack && originalPlayer === frame.player && originalSlot in 0..8 && automationSlot in 0..8 &&
            frame.player.inventory.selectedSlot == automationSlot) selectHotbar(frame, originalSlot)
        originalPlayer = null
        originalSlot = -1
        automationSlot = -1
    }

    private fun restoreOwnedSelection() {
        val player = originalPlayer ?: return
        if (switchBack && player === mc.player && originalSlot in 0..8 && automationSlot in 0..8 &&
            player.inventory.selectedSlot == automationSlot) player.inventory.selectedSlot = originalSlot
    }

    private fun clearState() {
        phase = Phase.IDLE
        target = null
        placementSupport = null
        guardPlan = null
        guardTries = 0
        guardComplete = false
        pendingPlaceAnchor = false
        expectedGuardBlock = ""
        expectedCharge = -1
        deadline = 0L
        nextActionTick = 0L
        detonations = 0
        useWasDown = false
        aimTarget = null
        originalPlayer = null
        originalSlot = -1
        automationSlot = -1
    }
}
