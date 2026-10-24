package com.verifone.cat.recovery;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.preflight.Bounded;

/**
 * DESIGN.md 6.3's escalating ladder. Ported from {@code petrotest.recovery.ladder.RecoveryLadder}.
 *
 * <p>Three rules, all from the Python and all kept:
 * <ul>
 *   <li><b>Capture first.</b> Rung 0 runs before anything else, including before the ladder asks
 *       whether recovery is needed.</li>
 *   <li><b>A rung that raises does not stop the ladder.</b> It is recorded as not performed and
 *       escalation continues.</li>
 *   <li><b>Every rung is bounded.</b> A rung that overruns is recorded as an overrun and the ladder
 *       escalates. Java cannot kill a thread, so an overrun rung keeps running on its daemon thread
 *       while the next one starts. The Python has the same limit, which is why a rung's own work is
 *       meant to finish inside its budget.</li>
 * </ul>
 * The ladder asks the rig, not the rung, whether it is testable, after every performed rung. Rung 6
 * always ends the walk and cannot fail.
 *
 * <p>Not ported: the Python's {@code current_deadline()}, which lets a rung see its remaining budget
 * and stop starting repairs it cannot finish.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class RecoveryLadder {

    private static final Logger LOG = LoggerFactory.getLogger(RecoveryLadder.class);

    /** Rung 4's worst case, rounded up: the Python's {@code PREFLIGHT_RUNG_SECONDS}. */
    public static final Duration PREFLIGHT_RUNG = Duration.ofSeconds(760);

    /** Rung 5's floor: the Python's {@code max(600.0, timeouts.ssh * 20)}. */
    public static final Duration REPROVISION_FLOOR = Duration.ofSeconds(600);

    private final RecoveryTarget target;
    private final Capturer capturer;
    private final String runId;
    private final RunSummary summary;
    private final Timeouts timeouts;
    private final List<Rung> escalation = Rung.ESCALATION;

    /**
     * @param summary  where each trip is recorded, or {@code null} to record nothing
     * @param timeouts the rig's bounded-wait defaults, or {@code null} for the defaults
     */
    public RecoveryLadder(RecoveryTarget target, Capturer capturer, String runId, RunSummary summary,
            Timeouts timeouts) {
        this.target = target;
        this.capturer = capturer;
        this.runId = runId;
        this.summary = summary;
        this.timeouts = timeouts == null ? Timeouts.defaults() : timeouts;
    }

    /**
     * Per-rung ceilings, derived from the rig's {@code timeouts:} block (DESIGN.md 6.3). Rungs 1-3
     * together are about one {@code default_wait}; rung 5 is minutes.
     */
    public static Map<Rung, Duration> rungBudgets(Timeouts timeouts) {
        Duration wait = timeouts.defaultWait();
        Map<Rung, Duration> budgets = new EnumMap<>(Rung.class);
        budgets.put(Rung.SOFT, wait);
        budgets.put(Rung.VOID, wait);
        budgets.put(Rung.RESET, wait.multipliedBy(2));
        // Preflight is ten probes plus serial repairs; it must not be cut off mid-repair.
        budgets.put(Rung.PREFLIGHT, max(PREFLIGHT_RUNG, wait.multipliedBy(8)));
        budgets.put(Rung.REPROVISION, max(REPROVISION_FLOOR, timeouts.ssh().multipliedBy(20)));
        return budgets;
    }

    /** Walks the ladder for one failed test. */
    public RecoveryOutcome recover(String testId, String reason) {
        long started = System.nanoTime();
        RecoveryOutcome outcome = new RecoveryOutcome(target.rigName(), testId);

        // Rung 0: capture, unconditional, before anything else (DESIGN.md 6.3).
        long captureStarted = System.nanoTime();
        CaptureBundle bundle = capturer.capture(runId, testId, reason);
        outcome.addAttempt(new RungAttempt(Rung.CAPTURE, true, false, nanos(System.nanoTime() - captureStarted),
                bundle.collected().size() + " source(s) captured"
                        + (bundle.failed().isEmpty() ? "" : ", " + bundle.failed().size() + " failed")));

        // Rungs 1-5: escalate, stopping at the first that restores a testable state.
        Map<Rung, Duration> budgets = rungBudgets(timeouts);
        Rung won = Rung.DRAIN;
        boolean recovered = false;
        for (Rung rung : escalation) {
            RungAttempt attempt = attempt(rung, budgets.get(rung));
            outcome.addAttempt(attempt);
            if (attempt.testableAfter()) {
                won = rung;
                recovered = true;
                break;
            }
        }

        // Rung 6: DRAIN, the honest admission that a rig needs a human.
        if (!recovered) {
            outcome.addAttempt(drain(testId, reason));
        }

        Duration elapsed = nanos(System.nanoTime() - started);
        outcome.finish(won, recovered, bundle, elapsed);
        LOG.info("{}", outcome.render());
        if (summary != null) {
            summary.recordRecovery(outcome.rig(), testId, outcome.rung(), outcome.attempted(), recovered, elapsed, "");
        }
        return outcome;
    }

    private RungAttempt attempt(Rung rung, Duration budget) {
        String description = describe(rung);
        long started = System.nanoTime();
        boolean performed = false;
        String detail = description;
        try {
            performed = runBounded(action(rung), budget, rung.label());
        } catch (Bounded.TimeoutExpired e) {
            detail = description + " - overran its " + budget.toSeconds() + "s budget";
            LOG.warn("recovery {}: {}", rung.label(), e.getMessage());
        } catch (RuntimeException e) {
            detail = description + " - raised " + e.getClass().getSimpleName() + ": " + e.getMessage();
            LOG.warn("recovery {} raised: {}", rung.label(), e.getMessage());
        }

        // Ask the rig, not the rung. A rung that reports success and leaves the POS wedged must not end the ladder.
        boolean testable = false;
        if (performed) {
            try {
                testable = runBounded(target::isTestable, timeouts.defaultWait(), "is_testable after " + rung.label());
            } catch (RuntimeException e) {
                detail += "; testability probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
        return new RungAttempt(rung, performed, testable, nanos(System.nanoTime() - started), detail);
    }

    private Supplier<Boolean> action(Rung rung) {
        return switch (rung) {
            case SOFT -> target::softCancel;
            case VOID -> target::voidOpenTicket;
            case RESET -> target::resetState;
            case PREFLIGHT -> target::runPreflight;
            case REPROVISION -> target::reprovision;
            default -> throw new IllegalArgumentException("not a walked rung: " + rung);
        };
    }

    /** Rung 6. Loud, and it cannot fail: a drain hook that raises is logged, never propagated. */
    private RungAttempt drain(String testId, String reason) {
        long started = System.nanoTime();
        String message = "rig " + target.rigName() + " is unhealthy: recovery for " + testId
                + " exhausted rungs 1-5" + (reason == null || reason.isEmpty() ? "" : " after " + reason);
        LOG.error("DRAIN: {}", message);
        try {
            runBounded(() -> {
                target.drain(message);
                return Boolean.TRUE;
            }, timeouts.defaultWait(), "drain");
        } catch (RuntimeException e) {
            LOG.error("drain hook failed for {}: {}", target.rigName(), e.getMessage());
        }
        if (summary != null) {
            summary.recordDrain(target.rigName(), message);
        }
        return new RungAttempt(Rung.DRAIN, true, false, nanos(System.nanoTime() - started), message);
    }

    private static String describe(Rung rung) {
        return switch (rung) {
            case SOFT -> "cancel/escape ladder";
            case VOID -> "void open ticket, clear parked";
            case RESET -> "device reset, reconnect CDP";
            case PREFLIGHT -> "preflight with repair (DESIGN.md 6.2)";
            case REPROVISION -> "provision --force, then preflight";
            default -> rung.name();
        };
    }

    /**
     * One bounded call. Returns the supplier's answer, throws what it threw, or throws
     * {@link Bounded.TimeoutExpired} when the budget ran out first. A timeout is never a
     * {@code false} answer: the caller treats it as an overrun.
     */
    private static boolean runBounded(Supplier<Boolean> work, Duration budget, String what) {
        Boolean value = Bounded.call(work, budget, what);
        return Boolean.TRUE.equals(value);
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static Duration nanos(long nanos) {
        return Duration.ofNanos(Math.max(0L, nanos));
    }
}
