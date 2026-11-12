package dev.donut.addon.expansion.search.growth;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Correlates server light cells with nearby geode lining and hidden crystal growth. */
final class SuspectHiddenAmethyst {
    private static final Direction[] DIRECTIONS = Direction.values();

    private SuspectHiddenAmethyst() {}

    static GrowthSurveyModule.LightReading scan(ClientLevel world, LevelChunk chunk, SuspectServerLight light,
                                                 Boolean hiddenSolids) {
        int minSection = chunk.getMinSectionY();
        int topSection = Math.min(2, chunk.getMaxSectionY());
        if (topSection < minSection) return new GrowthSurveyModule.LightReading(0, 0, 0, 0);
        LevelChunkSection[] sections = chunk.getSections();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        boolean solidsPass = Boolean.TRUE.equals(hiddenSolids);
        boolean solidRing = !Boolean.FALSE.equals(hiddenSolids);
        Map<Long, Boolean> darkCache = new HashMap<>();
        int buds = 0, clusters = 0, darkVotes = 0, glowVotes = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int sectionY = minSection; sectionY <= topSection; sectionY++) {
            int index = chunk.getSectionIndexFromSectionY(sectionY);
            if (index < 0 || index >= sections.length) continue;
            LevelChunkSection section = sections[index];
            if (section == null || section.hasOnlyAir() || !nearGeode(world, chunk, sectionY, minSection, topSection)) continue;
            int baseY = sectionY << 4;
            for (int y = baseY; y < baseY + 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState state = section.getBlockState(x, y & 15, z);
                if (crystal(state)) continue;
                int wx = minX + x, wz = minZ + z;
                int value = light(world, light, wx, y, wz);
                if (value == 0 && darkCandidate(world, wx, y, wz, state, solidsPass)) {
                    if (darkNextToGlow(world, light, wx, y, wz, state, solidsPass) && darkVotes < 100000) darkVotes++;
                    if (darkCluster(world, light, wx, y, wz, darkCache, solidsPass) && clusters < 100000) clusters++;
                } else if (growthLight(value) && passesLight(world, light, wx, y, wz, state, value, solidsPass)
                    && touchesLining(world, wx, y, wz) && value >= brightestAround(world, light, wx, y, wz, solidsPass)
                    && (value != 4 || !besideDarkCluster(world, light, wx, y, wz, darkCache, solidsPass)
                        && (!solidRing || !besideDarkSolid(world, light, wx, y, wz)))
                    && fadesEvenly(world, light, wx, y, wz, value, solidsPass)) {
                    if (value == 5) { glowVotes++; clusters++; }
                    else buds++;
                }
            }
        }
        return new GrowthSurveyModule.LightReading(buds, clusters, darkVotes, glowVotes);
    }

    private static boolean nearGeode(ClientLevel world, LevelChunk chunk, int sectionY, int minSection, int maxSection) {
        if (hasLining(chunk.getSections()[chunk.getSectionIndexFromSectionY(sectionY)])) return true;
        for (int dy : new int[]{-1, 1}) {
            int adjacent = sectionY + dy;
            if (adjacent >= minSection && adjacent <= maxSection
                && hasLining(chunk.getSections()[chunk.getSectionIndexFromSectionY(adjacent)])) return true;
        }
        int cx = chunk.getPos().x(), cz = chunk.getPos().z();
        for (int[] side : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
            var neighbor = world.getChunkSource().getChunk(cx + side[0], cz + side[1], false);
            if (neighbor == null) continue;
            int index = neighbor.getSectionIndexFromSectionY(sectionY);
            if (index >= 0 && index < neighbor.getSections().length && hasLining(neighbor.getSections()[index])) return true;
        }
        return false;
    }

    private static boolean hasLining(LevelChunkSection section) {
        return section != null && !section.hasOnlyAir() && section.maybeHas(SuspectHiddenAmethyst::amethyst);
    }

    private static boolean darkCandidate(ClientLevel world, int x, int y, int z, BlockState state, boolean solidsPass) {
        return (open(state) || solidsPass && hiddenSolid(world, x, y, z, state)) && touchesLining(world, x, y, z);
    }

    private static boolean passesLight(ClientLevel world, SuspectServerLight light, int x, int y, int z,
                                       BlockState state, int value, boolean solidsPass) {
        return open(state) || solidsPass && value > 0 && hiddenSolid(world, x, y, z, state);
    }

    private static boolean hiddenSolid(ClientLevel world, int x, int y, int z, BlockState state) {
        return state != null && !open(state) && !amethyst(state) && state.getLightEmission() == 0
            && touchesLining(world, x, y, z);
    }

    private static boolean darkNextToGlow(ClientLevel world, SuspectServerLight light, int x, int y, int z,
                                          BlockState state, boolean solidsPass) {
        if (!open(state) || light(world, light, x, y, z) != 0) return false;
        for (Direction side : DIRECTIONS) {
            int nx = x + side.getStepX(), ny = y + side.getStepY(), nz = z + side.getStepZ();
            BlockState next = state(world, nx, ny, nz);
            if (next != null && passesLight(world, light, nx, ny, nz, next, light(world, light, nx, ny, nz), solidsPass)
                && light(world, light, nx, ny, nz) >= 2) return true;
        }
        return false;
    }

    private static boolean darkCluster(ClientLevel world, SuspectServerLight light, int x, int y, int z,
                                       Map<Long, Boolean> cache, boolean solidsPass) {
        long key = BlockPos.asLong(x, y, z);
        Boolean known = cache.get(key);
        if (known != null) return known;
        BlockState state = state(world, x, y, z);
        boolean result = false;
        if (state != null && light(world, light, x, y, z) == 0 && darkCandidate(world, x, y, z, state, solidsPass)) {
            int strong = 0;
            boolean weak = false;
            for (Direction side : DIRECTIONS) {
                int nx = x + side.getStepX(), ny = y + side.getStepY(), nz = z + side.getStepZ();
                BlockState next = state(world, nx, ny, nz);
                int value = light(world, light, nx, ny, nz);
                if (next != null && passesLight(world, light, nx, ny, nz, next, value, solidsPass)
                    && !darkNextToGlow(world, light, nx, ny, nz, next, solidsPass) && value >= 0) {
                    if (value >= 4) strong++;
                    else weak = true;
                }
            }
            result = !weak && strong >= (open(state) ? 1 : 2);
        }
        cache.put(key, result);
        return result;
    }

    private static boolean besideDarkCluster(ClientLevel world, SuspectServerLight light, int x, int y, int z,
                                              Map<Long, Boolean> cache, boolean solidsPass) {
        for (Direction side : DIRECTIONS) if (darkCluster(world, light, x + side.getStepX(), y + side.getStepY(),
            z + side.getStepZ(), cache, solidsPass)) return true;
        return false;
    }

    private static boolean besideDarkSolid(ClientLevel world, SuspectServerLight light, int x, int y, int z) {
        for (Direction side : DIRECTIONS) {
            int nx = x + side.getStepX(), ny = y + side.getStepY(), nz = z + side.getStepZ();
            BlockState state = state(world, nx, ny, nz);
            if (state != null && light(world, light, nx, ny, nz) == 0 && hiddenSolid(world, nx, ny, nz, state)) return true;
        }
        return false;
    }

    private static int brightestAround(ClientLevel world, SuspectServerLight light, int x, int y, int z,
                                       boolean solidsPass) {
        int brightest = 0;
        for (Direction side : DIRECTIONS) {
            int nx = x + side.getStepX(), ny = y + side.getStepY(), nz = z + side.getStepZ();
            BlockState state = state(world, nx, ny, nz);
            int value = state == null || !open(state) && !(solidsPass && hiddenSolid(world, nx, ny, nz, state))
                && state.getLightEmission() == 0 ? 0 : light(world, light, nx, ny, nz);
            brightest = Math.max(brightest, value);
        }
        return brightest;
    }

    private static boolean fadesEvenly(ClientLevel world, SuspectServerLight light, int x, int y, int z, int value,
                                       boolean solidsPass) {
        for (Direction side : DIRECTIONS) {
            int nx = x + side.getStepX(), ny = y + side.getStepY(), nz = z + side.getStepZ();
            BlockState state = state(world, nx, ny, nz);
            if (state == null || !open(state) && !(solidsPass && hiddenSolid(world, nx, ny, nz, state))) continue;
            int around = light(world, light, nx, ny, nz);
            if (around >= 0 && around < value - 1) return false;
        }
        return true;
    }

    private static boolean touchesLining(ClientLevel world, int x, int y, int z) {
        for (Direction side : DIRECTIONS) if (lining(state(world, x + side.getStepX(), y + side.getStepY(), z + side.getStepZ()))) return true;
        return false;
    }

    private static BlockState state(ClientLevel world, int x, int y, int z) {
        if (world.isOutsideBuildHeight(y) || !world.hasChunkAt(new BlockPos(x, y, z))) return null;
        return world.getBlockState(new BlockPos(x, y, z));
    }

    private static int light(ClientLevel world, SuspectServerLight server, int x, int y, int z) {
        int value = server.lightAt(world, x, y, z);
        return value >= 0 ? value : world.getBrightness(LightLayer.BLOCK, new BlockPos(x, y, z));
    }

    private static boolean growthLight(int light) { return light == 1 || light == 2 || light == 4 || light == 5; }
    private static boolean open(BlockState state) { return state != null && (state.isAir() || state.is(Blocks.WATER)); }
    private static boolean lining(BlockState state) { return state != null && (state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.BUDDING_AMETHYST)); }
    private static boolean crystal(BlockState state) {
        return state != null && (state.is(Blocks.SMALL_AMETHYST_BUD) || state.is(Blocks.MEDIUM_AMETHYST_BUD)
            || state.is(Blocks.LARGE_AMETHYST_BUD) || state.is(Blocks.AMETHYST_CLUSTER));
    }
    private static boolean amethyst(BlockState state) { return lining(state) || crystal(state); }
}
