package com.verifone.cat.launch;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.devices.vats.PhysicalPinpad;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.DriverTimeout;
import com.verifone.cat.pos.driver.PollUntil;
import com.verifone.cat.pos.driver.PosDriver;

/**
 * Recovery's VOID rung on a {@code pinpad: vats} rig: end the payment the physical pinpad still holds, then wait
 * (bounded) for the POS. A port of the Python CAT's {@code petrotest.recovery.plugin._abort_pinpad_payment}.
 *
 * <p>With a card still in the M400 the void did nothing and the run drained (run 79d2c092eb4d, 2026-10-12). So before
 * the sale is voided: {@link PhysicalPinpad#abortPayment} (CANCEL, the card out, BTT's card stopped), the open
 * message's Cancel, then up to {@link #PINPAD_ABORT_SETTLE} for the POS to leave the payment. On any other rig, and on a
 * vats run whose session never opened, this does nothing: a simulator's payment ends with the message's Cancel alone.
 *
 * <p>Hook (LivePosScreen.voidOpenSale, owned by step4): after the review overlay is closed and before
 * {@code cancelOpenMessage(driver)}, {@code VatsRecovery.abortPinpadPayment(driver);}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class VatsRecovery {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.recovery");

    /** How long the POS gets to leave the payment (its CREDIT message closing) after the abort: the Python's 10 s. */
    public static final Duration PINPAD_ABORT_SETTLE = Duration.ofSeconds(10);

    /** A press's wait: the Python's 5 s. */
    static final Duration PRESS_WAIT = Duration.ofSeconds(5);

    private static final Duration POLL = Duration.ofMillis(500);

    private VatsRecovery() {}

    /** The run's open VATS pinpad's payment ended ({@link VatsRunPinpad#current()}); nothing when there is none. */
    public static void abortPinpadPayment(PosDriver driver) {
        abortPinpadPayment(VatsRunPinpad.current(), driver);
    }

    /** {@link #abortPinpadPayment(PosDriver)} with the pinpad given; a null pinpad does nothing. */
    public static void abortPinpadPayment(PhysicalPinpad pinpad, PosDriver driver) {
        if (pinpad == null) {
            return;
        }
        LOG.info("recovery VOID: pinpad payment aborted: {}", String.join(", ", pinpad.abortPayment()));
        cancelOpenMessage(driver);
        try {
            PollUntil.until(() -> driver.elementStates(Locators.OVERLAY_MSG_VIEW_TITLE).stream()
                    .noneMatch(PosDriver.ElementState::visible), PINPAD_ABORT_SETTLE, POLL,
                    "the POS to leave the payment");
        } catch (DriverTimeout e) {
            LOG.warn("recovery VOID: a POS message is still up after the pinpad abort");
        }
    }

    /** The Python's {@code _cancel_open_message}: press a message's Cancel, which a Void Ticket tap does not get past. */
    static void cancelOpenMessage(PosDriver driver) {
        if (driver.elementStates(Locators.OVERLAY_MSG_VIEW_CANCEL_BTN).stream()
                .anyMatch(s -> s.visible() && "Cancel".equals(s.text()))) {
            driver.press(Locators.OVERLAY_MSG_VIEW_CANCEL_BTN, PRESS_WAIT);
        }
    }
}
