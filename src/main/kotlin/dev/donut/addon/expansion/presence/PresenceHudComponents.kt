package dev.donut.addon.expansion.presence

import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.integration.theme.component.components.NativeHudComponent
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.Alignment
import net.minecraft.network.chat.Component

/** Movable roster panel; enable and position it in LiquidBounce's HUD editor. */
object StaffRosterHudComponent : NativeHudComponent(
    "DonutStaffRoster",
    true,
    Alignment(Alignment.ScreenAxisX.LEFT, 8, Alignment.ScreenAxisY.TOP, 36),
    description = "Shows tracked Donut staff, current visibility, and recent nearby sightings."
) {
    private val padding by int("Padding", 5, 0..16)
    private val scale by float("Scale", 1f, 0.5f..2f)
    private val background by color("Background", Color4b(0, 0, 0, 150))
    private val foreground by color("Text", Color4b.WHITE)

    override val guiScaledWidth: Float
        get() = displayRows().takeIf { it.first.isNotEmpty() }?.let { (rows, more) ->
            val texts = rows.map(::line) + if (more > 0) listOf("and $more more") else emptyList()
            maxOf(120, mc.font.width("Donut Staff"), texts.maxOfOrNull { mc.font.width(it) } ?: 0).toFloat() * safeScale + safePadding * 2
        } ?: 0f
    override val guiScaledHeight: Float
        get() = displayRows().takeIf { it.first.isNotEmpty() }?.let { (rows, more) ->
            ((mc.font.lineHeight + 2) * (rows.size + 1 + if (more > 0) 1 else 0) + safePadding * 2).toFloat() * safeScale
        } ?: 0f

    init { registerComponentListen(this) }

    @Suppress("unused")
    private val render = handler<OverlayRenderEvent> { event ->
        if (!PresenceFeatures.staffRoster.enabled || !PresenceFeatures.staffRoster.shouldShowHud) return@handler
        val (rows, more) = displayRows()
        if (rows.isEmpty()) return@handler
        val header = "Donut Staff"
        val display = listOf(header) + rows.map(::line) + if (more > 0) listOf("and $more more") else emptyList()
        val rawWidth = maxOf(120, display.maxOfOrNull { mc.font.width(it) } ?: 0) + safePadding * 2
        val rawHeight = (mc.font.lineHeight + 2) * display.size + safePadding * 2
        val bounds = getGuiScaledBounds(rawWidth * safeScale, rawHeight * safeScale)
        val context = event.context
        context.pose().pushMatrix()
        context.pose().translate(bounds.xMin, bounds.yMin)
        context.pose().scale(safeScale)
        context.fill(0, 0, rawWidth, rawHeight, background.argb)
        display.forEachIndexed { index, value ->
            val color = if (index == 0) 0xffffc857.toInt() else {
                val row = rows.getOrNull(index - 1)
                when {
                    row == null -> 0xffaaaaaa.toInt()
                    row.alertLevel > 0f -> 0xffff5555.toInt()
                    row.nearby -> 0xffffaa55.toInt()
                    row.presence == DonutStaffRoster.Presence.HIDDEN -> 0xffc77dff.toInt()
                    row.presence == DonutStaffRoster.Presence.SPECTATOR -> 0xffaaaaaa.toInt()
                    else -> foreground.argb
                }
            }
            context.text(mc.font, Component.literal(value), safePadding, safePadding + index * (mc.font.lineHeight + 2), color, false)
        }
        context.pose().popMatrix()
    }

    private fun line(row: DonutStaffRoster.Row): String = buildString {
        append(row.name).append(" [").append(row.rank).append("]")
        when (row.presence) {
            DonutStaffRoster.Presence.ONLINE -> append(" online")
            DonutStaffRoster.Presence.SPECTATOR -> append(" spectator")
            DonutStaffRoster.Presence.HIDDEN -> append(" hidden")
        }
        if (row.nearby) append(" ").append(row.distance.toInt()).append("m")
        else if (row.watching) append(" watching ").append(DonutStaffRoster.formatAge(row.watchingForMs))
    }

    private fun displayRows(): Pair<List<DonutStaffRoster.Row>, Int> {
        val all = PresenceFeatures.staffRoster.rows()
        return if (all.size > 8) all.take(7) to all.size - 7 else all to 0
    }

    private val safeScale get() = scale.takeIf { it.isFinite() && it > 0f } ?: 1f
    private val safePadding get() = padding.coerceIn(0, 16)
}

