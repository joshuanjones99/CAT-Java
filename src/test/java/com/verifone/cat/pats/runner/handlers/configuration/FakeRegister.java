package com.verifone.cat.pats.runner.handlers.configuration;

import com.verifone.cat.pats.runner.handlers.inits.TestClock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * An in-memory register: it records each key as the shim would send it ({@code EXIT}, {@code ENTER:1},
 * {@code PRINT_REPORT}...), and answers the reads from fields a test sets. A "Closing Cashiers" message goes up on
 * the second bare ENTER and stays for {@link #closingFor} of simulated time (or for ever), as Reg101 showed it.
 *
 * <p>Test support only.
 */
final class FakeRegister implements RegisterPos {

    static final String CLOSING = "Closing Cashiers";
    static final String PRINT_REPORT_SCREEN = "Dismiss Report\tPrint Report";
    /** A closing message that never clears within a test: far longer than any wait the port takes. */
    static final Duration FOREVER = Duration.ofDays(365);

    private final TestClock clock;
    final List<String> keys = new ArrayList<>();
    /** Whether a message was up before each key went, in key order (a closing key must find it gone). */
    final List<Boolean> messageUpAtKey = new ArrayList<>();
    final List<String> notes = new ArrayList<>();

    /** The express panel's labels, as read. */
    List<String> express = List.of("CSR Func");
    /** Whether the POS reads Ready, as read. */
    BooleanSupplier readyNow = () -> true;
    /** The screen text, as read. */
    String screen = PRINT_REPORT_SCREEN;
    /**
     * How long the closing message stays once it goes up; zero for no message at all, {@link #FOREVER} for one that
     * never clears.
     */
    Duration closingFor = Duration.ZERO;
    /** The message overlay other than the closing one (a title and lines), or empty. */
    Optional<Map<String, String>> otherOverlay = Optional.empty();

    private int bareEnters;
    private long closingUntilNanos = -1L;

    FakeRegister(TestClock clock) {
        this.clock = clock;
    }

    /** Whether the closing message is up now. */
    boolean closingUp() {
        return clock.nanos() < closingUntilNanos;
    }

    private void key(String name) {
        messageUpAtKey.add(overlay().isPresent());
        keys.add(name);
    }

    @Override
    public void exitKey() {
        key("EXIT");
    }

    @Override
    public void pendingEnter(String value) {
        if (value == null || value.isEmpty()) {
            bareEnter();
        } else {
            key("ENTER:" + value);
        }
    }

    @Override
    public void pressEnter() {
        bareEnter();
    }

    private void bareEnter() {
        key("ENTER");
        bareEnters++;
        if (bareEnters == 2 && !closingFor.isZero()) {
            // The close's confirming ENTER: the progress message goes up on it.
            closingUntilNanos = clock.nanos() + closingFor.toNanos();
        }
    }

    /** Takes the closing message down, as a coresvcs restart does. */
    void clearClosing() {
        closingUntilNanos = -1L;
    }

    @Override
    public void printReport() {
        key("PRINT_REPORT");
    }

    @Override
    public void voidTicket() {
        key("VOID_TICKET");
    }

    @Override
    public String screenText() {
        return screen;
    }

    @Override
    public void settle(String before) {
        notes.add("settle");
    }

    @Override
    public void sweep() {
        notes.add("sweep");
    }

    @Override
    public boolean ready() {
        return readyNow.getAsBoolean();
    }

    @Override
    public List<String> expressLabels() {
        return express;
    }

    @Override
    public Set<String> functionLabels() {
        return Set.of("Recall", "CSR Func", "Price Check");
    }

    @Override
    public Optional<Map<String, String>> overlay() {
        if (closingUp()) {
            return Optional.of(Map.of("TITLE", "Close Shift", "LINE1", CLOSING, "LINE2", "Please wait"));
        }
        return otherOverlay;
    }

    @Override
    public String closingCashiersLine() {
        return CLOSING;
    }

    @Override
    public void overlayPause() {
        notes.add("overlayPause");
    }
}
