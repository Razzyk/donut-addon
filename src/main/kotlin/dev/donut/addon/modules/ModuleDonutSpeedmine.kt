package dev.donut.addon.modules

import dev.donut.addon.AddonCategories
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.ccbluex.liquidbounce.utils.client.mc

object ModuleDonutSpeedmine : ClientModule("DonutSpeedmine", AddonCategories.UTILITY) {
    private val releaseState = SpeedmineReleaseState()
    private var observedPlayer: LocalPlayer? = null
    private var observedWorld: ClientLevel? = null

    init { literalDescription { "Restart held mining immediately after a block breaks." } }

    private fun resetForSessionChange() {
        val player = mc.player
        val world = mc.level
        if (observedPlayer !== player || observedWorld !== world) {
            releaseState.reset()
            observedPlayer = player
            observedWorld = world
        }
    }

    /** Clear only vanilla's post-break delay and schedule one actual input release. */
    @JvmStatic
    fun onBlockCompleted(attackHeld: Boolean): Boolean {
        resetForSessionChange()
        return releaseState.onBlockCompleted(enabled, attackHeld)
    }

    /** Called at Minecraft's normal held-attack continuation point. */
    @JvmStatic
    fun filterHeldAttack(attackHeld: Boolean): Boolean {
        resetForSessionChange()
        return releaseState.filterAttack(enabled, attackHeld)
    }

    @JvmStatic
    fun shouldRestart(attackHeld: Boolean): Boolean {
        resetForSessionChange()
        return releaseState.shouldRestart(enabled, attackHeld)
    }

    @JvmStatic
    fun onRestartStarted() = releaseState.restartStarted()

    override fun onDisabled() {
        releaseState.reset()
        observedPlayer = null
        observedWorld = null
    }

    @Suppress("unused")
    private val worldChanged = handler<WorldChangeEvent> {
        releaseState.reset()
        observedPlayer = mc.player
        observedWorld = it.world
    }
}
