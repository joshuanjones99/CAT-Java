package com.verifone.cat.preflight;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.verifone.cat.devices.DeviceUnavailable;

/**
 * The preflight state machine, DESIGN.md 6.2. Ported from {@code petrotest.preflight.runner}.
 *
 * <ol>
 *   <li><b>verify in parallel</b>, against a {@link RigContext#readOnly() read-only} rig, so
 *       two probes racing cannot interfere;</li>
 *   <li><b>repair serially</b>, in check order, only the checks that failed and can be read;</li>
 *   <li><b>re-verify</b> only what was repaired; a repair reports what it did, not what is true;</li>
 *   <li><b>twice means unhealthy</b>, which {@link PreflightReport#unhealthyChecks()} names.</li>
 * </ol>
 *
 * <p>A probe that raises is ERROR, never FAIL (DeviceUnavailable and DeviceUnreachable included).
 * A probe that does not answer inside the budget is TIMEOUT.
 *
 * <p>Not ported yet: the recovery ladder's {@code current_deadline()} (a repair must fit in the
 * rung's remaining budget) and {@code RunSummary} (the repair log's findings). Both live in the
 * Python {@code petrotest.recovery}, which is still to be ported.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class PreflightRunner {

    /** Ceiling for the whole parallel verify pass (the Python CAT's {@code VERIFY_BUDGET_SECONDS}). */
    public static final Duration VERIFY_BUDGET = Duration.ofSeconds(20);

    private static final Duration DEFAULT_REPAIR_BUDGET = Duration.ofSeconds(30);

    private PreflightRunner() {}

    /** The knobs a caller turns. {@code repair=false} is observation only. */
    public record RunOptions(boolean repair, Duration verifyBudget, boolean midRun) {

        public RunOptions {
            verifyBudget = verifyBudget == null ? VERIFY_BUDGET : verifyBudget;
        }

        /** Session start: repair on, the standard verify budget, not mid-run. */
        public static RunOptions defaults() {
            return new RunOptions(true, VERIFY_BUDGET, false);
        }
    }

    public static PreflightReport run(RigContext rig, Collection<? extends Check> checks, RunOptions options) {
        long started = System.nanoTime();
        PreflightReport report = new PreflightReport(rig.name(), options.repair());

        List<Check> applicable = new ArrayList<>();
        for (Check check : checks) {
            if (!check.applicable(rig)) {
                report.addSkipped(check.name());
                continue;
            }
            String why = options.midRun() ? check.operatorOnly(rig) : "";
            if (!why.isEmpty()) {
                report.holdBack(check.name(), why);
                continue;
            }
            applicable.add(check);
        }

        Map<String, Result> firstPass = verifyAll(applicable, rig, options.verifyBudget());
        Map<String, CheckRecord> records = new LinkedHashMap<>();
        for (Check check : applicable) {
            records.put(check.name(), new CheckRecord(check.name(), firstPass.get(check.name())));
        }

        // An unreadable probe is not repaired: with no source there is nothing to act on.
        List<Check> failing = new ArrayList<>();
        for (Check check : applicable) {
            CheckRecord record = records.get(check.name());
            if (!record.first().ok() && !record.unreadable()) {
                failing.add(check);
            }
        }

        if (!failing.isEmpty() && options.repair()) {
            List<Check> repaired = new ArrayList<>();
            for (Check check : failing) {
                records.get(check.name()).setRepair(repairOne(check, rig));
                repaired.add(check);
            }
            Map<String, Result> secondPass = verifyAll(repaired, rig, options.verifyBudget());
            for (Check check : repaired) {
                records.get(check.name()).setSecond(secondPass.get(check.name()));
            }
        }

        for (Check check : applicable) {
            report.addRecord(records.get(check.name()));
        }
        report.setElapsed(Duration.ofNanos(System.nanoTime() - started));
        return report;
    }

    /** Verify every check in parallel against a read-only snapshot, within one shared budget. */
    static Map<String, Result> verifyAll(List<Check> checks, RigContext rig, Duration budget) {
        RigContext frozen = rig.readOnly();
        List<Supplier<Result>> work = new ArrayList<>(checks.size());
        for (Check check : checks) {
            work.add(() -> verifyOne(check, frozen));
        }
        List<Bounded.Completed<Result>> outcomes = Bounded.runAll(work, budget);
        Map<String, Result> results = new LinkedHashMap<>();
        for (int i = 0; i < checks.size(); i++) {
            Check check = checks.get(i);
            Bounded.Completed<Result> done = outcomes.get(i);
            if (done.finished()) {
                results.put(check.name(), done.value());
            } else {
                results.put(check.name(), new Result(check.name(), Outcome.TIMEOUT,
                        "verify() did not answer within the " + budget.toSeconds() + "s preflight budget"));
            }
        }
        return results;
    }

    /** One {@code verify()}, every failure mode converted into a Result. */
    static Result verifyOne(Check check, RigContext rig) {
        long started = System.nanoTime();
        Result result;
        try {
            result = check.verify(rig);
        } catch (MutationInVerifyError e) {
            result = Result.error(check.name(), e.getMessage());
        } catch (DeviceUnavailable e) {
            result = Result.error(check.name(), CheckRecord.UNAVAILABLE_DETAIL + ": " + messageOf(e));
        } catch (RuntimeException e) {
            result = Result.error(check.name(),
                    "probe raised " + e.getClass().getSimpleName() + ": " + messageOf(e));
        }
        return result.withElapsed(Duration.ofNanos(System.nanoTime() - started));
    }

    /** One {@code repair()}, bounded from outside and always recorded. */
    static Result repairOne(Check check, RigContext rig) {
        long started = System.nanoTime();
        Duration budget = repairBudget(check);
        Result result;
        try {
            result = Bounded.call(() -> check.repair(rig), budget, "repair " + check.name());
        } catch (Bounded.TimeoutExpired e) {
            result = new Result(check.name(), Outcome.TIMEOUT, messageOf(e));
        } catch (RuntimeException e) {
            result = Result.error(check.name(),
                    "repair raised " + e.getClass().getSimpleName() + ": " + messageOf(e));
        }
        return result.withElapsed(Duration.ofNanos(System.nanoTime() - started)).asRepair();
    }

    private static Duration repairBudget(Check check) {
        Duration budget = check.repairBudget();
        return budget == null || budget.isZero() || budget.isNegative() ? DEFAULT_REPAIR_BUDGET : budget;
    }

    private static String messageOf(Throwable e) {
        return String.valueOf(e.getMessage());
    }
}
