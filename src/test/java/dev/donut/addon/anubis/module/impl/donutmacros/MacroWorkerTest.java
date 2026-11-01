package dev.donut.addon.anubis.module.impl.donutmacros;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MacroWorkerTest {
    @Test void cancellingDoesNotExecuteAnAlreadyQueuedInventoryAction() throws Exception {
        var queue = new LinkedBlockingQueue<Runnable>();
        var worker = new MacroWorker("cancel-test", queue::add);
        var actions = new AtomicInteger();
        var finished = new CountDownLatch(1);
        worker.start(() -> {
            try { worker.run(actions::incrementAndGet); }
            finally { finished.countDown(); }
        }, failure -> fail(failure));
        try {
            Runnable scheduled = queue.poll(2, TimeUnit.SECONDS);
            assertNotNull(scheduled);
            worker.stop();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            scheduled.run();
            assertEquals(0, actions.get());
        } finally { worker.stop(); }
    }

    @Test void restartingInvalidatesOldActionsAndAllowsTheNewRun() throws Exception {
        var queue = new LinkedBlockingQueue<Runnable>();
        var worker = new MacroWorker("restart-test", queue::add);
        var actions = new AtomicInteger();
        var finished = new CountDownLatch(1);
        worker.start(() -> worker.run(() -> actions.addAndGet(100)), failure -> fail(failure));
        try {
            Runnable old = queue.poll(2, TimeUnit.SECONDS);
            assertNotNull(old);
            worker.start(() -> {
                try { worker.run(actions::incrementAndGet); }
                finally { finished.countDown(); }
            }, failure -> fail(failure));
            Runnable current = queue.poll(2, TimeUnit.SECONDS);
            assertNotNull(current);
            old.run();
            current.run();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertEquals(1, actions.get());
        } finally { worker.stop(); }
    }
}
