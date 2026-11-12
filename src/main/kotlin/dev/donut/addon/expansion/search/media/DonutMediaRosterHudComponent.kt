package dev.donut.addon.expansion.search.media

import dev.donut.addon.expansion.search.SearchFeatures
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.integration.theme.component.components.NativeHudComponent
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.Alignment
import net.minecraft.network.chat.Component

/** Positionable media roster panel in LiquidBounce's native HUD editor. */
object DonutMediaRosterHudComponent : NativeHudComponent(
    "DonutMediaRoster",
    true,
    Alignment(Alignment.ScreenAxisX.RIGHT, 8, Alignment.ScreenAxisY.TOP, 36),
    description = "Shows media roster evidence and the current Donut shard. Configure its position in HUD settings."
) {
    private val padding by int("Padding", 6, 0..16)

    override val guiScaledWidth: Float
        get() = panelMetrics().first.toFloat() * safeScale
    override val guiScaledHeight: Float
        get() = panelMetrics().second.toFloat() * safeScale

    init { registerComponentListen(this) }

    @Suppress("unused")
    private val render = handler<OverlayRenderEvent> { event ->
        val module = SearchFeatures.MEDIA_ROSTER
        if (!module.isActive || !module.statusPanelEnabled()) return@handler
        val lines = module.panelLines().take(10)
        val (width, height) = panelMetrics(lines)
        val bounds = getGuiScaledBounds(width * safeScale, height * safeScale)
        val context = event.context
        context.pose().pushMatrix()
        context.pose().translate(bounds.xMin, bounds.yMin)
        context.pose().scale(safeScale)
        context.fill(0, 0, width, height, (module.panelOpacity().coerceIn(0, 255) shl 24))
        lines.forEachIndexed { index, line ->
            context.text(mc.font, Component.literal(line), safePadding, safePadding + index * (mc.font.lineHeight + 3),
                if (index == 0) 0xffffc857.toInt() else 0xffffffff.toInt(), true)
        }
        context.pose().popMatrix()
    }

    private fun panelMetrics(lines: List<String> = SearchFeatures.MEDIA_ROSTER.panelLines().take(10)): Pair<Int, Int> {
        val width = (lines.maxOfOrNull { mc.font.width(it) } ?: 0) + safePadding * 2
        val height = (mc.font.lineHeight + 3) * lines.size + safePadding * 2
        return width to height
    }

    private val safePadding get() = padding.coerceIn(0, 16)
    private val safeScale get() = SearchFeatures.MEDIA_ROSTER.panelScale().toFloat().coerceIn(0.5f, 3f)
}
