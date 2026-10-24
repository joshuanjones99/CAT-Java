package com.verifone.cat.provision;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What {@code provision} did to a rig, target by target. A port of {@code petrotest.provision.runner.ProvisionReport}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class ProvisionReport {

    private final String rig;
    private final boolean forced;
    private final List<TargetReport> targets = new ArrayList<>();
    private double elapsedSeconds;

    public ProvisionReport(String rig, boolean forced) {
        this.rig = rig;
        this.forced = forced;
    }

    public String rig() {
        return rig;
    }

    public boolean forced() {
        return forced;
    }

    public List<TargetReport> targets() {
        return List.copyOf(targets);
    }

    void add(TargetReport target) {
        targets.add(target);
    }

    public double elapsedSeconds() {
        return elapsedSeconds;
    }

    void elapsedSeconds(double seconds) {
        elapsedSeconds = seconds;
    }

    public boolean ok() {
        return targets.stream().allMatch(TargetReport::ok);
    }

    /** True when a checklist step restarted a register's POS app. */
    public boolean restartedPos() {
        return targets.stream().flatMap(t -> t.checklist().stream()).anyMatch(StepResult::restarted);
    }

    /** True when every target was already up to date: the fast path. */
    public boolean didNothing() {
        return !targets.isEmpty() && targets.stream().allMatch(t -> t.decision() == TargetReport.Decision.UP_TO_DATE);
    }

    public String render() {
        StringBuilder out = new StringBuilder("provision ").append(rig).append(forced ? " --force" : "").append('\n');
        for (TargetReport t : targets) {
            out.append("  ").append(t.summaryLine()).append('\n');
        }
        for (TargetReport t : targets) {
            if (t.verification() != null) {
                for (String problem : t.verification().problems()) {
                    out.append("  ! ").append(t.target()).append(": ").append(problem).append('\n');
                }
            }
            for (StepResult step : t.checklist()) {
                if (step.status() != StepStatus.DONE) {
                    out.append("  . ").append(t.target()).append(": ").append(step.step()).append(' ')
                            .append(step.status().wire()).append(" - ").append(step.detail()).append('\n');
                }
            }
        }
        out.append(String.format(Locale.ROOT, "  total %.2fs - %s", elapsedSeconds, ok() ? "OK" : "FAILED"));
        return out.toString();
    }
}
