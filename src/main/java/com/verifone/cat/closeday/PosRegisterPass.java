package com.verifone.cat.closeday;

import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.verifone.cat.devices.forecourt.ForecourtTimeouts;
import com.verifone.cat.devices.forecourt.PetroPumps;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.pages.ItemPanel;
import com.verifone.cat.recovery.Timeouts;
import com.verifone.cat.rig.Rig;

/**
 * The register's pass before the day close, driven through the POS: a port of the Python's
 * {@code close_day._clear_register_nag} and everything it calls ({@code _dismiss_messages},
 * {@code _return_to_sales}, {@code _void_open_sale}, {@code _void_suspended}, {@code _settle_forecourt},
 * {@code _clear_prepays}, {@code _rebuy_prepay}, {@code _press_ok}, {@code _answer_sign_on_messages}).
 *
 * <p>The Commander waits for every client to acknowledge the close's lock and cancels the close when one does not
 * (HANDOFF.md 1.60). A nag (NOT ONBOARDED), an OK-only message, or an open sale on the register blocks that
 * acknowledgement; a suspended transaction makes the Sales Service veto the close (1.166). So, in the Python's
 * order: the driver's own nag guard, the OK-only messages, back from the CSR functions page, void the open sale,
 * recall and void each suspended transaction, settle the forecourt (sign on at the login screen, refund each prepay
 * nobody fuelled through Petro, pay off each pump's due fill in CASH, report a reserve), then the guard and the
 * messages again, since the settle's sales raise the nag again.
 *
 * <p>Best effort, as the Python: a register that cannot be reached, or a step that fails, is reported on the close's
 * error stream with "closing anyway", and the close is sent. Nothing is thrown. Every wait is bounded
 * ({@link Timing}); none is {@code Thread.sleep}.
 *
 * <p>Deviations from the Python, each deliberate:
 * <ul>
 *   <li>The register and Petro come through seams ({@link RegisterScreen.Connector}, {@link PrepayPumps.Opener})
 *       rather than {@code connect_pos} and {@code PetroPumps.started} called inline, so tests drive fakes and the
 *       caller decides when PATS's port 1072 may be opened. {@link #forRig} builds the production ones the way the
 *       Python does.</li>
 *   <li>Petro failing to open is caught whatever the exception (the Python catches {@code OSError} only; anything
 *       else there ended the whole pass). The prepays are left and the rest of the settle goes on.</li>
 *   <li>The cashier's password comes from {@code Rig.Cashier.resolvePassword}, so an inline {@code password} works
 *       as well as {@code password_env}; with no cashier in the rig file the settle is skipped with a line, where
 *       the Python's attribute error ended the pass.</li>
 *   <li>{@code _settle_forecourt}'s unused {@code release_pump} closure is not ported ({@code Pos.clear_reserve} is
 *       off in the Python too, HANDOFF.md 1.147).</li>
 *   <li>A Playwright assertion failure ({@link AssertionError}) is caught where the Python catches
 *       {@code Exception}, which in Python includes its own {@code AssertionError}.</li>
 * </ul>
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python's lab evidence is in its docstrings (HANDOFF.md 1.60, 1.88
 * item 29, 1.147, 1.166; runs 726c85ae343d, 9cb72daf2bdf, 4a04258badad); this class has only run against fakes.
 */
public final class PosRegisterPass implements RegisterPass {

    /**
     * The pass's waits, the Python's module constants. A test shrinks them.
     *
     * @param messageGone       {@code MESSAGE_GONE_SECONDS}: a pressed message's OK, and its going
     * @param recallWait        {@code RECALL_WAIT}: the suspended list, the recalled sale, the sales page
     * @param settleWait        {@code SETTLE_WAIT}: each press and claim in the forecourt settle
     * @param productPromptWait {@code PRODUCT_PROMPT_WAIT}: the fuel products prompt's Skip key
     * @param prepayPaidWait    {@code PREPAY_PAID_WAIT}: a re-bought prepay's CASH payment to leave the register idle
     * @param signOnNagWait     {@code SIGN_ON_NAG_WAIT}: how long a message may take to show after a sign-on or sale
     * @param detailCloseWait   the detail view's Close press (the Python's literal 5.0)
     * @param poll              the Python's {@code interval=0.25}
     */
    public record Timing(Duration messageGone, Duration recallWait, Duration settleWait, Duration productPromptWait,
            Duration prepayPaidWait, Duration signOnNagWait, Duration detailCloseWait, Duration poll) {

        public static Timing production() {
            return new Timing(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(10),
                    Duration.ofSeconds(3), Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(5),
                    Duration.ofMillis(250));
        }
    }

