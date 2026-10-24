package com.verifone.cat.pos.receipt;

/**
 * Where a captured receipt came from (DESIGN.md 5.3). Ported from {@code petrotest.pos.receipt.Source}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public enum Source {
    DOM,
    SERIAL
}
