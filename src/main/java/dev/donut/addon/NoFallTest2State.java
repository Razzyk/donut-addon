package dev.donut.addon;

/** The Test 2 state machine, separated from game hooks so its landing timing can be tested. */
public final class NoFallTest2State {
    public record Correction(double y, boolean restoreHorizontal) {}
    public static final double DELTA_Y = 9e-8;
    private double groundHeight = Double.NEGATIVE_INFINITY;
    private double safeDistance;
    private double snapshotY;
    private boolean snapshotGround;
    private boolean testFlag;
    private boolean setbackResponse;
    private int counter;

    public void reset() {
        groundHeight = Double.NEGATIVE_INFINITY;
        testFlag = false;
        setbackResponse = false;
        counter = 0;
    }

    public void beforeTick(double y, boolean grounded, boolean fallReset, double safeDistance) {
        snapshotY = y;
        snapshotGround = grounded;
        this.safeDistance = safeDistance;
        if (grounded || fallReset || y > groundHeight) {
            groundHeight = y;
            counter = 0;
        }
        if (++counter > 100) setbackResponse = false;
    }

    public void onSetback() {
        setbackResponse = true;
    }

    /** Null lets vanilla send its usual movement. */
    public Correction beforeNetwork(double currentY, boolean grounded) {
        boolean shouldCheck = currentY <= groundHeight - safeDistance;
        if (testFlag && setbackResponse && grounded) {
            testFlag = false;
            setbackResponse = false;
            return new Correction(snapshotY + 9e-5, false);
        }
        if (shouldCheck && grounded && !snapshotGround) {
            groundHeight = currentY;
            testFlag = true;
            return new Correction(snapshotY + DELTA_Y, true);
        }
        return null;
    }
}
