package dev.donut.addon.mv

import dev.donut.addon.expansion.render.BlockClusterMesh
import dev.donut.addon.mv.compat.renderer.ShapeMode
import dev.donut.addon.mv.compat.utils.render.color.Color
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.drawCustomMesh
import net.ccbluex.liquidbounce.render.drawLine
import net.ccbluex.liquidbounce.render.drawLinesWithWidth
import net.ccbluex.liquidbounce.render.addVertex
import net.ccbluex.liquidbounce.render.setColor
import net.ccbluex.liquidbounce.render.engine.type.Vec3f
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** Queues geometry into LiquidBounce's event-owned batches, retaining through-wall ESP. */
class WorldRenderer(private val event: WorldRenderEvent) {
    @Suppress("UNUSED_PARAMETER")
    fun box(box: AABB, sides: Color, lines: Color, mode: ShapeMode, exclude: Int) {
        val localBox = box.move(-box.minX, -box.minY, -box.minZ)
        event.environment.withPositionRelativeToCamera(box.minX, box.minY, box.minZ) {
            drawBox(localBox, if (mode.sides()) Color4b(sides.getPacked()) else null,
                if (mode.lines()) Color4b(lines.getPacked()) else null)
        }
    }
    fun box(pos: BlockPos, sides: Color, lines: Color, mode: ShapeMode, exclude: Int) =
        box(AABB(pos), sides, lines, mode, exclude)
    fun box(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double,
            sides: Color, lines: Color, mode: ShapeMode, exclude: Int) =
        box(AABB(x1, y1, z1, x2, y2, z2), sides, lines, mode, exclude)
    /** Draws a box with LiquidBounce's width-aware outline pass and optional glow halo. */
    fun styledBox(box: AABB, sides: Color, lines: Color, lineWidth: Float, glow: Boolean) {
        val localBox = box.move(-box.minX, -box.minY, -box.minZ)
        event.environment.withPositionRelativeToCamera(box.minX, box.minY, box.minZ) {
            if (sides.a > 0) drawBox(localBox, Color4b(sides.getPacked()), null)
            if (lines.a <= 0 || lineWidth <= 0.0f) return@withPositionRelativeToCamera
            val edges = boxEdges(localBox)
            if (glow) drawLinesWithWidth(scaleAlpha(lines.getPacked(), 0.18f), lineWidth * 3.0f, *edges)
            drawLinesWithWidth(lines.getPacked(), lineWidth, *edges)
        }
    }
    /** Draws one merged voxel cluster. Mesh vertices stay local until the origin is camera-relative. */
    fun styledCluster(
        mesh: BlockClusterMesh.Mesh,
        sides: Color,
        lines: Color,
        lineWidth: Float,
        glow: Boolean,
        scale: Float = 1.0f,
    ) {
        if (mesh.isEmpty()) return
        val safeScale = scale.coerceAtLeast(0.0f)
        event.environment.withPositionRelativeToCamera(
            mesh.originX.toDouble(), mesh.originY.toDouble(), mesh.originZ.toDouble()
        ) {
            if (sides.a > 0 && mesh.faces.isNotEmpty()) {
                val fill = Color4b(sides.getPacked())
                drawCustomMesh(ClientRenderPipelines.quads(noDepthTest = true)) { pose ->
                    for (face in mesh.faces) {
                        addVertex(pose, scaled(face.a, mesh, safeScale)).setColor(fill)
                        addVertex(pose, scaled(face.b, mesh, safeScale)).setColor(fill)
                        addVertex(pose, scaled(face.c, mesh, safeScale)).setColor(fill)
                        addVertex(pose, scaled(face.d, mesh, safeScale)).setColor(fill)
                    }
                }
            }
            if (lines.a <= 0 || lineWidth <= 0.0f || mesh.edges.isEmpty()) return@withPositionRelativeToCamera
            val edges = Array(mesh.edges.size * 2) { index ->
                val edge = mesh.edges[index / 2]
                scaled(if (index % 2 == 0) edge.a else edge.b, mesh, safeScale)
            }
            if (glow) drawLinesWithWidth(scaleAlpha(lines.getPacked(), 0.18f), lineWidth * 3.0f, *edges)
            drawLinesWithWidth(lines.getPacked(), lineWidth, *edges)
        }
    }
    fun line(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double, color: Color) {
        event.environment.withPositionRelativeToCamera(x1, y1, z1) {
            drawLine(Vec3.ZERO, Vec3(x2 - x1, y2 - y1, z2 - z1), color.getPacked())
        }
    }

    private fun boxEdges(box: AABB): Array<Vec3f> {
        fun point(x: Double, y: Double, z: Double) = Vec3f(x.toFloat(), y.toFloat(), z.toFloat())
        val corners = arrayOf(
            point(box.minX, box.minY, box.minZ), point(box.maxX, box.minY, box.minZ),
            point(box.maxX, box.maxY, box.minZ), point(box.minX, box.maxY, box.minZ),
            point(box.minX, box.minY, box.maxZ), point(box.maxX, box.minY, box.maxZ),
            point(box.maxX, box.maxY, box.maxZ), point(box.minX, box.maxY, box.maxZ),
        )
        val pairs = intArrayOf(0, 1, 1, 2, 2, 3, 3, 0, 4, 5, 5, 6, 6, 7, 7, 4, 0, 4, 1, 5, 2, 6, 3, 7)
        return Array(pairs.size) { corners[pairs[it]] }
    }

    private fun scaled(vertex: BlockClusterMesh.Vertex, mesh: BlockClusterMesh.Mesh, scale: Float): Vec3f {
        val s = scale.toDouble()
        return Vec3f(
            (mesh.centerX + (vertex.x - mesh.centerX) * s).toFloat(),
            (mesh.centerY + (vertex.y - mesh.centerY) * s).toFloat(),
            (mesh.centerZ + (vertex.z - mesh.centerZ) * s).toFloat(),
        )
    }

    private fun scaleAlpha(argb: Int, scale: Float): Int {
        val alpha = ((argb ushr 24) * scale).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (argb and 0x00ffffff)
    }
}
