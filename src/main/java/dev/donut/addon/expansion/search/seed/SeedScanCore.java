package dev.donut.addon.expansion.search.seed;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Immutable seed inputs, generation ownership, and conservative cluster evidence checks. */
public final class SeedScanCore {
    public static final int MIN_Y = -64;
    public static final int MAX_Y = 319;
    public static final int JUDGE_MIN_Y = -1;
    public static final int JUDGE_MAX_Y = 31;
    public static final int CELLS_PER_CHUNK_LAYER = 256;
    private static final int JUDGE_LAYERS = JUDGE_MAX_Y - JUDGE_MIN_Y + 1;
    public static final byte AIR = 0;
    public static final byte NATURAL = 1;
    public static final byte GRANITE = 2;
    public static final byte DIORITE = 3;
    public static final byte ANDESITE = 4;
    public static final byte GRAVEL = 5;
    public static final byte ORE = 6;
    public static final byte STRUCTURE = 7;
    public static final byte MODIFIED = 8;
    public static final byte MISSING = 9;
    public static final byte STONE = 10;
    public static final byte TUFF = 11;
    public static final byte DIRT = 12;
    public static final byte CLAY = 13;

    private SeedScanCore() {}

    /** Matches the game's seed input rule: decimal long first, otherwise trimmed text hash. */
    public static OptionalLong parseSeed(String text) {
        if (text == null || text.trim().isEmpty()) return OptionalLong.empty();
        String value = text.trim();
        try {
            return OptionalLong.of(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return OptionalLong.of(value.hashCode());
        }
    }

    public enum Verdict { ALERT, CLEAN, OPEN, STRUCTURE, TERRAIN, HIDDEN, MISPREDICTED, TOO_FEW, INCOMPLETE }

    public record Token(long generation, long worldSession, long seed) {}

    /** A result token is accepted only while the same seed and world session remain current. */
    public static final class GenerationGate {
        private final AtomicLong serial = new AtomicLong();
        private final AtomicReference<Token> current = new AtomicReference<>();

        public Token begin(long worldSession, long seed) {
            Token token = new Token(this.serial.incrementAndGet(), worldSession, seed);
            this.current.set(token);
            return token;
        }

        public void invalidate() {
            this.serial.incrementAndGet();
            this.current.set(null);
        }

        public Token current() { return this.current.get(); }

        public boolean accepts(Token token) {
            return token != null && token.equals(this.current.get());
        }
    }

    /** A seed-generated cluster and its surrounding cells, expressed in packed world positions. */
    public record PredictedCluster(byte kind, long owner, long[] cells, long[] shell,
                                   byte[] shellModel, boolean generationComplete,
                                   long[] certainCells, byte[] shellFlags,
                                   List<SeedFeatureTrace.Probe> writerProbes) {
        public PredictedCluster(byte kind, long owner, long[] cells, long[] shell) {
            this(kind, owner, cells, shell, defaultShellModel(shell), true, cells, null, List.of());
        }
        public PredictedCluster(byte kind, long owner, long[] cells, long[] shell, boolean generationComplete) {
            this(kind, owner, cells, shell, defaultShellModel(shell), generationComplete, cells, null, List.of());
        }
        public PredictedCluster(byte kind, long owner, long[] cells, long[] shell, byte[] shellModel, boolean generationComplete) {
            this(kind, owner, cells, shell, shellModel, generationComplete, cells, null, List.of());
        }
        public PredictedCluster {
            cells = cells == null ? new long[0] : Arrays.copyOf(cells, cells.length);
            shell = shell == null ? new long[0] : Arrays.copyOf(shell, shell.length);
            if (shellModel == null || shellModel.length != shell.length) shellModel = defaultShellModel(shell);
            else shellModel = Arrays.copyOf(shellModel, shellModel.length);
            certainCells = certainCells == null ? Arrays.copyOf(cells, cells.length) : Arrays.copyOf(certainCells, certainCells.length);
            shellFlags = shellFlags == null ? null : Arrays.copyOf(shellFlags, shellFlags.length);
            if (shellFlags != null && shellFlags.length != shell.length) throw new IllegalArgumentException("Shell flags must match shell cells");
            writerProbes = writerProbes == null ? List.of() : List.copyOf(writerProbes);
        }
        @Override public long[] cells() { return Arrays.copyOf(this.cells, this.cells.length); }
        @Override public long[] shell() { return Arrays.copyOf(this.shell, this.shell.length); }
        @Override public byte[] shellModel() { return Arrays.copyOf(this.shellModel, this.shellModel.length); }
        @Override public long[] certainCells() { return Arrays.copyOf(this.certainCells, this.certainCells.length); }
        @Override public byte[] shellFlags() { return this.shellFlags == null ? null : Arrays.copyOf(this.shellFlags, this.shellFlags.length); }

        private static byte[] defaultShellModel(long[] shell) {
            byte[] model = new byte[shell == null ? 0 : shell.length];
            Arrays.fill(model, STONE);
            return model;
        }
    }

    /** Compact immutable view captured from loaded chunks on the client thread. */
    public static final class Snapshot {
        private final Map<Long, byte[]> chunks;
        private final Map<Long, Byte> sparse;
        private final java.util.Set<Long> sparseChunkKeys;
        private final Map<Long, Integer> nativeBlockIds;

        public Snapshot(Map<Long, byte[]> chunks) {
            Map<Long, byte[]> copy = new HashMap<>();
            if (chunks != null) chunks.forEach((key, value) -> {
                if (key != null && value != null && value.length == JUDGE_LAYERS * CELLS_PER_CHUNK_LAYER) {
                    copy.put(key, Arrays.copyOf(value, value.length));
                }
            });
            this.chunks = Map.copyOf(copy);
            this.sparse = null;
            this.sparseChunkKeys = java.util.Set.of();
            this.nativeBlockIds = Map.of();
        }

        private Snapshot(Map<Long, Byte> ownedCells, Map<Long, Integer> nativeBlockIds, java.util.Set<Long> coveredChunks) {
            this.chunks = Map.of();
            this.sparse = java.util.Collections.unmodifiableMap(ownedCells);
            this.sparseChunkKeys = java.util.Set.copyOf(coveredChunks);
            this.nativeBlockIds = Map.copyOf(nativeBlockIds);
        }

        /** Internal handoff: the bounded collector relinquishes its only mutable reference. */
        static Snapshot takeSparseOwnership(Map<Long, Byte> ownedCells, Map<Long, Integer> nativeBlockIds,
                                            java.util.Set<Long> coveredChunks) {
            return new Snapshot(ownedCells, nativeBlockIds, coveredChunks);
        }

        public byte at(int x, int y, int z) {
            if (y < JUDGE_MIN_Y || y > JUDGE_MAX_Y) return NATURAL;
            if (this.sparse != null) return this.sparse.getOrDefault(packBlock(x, y, z), MISSING);
            long key = packChunk(x >> 4, z >> 4);
            byte[] cells = this.chunks.get(key);
            if (cells == null) return MISSING;
            int local = (y - JUDGE_MIN_Y) * CELLS_PER_CHUNK_LAYER + (z & 15) * 16 + (x & 15);
            return cells[local];
        }

        public boolean hasChunk(int chunkX, int chunkZ) {
            if (this.sparse == null) return this.chunks.containsKey(packChunk(chunkX, chunkZ));
            return this.sparseChunkKeys.contains(packChunk(chunkX, chunkZ));
        }

        /** Registry block id captured for exact native ore-target and air replay; -1 means unknown. */
        public int nativeBlockIdAt(int x, int y, int z) {
            return nativeBlockIds.getOrDefault(packBlock(x, y, z), -1);
        }

        private boolean nearOpen(int x, int y, int z) {
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                byte state = this.at(x + dx, y + dy, z + dz);
                if (state == AIR || state == MISSING) return true;
            }
            return false;
        }
    }

