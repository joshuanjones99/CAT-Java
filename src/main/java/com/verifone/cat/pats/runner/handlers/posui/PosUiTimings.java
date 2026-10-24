package com.verifone.cat.pats.runner.handlers.posui;

import java.time.Duration;

/**
 * The POS UI family's ceilings and settle windows, with the values the Python's {@code pats/runner/pos_ui.py} and
 * {@code pats/runner/core.py} use. A test shortens them; a rig run takes {@link #defaults()}.
 *
 * @param uiWait               the ceiling on a POS UI wait (PATS's 60 s)
 * @param stateWait            the ceiling on a state wait (117 s)
 * @param pinpadWait           the ceiling on a pinpad wait (42 s)
 * @param overlayWait          how long a message overlay may stay before a press goes under it (7 s)
 * @param networkReportBuild   how long an express label may take to appear after a keypad OK (10 s)
 * @param touchSettle          PATS's pause before and after a touch (2 s); the settle cap
 * @param settleQuiet          how long the screen must hold still after a press (0.2 s)
 * @param readyQuiet           how long the screen must hold still once the POS reads Ready (1 s)
 * @param afterFailureReady    how long a case's first press waits for Ready after a failed case (10 s)
 * @param epsBusyWait          how long a press the EPS answered "busy" is retried for (300 s)
 * @param epsBusyRetryGap      the gap between those retries (15 s)
 * @param pumpCallingWait      how long a lifted pump's tile may take to show it calling (10 s)
 * @param dialogDeadEnd        how long an OK-only message may stay unchanged before a wait gives up (20 s)
 * @param dialogLook           how often a wait looks for a dead end (2 s)
 * @param appSwitchSettle      PATS's pause for an app switch (90 s)
 * @param poll                 the interval of an ordinary poll (0.1 s)
 * @param clickableLook        the interval of the clickable poll (0.1 s)
 * @param menuPoll             the poll for the main menu after an EXIT (3 s, every 0.25 s in the Python)
 */
public record PosUiTimings(
        Duration uiWait,
        Duration stateWait,
        Duration pinpadWait,
        Duration overlayWait,
        Duration networkReportBuild,
        Duration touchSettle,
        Duration settleQuiet,
        Duration readyQuiet,
        Duration afterFailureReady,
        Duration epsBusyWait,
        Duration epsBusyRetryGap,
        Duration pumpCallingWait,
        Duration dialogDeadEnd,
        Duration dialogLook,
        Duration appSwitchSettle,
        Duration poll,
        Duration clickableLook,
        Duration menuPoll) {

    /** The Python's values. */
    public static PosUiTimings defaults() {
        return new PosUiTimings(
                Duration.ofSeconds(60), Duration.ofSeconds(117), Duration.ofSeconds(42), Duration.ofSeconds(7),
                Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofMillis(200), Duration.ofSeconds(1),
                Duration.ofSeconds(10), Duration.ofSeconds(300), Duration.ofSeconds(15), Duration.ofSeconds(10),
                Duration.ofSeconds(20), Duration.ofSeconds(2), Duration.ofSeconds(90), Duration.ofMillis(100),
                Duration.ofMillis(100), Duration.ofSeconds(3));
    }
}
