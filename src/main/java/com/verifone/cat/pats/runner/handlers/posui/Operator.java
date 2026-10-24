package com.verifone.cat.pats.runner.handlers.posui;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.driver.PosText;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * The operator's steps on the POS: signing in and out, the self-checkout cashier key, the drawer's opening amount, the
 * till menu, and switching the register between its hosted apps.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The till menu has not been read this way on a rig yet.
 */
final class Operator {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    /** The poll {@code LOGOUT} gives the main menu after each EXIT. */
    private static final Duration MENU_WINDOW = Duration.ofSeconds(3);
    /** The most EXITs a LOGOUT presses before it gives up on the main menu. */
    private static final int MENU_EXITS = 40;
    /** The SCO-mode mark in the hosted app's text: the register is in SCO mode if it is there. */
    private static final String SCO_MARK = "Self Checkout";

    private final PosUi ui;

    Operator(PosUi ui) {
        this.ui = ui;
    }

    /**
     * {@code LOGIN}: ENTER, then ENTER with the id, then with the password. Each waits for the screen it answers, up to
     * the UI ceiling, then goes anyway.
     */
    void login(CaseRun run, RunnableAction action) {
        ui.sweep();
        PosDriver driver = ui.driver();
        Locator product = Locators.IDLE_SCREEN_VIEW_PRODUCT_NAME;
        Locator prompt = Locators.USER_ID_PASSWORD_SCREEN_VIEW_PROMPT_TEXT;
        wait(run, () -> !ui.visibleJoined(prompt).isEmpty() || driver.anyVisible(product), "the login screen");
        ui.pressEnter(null);
        String enterId = ui.widget("ENTER_ID");
        wait(run, () -> ui.visibleJoined(prompt).contains(enterId), "the Enter ID prompt");
        ui.pressEnter(PosUi.require(action, "USER_ID"));
        String enterPassword = ui.widget("ENTER_PASSWORD").strip();
        wait(run, () -> ui.visibleJoined(prompt).contains(enterPassword), "the password prompt");
        ui.pressEnter(PosUi.require(action, "PASSWORD"));
    }

    private void wait(CaseRun run, BooleanSupplier ready, String what) {
        Duration timeout = PosUi.seconds(PosUi.budget(run, ui.timings().uiWait()));
        if (!ui.pace().poll(ready, timeout, ui.timings().poll())) {
            LOG.warn("{}: LOGIN going on without {}; the screen reads {}", run.kase().key(), what,
                    PosText.normalise(ui.visibleJoined(Locators.USER_ID_PASSWORD_SCREEN_VIEW_PROMPT_TEXT)));
        }
    }

    /** {@code LOGOUT}: EXIT until the main menu offers Log Out, then LOG_OUT. */
    void logout(CaseRun run, RunnableAction action) {
        String label = ui.widget("MENU_FN_LOGOUT");
        ui.sweep();
        for (int i = 0; i < MENU_EXITS; i++) {
            ui.pressExit();
            if (ui.pace().poll(() -> ui.expressLabels().contains(label), MENU_WINDOW, Validations.QUARTER_SECOND)) {
                ui.pressLogout();
                return;
            }
        }
        throw new PatsStepError("not in the main menu after 40 EXITs, so no LOG_OUT (as PATS)");
    }

    /** {@code CASHIER_LOGIN}: the self-checkout cashier-entry key. It reads no field. */
    void cashierLogin(CaseRun run, RunnableAction action) {
        ui.pressKey("KEY_SCO_CASHIER_ENTRY");
    }

    /** {@code ENTER_DRAWER_AMNT}: once the calculator asks, ENTER carrying the amount. */
    void enterDrawerAmount(CaseRun run, RunnableAction action) {
        Locator title = Locators.CALCULATOR_VIEW_TITLE_TEXT;
        String text = PosText.normalise(ui.widget("ENTER_OPEN_DRAWER_AMOUNT"));
        Duration timeout = PosUi.seconds(PosUi.budget(run, ui.timings().stateWait()));
        if (!ui.pace().poll(() -> PosText.normalise(String.valueOf(ui.chosenText(title))).contains(text),
                timeout, ui.timings().poll())) {
            throw new PatsStepError(title + " to contain " + text + ": timed out");
        }
        ui.pressEnter(PosUi.require(action, "DRAWER_AMOUNT"));
    }

    /**
     * {@code SELECT_TILL}: once the till menu is up, ENTER carrying the option. Up on screen, or as the review menu the
     * register last pushed over RMI.
     */
    void selectTill(CaseRun run, RunnableAction action) {
        String row = ui.widget("SELECT_TILL_OTHER_FUNCTIONS").toLowerCase(Locale.ROOT);
        BooleanSupplier shown = () -> {
            for (String text : ui.driver().visibleTexts(Locators.REVIEW_OVERLAY_VIEW_ITEM_N)) {
                if (text.toLowerCase(Locale.ROOT).contains(row)) {
                    return true;
                }
            }
            return false;
        };
        BooleanSupplier pushed = () -> {
            List<String> review = ui.pushedReview();
            return review.size() > 2 && review.get(2).strip().toLowerCase(Locale.ROOT).equals(row);
        };
        Duration timeout = PosUi.seconds(PosUi.budget(run, ui.timings().stateWait()));
        if (!ui.pace().poll(() -> shown.getAsBoolean() || pushed.getAsBoolean(), timeout,
                Validations.QUARTER_SECOND)) {
            throw new PatsStepError("the select-till menu was not up within " + timeout.toSeconds() + "s");
        }
        ui.pressEnter(PosUi.require(action, "SELECT_TILL_OPTION"));
    }

    /**
     * {@code APP_SELECTOR}: touches the app selector's switcher, then the panel the case names, waits for the app to
     * switch, and reads the mode the hosted app shows. The mode is logged, never judged. Each touch is a press in the
     * page and the hosted frame both, so there is no frame to switch into.
     */
    void appSelector(CaseRun run, RunnableAction action) {
        PosDriver driver = ui.driver();
        Locator panel = PosUi.locator(action);
        for (Locator target : List.of(Locators.APP_SELECTOR_SWITCHER_VIEW_MAIN_PANEL, panel)) {
            String before = driver.screenText();
            driver.press(target, PosUi.seconds(PosUi.budget(run, ui.timings().uiWait())));
            ui.pressed(run);
            ui.settle(before, false, ui.timings().touchSettle());
        }
        LOG.info("{}: {} pressed; PATS's {} s for the app to switch", run.kase().key(), panel,
                ui.timings().appSwitchSettle().toSeconds());
        ui.bounded(ui.timings().appSwitchSettle());
        String mode = driver.screenText().contains(SCO_MARK) ? "SCO" : "POS";
        LOG.info("{}: the register reads as {} after the switch", run.kase().key(), mode);
    }
}
