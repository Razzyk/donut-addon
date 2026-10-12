package dev.donut.addon.mv.compat.events;

public class Cancellable {
    private boolean cancelled;
    public void cancel() { cancelled = true; }
    public void setCancelled(boolean value) { cancelled = value; }
    public boolean isCancelled() { return cancelled; }
}
