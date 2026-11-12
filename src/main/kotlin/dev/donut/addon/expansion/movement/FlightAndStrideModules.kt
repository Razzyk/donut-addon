package dev.donut.addon.expansion.movement

import dev.donut.addon.AddonCategories
import dev.donut.addon.mixin.LocalPlayerAccessor
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.EventState
import net.ccbluex.liquidbounce.event.events.*
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.client.Timer
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.phys.Vec3

internal fun travelVector(p: LocalPlayer, speed: Double): MotionPolicy.Horizontal {
    val keys = DirectionalInput(p.input)
    return MotionPolicy.horizontal(p.yRot, (if (keys.forwards) 1 else 0) - (if (keys.backwards) 1 else 0),
        (if (keys.left) 1 else 0) - (if (keys.right) 1 else 0), speed)
}

object DonutStride : ClientModule("DonutStride", AddonCategories.UTILITY, disableOnQuit = true) {
    private enum class Stride(override val tag: String) : Tagged { DIRECT("Vanilla"), HOP("Strafe") }
    private val mode by enumChoice("Mode", Stride.DIRECT).onChanged { state.reset() }
    private val directSpeed by float("VanillaSpeed", 5.6f, 0f..20f)
    private val hopMultiplier by float("StrafeSpeed", 1.6f, 0f..3f)
    private val capped by boolean("SpeedLimit", false)
    private val timer by float("Timer", 1f, .01f..10f)
    private val liquids by boolean("InLiquids", false)
    private val sneaking by boolean("WhenSneaking", false)
    private val groundedOnly by boolean("OnlyOnGround", false)
    private val state = StrideState()
    @Volatile private var correction = false
    private var distance = 0.0
    private var limitEpoch = 0L
    init { literalDescription { "Direct horizontal speed or staged strafe hops with condition and timer controls." } }
    override fun onEnabled() { state.reset(); correction = false; distance = 0.0; limitEpoch = System.nanoTime() }
    override fun onDisabled() { state.reset() }

    private fun eligible(p: LocalPlayer): Boolean = !p.isFallFlying && !p.isSleeping && !p.isPassenger &&
        (sneaking || !p.isShiftKeyDown) && (liquids || !p.isInWater && !p.isInLava) &&
        (mode != Stride.DIRECT || !groundedOnly || p.onGround())

    private val tick = handler<PlayerTickEvent> {
        val p = mc.player ?: return@handler
        if (correction) { state.reset(); correction = false }
        distance = kotlin.math.hypot(p.x - p.xo, p.z - p.zo)
    }
    private val packets = handler<PacketEvent> { event ->
        if (event.origin == TransferOrigin.INCOMING && event.packet is ClientboundPlayerPositionPacket) correction = true
    }
    private val move = handler<PlayerMoveEvent> { event ->
        val p = mc.player ?: return@handler
        if (!eligible(p)) return@handler
        val moving = DirectionalInput(p.input).isMoving
        if (moving && timer != 1f) Timer.requestTimerSpeed(timer, Priority.IMPORTANT_FOR_USAGE_1, this)
        val old = event.movement
        val result: Vec3
        if (mode == Stride.DIRECT) {
            val effect = p.getEffect(MobEffects.SPEED)?.let { 1.0 + .205 * (it.amplifier + 1) } ?: 1.0
            val v = travelVector(p, directSpeed * effect)
            result = Vec3(v.x, old.y, v.z)
        } else {
            val base = .2873 * (p.getEffect(MobEffects.SPEED)?.let { 1 + .2 * (it.amplifier + 1) } ?: 1.0) /
                (p.getEffect(MobEffects.SLOWNESS)?.let { 1 + .2 * (it.amplifier + 1) } ?: 1.0)
            val now = System.nanoTime()
            if (now - limitEpoch > 2_500_000_000L) limitEpoch = now
            val cap = if (!capped) Double.POSITIVE_INFINITY else if (now - limitEpoch > 1_250_000_000L) .44 else .43
            val hop = .40123128 + (p.getEffect(MobEffects.JUMP_BOOST)?.let { .1f * (it.amplifier + 1) } ?: 0f)
            val step = state.advance(moving, p.onGround(), p.verticalCollision,
                !world.noCollision(p.boundingBox.move(0.0, p.deltaMovement.y, 0.0)), distance,
                base, hopMultiplier.toDouble(), hop, old.y, cap)
            val v = travelVector(p, step.horizontal * 20.0)
            result = Vec3(v.x, step.vertical, v.z)
        }
        event.movement = result
        if (old === p.deltaMovement) p.deltaMovement = result
    }
}

