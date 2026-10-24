package com.verifone.cat.pats.runner.handlers.posui;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What the POS UI family needs of the case's pinpad ({@code FixtureName.PINPAD}). A pinpad that cannot settle, or
 * that never saw a decline, keeps the defaults.
 */
public interface PinpadView {

    /**
     * Waits until a physical pinpad's terminal has settled, capped at {@code seconds}.
     *
     * @return empty when this pinpad has no settle to wait on (the SLEEP is then slept or carried as usual), else
     *         whether the terminal settled within the cap
     */
    default Optional<Boolean> settle(Duration seconds) {
        return Optional.empty();
    }

    /**
     * When the pinpad last showed DECLINED, on the same clock as {@link Pace#nanoTime()}; empty when it never did.
     */
    default OptionalLong declinedAtNanos() {
        return OptionalLong.empty();
    }
}
