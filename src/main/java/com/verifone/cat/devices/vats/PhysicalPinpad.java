package com.verifone.cat.devices.vats;

import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;

import com.verifone.cat.pos.pinpad.PinpadDevice;

/**
 * What a physical pinpad ({@code pinpad: vats}) offers beyond {@link PinpadDevice}: the Python runner's
 * {@code hasattr(pinpad, "enter_digits")} test, made a type. A caller that holds a {@link PinpadDevice} asks
 * {@code pinpad instanceof PhysicalPinpad} where the Python asks {@code hasattr}.
 *
 * <ul>
 *   <li>{@link #insertCard(String, String, String, Duration)}: the case's {@code CARD_SIMULATION_IMAGE_FILE}, which
 *       only a physical pinpad loads (onto BTT's probe);</li>
 *   <li>{@link #enterDigits}: {@code GET_DECIMAL} keyed as written, since {@code Decimal("0042")} would drop a PIN's
 *       leading zeros;</li>
 *   <li>{@link #settle}: what replaces PATS's SLEEP after a pinpad action, ended on the terminal, not the clock;</li>
 *   <li>{@link #declinedAtNanos}: when the terminal last showed DECLINED, so a wait for a receipt ends early;</li>
 *   <li>{@link #abortPayment}: recovery's end to whatever the terminal is doing.</li>
 * </ul>
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface PhysicalPinpad extends PinpadDevice {

    /** PATS's {@code INSERT_OR_SLIDE_CARD} with the case's image; a blank image takes the rig's {@code card_images}. */
    String insertCard(String label, String image, String waitForPrompt, Duration timeout);

    /** PATS's {@code GET_DECIMAL}: the digits of {@code digits}, as written, then CONFIRM. */
    String enterDigits(String digits, String waitForPrompt, Duration timeout);

    /**
     * Waits, at most {@code budget}, until the terminal shows nothing busy and its screen has not changed for a second;
     * false if the budget ran out first.
     */
    boolean settle(Duration budget);

    /** When ({@link System#nanoTime}) the terminal was last seen declining the current card's payment, if it was. */
    OptionalLong declinedAtNanos();

    /** Ends whatever the terminal is doing (CANCEL, the card out, BTT's card stopped); what was done, one entry each. */
    List<String> abortPayment();
}
