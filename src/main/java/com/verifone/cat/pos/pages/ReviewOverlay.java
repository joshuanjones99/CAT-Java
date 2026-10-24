package com.verifone.cat.pos.pages;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.DriverTimeout;
import com.verifone.cat.pos.driver.PollUntil;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.receipt.DomReceiptCapture.DomElement;

/**
 * {@code ReviewOverlayView}, the loyalty-reward and item-review picker, and the "Select Till" menu.
 * Port of the Python {@code pages.overlay.ReviewOverlay}.
 *
 * <p>POS-generated item ids are opaque, so {@link #selectByText} reads the menu and matches its rows
 * by substring, waiting (bounded) for the rows to render.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public class ReviewOverlay extends BasePage {

    private static final Duration POLL = Duration.ofMillis(250);

    public ReviewOverlay(PosDriver driver) {
        super(driver);
    }

    public BaseElement title() {
        return element(Locators.REVIEW_OVERLAY_VIEW_TITLE_TEXT);
    }

    public BaseElement header() {
        return element(Locators.REVIEW_OVERLAY_VIEW_HEADER_TEXT);
    }

    public BaseElement footnote() {
        return element(Locators.REVIEW_OVERLAY_VIEW_FOOT_NOTE);
    }

    /** One reviewable item, by the POS-generated item id (the 26 in the corpus). */
    public BaseElement item(int itemId) {
        return element(Locators.REVIEW_OVERLAY_VIEW_ITEM_N, itemId);
    }

    public void select(int itemId, Duration timeout) {
        item(itemId).tap(timeout);
    }

    /**
     * Select the item whose visible text contains {@code substring} (case-insensitive). Re-reads the menu
     * until a row matches or the budget runs out; the refusal names what it last saw.
     */
    public void selectByText(String substring, Duration timeout) {
        String needle = substring.toLowerCase(Locale.ROOT);
        List<String> seen = new ArrayList<>();
        Supplier<String> matchingId = () -> {
            seen.clear();
            for (DomElement element : driver().familyElements(
                    Locators.REVIEW_OVERLAY_VIEW_ITEM_N.prefixSelector())) {
                seen.add(element.text());
                if (element.text().toLowerCase(Locale.ROOT).contains(needle) && !element.id().isEmpty()) {
                    return element.id();
                }
            }
            return null;
        };
        Duration budget = timeout != null ? timeout : driver().timeouts().defaultWait();
        String elementId;
        try {
            elementId = PollUntil.value(matchingId, budget, POLL,
                    "a menu row with '" + substring + "'");
        } catch (DriverTimeout e) {
            throw new NoSuchElementException(
                    "no ReviewOverlayView_Item_* contains '" + substring + "' (menu: " + seen + ")");
        }
        driver().tapWithin(timeout, rowById(elementId));
    }

    public void close(Duration timeout) {
        element(Locators.REVIEW_OVERLAY_VIEW_CLOSE_POPUP_ID).tap(timeout);
    }

    /**
     * A catalogue-shaped locator for an id read from the DOM. The id is the page's, not a literal written
     * here; the catalogue has no entry for a POS-generated row.
     */
    private static Locator rowById(String elementId) {
        return Locator.of(elementId, Locator.Kind.ID, "ReviewOverlayView_Item", "review_overlay_view",
                0, false, false, null, null, List.of(), null);
    }
}
