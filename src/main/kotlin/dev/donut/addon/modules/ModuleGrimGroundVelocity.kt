package dev.donut.addon.modules

import dev.donut.addon.resyncPosition
import net.ccbluex.liquidbounce.event.EventState
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.PlayerNetworkMovementTickEvent
import net.ccbluex.liquidbounce.event.events.PlayerTickEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import dev.donut.addon.AddonCategories
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentSkipListMap

object ModuleGrimGroundVelocity : ClientModule("GrimGroundVelocity", AddonCategories.DONUT) {
    private val freezeIfWalk by boolean("FreezeIfWalk", false)
    private val resetTicks by int("ResetTicks", 3, 1..10, "ticks")
    private val explosions by boolean("Explosions", false)
    private val notInWater by boolean("NotInWater", false)
    private val horizontalThreshold by float("HorizontalThreshold", 0f, 0f..5f)
    private val verticalThreshold by float("VerticalThreshold", 0f, 0f..5f)

    @Volatile private var damageCredits = 0
    @Volatile private var cancelTick = Int.MIN_VALUE
    @Volatile private var legacyPending = false
    private var freezeThisTick = false
    private var owner: LocalPlayer? = null
    private var compensationBlock: BlockPos? = null
    private val acknowledgements = ConcurrentSkipListMap<Int, BlockPos>()

    override val tag: String get() = "Grim / Ground"

    init {
        literalDescription { "SlimefunHelper legacy Grim velocity, restricted to grounded hits." }
    }

    override fun onEnabled() = reset()
    override fun onDisabled() = reset()

    private fun reset() {
        damageCredits = 0
        legacyPending = false
        freezeThisTick = false
        cancelTick = Int.MIN_VALUE
        compensationBlock = null
        acknowledgements.clear()
        owner = mc.player
    }

    @Suppress("unused")
    private val packet = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING || event.isCancelled) return@handler
        if (owner !== mc.player) reset()
        when (val incoming = event.packet) {
            is ClientboundBlockChangedAckPacket ->
                acknowledgements.headMap(incoming.sequence(), true).clear()
            is ClientboundDamageEventPacket -> {
                if (incoming.entityId() == player.id && !incoming.sourceType().`is`(DamageTypeTags.NO_KNOCKBACK)) {
                    damageCredits++
                }
            }
            is ClientboundSetEntityMotionPacket -> {
                if (incoming.id != player.id || damageCredits <= 0) return@handler
                damageCredits--
                val velocity = incoming.movement
                if (eligible() &&
                    (velocity.horizontalDistance() >= horizontalThreshold ||
                        kotlin.math.abs(velocity.y) >= verticalThreshold)) {
                    event.cancelEvent()
                    armCompensation()
                }
            }
        }
    }

    private fun eligible(): Boolean = player.onGround() && !player.isFallFlying &&
        player.isAlive && (!notInWater || (!player.isInWater && !player.isInLava))

    private fun armCompensation() {
        cancelTick = player.tickCount
        legacyPending = true
    }

    @Suppress("unused")
    private val tick = handler<PlayerTickEvent> {
        if (owner !== mc.player) reset()
        freezeThisTick = false
        if (!legacyPending) {
            compensationBlock = null
            return@handler
        }
        if (!player.isAlive || player.isFallFlying || player.tickCount - cancelTick >= resetTicks) {
            legacyPending = false
            return@handler
        }
        val pos = if (player.isVisuallyCrawling) player.blockPosition() else player.blockPosition().above()
        if (pos == compensationBlock) {
            if (!acknowledgements.containsValue(pos)) {
                legacyPending = false
                return@handler
            }
        } else {
            compensateBlock(pos)
            compensationBlock = pos
        }
        val moving = mc.options.keyUp.isDown || mc.options.keyDown.isDown ||
            mc.options.keyLeft.isDown || mc.options.keyRight.isDown
        freezeThisTick = freezeIfWalk || !moving
        player.setOnGround(true)
        if (!freezeThisTick) player.resyncPosition()
    }

    private fun compensateBlock(pos: BlockPos) {
        val direction = Direction.getApproximateNearest(player.eyePosition.subtract(Vec3.atCenterOf(pos)))
        if (!interaction.isDestroying) {
            interaction.startPrediction(world) { sequence ->
                ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, direction, sequence)
            }
        }
        interaction.startPrediction(world) { sequence ->
            acknowledgements[sequence] = pos
            ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, direction, sequence)
        }
    }

    @Suppress("unused")
    private val networkTick = handler<PlayerNetworkMovementTickEvent>(priority = -100) { event ->
        if (event.state != EventState.PRE || !freezeThisTick || event.isCancelled) return@handler
        event.cancelEvent()
        network.send(ServerboundMovePlayerPacket.Rot(player.yRot, player.xRot, true, player.horizontalCollision))
    }

    /** Narrow native travel hook avoids cancelling the whole player tick. */
    @JvmStatic
    fun shouldFreezeTravel(): Boolean = running && freezeThisTick

    /** Called at vanilla's explosion knockback addition, so its other effects are preserved. */
    @JvmStatic
    fun cancelExplosionKnockback(knockback: Vec3): Boolean {
        if (!running || !explosions || !eligible()) return false
        val velocity = player.deltaMovement.add(knockback)
        if (velocity.horizontalDistance() < horizontalThreshold && kotlin.math.abs(velocity.y) < verticalThreshold) return false
        armCompensation()
        return true
    }
}
