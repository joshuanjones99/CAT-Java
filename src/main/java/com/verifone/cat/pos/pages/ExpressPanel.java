package com.verifone.cat.pos.pages;

import java.time.Duration;
import java.util.Locale;

import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;

/**
 * The right-hand express function button strip, and idle-state detection. Port of the Python
 * {@code petrotest.pos.pages.express_panel}.
 *
 * <p>{@code ExpressPanelView_Button_ID_{n}} is the most-referenced template in the corpus, and most
 * references are {@code WIDGET_VALIDATION}: it is how a test asks "is the POS in the state I think
 * it is". Slot 7 reads "CSR Func" when idle and "Error Correct" mid-sale.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The slot numbers and texts are the Python's, from
 * Reg101 on 2026-09-14 and 2026-09-15.
 */
public class ExpressPanel extends BasePage {

    /** Slot 7 is the CSR-function button in the base config; 8,978 corpus references use it. */
    public static final int CSR_FUNCTION_SLOT = 7;
    /** Slot 3 is No Sale / Error Correct. */
    public static final int NO_SALE_SLOT = 3;
    /** "Log Out": only while the CSR Main Menu is open. */
    public static final int LOG_OUT_SLOT = 5;
    public static final String LOG_OUT_TEXT = "Log Out";
    /** "Void Ticket" on the sales screen with a sale open. One tap voids the ticket, unconfirmed. */
    public static final int VOID_TICKET_SLOT = 4;
    public static final String VOID_TICKET_TEXT = "Void Ticket";
    /** The visible text behind PATS's CSR_FCT validation key. */
    public static final String IDLE_TEXT = "CSR Func";
    /** The visible text behind PATS's ERROR_CORRECT_KEY validation key. */
    public static final String TRANSACTION_ACTIVE_TEXT = "Error Correct";

    public ExpressPanel(PosDriver driver) {
        super(driver);
    }

    public BaseElement button(int slot) {
        return element(Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N, slot);
    }

    public BaseElement csrFunction() {
        return button(CSR_FUNCTION_SLOT);
    }

    public BaseElement noSale() {
        return button(NO_SALE_SLOT);
    }

    public BaseElement logOut() {
        return button(LOG_OUT_SLOT);
    }

    public BaseElement voidTicket() {
        return button(VOID_TICKET_SLOT);
    }

    public BaseElement keypadIcon() {
        return element(Locators.EXPRESS_PANEL_VIEW_KEYPAD_ICON);
    }

    public BaseElement exitIcon() {
        return element(Locators.EXPRESS_PANEL_VIEW_EXIT_ICON);
    }

    /** No sale in progress. Retries until slot 7 reads "CSR Func", bounded by the timeout. */
    public void expectIdle(Duration timeout) {
        csrFunction().expectText(IDLE_TEXT, false, timeout);
    }

    /** A sale is open: slot 7 reads "Error Correct". */
    public void expectTransactionActive(Duration timeout) {
        csrFunction().expectText(TRANSACTION_ACTIVE_TEXT, false, timeout);
    }

    /** A snapshot, for the recovery ladder's "did rung N work?" check. No wait. */
    public boolean isIdle() {
        if (!csrFunction().isPresent()) {
            return false;
        }
        return csrFunction().text(null).toLowerCase(Locale.ROOT)
                .contains(IDLE_TEXT.toLowerCase(Locale.ROOT));
    }
}
