package com.verifone.cat.pos.driver;

/**
 * A frame target could not be located in the DOM. Which iframe {@code DEVICE_MONITORING} denotes
 * is a Phase 0 rig-inspection item (see {@code Locators.FRAMES}). Failing loudly is deliberate: a
 * frame that silently does not attach turns every selector inside it into a bare timeout.
 */
public final class FrameNotFoundError extends DriverError {

    private static final long serialVersionUID = 1L;

    public FrameNotFoundError(String message) {
        super(message);
    }
}
