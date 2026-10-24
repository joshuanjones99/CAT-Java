package com.verifone.cat.preflight;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Runs probes on daemon threads with a bound on the wait. Ported in spirit from the Python
 * CAT's {@code petrotest.recovery.timeouts} ({@code call_with_timeout}, {@code map_with_timeout}),
 * which this port does not have yet.
 *
 * <p>A probe that outlives its budget is cancelled and abandoned, not joined. Java cannot
 * kill a thread, so a hung probe keeps its thread until it returns; the daemon flag means it
 * cannot keep the JVM alive. The wait is a bounded {@code Future.get}, never a sleep loop.
 */
public final class Bounded {

    private Bounded() {}

    /** One supplier's fate: a value, a failure it threw, or a budget it ran out of. */
    public record Completed<T>(T value, RuntimeException failure, boolean timedOut) {

        /** True only when the supplier returned a value; a failure or a timeout is not finished. */
        public boolean finished() {
            return value != null;
        }
    }

    /**
     * Runs every supplier in parallel and waits at most {@code budget} for all of them together.
     * The result is in input order.
     */
    public static <T> List<Completed<T>> runAll(List<Supplier<T>> work, Duration budget) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, work.size()), Bounded::daemon);
        try {
            List<Future<T>> futures = new ArrayList<>(work.size());
            for (Supplier<T> supplier : work) {
                futures.add(pool.submit(supplier::get));
            }
            long deadline = System.nanoTime() + Math.max(0L, budget.toNanos());
            List<Completed<T>> results = new ArrayList<>(futures.size());
            for (Future<T> future : futures) {
                long left = Math.max(0L, deadline - System.nanoTime());
                try {
                    T value = future.get(left, java.util.concurrent.TimeUnit.NANOSECONDS);
                    results.add(new Completed<>(value, null, false));
                } catch (TimeoutException e) {
                    future.cancel(true);
                    results.add(new Completed<>(null, null, true));
                } catch (ExecutionException e) {
                    results.add(new Completed<>(null, asRuntime(e.getCause()), false));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    future.cancel(true);
                    results.add(new Completed<>(null, null, true));
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** One supplier, bounded. Returns its value, or throws what it threw or {@link TimeoutExpired}. */
    public static <T> T call(Supplier<T> work, Duration budget, String what) {
        Completed<T> done = runAll(List.of(work), budget).get(0);
        if (done.timedOut()) {
            throw new TimeoutExpired(what + " did not finish within " + budget.toSeconds() + "s");
        }
        if (done.failure() != null) {
            throw done.failure();
        }
        return done.value();
    }

    private static RuntimeException asRuntime(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException(cause);
    }

    private static Thread daemon(Runnable runnable) {
        Thread thread = new Thread(runnable, "preflight-probe");
        thread.setDaemon(true);
        return thread;
    }

    /** A bounded call ran past its budget. */
    public static final class TimeoutExpired extends RuntimeException {
        public TimeoutExpired(String message) {
            super(message);
        }
    }
}
