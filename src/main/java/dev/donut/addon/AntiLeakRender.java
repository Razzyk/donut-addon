package dev.donut.addon;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Shared render switch; safe to read from chunk compilation threads. */
public final class AntiLeakRender {
    private static volatile boolean enabled;

    private AntiLeakRender() { }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    private static boolean normalize(BlockState state) {
        return enabled && (state.is(Blocks.DEEPSLATE) || state.is(Blocks.INFESTED_DEEPSLATE));
    }

    public static BlockState modelState(BlockState state) {
        return normalize(state) ? state.getBlock().defaultBlockState() : state;
    }

    public static long seed(BlockState state, long vanillaSeed) {
        return normalize(state) ? 0L : vanillaSeed;
    }
}
