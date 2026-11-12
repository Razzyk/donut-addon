package dev.donut.addon.expansion.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchematicNativeUseTest {
    @BeforeAll static void prepareNativeData() throws Exception { SchematicNativeContextTest.prepareNativeData(); }

    @Test void nativeBlockUsePlacesThePredictedStateAndConsumesOneItem() {
        var world=new PlacementFixtureLevel(); var player=new PlacementFixturePlayer(world);
        var floor=new BlockPos(0,0,0); world.put(floor,Blocks.STONE.defaultBlockState());
        var stack=new ItemStack(Items.OAK_LOG,3); player.setItemInHand(InteractionHand.MAIN_HAND,stack);
        var hit=new BlockHitResult(new Vec3(0.5,1,0.5),Direction.UP,floor,false);
        assertTrue(stack.useOn(new UseOnContext(player,InteractionHand.MAIN_HAND,hit)).consumesAction());
        assertTrue(world.getBlockState(floor.above()).is(Blocks.OAK_LOG));
        assertEquals(2,stack.getCount());
    }

    @Test void scaffoldGlassAndLeavesUseTheActualNativePredictedState() throws Exception {
        var predict=SchematicNativePlacement.class.getDeclaredMethod("predictWithNativeContext",Player.class,
            BlockItem.class,ItemStack.class,BlockHitResult.class,Rotation.class,BlockPos.class);
        predict.setAccessible(true);
        for(var item : new net.minecraft.world.item.Item[]{Items.GLASS,Items.OAK_LEAVES}) {
            var world=new PlacementFixtureLevel(); var player=new PlacementFixturePlayer(world);
            var floor=new BlockPos(0,0,0); world.put(floor,Blocks.STONE.defaultBlockState());
            var stack=new ItemStack(item,2); player.setItemInHand(InteractionHand.MAIN_HAND,stack);
            var hit=new BlockHitResult(new Vec3(0.5,1,0.5),Direction.UP,floor,false);
            var predicted=(BlockState)predict.invoke(SchematicNativePlacement.INSTANCE,player,(BlockItem)item,stack,hit,new Rotation(0,45),floor.above());
            assertNotNull(predicted);
            assertTrue(stack.useOn(new UseOnContext(player,InteractionHand.MAIN_HAND,hit)).consumesAction());
            assertEquals(predicted,world.getBlockState(floor.above()));
            assertEquals(1,stack.getCount());
            if(item==Items.OAK_LEAVES) {
                assertTrue(predicted.getValue(BlockStateProperties.PERSISTENT));
                assertNotEquals(Blocks.OAK_LEAVES.defaultBlockState(),predicted);
            }
        }
    }

    @Test void nativeFluidRayAndEmptyBucketUseFillFromTheSelectedSource() {
        var world=new PlacementFixtureLevel(true); var player=new PlacementFixturePlayer(world);
        var source=new BlockPos(0,0,0); world.put(source,Blocks.WATER.defaultBlockState());
        player.setPos(0.5,1,0.5); player.setYRot(0); player.setXRot(90);
        player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.BUCKET));
        var eye=player.getEyePosition();
        var ray=world.clip(new ClipContext(eye,eye.add(player.getViewVector(1).scale(4.5)),
            ClipContext.Block.OUTLINE,ClipContext.Fluid.SOURCE_ONLY,player));
        assertEquals(HitResult.Type.BLOCK,ray.getType()); assertEquals(source,ray.getBlockPos());
        var result=player.getMainHandItem().use(world,player,InteractionHand.MAIN_HAND);
        assertTrue(result.consumesAction());
        assertTrue(result instanceof net.minecraft.world.InteractionResult.Success);
        var transformed=((net.minecraft.world.InteractionResult.Success)result).heldItemTransformedTo();
        assertNotNull(transformed); assertTrue(transformed.is(Items.WATER_BUCKET));
        assertTrue(world.getBlockState(source).isAir());
    }

    @Test void nativeFilledBucketUsePlacesWaterAgainstTheAimedSupport() {
        var world=new PlacementFixtureLevel(); var player=new PlacementFixturePlayer(world);
        var support=new BlockPos(0,0,0); world.put(support,Blocks.STONE.defaultBlockState());
        player.setPos(0.5,1,0.5); player.setYRot(0); player.setXRot(90);
        player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.WATER_BUCKET));
        var result=player.getMainHandItem().use(world,player,InteractionHand.MAIN_HAND);
        assertTrue(result.consumesAction());
        assertTrue(world.getFluidState(support.above()).is(Fluids.WATER));
        var transformed=((net.minecraft.world.InteractionResult.Success)result).heldItemTransformedTo();
        assertNotNull(transformed); assertTrue(transformed.is(Items.BUCKET));
    }

    @Test void nativeWaterBucketUsesTheExistingSlabInsteadOfPlacingAdjacentWater() {
        var world=new PlacementFixtureLevel(); var player=new PlacementFixturePlayer(world);
        var target=new BlockPos(0,1,0);
        var dry=Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED,false);
        world.put(target,dry);
        player.setPos(0.5,2,0.5); player.setYRot(0); player.setXRot(90);
        player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.WATER_BUCKET));
        var eye=player.getEyePosition();
        var ray=world.clip(new ClipContext(eye,eye.add(player.getViewVector(1).scale(4.5)),
            ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player));
        assertEquals(target,ray.getBlockPos());

        var result=player.getMainHandItem().use(world,player,InteractionHand.MAIN_HAND);

        assertTrue(result instanceof net.minecraft.world.InteractionResult.Success);
        assertEquals(dry.setValue(BlockStateProperties.WATERLOGGED,true),world.getBlockState(target));
        assertTrue(world.getBlockState(target.above()).isAir());
        assertTrue(world.getFluidTicks().hasScheduledTick(target,Fluids.WATER));
        var remainder=((net.minecraft.world.InteractionResult.Success)result).heldItemTransformedTo();
        assertNotNull(remainder); assertTrue(remainder.is(Items.BUCKET));
    }

    @Test void aDoubleSlabRequiresTwoAcknowledgedNativeUseStagesFromAir() {
        var world=new PlacementFixtureLevel(); var player=new PlacementFixturePlayer(world);
        var floor=new BlockPos(0,0,0); var target=floor.above();
        world.put(floor,Blocks.STONE.defaultBlockState());
        var slabs=new ItemStack(Items.OAK_SLAB,2); player.setItemInHand(InteractionHand.MAIN_HAND,slabs);
        var first=new BlockHitResult(new Vec3(0.5,1,0.5),Direction.UP,floor,false);
        assertTrue(slabs.useOn(new UseOnContext(player,InteractionHand.MAIN_HAND,first)).consumesAction());
        assertEquals(SlabType.BOTTOM,world.getBlockState(target).getValue(BlockStateProperties.SLAB_TYPE));
        assertTrue(SchematicStructureRules.INSTANCE.isDoubleSlabPending("minecraft:oak_slab",
            java.util.Map.of("type","double"),"minecraft:oak_slab",java.util.Map.of("type","bottom")));
        assertEquals(1,slabs.getCount());
        var second=new BlockHitResult(new Vec3(0.5,1.5,0.5),Direction.UP,target,false);
        assertTrue(slabs.useOn(new UseOnContext(player,InteractionHand.MAIN_HAND,second)).consumesAction());
        assertEquals(SlabType.DOUBLE,world.getBlockState(target).getValue(BlockStateProperties.SLAB_TYPE));
        assertTrue(slabs.isEmpty());
    }

    @Test void nativePlacementRejectsThePlayersBodyAndDoesNotConsumeTheItem() {
        var world=new PlacementFixtureLevel(); var player=new PlacementFixturePlayer(world);
        world.track(player);
        player.setPos(0.5,1,0.5);
        var floor=new BlockPos(0,0,0); world.put(floor,Blocks.STONE.defaultBlockState());
        var stack=new ItemStack(Items.GLASS,2); player.setItemInHand(InteractionHand.MAIN_HAND,stack);
        var hit=new BlockHitResult(new Vec3(0.5,1,0.5),Direction.UP,floor,false);
        assertFalse(stack.useOn(new UseOnContext(player,InteractionHand.MAIN_HAND,hit)).consumesAction());
        assertTrue(world.getBlockState(floor.above()).isAir());
        assertEquals(2,stack.getCount());
    }
}
