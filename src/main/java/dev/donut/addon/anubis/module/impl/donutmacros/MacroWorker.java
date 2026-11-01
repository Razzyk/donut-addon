// Ported from the supplied Anubis macro extraction; original classes were only read statically.
package dev.donut.addon.anubis.module.impl.donutmacros;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

@Environment(value=EnvType.CLIENT)
final class MacroWorker {
    private static final long POLL_MS = 50L;
    private static final long CALL_TIMEOUT_SECONDS = 10L;
    private final String name;
    private final Executor mainThread;
    private volatile Thread thread;

    MacroWorker(String name) {
        this(name, task -> Minecraft.getInstance().execute(task));
    }

    MacroWorker(String name, Executor mainThread) {
        this.name = name;
        this.mainThread = mainThread;
    }

    synchronized void start(Runnable body, Consumer<Throwable> crashed) {
        this.stop();
        Thread worker = new Thread(() -> {
            try {
                body.run();
            }
            catch (Cancelled cancelled) {
                MacroWorker macroWorker = this;
                synchronized (macroWorker) {
                    if (this.thread == Thread.currentThread()) {
                        this.thread = null;
                    }
                }
            }
            catch (Throwable failure) {
                if (this.thread == Thread.currentThread()) {
                    crashed.accept(failure);
                }
            }
            finally {
                MacroWorker macroWorker = this;
                synchronized (macroWorker) {
                    if (this.thread == Thread.currentThread()) {
                        this.thread = null;
                    }
                }
            }
        }, this.name);
        worker.setDaemon(true);
        this.thread = worker;
        worker.start();
    }

    synchronized void stop() {
        Thread worker = this.thread;
        this.thread = null;
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
        }
    }

    boolean running() {
        return this.thread != null;
    }

    void check() {
        if (this.thread != Thread.currentThread() || Thread.currentThread().isInterrupted()) {
            throw new Cancelled();
        }
    }

    void sleep(long ms) {
        this.check();
        try {
            Thread.sleep(Math.max(0L, ms));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Cancelled();
        }
        this.check();
    }

    void ticks(int ticks) {
        this.sleep((long)ticks * 50L);
    }

    <T> T call(Supplier<T> task) {
        this.check();
        Thread self = Thread.currentThread();
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            if (this.thread != self) {
                throw new Cancelled();
            }
            return task.get();
        }, mainThread);
        try {
            return future.get(10L, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw new Cancelled();
        }
        catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                RuntimeException runtime = (RuntimeException)cause;
                throw runtime;
            }
            if (cause instanceof Error) {
                Error error = (Error)cause;
                throw error;
            }
            throw new IllegalStateException(cause);
        }
        catch (TimeoutException e) {
            future.cancel(false);
            throw new IllegalStateException("The game did not respond");
        }
    }

    void run(Runnable task) {
        this.call(() -> {
            task.run();
            return Boolean.TRUE;
        });
    }

    boolean waitFor(BooleanSupplier condition, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!this.call(condition::getAsBoolean).booleanValue()) {
            if (System.currentTimeMillis() >= end) {
                return false;
            }
            this.sleep(50L);
        }
        return true;
    }

    @Environment(value=EnvType.CLIENT)
    static final class Cancelled
    extends RuntimeException {
        Cancelled() {
            super(null, null, false, false);
        }
    }
}
