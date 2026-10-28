package dev.donut.addon;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AntiLeakRenderTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void reset() {
        AntiLeakRender.setEnabled(false);
    }

    @Test
    void normalizesBothDeepslateModelsAndSeedsAcrossAxesAndPositions() {
        AntiLeakRender.setEnabled(true);
        BlockPos[] positions = {BlockPos.ZERO, new BlockPos(-1000000, -64, 900000), new BlockPos(29999999, 319, -29999999)};
        for (Block block : new Block[] {Blocks.DEEPSLATE, Blocks.INFESTED_DEEPSLATE}) {
            for (Direction.Axis axis : Direction.Axis.values()) {
                BlockState state = block.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
                assertSame(block.defaultBlockState(), AntiLeakRender.modelState(state));
                assertSame(block, AntiLeakRender.modelState(state).getBlock());
                assertEquals(axis, state.getValue(RotatedPillarBlock.AXIS));
                for (BlockPos pos : positions) {
                    assertEquals(0L, AntiLeakRender.seed(state, state.getSeed(pos)));
                }
            }
        }
    }

    @Test
    void leavesUnrelatedBlocksAndDisabledStatesAlone() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState deepslate = Blocks.DEEPSLATE.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        BlockState infested = Blocks.INFESTED_DEEPSLATE.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
        for (boolean enabled : new boolean[] {false, true}) {
            AntiLeakRender.setEnabled(enabled);
            assertSame(stone, AntiLeakRender.modelState(stone));
            assertEquals(-123456789L, AntiLeakRender.seed(stone, -123456789L));
        }
        AntiLeakRender.setEnabled(false);
        for (BlockState state : new BlockState[] {deepslate, infested}) {
            assertSame(state, AntiLeakRender.modelState(state));
            assertEquals(987654321L, AntiLeakRender.seed(state, 987654321L));
        }
    }
}
