package com.verifone.cat.closeday;

import java.io.PrintStream;

/**
 * The register's pass before the close: clear a blocking nag (NOT ONBOARDED, a fault message) and any
 * open or suspended sale, so the register can acknowledge the close's lock (HANDOFF.md 1.60, 1.166).
 *
 * <p>The production pass is {@link PosRegisterPass}, which drives the POS through CDP as the Python's
 * {@code _clear_register_nag} does. {@link #NOT_PORTED} remains for callers that must not touch the register
 * (the stuck-close recovery) and for tests: it says the pass did not run and lets the close go on, as the
 * Python does when the register cannot be reached.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface RegisterPass {

    /** Clears what blocks the lock acknowledgement. Best effort: a failure is reported, never thrown. */
    void clearBlockingNag(PrintStream err);

    /** A pass that does not touch the register: reports that it did not run, and closes. */
    RegisterPass NOT_PORTED = err -> err.println("could not check the register for a blocking nag before closing "
            + "the day (the register pass is not ported to CAT-Java); closing anyway");
}
