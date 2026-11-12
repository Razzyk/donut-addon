package dev.donut.addon.expansion.search.growth;

import net.minecraft.client.multiplayer.ClientLevel;
import dev.donut.addon.expansion.search.amethyst.GeodePaletteEvidence;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Counts dark openings at light-four boundaries around loaded geode sections. */
final class SuspectGlowScanner {
    private static final int MIN_Y = -58;
    private static final int MAX_Y = 30;

    int scan(ClientLevel level, LevelChunk chunk, SuspectServerLight serverLight) {
        LevelChunkSection[] sections = chunk.getSections();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int hits = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int sectionY = -4; sectionY <= 1; sectionY++) {
            int sectionIndex = chunk.getSectionIndexFromSectionY(sectionY);
            if (sectionIndex < 0 || sectionIndex >= sections.length) continue;
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) continue;
            int fromY = Math.max(MIN_Y, sectionY << 4);
            int toY = Math.min(MAX_Y, (sectionY << 4) + 15);
            for (int y = fromY; y <= toY; y++) {
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y & 15, z);
                    if (!open(state)) continue;
                    int wx = minX + x, wz = minZ + z;
                    cursor.set(wx, y, wz);
                    if (light(level, serverLight, wx, y, wz) != 0) continue;
                    int brightest = 0;
                    boolean litOpening = false;
                    boolean complete = true;
                    for (Direction side : Direction.values()) {
                        int nx = wx + side.getStepX();
                        int ny = y + side.getStepY();
                        int nz = wz + side.getStepZ();
                        if (!level.hasChunkAt(new BlockPos(nx, ny, nz))) { complete = false; break; }
                        int value = light(level, serverLight, nx, ny, nz);
                        if (value > 4) { brightest = value; break; }
                        brightest = Math.max(brightest, value);
                        if (value == 4 && !litOpening && open(level.getBlockState(cursor.set(nx, ny, nz)))) litOpening = true;
                    }
                    if (complete && GeodePaletteEvidence.isGlowEdge(0, true, brightest, litOpening)) hits++;
                }
            }
        }
        return hits;
    }

    private static int light(ClientLevel level, SuspectServerLight serverLight, int x, int y, int z) {
        int packet = serverLight.lightAt(level, x, y, z);
        return packet >= 0 ? packet : level.getBrightness(LightLayer.BLOCK, new BlockPos(x, y, z));
    }

    private static boolean open(BlockState state) {
        return state.isAir() || state.is(Blocks.AMETHYST_CLUSTER);
    }
}
