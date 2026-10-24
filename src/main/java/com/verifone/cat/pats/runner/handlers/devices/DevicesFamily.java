package com.verifone.cat.pats.runner.handlers.devices;

import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.ScratchKey;
import com.verifone.cat.pos.driver.DriverTimeout;
import com.verifone.cat.pos.pinpad.PinpadDouble;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The devices beside the POS: the pinpad (cards, prompts, keys), the DCR (cards, prompts, keys, function keys),
 * and the forecourt's pumps (FUEL_AMOUNT, HANDLE_UP, HANDLE_DOWN). Ported from the Python
 * {@code petrotest.pats.runner.devices}, which is the specification.
 *
 * <p>Eighteen action names, registered by {@link #register}. A device with no Java implementation fails its step
 * with {@link com.verifone.cat.devices.DeviceUnavailable}; nothing here reports success for it.
 *
 * <p>Two Python behaviours are not carried over and say so where they are: a card's CARD_SIMULATION_IMAGE_FILE is
 * ignored (the Python sends it only to a physical VATS pinpad, and {@link PinpadDevice} has no image), and
 * DISPLAY_KEY_EXACT_MATCH=YES is refused (the Python ports it for the ViperPay pinpad only).
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests only.
 */
public final class DevicesFamily {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner.handlers.devices");

    /** The action names this family registers, as PATS names them (upper-case). */
    public static final List<String> ACTIONS = List.of(
            "INSERT_OR_SLIDE_CARD", "REMOVE_CARD", "WAIT_FOR_PROMPT", "GET_ANY_KEY", "GET_DECIMAL",
            "WAIT_DCR_PROMPT", "KEY_PRESS", "R1", "R2", "R3", "R4", "L1", "L2", "L3", "L4",
            "FUEL_AMOUNT", "HANDLE_DOWN", "HANDLE_UP");

    /**
     * When a case last keyed the pinpad ({@code System.nanoTime}), set by GET_ANY_KEY and GET_DECIMAL. The Python keeps
     * it as {@code run.keyed_at}; a later family reads it through the same key.
     */
    public static final ScratchKey<Long> KEYED_AT = ScratchKey.of("keyed-at", Long.class);

    private static final String PINPAD_EXACT_UNPORTED = "DISPLAY_KEY_EXACT_MATCH=YES is ported for the ViperPay "
            + "pinpad only (PaymentTerminalSimManagerImpl.getPrompt); %s has no exact read of its display";

    private DevicesFamily() {
    }

    /**
     * Registers the eighteen devices handlers.
     *
     * @throws IllegalStateException when one of the names already has a handler
     */
    public static void register(ActionRegistry registry, DevicesContext context) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(context, "context");
        registry.register("INSERT_OR_SLIDE_CARD", (run, action) -> insertCard(run, action, context));
        registry.register("REMOVE_CARD", (run, action) -> context.pinpad().removeCard());
        registry.register("WAIT_FOR_PROMPT", (run, action) -> waitForPrompt(run, action, context));
        registry.register("GET_ANY_KEY", (run, action) -> getAnyKey(run, action, context));
        registry.register("GET_DECIMAL", (run, action) -> getDecimal(run, action, context));
        registry.register("WAIT_DCR_PROMPT", (run, action) -> waitDcrPrompt(run, action, context));
        registry.register("KEY_PRESS", (run, action) -> dcrKeyPress(run, action, context));
        for (String functionKey : new String[] {"R1", "R2", "R3", "R4", "L1", "L2", "L3", "L4"}) {
            registry.register(functionKey, (run, action) -> dcrFunctionKey(run, action, context));
        }
        registry.register("FUEL_AMOUNT", (run, action) -> fuelAmount(run, action, context));
        registry.register("HANDLE_DOWN", (run, action) -> handleDown(run, action, context));
        registry.register("HANDLE_UP", (run, action) -> handleUp(run, action, context));
    }

    // -- cards ----------------------------------------------------------------------------

    /**
     * {@code ViperPay slideCard}, or {@code DCRManager.cardAction} for a DCR. A DCR card is preceded by the input
     * pause. The Python's CARD_SIMULATION_IMAGE_FILE is not sent: it only reaches a physical VATS pinpad.
     */
    static void insertCard(CaseRun run, RunnableAction action, DevicesContext context) {
        String card = ActionFields.require(action, "CARD_NAME");
        if (ActionFields.device(action).startsWith("DCR")) {
            context.pauses().pause(context.timing().dcrInputPause());
            context.dcr().insertCard(card);
            return;
        }
        context.pinpad().insertCard(card, null, ActionFields.budget(run, context.timing().pinpadWait()));
    }

    // -- prompts and keys ---------------------------------------------------------------

    /**
     * ViperPay {@code getPrompt}: a substring of what the pinpad shows. A carried SLEEP is spent first, unless the
     * prompt it is carried into already shows (see {@link #sleepUnlessAwaited}).
     */
    static void waitForPrompt(CaseRun run, RunnableAction action, DevicesContext context) {
        String key = ActionFields.require(action, "DISPLAY_KEY");
        if (action.get("DISPLAY_KEY_EXACT_MATCH[YES/NO]").toUpperCase(Locale.ROOT).equals("YES")) {
            throw new PatsStepError(String.format(Locale.ROOT, PINPAD_EXACT_UNPORTED,
                    context.pinpad().getClass().getSimpleName()));
        }
        double carried = run.carry();
        if (carried > 0 && sleepUnlessAwaited(run, context, key, carried)) {
            run.budget(0.0); // slept: the carry is spent here, so the prompt's budget does not repeat it
        }
        context.pinpad().atPrompt(key, ActionFields.budget(run, context.timing().pinpadWait()));
    }

    /**
     * Sleeps the carried SLEEP when the prompt it is carried into already shows. Carried, a SLEEP is time PATS slept
     * before it looked, spent here waiting for the prompt instead. A prompt already showing before the SLEEP is not
     * what it waited for (Swipe Ahead 74863: the register re-inits the pinpad during a 7 s SLEEP, and "WELCOME" was
     * already up), so the sale would run on before the register is ready.
     *
     * @return true when the carry was slept, false when the prompt was not showing and the carry stays
     */
    private static boolean sleepUnlessAwaited(CaseRun run, DevicesContext context, String key, double carried) {
        if (!isShowing(context, key)) {
            return false;
        }
        LOG.info("{}: the prompt already showed; SLEEP {} s slept", run.kase().key(), Math.round(carried));
        context.pauses().pause(Duration.ofMillis(Math.round(carried * 1000.0)));
        return true;
    }

    private static boolean isShowing(DevicesContext context, String key) {
        try {
            context.pinpad().atPrompt(key, Duration.ZERO);
            return true;
        } catch (PinpadDouble.WaitTimeout | DriverTimeout notShowing) {
            return false;
        }
    }

    /** ViperPay {@code getAnyKey}. */
    static void getAnyKey(CaseRun run, RunnableAction action, DevicesContext context) {
        context.pinpad().key(ActionFields.require(action, "ENTER_KEY"), null,
                ActionFields.budget(run, context.timing().pinpadWait()));
        run.setScratch(KEYED_AT, System.nanoTime());
    }

    /**
     * ViperPay {@code getAnyDecimals}. The Python's VATS path keys the text as written, because {@code Decimal("0042")}
     * would drop a PIN's leading zeros. {@link PinpadDevice} has no digit-for-digit entry, so a text that a
     * {@link BigDecimal} would not reproduce is refused rather than keyed wrong.
     */
    static void getDecimal(CaseRun run, RunnableAction action, DevicesContext context) {
        String entry = ActionFields.require(action, "ENTER_DECIMAL");
        BigDecimal value = new BigDecimal(entry);
        if (!value.toPlainString().equals(entry)) {
            throw new PatsStepError("ENTER_DECIMAL '" + entry + "' would not survive a BigDecimal (leading zeros, a sign "
                    + "or an exponent); PinpadDevice has no digit-for-digit entry yet");
        }
        context.pinpad().decimal(value, null, ActionFields.budget(run, context.timing().pinpadWait()));
        run.setScratch(KEYED_AT, System.nanoTime());
    }

    // -- the DCR ---------------------------------------------------------------------------

    /** {@code DCRManager.waitForDcrDisplay}: any prompt line contains it, ignoring case. */
    static void waitDcrPrompt(CaseRun run, RunnableAction action, DevicesContext context) {
        context.dcr().waitForPrompt(ActionFields.require(action, "DISPLAY_KEY"),
                ActionFields.budget(run, context.timing().dcrWait()));
    }

    /** {@code DCRManager.numericKeyPress}. */
    static void dcrKeyPress(CaseRun run, RunnableAction action, DevicesContext context) {
        context.pauses().pause(context.timing().dcrInputPause());
        context.dcr().keyPress(ActionFields.require(action, "KEY_PRESS"));
    }

    /** {@code DCRManager.funKeyPress}: L1..L4, and R1..R4, which PATS sends as the same keys. */
    static void dcrFunctionKey(CaseRun run, RunnableAction action, DevicesContext context) {
        context.pauses().pause(context.timing().dcrKeyPause());
        context.dcr().press(action.name().toUpperCase(Locale.ROOT));
    }

    // -- the pumps --------------------------------------------------------------------------

    /**
     * {@code PumpManager.fillAmountFuel}: lift with the amount as the preset. A POS Fuel Preset sets the preset flag,
     * which this consumes, so the next lift is not a preset.
     */
    static void fuelAmount(CaseRun run, RunnableAction action, DevicesContext context) {
        boolean preset = run.scratch(ScratchKey.FUEL_PRESET).orElse(false);
        run.setScratch(ScratchKey.FUEL_PRESET, false);
        int pump = Integer.parseInt(ActionFields.require(action, "PUMP NO"));
        String amount = ActionFields.require(action, "AMOUNT");
        int hose = Integer.parseInt(action.get("HOSE NO", "1"));
        context.fuelPumps().fillAmount(pump, amount, hose, prepayFlag(run, action), preset);
    }

    /**
     * {@code FuelSimulator.handleUp} then {@code PumpManager.handleUp}: lift the nozzle with no fill. The prepay flag is
     * the case's own (set by an earlier PREPAY), not a field.
     */
    static void handleUp(CaseRun run, RunnableAction action, DevicesContext context) {
        int pump = Integer.parseInt(ActionFields.require(action, "PUMP NO"));
        int hose = Integer.parseInt(action.get("HOSE NO", "1"));
        context.fuelPumps().handleUp(pump, hose, run.scratch(ScratchKey.PREPAY).orElse(false));
    }

    /** {@code PumpManager.handleDown}. */
    static void handleDown(CaseRun run, RunnableAction action, DevicesContext context) {
        int pump = Integer.parseInt(ActionFields.require(action, "PUMP NO"));
        context.fuelPumps().handleDown(pump, prepayFlag(run, action));
    }

    /**
     * A BDD step's own {@code PREPAY true|false}; an XML case's earlier PREPAY otherwise (the case's prepay flag).
     */
    static boolean prepayFlag(CaseRun run, RunnableAction action) {
        String flag = action.raw("PREPAY");
        if (flag == null) {
            return run.scratch(ScratchKey.PREPAY).orElse(false);
        }
        return flag.strip().toLowerCase(Locale.ROOT).equals("true");
    }
}