/** Stream panel with an elapsed timer and direct channel link text. */
object StaffPulseHudComponent : NativeHudComponent(
    "DonutStaffPulse",
    true,
    Alignment(Alignment.ScreenAxisX.LEFT, 8, Alignment.ScreenAxisY.TOP, 138),
    description = "Shows configured Donut creator channels while they are live."
) {
    private val scale by float("Scale", 1f, 0.5f..2f)
    private val background by color("Background", Color4b(0, 0, 0, 150))

    override val guiScaledWidth: Float
        get() = PresenceFeatures.staffPulse.rows().takeIf { it.isNotEmpty() }?.let { rows ->
            maxOf(140, mc.font.width("Creator Live"), rows.maxOfOrNull { mc.font.width(line(it)) } ?: 0).toFloat() * safeScale + 10
        } ?: 0f
    override val guiScaledHeight: Float
        get() = PresenceFeatures.staffPulse.rows().takeIf { it.isNotEmpty() }?.let { rows ->
            ((mc.font.lineHeight + 3) * (rows.size + 1) + 10).toFloat() * safeScale
        } ?: 0f

    init { registerComponentListen(this) }

    @Suppress("unused")
    private val render = handler<OverlayRenderEvent> { event ->
        if (!PresenceFeatures.staffPulse.enabled || !PresenceFeatures.staffPulse.shouldShowHud) return@handler
        val rows = PresenceFeatures.staffPulse.rows()
        if (rows.isEmpty()) return@handler
        val display = listOf("Creator Live") + rows.map(::line)
        val rawWidth = maxOf(140, display.maxOfOrNull { mc.font.width(it) } ?: 0) + 10
        val rawHeight = (mc.font.lineHeight + 3) * display.size + 10
        val bounds = getGuiScaledBounds(rawWidth * safeScale, rawHeight * safeScale)
        val context = event.context
        context.pose().pushMatrix()
        context.pose().translate(bounds.xMin, bounds.yMin)
        context.pose().scale(safeScale)
        context.fill(0, 0, rawWidth, rawHeight, background.argb)
        display.forEachIndexed { index, value ->
            context.text(mc.font, Component.literal(value), 5, 5 + index * (mc.font.lineHeight + 3), if (index == 0) 0xff70d6a2.toInt() else 0xffffffff.toInt(), false)
        }
        context.pose().popMatrix()
    }

    private fun line(row: DonutStaffPulse.LiveRow): String = "${row.channel} (${elapsed(System.currentTimeMillis() - row.since)}) ${row.title}"
    private fun elapsed(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val minutes = total / 60
        return "${minutes}:${(total % 60).toString().padStart(2, '0')}"
    }
    private val safeScale get() = scale.takeIf { it.isFinite() && it > 0f } ?: 1f
}

/** Compact evidence list for the temporary invisibility and tab signals. */
object PresenceWatchHudComponent : NativeHudComponent(
    "DonutPresenceSignals",
    true,
    Alignment(Alignment.ScreenAxisX.RIGHT, 8, Alignment.ScreenAxisY.TOP, 36),
    description = "Shows short lived presence evidence and critical watch alerts."
) {
    private val scale by float("Scale", 1f, 0.5f..2f)
    private val background by color("Background", Color4b(0, 0, 0, 150))

    override val guiScaledWidth: Float
        get() = PresenceFeatures.presenceWatch.hudRows().takeIf { it.isNotEmpty() }?.let { rows ->
            maxOf(160, mc.font.width("Presence"), rows.maxOfOrNull { mc.font.width("${it.name}: ${it.reason}") } ?: 0).toFloat() * safeScale + 10
        } ?: 0f
    override val guiScaledHeight: Float
        get() = PresenceFeatures.presenceWatch.hudRows().takeIf { it.isNotEmpty() }?.let { rows ->
            ((mc.font.lineHeight + 3) * (rows.size + 1) + 10).toFloat() * safeScale
        } ?: 0f

    init { registerComponentListen(this) }

    @Suppress("unused")
    private val render = handler<OverlayRenderEvent> { event ->
        if (!PresenceFeatures.presenceWatch.enabled || !PresenceFeatures.presenceWatch.shouldShowHud) return@handler
        val rows = PresenceFeatures.presenceWatch.hudRows()
        if (rows.isEmpty()) return@handler
        val display = listOf("Presence") + rows.map { "${it.name}: ${it.reason}" }
        val rawWidth = maxOf(160, display.maxOfOrNull { mc.font.width(it) } ?: 0) + 10
        val rawHeight = (mc.font.lineHeight + 3) * display.size + 10
        val bounds = getGuiScaledBounds(rawWidth * safeScale, rawHeight * safeScale)
        val context = event.context
        context.pose().pushMatrix()
        context.pose().translate(bounds.xMin, bounds.yMin)
        context.pose().scale(safeScale)
        context.fill(0, 0, rawWidth, rawHeight, background.argb)
        display.forEachIndexed { index, value ->
            val isCritical = index > 0 && rows[index - 1].name == "CRITICAL"
            context.text(mc.font, Component.literal(value), 5, 5 + index * (mc.font.lineHeight + 3), if (isCritical) 0xffff5555.toInt() else 0xffffffff.toInt(), false)
        }
        context.pose().popMatrix()
    }

    private val safeScale get() = scale.takeIf { it.isFinite() && it > 0f } ?: 1f
}