object DonutFlightControl : ClientModule("DonutFlightControl", AddonCategories.UTILITY, disableOnQuit = true) {
    private enum class Flight(override val tag: String) : Tagged { ABILITY("Abilities"), MOTION("Velocity") }
    private enum class Dip(override val tag: String) : Tagged { MOTION("Normal"), NETWORK("Packet"), NONE("None") }
    private val mode by enumChoice("Mode", Flight.ABILITY).onChanged { restoreAbilities() }
    private val speed by float("Speed", .1f, 0f..10f)
    private val matchVertical by boolean("VerticalSpeedMatch", false)
    private val noSneak by boolean("NoSneak", false)
    private val dip by enumChoice("AntiKickMode", Dip.NETWORK)
    private val delay by int("AntiKickDelay", 20, 1..200)
    private val offTime by int("AntiKickOffTime", 1, 1..20)
    private val cadence = FlightCadence()
    @Volatile private var tracked: LocalPlayer? = null
    private var allowedFlight = false
    private var wasFlying = false
    private var flyingSpeed = .05f
    @Volatile private var abilityRevision = 0L
    private var networkY: Double? = null
    private var lastYaw = 0f
    private var yawOffset = 0f
    init {
        literalDescription { "Ability or velocity flight with a timed downward-packet or gravity interval." }
        enabledValue.onChanged { state -> if (!state) endFlightSession() }
    }

    private fun track(p: LocalPlayer) {
        if (p === tracked) return
        restoreAbilities()
        tracked = p
        allowedFlight = p.abilities.mayfly
        wasFlying = p.abilities.flying
        flyingSpeed = p.abilities.flyingSpeed
        lastYaw = p.yRot
        networkY = null
        cadence.reset()
    }
    private fun restoreAbilities() {
        abilityRevision++
        tracked?.let { p ->
            if (!p.isSpectator) {
                p.abilities.mayfly = allowedFlight || p.abilities.instabuild
                p.abilities.flying = wasFlying && p.abilities.mayfly
                p.abilities.flyingSpeed = flyingSpeed
            }
        }
        tracked = null
    }
    override fun onEnabled() { mc.player?.let(::track) }
    override fun onDisabled() = endFlightSession()
    internal fun endFlightSession() { restoreAbilities(); networkY = null; cadence.reset() }

    private val tick = handler<PlayerTickEvent> {
        val p = mc.player ?: return@handler
        track(p)
        cadence.tick(delay, offTime)
        if (p.fallDistance >= 3 && p.yRot == lastYaw && p.deltaMovement.length() < .003) {
            yawOffset = if (yawOffset > 0) -1f else 1f
            p.yRot += yawOffset
        }
        lastYaw = p.yRot
    }
    @JvmStatic fun controlsLocalMotion(): Boolean = running && mode == Flight.MOTION
    @JvmStatic fun suppressesCrouch(): Boolean = controlsLocalMotion() && noSneak
    @JvmStatic fun airAcceleration(sprinting: Boolean): Float = speed * if (sprinting) 15f else 10f
    private val post = handler<PlayerPostTickEvent> {
        val p = mc.player ?: return@handler
        track(p)
        if (p.isSpectator) return@handler
        if (mode == Flight.ABILITY) {
            val disabledInterval = dip == Dip.MOTION && cadence.dipping
            p.abilities.flying = !disabledInterval
            p.abilities.mayfly = true
            p.abilities.flyingSpeed = if (disabledInterval) .05f else speed
        } else {
            p.abilities.flying = false
            val verticalInput = if (mc.gui.screen() == null) (if (mc.options.keyJump.isDown) 1 else 0) -
                (if (mc.options.keyShift.isDown) 1 else 0) else 0
            val y = verticalInput * speed * if (matchVertical) 10.0 else 5.0
            p.deltaMovement = Vec3(0.0, y, 0.0)
        }
        if (dip == Dip.NETWORK && cadence.boundary) (p as LocalPlayerAccessor).`donut$setPositionReminder`(20)
    }
    private val networkMotion = handler<PlayerNetworkMovementTickEvent> { event ->
        if (event.state != EventState.PRE || dip != Dip.NETWORK || mc.isLocalServer) return@handler
        val p = mc.player ?: return@handler
        val old = networkY
        val unsupported = world.getBlockStates(p.boundingBox.inflate(.0625).expandTowards(0.0, -.55, 0.0))
            .allMatch { it.isAir }
        if (cadence.dipping && old != null && event.y > old - .0313 && unsupported) event.y = old - .0313
        else networkY = event.y
    }
    private val packets = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING || mode != Flight.ABILITY) return@handler
        val packet = event.packet as? ClientboundPlayerAbilitiesPacket ?: return@handler
        val observedPlayer = mc.player ?: return@handler
        val observedWorld = mc.level ?: return@handler
        val observedConnection = mc.connection ?: return@handler
        // Let vanilla apply the packet until the new player has an owned ability snapshot.
        if (observedPlayer !== tracked) return@handler
        val revision = abilityRevision
        // Preserve server invulnerability/build and walk-speed updates while retaining local flight.
        event.cancelEvent()
        mc.execute {
            if (!running || mode != Flight.ABILITY || revision != abilityRevision ||
                mc.player !== observedPlayer || mc.level !== observedWorld || mc.connection !== observedConnection
            ) return@execute
            observedPlayer.let { p ->
                p.abilities.invulnerable = packet.isInvulnerable
                p.abilities.instabuild = packet.canInstabuild()
                p.abilities.walkingSpeed = packet.walkingSpeed
                allowedFlight = packet.canFly()
                wasFlying = packet.isFlying
                flyingSpeed = packet.flyingSpeed
            }
        }
    }
}
