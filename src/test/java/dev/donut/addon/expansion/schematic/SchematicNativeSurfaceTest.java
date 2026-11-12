package dev.donut.addon.expansion.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchematicNativeSurfaceTest {
    @BeforeAll static void prepareNativeData() throws Exception { SchematicNativeContextTest.prepareNativeData(); }

    @Test void bottomSlabUsesItsRealSurfaceAndRejectsAnOverheadObstruction() {
        var world = new PlacementFixtureLevel();
        var player = new PlacementFixturePlayer(world);
        var feet = new BuildCellPos(0, 0, 0);
        world.put(new BlockPos(0, 0, 0), Blocks.OAK_SLAB.defaultBlockState());
        assertEquals(0.5, SchematicStandSurface.surfaceY(world, feet), 1e-6);
        assertTrue(SchematicStandSurface.canStandAt(world, player, feet));
        world.put(new BlockPos(0, 2, 0), Blocks.STONE.defaultBlockState());
        assertFalse(SchematicStandSurface.canStandAt(world, player, feet));
    }

    @Test void nativeFullSlabStairAndFenceSupportHeightsRemainDistinct() {
        var states = new net.minecraft.world.level.block.state.BlockState[]{
            Blocks.STONE.defaultBlockState(),
            Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP),
            Blocks.OAK_STAIRS.defaultBlockState(),
            Blocks.OAK_FENCE.defaultBlockState(),
        };
        var heights = new double[]{1, 1, 1, 1.5};
        for (int index = 0; index < states.length; index++) {
            var world = new PlacementFixtureLevel();
            var player = new PlacementFixturePlayer(world);
            var feet = new BuildCellPos(0, 1, 0);
            world.put(new BlockPos(0, 0, 0), states[index]);
            assertEquals(heights[index], SchematicStandSurface.surfaceY(world, feet), 1e-6, states[index].toString());
            assertTrue(SchematicStandSurface.canStandAt(world, player, feet), states[index].toString());
        }
    }

    @Test void missingSupportAndAnOccupiedBodyCellAreNotSafeStands() {
        var world = new PlacementFixtureLevel();
        var player = new PlacementFixturePlayer(world);
        var feet = new BuildCellPos(0, 1, 0);
        assertNull(SchematicStandSurface.surfaceY(world, feet));
        assertFalse(SchematicStandSurface.canStandAt(world, player, feet));
        world.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        world.put(new BlockPos(0, 1, 0), Blocks.STONE.defaultBlockState());
        assertFalse(SchematicStandSurface.canStandAt(world, player, feet));
    }

    @Test void anOpenTrapdoorOutsideTheCentralFootprintIsNotASupportingFloor() {
        var world = new PlacementFixtureLevel();
        var player = new PlacementFixturePlayer(world);
        var feet = new BuildCellPos(0, 1, 0);
        world.put(new BlockPos(0, 0, 0), Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(BlockStateProperties.OPEN, true)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        assertNull(SchematicStandSurface.surfaceY(world, feet));
        assertFalse(SchematicStandSurface.canStandAt(world, player, feet));
    }

    @Test void deepFlowingWaterIsUnsafeEvenThoughItIsNotASourceBlock() {
        var world = new PlacementFixtureLevel();
        var player = new PlacementFixturePlayer(world);
        var feet = new BuildCellPos(0, 1, 0);
        world.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        world.put(new BlockPos(0, 1, 0), Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1));
        assertFalse(world.getFluidState(new BlockPos(0, 1, 0)).isSource());
        assertTrue(world.getFluidState(new BlockPos(0, 1, 0)).getHeight(world, new BlockPos(0, 1, 0)) > 0.4f);
        assertFalse(SchematicStandSurface.canStandAt(world, player, feet));
    }

    @Test void flowingLavaIsNeverAcceptedAsAStand() {
        var world = new PlacementFixtureLevel();
        var player = new PlacementFixturePlayer(world);
        var feet = new BuildCellPos(0, 1, 0);
        world.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        world.put(new BlockPos(0, 1, 0), Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 7));
        assertFalse(world.getFluidState(new BlockPos(0, 1, 0)).isSource());
        assertFalse(SchematicStandSurface.canStandAt(world, player, feet));
    }

    @Test void evenShallowFlowingFluidAtHeadHeightIsRejected() {
        var world = new PlacementFixtureLevel();
        var player = new PlacementFixturePlayer(world);
        var feet = new BuildCellPos(0, 1, 0);
        world.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        world.put(new BlockPos(0, 2, 0), Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 7));
        assertFalse(world.getFluidState(new BlockPos(0, 2, 0)).isSource());
        assertFalse(SchematicStandSurface.canStandAt(world, player, feet));
    }

    @Test void anUnavailableNegativeCoordinateChunkCannotSupplyAStand() {
        var world = new PlacementFixtureLevel();
        var player = new PlacementFixturePlayer(world);
        var feet = new BuildCellPos(-1, 1, -1);
        world.put(new BlockPos(-1, 0, -1), Blocks.STONE.defaultBlockState());
        world.unloadChunk(-1, -1);
        assertNull(SchematicStandSurface.surfaceY(world, feet));
        assertFalse(SchematicStandSurface.canStandAt(world, player, feet));
    }
}
