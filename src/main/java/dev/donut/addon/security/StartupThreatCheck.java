package dev.donut.addon.security;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Runs once without blocking startup and retains its result until a world can show local chat. */
public final class StartupThreatCheck<T> implements AutoCloseable {
    private final Supplier<T> scan;
    private final Consumer<Exception> failure;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Donut-SilentNet-Check");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private boolean started;
    private boolean closed;
    private boolean delivered;
    private T result;
    private Future<?> future;

    public StartupThreatCheck(Supplier<T> scan, Consumer<Exception> failure) {
        this.scan = Objects.requireNonNull(scan);
        this.failure = Objects.requireNonNull(failure);
    }

    public synchronized void start() {
        if (started || closed) return;
        started = true;
        future = worker.submit(() -> {
            try {
                T completed = Objects.requireNonNull(scan.get());
                synchronized (this) {
                    if (!closed) result = completed;
                }
            } catch (Exception exception) {
                synchronized (this) {
                    if (closed) return;
                }
                failure.accept(exception);
            } finally {
                worker.shutdown();
            }
        });
    }

    /** Never blocks, including when the scan has not completed yet. */
    public synchronized T poll(boolean worldReady) {
        if (!worldReady || closed || delivered || result == null) return null;
        delivered = true;
        T completed = result;
        result = null;
        return completed;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        result = null;
        if (future != null) future.cancel(true);
        worker.shutdownNow();
    }
}
