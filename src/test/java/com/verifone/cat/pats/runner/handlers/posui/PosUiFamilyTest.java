package com.verifone.cat.pats.runner.handlers.posui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.Fields;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunState;
import com.verifone.cat.pats.Action;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.RunnableCase;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.recovery.Timeouts;
import com.verifone.cat.report.CaseRecord;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The POS UI family against markup in headless Chromium (Playwright), with a pos-shim stand-in for the RMI reads and key
 * presses. Evidence about this repository, not about a register: the markup is written by the test, and the shim is
 * {@link PosUiFakeShim}. Skipped, not failed, where Chromium is not installed for Playwright.
 */
class PosUiFamilyTest {

    private static final String SKIP_REASON = "Chromium is not installed for Playwright; skipping. Install it with: "
            + "java -cp \"target\\lib\\*\" com.microsoft.playwright.CLI install chromium";

    /** Short budgets: a wait that never ends should fail in seconds. */
    private static final Timeouts DRIVER_TIMEOUTS = new Timeouts(Duration.ofSeconds(2), Duration.ofSeconds(3));

    /** Short waits for the family: a wait that never holds gives up in about a second. */
    private static final PosUiTimings FAST = new PosUiTimings(
            Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
            Duration.ofSeconds(1), Duration.ofMillis(300), Duration.ofMillis(100), Duration.ofMillis(100),
            Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
            Duration.ofSeconds(1), Duration.ofMillis(200), Duration.ofSeconds(1), Duration.ofMillis(50),
            Duration.ofMillis(50), Duration.ofSeconds(1));

    /** The widget texts the tests read, as the POS shows them in English. */
    private static final Map<String, String> WIDGETS = Map.ofEntries(
            Map.entry("EXPRESS_REPRINT", "Reprint"),
            Map.entry("EXPRESS_RECALL", "Recall"),
            Map.entry("EXPRESS_CSRFUNC", "CSR Func"),
            Map.entry("EXPRESS_ERROR_CORR", "Error Correct"),
            Map.entry("EPS_BUSY_MSG", "Eps Busy! Please try again."),
            Map.entry("ENTER_ID", "Enter ID"),
            Map.entry("ENTER_PASSWORD", "Enter Password For ID: "),
            Map.entry("ENTER_OPEN_DRAWER_AMOUNT", "Enter Opening Drawer Amount"),
            Map.entry("MENU_FN_LOGOUT", "Log Out"),
            Map.entry("SELECT_TILL_OTHER_FUNCTIONS", "Other Functions"));

    private static final String ITEM = Locators.ITEM_PANEL_N.at(145394).value();
    private static final String EXPRESS_1 = Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N.at(1).value();

    @TempDir
    Path folder;

    private static Browser browser;
    private static Playwright playwright;

