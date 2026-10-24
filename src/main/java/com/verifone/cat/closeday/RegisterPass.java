package com.verifone.cat.closeday;

import java.io.PrintStream;

/**
 * The register's pass before the close: clear a blocking nag (NOT ONBOARDED, a fault message) and any
 * open or suspended sale, so the register can acknowledge the close's lock (HANDOFF.md 1.60, 1.166).
 *
 * <p>The Python's pass drives the POS through CDP (its {@code connect_pos} and page objects). The Java
 * POS driver exists, but this pass is not ported onto it, so the production default is
 * {@link #NOT_PORTED}, which says so and lets the close go on, as the Python does when the register
 * cannot be reached. A close with a nag up can then be cancelled by the Commander: CAT-Java's day close
 * is not safe to rely on against a register with a nag until this is wired.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface RegisterPass {

    /** Clears what blocks the lock acknowledgement. Best effort: a failure is reported, never thrown. */
    void clearBlockingNag(PrintStream err);

    /** The production default until the register pass is ported: reports that it did not run, and closes. */
    RegisterPass NOT_PORTED = err -> err.println("could not check the register for a blocking nag before closing "
            + "the day (the register pass is not ported to CAT-Java); closing anyway");
}
