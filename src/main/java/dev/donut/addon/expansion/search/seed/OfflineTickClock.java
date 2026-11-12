package dev.donut.addon.expansion.search.seed;

import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;

/** Normalized clock used only to retain relative scheduled-tick data during isolated generation. */
final class OfflineTickClock {
    static final int MAX_QUEUED_TICKS_PER_CHUNK = 4_096;

    private long nextOrder;

    <T> ScheduledTick<T> create(BlockPos pos, T type, int delay) {
        return new ScheduledTick<>(type, pos, delay, this.nextOrder++);
    }

    <T> ScheduledTick<T> create(BlockPos pos, T type, int delay, TickPriority priority) {
        return new ScheduledTick<>(type, pos, delay, priority, this.nextOrder++);
    }

    static void requireCapacity(int queuedInChunk, boolean duplicate, BlockPos pos) {
        if (!duplicate && queuedInChunk >= MAX_QUEUED_TICKS_PER_CHUNK) {
            throw new IllegalStateException("offline chunk scheduled-tick limit exceeded at " + pos);
        }
    }
}
