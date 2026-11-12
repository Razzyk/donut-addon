package dev.donut.addon.expansion.search.growth;

import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LayerLightEventListener;

/** Bounded server block-light snapshots used to correlate geode growth and stale local light. */
public final class SuspectServerLight {
    public static final int UNKNOWN = -1;
    private static final int MAX_COLUMNS = 1024;
    private static final int MAX_TOUCHED_POSITIONS = 8192;
    private static final int MAX_STORED_SECTION_Y = 3;
    private static final byte[] ZERO_SECTION = new byte[0];
    private final LinkedHashMap<Long, Column> columns = new LinkedHashMap<>(128, 0.75f, true);

    /** Replaces a full chunk-light snapshot or merges a light update, copying packet byte arrays. */
    public synchronized void applyPacket(int chunkX, int chunkZ, int minSection, int sectionCount,
                                         BitSet present, BitSet empty, List<byte[]> updates,
                                         boolean replaceChunk) {
        if (sectionCount < 0 || present == null || empty == null || updates == null) {
            throw new IllegalArgumentException("invalid block-light packet summary");
        }
        long key = ChunkPos.pack(chunkX, chunkZ);
        Column previous = replaceChunk ? null : this.columns.get(key);
        byte[][] sections = previous != null && previous.minSection == minSection
            && previous.sections.length == sectionCount ? previous.sections.clone() : new byte[sectionCount][];
        Iterator<byte[]> data = updates.iterator();
        for (int i = 0; i < sectionCount; i++) {
            boolean retained = minSection + i <= MAX_STORED_SECTION_Y;
            if (present.get(i)) {
                byte[] bytes = data.hasNext() ? data.next() : null;
                sections[i] = retained && bytes != null && bytes.length == 2048 ? bytes.clone() : null;
            } else if (empty.get(i)) {
                sections[i] = retained ? ZERO_SECTION : null;
            } else if (!retained) {
                sections[i] = null;
            }
        }
        Column next = new Column(minSection, sections);
        this.columns.put(key, next);
        trim();
    }

    /** Lazily seeds a snapshot from the client light engine when no server packet has arrived. */
    public synchronized boolean captureClient(ClientLevel level, int chunkX, int chunkZ) {
        if (level == null) return false;
        long key = ChunkPos.pack(chunkX, chunkZ);
        if (this.columns.containsKey(key)) return true;
        if (level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) == null) return false;
        var engine = level.getLightEngine();
        int minSection = engine.getMinLightSection();
        int count = engine.getLightSectionCount();
        byte[][] sections = new byte[count][];
        LayerLightEventListener listener = engine.getLayerListener(LightLayer.BLOCK);
        for (int i = 0; i < count && minSection + i <= MAX_STORED_SECTION_Y; i++) {
            DataLayer layer = listener.getDataLayerData(SectionPos.of(chunkX, minSection + i, chunkZ));
            if (layer != null) sections[i] = layer.isEmpty() ? ZERO_SECTION : layer.getData().clone();
        }
        this.columns.put(key, new Column(minSection, sections));
        trim();
        return true;
    }

    /** A local block change invalidates that exact server-light cell until a fresh light packet arrives. */
    public synchronized void blockChanged(BlockPos pos) {
        if (pos == null || pos.getY() > 63) return;
        Column column = this.columns.get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
        if (column == null || column.unknown) return;
        if (column.touched.size() >= MAX_TOUCHED_POSITIONS && !column.touched.contains(pos.asLong())) {
            column.unknown = true;
            column.touched.clear();
            return;
        }
        column.touched.add(pos.asLong());
    }

    /** Reads the latest known block-light nibble; -1 means the packet or client layer is unavailable. */
    public synchronized int lightAt(ClientLevel level, int x, int y, int z) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        long key = ChunkPos.pack(chunkX, chunkZ);
        Column column = this.columns.get(key);
        if (column == null && this.captureClient(level, chunkX, chunkZ)) column = this.columns.get(key);
        return read(column, x, y, z);
    }

    /** Reads only retained packet or locally captured state and never consults a live level. */
    public synchronized int storedLightAt(int x, int y, int z) {
        return read(this.columns.get(ChunkPos.pack(x >> 4, z >> 4)), x, y, z);
    }

    private static int read(Column column, int x, int y, int z) {
        if (column == null || column.unknown || column.touched.contains(BlockPos.asLong(x, y, z))) return UNKNOWN;
        int index = (y >> 4) - column.minSection;
        if (index < 0 || index >= column.sections.length) return UNKNOWN;
        byte[] section = column.sections[index];
        if (section == null) return UNKNOWN;
        if (section == ZERO_SECTION) return 0;
        int cell = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
        return (section[cell >> 1] >> ((cell & 1) << 2)) & 15;
    }

    public synchronized void forgetChunk(int chunkX, int chunkZ) { this.columns.remove(ChunkPos.pack(chunkX, chunkZ)); }

    public synchronized void forgetUnloaded(ClientLevel level) {
        if (level == null) { this.clear(); return; }
        this.columns.keySet().removeIf(key -> level.getChunkSource().getChunk(ChunkPos.getX(key), ChunkPos.getZ(key),
            ChunkStatus.FULL, false) == null);
    }

    public synchronized void clear() { this.columns.clear(); }
    public synchronized int size() { return this.columns.size(); }

    private void trim() {
        while (this.columns.size() > MAX_COLUMNS) this.columns.remove(this.columns.keySet().iterator().next());
    }

    private static final class Column {
        private final int minSection;
        private final byte[][] sections;
        private final Set<Long> touched = new HashSet<>();
        private boolean unknown;

        private Column(int minSection, byte[][] sections) {
            this.minSection = minSection;
            this.sections = sections;
        }
    }
}
