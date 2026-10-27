package dev.mulveo.mvaddon.modules;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StorageBlockStateTest {
   @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

   @Test void identifiesStatesWithoutAnyBlockEntity() {
      assertEquals(StorageEspPlus.StorageType.Chest, StorageEspPlus.typeOfState(Blocks.CHEST.defaultBlockState()));
      assertEquals(StorageEspPlus.StorageType.Shulker, StorageEspPlus.typeOfState(Blocks.SHULKER_BOX.defaultBlockState()));
      for (var block : Blocks.DYED_SHULKER_BOX.asList()) {
         assertEquals(StorageEspPlus.StorageType.Shulker, StorageEspPlus.typeOfState(block.defaultBlockState()));
      }
      assertEquals(StorageEspPlus.StorageType.TrialSpawner, StorageEspPlus.typeOfState(Blocks.TRIAL_SPAWNER.defaultBlockState()));
      assertEquals(StorageEspPlus.StorageType.Vault, StorageEspPlus.typeOfState(Blocks.VAULT.defaultBlockState()));
      assertNull(StorageEspPlus.typeOfState(Blocks.STONE.defaultBlockState()));
   }

   @Test void chestPairRequiresReciprocalStateAndSameBlockAcrossChunkBoundary() {
      BlockState left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT);
      BlockState right = left.setValue(ChestBlock.TYPE, ChestType.RIGHT);
      BlockPos pos = new BlockPos(15, -32, 0);
      BlockPos partner = pos.relative(ChestBlock.getConnectedDirection(left));
      assertTrue(StorageEspPlus.matchingChestPair(pos, left, partner, right));
      assertFalse(StorageEspPlus.matchingChestPair(pos, left, partner, Blocks.TRAPPED_CHEST.defaultBlockState()));
      assertFalse(StorageEspPlus.matchingChestPair(pos, left, partner, left));
      assertFalse(StorageEspPlus.matchingChestPair(pos, left, partner.offset(1, 0, 0), right));
   }
}
