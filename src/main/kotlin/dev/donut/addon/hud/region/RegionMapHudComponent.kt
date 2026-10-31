package dev.donut.addon.hud.region

import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.ValueType
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.integration.theme.component.components.NativeHudComponent
import net.ccbluex.liquidbounce.render.drawQuadXYWH
import net.ccbluex.liquidbounce.render.engine.type.BoundingBox2f
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.render.Alignment
import net.minecraft.world.level.Level
import net.minecraft.world.level.dimension.BuiltinDimensionTypes
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object RegionMapHudComponent : NativeHudComponent(
    name = "RegionMap",
    enabled = false,
    alignment = Alignment(
        horizontalAlignment = Alignment.ScreenAxisX.LEFT,
        horizontalOffset = 8,
        verticalAlignment = Alignment.ScreenAxisY.TOP,
        verticalOffset = 8,
    ),
    description = "Shows DonutSMP's shard map. Configure and position it in HUD settings.",
) {

    private object General : ValueGroup("Map") {
        val cellSize by double("CellSize", 4.0, 1.0, 12.0, "px")
        val showKey by boolean("ShowKey", true)
        val textColor by color("TextColor", Color4b.WHITE)
        val textShadow by boolean("TextShadow", true)
        val shardBorders by boolean("ShardBorders", true)
        val shardBorderColor by color("ShardBorderColor", Color4b(0, 0, 0, 90))
    }

    private object Numbers : ValueGroup("Shard Numbers") {
        val show by boolean("Show", true)
        val scale by double("Scale", 0.6, 0.1, 2.0)
        val color by color("Color", Color4b.WHITE)
    }

    private object Marker : ValueGroup("Marker") {
        val show by boolean("Show", true)
        val directionColor by color("DirectionColor", Color4b(170, 0, 0))
        val pivotColor by color("PivotColor", Color4b(255, 70, 70))
        val length by double("Length", 8.0, 1.0, 30.0, "px")
        val thickness by double("Thickness", 1.5, 1.0, 5.0, "px")
        val pivotSize by double("PivotSize", 3.0, 1.0, 12.0, "px")
        val outline by boolean("Outline", true)
        val outlineColor by color("OutlineColor", Color4b.BLACK)
    }

    private object RegionColors : ValueGroup("Region Colors") {
        val values = linkedMapOf(
            "NA West" to color("NAWest", Color4b(68, 114, 196)),
            "NA East" to color("NAEast", Color4b(91, 155, 213)),
            "Oceania" to color("Oceania", Color4b(237, 125, 49)),
            "Asia" to color("Asia", Color4b(255, 192, 0)),
            "EU West" to color("EUWest", Color4b(56, 118, 29)),
            "EU Central" to color("EUCentral", Color4b(146, 208, 80)),
            "Europe" to color("Europe", Color4b(218, 112, 214)),
        )
    }

    private val regionShards = RegionMapData.shards
    private val regionColors = RegionColors.values
    private val lineHeight: Float
        get() = runCatching { mc.font.lineHeight.toFloat() }.getOrDefault(FALLBACK_LINE_HEIGHT)
    private val maxRegionTextWidth: Float
        get() = RegionColors.values.keys.maxOfOrNull(::textWidth) ?: 0f

    override val guiScaledWidth: Float
        get() = max(mapSize, if (General.showKey) maxRegionTextWidth + lineHeight + LEGEND_TEXT_GAP else 0f)

    private val mapSize: Float
        get() = RegionMapData.GRID_SIZE * General.cellSize.toFloat()

    override val guiScaledHeight: Float
        get() = mapSize + if (General.showKey) {
            LEGEND_MAP_GAP + RegionColors.values.size * (lineHeight + KEY_ROW_GAP) - KEY_ROW_GAP
        } else {
            0f
        }

    init {
        tree(General)
        tree(Numbers)
        tree(Marker)
        tree(RegionColors)
        registerComponentListen(this)

        // HUD settings and the Render module share a single on/off state, including while the
        // HUD parent is disabled or before a world has loaded.
        enabledValue.onChanged { state ->
            if (ModuleRegionMap.enabled != state) ModuleRegionMap.enabled = state
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val cell = General.cellSize.toFloat()
        val size = mapSize
        val bounds = getGuiScaledBounds(guiScaledWidth, guiScaledHeight)
        val x = bounds.xMin
        val y = bounds.yMin
        val context = event.context
        val occupied = RegionMapData.cells

        for (row in 0 until RegionMapData.GRID_SIZE) {
            for (column in 0 until RegionMapData.GRID_SIZE) {
                val shard = occupied[row][column] ?: continue
                context.drawQuadXYWH(
                    x + column * cell,
                    y + row * cell,
                    cell,
                    cell,
                    regionColors.getValue(shard.region).get(),
                )
            }
        }

        if (General.shardBorders) {
            drawShardBorders(context, x, y, cell, shardLevel = true, General.shardBorderColor)
        }
        drawShardBorders(context, x, y, cell, shardLevel = false, Color4b(0, 0, 0, 200))
        if (General.shardBorders) {
            context.drawQuadXYWH(x, y, size, 1f, Color4b(0, 0, 0, 200))
            context.drawQuadXYWH(x, y + size - 1f, size, 1f, Color4b(0, 0, 0, 200))
            context.drawQuadXYWH(x, y, 1f, size, Color4b(0, 0, 0, 200))
            context.drawQuadXYWH(x + size - 1f, y, 1f, size, Color4b(0, 0, 0, 200))
        }

        if (Numbers.show) drawNumbers(event, x, y, cell)
        if (General.showKey) drawKey(event, x, y + size + LEGEND_MAP_GAP)
        if (Marker.show) drawPlayerMarker(event, bounds, size)
    }

    private fun drawShardBorders(
        context: net.minecraft.client.gui.GuiGraphicsExtractor,
        x: Float,
        y: Float,
        cell: Float,
        shardLevel: Boolean,
        color: Color4b,
    ) {
        for (row in 0 until RegionMapData.GRID_SIZE) {
            for (column in 0 until RegionMapData.GRID_SIZE) {
                val here = RegionMapData.cells[row][column]
                if (column < RegionMapData.GRID_SIZE - 1) {
                    val right = RegionMapData.cells[row][column + 1]
                    val different = if (shardLevel) {
                        here != null && right != null && here.region == right.region && here.number != right.number
                    } else {
                        here?.region != right?.region
                    }
                    if (different) context.drawQuadXYWH(x + (column + 1) * cell - 0.5f, y + row * cell, 1f, cell, color)
                }
                if (row < RegionMapData.GRID_SIZE - 1) {
                    val below = RegionMapData.cells[row + 1][column]
                    val different = if (shardLevel) {
                        here != null && below != null && here.region == below.region && here.number != below.number
                    } else {
                        here?.region != below?.region
                    }
                    if (different) context.drawQuadXYWH(x + column * cell, y + (row + 1) * cell - 0.5f, cell, 1f, color)
                }
            }
        }
    }

    private fun drawNumbers(event: OverlayRenderEvent, x: Float, y: Float, cell: Float) {
        val context = event.context
        val font = mc.font
        val maxScale = Numbers.scale.toFloat()
        for (shard in regionShards) {
            val text = shard.number.toString()
            val width = shard.width * cell
            val height = shard.height * cell
            val scale = min(maxScale, min(width / max(1f, textWidth(text)), height / font.lineHeight))
            if (scale < 0.08f) continue
            val centerX = x + (shard.column + shard.width * 0.5f) * cell
            val centerY = y + (shard.row + shard.height * 0.5f) * cell
            context.pose().pushMatrix()
            context.pose().translate(centerX, centerY)
            context.pose().scale(scale, scale)
            context.text(
                font,
                text,
                (-textWidth(text) / 2f).roundToInt(),
                (-font.lineHeight / 2f).roundToInt(),
                Numbers.color.argb,
                General.textShadow,
            )
            context.pose().popMatrix()
        }
    }

    private fun drawKey(event: OverlayRenderEvent, x: Float, startY: Float) {
        val context = event.context
        var y = startY
        for ((region, colorValue) in regionColors) {
            context.drawQuadXYWH(x, y, lineHeight, lineHeight, colorValue.get())
            context.text(mc.font, region, (x + lineHeight + LEGEND_TEXT_GAP).roundToInt(), y.roundToInt(), General.textColor.argb, General.textShadow)
            y += lineHeight + KEY_ROW_GAP
        }
    }

    private fun drawPlayerMarker(event: OverlayRenderEvent, bounds: BoundingBox2f, mapSize: Float) {
        val level = mc.level ?: return
        val player = mc.player ?: return
        // Servers can give overworld shards their own dimension keys.
        if (level.dimension() != Level.OVERWORLD &&
            !level.dimensionTypeRegistration().`is`(BuiltinDimensionTypes.OVERWORLD) &&
            !level.dimensionTypeRegistration().`is`(BuiltinDimensionTypes.OVERWORLD_CAVES)) return

        val (fractionX, fractionZ) = RegionMapData.projectWorldPosition(player.x, player.z, mapSize.toDouble())
        val markerX = bounds.xMin + fractionX.toFloat()
        val markerY = bounds.yMin + fractionZ.toFloat()
        val yaw = Math.toRadians(player.yRot.takeIf { it.isFinite() }?.toDouble() ?: 0.0).toFloat()
        val length = Marker.length.toFloat()
        val thickness = Marker.thickness.toFloat()
        val pivot = max(Marker.pivotSize.toFloat(), thickness)
        val context = event.context
        // Keep the marker above the map and shard labels in the queued GUI renderer.
        context.nextStratum()
        context.pose().pushMatrix()
        try {
            context.pose().translate(markerX, markerY)
            context.pose().rotate(yaw)
            // One continuous strip avoids the old chain of overlapping subpixel squares.
            if (Marker.outline) {
                context.drawQuadXYWH(-thickness / 2f - 1f, -1f, thickness + 2f,
                    length + thickness / 2f + 2f, Marker.outlineColor)
            }
            context.drawQuadXYWH(-thickness / 2f, 0f, thickness, length + thickness / 2f,
                Marker.directionColor)
        } finally {
            context.pose().popMatrix()
        }
        if (Marker.outline) {
            context.drawQuadXYWH(markerX - pivot / 2f - 1f, markerY - pivot / 2f - 1f,
                pivot + 2f, pivot + 2f, Marker.outlineColor)
        }
        context.drawQuadXYWH(markerX - pivot / 2f, markerY - pivot / 2f, pivot, pivot, Marker.pivotColor)
    }

    private fun textWidth(text: String): Float = runCatching {
        mc.font.width(text).toFloat()
    }.getOrDefault(text.length * FALLBACK_CHAR_WIDTH)

    private const val LEGEND_MAP_GAP = 4f
    private const val LEGEND_TEXT_GAP = 4f
    private const val KEY_ROW_GAP = 3f
    private const val FALLBACK_LINE_HEIGHT = 9f
    private const val FALLBACK_CHAR_WIDTH = 6f
}

private fun ValueGroup.double(name: String, default: Double, minimum: Double, sliderMaximum: Double, suffix: String = "") =
    value(
        RangedValue(
            name,
            defaultValue = default,
            range = minimum..sliderMaximum,
            suffix = suffix,
            valueType = ValueType.FLOAT,
        ).onChange { next -> if (next.isFinite()) max(minimum, next) else default }
    )
