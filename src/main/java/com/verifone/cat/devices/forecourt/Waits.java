package com.verifone.cat.devices.forecourt;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Bounded waits on a monitor. Each call returns once {@code ready} holds, or throws {@link ForecourtWaitExpired}
 * when {@code budget} runs out first. No sleep: the wait is on the monitor, woken by the listener's notify.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class Waits {

    private Waits() {
    }

    /**
     * Waits on {@code lock} until {@code ready} holds. Call without holding {@code lock}; the wait takes it.
     *
     * @throws ForecourtWaitExpired when {@code budget} passes first; the message is {@code what} plus the budget
     */
    static void await(Object lock, BooleanSupplier ready, Duration budget, Supplier<String> what) {
        long deadline = System.nanoTime() + budget.toNanos();
        synchronized (lock) {
            while (!ready.getAsBoolean()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new ForecourtWaitExpired(what.get() + " exceeded its " + seconds(budget) + "s budget");
                }
                try {
                    lock.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ForecourtWaitExpired(what.get() + " was interrupted");
                }
            }
        }
    }

    private static String seconds(Duration d) {
        return String.format(java.util.Locale.ROOT, "%.3g", d.toNanos() / 1e9);
    }
}
