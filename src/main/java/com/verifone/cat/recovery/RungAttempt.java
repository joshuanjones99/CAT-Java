package com.verifone.cat.recovery;

import java.time.Duration;

/** One rung, and what came of it. Ported from {@code petrotest.recovery.ladder.RungAttempt}. */
public record RungAttempt(Rung rung, boolean performed, boolean testableAfter, Duration elapsed, String detail) {

    public RungAttempt {
        elapsed = elapsed == null ? Duration.ZERO : elapsed;
        detail = detail == null ? "" : detail;
    }
}
