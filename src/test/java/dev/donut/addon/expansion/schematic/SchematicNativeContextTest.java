package dev.donut.addon.expansion.schematic;

import java.lang.reflect.Method;
import java.util.*;
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.item.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the production predictor against real native contexts and normally constructed players. */
class SchematicNativeContextTest {
    private static final BlockPos TARGET = new BlockPos(0, 1, 0);
    private static Method prediction;

    @BeforeAll static void prepareNativeData() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var resources = new VanillaPackResourcesBuilder().pushJarResources().exposeNamespace("minecraft")
            .build(new PackLocationInfo("vanilla", Component.literal("Vanilla placement test data"), PackSource.BUILT_IN, Optional.empty()));
        try (var data = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(resources.fullResources()))) {
            TagLoader.loadTagsForExistingRegistries(data, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY))
                .forEach(tags -> tags.apply());
        }
        PlacementFixtureLevel.prepareRegistries();
        prediction = SchematicNativePlacement.class.getDeclaredMethod("predictWithNativeContext",
            Player.class, BlockItem.class, ItemStack.class, BlockHitResult.class, Rotation.class, BlockPos.class);
        prediction.setAccessible(true);
    }

    private static PlacementFixtureLevel floor() {
        var level = new PlacementFixtureLevel();
        level.put(TARGET.below(), Blocks.STONE.defaultBlockState());
        return level;
    }

    private static BlockHitResult floorHit() {
        return new BlockHitResult(new Vec3(0.5, 1.0, 0.5), Direction.UP, TARGET.below(), false);
    }

    private static BlockState predict(PlacementFixturePlayer player, Item item, BlockHitResult hit, float yaw, float pitch) throws Exception {
        return (BlockState)prediction.invoke(SchematicNativePlacement.INSTANCE, player, (BlockItem)item,
            new ItemStack(item), hit, new Rotation(yaw, pitch), TARGET);
    }

    @Test void managedLookControlsAllFourNativeStairAndFurnaceDirectionsAndRestoresCamera() throws Exception {
        var player = new PlacementFixturePlayer(floor());
        player.setYRot(17.0f);
        player.setXRot(-12.0f);
        Direction[] expected = {Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST};
        for (int i = 0; i < expected.length; i++) {
            var stairs = predict(player, Items.OAK_STAIRS, floorHit(), i * 90f, 0f);
            assertNotNull(stairs);
            assertEquals(expected[i], stairs.getValue(StairBlock.FACING));
            var furnace = predict(player, Items.FURNACE, floorHit(), i * 90f, 0f);
            assertNotNull(furnace);
            assertEquals(expected[i].getOpposite(), furnace.getValue(HorizontalDirectionalBlock.FACING));
            assertEquals(17.0f, player.getYRot());
            assertEquals(-12.0f, player.getXRot());
        }
    }

    @Test void nativeLogAxisFollowsTheClickedFaceInsteadOfOnlyLookingAtTheSupport() throws Exception {
        for (Direction face : new Direction[]{Direction.UP, Direction.EAST, Direction.SOUTH}) {
            var level = new PlacementFixtureLevel();
            BlockPos support = TARGET.relative(face.getOpposite());
            level.put(support, Blocks.STONE.defaultBlockState());
            var player = new PlacementFixturePlayer(level);
            var hit = new BlockHitResult(Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5)), face, support, false);
            var state = predict(player, Items.OAK_LOG, hit, 0f, 0f);
            assertNotNull(state);
            assertEquals(face.getAxis(), state.getValue(RotatedPillarBlock.AXIS));
        }
    }

    @Test void sideHitHeightSelectsNativeSlabHalfAndSecondUseCombinesTheSlab() throws Exception {
        var level = new PlacementFixtureLevel();
        BlockPos support = TARGET.west();
        level.put(support, Blocks.STONE.defaultBlockState());
        var player = new PlacementFixturePlayer(level);
        for (double height : new double[]{0.25, 0.75}) {
            var hit = new BlockHitResult(new Vec3(0.0, 1.0 + height, 0.5), Direction.EAST, support, false);
            var state = predict(player, Items.OAK_SLAB, hit, 90f, 0f);
            assertNotNull(state);
            assertEquals(height < 0.5 ? SlabType.BOTTOM : SlabType.TOP, state.getValue(SlabBlock.TYPE));
        }
        level.put(TARGET, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        var mergeHit = new BlockHitResult(new Vec3(0.5, 1.5, 0.5), Direction.UP, TARGET, false);
        var doubled = predict(player, Items.OAK_SLAB, mergeHit, 0f, 45f);
        assertNotNull(doubled);
        assertEquals(SlabType.DOUBLE, doubled.getValue(SlabBlock.TYPE));
    }

    @Test void nativeDoorsUseLookAndHitPositionForFacingLowerHalfAndHinge() throws Exception {
        var player = new PlacementFixturePlayer(floor());
        var leftHit = new BlockHitResult(new Vec3(0.15, 1.0, 0.5), Direction.UP, TARGET.below(), false);
        var rightHit = new BlockHitResult(new Vec3(0.85, 1.0, 0.5), Direction.UP, TARGET.below(), false);
        var left = predict(player, Items.OAK_DOOR, leftHit, 180f, 0f);
        var right = predict(player, Items.OAK_DOOR, rightHit, 180f, 0f);
        assertNotNull(left);
        assertNotNull(right);
        assertEquals(Direction.NORTH, left.getValue(DoorBlock.FACING));
        assertEquals(DoubleBlockHalf.LOWER, left.getValue(DoorBlock.HALF));
        assertNotEquals(left.getValue(DoorBlock.HINGE), right.getValue(DoorBlock.HINGE));
    }

    @Test void blockedNativeContextIsRejectedAndFailureStillRestoresThePlayersAngles() throws Exception {
        var level = floor();
        level.put(TARGET, Blocks.STONE.defaultBlockState());
        var player = new PlacementFixturePlayer(level);
        player.setYRot(32f);
        player.setXRot(11f);
        assertNull(predict(player, Items.OAK_STAIRS, floorHit(), 180f, 45f));
        assertEquals(32f, player.getYRot());
        assertEquals(11f, player.getXRot());
    }

    @Test void verticalNativeLookSelectsUpwardAndDownwardPistonPlacement() throws Exception {
        var player = new PlacementFixturePlayer(floor());
        var upward = predict(player, Items.PISTON, floorHit(), 0f, 90f);
        var downward = predict(player, Items.PISTON, floorHit(), 0f, -90f);
        assertNotNull(upward);
        assertNotNull(downward);
        assertEquals(Direction.UP, upward.getValue(DirectionalBlock.FACING));
        assertEquals(Direction.DOWN, downward.getValue(DirectionalBlock.FACING));
    }

    @Test void itemStateOverridesApplyAfterNativePredictionAndWrongDestinationsAreRejected() throws Exception {
        var player = new PlacementFixturePlayer(floor());
        var stack = new ItemStack(Items.OAK_LOG);
        stack.set(net.minecraft.core.component.DataComponents.BLOCK_STATE,
            new net.minecraft.world.item.component.BlockItemStateProperties(Map.of("axis", "x")));
        var state = (BlockState)prediction.invoke(SchematicNativePlacement.INSTANCE, player, (BlockItem)Items.OAK_LOG,
            stack, floorHit(), new Rotation(0f, 30f), TARGET);
        assertNotNull(state);
        assertEquals(Direction.Axis.X, state.getValue(RotatedPillarBlock.AXIS));
        assertNull(prediction.invoke(SchematicNativePlacement.INSTANCE, player, (BlockItem)Items.OAK_LOG,
            stack, floorHit(), new Rotation(0f, 30f), TARGET.east()));
    }

    @Test void actualVanillaInventorySwapAndRestorationAgreeWithTheBuildersAcknowledgementFactory() {
        var player = new PlacementFixturePlayer(floor());
        var material = new ItemStack(Items.STONE, 32);
        material.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Owned material"));
        player.getInventory().setItem(9, material);
        player.getInventory().setItem(0, new ItemStack(Items.BREAD, 3));
        var menu = player.inventoryMenu;
        var tracker = new dev.donut.addon.expansion.automation.core.InventoryAckTracker();
        for (int pass = 0; pass < 2; pass++) {
            var beforeSource = dev.donut.addon.expansion.automation.core.InventorySlotValue.fromStack(menu.getSlot(9).getItem());
            var beforeDestination = dev.donut.addon.expansion.automation.core.InventorySlotValue.fromStack(menu.getSlot(36).getItem());
            int baseline = menu.getStateId();
            tracker.begin(SchematicInventoryRules.INSTANCE.swapExpectation(7L, menu.containerId, baseline,
                9, 36, beforeSource, beforeDestination));
            menu.clicked(9, 0, net.minecraft.world.inventory.ContainerInput.SWAP, player);
            var afterSource = dev.donut.addon.expansion.automation.core.InventorySlotValue.fromStack(menu.getSlot(9).getItem());
            var afterDestination = dev.donut.addon.expansion.automation.core.InventorySlotValue.fromStack(menu.getSlot(36).getItem());
            assertEquals(beforeDestination, afterSource);
            assertEquals(beforeSource, afterDestination);
            assertEquals(dev.donut.addon.expansion.automation.core.InventoryAckStatus.ACKNOWLEDGED,
                tracker.observe(new dev.donut.addon.expansion.automation.core.InventorySyncFrame(7L,
                    menu.containerId, (baseline + 1) & 0x7FFF, Map.of(9, afterSource, 36, afterDestination), null, false)));
            assertEquals(dev.donut.addon.expansion.automation.core.InventoryAckStatus.ACKNOWLEDGED,
                tracker.reconcileLocal(afterSource, afterDestination, menu.getCarried().isEmpty()));
        }
        assertEquals(Items.STONE, player.getInventory().getItem(9).getItem());
        assertEquals("Owned material", player.getInventory().getItem(9).getHoverName().getString());
        assertEquals(Items.BREAD, player.getInventory().getItem(0).getItem());
    }

    @Test void realFilledBucketsBecomeOnlyTheRecordedNativeEmptyBucketRemainder() {
        var empty = dev.donut.addon.expansion.automation.core.InventorySlotValue.fromStack(new ItemStack(Items.BUCKET));
        for (Item item : new Item[]{Items.WATER_BUCKET, Items.LAVA_BUCKET}) {
            var original = dev.donut.addon.expansion.automation.core.InventorySlotValue.fromStack(new ItemStack(item));
            assertNotEquals(original.getComponentFingerprint(), empty.getComponentFingerprint());
            assertTrue(SchematicInventoryRules.INSTANCE.isOwnedRestoreRemainder(original, empty, original, empty));
            var customEmpty = new ItemStack(Items.BUCKET);
            customEmpty.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Someone else's bucket"));
            assertFalse(SchematicInventoryRules.INSTANCE.isOwnedRestoreRemainder(original,
                dev.donut.addon.expansion.automation.core.InventorySlotValue.fromStack(customEmpty), original, empty));
        }
    }
}