    /** How many OK-only messages, one after another, are pressed away. */
    static final int MAX_MESSAGES = 5;
    /** Suspended transactions recalled and voided before giving up. */
    static final int MAX_SUSPENDED = 30;
    /** Claims tried on one pump: its rows, each possibly a Sold one that only raises an error. */
    static final int MAX_CLAIMS_PER_PUMP = 4;
    /** The express panel's keys, {@code ExpressPanelView_ButtonID_0..11}. */
    static final int EXPRESS_KEYS = 12;
    /** The prepay button a stranded prepay is re-bought with: $10 on Reg101. Any amount does. */
    static final int REBUY_PREPAY_BUTTON = 2;
    /** The express key that reads "Skip" at the fuel products prompt. */
    static final int SKIP_KEY = 3;

    static final String RECALL_LABEL = "Recall";
    static final String RETURN_TO_SALES_LABEL = "Return To Sales";
    static final String OK = "OK";
    static final String SKIP = "Skip";
    static final String BUSYOFF = "BUSYOFF";

    /** A tile's amount with no sign: a prepay paid and not fuelled, or a fill a sale holds. */
    static final Pattern UNSIGNED = Pattern.compile("^\\d+\\.\\d\\d$");

    /** Each sale row's "Current"/"Stacked" label in a pump's detail view; every row repeats the one id. */
    private static final Locator DETAIL_ROWS =
            Locators.FUEL_POINT_DETAIL_OVERLAY_VIEW_MAIN_PANEL_TRANSACTION_PANEL_TRANS_STATUS_PANEL_TRANS_LBL;

    private final RegisterScreen.Connector connector;
    private final PrepayPumps.Opener petro;
    private final String cashierId;
    private final String cashierSecretName;
    private final Supplier<String> password;
    private final Timing timing;
    private final PrintStream out;

