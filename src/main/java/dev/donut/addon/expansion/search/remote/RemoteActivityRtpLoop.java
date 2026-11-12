package dev.donut.addon.expansion.search.remote;

/** Client-thread state machine for Auto RTP, ported from the static reference behavior. */
final class RemoteActivityRtpLoop {
    static final long MIN_DELAY_MS = 3_000L;
    static final long MIN_SCAN_MS = 2_000L;
    static final long MIN_TIMEOUT_MS = 3_000L;
    static final long COOLDOWN_MARGIN_MS = 1_000L;
    static final long MAX_COOLDOWN_MS = 900_000L;
    static final long MAX_WARMUP_MS = 60_000L;
    static final long SETTLE_MS = 1_500L;
    static final long MAX_SETTLE_MS = 8_000L;
    static final long LATE_LANDING_MS = 5_000L;
    static final int MAX_FAILS_IN_ROW = 5;
    private static final long NEVER = Long.MIN_VALUE;

    private Phase phase = Phase.OFF;
    private int sent;
    private int landings;
    private int failsInRow;
    private boolean rejected;
    private boolean tryLanded;
    private boolean tryRefunded;
    private long lastSendAt = NEVER;
    private long cooldownUntil;
    private long nextSendAt;
    private long holdUntil;
    private long deadline;
    private long scanUntil;
    private long waitingSince;
    private long tryEndedAt = NEVER;
    private long lastDataAt = NEVER;

    void start(long now, Config config) {
        if (this.running()) return;
        this.phase = Phase.WAITING;
        this.sent = 0;
        this.landings = 0;
        this.failsInRow = 0;
        this.rejected = false;
        this.tryLanded = false;
        this.tryRefunded = false;
        this.nextSendAt = this.lastSendAt == NEVER ? now : Math.max(now, this.lastSendAt + config.delayMs());
        this.holdUntil = 0L;
        this.waitingSince = now;
        this.tryEndedAt = NEVER;
        this.lastDataAt = NEVER;
    }

    void stop() { this.phase = Phase.OFF; }
    void forgetCooldown() { this.cooldownUntil = 0L; }

    Step tick(long now, Config config) {
        return switch (this.phase) {
            case WAITING -> {
                if (now < this.sendAt()) yield Step.NONE;
                if (config.maxRtps() > 0 && this.sent >= config.maxRtps()) yield Step.LIMIT;
                this.sent++;
                this.lastSendAt = now;
                this.deadline = now + config.timeoutMs();
                this.rejected = false;
                this.tryLanded = false;
                this.tryRefunded = false;
                this.phase = Phase.TELEPORTING;
                yield Step.SEND;
            }
            case TELEPORTING -> {
                if (now < this.deadline) yield Step.NONE;
                if (this.rejected) {
                    this.sent = Math.max(0, this.sent - 1);
                    this.tryRefunded = true;
                } else {
                    if (this.failsInRow + 1 >= MAX_FAILS_IN_ROW) {
                        this.failsInRow = MAX_FAILS_IN_ROW;
                        yield Step.GAVE_UP;
                    }
                    this.failsInRow++;
                }
                this.toWaiting(now, config);
                yield Step.TIMED_OUT;
            }
            case SCANNING -> {
                if (now < this.scanUntil) yield Step.NONE;
                this.toWaiting(now, config);
                yield Step.SCAN_CLEAR;
            }
            default -> Step.NONE;
        };
    }

    void landed(long now, Config config) {
        if (this.phase != Phase.TELEPORTING) return;
        this.phase = Phase.SCANNING;
        this.landings++;
        this.failsInRow = 0;
        this.rejected = false;
        this.tryLanded = true;
        this.scanUntil = now + config.scanMs();
    }

    void relanded(long now, Config config) {
        if (this.phase == Phase.SCANNING) this.scanUntil = Math.max(this.scanUntil, now + config.scanMs());
    }

    boolean landedLate(long now, Config config) {
        if (this.phase != Phase.WAITING || this.tryEndedAt == NEVER || now - this.tryEndedAt > LATE_LANDING_MS) return false;
        if (!this.tryLanded) {
            this.landings++;
            if (this.tryRefunded) this.sent++;
        }
        this.phase = Phase.SCANNING;
        this.failsInRow = 0;
        this.rejected = false;
        this.tryLanded = true;
        this.tryRefunded = false;
        this.tryEndedAt = NEVER;
        this.scanUntil = now + config.scanMs();
        return true;
    }

