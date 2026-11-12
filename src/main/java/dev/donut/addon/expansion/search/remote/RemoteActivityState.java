package dev.donut.addon.expansion.search.remote;

public final class RemoteActivityState {
    public static final RemoteActivityInbox INBOX = new RemoteActivityInbox(8192);
    public static final RemoteActivityLedger LEDGER = new RemoteActivityLedger(4096);
    public static final RemoteLightTracker LIGHT = new RemoteLightTracker();
    public static final RemoteFrozenWitnessTracker FROZEN = new RemoteFrozenWitnessTracker();
    private RemoteActivityState() {}
}
