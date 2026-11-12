package dev.donut.addon.modules

import dev.donut.addon.AddonCategories
import dev.donut.addon.NoJumpDelayInputState
import dev.donut.addon.expansion.movement.MotionPolicy
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.minecraft.client.player.LocalPlayer
import java.util.concurrent.ThreadLocalRandom

object ModuleDonutNoJumpDelay : ClientModule("DonutNoJumpDelay", AddonCategories.UTILITY) {
    private enum class Method(override val tag: String) : Tagged { INPUT("InputRelease"), COUNTER("Cooldown") }
    private val method by enumChoice("Method", Method.INPUT)
    private val cooldown by int("Cooldown", 0, 0..9)
    private val variance by int("Variance", 0, 0..4)
    private val input = NoJumpDelayInputState()
    private var owner: LocalPlayer? = null
    init { literalDescription { "Release held jump in air, or rearm the local jump cooldown with optional variance." } }

    @JvmStatic
    fun rearmCooldown(vanilla: Int): Int {
        if (!running || method != Method.COUNTER) return vanilla
        return MotionPolicy.jumpCooldown(vanilla, cooldown, variance,
            if (variance == 0) 0 else ThreadLocalRandom.current().nextInt(variance + 1))
    }

    private fun reset() {
        input.filter(false, false, false, false)
        owner = null
    }
    override fun onEnabled() = reset()
    override fun onDisabled() = reset()

    @Suppress("unused")
    private val movement = handler<MovementInputEvent> { event ->
        if (method != Method.INPUT) return@handler
        val current = mc.player ?: return@handler
        if (owner !== current) {
            reset()
            owner = current
        }
        // KeyboardInput uses this event to build both the local movement input and the
        // input sent to the server, so the release is visible to both sides.
        event.jump = input.filter(true, event.jump, current.onGround(),
            current.isInWater || current.isInLava || current.onClimbable() || current.isPassenger ||
                current.isFallFlying || current.abilities.instabuild || current.abilities.flying)
    }

    @Suppress("unused")
    private val worldHandler = handler<WorldChangeEvent> { reset() }
}
