package com.verifone.cat.preflight;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The result of one preflight pass. Ported from {@code petrotest.preflight.runner.PreflightReport}.
 *
 * <p>{@link #testable()} is recovery's question and the CLI's exit code: every check that can
 * be read passes. {@link #healthy()} is stricter and is false on any rig where a probe has no
 * source.
 */
public final class PreflightReport {

    private final String rig;
    private final boolean repairEnabled;
    private final List<CheckRecord> records = new ArrayList<>();
    private final List<String> skipped = new ArrayList<>();
    private final Map<String, String> heldBack = new LinkedHashMap<>();
    private Duration elapsed = Duration.ZERO;

    PreflightReport(String rig, boolean repairEnabled) {
        this.rig = rig;
        this.repairEnabled = repairEnabled;
    }

    public String rig() {
        return rig;
    }

    public boolean repairEnabled() {
        return repairEnabled;
    }

    public List<CheckRecord> records() {
        return List.copyOf(records);
    }

    public List<String> skipped() {
        return List.copyOf(skipped);
    }

    /** Checks held back mid-run, with the reason. Nothing was read, so nothing counts against health. */
    public Map<String, String> heldBack() {
        return Map.copyOf(heldBack);
    }

    public Duration elapsed() {
        return elapsed;
    }

    public boolean healthy() {
        return records.stream().allMatch(CheckRecord::ok);
    }

    public boolean testable() {
        return records.stream().allMatch(record -> record.ok() || record.unreadable());
    }

    /** The DESIGN.md 6.2 drain trigger: checks that failed verification twice. */
    public List<String> unhealthyChecks() {
        return records.stream().filter(CheckRecord::failedTwice).map(CheckRecord::name).toList();
    }

    /** Every check that is not OK, whether or not it failed twice. */
    public List<String> failingChecks() {
        return records.stream().filter(record -> !record.ok()).map(CheckRecord::name).toList();
    }

    public List<Result> repairs() {
        return records.stream().map(CheckRecord::repair).filter(r -> r != null).toList();
    }

    void addRecord(CheckRecord record) {
        records.add(record);
    }

    void addSkipped(String name) {
        skipped.add(name);
    }

    void holdBack(String name, String reason) {
        heldBack.put(name, reason);
    }

    void setElapsed(Duration elapsed) {
        this.elapsed = elapsed;
    }

    public String render() {
        String head;
        if (healthy()) {
            head = "healthy";
        } else {
            List<String> shown = unhealthyChecks().isEmpty() ? failingChecks() : unhealthyChecks();
            head = "UNHEALTHY " + shown;
        }
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "preflight %s: %s (%.2fs)", rig, head, elapsed.toNanos() / 1e9));
        for (CheckRecord record : records) {
            lines.add("  " + record.render());
        }
        if (!skipped.isEmpty()) {
            lines.add("  not applicable to this rig: " + String.join(", ", skipped));
        }
        for (Map.Entry<String, String> held : heldBack.entrySet()) {
            lines.add(String.format("  %-22s skipped mid-run — %s", held.getKey(), held.getValue()));
        }
        return String.join("\n", lines);
    }
}
