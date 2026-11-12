package dev.donut.addon.expansion.search.growth;

/** Budgeted source-order shaft walk over an immutable open-cell snapshot. */
public final class DonutHoleScanCursor {
    private final int height;
    private int x;
    private int z;
    private int y;
    private int singleRun;
    private int wideXRun;
    private int wideZRun;
    private boolean complete;
    private boolean found;

    public DonutHoleScanCursor(int height) {
        if (height <= 0) throw new IllegalArgumentException("height must be positive");
        this.height = height;
    }

    /** Advances at most {@code budget} x/z/y positions and returns positions processed. */
    public int advance(int minX, int minY, int minZ, int budget, DonutHoleSignal.OpenCell open) {
        if (budget < 0 || open == null) throw new IllegalArgumentException();
        if (this.complete || budget == 0) return 0;
        int processed = 0;
        while (processed < budget && this.x < 16) {
            int x = minX + this.x;
            int z = minZ + this.z;
            int y = minY + this.y;
            if (DonutHoleSignal.singleShaft(x, y, z, open)) this.singleRun++;
            else {
                if (DonutHoleSignal.shaftEnds(x, y, z, this.singleRun, open)) return this.found(processed + 1);
                this.singleRun = 0;
            }
            if (DonutHoleSignal.wideShaft(x, y, z, 1, 0, open)) this.wideXRun++;
            else {
                if (DonutHoleSignal.shaftEnds(x, y, z, this.wideXRun, open)) return this.found(processed + 1);
                this.wideXRun = 0;
            }
            if (DonutHoleSignal.wideShaft(x, y, z, 0, 1, open)) this.wideZRun++;
            else {
                if (DonutHoleSignal.shaftEnds(x, y, z, this.wideZRun, open)) return this.found(processed + 1);
                this.wideZRun = 0;
            }
            this.y++;
            if (this.y >= this.height) {
                this.y = 0;
                this.singleRun = 0;
                this.wideXRun = 0;
                this.wideZRun = 0;
                if (++this.z >= 16) {
                    this.z = 0;
                    this.x++;
                }
            }
            processed++;
        }
        if (this.x >= 16) this.complete = true;
        return processed;
    }

    public boolean complete() { return this.complete; }
    public boolean found() { return this.found; }
    public int visitedColumns() { return this.x * 16 + this.z; }

    private int found(int processed) {
        this.found = true;
        this.complete = true;
        return processed;
    }
}
