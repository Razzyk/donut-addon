package dev.donut.addon;

/** Tracks server-requested glide restarts independently of client-predicted flight. */
public final class AutoReflyState {
    private static final int RETRY_TICKS = 5;
    private boolean pending;
    private boolean needsAttempt;
    private boolean attempted;
    private int lastAttempt;

    public void reset() {
        pending = false;
        needsAttempt = false;
        attempted = false;
    }

    public void serverFlag(boolean enabled, boolean flying, boolean wasGliding) {
        if (!enabled) {
            reset();
        } else if (flying) {
            // Keep the cooldown even if another stop follows immediately.
            pending = false;
            needsAttempt = false;
        } else if (wasGliding || pending) {
            pending = true;
            needsAttempt = true;
        }
    }

    /** A pending inventory swap must finish before chest eligibility is checked. */
    public boolean shouldStart(int tick, boolean enabled, boolean canContinue,
                               boolean usableChest, boolean transactionPending, boolean gliding) {
        if (!enabled || !canContinue) {
            reset();
            return false;
        }
        if (!pending || transactionPending) return false;
        if (!usableChest) {
            reset();
            return false;
        }
        // The old local glide bit may have been preserved during a swap. Only
        // accept local flight after a start for the latest observed server stop.
        if (!needsAttempt && gliding) {
            pending = false;
            return false;
        }
        if (attempted && tick - lastAttempt < RETRY_TICKS) return false;
        attempted = true;
        lastAttempt = tick;
        needsAttempt = false;
        return true;
    }

    public boolean isPending() { return pending; }
}
