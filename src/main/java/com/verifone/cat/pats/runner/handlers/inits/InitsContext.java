package com.verifone.cat.pats.runner.handlers.inits;

import java.nio.file.Path;
import java.util.Objects;

/**
 * What the inits family needs from its run: the per-run {@link InitsState}, shared with the configuration
 * family (an import's reply is a change the next init counts from) and with the SLEEP handler, which calls
 * {@link InitsState#afterInit} and {@link InitsState#beforeInit}.
 *
 * @param state the run's init state
 */
public record InitsContext(InitsState state) {

    public InitsContext {
        Objects.requireNonNull(state, "state");
    }

    /** The production context: Petro's log under {@code toolsRoot}, the real clock, PATS's timings. */
    public static InitsContext production(Path toolsRoot) {
        return new InitsContext(InitsState.production(toolsRoot));
    }
}
