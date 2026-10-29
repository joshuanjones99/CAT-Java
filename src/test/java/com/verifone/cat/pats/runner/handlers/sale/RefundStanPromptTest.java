package com.verifone.cat.pats.runner.handlers.sale;

import static com.verifone.cat.pats.runner.handlers.HandlerHarness.act;
import static com.verifone.cat.pats.runner.handlers.HandlerHarness.caseRun;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.handlers.HandlerFakes.Pumps;
import com.verifone.cat.pats.runner.handlers.HandlerFakes.Rmi;
import com.verifone.cat.pats.runner.handlers.HandlerFakes.Scanner;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PETROCORE-36853's refund, #11 and #28-#32, through the sale family with the production overlay read
 * ({@link SaleScreen#overlayFrom}) over a fake DOM, and a recording RMI.
 *
 * <p>The live evidence (CAT-Java run 5285e78aa370, 2026-10-28): REFUND_WITH_TRANS_MAP logged "the STAN prompt did not
 * show" and "REF holds TRAN# 1010184, STAN 00052023; overlay line 1 reads " (empty), keyed the TRAN#, and the refund
 * never posted. The Python's run 2581320c4367 logged "overlay line 1 reads 'ENTER STAN'" and keyed the STAN: its
 * {@code _overlay_texts} reads the input dialog ({@code InputOverlayView_Label1}) when the message box is empty, and
 * the Java read only the message box. Evidence about this repository only; nothing here is rig-verified.
 */
class RefundStanPromptTest {

    private static final String TRAN = "1010184";
    private static final String STAN = "00052023";

    @TempDir
    Path folder;

    /** The DOM: each locator's {@code (visible, text)} elements; a locator not in it matches nothing. */
    private final Map<Locator, List<PosDriver.ElementState>> dom = new HashMap<>();
    private final Rmi rmi = new Rmi();

    private SaleScreen screen() {
        return new SaleScreen() {
            @Override
            public String screenText() {
                return "screen";
            }

            @Override
            public boolean settled(String before, Duration cap) {
                return true;
            }

            @Override
            public Map<String, String> overlay() {
                return SaleScreen.overlayFrom(locator -> dom.getOrDefault(locator, List.of()));
            }

            @Override
            public List<String> expressLabels() {
                return List.of("Price Check", "Recall");
            }

            @Override
            public void clearBlockingOverlays() {
            }

            @Override
            public String receiptText() {
                return "SALE Receipt\nStan: " + STAN + "\nST#CL120               DR#1 TRAN#" + TRAN;
            }
        };
    }

    private static PosDriver.ElementState shown(String text) {
        return new PosDriver.ElementState(true, text);
    }

    @Test
    void theRefundKeysTheStanTheInputDialogAsksForThenEnterWithItsData() {
        ActionRegistry registry = new ActionRegistry();
        SaleFamily.register(registry, new SaleContext(screen(), rmi, new Pumps(), new Scanner(), LastTlog.VALIDATED,
                d -> { }, new TouchPacer(d -> { }, SaleFamilyTest.FAST.touchSettle()), SaleFamilyTest.FAST));
        // #28-#29: the refund's card is read; the register raises its STAN prompt as an input dialog, with the
        // message box's line 1 laid out and empty.
        registry.register("INSERT_OR_SLIDE_CARD", (run, action) -> {
            dom.put(Locators.OVERLAY_MSG_VIEW_LINE1, List.of(new PosDriver.ElementState(false, "SWIPE CARD")));
            dom.put(Locators.INPUT_OVERLAY_VIEW_LABEL1, List.of(shown("ENTER STAN")));
        });

        caseRun(registry, folder, Set.of(),
                act(11, "STORE_TRANS_MAP", "REFUND_KEY", "REF"),
                act(16, "REFUND"),
                act(29, "INSERT_OR_SLIDE_CARD", "CARD_NAME", "AMEXCARD", "DEVICE", "VIPERPAY"),
                act(31, "REFUND_WITH_TRANS_MAP", "REFUND_KEY", "REF"),
                act(32, "PRESS_ENTER", "DATA", "REF")).run();

        assertEquals(List.of("refund", "enter:" + STAN, "enter:REF"), rmi.calls);
    }

    @Test
    void theOverlayReadTakesTheMessageBoxFirstThenTheInputDialog() {
        assertNull(SaleScreen.overlayFrom(locator -> dom.getOrDefault(locator, List.of())), "nothing up");

        dom.put(Locators.INPUT_OVERLAY_VIEW_TITLE, List.of(shown("Refund")));
        dom.put(Locators.INPUT_OVERLAY_VIEW_LABEL1, List.of(new PosDriver.ElementState(false, "old"),
                shown("ENTER STAN")));
        assertEquals(Map.of("TITLE", "Refund", "LINE1", "ENTER STAN", "LINE2", "", "LINE3", ""),
                SaleScreen.overlayFrom(locator -> dom.getOrDefault(locator, List.of())));

        dom.put(Locators.OVERLAY_MSG_VIEW_LINE1, List.of(shown("Card declined")));
        assertEquals(Map.of("TITLE", "", "LINE1", "Card declined", "LINE2", "", "LINE3", ""),
                SaleScreen.overlayFrom(locator -> dom.getOrDefault(locator, List.of())));
    }
}
