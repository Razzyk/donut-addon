package dev.donut.addon.expansion.movement

import dev.donut.addon.AddonCategories
import net.ccbluex.liquidbounce.additions.forceSneak
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.*
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.input.InputTracker.isPressedOnAny
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/** Overrides input snapshots instead of latching KeyMapping state, so disabling releases immediately. */
object DonutTravelKeys : ClientModule("DonutTravelKeys", AddonCategories.UTILITY, disableOnQuit = true) {
    private enum class Heading(override val tag: String, val input: DirectionalInput) : Tagged {
        FORWARD("Forwards", DirectionalInput.FORWARDS), BACK("Backwards", DirectionalInput.BACKWARDS),
        LEFT("Left", DirectionalInput.LEFT), RIGHT("Right", DirectionalInput.RIGHT)
    }
    private val direction by enumChoice("SimpleDirection", Heading.FORWARD)
    private val stopOnInput by boolean("DisableOnInput", false)
    private val stopOnHeight by boolean("DisableOnYChange", false)
    private val loadedOnly by boolean("NoUnloadedChunks", true)
    init { literalDescription { "Walk in a chosen direction, with input, height and chunk guards." } }

    private val input = handler<MovementInputEvent> { event ->
        val p = mc.player ?: return@handler
        if (mc.gui.screen() != null) return@handler
        val physicalInput = with(mc.options) { listOf(keyUp, keyDown, keyLeft, keyRight, keyJump, keyShift) }
            .any { it.isPressedOnAny }
        if (stopOnInput && physicalInput || stopOnHeight && p.y != p.yo) {
            enabled = false
            return@handler
        }
        event.directionalInput = direction.input
    }
    private val move = handler<PlayerMoveEvent> { event ->
        if (!loadedOnly) return@handler
        val p = mc.player ?: return@handler
        val level = mc.level ?: return@handler
        val next = BlockPos.containing(p.x + event.movement.x * 2.0, p.y, p.z + event.movement.z * 2.0)
        if (!level.hasChunkAt(next)) event.movement = Vec3(0.0, event.movement.y, 0.0)
    }
}

object DonutHopAssist : ClientModule("DonutHopAssist", AddonCategories.UTILITY) {
    private enum class Hop(override val tag: String) : Tagged { NORMAL("Jump"), LOW("LowHop") }
    private enum class Condition(override val tag: String) : Tagged {
        ALWAYS("Always"), WALKING("Walking"), SPRINTING("Sprinting")
    }
    private val mode by enumChoice("Mode", Hop.NORMAL)
    private val condition by enumChoice("JumpIf", Condition.ALWAYS)
    private val height by float("VelocityHeight", .25f, 0f..2f)
    init { literalDescription { "Jump on landing, either normally or with a chosen vertical impulse." } }
    private val tick = handler<PlayerTickEvent> {
        val p = mc.player ?: return@handler
        if (!p.onGround() || p.isShiftKeyDown || mc.gui.screen() != null) return@handler
        if (!MotionPolicy.jumpAllowed(condition.tag, DirectionalInput(p.input).isMoving, p.isSprinting)) return@handler
        if (mode == Hop.NORMAL) p.jumpFromGround()
        else p.deltaMovement = p.deltaMovement.multiply(1.0, 0.0, 1.0).add(0.0, height.toDouble(), 0.0)
    }
}

object DonutWallClimb : ClientModule("DonutWallClimb", AddonCategories.UTILITY) {
    private val rate by float("ClimbSpeed", .2f, 0f..10f)
    init { literalDescription { "Apply a vertical climbing impulse when colliding with a wall." } }
    private val tick = handler<PlayerPostTickEvent> {
        val p = mc.player ?: return@handler
        val motion = p.deltaMovement
        if (p.horizontalCollision && motion.y < .2) p.setDeltaMovement(motion.x, rate.toDouble(), motion.z)
    }
}

object DonutCrouch : ClientModule("DonutCrouch", AddonCategories.UTILITY) {
    private enum class Crouch(override val tag: String) : Tagged { LOCAL("Vanilla"), NETWORK("Packet") }
    private val mode by enumChoice("Mode", Crouch.LOCAL)
    init { literalDescription { "Hold crouch locally or only in movement input sent to the server." } }
    private val input = handler<MovementInputEvent> { event ->
        val p = mc.player ?: return@handler
        if (!p.abilities.flying && mode == Crouch.LOCAL) event.sneak = true
    }
    private val packet = handler<PacketEvent> { event ->
        val packet = event.packet
        if (mode == Crouch.NETWORK && event.origin == TransferOrigin.OUTGOING &&
            mc.player?.abilities?.flying == false && packet is ServerboundPlayerInputPacket) {
            packet.forceSneak = true
        }
    }
}

/** A held-use teleport with the reference's interaction guards and ten-block padding cadence. */
object DonutPointTravel : ClientModule("DonutPointTravel", AddonCategories.UTILITY, disableOnQuit = true) {
    init { literalDescription { "While using an idle hand, move to the solid surface under the camera crosshair." } }
    private val tick = handler<PlayerPostTickEvent> {
        val p = mc.player ?: return@handler
        val level = mc.level ?: return@handler
        if (mc.gui.screen() != null || !mc.options.keyUse.isDown || p.mainHandItem.getUseDuration(p) > 0) return@handler
        // Keep normal nearby entity and block-item interactions available.
        if (mc.hitResult?.type == HitResult.Type.ENTITY ||
            mc.hitResult?.type == HitResult.Type.BLOCK && p.mainHandItem.item is BlockItem) return@handler
        val camera = mc.gameRenderer.mainCamera()
        val eye = camera.position()
        val aim = Vec3.directionFromRotation(camera.xRot(), camera.yRot())
        val hit = level.clip(ClipContext(eye, eye.add(aim.scale(210.0)), ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE, p))
        if (hit.type != HitResult.Type.BLOCK) return@handler
        val state = level.getBlockState(hit.blockPos)
        // Vanilla interaction must get the first chance; chests, doors and other usable blocks don't teleport.
        if (state.useWithoutItem(level, p, hit).consumesAction()) return@handler
        var shape = state.getCollisionShape(level, hit.blockPos)
        if (shape.isEmpty) shape = state.getShape(level, hit.blockPos)
        val top = if (shape.isEmpty) 1.0 else shape.max(net.minecraft.core.Direction.Axis.Y)
        val destination = Vec3(hit.blockPos.x + .5 + hit.direction.stepX,
            hit.blockPos.y + top, hit.blockPos.z + .5 + hit.direction.stepZ)
        repeat(MotionPolicy.teleportPadding(p.position().distanceTo(destination))) {
            p.connection.send(ServerboundMovePlayerPacket.StatusOnly(true, true))
        }
        p.connection.send(ServerboundMovePlayerPacket.Pos(destination.x, destination.y, destination.z, true, true))
        p.setPos(destination)
    }
}