    void cooldown(long now, long millis) {
        if (!this.running()) return;
        this.nextUseIn(now, millis);
        if (this.phase == Phase.TELEPORTING) {
            this.rejected = true;
            this.deadline = Math.min(this.deadline, now + 2_000L);
        }
    }

    void nextUseIn(long now, long millis) {
        if (this.running()) {
            long wait = Math.min(MAX_COOLDOWN_MS, Math.max(0L, millis)) + COOLDOWN_MARGIN_MS;
            this.cooldownUntil = Math.max(this.cooldownUntil, now + wait);
        }
    }

    void warmup(long now, long millis, Config config) {
        if (this.phase == Phase.TELEPORTING && !this.rejected) {
            this.deadline = Math.max(this.deadline, now + Math.min(MAX_WARMUP_MS, Math.max(0L, millis)) + config.timeoutMs());
        }
    }

    void extend(long now, Config config) {
        if (this.phase == Phase.TELEPORTING) this.deadline = Math.max(this.deadline, now + config.timeoutMs());
    }

    void cancelled(long now) {
        if (this.phase == Phase.TELEPORTING && !this.rejected) this.deadline = Math.min(this.deadline, now);
    }

    void interrupt(long now, Config config) {
        if (this.phase == Phase.TELEPORTING || this.phase == Phase.SCANNING) this.toWaiting(now, config);
    }

    void holdUntil(long until) { this.holdUntil = Math.max(this.holdUntil, until); }
    void dataArrived(long now) { this.lastDataAt = now; }
    Phase phase() { return this.phase; }
    boolean running() { return this.phase != Phase.OFF; }
    boolean awaitingTeleport() { return this.phase == Phase.TELEPORTING && !this.rejected; }
    int sent() { return this.sent; }
    int landings() { return this.landings; }
    boolean sentWithin(long now, long millis) { return this.lastSendAt != NEVER && now - this.lastSendAt <= millis; }
    long nextSendIn(long now) { return Math.max(0L, this.sendAt() - now); }
    boolean settling(long now) { return this.phase == Phase.WAITING && now >= this.readyAt() && now < this.settleUntil(); }
    long cooldownLeft(long now) { return Math.max(0L, this.cooldownUntil - now); }
    long scanLeft(long now) { return this.phase == Phase.SCANNING ? Math.max(0L, this.scanUntil - now) : 0L; }
    boolean lateLandingPending(long now) { return this.phase == Phase.WAITING && this.tryEndedAt != NEVER && now - this.tryEndedAt <= LATE_LANDING_MS; }

    private void toWaiting(long now, Config config) {
        this.phase = Phase.WAITING;
        this.rejected = false;
        this.nextSendAt = Math.max(now, this.lastSendAt + config.delayMs());
        this.waitingSince = now;
        this.tryEndedAt = now;
    }

    private long readyAt() {
        long ready = Math.max(this.nextSendAt, Math.max(this.holdUntil, this.cooldownUntil));
        return this.lastSendAt == NEVER ? ready : Math.max(ready, this.lastSendAt + MIN_DELAY_MS);
    }

    private long settleUntil() {
        return this.lastDataAt == NEVER ? 0L : Math.min(this.lastDataAt + SETTLE_MS, this.waitingSince + MAX_SETTLE_MS);
    }

    private long sendAt() { return Math.max(this.readyAt(), this.settleUntil()); }

    record Config(long scanMs, long delayMs, long timeoutMs, int maxRtps) {
        Config {
            scanMs = Math.max(MIN_SCAN_MS, scanMs);
            delayMs = Math.max(MIN_DELAY_MS, delayMs);
            timeoutMs = Math.max(MIN_TIMEOUT_MS, timeoutMs);
            maxRtps = Math.max(0, maxRtps);
        }
    }

    enum Phase { OFF, WAITING, TELEPORTING, SCANNING }
    enum Step { NONE, SEND, TIMED_OUT, SCAN_CLEAR, LIMIT, GAVE_UP }
}
