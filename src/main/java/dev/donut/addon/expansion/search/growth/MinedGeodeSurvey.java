package dev.donut.addon.expansion.search.growth;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BuddingAmethystBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Cross-chunk mined-geode candidates from exposed lining, settled chunk scans, and light peaks. */
public final class MinedGeodeSurvey {
    private static final int MAX_CHUNKS = 8192;
    private static final int SETTLED_TICKS = 40;
    private static final int MAX_SECTION_Y = 2;
    private final LinkedHashMap<Long, Slice> slices = new LinkedHashMap<>(256, 0.75f, true);

    public synchronized void scanChunk(ClientLevel world, LevelChunk chunk, long tick) {
        if (world == null || chunk == null || world.dimension() != Level.OVERWORLD) return;
        int chunkX = chunk.getPos().x();
        int chunkZ = chunk.getPos().z();
        ArrayList<Long> lining = new ArrayList<>();
        ArrayList<Long> budding = new ArrayList<>();
        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = chunk.getMinSectionY();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i < sections.length; i++) {
            int sectionY = minSectionY + i;
            if (sectionY > MAX_SECTION_Y) break;
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(MinedGeodeSurvey::isLining)) continue;
            int baseY = sectionY << 4;
            for (int y = 0; y < 16; y++) {
                int worldY = baseY + y;
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (!isLining(state)) continue;
                    int worldX = (chunkX << 4) + x;
                    int worldZ = (chunkZ << 4) + z;
                    boolean exposed = false;
                    for (Direction side : Direction.values()) {
                        int nx = worldX + side.getStepX();
                        int ny = worldY + side.getStepY();
                        int nz = worldZ + side.getStepZ();
                        if (world.isOutsideBuildHeight(ny)) continue;
                        int neighborChunkX = nx >> 4;
                        int neighborChunkZ = nz >> 4;
                        if (neighborChunkX != chunkX || neighborChunkZ != chunkZ) {
                            if (world.getChunkSource().getChunk(neighborChunkX, neighborChunkZ, ChunkStatus.FULL, false) == null) continue;
                            cursor.set(nx, ny, nz);
                            if (isOpen(world.getBlockState(cursor))) { exposed = true; break; }
                        } else {
                            cursor.set(nx, ny, nz);
                            if (isOpen(chunk.getBlockState(cursor))) { exposed = true; break; }
                        }
                    }
                    if (!exposed) continue;
                    long packed = BlockPos.asLong(worldX, worldY, worldZ);
                    lining.add(packed);
                    if (state.is(Blocks.BUDDING_AMETHYST)) budding.add(packed);
                }
            }
        }
        this.slices.put(ChunkPos.pack(chunkX, chunkZ), new Slice(ChunkPos.pack(chunkX, chunkZ),
            toLongArray(lining), toLongArray(budding), tick));
        while (this.slices.size() > MAX_CHUNKS) this.slices.remove(this.slices.keySet().iterator().next());
    }

    public synchronized void forgetChunk(int chunkX, int chunkZ) {
        this.slices.remove(ChunkPos.pack(chunkX, chunkZ));
    }

    public synchronized void clear() { this.slices.clear(); }
    public synchronized int size() { return this.slices.size(); }

    /** Recomputes source-sized, settled, dark clusters every caller-selected interval. */
    public synchronized Set<Long> candidates(ClientLevel world, long tick, SuspectServerLight serverLight) {
        if (world == null || serverLight == null || world.dimension() != Level.OVERWORLD) return Set.of();
        this.slices.keySet().removeIf(key -> world.getChunkSource().getChunk(ChunkPos.getX(key), ChunkPos.getZ(key),
            ChunkStatus.FULL, false) == null);
        List<Cluster> clusters = clusterSlices(this.slices.values());
        HashSet<Long> result = new HashSet<>();
        for (Cluster cluster : clusters) {
            if (!large(cluster) || !settled(world, cluster, tick) || !unlit(world, cluster.lining(), serverLight)) continue;
            result.add(cluster.centerChunk());
        }
        return Set.copyOf(result);
    }

    public static boolean large(Cluster cluster) {
        return cluster.budding() > 0 ? cluster.budding() >= 10 : cluster.lining().length >= 160;
    }

    /** Joins lining positions through the same four-block cells as the source scanner. */
    public static List<Cluster> clusterSlices(Collection<Slice> slices) {
        Map<Cell, ArrayList<Long>> cells = new HashMap<>();
        HashSet<Long> budding = new HashSet<>();
        for (Slice slice : slices) {
            for (long packed : slice.lining()) {
                Cell cell = cell(packed);
                cells.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(packed);
            }
            for (long packed : slice.budding()) budding.add(packed);
        }
        HashSet<Cell> seen = new HashSet<>();
        ArrayList<Cluster> clusters = new ArrayList<>();
        for (Cell start : cells.keySet()) {
            if (!seen.add(start)) continue;
            ArrayDeque<Cell> queue = new ArrayDeque<>();
            ArrayList<Long> lining = new ArrayList<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                Cell cell = queue.removeFirst();
                lining.addAll(cells.get(cell));
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    Cell next = new Cell(cell.x + dx, cell.y + dy, cell.z + dz);
                    if (cells.containsKey(next) && seen.add(next)) queue.addLast(next);
                }
            }
            clusters.add(toCluster(lining, budding));
        }
        return List.copyOf(clusters);
    }

    private static Cluster toCluster(List<Long> positions, Set<Long> budding) {
        long[] lining = toLongArray(positions);
        int count = 0, minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        long sumX = 0L, sumZ = 0L;
        for (long packed : lining) {
            if (budding.contains(packed)) count++;
            int x = BlockPos.getX(packed), z = BlockPos.getZ(packed);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            sumX += x; sumZ += z;
        }
        int centerX = (int)Math.floorDiv(sumX, lining.length);
        int centerZ = (int)Math.floorDiv(sumZ, lining.length);
        return new Cluster(lining, count, minX, minZ, maxX, maxZ, ChunkPos.pack(centerX >> 4, centerZ >> 4));
    }

    private boolean settled(ClientLevel world, Cluster cluster, long tick) {
        for (int cx = (cluster.minX() - 1) >> 4; cx <= (cluster.maxX() + 1) >> 4; cx++) {
            for (int cz = (cluster.minZ() - 1) >> 4; cz <= (cluster.maxZ() + 1) >> 4; cz++) {
                long key = ChunkPos.pack(cx, cz);
                Slice slice = this.slices.get(key);
                if (slice == null || world.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) == null
                    || tick - slice.scannedAtTick() < SETTLED_TICKS) return false;
            }
        }
        return true;
    }

    private static boolean unlit(ClientLevel world, long[] lining, SuspectServerLight serverLight) {
        HashMap<Long, Integer> peaks = new HashMap<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (long packed : lining) {
            int x = BlockPos.getX(packed), y = BlockPos.getY(packed), z = BlockPos.getZ(packed);
            for (Direction side : Direction.values()) {
                int nx = x + side.getStepX(), ny = y + side.getStepY(), nz = z + side.getStepZ();
                if (world.isOutsideBuildHeight(ny) || !world.hasChunkAt(new BlockPos(nx, ny, nz))) return false;
                cursor.set(nx, ny, nz);
                if (isGrowth(world.getBlockState(cursor))) return false;
                int light = serverLight.lightAt(world, nx, ny, nz);
                if (light > 0 && sourcePeak(world, nx, ny, nz, light, serverLight, peaks) != 14) return false;
            }
        }
        return true;
    }

    private static int sourcePeak(ClientLevel world, int x, int y, int z, int initial,
                                  SuspectServerLight serverLight, Map<Long, Integer> cache) {
        ArrayList<Long> path = new ArrayList<>();
        int cx = x, cy = y, cz = z, light = initial;
        int peak;
        while (true) {
            long key = BlockPos.asLong(cx, cy, cz);
            Integer known = cache.get(key);
            if (known != null) { peak = known; break; }
            path.add(key);
            int best = light;
            Direction uphill = null;
            for (Direction side : Direction.values()) {
                int nx = cx + side.getStepX(), ny = cy + side.getStepY(), nz = cz + side.getStepZ();
                if (world.isOutsideBuildHeight(ny) || !world.hasChunkAt(new BlockPos(nx, ny, nz))) continue;
                int value = serverLight.lightAt(world, nx, ny, nz);
                if (value > best) { best = value; uphill = side; }
            }
            if (uphill == null) { peak = light; break; }
            cx += uphill.getStepX(); cy += uphill.getStepY(); cz += uphill.getStepZ(); light = best;
        }
        for (long key : path) cache.put(key, peak);
        return peak;
    }

    private static boolean isLining(BlockState state) {
        return state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.BUDDING_AMETHYST);
    }

    private static boolean isGrowth(BlockState state) {
        return state.is(Blocks.SMALL_AMETHYST_BUD) || state.is(Blocks.MEDIUM_AMETHYST_BUD)
            || state.is(Blocks.LARGE_AMETHYST_BUD) || state.is(Blocks.AMETHYST_CLUSTER);
    }

    private static boolean isOpen(BlockState state) {
        return BuddingAmethystBlock.canClusterGrowAtState(state) || isGrowth(state);
    }

    private static Cell cell(long packed) {
        return new Cell(BlockPos.getX(packed) >> 2, BlockPos.getY(packed) >> 2, BlockPos.getZ(packed) >> 2);
    }

    private static long[] toLongArray(List<Long> values) {
        long[] result = new long[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }

    public record Slice(long chunkKey, long[] lining, long[] budding, long scannedAtTick) {
        public Slice { lining = lining.clone(); budding = budding.clone(); }
        @Override public long[] lining() { return lining.clone(); }
        @Override public long[] budding() { return budding.clone(); }
    }

    public record Cluster(long[] lining, int budding, int minX, int minZ, int maxX, int maxZ, long centerChunk) {
        public Cluster { lining = lining.clone(); }
        @Override public long[] lining() { return lining.clone(); }
    }

    private record Cell(int x, int y, int z) {}
}
