package com.verifone.cat.pats.runner.handlers.posui;

import com.verifone.cat.pats.Elements;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.posshim.ShimReplies;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The helpers every POS UI handler shares: the element an action names, what the POS shows, the overlay the POS
 * draws, the settle after a press, and the keys the pos-shim presses. One instance per {@link PosUiFamily}.
 *
 * <p>Ported from the Python {@code pos_ui.py} helpers ({@code chosen_text}, {@code _express_labels}, {@code _ready},
 * {@code _overlay_texts}, {@code _pushed_overlay}, {@code settle}, {@code _message_showing}) and the legacy RMI calls
 * the handlers make ({@code press_enter}, {@code exit_key}, {@code logout}, {@code sco_cashier_entry}).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class PosUi {

    /** A message's lines as the POS draws them: its title, and up to three lines (absent ones are empty). */
    record Shown(String title, String line1, String line2, String line3) {

        /** Whether any of the four has text. */
        boolean hasText() {
            return !(title + line1 + line2 + line3).isEmpty();
        }

        /** The key the Python compares by: LINE1 to LINE3. */
        List<String> key() {
            return List.of(line1, line2, line3);
        }
    }

    private final PosUiContext ctx;

    PosUi(PosUiContext ctx) {
        this.ctx = ctx;
    }

    PosUiContext ctx() {
        return ctx;
    }

    PosDriver driver() {
        return ctx.driver();
    }

    PosUiTimings timings() {
        return ctx.timings();
    }

    Pace pace() {
        return ctx.pace();
    }

    /** {@code _require}: the field's value, or a step failure when the action has no such field. */
    static String require(RunnableAction action, String field) {
        String value = action.raw(field);
        if (value == null) {
            throw new PatsStepError(action.describe() + " has no " + field + " field (PATS would throw here)");
        }
        return value;
    }

    /** The catalogue element the action names ({@code WEB ELEMENT}, {@code TYPE[ID/XPATH]}). */
    static Locator locator(RunnableAction action) {
        return Elements.resolve(require(action, "WEB ELEMENT"), action.get("TYPE[ID/XPATH]", "ID"));
    }

    /**
     * The text PATS's {@code findElement} reads: the element on top (the last visible), else the first; null when
     * nothing matches.
     */
    String chosenText(Locator locator) {
        List<PosDriver.ElementState> states = driver().elementStates(locator);
        if (states.isEmpty()) {
            return null;
        }
        String shown = null;
        for (PosDriver.ElementState state : states) {
            if (state.visible()) {
                shown = state.text();
            }
        }
        return shown != null ? shown : states.get(0).text();
    }

    /** The texts of the express panel's visible buttons, in document order. */
    List<String> expressLabels() {
        return driver().visibleTexts(Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N);
    }

    /** {@code _ready_among}: an express button reads Reprint, Recall or CSR Func. */
    boolean readyAmong(List<String> labels) {
        List<String> wanted = List.of(
                widget("EXPRESS_REPRINT"), widget("EXPRESS_RECALL"), widget("EXPRESS_CSRFUNC"));
        for (String label : labels) {
            if (wanted.contains(label)) {
                return true;
            }
        }
        return false;
    }

    /** {@code isPOSInReadyState}: the express panel reads Ready now. */
    boolean ready() {
        return readyAmong(expressLabels());
    }

    /** The English text of a widget key. */
    String widget(String key) {
        return ctx.widgetText().apply(key);
    }

    /** The overlay on screen, if any: the message box, else the input dialog (whose label is its line 1). */
    Shown overlayTexts() {
        Shown message = new Shown(lastVisible(Locators.OVERLAY_MSG_VIEW_TITLE),
                lastVisible(Locators.OVERLAY_MSG_VIEW_LINE1), lastVisible(Locators.OVERLAY_MSG_VIEW_LINE2),
                lastVisible(Locators.OVERLAY_MSG_VIEW_LINE3));
        if (message.hasText()) {
            return message;
        }
        Shown input = new Shown(lastVisible(Locators.INPUT_OVERLAY_VIEW_TITLE),
                lastVisible(Locators.INPUT_OVERLAY_VIEW_LABEL1), lastVisible(Locators.INPUT_OVERLAY_VIEW_LABEL2), "");
        return input.hasText() ? input : null;
    }

    /** The overlay's key, as {@link #overlayTexts} gives it; empty when there is no overlay. */
    static List<String> messageKey(Shown shown) {
        return shown == null ? List.of() : shown.key();
    }

    /** Whether a key has any line with text (a message with no lines is never told apart). */
    static boolean keyHasText(List<String> key) {
        for (String line : key) {
            if (!line.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Whether a message box's title or line 1 is up now (one look). */
    boolean messageShowing() {
        return driver().anyVisible(Locators.OVERLAY_MSG_VIEW_TITLE, Locators.OVERLAY_MSG_VIEW_LINE1);
    }

    /**
     * The overlay the register last pushed over RMI, which is what PATS compares: the shim's overlay read, when the
     * case declared the legacy RMI fixture. Null when it did not, or the shim does not answer.
     */
    Shown pushedOverlay(CaseRun run) {
        if (!run.declares(FixtureName.LEGACY_RMI)) {
            return null;
        }
        ShimReplies.Overlay overlay;
        try {
            overlay = ctx.shim().screen().overlay();
        } catch (RuntimeException e) {
            return null; // the DOM read stands on its own; this only widens it
        }
        if (overlay == null) {
            return null;
        }
        return new Shown(nz(overlay.title()), nz(overlay.line1()), nz(overlay.line2()), nz(overlay.line3()));
    }

    /** The review menu the register last pushed over RMI, or empty when the shim does not answer. */
    List<String> pushedReview() {
        try {
            List<String> review = ctx.shim().screen().overlayReview();
            return review == null ? List.of() : review;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * {@code settle}: waits until the screen differs from {@code before} and has held still for the settle quiet, or
     * {@code cap} has passed, whichever is first; with {@code moved}, only for it to hold still.
     *
     * @return whether the screen changed
     */
    boolean settle(String before, boolean moved, Duration cap) {
        PosDriver.Settled settled = driver().settleScreen(before, timings().settleQuiet(), cap, moved);
        return settled.changed() && !settled.text().equals(before);
    }

    /** A fixed pause, bounded: a zero or negative one returns at once. */
    void bounded(Duration duration) {
        if (duration.compareTo(Duration.ZERO) > 0) {
            pace().pause(duration);
        }
    }

    /** {@code max(0, total - elapsed)} since {@code fromNanos}, on the family's clock. */
    Duration left(long fromNanos, Duration total) {
        Duration elapsed = Duration.ofNanos(pace().nanoTime() - fromNanos);
        Duration left = total.minus(elapsed);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** Seconds (a SLEEP's, or {@code CaseRun.budget}'s) as a duration. */
    static Duration seconds(double seconds) {
        return Duration.ofMillis(Math.round(seconds * 1000.0));
    }

    /** The field's value after the case's own budget is taken: {@code CaseRun.budget} with the base as a duration. */
    static double budget(CaseRun run, Duration base) {
        return run.budget(base.toMillis() / 1000.0);
    }

    /** Presses a named POS key through the pos-shim; a step failure when the shim cannot press that key. */
    void pressKey(String key) {
        if (!ctx.shim().supportedKeys().namedKeys().contains(key)) {
            throw new PatsStepError("the pos-shim cannot press " + key);
        }
        ctx.shim().pressKeys(List.of(key));
    }

    /** The legacy RMI's {@code press_enter}: a bare ENTER, or ENTER carrying {@code data} (where "0" is bare). */
    void pressEnter(String data) {
        if (data != null && !"0".equals(data)) {
            ctx.shim().pressEnterWithData(data);
        } else {
            pressKey("ENTER");
        }
    }

    /** The legacy RMI's {@code exit_key}. */
    void pressExit() {
        pressKey("EXIT");
    }

    /** The legacy RMI's {@code logout}, the LOG_OUT key. */
    void pressLogout() {
        pressKey("LOG_OUT");
    }

    /** The overlay sweep every RMI action makes: OK the nags the POS shows (PATS's {@code preProcessPOSAction}). */
    void sweep() {
        driver().clearBlockingOverlays(null);
    }

    /** The text of the action's element, stripped; empty when nothing matches (PATS's {@code chosen_text or ""}). */
    String chosenTextOrEmpty(RunnableAction action) {
        String text = chosenText(locator(action));
        return text == null ? "" : text.strip();
    }

    /** A press has reached the POS: the case has touched it, and the session's last-touch mark moves. */
    void pressed(CaseRun run) {
        PosUiCaseState state = PosUiCaseState.of(run);
        state.touch();
        ctx.session().touched(pace().nanoTime());
    }

    /**
     * {@code ok_dialog}: the lines of the message on screen if its only buttons read OK, else null. The message's key
     * when it has an overlay, else the one-element key of an OK with no overlay.
     */
    List<String> okDialog() {
        List<String> buttons = new ArrayList<>();
        for (Locator locator : List.of(Locators.OVERLAY_MSG_VIEW_OK_BTN, Locators.OVERLAY_MSG_VIEW_CANCEL_BTN)) {
            for (PosDriver.ElementState state : driver().elementStates(locator)) {
                if (state.visible()) {
                    buttons.add(state.text().strip());
                }
            }
        }
        if (buttons.isEmpty()) {
            return null;
        }
        for (String button : buttons) {
            if (!button.toLowerCase(Locale.ROOT).equals("ok")) {
                return null;
            }
        }
        Shown overlay = overlayTexts();
        return overlay == null ? List.of("") : overlay.key();
    }

    /** The texts of the visible elements matching {@code locator}, joined by a space. */
    String visibleJoined(Locator locator) {
        List<String> shown = new ArrayList<>();
        for (PosDriver.ElementState state : driver().elementStates(locator)) {
            if (state.visible()) {
                shown.add(state.text());
            }
        }
        return String.join(" ", shown);
    }

    private String lastVisible(Locator locator) {
        String shown = "";
        for (PosDriver.ElementState state : driver().elementStates(locator)) {
            if (state.visible()) {
                shown = state.text();
            }
        }
        return shown;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
