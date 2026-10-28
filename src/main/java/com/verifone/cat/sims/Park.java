package com.verifone.cat.sims;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

/**
 * The sims package's bounded pause: a park against a deadline, never a sleep. An interrupt ends it
 * early with the flag restored, so a caller's own deadline check decides what happens next.
 */
final class Park {

    private Park() {
    }

    static void park(Duration duration) {
        long deadline = System.nanoTime() + Math.max(0, duration.toNanos());
        for (long left = deadline - System.nanoTime(); left > 0; left = deadline - System.nanoTime()) {
            LockSupport.parkNanos(left);
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
        }
    }
}
