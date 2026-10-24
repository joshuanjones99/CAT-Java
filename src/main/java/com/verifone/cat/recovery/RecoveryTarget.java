package com.verifone.cat.recovery;

/**
 * The rig as the ladder sees it: one method per rung plus one probe. Ported from the Python
 * {@code petrotest.recovery.ladder.RecoveryTarget}.
 *
 * <p>Each rung method returns {@code true} for "I did the thing", not "the rig is now fine". Only
 * {@link #isTestable()} answers that, and the ladder asks it after every performed rung. A rung
 * that is not available on this port throws {@link com.verifone.cat.devices.DeviceUnavailable}
 * rather than returning {@code false}, so the report says why (CLAUDE.md hard rule 5).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface RecoveryTarget {

    String rigName();

    /** Cheap, read-only: can the next test start against this rig? */
    boolean isTestable();

    /** Rung 1 SOFT: cancel/escape ladder on the POS; dismiss overlays. */
    boolean softCancel();

    /** Rung 2 VOID: void the open ticket; clear any parked transaction. */
    boolean voidOpenTicket();

    /** Rung 3 RESET: reset device state; reconnect CDP. */
    boolean resetState();

    /** Rung 4 PREFLIGHT: preflight with repair enabled (DESIGN.md 6.2). */
    boolean runPreflight();

    /** Rung 5 REPROVISION: provision --force, then preflight again. */
    boolean reprovision();

    /** Rung 6 DRAIN: mark the rig unhealthy and report loudly. Cannot fail. */
    void drain(String reason);
}
