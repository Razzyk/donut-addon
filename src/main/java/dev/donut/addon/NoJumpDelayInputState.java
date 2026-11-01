package dev.donut.addon;

/** Tracks only a held jump that began on the ground, leaving airborne presses alone. */
public final class NoJumpDelayInputState {
    private boolean groundedHold;

    public boolean filter(boolean enabled, boolean jumping, boolean onGround, boolean exempt) {
        if (!enabled || !jumping || exempt) {
            groundedHold = false;
            return jumping;
        }
        if (onGround) {
            groundedHold = true;
            return true;
        }
        return !groundedHold;
    }
}
