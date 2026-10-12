package dev.donut.addon.mv

import dev.donut.addon.mv.compat.renderer.ShapeMode
import dev.donut.addon.mv.compat.utils.render.color.Color
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.drawLine
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** Queues geometry into LiquidBounce's event-owned batches, retaining through-wall ESP. */
class WorldRenderer(private val event: WorldRenderEvent) {
    @Suppress("UNUSED_PARAMETER")
    fun box(box: AABB, sides: Color, lines: Color, mode: ShapeMode, exclude: Int) {
        event.environment.withPositionRelativeToCamera {
            drawBox(box, if (mode.sides()) Color4b(sides.getPacked()) else null,
                if (mode.lines()) Color4b(lines.getPacked()) else null)
        }
    }
    fun box(pos: BlockPos, sides: Color, lines: Color, mode: ShapeMode, exclude: Int) =
        box(AABB(pos), sides, lines, mode, exclude)
    fun box(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double,
            sides: Color, lines: Color, mode: ShapeMode, exclude: Int) =
        box(AABB(x1, y1, z1, x2, y2, z2), sides, lines, mode, exclude)
    fun line(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, color: Color) {
        event.environment.withPositionRelativeToCamera {
            drawLine(Vec3(x1, y1, z1), Vec3(x2, y2, z2), color.getPacked())
        }
    }
}
