package com.verifone.cat.pats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.verifone.cat.pats.runner.PatsStepError;

import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * {@link WidgetKeys}: PATS's two widget tables, read from the copied {@code widget_keys.json}. The values are the
 * Python's (read from {@code src/petrotest/pats/data/widget_keys.json}, 2026-10-24).
 */
class WidgetKeysTest {

    private static final Function<String, String> WIDGETS = WidgetKeys.standard();

    @Test
    void anOverlayOnlyKeyReadsItsOverlayText() {
        assertEquals("Eps Busy! Please try again.", WIDGETS.apply("EPS_BUSY_MSG"));
        assertEquals("Cancelling PinPad Session", WIDGETS.apply("CANCEL_PINPAD_SESSION"));
    }

    @Test
    void aTopazOnlyKeyReadsItsTopazText() {
        assertEquals("Enter ID", WIDGETS.apply("ENTER_ID"));
    }

    @Test
    void theTopazTableWinsWhereBothHaveTheKey() {
        // PATS's getTopazRes is asked first: CANCEL is "CANCEL" in Topaz and "Cancel" in the overlay table.
        assertEquals("CANCEL", WIDGETS.apply("CANCEL"));
    }

    @Test
    void theLookupIsCaseSensitive() {
        PatsStepError error = assertThrows(PatsStepError.class, () -> WIDGETS.apply("enter_id"));
        assertEquals("VALIDATION KEY 'enter_id' is in neither TopazRes nor OverlayRes", error.getMessage());
    }

    @Test
    void anUnknownKeyNamesItself() {
        PatsStepError error = assertThrows(PatsStepError.class, () -> WidgetKeys.text("NO_SUCH_WIDGET"));
        assertEquals("VALIDATION KEY 'NO_SUCH_WIDGET' is in neither TopazRes nor OverlayRes", error.getMessage());
    }
}
