package dev.donut.addon.expansion.building

import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/** Uses the existing native Level fixture without changing its schematic-owned source files. */
class TunnelMiningRayNativeTest {
    @Test
    fun `server rotation finds the visible front face when the requested top face is occluded by the block`() {
        val world = fixtureWorld()
        val player = fixturePlayer(world)
        val target = BlockPos(0, 2, 0)
        put(world, target, Blocks.STONE.defaultBlockState())

        val requestedTop = Vec3(0.5, 3.0, 0.5)
        val rotation = Rotation.lookingAt(requestedTop, player.eyePosition)
        val hit = TunnelMiningRayRules.hit(world, player, rotation, target, 4.5)

        assertEquals(Direction.NORTH, hit?.direction)
        assertEquals(target, hit?.blockPos)
    }

    @Test
    fun `server rotation ray respects an intervening block instead of accepting a hidden target`() {
        val world = fixtureWorld()
        val player = fixturePlayer(world)
        val target = BlockPos(0, 2, 0)
        put(world, target, Blocks.STONE.defaultBlockState())
        put(world, BlockPos(0, 2, -1), Blocks.STONE.defaultBlockState())

        val rotation = Rotation.lookingAt(Vec3(0.5, 3.0, 0.5), player.eyePosition)

        assertNull(TunnelMiningRayRules.hit(world, player, rotation, target, 4.5))
    }

    private fun fixtureWorld(): Level {
        val type = Class.forName("dev.donut.addon.expansion.schematic.PlacementFixtureLevel")
        val constructor = type.getDeclaredConstructor().apply { isAccessible = true }
        return constructor.newInstance() as Level
    }

    private fun fixturePlayer(world: Level): Entity {
        val type = Class.forName("dev.donut.addon.expansion.schematic.PlacementFixturePlayer")
        val constructor = type.declaredConstructors.single().apply { isAccessible = true }
        return constructor.newInstance(world) as Entity
    }

    private fun put(world: Level, pos: BlockPos, state: net.minecraft.world.level.block.state.BlockState) {
        val method = world.javaClass.getDeclaredMethod(
            "put", BlockPos::class.java, net.minecraft.world.level.block.state.BlockState::class.java,
        ).apply { isAccessible = true }
        method.invoke(world, pos, state)
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun prepareNativeData() {
            val context = Class.forName("dev.donut.addon.expansion.schematic.SchematicNativeContextTest")
            context.getDeclaredMethod("prepareNativeData").apply { isAccessible = true }.invoke(null)
        }
    }
}
