package com.verifone.cat.pos.pages;

import java.time.Duration;

import com.verifone.cat.pos.driver.PosDriver;

// Stub; ported in wave7/pagesb.
public class ExpressPanel extends BasePage {

    /** The Main Menu's "Log Out" button text (Python LOG_OUT_TEXT). */
    public static final String LOG_OUT_TEXT = "Log Out";

    /** The sales screen's "Void Ticket" button text (Python VOID_TICKET_TEXT). */
    public static final String VOID_TICKET_TEXT = "Void Ticket";

    public ExpressPanel(PosDriver driver) {
        super(driver);
    }

    public BaseElement keypadIcon() {
        throw new UnsupportedOperationException("ported in wave7/pagesb");
    }

    public BaseElement csrFunction() {
        throw new UnsupportedOperationException("ported in wave7/pagesb");
    }

    public BaseElement logOut() {
        throw new UnsupportedOperationException("ported in wave7/pagesb");
    }

    public BaseElement voidTicket() {
        throw new UnsupportedOperationException("ported in wave7/pagesb");
    }

    public void expectIdle(Duration timeout) {
        throw new UnsupportedOperationException("ported in wave7/pagesb");
    }

    public void expectTransactionActive(Duration timeout) {
        throw new UnsupportedOperationException("ported in wave7/pagesb");
    }

    public boolean isIdle() {
        throw new UnsupportedOperationException("ported in wave7/pagesb");
    }
}
