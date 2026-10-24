package com.verifone.cat.pats.runner.handlers.posui;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * The POS's message overlays, as a press meets them: waiting for a message to hide before a press goes through, OK-ing
 * a nag, learning a message that takes presses, and the express label a report's view fills in.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class Messages {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    private final PosUi ui;

    Messages(PosUi ui) {
        this.ui = ui;
    }

    /**
     * {@code _await_message_gone}: a message overlay swallows a press anywhere else, so wait for it to hide first, up
     * to PATS's overlay window; if it is still up, the press goes anyway, and the message is kept to learn from.
     *
     * <p>A nag (NOT ONBOARDED, SYSTEM NOT REGISTERED) never hides by itself, so it is OK-ed first. A message the
     * POS has shown it takes presses ({@link PosUiContext.Session}) is pressed under at once.
     */
    void awaitMessageGone(CaseRun run) {
        if (!ui.messageShowing()) {
            return;
        }
        clearNag();
        if (!ui.messageShowing()) {
            return;
        }
        PosUi.Shown shown = ui.overlayTexts();
        List<String> key = PosUi.messageKey(shown);
        if (ui.ctx().session().takesPresses().contains(key)) {
            LOG.info("{}: pressing at once under the message {}: it takes presses", run.kase().key(), shown);
            return;
        }
        LOG.info("{}: waiting for the message {} to hide", run.kase().key(), shown);
        boolean hidden = ui.pace().poll(() -> !ui.messageShowing(), ui.timings().overlayWait(),
                ui.timings().poll());
        if (!hidden) {
            PosUi.Shown still = ui.overlayTexts();
            LOG.warn("{}: pressing under the message {}", run.kase().key(), still);
            PosUiCaseState.of(run).underMessage(PosUi.messageKey(still));
            return;
        }
        ui.settle(ui.driver().screenText(), true, ui.timings().touchSettle());
    }

    /**
     * {@code _learn_message}: after a press made under a message, learn that it takes presses if the screen answered
     * and the message is still up. A message that hides by itself, or has no lines, is never learned.
     */
    void learnMessage(CaseRun run, boolean changed) {
        PosUiCaseState state = PosUiCaseState.of(run);
        List<String> key = state.underMessage();
        state.underMessage(null);
        if (key == null || !PosUi.keyHasText(key) || !changed
                || ui.ctx().session().takesPresses().contains(key)) {
            return;
        }
        if (PosUi.messageKey(ui.overlayTexts()).equals(key)) {
            ui.ctx().session().learn(key);
            LOG.info("{}: the message {} takes presses; later ones go at once", run.kase().key(), key);
        }
    }

    /**
     * {@code _press_ok}: the message's OK: whichever of its two buttons is visible and reads OK, as an OK-only message
     * lays it out, else its OK button as named.
     */
    void pressOk(CaseRun run) {
        PosDriver driver = ui.driver();
        Duration timeout = ui.timings().uiWait();
        for (Locator locator : List.of(Locators.OVERLAY_MSG_VIEW_OK_BTN, Locators.OVERLAY_MSG_VIEW_CANCEL_BTN)) {
            for (PosDriver.ElementState state : driver.elementStates(locator)) {
                if (state.visible() && state.text().strip().toLowerCase(Locale.ROOT).equals("ok")) {
                    driver.press(locator, timeout);
                    return;
                }
            }
        }
        driver.press(Locators.OVERLAY_MSG_VIEW_OK_BTN, timeout);
    }

    /** {@code _clear_nag}: OK a nag if one is up. A case's own message is left alone. */
    void clearNag() {
        try {
            ui.driver().clearBlockingOverlays(null);
        } catch (RuntimeException e) {
            // the caller's own wait still applies
            LOG.warn("could not clear a nag: {}", e.getMessage());
        }
    }

    /**
     * {@code _await_express_label}: an express button with no label does nothing when pressed, so wait for it to get
     * one, up to the report's build time, then press anyway.
     */
    void awaitExpressLabel(CaseRun run, RunnableAction action) {
        if (!ui.chosenTextOrEmpty(action).isEmpty()) {
            return;
        }
        LOG.info("{}: waiting for {} to be labelled", run.kase().key(), action.get("WEB ELEMENT"));
        boolean labelled = ui.pace().poll(() -> !ui.chosenTextOrEmpty(action).isEmpty(),
                ui.timings().networkReportBuild(), ui.timings().poll());
        if (!labelled) {
            LOG.warn("{}: pressing {} unlabelled", run.kase().key(), action.get("WEB ELEMENT"));
        }
    }
}
