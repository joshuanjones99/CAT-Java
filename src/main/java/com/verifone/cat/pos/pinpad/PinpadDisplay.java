package com.verifone.cat.pos.pinpad;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a pinpad is showing: its display lines, the prompt, and the card in it. Ported from the
 * Python {@code petrotest.devices.models.PinpadDisplayResponse}.
 *
 * @param pinpad      the pinpad's id
 * @param lines       the display lines
 * @param prompt      the prompt, or null
 * @param card        the card's label, or null
 * @param cardPresent whether a card is in the pinpad
 * @param lastKey     the last key pressed, or null
 * @param lastEntry   the last decimal entered, or null
 * @param seq         orders displays for a client that waits on them
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public record PinpadDisplay(
        String pinpad,
        List<String> lines,
        String prompt,
        String card,
        boolean cardPresent,
        String lastKey,
        BigDecimal lastEntry,
        int seq) {

    public PinpadDisplay {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
