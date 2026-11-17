package dev.donut.addon.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StartupThreatCheckTest {
    @Test void launchNeverWaitsForScanAndWorldReadinessDoesNotLoseTheResult() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var runs = new AtomicInteger();
        try (var check = new StartupThreatCheck<>(() -> {
            runs.incrementAndGet();
            entered.countDown();
            try { assertTrue(finish.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
            return "finding";
        }, ignored -> fail("scan unexpectedly failed"))) {
            check.start();
            check.start();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertNull(check.poll(true), "joining before completion must not block or consume the alert");
            finish.countDown();
            assertNull(check.poll(false), "main menu must not consume the alert");
            assertEquals("finding", awaitNotice(check));
            assertNull(check.poll(true), "later ticks, dimensions and joins must not repeat the notice");
            check.start();
            assertEquals(1, runs.get());
        }
    }

    @Test void failureIsReportedAndNeverBecomesAnAllClearResult() throws Exception {
        var failed = new CountDownLatch(1);
        try (var check = new StartupThreatCheck<>(() -> {
            throw new IllegalStateException("unreadable");
        }, ignored -> failed.countDown())) {
            check.start();
            assertTrue(failed.await(3, TimeUnit.SECONDS));
            assertNull(check.poll(true));
        }
    }

    @Test void closingCancelsWorkAndPreventsLaterNotifications() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var check = new StartupThreatCheck<>(() -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) { interrupted.countDown(); }
            return "late finding";
        }, ignored -> fail("cancelled scans should not produce failure notices"));
        try {
            check.start();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            check.close();
            check.close();
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            assertNull(check.poll(true));
            check.start();
            assertNull(check.poll(true));
        } finally { check.close(); }
    }

    private static <T> T awaitNotice(StartupThreatCheck<T> check) {
        return assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            T notice;
            while ((notice = check.poll(true)) == null) Thread.sleep(1);
            return notice;
        });
    }
}
