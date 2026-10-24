package com.verifone.cat.preflight;

/**
 * A check reached for a mutating surface from {@code verify()}.
 *
 * <p>A programming error in the check, not a rig fault, so the runner records it as ERROR,
 * never FAIL. Ported from {@code petrotest.preflight.context.MutationInVerifyError}.
 */
public final class MutationInVerifyError extends RuntimeException {

    private final String attribute;

    public MutationInVerifyError(String attribute) {
        super("verify() touched the mutating surface '" + attribute + "'. DESIGN.md §6.2: "
                + "'verify() never mutates — it must be safe to run mid-suite.' "
                + "Move the mutation into repair().");
        this.attribute = attribute;
    }

    public String attribute() {
        return attribute;
    }
}
