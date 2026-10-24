package com.verifone.cat.validate;

/**
 * Why a receipt field differs between two runs of the same test (DESIGN.md §5.4).
 *
 * <p>The distinction decides whether masking the field costs assertion power. The Python
 * CAT's {@code petrotest.validate.normalise.Volatility}; UNVERIFIED AGAINST HARDWARE as a
 * Java port, though the classes it names were measured against the petroapps corpus.
 */
public enum Volatility {

    /** Different on every execution. Nothing can assert on it. Always masked. */
    RUN,

    /**
     * Constant on one rig, different on another (store, register, cashier, shift). Masked
     * by default: the alternative is per-rig snapshots, which DESIGN.md §5.4 does not propose.
     */
    RIG,

    /**
     * Decided by the test's own inputs (the card it inserted). Masking it deletes a real
     * assertion, so it is not masked by default.
     */
    DETERMINISTIC
}
