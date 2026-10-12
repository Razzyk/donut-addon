package dev.donut.addon.hud.speedometer

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.integration.theme.component.HudComponentManager
import net.ccbluex.liquidbounce.integration.theme.component.components.NativeHudComponent
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.Alignment
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket

/** A movable, configurable speedometer that is available in LiquidBounce's HUD editor. */
object SpeedometerHudComponent : NativeHudComponent(
    "Speedometer",
    false,
    Alignment(
        horizontalAlignment = Alignment.ScreenAxisX.LEFT,
        horizontalOffset = 8,
        verticalAlignment = Alignment.ScreenAxisY.BOTTOM,
        verticalOffset = 20,
    ),
    description = "Shows the player's movement speed.",
) {
    private val unit by enumChoice("Unit", Unit.KILOMETERS_PER_HOUR)
    private val decimals by int("Decimals", 1, 0..3)
    private val template by text("Text", "Speed: {speed} {unit}")
    private val threeDimensional by boolean("ThreeDimensional", false)
    private val textColor by color("TextColor", Color4b.WHITE)
    private val backgroundColor by color("BackgroundColor", Color4b(0, 0, 0, 120))
    private val borderColor by color("BorderColor", Color4b.TRANSPARENT)
    private val shadow by boolean("Shadow", true)
    private val padding by int("Padding", 3, 0..20)
    private val scale by float("Scale", 1.0F, 0.5F..3.0F)

    private val sampler = SpeedSampleTracker()
    private var blocksPerSecond = 0.0
    private var previousReportedWidth = Float.NaN
    @Volatile
    private var resetRequestedByPacket = false

    private val safeScale: Float
        get() = scale.takeIf { it.isFinite() && it > 0f } ?: 1f

    private val safePadding: Int
        get() = padding.coerceIn(0, 20)

    private val safeDecimals: Int
        get() = decimals.coerceIn(0, 3)

    private val fontMetrics
        get() = mc.font

    override val guiScaledWidth: Float
        get() = ((fontMetrics?.width(displayText())?.toFloat() ?: displayText().length * APPROX_CHAR_WIDTH) +
            safePadding * 2).coerceAtLeast(1f) * safeScale

    override val guiScaledHeight: Float
        get() = ((fontMetrics?.lineHeight ?: FALLBACK_LINE_HEIGHT) + safePadding * 2).coerceAtLeast(1) * safeScale

    init {
        registerComponentListen(this)
    }

    override fun onEnabled() {
        resetSampling()
    }

    override fun onDisabled() {
        resetSampling()
    }

    /** Keep a single authoritative sample per client tick, independent of rendered frames. */
    @Suppress("unused")
    private val sampleHandler = handler<GameTickEvent> {
        if (resetRequestedByPacket) {
            resetRequestedByPacket = false
            resetSampling()
        }

        val player = mc.player
        val world = mc.level
        if (player == null || world == null) {
            resetSampling()
        } else {
            blocksPerSecond = sampler.update(
                world = world,
                player = player,
                x = player.x,
                y = player.y,
                z = player.z,
                threeDimensional = threeDimensional,
                tickCount = player.tickCount,
            )

            val currentWidth = guiScaledWidth
            if (currentWidth != previousReportedWidth) {
                previousReportedWidth = currentWidth
                HudComponentManager.updateComponents()
            }
        }
    }

    @Suppress("unused")
    private val teleportHandler = handler<PacketEvent> { event ->
        if (event.origin == TransferOrigin.INCOMING && event.original &&
            event.packet is ClientboundPlayerPositionPacket
        ) {
            resetRequestedByPacket = true
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val player = mc.player
        if (player == null || mc.level == null) {
            return@handler
        }

        val content = displayText()
        val font = fontMetrics ?: return@handler
        val textWidth = font.width(content)
        val textHeight = font.lineHeight
        val drawPadding = safePadding
        val drawScale = safeScale
        val rawWidth = (textWidth + drawPadding * 2).coerceAtLeast(1)
        val rawHeight = (textHeight + drawPadding * 2).coerceAtLeast(1)
        val bounds = getGuiScaledBounds(rawWidth * drawScale, rawHeight * drawScale)

        with(event.context) {
            pose().pushMatrix()
            pose().translate(bounds.xMin, bounds.yMin)
            pose().scale(drawScale)
            drawBox(rawWidth, rawHeight)
            text(font, content, drawPadding, drawPadding, textColor.argb, shadow)
            pose().popMatrix()
        }
    }

    private fun GuiGraphicsExtractor.drawBox(width: Int, height: Int) {
        if (backgroundColor.a > 0) {
            fill(0, 0, width, height, backgroundColor.argb)
        }
        if (borderColor.a > 0) {
            fill(0, 0, width, 1, borderColor.argb)
            fill(0, height - 1, width, height, borderColor.argb)
            fill(0, 1, 1, height - 1, borderColor.argb)
            fill(width - 1, 1, width, height - 1, borderColor.argb)
        }
    }

    private fun displayText(): String {
        val displaySpeed = SpeedometerMath.convert(blocksPerSecond, unit)
        return SpeedometerMath.applyTemplate(
            template,
            SpeedometerMath.format(displaySpeed, safeDecimals),
            SpeedometerMath.unitLabel(unit),
        )
    }

    private fun resetSampling() {
        blocksPerSecond = 0.0
        sampler.reset()
        previousReportedWidth = Float.NaN
        resetRequestedByPacket = false
    }

    enum class Unit(override val tag: String) : net.ccbluex.liquidbounce.config.types.list.Tagged {
        KILOMETERS_PER_HOUR("KilometersPerHour"),
        BLOCKS_PER_SECOND("BlocksPerSecond"),
        MILES_PER_HOUR("MilesPerHour"),
    }

    private const val APPROX_CHAR_WIDTH = 6f
    private const val FALLBACK_LINE_HEIGHT = 9
}
