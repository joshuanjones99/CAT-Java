package com.verifone.cat.launch;

/**
 * How one step ended: {@code complete}, {@code failed}, {@code skipped}, or {@code pending} when the
 * run never reached it (a stop mid-run leaves later steps pending, as the Python CAT leaves them).
 *
 * @param name   the step's name
 * @param state  one of the four values above
 * @param detail the reason, or ""
 */
public record StepResult(String name, String state, String detail) {

    public static final String COMPLETE = "complete";
    public static final String FAILED = "failed";
    public static final String SKIPPED = "skipped";
    public static final String PENDING = "pending";
}
