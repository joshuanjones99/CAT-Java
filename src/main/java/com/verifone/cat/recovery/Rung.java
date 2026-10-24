package com.verifone.cat.recovery;

import java.util.List;

/**
 * The seven rungs of DESIGN.md 6.3, in order. Ported from {@code petrotest.recovery.rungs}.
 *
 * <p>The ordinal is the semantics: the ladder escalates, "rung 4 or 5" is a comparison, and the
 * run summary sorts by it. Rung 0 is not walked by the ladder (it runs first, unconditionally);
 * rung 6 is not an attempt to recover but the admission that recovery failed.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public enum Rung {
    CAPTURE,
    SOFT,
    VOID,
    RESET,
    PREFLIGHT,
    REPROVISION,
    DRAIN;

    /** The rungs the ladder walks, in order (Python {@code ESCALATION}). */
    public static final List<Rung> ESCALATION = List.of(SOFT, VOID, RESET, PREFLIGHT, REPROVISION);

    /** Ordinal, then name: {@code "1:SOFT"}. Plain ASCII, so it prints the same on every console. */
    public String label() {
        return ordinal() + ":" + name();
    }

    /** DESIGN.md 6.3: "Rungs 1-3 should cost seconds and handle the common cases." */
    public boolean isCheap() {
        return this.compareTo(SOFT) >= 0 && this.compareTo(RESET) <= 0;
    }

    /** DESIGN.md 6.3: a drift toward rungs 4-5 "is a hardware or design signal, not noise". */
    public boolean isStructuralSignal() {
        return this == PREFLIGHT || this == REPROVISION;
    }
}
