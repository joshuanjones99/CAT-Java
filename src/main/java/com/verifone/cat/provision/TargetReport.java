package com.verifone.cat.provision;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What happened to one box. A port of {@code petrotest.provision.runner.TargetReport}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class TargetReport {

    /** What {@code provision} decided for a box. */
    public enum Decision {
        UP_TO_DATE("up-to-date"),
        PROVISIONED("provisioned"),
        FAILED("failed");

        private final String wire;

        Decision(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    private final String target;
    private final TargetRole role;
    private Decision decision = Decision.FAILED;
    private double elapsedSeconds;
    private String manifestDigest = "";
    private final List<String> pushed = new ArrayList<>();
    private final List<String> drift = new ArrayList<>();
    private final List<StepResult> checklist = new ArrayList<>();
    private Verification.Report verification;
    private String error = "";

    public TargetReport(String target, TargetRole role) {
        this.target = target;
        this.role = role;
    }

    public String target() {
        return target;
    }

    public TargetRole role() {
        return role;
    }

    public Decision decision() {
        return decision;
    }

    void decision(Decision value) {
        decision = value;
    }

    public double elapsedSeconds() {
        return elapsedSeconds;
    }

    void elapsedSeconds(double seconds) {
        elapsedSeconds = seconds;
    }

    public String manifestDigest() {
        return manifestDigest;
    }

    void manifestDigest(String digest) {
        manifestDigest = digest;
    }

    public List<String> pushed() {
        return List.copyOf(pushed);
    }

    void pushed(String path) {
        pushed.add(path);
    }

    public List<String> drift() {
        return List.copyOf(drift);
    }

    void drift(List<String> paths) {
        drift.addAll(paths);
    }

    public List<StepResult> checklist() {
        return List.copyOf(checklist);
    }

    void checklist(StepResult result) {
        checklist.add(result);
    }

    public Verification.Report verification() {
        return verification;
    }

    void verification(Verification.Report report) {
        verification = report;
    }

    public String error() {
        return error;
    }

    void error(String message) {
        error = message;
    }

    public boolean ok() {
        return decision != Decision.FAILED;
    }

    /** One line for the run report. */
    public String summaryLine() {
        String head = String.format(Locale.ROOT, "%-20s %-13s %6.2fs", target, decision.wire(), elapsedSeconds);
        return switch (decision) {
            case UP_TO_DATE -> head + "  (stamp " + manifestDigest.substring(0, Math.min(12, manifestDigest.length()))
                    + "...)";
            case PROVISIONED -> head + "  " + pushed.size() + " file(s) pushed and verified";
            case FAILED -> head + "  " + error;
        };
    }
}
