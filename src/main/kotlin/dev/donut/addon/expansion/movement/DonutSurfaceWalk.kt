package dev.donut.addon.expansion.movement

import dev.donut.addon.AddonCategories
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.*
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Strider
import net.minecraft.world.entity.vehicle.boat.AbstractBoat
import net.minecraft.world.level.CollisionGetter
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import kotlin.math.sign

/** Independent water/lava support, powder snow, and the recovered fifteen-move surface cadence. */
object DonutSurfaceWalk : ClientModule("DonutSurfaceWalk", AddonCategories.UTILITY) {
    private enum class Surface(override val tag: String) : Tagged { SOLID("Solid"), IGNORE("Ignore") }
    private val powderSnow by boolean("PowderSnow", true)
    private val pulsing by boolean("SurfacePulse", false)
    private val pausePulse by boolean("PauseOnPulse", false).visibleWhen { pulsing }
    private val waterMode by enumChoice("WaterMode", Surface.SOLID)
    private val dipBurning by boolean("WaterDipWhenBurning", true).visibleWhen { waterMode == Surface.SOLID }
    private val waterSneak by boolean("WaterDipOnSneak", true).visibleWhen { waterMode == Surface.SOLID }
    private val waterFall by boolean("WaterDipOnFall", true).visibleWhen { waterMode == Surface.SOLID }
    private val waterHeight by int("WaterFallHeight", 4, 1..255).visibleWhen { waterMode == Surface.SOLID && waterFall }
    private val lavaMode by enumChoice("LavaMode", Surface.SOLID)
    private val dipResistant by boolean("LavaDipWithResistance", true).visibleWhen { lavaMode == Surface.SOLID }
    private val lavaSneak by boolean("LavaDipOnSneak", true).visibleWhen { lavaMode == Surface.SOLID }
    private val lavaFall by boolean("LavaDipOnFall", true).visibleWhen { lavaMode == Surface.SOLID }
    private val lavaHeight by int("LavaFallHeight", 4, 1..255).visibleWhen { lavaMode == Surface.SOLID && lavaFall }
    private val pulse = SurfaceWalkRules.Pulse()
    private var ascending = 10
    private var bubble = false
    private var aliasedMovement = false

    init { literalDescription { "Walk on configured water, lava, and powder snow with separate dipping controls." } }
    override fun onEnabled() = resetSurfaceSession()
    override fun onDisabled() = resetSurfaceSession()

    fun resetSurfaceSession() {
        ascending = 10
        bubble = false
        aliasedMovement = false
        pulse.reset()
    }

    private fun guards(): SurfaceWalkRules.Guards {
        val p = mc.player ?: return SurfaceWalkRules.Guards(spectator = true)
        val resistance = p.getEffect(MobEffects.FIRE_RESISTANCE)
        return SurfaceWalkRules.Guards(
            p.isSpectator, p.abilities.flying, p.vehicle is AbstractBoat, p.vehicle is Strider,
            DonutFlightControl.running, p.isOnFire, mc.options.keyShift.isDown, p.fallDistance.toDouble(),
            resistance != null && resistance.duration > 300.0 * p.getAttributeValue(Attributes.BURNING_TIME),
        )
    }

    private fun waterAllowed() = SurfaceWalkRules.water(guards(),
        SurfaceWalkRules.Options(waterMode == Surface.SOLID, waterSneak, waterFall, waterHeight), dipBurning)
    private fun lavaAllowed() = SurfaceWalkRules.lava(guards(),
        SurfaceWalkRules.Options(lavaMode == Surface.SOLID, lavaSneak, lavaFall, lavaHeight), dipResistant)
    private fun owns(entity: Entity) = running && mc.isSameThread && entity === mc.player && entity.level() === mc.level

    @JvmStatic fun fluidSupport(entity: LivingEntity, fluid: FluidState): Boolean {
        if (!owns(entity) || entity.isSwimming) return false
        return when (fluid.type) {
            Fluids.WATER, Fluids.FLOWING_WATER -> waterAllowed()
            Fluids.LAVA, Fluids.FLOWING_LAVA -> lavaAllowed()
            else -> false
        }
    }
    @JvmStatic fun snowSupport(entity: Entity): Boolean = owns(entity) && powderSnow
    @JvmStatic fun observeBubble(entity: Entity) { if (owns(entity)) bubble = true }

    @JvmStatic fun surfaceShape(level: CollisionGetter, state: BlockState, pos: BlockPos, original: VoxelShape): VoxelShape {
        if (!running || !mc.isSameThread || level !== mc.level || state.fluidState.isEmpty) return original
        val p = mc.player ?: return original
        if ((state.`is`(Blocks.WATER) || state.fluidState.type == Fluids.WATER) &&
            !p.isInWater && waterAllowed() && pos.y <= p.y - 1.0) return Shapes.block()
        if (state.`is`(Blocks.LAVA) && !p.isInLava && lavaAllowed()) {
            val dangerous = !dipResistant || !guards().lavaProtection
            if (dangerous || pos.y <= p.y - 1.0) return Shapes.block()
        }
        return original
    }

