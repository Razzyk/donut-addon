package dev.donut.addon.expansion.schematic;

import net.ccbluex.liquidbounce.utils.aiming.data.Rotation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Production ray/placement search against native blocks and tracked player collision. */
class SchematicNativeSolverTest {
    @BeforeAll static void prepareNativeData() throws Exception {
        SchematicNativeContextTest.prepareNativeData();
    }

    private static SchematicPlacementCandidate find(PlacementFixtureLevel world, PlacementFixturePlayer player,
                                                    BlockPos target, BlockState expected, boolean partialSlab) {
        return SchematicNativePlacement.INSTANCE.findCandidate(world, player, target, expected,
                player.getMainHandItem(), player.getEyePosition(), false, false, partialSlab, false,
                Rotation.ZERO, rotation -> rotation);
    }

    private static void use(PlacementFixturePlayer player, SchematicPlacementCandidate candidate) {
        assertNotNull(candidate, "production solver must find a reachable native placement");
        float yaw = player.getYRot(), pitch = player.getXRot();
        try {
            player.setYRot(candidate.getRotation().yRot());
            player.setXRot(candidate.getRotation().xRot());
            var stack = player.getMainHandItem();
            assertTrue(stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                    candidate.getHit())).consumesAction(), "native use must accept the candidate");
        } finally {
            player.setYRot(yaw);
            player.setXRot(pitch);
        }
    }

    @Test void predictedGlassAndPersistentLeavesActuallyPlaceFromARealStand() {
        for (var item : new net.minecraft.world.item.Item[]{Items.GLASS, Items.OAK_LEAVES}) {
            var world = new PlacementFixtureLevel();
            var player = new PlacementFixturePlayer(world);
            world.track(player);
            world.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
            world.put(new BlockPos(0, 0, -2), Blocks.STONE.defaultBlockState());
            player.setPos(0.5, 1, -1.5);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item, 3));
            var expected = item == Items.GLASS ? Blocks.GLASS.defaultBlockState()
                    : Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, true);
            var target = new BlockPos(0, 1, 0);
            var candidate = find(world, player, target, expected, false);
            use(player, candidate);
            assertEquals(candidate.getPredictedState(), world.getBlockState(target));
            assertEquals(2, player.getMainHandItem().getCount());
        }
    }

    @Test void doubleSlabCanStartWithOneNativeUseFromAir() {
        var world = new PlacementFixtureLevel();
        var player = new PlacementFixturePlayer(world);
        world.track(player);
        world.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        player.setPos(0.5, 1, -1.5);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_SLAB, 2));
        var expected = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE);
        var target = new BlockPos(0, 1, 0);
        assertNull(find(world, player, target, expected, false));
        var candidate = find(world, player, target, expected, true);
        use(player, candidate);
        assertNotEquals(SlabType.DOUBLE, world.getBlockState(target).getValue(BlockStateProperties.SLAB_TYPE));
        assertEquals(1, player.getMainHandItem().getCount());
    }

    @Test void centeredStandCannotReachItsOwnSideButAnEdgeLeanCan() {
        var world = new PlacementFixtureLevel();
        var player = new PlacementFixturePlayer(world);
        world.track(player);
        var support = new BlockPos(1, 2, 2);
        var target = support.south();
        world.put(support, Blocks.GLASS.defaultBlockState());
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GLASS, 4));
        player.setPos(1.5, 3, 2.5);
        assertNull(find(world, player, target, Blocks.GLASS.defaultBlockState(), false),
                "the supporting block's top occludes its side from the centered eye");
        player.setPos(1.5, 3, 3.18);
        var candidate = find(world, player, target, Blocks.GLASS.defaultBlockState(), false);
        use(player, candidate);
        assertTrue(world.getBlockState(target).is(Blocks.GLASS));
        assertEquals(3, player.getMainHandItem().getCount());
    }

    @Test void threeSuccessivePillarPlacementsAreLegalInsideTheJumpWindow() {
        var world = new PlacementFixtureLevel();
        var player = new PlacementFixturePlayer(world);
        world.track(player);
        world.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GLASS, 4));
        for (int y = 1; y <= 3; y++) {
            var target = new BlockPos(0, y, 0);
            // A valid geometry sample within a vanilla jump; no physics or server is simulated.
            player.setPos(0.5, y + 1.05, 0.5);
            var candidate = find(world, player, target, Blocks.GLASS.defaultBlockState(), false);
            use(player, candidate);
            assertTrue(world.getBlockState(target).is(Blocks.GLASS));
            player.setPos(0.5, y + 1, 0.5);
        }
        assertEquals(1, player.getMainHandItem().getCount());
    }

    @Test void currentPlayerObstructionRequiresADifferentStandBeforePlacement() {
        var world = new PlacementFixtureLevel();
        var player = new PlacementFixturePlayer(world);
        world.track(player);
        world.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GLASS, 2));
        player.setPos(0.5, 1, 0.5);
        var target = new BlockPos(0, 1, 0);
        assertNull(find(world, player, target, Blocks.GLASS.defaultBlockState(), false),
                "the current player body prevents native glass placement");
        var futureEye = player.getEyePosition().add(0, 0, -2);
        var futureCandidate = SchematicNativePlacement.INSTANCE.findCandidate(world, player, target,
                Blocks.GLASS.defaultBlockState(), player.getMainHandItem(), futureEye,
                false, false, false, false, Rotation.ZERO, rotation -> rotation);
        assertNotNull(futureCandidate, "a hypothetical clear stand must not retain the old player-body obstruction");
        player.setPos(0.5, 1, -1.5);
        use(player, futureCandidate);
        assertTrue(world.getBlockState(target).is(Blocks.GLASS));
    }

    @Test void planningStillRespectsAnotherPlayersBodyAtTheTarget() {
        var world = new PlacementFixtureLevel();
        var player = new PlacementFixturePlayer(world);
        var other = new PlacementFixturePlayer(world);
        world.track(player);
        world.track(other);
        world.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        player.setPos(0.5, 1, -1.5);
        other.setPos(0.5, 1, 0.5);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GLASS, 2));
        var target = new BlockPos(0, 1, 0);
        assertNull(find(world, player, target, Blocks.GLASS.defaultBlockState(), false));
        var futureEye = player.getEyePosition().add(1, 0, 0);
        assertNull(SchematicNativePlacement.INSTANCE.findCandidate(world, player, target,
                Blocks.GLASS.defaultBlockState(), player.getMainHandItem(), futureEye,
                false, false, false, false, Rotation.ZERO, rotation -> rotation));
        assertTrue(world.getBlockState(target).isAir());
        assertEquals(2, player.getMainHandItem().getCount());
    }
}
