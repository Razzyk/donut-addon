package dev.donut.addon;

/** Keeps a reset transaction pending until the server stops glide, or recovery is needed. */
public final class GlideResetState {
    private boolean pending;
    private boolean acknowledged;
    private int slot = -1;
    private int startedTick;

    public boolean begin(int tick, int slot) {
        if (pending) return false;
        pending = true;
        acknowledged = false;
        this.slot = slot;
        startedTick = tick;
        return true;
    }

    public boolean serverStopped() {
        if (!pending) return false;
        acknowledged = true;
        return true;
    }

    public boolean needsRestore(int tick, boolean enabled, boolean canContinue, boolean gliding) {
        return pending && (acknowledged || tick - startedTick >= 40 || !enabled || !canContinue || !gliding);
    }

    public boolean isPending() { return pending; }
    public int slot() { return slot; }

    public void finish() {
        pending = false;
        acknowledged = false;
        slot = -1;
    }
}
