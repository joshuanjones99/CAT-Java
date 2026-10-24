package com.verifone.cat.pats.runner.handlers.posui;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.ScratchKey;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code TOUCH_EVENT}: a press on the POS, present within the ceiling, then clicked. PATS sleeps two seconds before
 * every touch, and cases lean on it, so each press waits for the POS's screen to settle after it, capped at that two
 * seconds. Across a case boundary the first press comes no sooner than two seconds after the last case's last press.
 *
 * <p>A pump's fuel-panel tile is pressed only once a hang-up on that pump has posted its sale, and, for a lifted pump
 * not yet authorised, once its tile shows it calling. A quick tender ("$50 CASH") is followed by PATS's pause before
 * the next press, and the sale it closed is counted for the receipt check. An express label that is still blank, or
 * a message that is up, is waited out before the press.
 *
 * <p>Ported from {@code pos_ui._touch} and its helpers. The Total key's separate settle cap is not kept: the Python's
 * {@code TOTAL_SETTLE} is {@code TOUCH_SETTLE}, so the press takes the same two seconds either way.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class Touch {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    /** Fuel Preset (0xD00003) under Other Fuel Functions: the item whose press starts a preset. */
    static final String FUEL_PRESET = Locators.REVIEW_OVERLAY_VIEW_ITEM_N.at(0xD00003).value();

    /** A pump's tile on the POS fuel panel; group 1 is the pump. */
    static final Pattern PUMP_TILE = Pattern.compile("FuelPanel_\\d+_FuelButton_(\\d+)(?:_\\w+)?");

    /** An item-panel quick tender: "$50 CASH". The space is optional, as "$100<br>CASH" reads "$100CASH". */
    static final Pattern QUICK_TENDER = Pattern.compile("\\$\\d+\\s*CASH");

    private static final Pattern XPATH_TEXT = Pattern.compile("text\\(\\)\\s*=\\s*'([^']*)'");

    private static final Duration MIN_LAST_WAIT = Duration.ofMillis(100);

    private final PosUi ui;
    private final Messages messages;
    private final Sleeps sleeps;

    Touch(PosUi ui, Messages messages, Sleeps sleeps) {
        this.ui = ui;
        this.messages = messages;
        this.sleeps = sleeps;
    }

    /** The touch's handler: {@code TOUCH_EVENT} with its {@code WEB ELEMENT}. */
    void handle(CaseRun run, RunnableAction action) {
        PosUiCaseState state = PosUiCaseState.of(run);
        String element = action.get("WEB ELEMENT");
        if (FUEL_PRESET.equals(element)) {
            run.setScratch(ScratchKey.FUEL_PRESET, Boolean.TRUE);
        }
        Matcher tile = PUMP_TILE.matcher(element);
        if (tile.matches() && run.declares(FixtureName.FUEL_PUMPS)) {
            int pump = Integer.parseInt(tile.group(1));
            FuelPumpsView pumps = run.fixture(FixtureName.FUEL_PUMPS, FuelPumpsView.class);
            if (!pumps.waitForPostedSale(pump, ui.timings().pinpadWait())) {
                LOG.warn("{}: pressing pump {}'s tile anyway: its sale was not posted", run.kase().key(), pump);
            }
            boolean prepay = run.scratch(ScratchKey.PREPAY).orElse(false);
            if (!prepay && pumps.lifted(pump)) {
                awaitPumpCalling(run, pumps, pump);
            }
        }
        // Read before anything else of this press, as PATS's before-action mark reads it.
        int printMark = ui.ctx().printCount().applyAsInt(run);
        if (!element.startsWith(Locators.OVERLAY_MSG_VIEW_ANY.prefix())) {
            messages.awaitMessageGone(run);
        }
        Locator locator = PosUi.locator(action);
        if (locator.name().equals(Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N.name())) {
            messages.awaitExpressLabel(run, action);
        }
        if (!state.touched() && run.afterFailure()) {
            sleeps.awaitReady(run, ui.timings().afterFailureReady(), "after the last case's recovery");
        } else {
            boundaryPause(run, ui.ctx());
        }
        if (state.keyedAt().isPresent()) {
            // PATS's 2 s before a touch, after a key on the pinpad: the screen moves when the terminal answers.
            ui.bounded(ui.left(state.keyedAt().getAsLong(), ui.timings().touchSettle()));
            state.clearKeyed();
        }
        if (state.tenderedAt().isPresent()) {
            ui.bounded(ui.left(state.tenderedAt().getAsLong(), ui.timings().touchSettle()));
            state.clearTendered();
            countTender(run);
        }
        String label = buttonLabel(run, locator);
        Duration cap = ui.timings().touchSettle();
        String before = ui.driver().screenText();
        List<String> labels = ui.expressLabels();
        ui.driver().press(locator, PosUi.seconds(PosUi.budget(run, ui.timings().uiWait())));
        long pressed = ui.pace().nanoTime();
        markPressed(state);
        if (QUICK_TENDER.matcher(label).matches()) {
            // The POS is still finishing the sale it paid, and drops a press made meanwhile.
            state.tendered(pressed, printMark, state.salesClosed());
        }
        boolean answered = ui.settle(before, false, cap);
        Optional<Boolean> retried = retryWhileEpsBusy(run, action, cap);
        if (retried.isPresent()) {
            answered = retried.get();
            pressed = ui.pace().nanoTime();
        }
        if (!answered) {
            // A screen that moved and came back to `before` settles early, but it is no answer: PATS's full 2 s.
            ui.bounded(ui.left(pressed, cap));
            LOG.info("{}: nothing on screen answered {}", run.kase().key(), element);
        } else if (endedSale(labels)) {
            awaitSaleClosed(run, action, pressed, printMark);
        }
        if (state.underMessage() != null && !state.underMessage().isEmpty()) {
            messages.learnMessage(run, answered);
        }
    }

    /**
     * {@code boundary_pause}: a case's first input to the POS (a touch, a scan, ENTER) comes no sooner than PATS's two
     * seconds after the last case's last one. Public so the scan family makes the same pause.
     */
    public static void boundaryPause(CaseRun run, PosUiContext ctx) {
        PosUiCaseState state = PosUiCaseState.of(run);
        PosUi ui = new PosUi(ctx);
        Optional<Long> last = ctx.session().lastTouch().isPresent()
                ? Optional.of(ctx.session().lastTouch().getAsLong()) : Optional.empty();
        if (!state.touched() && last.isPresent()) {
            ui.bounded(ui.left(last.get(), ctx.timings().touchSettle()));
        }
    }

    private void markPressed(PosUiCaseState state) {
        long now = ui.pace().nanoTime();
        state.touch();
        ui.ctx().session().touched(now);
    }

    /**
     * {@code _await_pump_calling}: wait for a lifted pump's tile to show it calling, or for the pump to be authorised,
     * up to the pump-calling ceiling; then press anyway.
     */
    private void awaitPumpCalling(CaseRun run, FuelPumpsView pumps, int pump) {
        Locator tile = Locators.FUEL_PANEL_0_FUEL_BUTTON_N.at(pump);
        BooleanSupplier ready = () -> {
            String state = pumps.state(pump);
            if ("BUSYON".equals(state) || "DCRIDLEON".equals(state)) {
                return true;
            }
            for (String cls : ui.driver().elementClasses(tile)) {
                if (cls.contains("CALLING")) {
                    return true;
                }
            }
            return false;
        };
        if (ready.getAsBoolean()) {
            return;
        }
        LOG.info("{}: waiting for pump {}'s tile to show it calling", run.kase().key(), pump);
        if (!ui.pace().poll(ready, ui.timings().pumpCallingWait(), ui.timings().poll())) {
            LOG.warn("{}: pressing pump {}'s tile, not shown calling", run.kase().key(), pump);
        }
    }

    /**
     * {@code _retry_while_eps_busy}: after a press the EPS answered "Eps Busy! Please try again.", OK it, wait the
     * retry gap, press again, for up to the busy ceiling. Empty when the EPS was not busy; else whether the last press
     * changed the screen.
     */
    private Optional<Boolean> retryWhileEpsBusy(CaseRun run, RunnableAction action, Duration cap) {
        String busy = ui.widget("EPS_BUSY_MSG");
        if (!showingBusy(busy)) {
            return Optional.empty();
        }
        Locator locator = PosUi.locator(action);
        String element = action.get("WEB ELEMENT");
        long started = ui.pace().nanoTime();
        int tries = 0;
        boolean answered = false;
        while (showingBusy(busy)) {
            Duration waited = Duration.ofNanos(ui.pace().nanoTime() - started);
            if (waited.compareTo(ui.timings().epsBusyWait()) >= 0) {
                LOG.warn("{}: the EPS was still busy after {} presses of {} over {}s; left as it is",
                        run.kase().key(), tries + 1, element, waited.toSeconds());
                return Optional.of(answered);
            }
            tries++;
            LOG.info("{}: the EPS answered {} {}; OK, and {} again in {}s (retry {})", run.kase().key(), element,
                    busy, element, ui.timings().epsBusyRetryGap().toSeconds(), tries);
            messages.pressOk(run);
            Duration gap = min(ui.timings().epsBusyRetryGap(),
                    max(Duration.ZERO, ui.timings().epsBusyWait().minus(waited)));
            ui.bounded(gap);
            String before = ui.driver().screenText();
            ui.driver().press(locator, PosUi.seconds(PosUi.budget(run, ui.timings().uiWait())));
            markPressed(PosUiCaseState.of(run));
            answered = ui.settle(before, false, cap);
        }
        LOG.info("{}: {} went through after {} retr{}, {}s after the EPS first answered busy", run.kase().key(),
                element, tries, tries == 1 ? "y" : "ies",
                Duration.ofNanos(ui.pace().nanoTime() - started).toSeconds());
        return Optional.of(answered);
    }

    private boolean showingBusy(String busy) {
        PosUi.Shown shown = ui.overlayTexts();
        return shown != null && shown.line1().strip().equals(busy);
    }

    /** {@code _ended_sale}: a press with a sale open after which none does, or the POS reads Ready. */
    private boolean endedSale(List<String> labels) {
        if (ui.readyAmong(labels)) {
            return false;
        }
        List<String> now = ui.expressLabels();
        if (ui.readyAmong(now)) {
            return true;
        }
        String errorCorrect = ui.widget("EXPRESS_ERROR_CORR");
        return labels.contains(errorCorrect) && !now.contains(errorCorrect);
    }

    /**
     * {@code _await_sale_closed}: wait for the POS to read Ready, until PATS's two seconds after the press, then hold it
     * there; if it never reads Ready, the press after goes on as PATS's would. Only a sale the register confirmed by
     * reading Ready is counted.
     */
    private void awaitSaleClosed(CaseRun run, RunnableAction action, long pressed, int printMark) {
        Duration left = ui.timings().touchSettle().minus(Duration.ofNanos(ui.pace().nanoTime() - pressed));
        if (left.compareTo(MIN_LAST_WAIT) < 0) {
            left = MIN_LAST_WAIT;
        }
        if (!ui.pace().poll(ui::ready, left, ui.timings().poll())) {
            LOG.info("{}: {} closed the sale; not Ready in 2 s", run.kase().key(), action.get("WEB ELEMENT"));
            return;
        }
        PosUiCaseState.of(run).countSale(printMark);
        ui.bounded(ui.left(pressed, ui.timings().touchSettle()));
        LOG.info("{}: {} ended the sale; PATS's 2 s ({})", run.kase().key(), action.get("WEB ELEMENT"),
                ui.expressLabels());
    }

    /**
     * {@code _count_tender}: count the sale a quick tender closed, if the POS reads Ready now, two seconds after it.
     * A partial tender leaves the sale open, so it is not counted.
     */
    private void countTender(CaseRun run) {
        PosUiCaseState state = PosUiCaseState.of(run);
        PosUiCaseState.TenderMark mark = state.tenderMark();
        if (mark == null || state.salesClosed() != mark.sales() || !ui.ready()) {
            return; // counted already, or the sale is still open
        }
        state.countSale(mark.prints());
        state.setSalesAtMark(state.salesClosed());
    }

    /** What a pressed item-panel button reads, or the text an XPath names; empty for anything else. */
    private String buttonLabel(CaseRun run, Locator locator) {
        if (locator.name().equals(Locators.ITEM_PANEL_N.name())) {
            String text = ui.chosenText(locator);
            return text == null ? "" : String.join(" ", (text.strip().isEmpty() ? "" : text.strip()).split("\\s+"));
        }
        if (locator.kind() == Locator.Kind.XPATH) {
            Matcher named = XPATH_TEXT.matcher(locator.value());
            return named.find() ? named.group(1).strip() : "";
        }
        return "";
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
