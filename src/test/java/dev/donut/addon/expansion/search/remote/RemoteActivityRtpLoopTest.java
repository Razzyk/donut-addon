package dev.donut.addon.expansion.search.remote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RemoteActivityRtpLoopTest {
    @Test
    void landingScansAndRelandingExtendsTheScanWithoutChangingCounters() {
        RemoteActivityRtpLoop loop = new RemoteActivityRtpLoop();
        RemoteActivityRtpLoop.Config config = config(2_000L, 3_000L, 3_000L, 0);
        loop.start(100L, config);

        assertEquals(RemoteActivityRtpLoop.Step.SEND, loop.tick(100L, config));
        loop.landed(200L, config);
        loop.relanded(1_000L, config);

        assertEquals(1, loop.sent());
        assertEquals(1, loop.landings());
        assertEquals(RemoteActivityRtpLoop.Step.NONE, loop.tick(2_999L, config));
        assertEquals(RemoteActivityRtpLoop.Step.SCAN_CLEAR, loop.tick(3_000L, config));
        assertEquals(1, loop.sent());
        assertEquals(1, loop.landings());
    }

    @Test
    void timeoutKeepsAWindowForLateLanding() {
        RemoteActivityRtpLoop loop = new RemoteActivityRtpLoop();
        RemoteActivityRtpLoop.Config config = config(2_000L, 3_000L, 3_000L, 0);
        loop.start(100L, config);

        assertEquals(RemoteActivityRtpLoop.Step.SEND, loop.tick(100L, config));
        assertEquals(RemoteActivityRtpLoop.Step.TIMED_OUT, loop.tick(3_100L, config));
        assertTrue(loop.landedLate(8_100L, config));
        assertEquals(1, loop.sent());
        assertEquals(1, loop.landings());
        assertFalse(loop.landedLate(13_101L, config));
    }

    @Test
    void cooldownRejectionRefundsAttemptButLateLandingRestoresIt() {
        RemoteActivityRtpLoop loop = new RemoteActivityRtpLoop();
        RemoteActivityRtpLoop.Config config = config(2_000L, 3_000L, 3_000L, 1);
        loop.start(100L, config);

        assertEquals(RemoteActivityRtpLoop.Step.SEND, loop.tick(100L, config));
        loop.cooldown(500L, 60_000L);
        assertEquals(RemoteActivityRtpLoop.Step.TIMED_OUT, loop.tick(2_500L, config));
        assertEquals(0, loop.sent());
        assertTrue(loop.nextSendIn(2_500L) >= 59_000L);

        assertTrue(loop.landedLate(3_000L, config));
        assertEquals(1, loop.sent());
        assertEquals(1, loop.landings());
        assertEquals(RemoteActivityRtpLoop.Phase.SCANNING, loop.phase());
    }

    @Test
    void userPauseAndDeathHoldSendingWithoutResettingSessionCounters() {
        RemoteActivityRtpLoop loop = new RemoteActivityRtpLoop();
        RemoteActivityRtpLoop.Config config = config(2_000L, 3_000L, 3_000L, 0);
        loop.start(100L, config);
        loop.holdUntil(1_000L);

        assertEquals(RemoteActivityRtpLoop.Step.NONE, loop.tick(999L, config));
        assertEquals(RemoteActivityRtpLoop.Step.SEND, loop.tick(1_000L, config));
        loop.landed(1_100L, config);
        loop.interrupt(1_200L, config);
        loop.holdUntil(4_200L);

        assertEquals(1, loop.sent());
        assertEquals(1, loop.landings());
        assertEquals(RemoteActivityRtpLoop.Step.NONE, loop.tick(4_199L, config));
        assertEquals(RemoteActivityRtpLoop.Step.SEND, loop.tick(4_200L, config));
        assertEquals(2, loop.sent());
        assertEquals(1, loop.landings());
    }

    @Test
    void maxCommandLimitAndRepeatedTimeoutStopConditionsAreDistinct() {
        RemoteActivityRtpLoop limited = new RemoteActivityRtpLoop();
        RemoteActivityRtpLoop.Config oneAttempt = config(2_000L, 3_000L, 3_000L, 1);
        limited.start(100L, oneAttempt);
        assertEquals(RemoteActivityRtpLoop.Step.SEND, limited.tick(100L, oneAttempt));
        limited.landed(200L, oneAttempt);
        assertEquals(RemoteActivityRtpLoop.Step.SCAN_CLEAR, limited.tick(2_200L, oneAttempt));
        assertEquals(RemoteActivityRtpLoop.Step.LIMIT, limited.tick(3_100L, oneAttempt));

        RemoteActivityRtpLoop failing = new RemoteActivityRtpLoop();
        RemoteActivityRtpLoop.Config unlimited = config(2_000L, 3_000L, 3_000L, 0);
        failing.start(100L, unlimited);
        long now = 100L;
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals(RemoteActivityRtpLoop.Step.SEND, failing.tick(now, unlimited));
            now += 3_000L;
            RemoteActivityRtpLoop.Step result = failing.tick(now, unlimited);
            assertEquals(attempt == 5 ? RemoteActivityRtpLoop.Step.GAVE_UP : RemoteActivityRtpLoop.Step.TIMED_OUT, result);
        }
        assertEquals(5, failing.sent());
    }

    @Test
    void configClampsUnsafeIntervalsAndAttemptCounts() {
        RemoteActivityRtpLoop.Config config = new RemoteActivityRtpLoop.Config(1L, 2L, 0L, -1);

        assertEquals(2_000L, config.scanMs());
        assertEquals(3_000L, config.delayMs());
        assertEquals(3_000L, config.timeoutMs());
        assertEquals(0, config.maxRtps());
    }

    private static RemoteActivityRtpLoop.Config config(long scanMs, long delayMs, long timeoutMs, int maxRtps) {
        return new RemoteActivityRtpLoop.Config(scanMs, delayMs, timeoutMs, maxRtps);
    }
}
