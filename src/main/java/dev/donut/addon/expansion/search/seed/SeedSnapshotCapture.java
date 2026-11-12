package dev.donut.addon.expansion.search.seed;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.LongToIntFunction;
import java.util.function.LongUnaryOperator;

/** Main-thread sparse capture with explicit per-tick work limits and chunk revision validation. */
public final class SeedSnapshotCapture {
    public enum State { EXPANDING, READING, VALIDATING, COMPLETE, STALE, TOO_LARGE }
    private static final int MAX_POSITIONS = 300_000;
    private final SeedScanCore.Token token;
    private final List<SeedScanCore.PredictedCluster> clusters;
    private final LinkedHashSet<Long> positions = new LinkedHashSet<>();
    private final LinkedHashSet<Long> nativePositions = new LinkedHashSet<>();
    private final Map<Long, Long> revisions = new HashMap<>();
    private final Map<Long, Integer> nativeBlockIds = new HashMap<>();
    private final java.util.Set<Long> loadedChunks = new HashSet<>();
    private Map<Long, Byte> values = new HashMap<>();
    private State state = State.EXPANDING;
    private int clusterIndex;
    private long[] cells;
    private long[] shell;
    private int cellIndex;
    private int shellIndex;
    private int neighborIndex;
    private Iterator<Long> reading;
    private Iterator<Map.Entry<Long, Long>> validating;
    private SeedScanCore.Snapshot snapshot;

    public SeedSnapshotCapture(SeedScanCore.Token token, List<SeedScanCore.PredictedCluster> clusters) {
        this(token, clusters, List.of());
    }

    public SeedSnapshotCapture(SeedScanCore.Token token, List<SeedScanCore.PredictedCluster> clusters,
                               List<Long> nativeReads) {
        this.token = java.util.Objects.requireNonNull(token);
        this.clusters = List.copyOf(clusters);
        if (nativeReads != null) for (long position : nativeReads) {
            offerNative(position);
            int x = SeedScanCore.unpackX(position), y = SeedScanCore.unpackY(position), z = SeedScanCore.unpackZ(position);
            offerNative(SeedScanCore.packBlock(x + 1, y, z));
            offerNative(SeedScanCore.packBlock(x - 1, y, z));
            offerNative(SeedScanCore.packBlock(x, y + 1, z));
            offerNative(SeedScanCore.packBlock(x, y - 1, z));
            offerNative(SeedScanCore.packBlock(x, y, z + 1));
            offerNative(SeedScanCore.packBlock(x, y, z - 1));
        }
    }

    public State state() { return state; }
    public SeedScanCore.Snapshot snapshot() { return snapshot; }
    public List<SeedScanCore.PredictedCluster> clusters() { return clusters; }
    public Map<Long, Long> capturedRevisions() {
        if (state != State.COMPLETE) throw new IllegalStateException("Capture is not complete");
        return Map.copyOf(revisions);
    }

    public State step(SeedScanCore.Token current, int operationLimit, long deadlineNanos,
                      LongSupplier clock, LongToIntFunction readState, LongUnaryOperator revision) {
        return stepNative(current, operationLimit, deadlineNanos, clock,
            position -> new CapturedBlock(readState.applyAsInt(position), -1), revision);
    }

    public record CapturedBlock(int kind, int nativeBlockId) {}
    @FunctionalInterface public interface NativeReader { CapturedBlock read(long packedPosition); }

