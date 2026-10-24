package com.verifone.cat.pats.runner.handlers.devices;

import static com.verifone.cat.pats.runner.handlers.HandlerHarness.act;
import static com.verifone.cat.pats.runner.handlers.HandlerHarness.caseRun;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.handlers.HandlerFakes.Dcr;
import com.verifone.cat.pats.runner.handlers.HandlerFakes.Pinpad;
import com.verifone.cat.pats.runner.handlers.HandlerFakes.Pumps;
import com.verifone.cat.pats.runner.handlers.HandlerHarness;
import com.verifone.cat.pos.pinpad.PinpadDouble;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The devices family, driven through the real {@link CaseRun} against the fakes. No device is touched. */
class DevicesFamilyTest {

    @TempDir
    Path folder;

    private final List<Duration> pauses = new ArrayList<>();
    private Pinpad pinpad;
    private Dcr dcr;
    private Pumps pumps;
    private ActionRegistry registry;

    @BeforeEach
    void setUp() {
        pinpad = new Pinpad();
        dcr = new Dcr();
        pumps = new Pumps();
        registry = new ActionRegistry();
        DevicesFamily.register(registry, new DevicesContext(pinpad, dcr, pumps, pauses::add, DevicesTiming.DEFAULT));
        HandlerHarness.registerTestHandlers(registry);
    }

    private CaseRun run(RunnableAction... actions) {
        return HandlerHarness.caseRun(registry, folder, Set.of(), actions);
    }

    @Test
    void registersTheEighteenDevicesActionsAndNoOthers() {
        assertEquals(Set.copyOf(DevicesFamily.ACTIONS), registry.supportedActions().stream()
                .filter(name -> !name.startsWith("SET_") && !name.equals("CARRY"))
                .collect(Collectors.toSet()));
        assertEquals(18, DevicesFamily.ACTIONS.size());
    }

    @Test
    void aCardOnThePinpadIsInsertedByName() {
        run(act(0, "INSERT_OR_SLIDE_CARD", "CARD_NAME", "VISA", "DEVICE", "POS")).run();

        assertEquals(List.of("card:VISA"), pinpad.calls);
        assertTrue(pauses.isEmpty());
    }

    @Test
    void aCardOnTheDcrPausesOneSecondThenInsertsIt() {
        run(act(0, "INSERT_OR_SLIDE_CARD", "CARD_NAME", "VISA", "DEVICE", "DCR1")).run();

        assertEquals(List.of("card:VISA"), dcr.calls);
        assertEquals(List.of(Duration.ofSeconds(1)), pauses);
    }

    @Test
    void aCardWithNoNameIsAStepFailure() {
        assertThrows(PatsStepError.class, () -> run(act(0, "INSERT_OR_SLIDE_CARD")).run());
    }

    @Test
    void removeCardTakesTheCardOutOfThePinpad() {
        run(act(0, "REMOVE_CARD")).run();

        assertEquals(List.of("remove"), pinpad.calls);
    }

    @Test
    void aPromptAlreadyOnTheDisplayPassesWithoutWaiting() {
        pinpad.showing.add("ENTER PIN");

        run(act(0, "WAIT_FOR_PROMPT", "DISPLAY_KEY", "ENTER PIN")).run();

        assertEquals(List.of("prompt:ENTER PIN"), pinpad.calls);
    }

    @Test
    void aPromptThatNeverShowsIsAStepFailure() {
        assertThrows(PatsStepError.class,
                () -> run(act(0, "WAIT_FOR_PROMPT", "DISPLAY_KEY", "ENTER PIN")).run());
    }

    @Test
    void anExactMatchPromptIsRefusedBecauseItIsPortedForTheViperPayPinpadOnly() {
        PatsStepError refused = assertThrows(PatsStepError.class, () -> run(act(0, "WAIT_FOR_PROMPT",
                "DISPLAY_KEY", "ENTER PIN", "DISPLAY_KEY_EXACT_MATCH[YES/NO]", "YES")).run());

        assertTrue(refused.getMessage().contains("ViperPay"), refused.getMessage());
        assertTrue(pinpad.calls.isEmpty());
    }

    @Test
    void aCarriedSleepIsSleptWhenThePromptItWaitedForAlreadyShows() {
        pinpad.showing.add("WELCOME");

        run(act(0, "CARRY", "SECONDS", "7"), act(1, "WAIT_FOR_PROMPT", "DISPLAY_KEY", "WELCOME")).run();

        assertEquals(List.of(Duration.ofSeconds(7)), pauses);
        assertEquals(Duration.ofSeconds(42), pinpad.promptTimeouts.get(pinpad.promptTimeouts.size() - 1));
    }

    @Test
    void aCarriedSleepIsSpentInTheWaitWhenThePromptIsNotYetShowing() {
        pinpad.showingAfterWait.add("WELCOME");

        run(act(0, "CARRY", "SECONDS", "7"), act(1, "WAIT_FOR_PROMPT", "DISPLAY_KEY", "WELCOME")).run();

        assertTrue(pauses.isEmpty());
        assertEquals(Duration.ofSeconds(49), pinpad.promptTimeouts.get(pinpad.promptTimeouts.size() - 1));
    }