    @BeforeAll
    static void openChromium() {
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch();
        } catch (PlaywrightException missing) {
            browser = null;
        }
    }

    @AfterAll
    static void closeChromium() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @BeforeEach
    void needsChromium() {
        Assumptions.assumeTrue(browser != null, SKIP_REASON);
    }

    // -- the family's table, without a browser --------------------------------

    @Test
    void theFamilyHandlesTheThirteenPatsActionsItsPythonHandles() {
        assertEquals(13, PosUiFamily.ACTIONS.size());
        assertEquals(Set.copyOf(PosUiFamily.ACTIONS), Set.of("SLEEP", "TOUCH_EVENT", "WIDGET_VALIDATION",
                "VALIDATION_BY_TEXT", "IS_ELEMENT_PRESENT", "IS_ELEMENT_CLICKABLE", "APP_SELECTOR", "CASHIER_LOGIN",
                "VALIDATE_OVERLAY", "LOGIN", "LOGOUT", "ENTER_DRAWER_AMNT", "SELECT_TILL"));
    }

    @Test
    void everyFieldAHandlerReadsIsListedInFieldsRead() {
        // The Python's test_pats_feature check, for this family: each handler's reads are in the table.
        Map<String, List<String>> reads = Map.ofEntries(
                Map.entry("SLEEP", List.of("SLEEP")),
                Map.entry("TOUCH_EVENT", List.of("WEB ELEMENT", "TYPE[ID/XPATH]")),
                Map.entry("WIDGET_VALIDATION", List.of("WEB ELEMENT", "TYPE[ID/XPATH]", "VALIDATION KEY")),
                Map.entry("VALIDATION_BY_TEXT", List.of("WEB ELEMENT", "TYPE[ID/XPATH]", "VALIDATION TEXT")),
                Map.entry("IS_ELEMENT_PRESENT", List.of("WEB ELEMENT", "TYPE[ID/XPATH]", "YES/NO")),
                Map.entry("IS_ELEMENT_CLICKABLE", List.of("WEB ELEMENT", "TYPE[ID/XPATH]", "YES/NO", "WAIT TIME[SEC]")),
                Map.entry("APP_SELECTOR", List.of("WEB ELEMENT", "TYPE[ID/XPATH]")),
                Map.entry("CASHIER_LOGIN", List.of()),
                Map.entry("VALIDATE_OVERLAY", List.of("TITLE", "LINE1", "LINE2", "LINE3")),
                Map.entry("LOGIN", List.of("USER_ID", "PASSWORD")),
                Map.entry("LOGOUT", List.of()),
                Map.entry("ENTER_DRAWER_AMNT", List.of("DRAWER_AMOUNT")),
                Map.entry("SELECT_TILL", List.of("SELECT_TILL_OPTION")));
        for (String name : PosUiFamily.ACTIONS) {
            List<String> listed = Fields.FIELDS_READ.get(name);
            assertTrue(listed != null, name + " is not in FIELDS_READ");
            for (String field : reads.get(name)) {
                assertTrue(listed.contains(field), name + " reads " + field + ", which FIELDS_READ does not list");
            }
        }
    }

    @Test
    void theFastTimingsAreTheDefaultsWithShorterWaits() {
        assertEquals(PosUiTimings.defaults().touchSettle(), Duration.ofSeconds(2));
        assertEquals(PosUiTimings.defaults().uiWait(), Duration.ofSeconds(60));
    }

    // -- sleeps ----------------------------------------------------------------

    @Test
    void aSleepBetweenActionsIsCarriedIntoTheNextWaitNotSlept() throws Exception {
        try (PosUiFakeShim shim = new PosUiFakeShim(); Page page = browser.newPage()) {
            Harness h = new Harness(page, shim);
            page.setContent("<div>idle</div>");
            CaseRun run = h.run("carry", at(0, "SLEEP", "SLEEP", "3000"),
                    at(1, "TOUCH_EVENT", "WEB ELEMENT", ITEM));
            run.begin();
            run.perform(run.kase().actions().get(0));
            assertTrue(h.pace.pauses.isEmpty(), "the SLEEP was slept, not carried: " + h.pace.pauses);
            assertEquals(3.0, run.budget(0.0), 0.001);
        }
    }

    @Test
    void aSleepAfterAForecourtActionIsWaitedOut() throws Exception {
        try (PosUiFakeShim shim = new PosUiFakeShim(); Page page = browser.newPage()) {
            Harness h = new Harness(page, shim);
            page.setContent("<div>idle</div>");
            CaseRun run = h.run("forecourt", at(0, "HANDLE_DOWN", "PUMP NO", "1"),
                    at(1, "SLEEP", "SLEEP", "2000"),
                    at(2, "TOUCH_EVENT", "WEB ELEMENT", ITEM));
            run.begin();
            run.perform(run.kase().actions().get(1));
            assertEquals(List.of(Duration.ofSeconds(2)), h.pace.pauses);
            assertEquals(0.0, run.budget(0.0), 0.001);
        }
    }

    // -- touches ---------------------------------------------------------------

    @Test
    void aTouchPressesTheElementAndSettlesOnTheScreen() throws Exception {
        try (PosUiFakeShim shim = new PosUiFakeShim(); Page page = browser.newPage()) {
            Harness h = new Harness(page, shim);
            page.setContent(button(ITEM));
            CaseRun run = h.run("touch", at(0, "TOUCH_EVENT", "WEB ELEMENT", ITEM));
            run.begin();
            run.perform(run.kase().actions().get(0));
            assertEquals(List.of(ITEM), pressed(page));
            assertTrue(PosUiCaseState.touched(run));
        }
    }

    @Test
    void aPressUnderAMessageThatDoesNotHideGoesThroughAndKeepsTheMessage() throws Exception {
        try (PosUiFakeShim shim = new PosUiFakeShim(); Page page = browser.newPage()) {
            Harness h = new Harness(page, shim);
            page.setContent(overlay("Warn", "Hello") + button(ITEM));
            CaseRun run = h.run("under", at(0, "TOUCH_EVENT", "WEB ELEMENT", ITEM));
            run.begin();
            run.perform(run.kase().actions().get(0));
            assertEquals(List.of(ITEM), pressed(page));
            // The screen did not answer the press, so the message is judged, not learned, and cleared as the Python does.
            assertEquals(null, PosUiCaseState.of(run).underMessage());
            assertTrue(h.ctx.session().takesPresses().isEmpty());
        }
    }

    // -- validations -----------------------------------------------------------

    @Test
    void validateOverlayPassesWhenTheMessageMatchesIgnoringCase() throws Exception {
        try (PosUiFakeShim shim = new PosUiFakeShim(); Page page = browser.newPage()) {
            Harness h = new Harness(page, shim);
            page.setContent(overlay("Warn", "Hello"));
            CaseRun run = h.run("overlay-ok",
                    at(0, "VALIDATE_OVERLAY", "TITLE", "warn", "LINE1", "HELLO"));
            run.begin();
            run.perform(run.kase().actions().get(0));
            assertTrue(run.softFailures().isEmpty());
        }
    }

    @Test
    void validateOverlayIsASoftFailureWhenALineDiffers() throws Exception {
        try (PosUiFakeShim shim = new PosUiFakeShim(); Page page = browser.newPage()) {
            Harness h = new Harness(page, shim);
            page.setContent(overlay("Warn", "Hello"));
            CaseRun run = h.run("overlay-bad",
                    at(0, "VALIDATE_OVERLAY", "TITLE", "Warn", "LINE1", "Goodbye"));
            run.begin();
            run.perform(run.kase().actions().get(0));
            assertEquals(1, run.softFailures().size(), "a failed overlay is recorded and the case carries on");
            assertTrue(run.softFailures().get(0).contains("expected"));
        }
    }

    @Test
    void validateOverlayPassesWhenNoOverlayIsShowing() throws Exception {
        try (PosUiFakeShim shim = new PosUiFakeShim(); Page page = browser.newPage()) {
            Harness h = new Harness(page, shim);
            page.setContent("<div>idle</div>");
            CaseRun run = h.run("overlay-none",
                    at(0, "VALIDATE_OVERLAY", "TITLE", "Warn"));
            run.begin();
            run.perform(run.kase().actions().get(0));
            assertTrue(run.softFailures().isEmpty(), "no overlay at all is a pass, as in PATS");
        }
    }

    @Test
    void widgetValidationReadsTheWidgetsEnglishFromTheElement() throws Exception {
        try (PosUiFakeShim shim = new PosUiFakeShim(); Page page = browser.newPage()) {
            Harness h = new Harness(page, shim);
            page.setContent("<div id=\"" + EXPRESS_1 + "\">Reprint</div>");
            CaseRun run = h.run("widget", at(0, "WIDGET_VALIDATION", "WEB ELEMENT", EXPRESS_1,
                    "VALIDATION KEY", "EXPRESS_REPRINT"));
            run.begin();
            assertDoesNotThrow(() -> run.perform(run.kase().actions().get(0)));
        }
    }

    @Test
    void validationByTextIsAHardFailureWhenTheTextIsAbsent() throws Exception {
        try (PosUiFakeShim shim = new PosUiFakeShim(); Page page = browser.newPage()) {
            Harness h = new Harness(page, shim);
            page.setContent(overlay("Warn", "Hello"));
            CaseRun run = h.run("by-text", at(0, "VALIDATION_BY_TEXT",
                    "WEB ELEMENT", Locators.OVERLAY_MSG_VIEW_TITLE.value(), "VALIDATION TEXT", "Goodbye"));
            run.begin();
            assertThrows(PatsStepError.class, () -> run.perform(run.kase().actions().get(0)));
        }
    }

    // -- presence and clickability ----------------------------------------------

    @Test
    void isElementPresentYesPassesOnAPresentElementAndNoFailsOnIt() throws Exception {
        try (PosUiFakeShim shim = new PosUiFakeShim(); Page page = browser.newPage()) {
            Harness h = new Harness(page, shim);
            page.setContent(button(ITEM));
            CaseRun run = h.run("present",
                    at(0, "IS_ELEMENT_PRESENT", "WEB ELEMENT", ITEM, "YES/NO", "YES"),
                    at(1, "IS_ELEMENT_PRESENT", "WEB ELEMENT", ITEM, "YES/NO", "NO"));
            run.begin();
            assertDoesNotThrow(() -> run.perform(run.kase().actions().get(0)));
            assertThrows(PatsStepError.class, () -> run.perform(run.kase().actions().get(1)));
        }
    }

    @Test
    void isElementClickableNoPassesOnADisabledButtonAndYesFails() throws Exception {
        try (PosUiFakeShim shim = new PosUiFakeShim(); Page page = browser.newPage()) {
            Harness h = new Harness(page, shim);
            page.setContent("<button id=\"" + ITEM + "\" disabled>Go</button>");
            CaseRun run = h.run("clickable",
                    at(0, "IS_ELEMENT_CLICKABLE", "WEB ELEMENT", ITEM, "YES/NO", "NO",
                            "WAIT TIME[SEC]", "1"),
                    at(1, "IS_ELEMENT_CLICKABLE", "WEB ELEMENT", ITEM, "YES/NO", "YES",
                            "WAIT TIME[SEC]", "1"));
            run.begin();
            assertDoesNotThrow(() -> run.perform(run.kase().actions().get(0)));
            assertThrows(PatsStepError.class, () -> run.perform(run.kase().actions().get(1)));
        }
    }

    // -- the operator's steps, against the shim ----------------------------------

    @Test
    void loginAnswersEachPromptWithTheIdAndThePassword() throws Exception {
        try (PosUiFakeShim shim = new PosUiFakeShim(); Page page = browser.newPage()) {
            Harness h = new Harness(page, shim);
            page.setContent(text(Locators.USER_ID_PASSWORD_SCREEN_VIEW_PROMPT_TEXT.value(),
                    "Enter ID Enter Password For ID:"));
            CaseRun run = h.run("login", at(0, "LOGIN", "USER_ID", "123", "PASSWORD", "pw"));
            run.begin();
            run.perform(run.kase().actions().get(0));
            assertEquals(List.of("keys:ENTER", "enter:123", "enter:pw"), shim.calls());
        }
    }

    @Test
    void cashierLoginSendsTheSelfCheckoutCashierKey() throws Exception {
        try (PosUiFakeShim shim = new PosUiFakeShim(); Page page = browser.newPage()) {
            Harness h = new Harness(page, shim);
            page.setContent("<div>idle</div>");
            CaseRun run = h.run("cashier", at(0, "CASHIER_LOGIN"));
            run.begin();
            run.perform(run.kase().actions().get(0));
            assertEquals(List.of("keys:KEY_SCO_CASHIER_ENTRY"), shim.calls());
        }
    }

    @Test
    void logoutExitsOnceThenPressesLogOutWhenTheMenuOffersIt() throws Exception {
        try (PosUiFakeShim shim = new PosUiFakeShim(); Page page = browser.newPage()) {
            Harness h = new Harness(page, shim);
            page.setContent(text(EXPRESS_1, "Log Out"));
            CaseRun run = h.run("logout", at(0, "LOGOUT"));
            run.begin();
            run.perform(run.kase().actions().get(0));
            assertEquals(List.of("keys:EXIT", "keys:LOG_OUT"), shim.calls());
        }
    }

    @Test
    void enterDrawerAmountEntersTheAmountOnceTheCalculatorAsks() throws Exception {
        try (PosUiFakeShim shim = new PosUiFakeShim(); Page page = browser.newPage()) {
            Harness h = new Harness(page, shim);
            page.setContent(text(Locators.CALCULATOR_VIEW_TITLE_TEXT.value(), "Enter Opening Drawer Amount"));
            CaseRun run = h.run("drawer", at(0, "ENTER_DRAWER_AMNT", "DRAWER_AMOUNT", "100"));
            run.begin();
            run.perform(run.kase().actions().get(0));
            assertEquals(List.of("enter:100"), shim.calls());
        }
    }

    @Test
    void selectTillKeysTheOptionWhenTheRegisterPushedTheMenu() throws Exception {
        try (PosUiFakeShim shim = new PosUiFakeShim(); Page page = browser.newPage()) {
            Harness h = new Harness(page, shim);
            shim.review(List.of("Sign On", "Till", "Other Functions"));
            page.setContent("<div>idle</div>");
            CaseRun run = h.run("till", at(0, "SELECT_TILL", "SELECT_TILL_OPTION", "3"));
            run.begin();
            run.perform(run.kase().actions().get(0));
            assertEquals(List.of("enter:3"), shim.calls());
        }
    }

    @Test
    void appSelectorPressesTheSwitcherThenThePanelAndWaitsOutTheSwitch() throws Exception {
        try (PosUiFakeShim shim = new PosUiFakeShim(); Page page = browser.newPage()) {
            Harness h = new Harness(page, shim);
            page.setContent(button(Locators.APP_SELECTOR_SWITCHER_VIEW_MAIN_PANEL.value())
                    + button(Locators.APP_SELECTION_PROMPT_VIEW_APP_PANEL_POS.value()));
            CaseRun run = h.run("app", at(0, "APP_SELECTOR", "WEB ELEMENT",
                    Locators.APP_SELECTION_PROMPT_VIEW_APP_PANEL_POS.value()));
            run.begin();
            run.perform(run.kase().actions().get(0));
            assertEquals(List.of(Locators.APP_SELECTOR_SWITCHER_VIEW_MAIN_PANEL.value(),
                    Locators.APP_SELECTION_PROMPT_VIEW_APP_PANEL_POS.value()), pressed(page));
            assertTrue(h.pace.pauses.contains(FAST.appSwitchSettle()), "the app switch's settle, as the timings give it");
        }
    }

    // -- harness ---------------------------------------------------------------

    /** A recording pace: pauses are noted, not waited, and a poll looks once (or, when asked, up to its timeout). */
    static final class RecordingPace implements Pace {
        final List<Duration> pauses = new ArrayList<>();

        @Override
        public void pause(Duration duration) {
            pauses.add(duration);
        }

        @Override
        public boolean poll(java.util.function.BooleanSupplier check, Duration timeout, Duration interval) {
            if (check.getAsBoolean()) {
                return true;
            }
            return false;
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }
    }

    /** One test's wiring: the driver on the page, the shim, the family registered, and a case-run factory. */
    private final class Harness {

        final RecordingPace pace = new RecordingPace();
        final ActionRegistry registry = new ActionRegistry();
        final PosUiContext ctx;

        Harness(Page page, PosUiFakeShim shim) {
            PosDriver driver = PosDriver.builder(page).timeouts(DRIVER_TIMEOUTS).registerId("local")
                    .settleOverlays(false).build();
            this.ctx = new PosUiContext(driver, shim.client(), key -> {
                String text = WIDGETS.get(key);
                if (text == null) {
                    throw new PatsStepError("VALIDATION KEY " + key + " is not in the test's widgets");
                }
                return text;
            }).withTimings(FAST).withPace(pace);
            PosUiFamily.register(registry, ctx);
        }

        CaseRun run(String key, RunnableAction... actions) {
            RunnableCase kase = new Kase(key, "a test case", "memory", List.of(actions));
            CaseRecord record = new CaseRecord("test::" + key, key, folder.resolve(key), Instant.now());
            return new CaseRun(kase, record, registry,
                    name -> {
                        throw new IllegalStateException("no fixture " + name.key() + " in this test");
                    },
                    Set.of(FixtureName.POS, FixtureName.LEGACY_RMI), new RunState());
        }
    }

    private static List<String> pressed(Page page) {
        List<String> ids = new ArrayList<>();
        Object raw = page.evaluate("() => window.pressed || []");
        if (raw instanceof List<?> list) {
            for (Object id : list) {
                ids.add(String.valueOf(id));
            }
        }
        return ids;
    }

    private static String button(String id) {
        return "<button id=\"" + id + "\" onclick=\"window.pressed=(window.pressed||[]).concat(this.id)\">Go</button>";
    }

    private static String text(String id, String text) {
        return "<div id=\"" + id + "\">" + text + "</div>";
    }

    private static String overlay(String title, String line1) {
        return text(Locators.OVERLAY_MSG_VIEW_TITLE.value(), title) + text(Locators.OVERLAY_MSG_VIEW_LINE1.value(), line1);
    }

    /** A PATS action at {@code index}, with {@code name} and its {@code field, value, ...} pairs. */
    static RunnableAction at(int index, String name, String... nameValuePairs) {
        return Action.of(name, nameValuePairs).withIndex(index);
    }

    /** A case of the given actions, as the runner reads it. */
    record Kase(String key, String title, String source, List<RunnableAction> actions) implements RunnableCase {
    }

}
