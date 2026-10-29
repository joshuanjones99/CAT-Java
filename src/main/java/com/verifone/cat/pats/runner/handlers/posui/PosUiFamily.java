package com.verifone.cat.pats.runner.handlers.posui;

import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * The POS UI action family: the actions that act on the POS's own screen (the SLEEP that carries its time, the
 * touches, the text and presence checks, the overlays, login and logout, the till prompts, the app selector).
 *
 * <p>{@link #register} is the family's one entry point; the orchestrator wires every family into one registry. The
 * family's cross-case state (what the POS has shown it takes presses under, when it was last touched) lives in the
 * {@link PosUiContext} it is registered with, so one context is one run.
 *
 * <p>Ported from the Python {@code petrotest.pats.runner.pos_ui}. UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class PosUiFamily {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    /** The PATS action names this family handles, upper-cased. */
    public static final List<String> ACTIONS = List.of(
            "SLEEP", "TOUCH_EVENT", "WIDGET_VALIDATION", "VALIDATION_BY_TEXT", "IS_ELEMENT_PRESENT",
            "IS_ELEMENT_CLICKABLE", "APP_SELECTOR", "CASHIER_LOGIN", "VALIDATE_OVERLAY", "LOGIN", "LOGOUT",
            "ENTER_DRAWER_AMNT", "SELECT_TILL");

    private PosUiFamily() {
    }

    /**
     * Registers the POS UI handlers, and the case-start hook that begins the family's per-case state.
     *
     * @throws IllegalStateException when another family already registered one of these action names
     */
    public static void register(ActionRegistry registry, PosUiContext ctx) {
        PosUi ui = new PosUi(ctx);
        Messages messages = new Messages(ui);
        Sleeps sleeps = new Sleeps(ui);
        Touch touch = new Touch(ui, messages, sleeps);
        Validations validations = new Validations(ui, messages);
        Presence presence = new Presence(ui);
        Operator operator = new Operator(ui);

        registry.onBegin(run -> PosUiCaseState.of(run).started(ctx.pace().nanoTime()));
        registry.register("SLEEP", sleeps::handle);
        registry.register("TOUCH_EVENT", touch::handle);
        registry.register("WIDGET_VALIDATION", validations::widget);
        registry.register("VALIDATION_BY_TEXT", validations::byText);
        registry.register("IS_ELEMENT_PRESENT", presence::present);
        registry.register("IS_ELEMENT_CLICKABLE", presence::clickable);
        registry.register("APP_SELECTOR", operator::appSelector);
        registry.register("CASHIER_LOGIN", operator::cashierLogin);
        registry.register("VALIDATE_OVERLAY", validations::validateOverlay);
        registry.register("LOGIN", operator::login);
        registry.register("LOGOUT", operator::logout);
        registry.register("ENTER_DRAWER_AMNT", operator::enterDrawerAmount);
        registry.register("SELECT_TILL", operator::selectTill);
    }

    /**
     * The first input of a case after the previous case's last one comes no sooner than PATS's two seconds after it.
     * For the scan family (PATS's scan presses share the touch's pause).
     */
    public static void boundaryPause(CaseRun run, PosUiContext ctx) {
        Touch.boundaryPause(run, ctx);
    }

    /**
     * A fresh {@code DialogWatch(run)} for one wait outside this family (the receipt and TLog validations): asked on
     * each look, it gives the reason the wait cannot finish, or null. Giving up records the dead end
     * {@link #clearDeadEnd} presses OK on when the case fails. A case that does not declare the POS has no POS to
     * watch: its watch never gives up.
     */
    public static java.util.function.Supplier<String> dialogWatch(CaseRun run, PosUiContext ctx) {
        if (!run.declares(FixtureName.POS)) {
            return () -> null;
        }
        DialogWatch watch = new DialogWatch(new PosUi(ctx), run);
        return watch::check;
    }

    /**
     * {@code _clear_dead_end}: after a failed case, OK the OK-only message a wait gave up on, if it is still up and
     * the same message. Only that message is pressed. Called by the recovery path, not by an action.
     */
    public static void clearDeadEnd(CaseRun run, PosUiContext ctx) {
        Optional<List<String>> deadEnd = PosUiCaseState.of(run).deadEnd();
        if (deadEnd.isEmpty() || !run.declares(FixtureName.POS)) {
            return;
        }
        PosUi ui = new PosUi(ctx);
        try {
            if (!deadEnd.get().equals(ui.okDialog())) {
                return;
            }
            LOG.warn("{}: pressing OK on {} for the next case", run.kase().key(), deadEnd.get());
            new Messages(ui).pressOk(run);
        } catch (RuntimeException e) {
            // the case has failed already; the next one will say why
            LOG.warn("{}: could not clear {}: {}", run.kase().key(), deadEnd.get(), e.getMessage());
        }
    }
}
