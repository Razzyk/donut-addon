package dev.donut.addon.mv.compat.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.ChunkPos;
import java.util.*;
public final class Utils {
    public static boolean canUpdate() { Minecraft mc = Minecraft.getInstance(); return mc.player != null && mc.level != null; }
    public static Iterable<ChunkAccess> chunks() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return List.of();
        ChunkPos center = mc.player.chunkPosition();
        int radius = mc.options.getEffectiveRenderDistance() + 3;
        List<ChunkAccess> result = new ArrayList<>();
        for (int x = center.x() - radius; x <= center.x() + radius; x++) {
            for (int z = center.z() - radius; z <= center.z() + radius; z++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null) result.add(chunk);
            }
        }
        return result;
    }
}
