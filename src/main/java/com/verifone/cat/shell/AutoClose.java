package com.verifone.cat.shell;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * CAT closes itself once no page has been open for a while and nothing holds it: the Python CAT's
 * {@code cat.web.autoclose.watch}. Checks every {@link PageWatch#CHECK_EVERY} on a daemon timer (no sleep), and calls
 * {@code close} once, logging why. {@code --no-browser} and {@code --stay} never start one.
 */
public final class AutoClose implements AutoCloseable {

    private final ScheduledExecutorService timer;
    private final AtomicBoolean closed = new AtomicBoolean();

    private AutoClose(ScheduledExecutorService timer) {
        this.timer = timer;
    }

    /**
     * @param shell the open pages and what holds CAT
     * @param clock {@link System#nanoTime()} in a real session
     * @param log   hears the one line said on closing
     * @param close what closes CAT
     */
    public static AutoClose watch(Shell shell, LongSupplier clock, java.util.function.Consumer<String> log,
            Runnable close) {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cat-web-autoclose");
            thread.setDaemon(true);
            return thread;
        });
        AutoClose watch = new AutoClose(timer);
        PageWatch pages = new PageWatch(clock.getAsLong());
        long every = PageWatch.CHECK_EVERY.toMillis();
        timer.scheduleWithFixedDelay(() -> {
            long now = clock.getAsLong();
            if (pages.shouldClose(now, shell.openPages(now), shell.holding()) && watch.closed.compareAndSet(false, true)) {
                log.accept("no CAT page open and nothing running: closing CAT");
                timer.shutdown();
                close.run();
            }
        }, every, every, TimeUnit.MILLISECONDS);
        return watch;
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
