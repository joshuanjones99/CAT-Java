package com.verifone.cat.pats.runner.handlers.cloudsettle;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import com.verifone.cat.pats.runner.PatsStepError;

/**
 * What the cloud-settle waits need from outside: where the Commander's log is read, the budgets, and the clock and
 * pause the waits poll with. The defaults are the Python's ({@code APPLY_BUDGET} 360 s, {@code QUIET} 10 s,
 * {@code POLL_INTERVAL} 5 s); a test passes its own clock and pause, so no test waits real time.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the budgets were read from a lab Commander's log (2026-10-21).
 */
public final class CloudSettleContext {

    /** How long the Commander gets to apply a cloud write (QAT took about 4 min, 2026-10-21). */
    public static final Duration APPLY_BUDGET = Duration.ofSeconds(360);
    /** After the last dataset applied, how long nothing new may arrive before the case goes on. */
    public static final Duration QUIET = Duration.ofSeconds(10);
    /** How often the log is read while waiting. */
    public static final Duration POLL_INTERVAL = Duration.ofSeconds(5);

    private final Supplier<? extends CommanderLog> commanderLog;
    private final Duration applyBudget;
    private final Duration quiet;
    private final Duration pollInterval;
    private final LongSupplier nanoTime;
    private final Consumer<Duration> pause;

    /**
     * @param commanderLog  opens the Commander's log reader for one write, or {@code null} when no reader is wired
     *                      (the waits are then skipped, with a warning)
     * @param applyBudget   the ceiling for a write's dataset to be applied
     * @param quiet         how long no new push may arrive before the wait ends
     * @param pollInterval  the pause between reads
     * @param nanoTime      the monotonic clock, in nanoseconds
     * @param pause         parks the caller for a duration; throws {@link PatsStepError} when interrupted
     */
    public CloudSettleContext(Supplier<? extends CommanderLog> commanderLog, Duration applyBudget, Duration quiet,
            Duration pollInterval, LongSupplier nanoTime, Consumer<Duration> pause) {
        this.commanderLog = commanderLog;
        this.applyBudget = Objects.requireNonNull(applyBudget, "applyBudget");
        this.quiet = Objects.requireNonNull(quiet, "quiet");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.pause = Objects.requireNonNull(pause, "pause");
    }

    /** The production budgets, the real clock and a real pause. */
    public static CloudSettleContext of(Supplier<? extends CommanderLog> commanderLog) {
        return new CloudSettleContext(commanderLog, APPLY_BUDGET, QUIET, POLL_INTERVAL, System::nanoTime,
                CloudSettleContext::park);
    }

    /** A new reader for one write's mark, or empty when none is wired. */
    Optional<CommanderLog> openLog() {
        return commanderLog == null ? Optional.empty() : Optional.ofNullable(commanderLog.get());
    }

    Duration applyBudget() {
        return applyBudget;
    }

    Duration quiet() {
        return quiet;
    }

    Duration pollInterval() {
        return pollInterval;
    }

    long nanoTime() {
        return nanoTime.getAsLong();
    }

    void pause(Duration duration) {
        pause.accept(duration);
    }

    /** Parks until {@code duration} has passed; an interrupt is a step failure, not a retry. */
    static void park(Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        for (long left = duration.toNanos(); left > 0; left = deadline - System.nanoTime()) {
            LockSupport.parkNanos(left);
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new PatsStepError("interrupted while waiting for the Commander to apply a cloud write");
            }
        }
    }
}