    private val postTick = handler<PlayerPostTickEvent> {
        val inBubble = bubble
        bubble = false
        val p = mc.player ?: return@handler
        val level = mc.level ?: return@handler
        if (p.isVisuallySwimming || p.isInWater && !waterAllowed() || p.isInLava && !lavaAllowed()) return@handler
        val moving = p.vehicle ?: p
        fun lift(y: Double) { moving.deltaMovement = Vec3(moving.deltaMovement.x, y, moving.deltaMovement.z) }
        if (inBubble) {
            if (mc.options.keyJump.isDown && moving.deltaMovement.y < 0.11) lift(0.11)
        } else if (moving.isInWater || moving.isInLava) {
            lift(0.11)
            ascending = 0
        } else {
            val below = level.getBlockState(moving.blockPosition().below())
            if (ascending == 0) lift(0.11)
            else if (ascending == 1 && (below.`is`(Blocks.WATER) || below.`is`(Blocks.LAVA) ||
                    below.getValueOrElse(BlockStateProperties.WATERLOGGED, false))) lift(0.0)
            if (ascending < Int.MAX_VALUE) ascending++
        }
    }

    private val captureMove = handler<PlayerMoveEvent>(priority = Short.MAX_VALUE) { event ->
        val p = mc.player
        aliasedMovement = pulsing && p != null && p.deltaMovement.x == event.movement.x &&
            p.deltaMovement.y == event.movement.y && p.deltaMovement.z == event.movement.z
    }

    private val move = handler<PlayerMoveEvent> { event ->
        if (!pulsing) return@handler
        val alias = aliasedMovement
        aliasedMovement = false
        val p = mc.player ?: return@handler
        val supported = supportedLiquids()
        val usable = supported and 1 != 0 && waterAllowed() || supported and 2 != 0 && lavaAllowed()
        val bob = pulse.step(usable)
        if (!usable) return@handler
        if (bob) {
            val changed = if (pausePulse) Vec3(0.0, event.movement.y, 0.0) else event.movement
            p.deltaMovement = Vec3(if (alias) changed.x else p.deltaMovement.x, 0.08,
                if (alias) changed.z else p.deltaMovement.z)
            event.movement = if (alias) Vec3(changed.x, 0.08, changed.z) else changed
        } else if (p.onGround()) {
            val input = p.input.getMoveVector()
            val moving = p.zza != 0f || p.xxa != 0f
            val strafe = MotionPolicy.horizontal(p.yRot, input.y.sign.toInt(), input.x.sign.toInt(), if (moving) 5.746 else 0.0)
            event.movement = Vec3(strafe.x, event.movement.y, strafe.z)
            if (alias) p.deltaMovement = Vec3(strafe.x, p.deltaMovement.y, strafe.z)
        }
    }

    private val packets = handler<PacketEvent> { event ->
        val packet = event.packet as? ServerboundMovePlayerPacket ?: return@handler
        if (!pulsing || !mc.isSameThread || event.origin != TransferOrigin.OUTGOING || !event.original) return@handler
        val p = mc.player ?: return@handler
        if (p.isInWater && !waterAllowed() || p.isInLava && !lavaAllowed()) return@handler
        val support = supportedLiquids()
        val usable = support and 1 != 0 && waterAllowed() || support and 2 != 0 && lavaAllowed()
        if (p.isInWater || p.isInLava || p.fallDistance > 3.0 || !usable) return@handler
        packet.onGround = false
        if (p.onGround() && packet.hasPosition()) packet.y = packet.getY(0.0) - (0.02 + 0.0001 * pulse.count)
    }

    private fun supportedLiquids(): Int {
        val p = mc.player ?: return 0
        val level = mc.level ?: return 0
        val bounds = p.vehicle?.let { p.boundingBox.minmax(it.boundingBox) } ?: p.boundingBox
        return level.getBlockStates(bounds.move(0.0, -0.01, 0.0)).use { states ->
            val it = states.iterator()
            var mask = 0
            var inspected = 0
            while (it.hasNext()) {
                if (++inspected > 4096) return@use 0
                val state = it.next()
                if (state.`is`(Blocks.WATER) || state.fluidState.type == Fluids.WATER) mask = mask or 1
                else if (state.`is`(Blocks.LAVA)) mask = mask or 2
                else if (!state.isAir) return@use 0
            }
            mask
        }
    }
}
