package com.verifone.cat.pats.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.report.CaseRecord;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The runner's hooks for the handler families: the before-action, begin and finish marks, and the per-case scratch
 * state. Driven with test-only handlers and hooks; each hook records its order into a list, so the tests read the
 * sequence the runner takes. Port of the Python core's ordering: {@code CaseRun.begin}, {@code CaseRun.do}'s
 * {@code reply_pause} and print mark before the handler, and {@code CaseRun.finish}'s dead-end press.
 */
class CaseHooksTest {

    @TempDir
    Path folder;

    private final List<String> order = new ArrayList<>();

    private CaseRecord record(String key) {
        return new CaseRecord("test::" + key, key, folder.resolve(key), Instant.now());
    }

    private ActionRegistry registry() {
        ActionRegistry registry = new ActionRegistry();
        registry.register("NOOP", (run, action) -> order.add("handler " + action.index()));
        registry.register("FAIL", (run, action) -> {
            throw new IllegalStateException("the button is not there");
        });
        registry.register("SOFT", (run, action) -> {
            throw new ValidationFailure("the overlay read ALERT");
        });
        return registry;
    }

    private CaseRun run(ActionRegistry registry, TestCase kase, CaseRecord record, RunState state) {
        return new CaseRun(kase, record, registry, name -> "fixture " + name.key(), Set.of(), state);
    }

    @Test
    void theBeginHookRunsOnceAfterTheCaseBeginsAndBeforeItsFirstAction() {
        ActionRegistry registry = registry();
        registry.onBegin(run -> order.add("begin " + run.kase().key()));
        TestCase kase = TestCase.of("PETROCORE-9", TestAction.of(0, "NOOP"), TestAction.of(1, "NOOP"));

        run(registry, kase, record("PETROCORE-9"), new RunState()).run();

        assertEquals(List.of("begin PETROCORE-9", "handler 0", "handler 1"), order);
    }

    @Test
    void theBeforeActionHookRunsBeforeEachHandlerWithTheActionInHand() {
        ActionRegistry registry = registry();
        registry.beforeAction((run, action) -> order.add("before " + action.index() + " " + action.name()));
        TestCase kase = TestCase.of("PETROCORE-9", TestAction.of(0, "NOOP"), TestAction.of(1, "NOOP"));

        run(registry, kase, record("PETROCORE-9"), new RunState()).run();

        assertEquals(List.of("before 0 NOOP", "handler 0", "before 1 NOOP", "handler 1"), order);
    }

    @Test
    void hooksRunInTheOrderTheyWereRegistered() {
        ActionRegistry registry = registry();
        registry.beforeAction((run, action) -> order.add("first"));
        registry.beforeAction((run, action) -> order.add("second"));
        TestCase kase = TestCase.of("PETROCORE-9", TestAction.of(0, "NOOP"));

        run(registry, kase, record("PETROCORE-9"), new RunState()).run();

        assertEquals(List.of("first", "second", "handler 0"), order);
    }

    @Test
    void aBeforeActionHookThatThrowsIsAHardStepFailureAndMarksTheCase() {
        ActionRegistry registry = registry();
        registry.beforeAction((run, action) -> {
            throw new IllegalStateException("the reply never came");
        });
        TestCase kase = TestCase.of("PETROCORE-9", TestAction.of(0, "NOOP"));
        CaseRecord record = record("PETROCORE-9");
        RunState state = new RunState();

        assertThrows(com.verifone.cat.pats.runner.PatsStepError.class,
                () -> run(registry, kase, record, state).run());

        assertEquals(List.of("failed"), record.steps().stream().map(CaseRecord.StepRecord::outcome).toList());
        assertTrue(record.steps().get(0).detail().contains("the reply never came"), record.steps().get(0).detail());
        assertTrue(state.beginCase(), "the next case is told the POS was pressed on");
        assertTrue(order.isEmpty(), "the handler never ran");
    }

    @Test
    void aBeforeActionHookIsNotRunForAnActionWithNoHandler() {
        ActionRegistry registry = registry();
        registry.beforeAction((run, action) -> order.add("before"));
        TestCase kase = TestCase.of("PETROCORE-9", TestAction.of(0, "FROBNICATE"));

        assertThrows(com.verifone.cat.pats.runner.PatsStepError.class,
                () -> run(registry, kase, record("PETROCORE-9"), new RunState()).run());

        assertTrue(order.isEmpty());
    }

