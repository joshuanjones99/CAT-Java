package com.verifone.cat.pats.runner.handlers.posui;

import java.time.Duration;

/**
 * What the POS UI family needs of the case's fuel pumps ({@code FixtureName.FUEL_PUMPS}). The pumps' own family
 * implements it; this is the seam, so the family does not depend on how a pump is driven.
 */
public interface FuelPumpsView {

    /**
     * Waits for a hang-up on {@code pump} to post its sale to the POS's fuel panel.
     *
     * @return whether the sale was posted within {@code timeout}
     */
    boolean waitForPostedSale(int pump, Duration timeout);

    /** Whether {@code pump} is lifted. */
    boolean lifted(int pump);

    /** The pump's state, as the Commander reports it ({@code BUSYON}, {@code DCRIDLEON}, ...). */
    String state(int pump);
}
