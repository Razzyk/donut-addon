package dev.donut.addon.modules

import dev.donut.addon.AddonCategories
import dev.donut.addon.NoJumpDelayInputState
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.minecraft.client.player.LocalPlayer

object ModuleDonutNoJumpDelay : ClientModule("DonutNoJumpDelay", AddonCategories.UTILITY) {
    private val input = NoJumpDelayInputState()
    private var owner: LocalPlayer? = null
    init { literalDescription { "Release held jump while airborne so it can jump again on landing." } }

    private fun reset() {
        input.filter(false, false, false, false)
        owner = null
    }
    override fun onEnabled() = reset()
    override fun onDisabled() = reset()

    @Suppress("unused")
    private val movement = handler<MovementInputEvent> { event ->
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