    @Test
    void aValidationFailureFromABeforeActionHookCarriesOnAsAValidation() {
        ActionRegistry registry = registry();
        registry.beforeAction((run, action) -> {
            throw new ValidationFailure("the sweep found an overlay");
        });
        TestCase kase = TestCase.of("PETROCORE-9", TestAction.of(0, "NOOP"), TestAction.of(1, "NOOP"));

        ValidationsFailed raised = assertThrows(ValidationsFailed.class,
                () -> run(registry, kase, record("PETROCORE-9"), new RunState()).run());

        assertTrue(raised.getMessage().contains("the sweep found an overlay"), raised.getMessage());
        assertTrue(order.isEmpty(), "the handler never ran");
    }

    @Test
    void theFinishHookRunsBeforeTheVerdictWhetherOrNotAnythingFailed() {
        ActionRegistry registry = registry();
        registry.onFinish(run -> order.add("finish " + run.softFailures().size()));
        TestCase passing = TestCase.of("PETROCORE-9", TestAction.of(0, "NOOP"));
        TestCase failing = TestCase.of("PETROCORE-8", TestAction.of(0, "SOFT"));

        run(registry, passing, record("PETROCORE-9"), new RunState()).run();
        assertThrows(ValidationsFailed.class, () -> run(registry, failing, record("PETROCORE-8"), new RunState()).run());

        assertEquals(List.of("handler 0", "finish 0", "finish 1"), order);
    }

    @Test
    void aFinishHookSeesTheSoftFailuresTheDeadEndCheckNeedsAndTheCaseStillFails() {
        ActionRegistry registry = registry();
        registry.onFinish(run -> {
            if (!run.softFailures().isEmpty()) {
                order.add("press OK");
            }
        });
        TestCase kase = TestCase.of("PETROCORE-9", TestAction.of(0, "SOFT"));
        RunState state = new RunState();

        assertThrows(ValidationsFailed.class, () -> run(registry, kase, record("PETROCORE-9"), state).run());

        assertEquals(List.of("press OK"), order);
        assertTrue(state.beginCase(), "the case still failed");
    }

    @Test
    void aBeginHookCanReadTheCaseAndLeaveAMarkForItsActions() {
        ScratchKey<String> mark = ScratchKey.of("tlog-mark", String.class);
        ActionRegistry registry = new ActionRegistry();
        registry.onBegin(run -> run.setScratch(mark, "newest"));
        registry.register("PEEK", (run, action) -> order.add(run.scratch(mark).orElse("none")));
        TestCase kase = TestCase.of("PETROCORE-9", TestAction.of(0, "PEEK"));

        run(registry, kase, record("PETROCORE-9"), new RunState()).run();

        assertEquals(List.of("newest"), order);
    }

    @Test
    void scratchStateIsPerCaseAndTyped() {
        ScratchKey<Boolean> prepay = ScratchKey.of("prepay", Boolean.class);
        ActionRegistry registry = new ActionRegistry();
        registry.register("SET", (run, action) -> run.setScratch(prepay, true));
        registry.register("READ", (run, action) -> order.add(String.valueOf(run.scratch(prepay).orElse(false))));
        RunState state = new RunState();

        run(registry, TestCase.of("PETROCORE-1", TestAction.of(0, "SET"), TestAction.of(1, "READ")),
                record("PETROCORE-1"), state).run();
        run(registry, TestCase.of("PETROCORE-2", TestAction.of(0, "READ")), record("PETROCORE-2"), state).run();

        assertEquals(List.of("true", "false"), order, "a later case does not inherit an earlier case's scratch");
    }

    @Test
    void scratchOrCreateMakesTheValueOnceAndClearScratchForgetsIt() {
        ScratchKey<List> seen = ScratchKey.of("seen", List.class);
        CaseRun run = run(registry(), TestCase.of("PETROCORE-9"), record("PETROCORE-9"), new RunState());
        int[] made = {0};

        List<?> first = run.scratchOrCreate(seen, () -> {
            made[0]++;
            return new ArrayList<>(List.of("a"));
        });
        List<?> again = run.scratchOrCreate(seen, () -> {
            made[0]++;
            return new ArrayList<>();
        });
        assertEquals(1, made[0]);
        assertEquals(first, again);

        run.clearScratch(seen);
        assertEquals(Optional.empty(), run.scratch(seen));
    }

    @Test
    void theBuiltInScratchKeysAreTypedAndDistinctEvenWhenNamedAlike() {
        assertEquals(Boolean.class, ScratchKey.PREPAY.type());
        assertEquals(Boolean.class, ScratchKey.FUEL_PRESET.type());
        assertEquals(String.class, ScratchKey.TRANS_MAP_KEY.type());
        assertEquals(java.util.Map.class, ScratchKey.TRANS_MAP.type());
        assertNotEquals(ScratchKey.of("prepay", Boolean.class), ScratchKey.PREPAY);
    }
}
