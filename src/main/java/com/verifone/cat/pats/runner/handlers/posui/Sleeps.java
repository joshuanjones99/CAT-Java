package com.verifone.cat.pats.runner.handlers.posui;

import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * {@code SLEEP}. Its time is carried into the ceiling of the next bounded wait rather than slept, except where the POS
 * needs the time to settle (a forecourt action, a pinpad action's terminal, a keypad OK's report, a case's last SLEEP,
 * a SLEEP beside a CONTROL_FUNCTION). {@link #awaitReady} is the last-SLEEP wait, which the touch family also uses
 * after a failed case.
 *
 * <p>Not ported: the Python's SLEEP beside a driver init (its {@code inits} module). Such a SLEEP follows the general
 * rule here, which is the carry or the slept time PATS gives the other SLEEPs.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class Sleeps {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    /** Forecourt actions whose following SLEEP is waited out, not carried: a lift or a posted fill. */
    static final Set<String> FORECOURT_SETTLE = Set.of("FUEL_AMOUNT", "HANDLE_DOWN", "HANDLE_UP");
    /** Pinpad actions that wait for the terminal themselves, so a SLEEP before one is carried into its budget. */
    static final Set<String> PINPAD_WAITS = Set.of("WAIT_FOR_PROMPT", "GET_ANY_KEY", "GET_DECIMAL", "REMOVE_CARD");
    /** Pinpad actions a SLEEP before is waited out, never carried: the M400 draws its PIN form before it takes keys. */
    static final Set<String> PINPAD_KEYED = Set.of("GET_DECIMAL");
    /** Actions that wait, with a budget, for what a forecourt SLEEP was waiting for. */
    static final Set<String> FORECOURT_OBSERVED_BY = Set.of(
            "VALIDATION_BY_TEXT", "WIDGET_VALIDATION", "VALIDATE_RECEIPT", "VALIDATE_TRANSACTION");

    private static final String SLEEP = "SLEEP";

    private final PosUi ui;

    Sleeps(PosUi ui) {
        this.ui = ui;
    }

    /** The SLEEP handler. */
    void handle(CaseRun run, RunnableAction action) {
        double seconds = Long.parseLong(PosUi.require(action, SLEEP).strip()) / 1000.0;
        Duration duration = PosUi.seconds(seconds);
        List<RunnableAction> actions = run.kase().actions();
        int index = action.index();
        RunnableAction previous = index > 0 ? actions.get(index - 1) : null;
        String before = previous == null ? "" : previous.name().toUpperCase(Locale.ROOT);
        boolean pinpad = previous != null && previous.get("DEVICE").toUpperCase(Locale.ROOT).equals("VIPERPAY");
        boolean keypadOk = before.equals("TOUCH_EVENT")
                && (previous.get("WEB ELEMENT").endsWith("_KEY_OK") || previous.get("WEB ELEMENT").endsWith("_OkBtn"));
        String after = nextName(actions, index);
        boolean last = index == actions.size() - 1;
        boolean keep = run.keepSleeps();
        boolean controlFunction = before.equals("CONTROL_FUNCTION") || after.equals("CONTROL_FUNCTION");
        if (last && !keep && run.declares(FixtureName.POS) && !before.equals("CONTROL_FUNCTION")) {
            awaitReady(run, duration, "last SLEEP");
            return;
        }
        boolean forecourt = FORECOURT_SETTLE.contains(before) && !nextWaitsOnForecourt(run, index);
        if (pinpad && !keep && !controlFunction) {
            if (PINPAD_KEYED.contains(after)) {
                ui.bounded(duration);
                return;
            }
            if (PINPAD_WAITS.contains(after)) {
                run.addCarry(seconds); // the next action waits on the pinpad itself
                return;
            }
            if (run.declares(FixtureName.PINPAD)) {
                PinpadView pad = run.fixture(FixtureName.PINPAD, PinpadView.class);
                Optional<Boolean> settled = pad.settle(duration);
                if (settled.isPresent()) {
                    LOG.info("{}: SLEEP {}s after {}: the pinpad {}", run.kase().key(), (long) seconds, before,
                            settled.get() ? "settled" : "was still busy");
                    return;
                }
            }
        }
        if (keep || last || controlFunction || forecourt || pinpad || keypadOk) {
            ui.bounded(duration);
        } else {
            run.addCarry(seconds);
        }
    }

    /**
     * {@code _await_ready}: a case's last SLEEP, waited on the POS instead of the clock, capped at the SLEEP. No message
     * overlay showing, an express button reading Reprint, Recall or CSR Func, then the screen holding still for the
     * ready quiet. If the POS is not there by the SLEEP's end, the SLEEP has been waited and the case goes on.
     */
    void awaitReady(CaseRun run, Duration seconds, String why) {
        long started = ui.pace().nanoTime();
        BooleanSupplier ready = () -> !ui.messageShowing() && ui.ready();
        if (!ui.pace().poll(ready, seconds, ui.timings().poll())) {
            LOG.info("{}: {} {}s waited out; the POS never read Ready", run.kase().key(), why, seconds.toSeconds());
            return;
        }
        Duration left = ui.left(started, seconds);
        if (!left.isZero()) {
            ui.driver().settleScreen(ui.driver().screenText(), ui.timings().readyQuiet(), left, true);
        }
        LOG.info("{}: {} {}s: the POS read Ready after {}ms", run.kase().key(), why, seconds.toSeconds(),
                Duration.ofNanos(ui.pace().nanoTime() - started).toMillis());
    }

    /**
     * Whether the action after a forecourt SLEEP waits on the forecourt itself, so the SLEEP is carried into its budget
     * rather than slept: a validation, or a press on a pump's tile.
     */
    private boolean nextWaitsOnForecourt(CaseRun run, int index) {
        RunnableAction following = nextAction(run.kase().actions(), index);
        if (following == null) {
            return false;
        }
        String name = following.name().toUpperCase(Locale.ROOT);
        if (FORECOURT_OBSERVED_BY.contains(name)) {
            return true;
        }
        return name.equals("TOUCH_EVENT") && run.declares(FixtureName.FUEL_PUMPS)
                && Touch.PUMP_TILE.matcher(following.get("WEB ELEMENT")).matches();
    }

    private static RunnableAction nextAction(List<RunnableAction> actions, int index) {
        for (RunnableAction later : actions.subList(index + 1, actions.size())) {
            if (!later.name().equals(SLEEP)) {
                return later;
            }
        }
        return null;
    }

    private static String nextName(List<RunnableAction> actions, int index) {
        RunnableAction next = nextAction(actions, index);
        return next == null ? "" : next.name().toUpperCase(Locale.ROOT);
    }
}
