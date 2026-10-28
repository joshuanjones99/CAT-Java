package com.verifone.cat.pats.runner.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.authoring.StepDef;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.pats.ParsedCase;
import com.verifone.cat.pats.feature.LibrarySteps;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunState;
import com.verifone.cat.pats.runner.handlers.steps.LibraryStepFamily;
import com.verifone.cat.pos.pinpad.PinpadDevice;
import com.verifone.cat.report.CaseRecord;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The step library's phrases run through the {@link StandardHandlers} set: every domain and UI phrase of the library has
 * a Java body, a step reaches its device with the line's arguments, a step needing a device the run lacks fails by name,
 * and a line that is no phrase stops the case. Fakes only: no browser, no device.
 */
class LibraryStepFamilyTest {

    @TempDir
    Path root;

    @Test
    void everyDomainAndUiPhraseHasABody() {
        Set<String> library = LibrarySteps.phrases().stream().map(StepDef::text)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(library, new TreeSet<>(LibraryStepFamily.templates()));
    }

    @Test
    void pinpadStepsReachThePinpadWithTheirArguments() throws Exception {
        List<String> calls = new ArrayList<>();
        HandlerFixtures fixtures = WiringFixtures.unbrowsed(root, recordingPinpad(calls));
        CaseRun run = run(fixtures, "the pinpad shows \"donate\"", "the customer presses \"$0.51\" on the pinpad",
                "the customer inserts card \"EMV AMMEX CREDIT\"",
                "the pinpad's \"donate\" prompt clears by itself within 90s");
        run.run();
        assertEquals(List.of("atPrompt[donate]", "key[$0.51]", "insertCard[EMV AMMEX CREDIT]",
                "expectPromptCleared[donate, PT1M30S]"), calls);
    }

    @Test
    void aRegisterKeyWithoutRmiFailsByName() {
        HandlerFixtures fixtures = WiringFixtures.unbrowsed(root, recordingPinpad(new ArrayList<>()));
        PatsStepError e = assertThrows(PatsStepError.class, () -> run(fixtures, "the cashier keys TOTAL").run());
        assertTrue(e.getCause() instanceof DeviceUnavailable, String.valueOf(e.getCause()));
        assertTrue(e.getMessage().contains("#1 the cashier keys TOTAL"), e.getMessage());
    }

    @Test
    void aLineThatIsNoPhraseStopsTheCase() {
        HandlerFixtures fixtures = WiringFixtures.unbrowsed(root, recordingPinpad(new ArrayList<>()));
        PatsStepError e = assertThrows(PatsStepError.class,
                () -> run(fixtures, "the customer presses \"OK\" on the pinpad", "the cashier juggles").run());
        assertTrue(e.getMessage().contains("step definition not found"), e.getMessage());
    }

    @Test
    void theCalculatorKeysPairsOfZeros() {
        assertEquals(List.of("1", "00", "00"), LibraryStepFamily.calculatorKeys("10000"));
        assertEquals(List.of("2", "5", "00", "0"), LibraryStepFamily.calculatorKeys("25000"));
    }

    private CaseRun run(HandlerFixtures fixtures, String... lines) {
        ActionRegistry registry = StandardHandlers.create(fixtures);
        ParsedCase kase = new ParsedCase("PETROCORE-1", "steps", root.resolve("PETROCORE-1-TestCase.feature").toString(),
                LibrarySteps.actions(Arrays.asList(lines)));
        CaseRecord record = new CaseRecord("t::PETROCORE-1", "PETROCORE-1", root, Instant.now());
        return new CaseRun(kase, record, registry, StandardHandlers.fixtureSource(fixtures), Set.of(), new RunState());
    }

    /** A pinpad that records each call as {@code name[args]} and answers with an empty screen. */
    private static PinpadDevice recordingPinpad(List<String> calls) {
        return (PinpadDevice) Proxy.newProxyInstance(PinpadDevice.class.getClassLoader(),
                new Class<?>[] {PinpadDevice.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.getName().equals("toString") ? "recording pinpad" : null;
                    }
                    List<Object> shown = new ArrayList<>();
                    for (Object arg : args == null ? new Object[0] : args) {
                        if (arg != null) {
                            shown.add(arg);
                        }
                    }
                    calls.add(method.getName() + shown);
                    return method.getReturnType() == String.class ? "" : null;
                });
    }
}
