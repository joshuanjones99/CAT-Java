package com.verifone.cat.preflight;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything that happened to one check in one preflight pass. Ported from
 * {@code petrotest.preflight.runner.CheckRecord}.
 */
public final class CheckRecord {

    /** The detail a probe with no source on this rig carries (hard rule 5: ERROR, never a pass). */
    static final String UNAVAILABLE_DETAIL = "probe raised DeviceUnavailable";

    private final String name;
    private final Result first;
    private Result repair;
    private Result second;

    CheckRecord(String name, Result first) {
        this.name = name;
        this.first = first;
    }

    public String name() {
        return name;
    }

    public Result first() {
        return first;
    }

    /** The repair attempt, or null when none ran. */
    public Result repair() {
        return repair;
    }

    /** The re-verification after a repair, or null when none ran. */
    public Result second() {
        return second;
    }

    void setRepair(Result repair) {
        this.repair = repair;
    }

    void setSecond(Result second) {
        this.second = second;
    }

    /** The re-verification if there was one, otherwise the first verification. */
    public Result finalResult() {
        return second != null ? second : first;
    }

    public boolean ok() {
        return finalResult().ok();
    }

    /** The probe has no source on this rig: an ERROR that says nothing about the rig's state. */
    public boolean unreadable() {
        Result last = finalResult();
        return last.outcome() == Outcome.ERROR && last.detail().startsWith(UNAVAILABLE_DETAIL);
    }

    /**
     * DESIGN.md 6.2's unhealthy condition: failed verification, repaired, failed again. A repair
     * that declined counts too: there is no second verification to wait for.
     */
    public boolean failedTwice() {
        if (first.ok()) {
            return false;
        }
        if (repair != null && repair.outcome() == Outcome.UNREPAIRABLE) {
            return true;
        }
        return second != null && !second.ok();
    }

    public String render() {
        List<String> parts = new ArrayList<>();
        parts.add(String.format("%-22s %s", name, first.outcome().value()));
        if (repair != null) {
            parts.add("repair=" + repair.outcome().value());
        }
        if (second != null) {
            parts.add("reverify=" + second.outcome().value());
        }
        String detail = finalResult().detail();
        return String.join("  ", parts) + (detail.isEmpty() ? "" : "  — " + detail);
    }
}
