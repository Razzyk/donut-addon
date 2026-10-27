package dev.donut.addon.mv.compat.events.world;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Block changes already applied to the client world. */
public record StorageBlocksChangedEvent(Map<BlockPos, BlockState> changes) {}
