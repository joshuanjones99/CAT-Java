package com.verifone.cat.pats.runner.handlers.configuration;

import com.verifone.cat.devices.DeviceUnavailable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The register as the configuration family's report actions reach it: the keys PATS's {@code LegacyRmi}
 * presses, and the reads that say when the register is ready. The POS driver and the pos-shim are behind it;
 * the handlers in this package decide the order and the waits, as the Python's {@code configuration.py} does.
 *
 * <p>Each read means what the Python's {@code pos_ui} helper of the same name means, and the POS family, not
 * this one, implements it. A key method either sends the key or throws; a key the register could not take
 * fails the step.
 *
 * <p>{@link #pendingEnter} sends an ENTER that carries a value. {@code null} and the empty string are the same
 * bare ENTER, which is what PATS's {@code pendingToken} builds for both.
 */
public interface RegisterPos {

    /** The EXIT key. */
    void exitKey();

    /** An ENTER carrying {@code value}; {@code null} or empty is a bare ENTER. */
    void pendingEnter(String value);

    /** A bare ENTER (PATS's {@code PRESS_ENTER}). */
    void pressEnter();

    /** The PRINT_REPORT key. */
    void printReport();

    /** The VOID_TICKET key. */
    void voidTicket();

    /** The screen's text, as the POS driver reads it now. */
    String screenText();

    /**
     * Waits, after a key, for the screen to answer and settle, capped as the POS family caps it.
     *
     * @param before the screen text read before the key went
     */
    void settle(String before);

    /** The run's overlay sweep: the marks PATS takes before a report's keys. */
    void sweep();

    /** Whether the POS reads Ready (the POS family's {@code _ready}). */
    boolean ready();

    /** The labels of the express panel's buttons, as read now. */
    List<String> expressLabels();

    /**
     * The labels that say the POS offers its functions: Recall, CSR Func and Price Check, as the register
     * writes them. {@code PRINT_FLASH_REPORT} waits until one of them is on the express panel.
     */
    Set<String> functionLabels();

    /**
     * The open message overlay's texts, keyed as PATS names them ({@code TITLE}, {@code LINE1}, {@code LINE2},
     * and so on), or empty when no message is up.
     */
    Optional<Map<String, String>> overlay();

    /** The register's text for the close's progress line, "Closing Cashiers". */
    String closingCashiersLine();

    /** PATS's pause before a void, error correct or keyboard key, when a message is up (the POS family's). */
    void overlayPause();

    /** Refuses every call: the POS family is not bound to this run. */
    RegisterPos UNBOUND = new RegisterPos() {
        private DeviceUnavailable unbound() {
            return new DeviceUnavailable("pos", "the report actions have no register bound; the POS family is not wired");
        }

        @Override
        public void exitKey() {
            throw unbound();
        }

        @Override
        public void pendingEnter(String value) {
            throw unbound();
        }

        @Override
        public void pressEnter() {
            throw unbound();
        }

        @Override
        public void printReport() {
            throw unbound();
        }

        @Override
        public void voidTicket() {
            throw unbound();
        }

        @Override
        public String screenText() {
            throw unbound();
        }

        @Override
        public void settle(String before) {
            throw unbound();
        }

        @Override
        public void sweep() {
            throw unbound();
        }

        @Override
        public boolean ready() {
            throw unbound();
        }

        @Override
        public List<String> expressLabels() {
            throw unbound();
        }

        @Override
        public Set<String> functionLabels() {
            throw unbound();
        }

        @Override
        public Optional<Map<String, String>> overlay() {
            throw unbound();
        }

        @Override
        public String closingCashiersLine() {
            throw unbound();
        }

        @Override
        public void overlayPause() {
            throw unbound();
        }
    };
}
