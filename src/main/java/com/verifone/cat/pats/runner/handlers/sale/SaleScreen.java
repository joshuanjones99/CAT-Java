package com.verifone.cat.pats.runner.handlers.sale;

import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.pages.BaseElement;
import com.verifone.cat.pos.pages.Overlay;
import com.verifone.cat.pos.pages.Pos;
import com.verifone.cat.pos.driver.PosDriver;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the sale handlers read off the POS screen: the overlay, the express panel's labels, the screen text, and the
 * receipt just printed. The Python reads these through {@code pos_ui} and the driver; the sale family takes them
 * through this seam so the handlers test against fakes.
 *
 * <p>{@link #of(Pos)} is the production reader, over the Pos page objects and the driver.
 */
public interface SaleScreen {

    /** The Python's {@code SETTLE_QUIET}: a screen that holds still this long has settled. */
    Duration SETTLE_QUIET = Duration.ofMillis(200);

    /** Everything the page renders as text, right now. No wait. */
    String screenText();

    /**
     * Waits until the screen differs from {@code before} and holds still, or {@code cap} passes. True when the screen
     * changed (PATS's {@code settle}).
     */
    boolean settled(String before, Duration cap);

    /**
     * The overlay on screen: {@code TITLE}, {@code LINE1}, {@code LINE2} and {@code LINE3}, each the visible text of
     * its element. Null when no overlay text is up.
     */
    Map<String, String> overlay();

    /** The express panel's visible button labels. */
    List<String> expressLabels();

    /** Clears any overlay that blocks the POS, a nag included (PATS's sweep; the Python's {@code _clear_nag}). */
    void clearBlockingOverlays();

    /** The text of the receipt just printed. */
    String receiptText();

    /** The production reader, over the Pos page objects. */
    static SaleScreen of(Pos pos) {
        return new PosSaleScreen(pos);
    }

    /** Reads the screen through the Pos page objects and the driver. */
    final class PosSaleScreen implements SaleScreen {

        private final Pos pos;

        PosSaleScreen(Pos pos) {
            this.pos = java.util.Objects.requireNonNull(pos, "pos");
        }

        private PosDriver driver() {
            return pos.driver();
        }

        @Override
        public String screenText() {
            return driver().screenText();
        }

        @Override
        public boolean settled(String before, Duration cap) {
            PosDriver.Settled settled = driver().settleScreen(before, SETTLE_QUIET, cap, false);
            return settled.changed() && !settled.text().equals(before);
        }

        @Override
        public Map<String, String> overlay() {
            Overlay overlay = pos.overlay();
            Map<String, String> read = new LinkedHashMap<>();
            read.put("TITLE", lastVisible(overlay.title()));
            read.put("LINE1", lastVisible(overlay.line1()));
            read.put("LINE2", lastVisible(overlay.line2()));
            read.put("LINE3", lastVisible(overlay.line3()));
            return read.values().stream().anyMatch(text -> !text.isEmpty()) ? read : null;
        }

        private String lastVisible(BaseElement element) {
            Locator locator = element.locator();
            List<String> shown = new ArrayList<>();
            for (PosDriver.ElementState state : driver().elementStates(locator)) {
                if (state.visible()) {
                    shown.add(state.text());
                }
            }
            return shown.isEmpty() ? "" : shown.get(shown.size() - 1);
        }

        @Override
        public List<String> expressLabels() {
            return driver().visibleTexts(Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N);
        }

        @Override
        public void clearBlockingOverlays() {
            driver().clearBlockingOverlays(PosDriver.OVERLAY_SETTLE);
        }

        @Override
        public String receiptText() {
            return pos.receipt().text();
        }
    }
}
