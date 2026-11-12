package dev.donut.addon.expansion.search.media

import dev.donut.addon.compat.DonutEventListener

import dev.donut.addon.expansion.search.SearchFeatures
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.network.chat.Component

/** Compact status panel for the current shard and roster evidence. */
object DonutMediaRosterOverlay : DonutEventListener {
    @Suppress("unused")
    private val render = handler<OverlayRenderEvent> { event ->
        val module = SearchFeatures.MEDIA_ROSTER
        if (!module.isActive || !module.statusPanelEnabled()) return@handler
        val lines = module.panelLines().take(10)
        val scale = module.panelScale().toFloat().coerceIn(0.5f, 3f)
        val padding = 6
        val width = (lines.maxOfOrNull { mc.font.width(it) } ?: 0) + padding * 2
        val height = (mc.font.lineHeight + 3) * lines.size + padding * 2
        val x = (mc.window.guiScaledWidth - width * scale - 8).coerceAtLeast(4f)
        val context = event.context
        context.pose().pushMatrix()
        context.pose().translate(x, 8f)
        context.pose().scale(scale)
        val alpha = module.panelOpacity().coerceIn(0, 255)
        context.fill(0, 0, width, height, (alpha shl 24))
        lines.forEachIndexed { index, line ->
            context.text(mc.font, Component.literal(line), padding, padding + index * (mc.font.lineHeight + 3),
                if (index == 0) 0xffffc857.toInt() else 0xffffffff.toInt(), true)
        }
        context.pose().popMatrix()
    }
}