    @Test
    void aKeyOnThePinpadIsPressedAndMarksWhenItWasKeyed() {
        CaseRun kase = run(act(0, "GET_ANY_KEY", "ENTER_KEY", "OK"));
        kase.run();

        assertEquals(List.of("key:OK"), pinpad.calls);
        assertTrue(kase.scratch(DevicesFamily.KEYED_AT).isPresent());
    }

    @Test
    void aDecimalIsKeyedAsItsValue() {
        run(act(0, "GET_DECIMAL", "ENTER_DECIMAL", "12.50")).run();

        assertEquals(List.of("decimal:12.50"), pinpad.calls);
    }

    @Test
    void aDecimalThatLosesItsLeadingZerosIsRefusedNotKeyedWrong() {
        assertThrows(PatsStepError.class, () -> run(act(0, "GET_DECIMAL", "ENTER_DECIMAL", "0042")).run());

        assertTrue(pinpad.calls.isEmpty());
    }

    @Test
    void aKeyOnTheRealPinpadDoubleShowsThePressedKeyOnItsDisplay() {
        PinpadDouble double_ = new PinpadDouble();
        ActionRegistry real = new ActionRegistry();
        DevicesFamily.register(real, DevicesContext.withPinpad(double_));

        caseRun(real, folder, Set.of(), act(0, "GET_ANY_KEY", "ENTER_KEY", "OK")).run();

        assertTrue(double_.display().lines().contains("LOOPBACK/KEY OK"), double_.display().lines().toString());
    }

    @Test
    void theDcrPromptKeysAndFunctionKeysAreSentWithTheirPauses() {
        run(act(0, "WAIT_DCR_PROMPT", "DISPLAY_KEY", "ENTER PIN"),
                act(1, "KEY_PRESS", "KEY_PRESS", "5"),
                act(2, "R4")).run();

        assertEquals(List.of("prompt:ENTER PIN", "key:5", "press:R4"), dcr.calls);
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(3)), pauses);
    }

    @Test
    void aLowerFunctionKeyIsPressedAsItsName() {
        run(act(0, "L1")).run();

        assertEquals(List.of("press:L1"), dcr.calls);
        assertEquals(List.of(Duration.ofSeconds(3)), pauses);
    }

    @Test
    void aPostedPresetLiftsOncePastTheFuelAmountThenIsCleared() {
        run(act(0, "SET_FUEL_PRESET"), act(1, "FUEL_AMOUNT", "PUMP NO", "14", "AMOUNT", "20.00"),
                act(2, "FUEL_AMOUNT", "PUMP NO", "14", "AMOUNT", "5.00")).run();

        assertEquals(List.of("fill:14:20.00:1:false:true", "fill:14:5.00:1:false:false"), pumps.calls);
    }

    @Test
    void theFuelAmountPrepayFlagComesFromTheStepThenFromTheCase() {
        run(act(0, "FUEL_AMOUNT", "PUMP NO", "3", "AMOUNT", "9.00", "PREPAY", "true"),
                act(1, "SET_PREPAY"),
                act(2, "FUEL_AMOUNT", "PUMP NO", "3", "AMOUNT", "9.00")).run();

        assertEquals(List.of("fill:3:9.00:1:true:false", "fill:3:9.00:1:true:false"), pumps.calls);
    }

    @Test
    void handleUpUsesThePrepayFlagOfTheCaseNotAField() {
        run(act(0, "SET_PREPAY"), act(1, "HANDLE_UP", "PUMP NO", "3", "PREPAY", "false")).run();

        assertEquals(List.of("up:3:1:true"), pumps.calls);
    }

    @Test
    void handleDownUsesItsOwnPrepayField() {
        run(act(0, "SET_PREPAY"), act(1, "HANDLE_DOWN", "PUMP NO", "3", "PREPAY", "false")).run();

        assertEquals(List.of("down:3:false"), pumps.calls);
    }

    @Test
    void anUnwiredDcrFailsItsStepRatherThanPassing() {
        ActionRegistry unwired = new ActionRegistry();
        DevicesFamily.register(unwired, new DevicesContext(pinpad, DcrDevice.UNAVAILABLE, FuelPumps.UNAVAILABLE,
                pauses::add, DevicesTiming.DEFAULT));
        CaseRun run = caseRun(unwired, folder, Set.of(), act(0, "L2"));

        PatsStepError failed = assertThrows(PatsStepError.class, run::run);

        assertInstanceOf(DeviceUnavailable.class, failed.getCause());
        assertEquals("failed", run.record().steps().get(0).outcome());
    }

    @Test
    void anUnwiredPumpFailsItsStep() {
        ActionRegistry unwired = new ActionRegistry();
        DevicesFamily.register(unwired, DevicesContext.withPinpad(pinpad));

        PatsStepError failed = assertThrows(PatsStepError.class,
                () -> caseRun(unwired, folder, Set.of(), act(0, "HANDLE_UP", "PUMP NO", "2")).run());

        assertInstanceOf(DeviceUnavailable.class, failed.getCause());
    }
}
