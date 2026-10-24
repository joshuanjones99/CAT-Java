package com.verifone.cat.pos.driver;

/**
 * A frame reloaded under a look: the look is abandoned, and the caller looks again. Internal to
 * the driver. Carries no stack trace, as it is control flow.
 */
final class FrameReloaded extends RuntimeException {

    private static final long serialVersionUID = 1L;

    FrameReloaded() {
        super("a frame reloaded under the look", null, false, false);
    }
}
