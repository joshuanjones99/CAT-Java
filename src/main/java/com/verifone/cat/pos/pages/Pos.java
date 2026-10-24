package com.verifone.cat.pos.pages;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.DriverError;
import com.verifone.cat.pos.driver.DriverTimeout;
import com.verifone.cat.pos.driver.PollUntil;
import com.verifone.cat.pos.driver.PosDomDriver;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.receipt.DomReceiptCapture;
import com.verifone.cat.pos.receipt.Receipt;
import com.verifone.cat.pos.receipt.ReceiptLine;
import com.verifone.cat.pos.receipt.ReceiptParser;
import com.verifone.cat.pos.receipt.ReceiptSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything a test does to the POS. Port of the Python {@code petrotest.pos.pages.pos.Pos}, the
 * {@code pos} fixture object that makes the DESIGN.md 5.1 vocabulary read as written.
 *
 * <p>Java builds the page objects eagerly, in the constructor: each holds only the driver, so this
 * costs nothing, as the Python's {@code cached_property} does.
 *
 * <p>Two things are injected rather than reached for (DESIGN.md 4.2 rule 6):
 * <ul>
 *   <li>{@link TlogSource}: the TLog comes from the Commander over CGILink, not from the POS
 *       (docs/commander-config-surface.md 8a). Without one, {@link #tlog()} refuses.</li>
 *   <li>the Commander's transaction numbers, newest first.</li>
 * </ul>
 *
 * <p>The fuel, item, express and sign-on pages are the sibling branch's (wave7/pagesb); this class
 * calls them through the stubs in this package until that merge.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public class Pos {

    private static final Logger LOG = LoggerFactory.getLogger(Pos.class);

    /** A ticket line for a pump's fuel: "PETR-UNLD1 CA #03" (Reg101, 2026-09-23). */
    private static final Pattern PUMP_LINE = Pattern.compile("#\\d+$");

    /** How long a CASH payoff may take to leave the register idle (Reg101, 2026-09-23). */
    private static final Duration SETTLE_IDLE_WAIT = Duration.ofSeconds(60);

    /**
     * The opening float typed on the amount-entry keypad when a fresh sign-on surfaces the till-open menu.
     * Digits only: the keypad has no decimal key, so the last two represent cents.
     */
    private static final String TILL_OPENING_FLOAT = "10000";

    /** {@code CalculatorView_TitleText} while it asks for that float (TopazRes "Enter Open Drawer Amount"). */
    private static final String OPEN_DRAWER_TITLE_TEXT = "Enter Open Drawer Amount";

    /** How long the till-open menu gets to render after the password OK (measured 1.58 s on Reg101). */
    private static final Duration TILL_CHECK_TIMEOUT = Duration.ofSeconds(10);

    /** How long the "Select Till" menu gets to close after the first tap, before the tap is repeated. */
    private static final Duration TILL_CONFIRM_TIMEOUT = Duration.ofSeconds(5);

    /** Budget for each menu, overlay and status wait in the EMV-key and sign-off flows. Not measured. */
    public static final Duration MENU_WAIT = Duration.ofSeconds(60);

    /** The two "Select Till" rows a sign-on answers with, matched by substring. */
    public static final String OPEN_TILL = "OPEN TILL";

    /** Leaves the till closed and lands on the CSR Main Menu, which is what the EMV key download needs. */
    public static final String NON_SALES_ONLY = "NON-SALES FUNCTIONS ONLY";

    /** CSR Func, then the Network Menu, then item 15: Perform EMV CA Public Key. Labels, not positions. */
    public static final List<String> EMV_KEY_DOWNLOAD_PATH = List.of(
            "2. Network Menu", "3. EPS Network Functions", "15. Perform EMV CA Public Key");

    /**
     * PATS's {@code pos.tblld.webelement.ids}: CSR Func, then the Network Menu, then the table load. The
     * last label is the whole of what Reg101 shows, not the bare prefix "3. ": a prefix also matches
     * "3. EPS Network Functions" and re-presses the parent before the EPS has answered.
     */
    public static final List<String> TABLE_LOAD_PATH = List.of(
            "2. Network Menu", "3. EPS Network Functions", "3. Perform Table Load");

    /** How long a sub-menu gets to open before its item is pressed again. */
    static final Duration SUBMENU_OPEN_WAIT = Duration.ofSeconds(10);

    /** How long a tapped pump tile has to ring its due fill into the sale. */
    static final Duration TILE_CLAIM_WAIT = Duration.ofSeconds(10);

    /** How long the detail view gets to open after a tile's tap, and the reserve to go. */
    static final Duration RESERVE_WAIT = Duration.ofSeconds(10);

    /** How long the keypad icon's pad gets to open before the icon is pressed again. */
    static final Duration KEYPAD_OPEN_WAIT = Duration.ofSeconds(5);

    private static final Duration POLL = Duration.ofMillis(250);

    /** Defaults as in the Python; package-private so a test can shorten the login wait. */
    Duration loginSteady = Duration.ofSeconds(3);
    Duration loginSettleTimeout = Duration.ofSeconds(60);

    /** The TLog of one transaction, or of the Commander's newest when the number is null. */
    @FunctionalInterface
    public interface TlogSource {
        /** Fetch the TLog of {@code transactionNumber}; {@code null} means the newest transaction. */
        Object fetch(String transactionNumber);
    }

    private final PosDriver driver;
    private final ReceiptSource receiptSource;
    private final TlogSource tlogSource;
    private final Supplier<List<String>> transactionNumbers;

    private final ExpressPanel expressPanel;
    private final FuelPage fuelPanel;
    private final FuelDetailOverlay fuelDetail;
    private final FuelGradeTab gradeTab;
    private final ItemPanel itemPanel;
    private final Overlay overlay;
    private final ReviewOverlay reviewOverlay;
    private final InputOverlay inputOverlay;
    private final Keypad keypad;
    private final Keypad calculatorKeypad;
    private final SignOnScreen signOnScreen;
    private final FunctionMenu functionMenu;
    private final OverlayMenu overlayMenu;
    private final ParkedMenu parkedMenu;
    private final ProductMenu productMenu;
    private final SCOMenu scoMenu;
    private final DeviceMonitoring deviceMonitoring;

    /** The transaction number of the last receipt {@link #receipt()} captured; null when none. */
    private Long lastTransaction;

    /** The transaction {@link #newestTlog} last waited for and read; null when it read the newest blind. */
    private String newestRead;

    /** A Pos on the DOM receipt source, with no TLog and no transaction list. */
    public Pos(PosDriver driver) {
        this(driver, null, null, null);
    }

    /**
     * @param receiptSource  the receipt source; null for the DOM capture ({@link PosDomDriver}).
     * @param tlogSource     the injected TLog fetch; null means {@link #tlog()} refuses.
     * @param transactionNumbers the Commander's transaction numbers, newest first; null if none.
     */
    public Pos(PosDriver driver, ReceiptSource receiptSource, TlogSource tlogSource,
            Supplier<List<String>> transactionNumbers) {
        this.driver = java.util.Objects.requireNonNull(driver, "a Pos needs a driver");
        this.receiptSource = receiptSource != null
                ? receiptSource
                : new DomReceiptCapture(new PosDomDriver(driver), PosDomDriver.itemFamilies());
        this.tlogSource = tlogSource;
        this.transactionNumbers = transactionNumbers;
        this.expressPanel = new ExpressPanel(driver);
        this.fuelPanel = new FuelPage(driver);
        this.fuelDetail = fuelPanel.detail();
        this.gradeTab = fuelPanel.gradeTab();
        this.itemPanel = new ItemPanel(driver);
        this.overlay = new Overlay(driver);
        this.reviewOverlay = new ReviewOverlay(driver);
        this.inputOverlay = new InputOverlay(driver);
        this.keypad = new Keypad(driver);
        this.calculatorKeypad = new Keypad(driver, KeypadNamespace.CALCULATOR);
        this.signOnScreen = new SignOnScreen(driver);
        this.functionMenu = new FunctionMenu(driver);
        this.overlayMenu = new OverlayMenu(driver);
        this.parkedMenu = new ParkedMenu(driver);
        this.productMenu = new ProductMenu(driver);
        this.scoMenu = new SCOMenu(driver);
        this.deviceMonitoring = new DeviceMonitoring(driver);
    }

    // -- page objects -----------------------------------------------------

    public PosDriver driver() {
        return driver;
    }

    public ExpressPanel expressPanel() {
        return expressPanel;
    }

    public FuelPage fuelPanel() {
        return fuelPanel;
    }

    FuelDetailOverlay fuelDetail() {
        return fuelDetail;
    }

    FuelGradeTab gradeTab() {
        return gradeTab;
    }

    public ItemPanel itemPanel() {
        return itemPanel;
    }

    public Overlay overlay() {
        return overlay;
    }

    public ReviewOverlay reviewOverlay() {
        return reviewOverlay;
    }

    public InputOverlay inputOverlay() {
        return inputOverlay;
    }

    public Keypad keypad() {
        return keypad;
    }

    public Keypad calculatorKeypad() {
        return calculatorKeypad;
    }

    public SignOnScreen signOnScreen() {
        return signOnScreen;
    }

    public FunctionMenu functionMenu() {
        return functionMenu;
    }

    public OverlayMenu overlayMenu() {
        return overlayMenu;
    }

    public ParkedMenu parkedMenu() {
        return parkedMenu;
    }

    public ProductMenu productMenu() {
        return productMenu;
    }

    public SCOMenu scoMenu() {
        return scoMenu;
    }

    public DeviceMonitoring deviceMonitoring() {
        return deviceMonitoring;
    }

    // -- sign-on ----------------------------------------------------------

    /** Sign on with the till open, as the default. */
    public void signOn(String cashierId, String password) {
        signOn(cashierId, password, OPEN_TILL, null);
    }

    /**
     * Log a cashier on, answering the fresh-period review menu if it appears. {@code till} says which row
     * answers that menu. Later sign-ons in the same period show no menu, so this checks for it rather
     * than assuming it.
     */
    public void signOn(String cashierId, String password, String till, Duration timeout) {
        if (driver.isPresent(Locators.IDLE_SCREEN_VIEW_IDLE_MSG)) {
            driver.tapWithin(timeout, Locators.IDLE_SCREEN_VIEW_IDLE_MSG);
        }
        driver.expectVisibleWithin(timeout, Locators.USER_ID_PASSWORD_SCREEN_VIEW_PROMPT_TEXT);
        // A login someone left half-done takes the ID typed here as that ID's password. Cancel it first.
        if (asksForPassword(loginPrompt(timeout))) {
            keypad.cancel(timeout);
            // Cancel lands on the idle screen, which has to be touched again for the ID keypad.
            driver.expectVisibleWithin(timeout, Locators.IDLE_SCREEN_VIEW_IDLE_MSG);
            driver.tapWithin(timeout, Locators.IDLE_SCREEN_VIEW_IDLE_MSG);
            driver.expectVisibleWithin(timeout, Locators.USER_ID_PASSWORD_SCREEN_VIEW_PROMPT_TEXT);
        }
        keypad.enter(cashierId, timeout);
        // The password prompt names the ID it is for. A keypad that swallowed the ID's first keystroke
        // would otherwise take the password as the ID, and nothing would notice until the next step.
        String prompt = loginPrompt(timeout);
        if (asksForPassword(prompt) && !promptId(prompt).equals(cashierId)) {
            throw new DriverError("sign-on: the keypad took '" + prompt.strip()
                    + "' for the ID, not '" + cashierId + "'");
        }
        keypad.enter(password, timeout);

        try {
            driver.expectVisibleWithin(TILL_CHECK_TIMEOUT, Locators.REVIEW_OVERLAY_VIEW_TITLE_TEXT);
        } catch (AssertionError noMenu) {
            // No review menu: the sign-on is done.
            return;
        }
        if (OPEN_TILL.equals(till)) {
            openTill(timeout);
        } else {
            answerTillMenu(till, timeout);
            expectCsrMenu(timeout);
        }
    }

    /**
     * {@link #signOn} from a login screen that may still be redrawing, with one retry. A half-typed login
     * is cancelled before the retry, and again if the retry fails too.
     */
    public void signOnFromLogin(String cashierId, String password, String till, Duration timeout) {
        settleAtLogin();
        try {
            signOn(cashierId, password, till, timeout);
            return;
        } catch (DriverError exc) {
            LOG.warn("sign-on raced the login screen ({}); once more", exc.getMessage());
            cancelHalfLogin(timeout);
        }
        settleAtLogin();
        try {
            signOn(cashierId, password, till, timeout);
        } catch (RuntimeException | AssertionError exc) {
            cancelHalfLogin(timeout);
            throw exc;
        }
    }

    private void cancelHalfLogin(Duration timeout) {
        if (keypad.cancelKey().isPresent()) {
            keypad.cancel(timeout);
        }
    }

    private void settleAtLogin() {
        Long[] steadySince = new Long[1];
        PollUntil.until(() -> {
            if (!atLogin()) {
                steadySince[0] = null;
                return false;
            }
            if (steadySince[0] == null) {
                steadySince[0] = System.nanoTime();
            }
            return System.nanoTime() - steadySince[0] >= loginSteady.toNanos();
        }, loginSettleTimeout, Duration.ofMillis(500), "a steady login screen");
    }

    private void expectCsrMenu(Duration timeout) {
        PollUntil.until(() -> !functionMenu.headers().isEmpty(),
                timeout != null ? timeout : driver.timeouts().defaultWait(), POLL,
                "the CSR Main Menu after " + NON_SALES_ONLY);
    }

    /**
     * Select the "Select Till" row whose text contains {@code row}, and wait for the menu to close. The
     * first tap usually advances the menu on its own; a confirming second tap is taken only while the
     * menu is still up.
     */
    public void answerTillMenu(String row, Duration timeout) {
        reviewOverlay.selectByText(row, timeout);
        try {
            driver.expectHiddenWithin(TILL_CONFIRM_TIMEOUT, Locators.REVIEW_OVERLAY_VIEW_TITLE_TEXT);
        } catch (AssertionError stillUp) {
            reviewOverlay.selectByText(row, timeout);
            driver.expectHiddenWithin(timeout, Locators.REVIEW_OVERLAY_VIEW_TITLE_TEXT);
        }
    }

    /** Answer the "Select Till" menu with OPEN TILL and key the opening float. */
    public void openTill(Duration timeout) {
        answerTillMenu(OPEN_TILL, timeout);
        BaseElement title = signOnScreen.openDrawerTitle();
        title.expectVisible(timeout);
        title.expectText(OPEN_DRAWER_TITLE_TEXT, false, timeout);
        new Keypad(driver, KeypadNamespace.CALCULATOR).enter(TILL_OPENING_FLOAT, timeout);
    }

    private String loginPrompt(Duration timeout) {
        return driver.textOfWithin(timeout, Locators.USER_ID_PASSWORD_SCREEN_VIEW_PROMPT_TEXT);
    }

    // -- sales ------------------------------------------------------------

    /**
     * Tender one of the store's "Other MOP" list entries (CHECK, COUPON, ...). The amount keypad step is
     * inferred, not corpus-confirmed: re-derive it live if a caller hangs past tender selection.
     */
    public void payOtherMop(String name, String amount, Duration timeout) {
        itemPanel.select(ItemPanel.MOP.PAYMENT_GATEWAY, timeout);
        itemPanel.select(ItemPanel.MOP.OTHER, timeout);
        reviewOverlay.selectByText(name, timeout);
        if (amount != null) {
            keypad.enter(amount, timeout);
        }
    }

    /**
     * Ring up a decimal PLU the way PATS does: keyed on the numeric pad, then OK, then the pad is cancelled
     * (a second, empty copy of it blocks the item panel on this build). Pass the PLU as a string when it has
     * leading zeros.
     */
    public void keyPlu(String plu, Duration timeout) {
        Keypad padKeypad = new Keypad(driver, KeypadNamespace.VIRTUAL_KEYBOARD);
        openKeypad(padKeypad, timeout);
        padKeypad.enter(plu, timeout);
        padKeypad.cancel(timeout);
    }

    /**
     * Press the keypad icon, and once more if the first press opened no pad: the first press can land under
     * a "Sales functions initializing" window (Reg101, 2026-10-20).
     */
    private void openKeypad(Keypad padKeypad, Duration timeout) {
        for (int attempt = 1; ; attempt++) {
            expressPanel.keypadIcon().tap(timeout);
            try {
                padKeypad.digit("1").expectVisible(KEYPAD_OPEN_WAIT);
                return;
            } catch (AssertionError noPad) {
                if (attempt == 2) {
                    throw noPad;
                }
                LOG.warn("the keypad icon opened no keypad; pressing it again");
            }
        }
    }

    // -- EPS and table load -----------------------------------------------

    /** The status strip's lines right now, first line first. It rotates, so one read is a sample. */
    public List<String> systemMessages() {
        List<String> messages = new ArrayList<>();
        for (Locator label : List.of(Locators.SYSTEM_MESSAGES_PANEL_VIEW_LABEL1,
                Locators.SYSTEM_MESSAGES_PANEL_VIEW_LABEL2)) {
            for (String text : driver.visibleTexts(label)) {
                if (!text.isEmpty()) {
                    messages.add(text);
                }
            }
        }
        return messages;
    }

    /** PATS's BuypassTblLoader.tblLoad: presses the table load and returns, menus left open. */
    public void downloadTables() {
        downloadTables(MENU_WAIT);
    }

    public void downloadTables(Duration timeout) {
        eppNetworkFunction(TABLE_LOAD_PATH, timeout != null ? timeout : MENU_WAIT);
    }

    /** Press the EMV CA public key file download and return, menus left open. */
    public void downloadEmvCaPublicKeys(Duration timeout) {
        eppNetworkFunction(EMV_KEY_DOWNLOAD_PATH, timeout != null ? timeout : MENU_WAIT);
    }

    /** Walk {@code path} from the CSR menus and press its last item. No wait after the press. */
    private void eppNetworkFunction(List<String> path, Duration timeout) {
        if (functionMenu.headers().isEmpty()) {
            // A NON_SALES_ONLY sign-on lands on the Main Menu already, and CSR Func toggles it.
            expressPanel.csrFunction().tap(timeout);
        }
        List<String> menus = path.subList(0, path.size() - 1);
        for (String label : menus) {
            openSubmenu(label, timeout);
        }
        pressMenuItem(path.get(path.size() - 1), timeout);
    }

    /**
     * Press a menu item by label, ending at once if the login screen is showing: a register that has signed
     * the cashier off has no CSR menu to wait for.
     */
    private String pressMenuItem(String label, Duration timeout) {
        return PollUntil.value(() -> {
            String pressed = driver.pressLastVisibleOnce(Locators.FUNCTION_MENU_N, label);
            if (pressed == null && !driver.visibleTexts(Locators.IDLE_SCREEN_VIEW_IDLE_MSG).isEmpty()) {
                throw new DriverError(label + ": the register is at its login screen; "
                        + "the cashier was signed off during the CSR menu walk");
            }
            return pressed;
        }, timeout, POLL, Locators.FUNCTION_MENU_N.name() + " reading '" + label + "'");
    }

    /**
     * Press {@code label} until the open menus change, within {@code timeout}. "Changed" means the headers
     * moved, or the item itself has gone (the next item's own wait then checks where it went).
     */
    private void openSubmenu(String label, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<String> before = functionMenu.headers();
        while (true) {
            pressMenuItem(label, timeout);
            long remaining = deadline - System.nanoTime();
            Duration budget = Duration.ofNanos(Math.max(
                    Math.min(SUBMENU_OPEN_WAIT.toNanos(), remaining), Duration.ofMillis(100).toNanos()));
            try {
                PollUntil.until(() -> movedOn(label, before), budget, Duration.ofMillis(500),
                        "the menu under '" + label + "'");
                return;
            } catch (DriverTimeout noMenu) {
                if (System.nanoTime() - deadline >= 0) {
                    throw noMenu;
                }
                LOG.warn("'{}' opened no menu; pressing it again", label);
            }
        }
    }

    private boolean movedOn(String label, List<String> before) {
        List<String> now = functionMenu.headers();
        if (!now.equals(before) && !now.isEmpty()) {
            return true;
        }
        return driver.visibleTexts(Locators.FUNCTION_MENU_N).stream()
                .noneMatch(text -> text.strip().startsWith(label));
    }

    // -- sign-off, void, and recovery ------------------------------------

    /** Log the cashier out with the Main Menu's "Log Out" button (express slot 5). */
    public void signOff(Duration timeout) {
        Duration budget = timeout != null ? timeout : MENU_WAIT;
        functionMenu.closeToMainMenu(budget);
        if (functionMenu.headers().isEmpty()) {
            expressPanel.csrFunction().tap(budget);
        }
        expressPanel.logOut().expectText(ExpressPanel.LOG_OUT_TEXT, false, budget).tap(budget);
        driver.expectVisibleWithin(budget, Locators.IDLE_SCREEN_VIEW_IDLE_MSG);
    }

    public void signOff() {
        signOff(MENU_WAIT);
    }

    /** Void the open sale with the sales screen's "Void Ticket" button (express slot 4). */
    public void voidTicket(Duration timeout) {
        expressPanel.voidTicket().expectText(ExpressPanel.VOID_TICKET_TEXT, false, timeout).tap(timeout);
        expectIdle(timeout);
    }

    /** Snapshot: the open ticket carries a fill collected from a pump. No wait. */
    public boolean holdsDispensedFuel() {
        Map<Long, List<String>> descriptions =
                driver.familyTexts(Locators.RECEIPT_VIEW_ITEM_LINE_DESCRIPTION_N);
        Map<Long, List<String>> quantities =
                driver.familyTexts(Locators.RECEIPT_VIEW_ITEM_LINE_QUANTITY_N);
        for (Map.Entry<Long, List<String>> row : descriptions.entrySet()) {
            boolean pumpLine = row.getValue().stream().anyMatch(text -> PUMP_LINE.matcher(text).find());
            boolean hasVolume = quantities.getOrDefault(row.getKey(), List.of()).stream()
                    .anyMatch(text -> text.contains("."));
            if (pumpLine && hasVolume) {
                return true;
            }
        }
        return false;
    }

    /** Snapshot: the open sale already carries a payment line (a partial tender). No wait. */
    public boolean holdsTender() {
        return driver.visibleTexts(Locators.ODSP_INFO_VIEW_AMOUNT_DESC_PAY_LINE_1).stream()
                .anyMatch(text -> !text.strip().isEmpty());
    }

    /**
     * Leave the register idle: pay a sale holding a collected fill or a partial tender, void any other.
     * Returns "idle", "settled" or "voided".
     */
    public String clearOpenSale(Duration timeout) {
        if (isIdle()) {
            return "idle";
        }
        if (holdsDispensedFuel() || holdsTender()) {
            try {
                itemPanel.select(ItemPanel.MOP.PAYMENT_GATEWAY, timeout);
                itemPanel.select(ItemPanel.MOP.CASH, timeout);
                keypad.ok(timeout);
                expectIdle(SETTLE_IDLE_WAIT);
                return "settled";
            } catch (RuntimeException | AssertionError exc) {
                // The void below still leaves the register usable.
                if (isIdle()) {
                    return "settled";
                }
                LOG.warn("could not pay the fuel sale off in CASH ({}); voiding it", exc.getMessage());
            }
        }
        voidTicket(timeout);
        return "voided";
    }

    /**
     * Pay off every fill due on {@code pump} in CASH; return the amounts paid. Not a PATS step: recovery.
     * Stops at the first fill that does not ring in, or is voided rather than paid. UNVERIFIED AGAINST
     * HARDWARE: not yet run on a rig.
     */
    public List<String> settleDueFuel(int pump, Duration timeout) {
        List<String> paid = new ArrayList<>();
        for (int i = 0; i < 4; i++) { // a tile shows two due fills; this bounds a POS that adds more
            List<String> due = fuelPanel.due(pump);
            if (due.isEmpty() || !isIdle()) {
                break;
            }
            fuelPanel.pump(pump).tap(timeout);
            try {
                PollUntil.until(this::holdsDispensedFuel, TILE_CLAIM_WAIT, POLL,
                        "pump " + pump + "'s due fill in the sale");
            } catch (DriverTimeout noFill) {
                LOG.warn("pump {}: tapping its tile rang no fill in (due {})", pump, due);
                if (fuelDetail.prepay(1).isPresent()) {
                    fuelDetail.close();
                }
                if (!isIdle()) {
                    clearOpenSale(timeout);
                }
                break;
            }
            if (!"settled".equals(clearOpenSale(timeout))) {
                break;
            }
            paid.add(due.get(0));
        }
        return paid;
    }

    /** {@link #clearReserve(int, IntFunction)} with no release. */
    public String clearReserve(int pump) {
        return clearReserve(pump, null);
    }

    /**
     * Free a pump holding a reserve with nothing dispensed; what was done, or "". Not a PATS step: recovery.
     * A prepaid reserve is fuelled out through {@code release} (the prepay's own limit ends the fill); without
     * one, or with no prepay shown, Clear Reserve. Idle register only. UNVERIFIED AGAINST HARDWARE.
     */
    public String clearReserve(int pump, IntFunction<String> release) {
        String held = isIdle() ? reserved(pump) : null;
        if (held == null || held.isEmpty()) {
            return "";
        }
        StringBuilder done = new StringBuilder();
        boolean fuelledOut = false;
        try {
            if (!openDetail(pump)) {
                return "pump " + pump + ": " + held + " reserved; its detail view did not open";
            }
            boolean prepaid = driver.screenText().toLowerCase(Locale.ROOT).contains("prepaid");
            if (prepaid && release != null && fuelDetail.pressLabelled("Approve / Restart", null)) {
                String refused = answerMessage();
                if (!refused.isEmpty()) {
                    // Reg101, 2026-10-13: "Error approving Pump 2". Clear it instead.
                    done.append("Approve / Restart refused (").append(refused).append("); ");
                } else {
                    if (detailOpen()) {
                        driver.press(Locators.FUEL_POINT_DETAIL_OVERLAY_VIEW_CLOSE_BUTTON, RESERVE_WAIT);
                    }
                    String before = release.apply(pump);
                    done.append("prepay re-authorised and fuelled out (pump was ")
                            .append(before == null || before.isEmpty() ? "idle" : before).append(")");
                    fuelledOut = true;
                }
            }
            if (!fuelledOut) {
                if (!openDetail(pump)) {
                    return "pump " + pump + ": " + held + " reserved; " + done
                            + "its detail view did not reopen";
                }
                if (!fuelDetail.pressLabelled("Clear Reserve", null)) {
                    return "pump " + pump + ": " + held + " reserved; " + done + "no Clear Reserve button";
                }
                String refused = answerMessage();
                done.append(refused.isEmpty() ? "Clear Reserve pressed"
                        : "Clear Reserve refused (" + refused + ")");
            }
        } finally {
            // Never leave a view or a message up.
            try {
                answerMessage();
                if (detailOpen()) {
                    driver.press(Locators.FUEL_POINT_DETAIL_OVERLAY_VIEW_CLOSE_BUTTON, RESERVE_WAIT);
                }
            } catch (RuntimeException | AssertionError ignored) {
                // a failed cleanup must not mask the result above
            }
        }
        try {
            PollUntil.until(() -> reserved(pump) == null, RESERVE_WAIT, POLL,
                    "pump " + pump + "'s reserve to clear");
        } catch (DriverTimeout stillShown) {
            return "pump " + pump + ": " + held + " reserved; " + done + ", but the tile still shows it";
        }
        return "pump " + pump + ": " + held + " reserved; " + done;
    }

    /** The pump's reserve text, or null when it holds none. */
    private String reserved(int pump) {
        return fuelPanel.reserved(pump).orElse(null);
    }

    private boolean detailOpen() {
        return driver.elementStates(Locators.FUEL_POINT_DETAIL_OVERLAY_VIEW_CLOSE_BUTTON).stream()
                .anyMatch(PosDriver.ElementState::visible);
    }

    private boolean openDetail(int pump) {
        if (!detailOpen()) {
            // The _Data tile, as close_day settles the forecourt on Reg101.
            driver.press(Locators.FUEL_PANEL_0_FUEL_BUTTON_N_DATA.at(pump), RESERVE_WAIT);
        }
        try {
            PollUntil.until(this::detailOpen, RESERVE_WAIT, POLL, "pump " + pump + "'s view");
        } catch (DriverTimeout noView) {
            return false;
        }
        return true;
    }

    /** OK any message a press raised. Its text, "" when none was up. */
    private String answerMessage() {
        boolean okShown = driver.elementStates(Locators.OVERLAY_MSG_VIEW_OK_BTN).stream()
                .anyMatch(PosDriver.ElementState::visible);
        if (!okShown) {
            return "";
        }
        List<String> line1 = new ArrayList<>();
        for (PosDriver.ElementState state : driver.elementStates(Locators.OVERLAY_MSG_VIEW_LINE1)) {
            if (state.visible()) {
                line1.add(state.text());
            }
        }
        String text = String.join(" ", line1);
        driver.press(Locators.OVERLAY_MSG_VIEW_OK_BTN, RESERVE_WAIT);
        return text.isEmpty() ? "a message" : text;
    }

    // -- state ------------------------------------------------------------

    public void expectIdle(Duration timeout) {
        expressPanel.expectIdle(timeout);
    }

    public void expectTransactionActive(Duration timeout) {
        expressPanel.expectTransactionActive(timeout);
    }

    public boolean isIdle() {
        return expressPanel.isIdle();
    }

    /** Snapshot: the login screen or its ID/password prompt is showing. */
    public boolean atLogin() {
        return driver.isPresent(Locators.IDLE_SCREEN_VIEW_IDLE_MSG)
                || driver.isPresent(Locators.USER_ID_PASSWORD_SCREEN_VIEW_PROMPT_TEXT);
    }

    // -- artifacts --------------------------------------------------------

    /** Capture the receipt, DOM first. Remembers its transaction number for {@link #tlog()}. */
    public Receipt receipt() {
        Receipt receipt = receiptSource.capture();
        List<String> lines = new ArrayList<>();
        for (ReceiptLine line : receipt.lines()) {
            lines.add(line.text());
        }
        lastTransaction = ReceiptParser.transactionNumber(lines);
        return receipt;
    }

    /**
     * The TLog for the completed sale. Fetched by the transaction number the last receipt named, so a
     * prepay completion recorded under another POS cannot be mistaken for it.
     */
    public Object tlog() {
        if (tlogSource == null) {
            throw new UnsupportedOperationException(
                    "pos.tlog() has no source wired. It comes from the Commander over CGILink, "
                    + "not the POS shim; pass a TlogSource when constructing Pos.");
        }
        if (lastTransaction != null) {
            return tlogSource.fetch(String.valueOf(lastTransaction));
        }
        return newestTlog();
    }

    /** The Commander's newest transaction number, or null if it lists none (or cannot). */
    public String newestTransaction() {
        if (transactionNumbers == null) {
            return null;
        }
        List<String> numbers = transactionNumbers.get();
        return numbers.isEmpty() ? null : numbers.get(0);
    }

    /** {@link #newestTlog(String, Duration, Supplier)} with no wait: the newest as it reads now. */
    public Object newestTlog() {
        return newestTlog(null, Duration.ZERO, null);
    }

    /**
     * The TLog of the transaction the Commander committed last, whatever its register. {@code after} is the
     * newest number when the case began: with a positive {@code timeout}, waits for a newer one. If none
     * comes, the newest there is is read, and {@link #newestRead()} says so. {@code abort} ends the wait early
     * when it returns a reason.
     */
    public Object newestTlog(String after, Duration timeout, Supplier<String> abort) {
        if (tlogSource == null) {
            return tlog(); // raises the no-source error
        }
        newestRead = null;
        if (after != null && transactionNumbers != null && timeout.compareTo(Duration.ZERO) > 0) {
            String newest = newerTransaction(this::newestTransaction, after, timeout, abort);
            if (newest != null) {
                newestRead = newest;
                return tlogSource.fetch(newest);
            }
        }
        return tlogSource.fetch(null);
    }

    /** The transaction {@link #newestTlog} last waited for and read; null when it read the newest blind. */
    public String newestRead() {
        return newestRead;
    }

    /** Recovery ladder rung 0, before anything else runs. */
    public Map<String, Path> captureEvidence(String label) {
        return driver.capture(label);
    }

    /**
     * {@code newest} once it is a number other than {@code after}, looked for each quarter second up to
     * {@code timeout}; null if none came. {@code after} may be null or empty for "any at all". A reason from
     * {@code abort} ends the wait early: a dead end, which throws {@link DriverError}.
     */
    static String newerTransaction(Supplier<String> newest, String after, Duration timeout,
            Supplier<String> abort) {
        String seen = after == null ? "" : after;
        try {
            return PollUntil.value(() -> {
                String n = newest.get();
                if (n != null && !n.isEmpty() && !n.equals(seen)) {
                    return n;
                }
                if (abort != null) {
                    String reason = abort.get();
                    if (reason != null && !reason.isEmpty()) {
                        throw new DriverError(reason);
                    }
                }
                return null;
            }, timeout, POLL, "a transaction newer than " + (seen.isEmpty() ? "none" : seen));
        } catch (DriverTimeout none) {
            return null;
        }
    }

    /** True when a login prompt is asking for a password. */
    static boolean asksForPassword(String prompt) {
        return prompt.toLowerCase(Locale.ROOT).contains("password");
    }

    /** The ID a password prompt names: what follows its last colon. */
    static String promptId(String prompt) {
        int colon = prompt.lastIndexOf(':');
        return colon < 0 ? "" : prompt.substring(colon + 1).strip();
    }

    @Override
    public String toString() {
        return "Pos register=" + driver.registerId();
    }
}
