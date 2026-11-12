package dev.donut.addon.expansion.search.seed;

import java.util.Arrays;
import java.util.function.LongSupplier;

/** Incremental surface sampling; it never reads more cells than the caller's tick budget. */
public final class SeedGroundCapture {
    public enum State { READING, COMPLETE, STALE }
    public record Cell(boolean blocksMotion, boolean terrain, boolean ice) {}
    @FunctionalInterface public interface Reader { Cell read(int localX, int y, int localZ); }
    private final long revision;
    private final int[] terrain = new int[256];
    private final int[] motion = new int[256];
    private State state = State.READING;
    private int column;
    private int y = SeedScanCore.MAX_Y;
    private boolean terrainFound;
    private boolean motionFound;

    public SeedGroundCapture(long revision) {
        this.revision = revision;
        Arrays.fill(terrain, SeedScanCore.MIN_Y);
        Arrays.fill(motion, SeedScanCore.MIN_Y);
    }
    public State state() { return state; }
    public long revision() { return revision; }
    public int[] terrain() { checkComplete(); return terrain.clone(); }
    public int[] motion() { checkComplete(); return motion.clone(); }
    private void checkComplete() { if (state != State.COMPLETE) throw new IllegalStateException("Surface sample is incomplete"); }

    public State step(long currentRevision, int operations, long deadline, LongSupplier clock, Reader reader) {
        if (currentRevision != revision) return state = State.STALE;
        if (state != State.READING) return state;
        while (operations-- > 0 && clock.getAsLong() < deadline && state == State.READING) {
            Cell cell = reader.read(column & 15, y, column >> 4);
            if (cell == null) return state = State.STALE;
            if (!motionFound && cell.blocksMotion()) { motion[column] = y + 1; motionFound = true; }
            if (!terrainFound && cell.terrain()) {
                terrain[column] = cell.ice() ? SeedGroundEvidence.UNKNOWN : y + 1;
                terrainFound = true;
            }
            if (--y < SeedScanCore.MIN_Y || terrainFound && motionFound) {
                if (++column == 256) state = State.COMPLETE;
                y = SeedScanCore.MAX_Y;
                terrainFound = motionFound = false;
            }
        }
        return state;
    }
}