    /**
     * @param connector         opens the register (the Python's {@code connect_pos})
     * @param petro             opens Petro for the prepays (the Python's {@code _petro_pumps}); null leaves them
     * @param cashierId         the rig's cashier, signed on when the register sits at its login screen; null: none
     * @param cashierSecretName how the password is named in the "not set" line ({@code $NAME}, as the Python)
     * @param password          the cashier's password, read only when a sign-on is needed; "" when unset
     * @param timing            the waits
     * @param out               progress lines (the Python's stdout)
     */
    public PosRegisterPass(RegisterScreen.Connector connector, PrepayPumps.Opener petro, String cashierId,
            String cashierSecretName, Supplier<String> password, Timing timing, PrintStream out) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.petro = petro;
        this.cashierId = cashierId;
        this.cashierSecretName = cashierSecretName == null ? "" : cashierSecretName;
        this.password = password == null ? () -> "" : password;
        this.timing = Objects.requireNonNull(timing, "timing");
        this.out = Objects.requireNonNull(out, "out");
    }

    /**
     * The production pass for a rig: its primary register over CDP, Petro on the controller (PATS's port 1072,
     * opened only when a prepay nobody fuelled is found), the rig's cashier with its password from {@code env}.
     */
    public static PosRegisterPass forRig(Rig rig, Map<String, String> env, PrintStream out) {
        Timeouts timeouts = Timeouts.of(rig.timeouts());
        PrepayPumps.Opener petro = () -> PrepayPumps.of(
                PetroPumps.started(rig.controller().host(), ForecourtTimeouts.of(timeouts)), timeouts.http());
        Rig.Cashier cashier = rig.cashier();
        return new PosRegisterPass(() -> PosRegisterScreen.connect(rig), petro,
                cashier == null ? null : cashier.id(),
                cashier == null ? "" : (cashier.passwordEnv().isEmpty() ? "cashier.password" : cashier.passwordEnv()),
                () -> cashier == null ? "" : cashier.resolvePassword(env), Timing.production(), out);
    }

    // -- the pass -----------------------------------------------------------------------------

    /** The Python's {@code _clear_register_nag}. Never throws: a failure is reported and the close goes on. */
    @Override
    public void clearBlockingNag(PrintStream err) {
        try (RegisterScreen screen = connector.connect()) {
            screen.clearBlockingOverlays();
            dismissMessages(screen);
            returnToSales(screen);
            voidOpenSale(screen);
            voidSuspended(screen);
            settleForecourt(screen);
            // The settle signs on and may ring sales, each of which can raise a nag.
            screen.clearBlockingOverlays();
            dismissMessages(screen);
        } catch (RuntimeException | AssertionError e) {
            err.println("could not check the register for a blocking nag before closing the day ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + "); closing anyway");
        }
    }

    // -- messages -----------------------------------------------------------------------------

    /**
     * Presses OK on each OK-only message the register shows; nothing else is answered. A nag goes to the driver's
     * guard, which presses it only when nothing covers it; one the guard leaves up ends the dismissals.
     */
    void dismissMessages(RegisterScreen screen) {
        for (int i = 0; i < MAX_MESSAGES; i++) {
            String message = title(screen);
            if (message.isEmpty()) {
                return;
            }
            String upper = message.toUpperCase(Locale.ROOT);
            if (PosDriver.NAG_OVERLAY_TITLES.stream().anyMatch(upper::contains)) {
                screen.clearBlockingOverlays();
                if (title(screen).equals(message)) {
                    return; // buried under another view: not blocking, and not ours to press
                }
                continue;
            }
            Locator ok = null;
            for (Locator button : List.of(Locators.OVERLAY_MSG_VIEW_OK_BTN, Locators.OVERLAY_MSG_VIEW_CANCEL_BTN)) {
                if (shows(screen, button, OK)) {
                    ok = button;
                    break;
                }
            }
            if (ok == null) {
                return; // it asks something; the close will fail on it, which says so
            }
            out.println("dismissing '" + message + "' on the register before closing the day");
            screen.press(ok, timing.messageGone());
            String gone = message;
            poll(() -> !title(screen).equals(gone), timing.messageGone());
        }
    }

    /** Presses a visible OK. "Error adding fuel sale to ticket" has no title, so {@link #dismissMessages} misses it. */
    void pressOk(RegisterScreen screen) {
        if (shows(screen, Locators.OVERLAY_MSG_VIEW_OK_BTN, OK)) {
            screen.press(Locators.OVERLAY_MSG_VIEW_OK_BTN, timing.messageGone());
        }
    }

    /**
     * Answers what signing on (or a sale) raises, while it is still on top: waits {@link Timing#signOnNagWait} for a
     * message to show, then {@link #dismissMessages}. A register with none loses that much and nothing else.
     */
    void answerSignOnMessages(RegisterScreen screen) {
        BooleanSupplier messageUp = () -> screen.elementStates(Locators.OVERLAY_MSG_VIEW_TITLE).stream()
                .anyMatch(s -> s.visible() && !s.text().strip().isEmpty());
        if (poll(messageUp, timing.signOnNagWait())) {
            dismissMessages(screen);
        }
    }

    private static String title(RegisterScreen screen) {
        for (PosDriver.ElementState state : screen.elementStates(Locators.OVERLAY_MSG_VIEW_TITLE)) {
            if (state.visible()) {
                return state.text();
            }
        }
        return "";
    }

    private static boolean shows(RegisterScreen screen, Locator locator, String text) {
        return screen.elementStates(locator).stream().anyMatch(s -> s.visible() && s.text().equals(text));
    }

    // -- the sale and the suspended ones ------------------------------------------------------

    /** Clears the open sale (a fuel sale paid in CASH, any other voided); an idle register or the login is left. */
    void voidOpenSale(RegisterScreen screen) {
        // No cashier, no sale: a register fresh from a PSI install sits at its login screen.
        if (screen.atLogin() || screen.isIdle()) {
            return;
        }
        if (screen.reviewOverlayShown()) {
            screen.closeReviewOverlay();
        }
        out.println("sale open on the register before closing the day: " + screen.clearOpenSale());
    }

    /** The express key showing {@code label}, or null. */
    static Locator expressButton(RegisterScreen screen, String label) {
        for (int i = 0; i < EXPRESS_KEYS; i++) {
            Locator button = Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N.at(i);
            if (screen.elementStates(button).stream().anyMatch(s -> s.visible() && s.text().strip().equals(label))) {
                return button;
            }
        }
        return null;
    }

    /** Leaves the CSR functions page for the sales page: that page has neither Void Ticket nor Recall (726c85ae343d). */
    void returnToSales(RegisterScreen screen) {
        Locator back = expressButton(screen, RETURN_TO_SALES_LABEL);
        if (back == null) {
            return;
        }
        out.println("register on the CSR functions page before closing the day: Return To Sales");
        screen.press(back, null);
        poll(() -> expressButton(screen, RETURN_TO_SALES_LABEL) == null, timing.recallWait());
    }

    /**
     * Recalls and voids every suspended transaction; how many were voided. On the idle register Recall lists them,
     * Recall again brings the first onto the ticket, and {@link #voidOpenSale} voids it (PATS's 87462).
     */
    int voidSuspended(RegisterScreen screen) {
        int voided = 0;
        for (int i = 0; i < MAX_SUSPENDED; i++) {
            if (screen.atLogin() || !screen.isIdle()) {
                break;
            }
            Locator button = expressButton(screen, RECALL_LABEL);
            if (button == null) {
                break;
            }
            screen.press(button, null);
            if (!poll(() -> !screen.visibleTexts(Locators.REVIEW_OVERLAY_VIEW_ITEM_N).isEmpty(), timing.recallWait())) {
                dismissMessages(screen); // nothing suspended: whatever the POS said instead
                break;
            }
            button = expressButton(screen, RECALL_LABEL);
            if (button == null) {
                break;
            }
            screen.press(button, null);
            if (!poll(() -> !screen.isIdle(), timing.recallWait())) {
                break;
            }
            voidOpenSale(screen);
            voided++;
        }
        if (voided > 0) {
            out.println("suspended transactions recalled and voided before closing the day: " + voided);
        }
        return voided;
    }

    // -- the forecourt ------------------------------------------------------------------------

    /** A pump tile owes money: "1+20.23+15.15" a fill due, "16-10.00" a refund due. */
    static boolean due(String text) {
        return text.contains("+") || text.contains("-");
    }

    /**
     * Pays off every pump's unpaid fills in CASH. Signs a cashier on with an open till when the register is at its
     * login screen; refunds each prepay nobody fuelled ({@link #clearPrepays}); claims each due fill by its tile (or
     * each row of its detail view in turn) and pays it; reports a Sold one, a fill still due, and a reserve.
     */
    void settleForecourt(RegisterScreen screen) {
        if (screen.atLogin()) {
            String secret = cashierId == null ? "" : password.get();
            if (cashierId == null) {
                out.println("not settling pump sales: the rig file names no cashier");
                return;
            }
            if (secret == null || secret.isEmpty()) {
                out.println("not settling pump sales: $" + cashierSecretName + " is not set");
                return;
            }
            screen.signOnFromLogin(cashierId, secret);
            answerSignOnMessages(screen);
        }
        clearPrepays(screen);

        for (int pump = 1; !screen.elementStates(tile(pump)).isEmpty(); pump++) {
            final int p = pump;
            for (int attempt = 0; attempt < MAX_CLAIMS_PER_PUMP; attempt++) {
                if (!due(tileText(screen, p))) {
                    break;
                }
                screen.press(tile(p), timing.settleWait());
                poll(() -> screen.holdsDispensedFuel() || !screen.elementStates(DETAIL_ROWS).isEmpty(),
                        timing.settleWait());
                if (!screen.holdsDispensedFuel() && screen.dispatchPress(DETAIL_ROWS, attempt)) {
                    poll(screen::holdsDispensedFuel, timing.settleWait());
                }
                if (screen.holdsDispensedFuel()) {
                    out.println("pump " + p + ": paying off a fill left due (" + screen.clearOpenSale() + ")");
                    answerSignOnMessages(screen);
                } else {
                    out.println("pump " + p + ": sale " + (attempt + 1) + " not claimed (paid, not finalised?)");
                    pressOk(screen);
                }
                if (!screen.elementStates(Locators.FUEL_POINT_DETAIL_OVERLAY_VIEW_CLOSE_BUTTON).isEmpty()) {
                    screen.press(Locators.FUEL_POINT_DETAIL_OVERLAY_VIEW_CLOSE_BUTTON, timing.detailCloseWait());
                }
            }
            String text = tileText(screen, p);
            if (due(text)) {
                out.println("pump " + p + " still has a fill due ('" + text + "'); left for a person");
            }
            Optional<String> held = Optional.empty();
            try { // a report, never a reason to stop the close
                held = screen.isIdle() ? screen.reserved(p) : Optional.empty();
            } catch (RuntimeException | AssertionError ignored) {
                // not reported: the Python suppresses it too
            }
            if (held.isPresent() && !held.get().isEmpty()) {
                // Reported, not cleared: Pos.clear_reserve failed two day closes on Reg101 (HANDOFF.md 1.147).
                out.println("pump " + p + ": " + held.get() + " reserved; left for a person (not cleared by CAT)");
            }
        }
    }

    /**
     * Turns every prepay nobody fuelled into a refund due, which the settle then claims and pays. A tile showing an
     * unsigned amount is lifted through Petro at $0.00 and hung up (PATS's 34433); one Petro reports idle has lost
     * its authorisation and a new prepay is sold first ({@link #rebuyPrepay}). A pump not cleared is reported and
     * left.
     */
    void clearPrepays(RegisterScreen screen) {
        List<Integer> prepaid = new ArrayList<>();
        for (int pump = 1; !screen.elementStates(tile(pump)).isEmpty(); pump++) {
            if (UNSIGNED.matcher(currentAmount(screen, pump)).matches()) {
                prepaid.add(pump);
            }
        }
        if (prepaid.isEmpty()) {
            return;
        }
        PrepayPumps pumps = openPetro();
        if (pumps == null) {
            return;
        }
        try {
            for (int pump : prepaid) {
                String amount = currentAmount(screen, pump);
                boolean rebought;
                try {
                    rebought = !BUSYOFF.equals(pumps.reported(pump));
                    if (rebought) {
                        rebuyPrepay(screen, pump);
                    }
                    pumps.liftAtZero(pump);
                    pumps.hangUp(pump);
                } catch (RuntimeException | AssertionError e) { // best effort, as the rest of the settle
                    out.println("pump " + pump + ": prepay " + amount + " not cleared (" + e.getMessage()
                            + "); left for a person");
                    pressOk(screen);
                    try {
                        screen.clearOpenSale();
                    } catch (RuntimeException | AssertionError ignored) {
                        // the next pump, or the close, finds what is left
                    }
                    continue;
                }
                String how = rebought ? "re-bought, then lifted" : "lifted";
                out.println("pump " + pump + ": prepay " + amount + " nobody fuelled " + how
                        + " at $0.00 for its refund");
            }
        } finally {
            pumps.close();
        }
    }

    private PrepayPumps openPetro() {
        if (petro == null) {
            out.println("prepays nobody fuelled are left for a person: Petro cannot be driven (no Petro configured)");
            return null;
        }
        try {
            return petro.open();
        } catch (RuntimeException e) {
            out.println("prepays nobody fuelled are left for a person: Petro cannot be driven (" + e.getMessage() + ")");
            return null;
        }
    }

    /**
     * Sells {@code pump} a new prepay in CASH, which the Commander authorises, as it no longer does the stranded
     * one (Reg101, 2026-10-14, pump 16). Base's {@code collectAdditionalData} may ask for fuel products at Total,
     * under a Skip key.
     */
    void rebuyPrepay(RegisterScreen screen, int pump) {
        Duration wait = timing.settleWait();
        screen.press(Locators.FUEL_PANEL_0_FUEL_BUTTON_N.at(pump), wait);
        screen.press(Locators.FUEL_POINT_DETAIL_OVERLAY_VIEW_MAIN_PANEL_BUTTONS_PANEL_PREPAY_BUTTONS_N
                .at(REBUY_PREPAY_BUTTON), wait);
        screen.selectMop(ItemPanel.MOP.PAYMENT_GATEWAY, wait);
        Locator skip = Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N.at(SKIP_KEY);
        if (poll(() -> shows(screen, skip, SKIP), timing.productPromptWait())) {
            screen.press(skip, wait);
            screen.selectMop(ItemPanel.MOP.PAYMENT_GATEWAY, wait);
        }
        screen.selectMop(ItemPanel.MOP.CASH, wait);
        screen.keypadOk(wait);
        screen.expectIdle(timing.prepayPaidWait());
        answerSignOnMessages(screen);
    }

    private static Locator tile(int pump) {
        return Locators.FUEL_PANEL_0_FUEL_BUTTON_N_DATA.at(pump);
    }

    private static String tileText(RegisterScreen screen, int pump) {
        return firstVisible(screen, tile(pump));
    }

    private static String currentAmount(RegisterScreen screen, int pump) {
        return firstVisible(screen, Locators.FUEL_PANEL_0_FUEL_BUTTON_N_LABEL_CURRENT.at(pump));
    }

    private static String firstVisible(RegisterScreen screen, Locator locator) {
        for (PosDriver.ElementState state : screen.elementStates(locator)) {
            if (state.visible()) {
                return state.text();
            }
        }
        return "";
    }

    /** Checks at once, then every {@link Timing#poll}, until {@code check} holds or {@code timeout} passes. */
    private boolean poll(BooleanSupplier check, Duration timeout) {
        return Waits.until(() -> check.getAsBoolean() ? Optional.of(Boolean.TRUE) : Optional.<Boolean>empty(),
                timeout, timing.poll()).isPresent();
    }
}
