package dev.donut.addon.modules;

/** Tracks the one-tick input release between vanilla block breaks. */
final class SpeedmineReleaseState {
    private boolean releasePending;
    private boolean restartPending;

    /**
     * Returns true only when the completion hook should clear vanilla's five-tick delay.
     * Block progress and packets remain entirely under vanilla control.
     */
    boolean onBlockCompleted(boolean enabled, boolean attackHeld) {
        if (!enabled || !attackHeld) {
            releasePending = false;
            restartPending = false;
            return false;
        }

        releasePending = true;
        restartPending = false;
        return true;
    }

    /**
     * Gates exactly one held-attack continuation. The next held call is passed through,
     * allowing vanilla to start the newly targeted block from zero progress.
     */
    boolean filterAttack(boolean enabled, boolean attackHeld) {
        if (!enabled) {
            reset();
            return attackHeld;
        }

        if (!attackHeld) {
            // The player already released the mouse; that satisfies the release cycle.
            releasePending = false;
            restartPending = false;
            return false;
        }

        if (releasePending) {
            releasePending = false;
            restartPending = true;
            return false;
        }

        if (restartPending) {
            // Keep the old continuation path idle until the vanilla START path succeeds.
            return false;
        }

        return true;
    }

    boolean shouldRestart(boolean enabled, boolean attackHeld) {
        if (!enabled || !attackHeld) {
            if (!attackHeld) restartPending = false;
            return false;
        }
        return restartPending;
    }

    void restartStarted() {
        restartPending = false;
    }

    void reset() {
        releasePending = false;
        restartPending = false;
    }
}
