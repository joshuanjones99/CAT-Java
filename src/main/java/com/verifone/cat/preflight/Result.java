package com.verifone.cat.preflight;

import java.time.Duration;

/**
 * One verify or repair outcome. Ported from {@code petrotest.harness.base.Result}.
 *
 * <p>{@code wasRepair} is set by the runner, not by a check: a repair's record is a repair
 * whatever it reported, because a summary that only counted successes would make a rig
 * that fails every repair look like a rig that never needed one.
 */
public record Result(String check, Outcome outcome, String detail, Duration elapsed, boolean wasRepair) {

    public Result {
        detail = detail == null ? "" : detail;
        elapsed = elapsed == null ? Duration.ZERO : elapsed;
    }

    public Result(String check, Outcome outcome, String detail) {
        this(check, outcome, detail, Duration.ZERO, false);
    }

    public boolean ok() {
        return outcome.isGood();
    }

    public Result withElapsed(Duration elapsed) {
        return new Result(check, outcome, detail, elapsed, wasRepair);
    }

    public Result asRepair() {
        return new Result(check, outcome, detail, elapsed, true);
    }

    public static Result passed(String check) {
        return passed(check, "");
    }

    public static Result passed(String check, String detail) {
        return new Result(check, Outcome.PASS, detail);
    }

    /** A FAIL always carries a detail. A bare "failed" costs a rig visit. */
    public static Result failed(String check, String detail) {
        return new Result(check, Outcome.FAIL, detail);
    }

    /** The probe could not tell. Never a FAIL: a broken probe and a sick rig need different responses. */
    public static Result error(String check, String detail) {
        return new Result(check, Outcome.ERROR, detail);
    }

    public static Result unrepairable(String check, String detail) {
        return new Result(check, Outcome.UNREPAIRABLE, detail);
    }

    @Override
    public String toString() {
        return check + ": " + outcome.value() + (detail.isEmpty() ? "" : " — " + detail);
    }
}