    public State stepNative(SeedScanCore.Token current, int operationLimit, long deadlineNanos,
                      LongSupplier clock, NativeReader reader, LongUnaryOperator revision) {
        if (!token.equals(current)) return abandon(State.STALE);
        if (terminal()) return state;
        int remaining = Math.max(0, operationLimit);
        while (remaining-- > 0 && clock.getAsLong() < deadlineNanos && !terminal()) {
            switch (state) {
                case EXPANDING -> expandOne();
                case READING -> {
                    if (!reading.hasNext()) {
                        validating = revisions.entrySet().iterator();
                        state = State.VALIDATING;
                        break;
                    }
                    long pos = reading.next();
                    long chunk = SeedScanCore.packChunk(SeedScanCore.unpackX(pos) >> 4, SeedScanCore.unpackZ(pos) >> 4);
                    long observed = revision.applyAsLong(chunk);
                    Long previous = revisions.putIfAbsent(chunk, observed);
                    if (previous != null && previous != observed) return abandon(State.STALE);
                    CapturedBlock cell = reader.read(pos);
                    int value = cell.kind();
                    if (value < 0 || value > SeedScanCore.CLAY) throw new IllegalArgumentException("Unknown snapshot cell kind");
                    if (yInJudgeBand(pos)) values.put(pos, (byte)value);
                    if (cell.nativeBlockId() >= 0) nativeBlockIds.put(pos, cell.nativeBlockId());
                    if (value != SeedScanCore.MISSING) loadedChunks.add(chunk);
                }
                case VALIDATING -> {
                    if (!validating.hasNext()) {
                        snapshot = SeedScanCore.Snapshot.takeSparseOwnership(values, nativeBlockIds, loadedChunks);
                        values = null;
                        positions.clear();
                        nativePositions.clear();
                        loadedChunks.clear();
                        nativeBlockIds.clear();
                        reading = null;
                        validating = null;
                        state = State.COMPLETE;
                        break;
                    }
                    var entry = validating.next();
                    if (revision.applyAsLong(entry.getKey()) != entry.getValue()) return abandon(State.STALE);
                }
                default -> { }
            }
        }
        return state;
    }

    private void expandOne() {
        if (clusterIndex == clusters.size()) {
            LinkedHashSet<Long> captured = new LinkedHashSet<>(positions);
            captured.addAll(nativePositions);
            reading = captured.iterator();
            state = State.READING;
            return;
        }
        if (cells == null) {
            var cluster = clusters.get(clusterIndex);
            cells = cluster.cells();
            shell = cluster.shell();
            cellIndex = shellIndex = neighborIndex = 0;
        }
        if (cellIndex < cells.length) {
            offer(cells[cellIndex++]);
        } else if (shellIndex < shell.length) {
            long anchor = shell[shellIndex];
            int dx = neighborIndex / 9 - 1;
            int dy = neighborIndex / 3 % 3 - 1;
            int dz = neighborIndex % 3 - 1;
            offer(SeedScanCore.packBlock(SeedScanCore.unpackX(anchor) + dx,
                SeedScanCore.unpackY(anchor) + dy, SeedScanCore.unpackZ(anchor) + dz));
            if (++neighborIndex == 27) { neighborIndex = 0; shellIndex++; }
        } else {
            cells = shell = null;
            clusterIndex++;
        }
    }

    private void offer(long pos) {
        int y = SeedScanCore.unpackY(pos);
        if (y < SeedScanCore.JUDGE_MIN_Y || y > SeedScanCore.JUDGE_MAX_Y) return;
        positions.add(pos);
        if (positions.size() + nativePositions.size() > MAX_POSITIONS) abandon(State.TOO_LARGE);
    }

    private void offerNative(long pos) {
        int y = SeedScanCore.unpackY(pos);
        if (y < SeedScanCore.MIN_Y || y > SeedScanCore.MAX_Y) return;
        nativePositions.add(pos);
        if (positions.size() + nativePositions.size() > MAX_POSITIONS) abandon(State.TOO_LARGE);
    }

    private static boolean yInJudgeBand(long position) {
        int y = SeedScanCore.unpackY(position);
        return y >= SeedScanCore.JUDGE_MIN_Y && y <= SeedScanCore.JUDGE_MAX_Y;
    }

    private boolean terminal() { return state == State.COMPLETE || state == State.STALE || state == State.TOO_LARGE; }

    private State abandon(State reason) {
        positions.clear();
        nativePositions.clear();
        revisions.clear();
        loadedChunks.clear();
        nativeBlockIds.clear();
        if (values != null) values.clear();
        snapshot = null;
        reading = null;
        validating = null;
        cells = shell = null;
        state = reason;
        return reason;
    }
}