    /** Source-style conservative verdict: boundary gaps and cave exposure never become alerts. */
    public static Verdict judge(PredictedCluster candidate, Snapshot shown, int minimumChanges) {
        if (candidate == null || shown == null || !candidate.generationComplete || candidate.cells.length == 0) return Verdict.INCOMPLETE;
        int matches = 0, judged = 0, fakes = 0, changes = 0;
        for (long packed : candidate.cells) {
            int x = unpackX(packed), y = unpackY(packed), z = unpackZ(packed);
            byte state = shown.at(x, y, z);
            if (state == MISSING) return Verdict.INCOMPLETE;
            if (state == AIR) return Verdict.OPEN;
            if (state == STRUCTURE) return Verdict.STRUCTURE;
        }
        for (long packed : candidate.certainCells) {
            int x = unpackX(packed), y = unpackY(packed), z = unpackZ(packed);
            byte state = shown.at(x, y, z);
            if (state == MISSING) return Verdict.INCOMPLETE;
            if (state == ORE) fakes++;
            else if (state != NATURAL) {
                judged++;
                if (state == candidate.kind) matches++;
                else changes++;
            }
        }
        byte[] shellModel = candidate.shellModel;
        byte[] shellFlags = candidate.shellFlags;
        int filled = 0;
        int outsideChanges = 0;
        for (int i = 0; i < candidate.shell.length; i++) {
            long packed = candidate.shell[i];
            byte state = shown.at(unpackX(packed), unpackY(packed), unpackZ(packed));
            if (state == MISSING) return Verdict.INCOMPLETE;
            if (state == AIR) return Verdict.OPEN;
            if (state == STRUCTURE) return Verdict.STRUCTURE;
            int x = unpackX(packed), y = unpackY(packed), z = unpackZ(packed);
            byte expected = shellModel[i];
            if (shellFlags == null) {
                if (expected == AIR) filled++;
                if (y < 0 || y > 30 || expected == AIR) continue;
                if (isFamily(state)) {
                    if (state != expected && (state != GRAVEL || !shown.nearOpen(x, y, z))) outsideChanges++;
                } else if (expected == STONE && state != STONE && state != ORE && state != NATURAL &&
                    !shown.nearOpen(x, y, z)) outsideChanges++;
            } else {
                int flags = Byte.toUnsignedInt(shellFlags[i]);
                if ((flags & 16) != 0) filled++;
                if ((flags & 32) == 0 || (flags & 16) != 0) continue;
                if (isFamily(state)) {
                    int family = familyBit(state);
                    if ((flags & family) == 0 && (state != GRAVEL || !shown.nearOpen(x, y, z))) outsideChanges++;
                } else if ((flags & 64) != 0 && state != STONE && state != ORE && state != NATURAL &&
                    !shown.nearOpen(x, y, z)) outsideChanges++;
            }
        }
        if (filled > 3) return Verdict.TERRAIN;
        if (judged + fakes > 0 && fakes * 10 > (judged + fakes) * 3) return Verdict.HIDDEN;
        if (judged < 8) return Verdict.TOO_FEW;
        if (matches < judged * 0.60) return Verdict.MISPREDICTED;
        changes += outsideChanges;
        return changes >= Math.max(1, minimumChanges) ? Verdict.ALERT : Verdict.CLEAN;
    }

    private static boolean isFamily(byte value) {
        return value == GRANITE || value == DIORITE || value == ANDESITE || value == GRAVEL;
    }

    private static int familyBit(byte value) {
        return switch (value) {
            case GRANITE -> 1;
            case DIORITE -> 2;
            case ANDESITE -> 4;
            case GRAVEL -> 8;
            default -> 0;
        };
    }

    public static long packChunk(int x, int z) { return ((long)x & 0xffffffffL) << 32 | ((long)z) & 0xffffffffL; }
    public static long packBlock(int x, int y, int z) { return ((long)(x & 0x3ffffff) << 38) | ((long)(z & 0x3ffffff) << 12) | (y & 0xfffL); }
    public static int unpackX(long packed) { return (int)(packed >> 38); }
    public static int unpackY(long packed) { return (int)(packed << 52 >> 52); }
    public static int unpackZ(long packed) { return (int)(packed << 26 >> 38); }
}
