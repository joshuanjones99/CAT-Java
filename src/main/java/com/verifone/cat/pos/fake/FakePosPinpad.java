package com.verifone.cat.pos.fake;

import com.verifone.cat.pos.pinpad.PinpadDouble;
import java.util.List;

/**
 * Wires the fake POS's MOP taps to the pinpad double. Ported from the Python
 * {@code plugin._wire_fake_pos_to_pinpad}.
 *
 * <p>Cash and credit are wired at different points, as the Python does. Cash never touches the
 * pinpad on a real register, so the cash hook puts the round-up prompt on the pinpad itself, as if
 * the POS had told ViperPay. Credit does go through the pinpad: the credit hook shows SWIPE CARD,
 * and the round-up prompt follows the card insert through {@link PinpadDouble#setAfterCard}. A
 * "donate" answer then shows the cashback offer through {@link PinpadDouble#setAfterKey}.
 *
 * <p>The Python wiring was module-global and was undone by {@code set_mop_*_callback(None)}. Here
 * the hooks belong to the {@link FakePos} they are passed to, so there is nothing global to undo;
 * {@link PinpadDouble#reset()} clears the pinpad's callbacks.
 *
 * <p>A double. The prompt text ("donate", "SWIPE CARD") is the corpus's own, unverified against a
 * real pinpad.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class FakePosPinpad {

    /** The round-up prompt, as the corpus matches it. */
    static final String DONATE_PROMPT = "donate";

    /** The card prompt a credit tap raises. */
    static final String SWIPE_PROMPT = "SWIPE CARD";

    /** The cashback offer after a donate answer (PETROCORE-61390). */
    static final String CASHBACK_PROMPT = "CASHBACK";

    private FakePosPinpad() {}

    /** The hooks that drive {@code pinpad}, and the pinpad callbacks that chain its prompts. */
    public static FakePos.Hooks wire(PinpadDouble pinpad) {
        pinpad.setAfterCard(action -> {
            if ("insert".equals(action)) {
                showDonate(pinpad);
            }
        });
        pinpad.setAfterKey((key, answered) -> {
            if (DONATE_PROMPT.equals(answered)) {
                pinpad.setDisplay(List.of("LOOPBACK/CASHBACK", "Cash back?"), CASHBACK_PROMPT);
            }
        });
        return new FakePos.Hooks(
                () -> showDonate(pinpad),
                () -> pinpad.setDisplay(List.of("LOOPBACK/SWIPE", "CARD"), SWIPE_PROMPT));
    }

    private static void showDonate(PinpadDouble pinpad) {
        pinpad.setDisplay(List.of("LOOPBACK/DONATE", "Round up for"), DONATE_PROMPT);
    }
}
