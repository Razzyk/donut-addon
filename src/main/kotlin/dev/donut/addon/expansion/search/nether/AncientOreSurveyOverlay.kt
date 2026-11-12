package dev.donut.addon.expansion.search.nether

import dev.donut.addon.compat.DonutEventListener

import dev.donut.addon.expansion.search.SearchFeatures
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.WorldToScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3

object AncientOreSurveyOverlay : DonutEventListener {
    @Suppress("unused")
    private val render = handler<OverlayRenderEvent> { event ->
        val module = SearchFeatures.ANCIENT_ORE_SURVEY
        if (!module.shouldShowProbeProgress() || mc.player == null) return@handler
        module.overlayLabels().forEach { label ->
            val x = (label.chunkX shl 4) + 8.0
            val y = (label.sectionY shl 4) + 8.7
            val z = (label.chunkZ shl 4) + 8.0
            val screen = WorldToScreen.calculateScreenPos(Vec3(x, y, z)) ?: return@forEach
            if (screen.x < 0f || screen.y < 0f || screen.x > mc.window.guiScaledWidth || screen.y > mc.window.guiScaledHeight) return@forEach
            val text = "Ore probes ${label.sent}/${label.total}"
            event.context.text(mc.font, Component.literal(text), screen.x.toInt(), screen.y.toInt(),
                if (label.thresholdReached()) 0xff77e0a0.toInt() else 0xffffc857.toInt(), true)
        }
    }
}
