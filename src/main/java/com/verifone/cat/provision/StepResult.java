package com.verifone.cat.provision;

/**
 * One checklist step's outcome. A port of {@code petrotest.provision.checklist.StepResult}.
 *
 * @param restarted the step restarted the register's POS app, which is minutes from usable again
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public record StepResult(String step, StepStatus status, String detail, boolean restarted) {

    public StepResult(String step, StepStatus status, String detail) {
        this(step, status, detail, false);
    }

    /** DONE, ALREADY and SKIPPED pass; only FAILED does not. A skipped step is reported, not hidden. */
    public boolean ok() {
        return status != StepStatus.FAILED;
    }
}
