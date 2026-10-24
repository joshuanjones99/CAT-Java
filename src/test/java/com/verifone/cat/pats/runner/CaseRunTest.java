package com.verifone.cat.pats.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.report.CaseRecord;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The runner's framework, driven with test-only handlers: order, the two kinds of failure, the
 * records, the verdict, the recovery hooks. No production handler is registered; these are the only
 * handlers, and they touch no device.
 */
class CaseRunTest {

    @TempDir
    Path folder;

    /** The test-only handlers: each does nothing but what its name says. */
    private static ActionRegistry testRegistry() {
        ActionRegistry registry = new ActionRegistry();
        registry.register("NOOP", (run, action) -> { });
        registry.register("SOFT", (run, action) -> {
            throw new ValidationFailure("the overlay read ALERT");
        });
        registry.register("HARD", (run, action) -> {
            throw new IllegalStateException("the button is not there");
        });
        registry.register("VALIDATE_OVERLAY", (run, action) -> { });
        registry.register("VALIDATE_RECEIPT", (run, action) -> {
            run.setCompared("the baseline", "the capture");
            throw new ValidationFailure("differs");
        });
        registry.register("CARRY", (run, action) -> run.addCarry(Double.parseDouble(action.get("SECONDS"))));
        return registry;
    }

    private CaseRecord record(String key) {
        return new CaseRecord("test::" + key, key, folder.resolve(key), Instant.now());
    }

    private CaseRun run(TestCase kase, CaseRecord record, RunState state, Set<FixtureName> declared) {
        return new CaseRun(kase, record, testRegistry(), name -> "fixture " + name.key(), declared, state);
    }

    private CaseRun run(TestCase kase, CaseRecord record) {
        return run(kase, record, new RunState(), Set.of());
    }

    @Test
    void actionsRunInOrderAndEachIsAPassedStep() {
        CaseRecord record = record("PETROCORE-9");
        TestCase kase = TestCase.of("PETROCORE-9", TestAction.of(0, "NOOP"), TestAction.of(1, "NOOP", "F", "v"));

        run(kase, record).run();

        assertEquals(List.of("#1 NOOP()", "#2 NOOP(F='v')"),
                record.steps().stream().map(CaseRecord.StepRecord::label).toList());
        assertEquals(List.of("passed", "passed"), record.steps().stream().map(CaseRecord.StepRecord::outcome).toList());
        assertTrue(record.validations().isEmpty());
    }

    @Test
    void aHardFailureStopsTheCaseThereAndTheRestAreListedAsNotReached() {
        CaseRecord record = record("PETROCORE-9");
        TestCase kase = TestCase.of("PETROCORE-9", TestAction.of(0, "NOOP"), TestAction.of(1, "HARD"),
                TestAction.of(2, "NOOP"));

        PatsStepError raised = assertThrows(PatsStepError.class, () -> run(kase, record).run());

        assertTrue(raised.getMessage().startsWith("PETROCORE-9 #2 HARD(): "), raised.getMessage());
        assertTrue(raised.getMessage().contains("the button is not there"), raised.getMessage());
        List<CaseRecord.StepRecord> steps = record.steps();
        assertEquals(List.of("passed", "failed", "skipped"),
                steps.stream().map(CaseRecord.StepRecord::outcome).toList());
        assertEquals("not reached", steps.get(2).detail());
        assertTrue(steps.get(1).detail().contains("the button is not there"));
    }

