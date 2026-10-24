package com.verifone.cat.upgrade;

/**
 * The outcome of one install step: what it was, whether it is done, and why it failed if it did not.
 *
 * <p>A port of {@code petrotest.provision.checklist.StepResult}, narrowed to the two states the upgrade
 * path produces.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public record StepResult(String step, Status status, String detail) {

    public StepResult {
        detail = detail == null ? "" : detail;
    }

    /** Whether the step finished, or stopped the install. */
    public enum Status {
        DONE, FAILED;

        /** The lowercase word the run's log uses. */
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static StepResult done(String step) {
        return new StepResult(step, Status.DONE, "");
    }

    public static StepResult done(String step, String detail) {
        return new StepResult(step, Status.DONE, detail);
    }

    public static StepResult failed(String step, String detail) {
        return new StepResult(step, Status.FAILED, detail);
    }

    public boolean failed() {
        return status == Status.FAILED;
    }
}
