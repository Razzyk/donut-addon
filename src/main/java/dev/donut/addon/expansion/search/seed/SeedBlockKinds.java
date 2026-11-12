package dev.donut.addon.expansion.search.seed;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Compact live block classification for independently captured evidence. */
final class SeedBlockKinds {
    static SeedGroundCapture.Cell ground(BlockState state) {
        Block b = state.getBlock();
        String name = BuiltInRegistries.BLOCK.getKey(b).getPath();
        boolean mapped = b == Blocks.STONE || b == Blocks.DEEPSLATE || b == Blocks.GRANITE || b == Blocks.DIORITE ||
            b == Blocks.ANDESITE || b == Blocks.TUFF || b == Blocks.GRAVEL || b == Blocks.DIRT || b == Blocks.CLAY ||
            name.endsWith("_ore") || b == Blocks.RAW_IRON_BLOCK || b == Blocks.RAW_COPPER_BLOCK ||
            b == Blocks.INFESTED_STONE || b == Blocks.INFESTED_DEEPSLATE;
        boolean groundPath = name.endsWith("dirt") || name.equals("grass_block") || name.equals("podzol") ||
            name.equals("mycelium") || name.endsWith("sand") || name.endsWith("sandstone") ||
            name.endsWith("terracotta") || name.equals("snow_block") || name.equals("packed_ice") ||
            name.equals("blue_ice") || name.equals("mud") || name.equals("calcite") || name.equals("powder_snow") ||
            name.equals("moss_block") || name.equals("clay") || name.equals("dripstone_block") ||
            name.equals("magma_block") || name.equals("smooth_basalt");
        boolean ice = b == Blocks.PACKED_ICE || b == Blocks.BLUE_ICE || b == Blocks.SNOW_BLOCK;
        return new SeedGroundCapture.Cell(state.isSolid(), mapped || state.isSolid() && groundPath, ice);
    }

    static byte classify(BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty()) return SeedScanCore.AIR;
        Block block = state.getBlock();
        if (block == Blocks.GRANITE) return SeedScanCore.GRANITE;
        if (block == Blocks.DIORITE) return SeedScanCore.DIORITE;
        if (block == Blocks.ANDESITE) return SeedScanCore.ANDESITE;
        if (block == Blocks.GRAVEL) return SeedScanCore.GRAVEL;
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        if (path.endsWith("_ore") || block == Blocks.RAW_IRON_BLOCK || block == Blocks.RAW_COPPER_BLOCK
            || block == Blocks.INFESTED_STONE || block == Blocks.INFESTED_DEEPSLATE || block == Blocks.MOSSY_COBBLESTONE
            || block == Blocks.OBSIDIAN || block == Blocks.CHEST || block == Blocks.ENDER_CHEST) return SeedScanCore.ORE;
        if (isStructure(path, block)) return SeedScanCore.STRUCTURE;
        if (isNatural(block)) return SeedScanCore.NATURAL;
        if (block == Blocks.STONE || block == Blocks.DEEPSLATE) return SeedScanCore.STONE;
        if (block == Blocks.TUFF) return SeedScanCore.TUFF;
        if (block == Blocks.DIRT) return SeedScanCore.DIRT;
        if (block == Blocks.CLAY) return SeedScanCore.CLAY;
        return SeedScanCore.MODIFIED;
    }


    private static boolean isNatural(Block block) {
        return block == Blocks.CALCITE || block == Blocks.SMOOTH_BASALT || block == Blocks.AMETHYST_BLOCK
            || block == Blocks.BUDDING_AMETHYST || block == Blocks.DRIPSTONE_BLOCK || block == Blocks.POINTED_DRIPSTONE
            || block == Blocks.MOSS_BLOCK || block == Blocks.MOSS_CARPET || block == Blocks.ROOTED_DIRT
            || block == Blocks.CLAY || block == Blocks.SCULK || block == Blocks.SCULK_VEIN
            || block == Blocks.SCULK_CATALYST || block == Blocks.SCULK_SENSOR || block == Blocks.SCULK_SHRIEKER
            || block == Blocks.MAGMA_BLOCK || block == Blocks.NETHERRACK || block == Blocks.CRYING_OBSIDIAN;
    }


    private static boolean isStructure(String path, Block block) {
        if (block == Blocks.BONE_BLOCK || block == Blocks.SPAWNER || block == Blocks.TRIAL_SPAWNER
            || block == Blocks.VAULT || block == Blocks.END_PORTAL_FRAME || block == Blocks.REINFORCED_DEEPSLATE
            || block == Blocks.SEA_LANTERN) return true;
        return path.contains("stone_brick") || path.contains("tuff") && !path.equals("tuff")
            || path.contains("copper") && !path.contains("ore") && !path.equals("raw_copper_block")
            || path.startsWith("deepslate_brick") || path.startsWith("deepslate_tile")
            || path.startsWith("cracked_deepslate") || path.startsWith("polished_deepslate")
            || path.startsWith("suspicious_") || path.contains("terracotta") || path.contains("prismarine")
            || path.endsWith("sponge") || path.startsWith("mud_brick") || path.equals("chiseled_deepslate");
    }

}