    @Test
    void aValidationFailureIsListedAndTheCaseCarriesOn() {
        CaseRecord record = record("PETROCORE-9");
        TestCase kase = TestCase.of("PETROCORE-9", TestAction.of(0, "SOFT"), TestAction.of(1, "NOOP"),
                TestAction.of(2, "SOFT"));
        CaseRun run = run(kase, record);

        ValidationsFailed verdict = assertThrows(ValidationsFailed.class, run::run);

        assertTrue(verdict.actionsCompleted(), "every action ran, so a teardown leaves the rig as it is");
        assertTrue(verdict.getMessage().contains("2 validation(s) failed"), verdict.getMessage());
        assertTrue(verdict.getMessage().contains("#1 SOFT(): the overlay read ALERT"), verdict.getMessage());
        assertTrue(verdict.getMessage().contains("#3 SOFT(): the overlay read ALERT"), verdict.getMessage());
        assertEquals(List.of("failed", "passed", "failed"),
                record.steps().stream().map(CaseRecord.StepRecord::outcome).toList());
        assertTrue(record.steps().get(0).detail().startsWith("validation failed; the case carried on"),
                record.steps().get(0).detail());
        assertEquals(2, run.softFailures().size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void evidenceIsCapturedForAFailedValidationOnlyWhenThePosWasDeclared(boolean declaresPos) {
        List<String> captured = new ArrayList<>();
        TestCase kase = TestCase.of("PETROCORE-9", TestAction.of(0, "SOFT"));
        Set<FixtureName> declared = declaresPos ? Set.of(FixtureName.POS) : Set.of();

        CaseRun run = run(kase, record("PETROCORE-9"), new RunState(), declared)
                .withEvidence(captured::add);
        assertThrows(ValidationsFailed.class, run::run);

        assertEquals(declaresPos ? List.of("PETROCORE-9-validation-1") : List.of(), captured);
    }

    @Test
    void aFailedEvidenceCaptureDoesNotChangeTheVerdict() {
        TestCase kase = TestCase.of("PETROCORE-9", TestAction.of(0, "SOFT"));
        CaseRun run = run(kase, record("PETROCORE-9"), new RunState(), Set.of(FixtureName.POS))
                .withEvidence(label -> {
                    throw new IllegalStateException("no screen");
                });

        assertThrows(ValidationsFailed.class, run::run);
    }

    @Test
    void aValidationThatFailsRecordsItsComparedTextsUnlessTheHandlerRecordedItsOwn() {
        CaseRecord record = record("PETROCORE-9");
        TestCase kase = TestCase.of("PETROCORE-9", TestAction.of(0, "VALIDATE_RECEIPT", "SELECT_FILE", "r.txt"));

        assertThrows(ValidationsFailed.class, run(kase, record)::run);

        CaseRecord.ValidationRecord verdict = record.validations().get(0);
        assertEquals("receipt", verdict.kind());
        assertEquals("r.txt", verdict.name());
        assertFalse(verdict.passed());
        assertEquals("differs", verdict.diff());
        assertEquals("the baseline", verdict.expected());
        assertEquals("the capture", verdict.actual());
        assertEquals("PETROCORE-9", verdict.caseKey());
    }

    @Test
    void aHandlerThatRecordedItsOwnVerdictIsNotRecordedTwice() {
        CaseRecord record = record("PETROCORE-9");
        ActionRegistry registry = new ActionRegistry();
        registry.register("VALIDATE_RECEIPT", (run, action) -> {
            run.record().recordValidation("receipt", "its own", false, "d", "", "", "");
            throw new ValidationFailure("d");
        });
        TestCase kase = TestCase.of("PETROCORE-9", TestAction.of(0, "VALIDATE_RECEIPT", "SELECT_FILE", "r.txt"));

        assertThrows(ValidationsFailed.class,
                () -> new CaseRun(kase, record, registry, name -> null, Set.of(), new RunState()).run());

        assertEquals(List.of("its own"), record.validations().stream().map(CaseRecord.ValidationRecord::name).toList());
    }

    @Test
    void anUpdateRunWritesItsBaselinesWithoutAVerdictButStillRecordsOtherValidations() {
        CaseRecord record = record("PETROCORE-9");
        ActionRegistry registry = new ActionRegistry();
        registry.register("VALIDATE_RECEIPT", (run, action) -> { });
        registry.register("VALIDATE_OVERLAY", (run, action) -> { });
        TestCase kase = TestCase.of("PETROCORE-9",
                TestAction.of(0, "VALIDATE_RECEIPT", "SELECT_FILE", "r.txt"),
                TestAction.of(1, "VALIDATE_OVERLAY", "TITLE", "ALERT", "LINE1", "BLUE LAW"));

        new CaseRun(kase, record, registry, name -> null, Set.of(), new RunState())
                .withUpdate(true).run();

        assertEquals(List.of("overlay"), record.validations().stream().map(CaseRecord.ValidationRecord::kind).toList());
        assertEquals("ALERT / BLUE LAW", record.validations().get(0).name());
        assertTrue(record.validations().get(0).passed());
    }

    @Test
    void aPassedValidationIsRecordedAsPassed() {
        CaseRecord record = record("PETROCORE-9");
        ActionRegistry registry = new ActionRegistry();
        registry.register("VALIDATE_OVERLAY", (run, action) -> { });
        TestCase kase = TestCase.of("PETROCORE-9", TestAction.of(0, "VALIDATE_OVERLAY", "TITLE", "ALERT"));

        new CaseRun(kase, record, registry, name -> null, Set.of(), new RunState()).run();

        assertTrue(record.validations().get(0).passed());
        assertEquals("ALERT", record.validations().get(0).name());
    }

    @Test
    void aPacketsValidationIsNamedByItsDeviceAndReference() {
        RunnableAction packets = TestAction.of(0, "VALIDATE_PACKETS", "DEVICE", "buypass",
                "SELECT_REFERENCE_FILE", "ref.xml");

        assertEquals("BUYPASS: ref.xml", ValidationKinds.validationName(packets));
    }

    @Test
    void anActionWithNoHandlerFailsBeforeAnythingIsRecordedForIt() {
        CaseRecord record = record("PETROCORE-9");
        TestCase kase = TestCase.of("PETROCORE-9", TestAction.of(0, "FROBNICATE"));
        RunState state = new RunState();

        PatsStepError raised = assertThrows(PatsStepError.class, () -> run(kase, record, state, Set.of()).run());

        assertTrue(raised.getMessage().contains("no handler for FROBNICATE"), raised.getMessage());
        assertTrue(record.steps().isEmpty(), "no step is recorded for an action with no handler, as PATS does");
        assertFalse(state.beginCase(), "nothing was done, so the next case is not told the POS was pressed on");
    }

    @Test
    void anActionWithNoHandlerAfterAnotherLeavesTheEarlierStepPassedAndTheLaterOnesNotReached() {
        CaseRecord record = record("PETROCORE-9");
        TestCase kase = TestCase.of("PETROCORE-9", TestAction.of(0, "NOOP"), TestAction.of(1, "FROBNICATE"),
                TestAction.of(2, "NOOP"));

        assertThrows(PatsStepError.class, () -> run(kase, record).run());

        assertEquals(List.of("passed", "skipped"),
                record.steps().stream().map(CaseRecord.StepRecord::outcome).toList());
    }

    @Test
    void aHardFailureRestoresTheSimulatorConfigurationTheCaseChanged() {
        AtomicBoolean restored = new AtomicBoolean();
        ActionRegistry registry = testRegistry();
        registry.register("CHANGE", (run, action) -> run.recordSimulatorChange("BUYPASS/PayHostSim.prop",
                () -> restored.set(true)));
        TestCase kase = TestCase.of("PETROCORE-9", TestAction.of(0, "CHANGE"), TestAction.of(1, "HARD"));

        assertThrows(PatsStepError.class, () -> new CaseRun(kase, record("PETROCORE-9"), registry,
                name -> null, Set.of(), new RunState()).run());

        assertTrue(restored.get());
    }

    @Test
    void aCaseThatRunsToItsEndKeepsWhatItWrote() {
        AtomicBoolean restored = new AtomicBoolean();
        ActionRegistry registry = testRegistry();
        registry.register("CHANGE", (run, action) -> run.recordSimulatorChange("BUYPASS/PayHostSim.prop",
                () -> restored.set(true)));
        TestCase kase = TestCase.of("PETROCORE-9", TestAction.of(0, "CHANGE"), TestAction.of(1, "NOOP"));

        new CaseRun(kase, record("PETROCORE-9"), registry, name -> null, Set.of(), new RunState()).run();

        assertFalse(restored.get());
    }

    @Test
    void aSleepIsCarriedIntoTheNextWaitAndSpentOnce() {
        TestCase kase = TestCase.of("PETROCORE-9", TestAction.of(0, "CARRY", "SECONDS", "2.5"));
        CaseRun run = run(kase, record("PETROCORE-9"));
        run.run();

        assertEquals(62.5, run.budget(60.0));
        assertEquals(60.0, run.budget(60.0));
    }

    @Test
    void aFailedCaseTellsTheNextOneThePosWasPressedOn() {
        RunState state = new RunState();
        TestCase failing = TestCase.of("PETROCORE-1", TestAction.of(0, "HARD"));
        TestCase next = TestCase.of("PETROCORE-2", TestAction.of(0, "NOOP"));
        TestCase after = TestCase.of("PETROCORE-3", TestAction.of(0, "NOOP"));

        assertThrows(PatsStepError.class, () -> run(failing, record("PETROCORE-1"), state, Set.of()).run());
        CaseRun second = run(next, record("PETROCORE-2"), state, Set.of());
        second.run();
        CaseRun third = run(after, record("PETROCORE-3"), state, Set.of());
        third.begin();

        assertTrue(second.afterFailure());
        assertFalse(third.afterFailure());
    }

    @Test
    void aValidationOnlyFailureAlsoTellsTheNextCase() {
        RunState state = new RunState();
        TestCase soft = TestCase.of("PETROCORE-1", TestAction.of(0, "SOFT"));
        assertThrows(ValidationsFailed.class, () -> run(soft, record("PETROCORE-1"), state, Set.of()).run());

        CaseRun next = run(TestCase.of("PETROCORE-2", TestAction.of(0, "NOOP")), record("PETROCORE-2"), state,
                Set.of());
        next.begin();

        assertTrue(next.afterFailure());
    }

    @Test
    void aCaseWithNoFailedValidationFinishesQuietly() {
        CaseRun run = run(TestCase.of("PETROCORE-9", TestAction.of(0, "NOOP")), record("PETROCORE-9"));

        run.run();

        assertTrue(run.softFailures().isEmpty());
    }

    @Test
    void aHandlerReadsItsFixtureThroughTheFixtureSourceAndTheDeclaredSetIsKept() {
        AtomicReference<String> seen = new AtomicReference<>();
        ActionRegistry registry = new ActionRegistry();
        registry.register("PEEK", (run, action) -> seen.set(run.fixture(FixtureName.POS, String.class)));
        TestCase kase = TestCase.of("PETROCORE-9", TestAction.of(0, "PEEK"));
        CaseRun run = new CaseRun(kase, record("PETROCORE-9"), registry,
                name -> "the " + name.key(), Set.of(FixtureName.POS), new RunState());

        run.run();

        assertEquals("the pos", seen.get());
        assertTrue(run.declares(FixtureName.POS));
        assertFalse(run.declares(FixtureName.DCR));
    }
}
