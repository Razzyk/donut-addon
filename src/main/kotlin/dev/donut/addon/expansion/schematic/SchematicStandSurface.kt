package dev.donut.addon.expansion.schematic

import net.minecraft.core.BlockPos
import net.minecraft.tags.FluidTags
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB

/** Native collision-top and body-clearance queries for safe stand positions. */
object SchematicStandSurface {
    /** Returns the real partial-collision top at the feet cell or the supporting cell below. */
    @JvmStatic
    fun surfaceY(world: Level, feet: BuildCellPos): Double? {
        val pos = BlockPos(feet.x, feet.y, feet.z)
        val below = pos.below()
        if (!world.hasChunkAt(pos)) return null
        val own = topUnderFoot(world, pos)
        val surface = if (!own.isNaN()) {
            if (own > 0.999) return null
            feet.y + own
        } else {
            val fluid = world.getFluidState(pos)
            if (fluid.`is`(FluidTags.LAVA) || world.getBlockState(pos).`is`(Blocks.BUBBLE_COLUMN)) return null
            if (!world.hasChunkAt(below)) return null
            val ground = topUnderFoot(world, below)
            if (ground.isNaN() || ground <= 0.999) return null
            below.y + ground
        }
        return surface
    }

    /** Verifies source-width body clearance over the resolved native surface. */
    @JvmStatic
    fun canStandAt(world: Level, player: Player, feet: BuildCellPos): Boolean {
        val pos = BlockPos(feet.x, feet.y, feet.z)
        val surface = surfaceY(world, feet) ?: return false
        if (!world.hasChunkAt(pos.above())) return false
        val feetFluid = world.getFluidState(pos)
        if (feetFluid.`is`(FluidTags.WATER) && feetFluid.getHeight(world, pos) > 0.4F) return false
        if (feetFluid.`is`(FluidTags.LAVA) || world.getBlockState(pos).`is`(Blocks.BUBBLE_COLUMN)) return false
        if (!world.getFluidState(pos.above()).isEmpty) return false
        val x = pos.x.toDouble()
        val y = surface
        val z = pos.z.toDouble()
        val body = AABB(
            x + 0.2, y + 0.01, z + 0.2,
            x + 0.8, y + 1.8, z + 0.8,
        )
        if (!world.noCollision(player, body)) return false
        val contact = AABB(x + 0.2, y - 0.05, z + 0.2, x + 0.8, y - 0.001, z + 0.8)
        return !world.noCollision(player, contact)
    }

    private fun topUnderFoot(world: Level, pos: BlockPos): Double {
        val shape = world.getBlockState(pos).getCollisionShape(world, pos)
        if (shape.isEmpty) return Double.NaN
        var top = Double.NaN
        for (box in shape.toAabbs()) {
            if (box.maxX <= 0.2 || box.minX >= 0.8 || box.maxZ <= 0.2 || box.minZ >= 0.8) continue
            top = if (top.isNaN()) box.maxY else maxOf(top, box.maxY)
        }
        return top
    }
}
