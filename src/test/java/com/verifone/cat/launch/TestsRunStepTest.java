package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.recovery.RecoveryTarget;

/**
 * Step 5 end to end over fakes: a fake {@link RegistryFactory} with trivial handlers, a fake recovery target,
 * and case files in a {@code @TempDir}. Nothing here touches a rig, the POS or the petroapps checkout.
 *
 * <p>What it proves about this repository, not about a rig: the cases run in plan order through
 * {@code CaseRun}; the events, {@code steps.log}, pause and stop, the run state carried to the next case,
 * recovery after a failed case, and the fake-POS mode's banner and loopback POS.
 */
class TestsRunStepTest {

    private static final String POS_PATH = "tests/suites/pulled/";

    @TempDir
    Path dir;

    // -- fixtures ----------------------------------------------------------------------------------

    private RunContext context(String id, LaunchConfig config) throws IOException {
        Path folder = dir.resolve(id);
        RunEvents events = RunEvents.open(folder, Clock.systemUTC());
        return new RunContext(config, LaunchFixtures.paths(dir), id, folder, events, new PauseGate(),
                Clock.systemUTC());
    }

    private static LaunchConfig rigRun() {
        return LaunchConfig.forRig(LaunchFixtures.RIG);
    }

    private static LaunchConfig fakePosRun() {
        return new LaunchConfig.Builder(LaunchFixtures.RIG).fakePos(true).build();
    }

    /** A PATS case file with one action, named {@code <key>-TestCase.xml}. */
    private Path writeCase(String key, String action) throws IOException {
        Path folder = Files.createDirectories(dir.resolve("petroapps").resolve(key));
        Path file = folder.resolve(key + "-TestCase.xml");
        Files.writeString(file, "<testscript><testcase name=\"" + key + "\"><action name=\"" + action + "\"/>"
                + "</testcase></testscript>", StandardCharsets.UTF_8);
        return file;
    }

    /** Three cases in plan order: {@code 100}, {@code 200}, {@code 300}, with the given actions. */
    private CaseSource threeCases(String first, String second, String third) throws IOException {
        List<CaseEntry> entries = List.of(
                entry("PETROCORE-100", writeCase("PETROCORE-100", first)),
                entry("PETROCORE-200", writeCase("PETROCORE-200", second)),
                entry("PETROCORE-300", writeCase("PETROCORE-300", third)));
        return config -> entries;
    }

    private static CaseEntry entry(String key, Path file) {
        return new CaseEntry(key, POS_PATH + file.getFileName(), file);
    }

    /**
     * {@code factory}'s registry with every fixture a case declares available (a placeholder object): the step sets a
     * case's declared fixtures up before its first action, and these fakes register actions such as TOUCH_EVENT that
     * declare the POS.
     */
    static RegistryFactory withFixtures(RegistryFactory factory) {
        return new RegistryFactory() {
            @Override
            public ActionRegistry create(RunServices services) {
                return factory.create(services);
            }

            @Override
            public Built build(RunServices services) {
                return new Built(factory.create(services), name -> name, null);
            }
        };
    }

    /** Makes {@code ctx} the whole plan of {@code entries} in plan order, so a {@code @plan_end} case is judged. */
    private static void fullPlan(RunContext ctx, List<CaseEntry> entries) {
        List<com.verifone.cat.plans.PlanCase> rows = new ArrayList<>();
        List<String> nodes = new ArrayList<>();
        for (CaseEntry entry : entries) {
            rows.add(new com.verifone.cat.plans.PlanCase(entry.key(), "", entry.nodeid()));
            nodes.add(entry.nodeid());
        }
        ctx.config(ctx.config().withSuites(nodes));
        ctx.planCases(rows);
    }

    /** A registry whose TOUCH_EVENT records the case and whether the case before it failed. */
    private static RegistryFactory touching(List<String> seen) {
        return withFixtures(services -> {
            ActionRegistry registry = new ActionRegistry();
            registry.register("TOUCH_EVENT", (run, action) ->
                    seen.add(run.kase().key() + " afterFailure=" + run.afterFailure()));
            registry.register("BOOM", (run, action) -> {
                throw new IllegalStateException("boom");
            });
            return registry;
        });
    }

    private static List<JsonNode> events(RunContext ctx) throws IOException {
        return LaunchFixtures.events(ctx.runFolder());
    }

    private static List<String> types(RunContext ctx) throws IOException {
        return LaunchFixtures.types(events(ctx));
    }

    private static JsonNode runFinished(RunContext ctx) throws IOException {
        return events(ctx).stream().filter(e -> "run.finished".equals(e.get("type").asText()))
                .findFirst().orElseThrow();
    }

    private static String stepState(RunContext ctx, String name) {
        return ctx.stepResults().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow().state();
    }

    private static String stepDetail(RunContext ctx, String name) {
        return ctx.stepResults().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow().detail();
    }

    private static List<String> finishedOutcomes(List<JsonNode> events) {
        List<String> out = new ArrayList<>();
        for (JsonNode event : events) {
            if ("test.finished".equals(event.get("type").asText())) {
                out.add(event.get("nodeid").asText() + "=" + event.get("outcome").asText());
            }
        }
        return out;
    }

    /** A recovery target whose SOFT rung restores a testable rig, and which records any drain. */
    private static final class FakeRig implements RecoveryTarget {

        private final List<String> drains = new ArrayList<>();
        private boolean softCancelled;

        @Override
        public String rigName() {
            return LaunchFixtures.RIG;
        }

        @Override
        public boolean isTestable() {
            return softCancelled;
        }

        @Override
        public boolean softCancel() {
            softCancelled = true;
            return true;
        }

        @Override
        public boolean voidOpenTicket() {
            throw new DeviceUnavailable("void", "not in this fake");
        }

        @Override
        public boolean resetState() {
            throw new DeviceUnavailable("reset", "not in this fake");
        }

        @Override
        public boolean runPreflight() {
            throw new DeviceUnavailable("preflight", "not in this fake");
        }

        @Override
        public boolean reprovision() {
            throw new DeviceUnavailable("reprovision", "not in this fake");
        }

        @Override
        public void drain(String reason) {
            drains.add(reason);
        }
    }

    // -- the tests --------------------------------------------------------------------------------

    @Test
    void threeCasesRunInPlanOrderWithTheirEventsStepsAndRunFinished() throws IOException {
        RunContext ctx = context("ok", rigRun());
        List<String> seen = new ArrayList<>();
        TestsRunStep step = new TestsRunStep(touching(seen), threeCases("TOUCH_EVENT", "TOUCH_EVENT", "TOUCH_EVENT"),
                null);

        assertTrue(StepRunner.runLocal(ctx, step));

        assertEquals(List.of("PETROCORE-100 afterFailure=false", "PETROCORE-200 afterFailure=false",
                "PETROCORE-300 afterFailure=false"), seen);
        assertEquals("complete", stepState(ctx, "Tests Run"));
        assertEquals("3/3 tests completed", stepDetail(ctx, "Tests Run"));

        List<JsonNode> events = events(ctx);
        assertEquals(List.of("tests/suites/pulled/PETROCORE-100-TestCase.xml=passed",
                "tests/suites/pulled/PETROCORE-200-TestCase.xml=passed",
                "tests/suites/pulled/PETROCORE-300-TestCase.xml=passed"), finishedOutcomes(events));
        assertEquals(1, types(ctx).stream().filter("test.collected"::equals).count());
        assertEquals(3, types(ctx).stream().filter("test.started"::equals).count());
        assertEquals(6, types(ctx).stream().filter("test.step"::equals).count());

        JsonNode run = runFinished(ctx);
        assertEquals("passed", run.get("outcome").asText());
        assertEquals(3, run.get("passed").asInt());
        assertEquals(0, run.get("failed").asInt());
        assertFalse(run.get("cancelled").asBoolean());

        assertTrue(Files.isRegularFile(ctx.runFolder().resolve("PETROCORE-100").resolve("steps.log")));
        assertTrue(Files.readString(ctx.runFolder().resolve("PETROCORE-100").resolve("steps.log"))
                .contains("TOUCH_EVENT"));
        assertTrue(Files.readString(ctx.runFolder().resolve("run.log")).contains("Tests Run"));
    }

    @Test
    void aFailingCaseIsRecoveredAndTheNextCaseStillRunsAfterIt() throws IOException {
        RunContext ctx = context("recovered", rigRun());
        List<String> seen = new ArrayList<>();
        FakeRig rig = new FakeRig();
        RunServices.Connector connector = base -> new RunServices(base.config(), base.paths(), base.runId(),
                base.runFolder(), base.rig(), null, null, null, base.pinpad(), base.fakePosUrl(), rig);
        TestsRunStep step = new TestsRunStep(touching(seen), threeCases("TOUCH_EVENT", "BOOM", "TOUCH_EVENT"),
                connector);

        assertTrue(StepRunner.runLocal(ctx, step));

        assertEquals(List.of("PETROCORE-100 afterFailure=false", "PETROCORE-300 afterFailure=true"), seen);
        assertEquals(List.of("tests/suites/pulled/PETROCORE-100-TestCase.xml=passed",
                "tests/suites/pulled/PETROCORE-200-TestCase.xml=failed",
                "tests/suites/pulled/PETROCORE-300-TestCase.xml=passed"), finishedOutcomes(events(ctx)));

        JsonNode rung = events(ctx).stream().filter(e -> "recovery.rung".equals(e.get("type").asText()))
                .findFirst().orElseThrow();
        assertEquals(1, rung.get("rung").asInt(), "SOFT is the rung that restored the rig");
        assertEquals("passed", rung.get("outcome").asText());
        assertTrue(rig.drains.isEmpty(), "a recovered rig is not drained");

        assertEquals("complete", stepState(ctx, "Tests Run"));
        assertEquals("3/3 tests completed, 1 failed", stepDetail(ctx, "Tests Run"));
        JsonNode run = runFinished(ctx);
        assertEquals("failed", run.get("outcome").asText());
        assertEquals(2, run.get("passed").asInt());
        assertEquals(1, run.get("failed").asInt());
    }

    /**
     * The Python's {@code actions_completed} gate: a case that failed only on its validations ran every action as
     * PATS does, so the ladder is not walked after it (two plans drained on that, 2026-10-15).
     */
    @Test
    void aCaseThatFailedOnlyOnItsValidationsIsNotWalked() throws IOException {
        RunContext ctx = context("validation-only", rigRun());
        List<String> seen = new ArrayList<>();
        FakeRig rig = new FakeRig();
        RunServices.Connector connector = base -> new RunServices(base.config(), base.paths(), base.runId(),
                base.runFolder(), base.rig(), null, null, null, base.pinpad(), base.fakePosUrl(), rig);
        RegistryFactory checks = services -> {
            ActionRegistry registry = new ActionRegistry();
            registry.register("TOUCH_EVENT", (run, action) -> seen.add(run.kase().key()));
            registry.register("CHECK", (run, action) -> {
                throw new com.verifone.cat.pats.runner.ValidationFailure("receipt differs");
            });
            return registry;
        };
        TestsRunStep step = new TestsRunStep(withFixtures(checks), threeCases("TOUCH_EVENT", "CHECK", "TOUCH_EVENT"),
                connector);

        assertTrue(StepRunner.runLocal(ctx, step));

        assertEquals(List.of("tests/suites/pulled/PETROCORE-100-TestCase.xml=passed",
                "tests/suites/pulled/PETROCORE-200-TestCase.xml=failed",
                "tests/suites/pulled/PETROCORE-300-TestCase.xml=passed"), finishedOutcomes(events(ctx)));
        assertTrue(events(ctx).stream().noneMatch(e -> "recovery.rung".equals(e.get("type").asText())),
                "no ladder walk after a validation-only failure");
        assertFalse(rig.softCancelled, "no rung reached the rig");
        assertEquals(List.of("PETROCORE-100", "PETROCORE-300"), seen);
    }

    /** A {@code @plan_end} case as a {@code .feature} of one PATS action, named {@code <key>-TestCase.feature}. */
    private Path writePlanEndCase(String key, String action) throws IOException {
        Path folder = Files.createDirectories(dir.resolve("suite"));
        Path file = folder.resolve(key + "-TestCase.feature");
        Files.writeString(file, "@" + key + " @plan_end\nFeature: Cadence\n\n  Scenario: the end-of-plan report\n"
                + "    When PATS runs " + action + " with \"REPORTNAME=X\"\n", StandardCharsets.UTF_8);
        return file;
    }

    private static RegistryFactory checking() {
        return withFixtures(services -> {
            ActionRegistry registry = new ActionRegistry();
            registry.register("TOUCH_EVENT", (run, action) -> { });
            registry.register("BOOM", (run, action) -> {
                throw new IllegalStateException("boom");
            });
            registry.register("CHECK", (run, action) -> {
                throw new com.verifone.cat.pats.runner.ValidationFailure("report differs");
            });
            return registry;
        });
    }

    private static JsonNode finished(RunContext ctx, String key) throws IOException {
        return events(ctx).stream().filter(e -> "test.finished".equals(e.get("type").asText())
                && e.get("nodeid").asText().contains(key)).findFirst().orElseThrow();
    }

    /**
     * PETROCORE-37031/37033/37036/37063 in CAT-Java run a4eda2bf8602: an end-of-plan report after two earlier
     * failures. The Python ({@code pats.plugin.PatsCase.run}, {@code pats.feature.PatsScenario.finish}) xfails it,
     * "plan_end verdict untrusted: 2 earlier case(s) failed", as its run 2581320c4367 did; it is neither passed nor
     * failed.
     */
    @Test
    void aPlanEndReportAfterAnEarlierFailureIsAnExpectedFailure() throws IOException {
        RunContext ctx = context("plan-end-untrusted", rigRun());
        List<CaseEntry> entries = List.of(
                entry("PETROCORE-100", writeCase("PETROCORE-100", "BOOM")),
                entry("PETROCORE-200", writeCase("PETROCORE-200", "CHECK")),
                entry("PETROCORE-37031", writePlanEndCase("PETROCORE-37031", "CHECK")));
        fullPlan(ctx, entries);
        TestsRunStep step = new TestsRunStep(checking(), config -> entries, null);

        assertTrue(StepRunner.runLocal(ctx, step));

        JsonNode report = finished(ctx, "PETROCORE-37031");
        assertEquals("skipped", report.get("outcome").asText());
        assertEquals("XFailed", report.get("exc_type").asText());
        assertTrue(report.get("detail").asText().startsWith("plan_end verdict untrusted: 2 earlier case(s) failed\n"),
                report.get("detail").asText());
        assertTrue(report.get("detail").asText().contains("report differs"));
        String log = Files.readString(ctx.runFolder().resolve("PETROCORE-37031").resolve("steps.log"));
        assertTrue(log.contains("EXPECTED FAILURE (xfail)"), log);

        assertEquals("3/3 tests completed, 2 failed", stepDetail(ctx, "Tests Run"));
        JsonNode run = runFinished(ctx);
        assertEquals(0, run.get("passed").asInt());
        assertEquals(2, run.get("failed").asInt(), "the expected failure is not counted as a failure");
    }

    /** With every earlier case passed, a plan_end report's verdict is trusted: a failed validation fails it. */
    @Test
    void aPlanEndReportAfterOnlyPassesIsAFailure() throws IOException {
        RunContext ctx = context("plan-end-trusted", rigRun());
        List<CaseEntry> entries = List.of(
                entry("PETROCORE-100", writeCase("PETROCORE-100", "TOUCH_EVENT")),
                entry("PETROCORE-37031", writePlanEndCase("PETROCORE-37031", "CHECK")));
        fullPlan(ctx, entries);
        TestsRunStep step = new TestsRunStep(checking(), config -> entries, null);

        assertTrue(StepRunner.runLocal(ctx, step));

        assertEquals("failed", finished(ctx, "PETROCORE-37031").get("outcome").asText());
        assertEquals(1, runFinished(ctx).get("failed").asInt());
    }

    /**
     * The Python's {@code PETROTEST_FULL_PLAN_RUN} gate: outside a whole, in-order run of its plan on a rig, a
     * {@code @plan_end} case is skipped before any fixture, with the plugin's words, and counts in nothing.
     */
    @Test
    void aPlanEndCaseOutsideAFullPlanRunIsSkipped() throws IOException {
        RunContext ctx = context("plan-end-skipped", rigRun());
        List<String> seen = new ArrayList<>();
        List<CaseEntry> entries = List.of(
                entry("PETROCORE-100", writeCase("PETROCORE-100", "TOUCH_EVENT")),
                entry("PETROCORE-37031", writePlanEndCase("PETROCORE-37031", "CHECK")));
        // No plan resolved: not a full plan run.
        TestsRunStep step = new TestsRunStep(touching(seen), config -> entries, null);

        assertTrue(StepRunner.runLocal(ctx, step));

        JsonNode report = finished(ctx, "PETROCORE-37031");
        assertEquals("skipped", report.get("outcome").asText());
        assertEquals("Skipped", report.get("exc_type").asText());
        assertEquals(TestsRunStep.PLAN_END_SKIP, report.get("detail").asText());
        assertEquals("2/2 tests completed", stepDetail(ctx, "Tests Run"));
        assertEquals(1, runFinished(ctx).get("passed").asInt());
        assertEquals(0, runFinished(ctx).get("failed").asInt());
        assertEquals(List.of("PETROCORE-100 afterFailure=false"), seen);
    }

    /** A fake-POS run is never a full plan run, whatever its targets. */
    @Test
    void aFakePosRunIsNeverAFullPlanRun() {
        List<com.verifone.cat.plans.PlanCase> cases = List.of(new com.verifone.cat.plans.PlanCase("A-1", "", "t/a"),
                new com.verifone.cat.plans.PlanCase("A-2", "", "t/b"));
        LaunchConfig rig = new LaunchConfig.Builder("lab").suite("t/a").suite("t/b").build();
        assertTrue(PlanStart.isFullPlanRun(rig, cases));
        assertFalse(PlanStart.isFullPlanRun(rig.toBuilder().fakePos(true).build(), cases));
        assertFalse(PlanStart.isFullPlanRun(rig.withSuites(List.of("t/b", "t/a")), cases), "reordered");
        assertFalse(PlanStart.isFullPlanRun(rig.withSuites(List.of("t/a")), cases), "partial");
        assertFalse(PlanStart.isFullPlanRun(rig, List.of(cases.get(0), cases.get(1),
                new com.verifone.cat.plans.PlanCase("A-3", "", null))), "an unmigrated case");
        assertFalse(PlanStart.isFullPlanRun(rig, null), "an unresolved plan");
    }

    /** A plan_end case's step that could not be done is still a failure after earlier failures (PatsStepError). */
    @Test
    void aPlanEndStepFailureIsNeverAnExpectedFailure() throws IOException {
        RunContext ctx = context("plan-end-step", rigRun());
        List<CaseEntry> entries = List.of(
                entry("PETROCORE-200", writeCase("PETROCORE-200", "CHECK")),
                entry("PETROCORE-37031", writePlanEndCase("PETROCORE-37031", "BOOM")));
        fullPlan(ctx, entries);
        TestsRunStep step = new TestsRunStep(checking(), config -> entries, null);

        assertTrue(StepRunner.runLocal(ctx, step));

        assertEquals("failed", finished(ctx, "PETROCORE-37031").get("outcome").asText());
        assertEquals(2, runFinished(ctx).get("failed").asInt());
    }

    /** A failed action walks the rungs in order until one restores the rig, each reaching the run's target. */
    @Test
    void aFailedActionWalksTheRungsInOrderOnTheRunsTarget() throws IOException {
        RunContext ctx = context("walked", rigRun());
        List<String> rungs = new ArrayList<>();
        RecoveryTarget rig = new RecoveryTarget() {
            private boolean voided;

            @Override
            public String rigName() {
                return LaunchFixtures.RIG;
            }

            @Override
            public boolean isTestable() {
                rungs.add("isTestable");
                return voided;
            }

            @Override
            public boolean softCancel() {
                rungs.add("soft");
                return true;
            }

            @Override
            public boolean voidOpenTicket() {
                rungs.add("void");
                voided = true;
                return true;
            }

            @Override
            public boolean resetState() {
                rungs.add("reset");
                return true;
            }

            @Override
            public boolean runPreflight() {
                rungs.add("preflight");
                return true;
            }

            @Override
            public boolean reprovision() {
                rungs.add("reprovision");
                return true;
            }

            @Override
            public void drain(String reason) {
                rungs.add("drain");
            }
        };
        RunServices.Connector connector = base -> new RunServices(base.config(), base.paths(), base.runId(),
                base.runFolder(), base.rig(), null, null, null, base.pinpad(), base.fakePosUrl(), rig);
        TestsRunStep step = new TestsRunStep(touching(new ArrayList<>()),
                threeCases("BOOM", "TOUCH_EVENT", "TOUCH_EVENT"), connector);

        assertTrue(StepRunner.runLocal(ctx, step));

        assertEquals(List.of("soft", "isTestable", "void", "isTestable"), rungs);
        JsonNode rung = events(ctx).stream().filter(e -> "recovery.rung".equals(e.get("type").asText()))
                .findFirst().orElseThrow();
        assertEquals("tests/suites/pulled/PETROCORE-100-TestCase.xml", rung.get("nodeid").asText());
        assertEquals(2, rung.get("rung").asInt(), "VOID restored the rig");
    }

    @Test
    void aStopBetweenCasesEndsTheStepAsStoppedAndWritesNoRunFinished() throws IOException {
        RunContext ctx = context("stopped", rigRun());
        List<String> seen = new ArrayList<>();
        RegistryFactory stopAfterFirst = services -> {
            ActionRegistry registry = new ActionRegistry();
            registry.register("TOUCH_EVENT", (run, action) -> {
                seen.add(run.kase().key());
                ctx.cancel();
            });
            return registry;
        };
        TestsRunStep step = new TestsRunStep(withFixtures(stopAfterFirst),
                threeCases("TOUCH_EVENT", "TOUCH_EVENT", "TOUCH_EVENT"), null);

        assertThrows(StepRunner.Cancelled.class, () -> StepRunner.runLocal(ctx, step));

        assertEquals(List.of("PETROCORE-100"), seen, "the stop is honoured before the next case");
        assertEquals("skipped", stepState(ctx, "Tests Run"));
        assertEquals("stopped by the operator", stepDetail(ctx, "Tests Run"));
        assertFalse(types(ctx).contains("run.finished"), "the launcher, not the step, writes a stopped run's end");
    }

    @Test
    void aPauseHoldsTheNextCaseUntilContinue() throws Exception {
        RunContext ctx = context("paused", rigRun());
        List<String> seen = new ArrayList<>();
        RegistryFactory pauseAfterFirst = services -> {
            ActionRegistry registry = new ActionRegistry();
            registry.register("TOUCH_EVENT", (run, action) -> {
                seen.add(run.kase().key());
                if ("PETROCORE-100".equals(run.kase().key())) {
                    ctx.pause().pause();
                }
            });
            return registry;
        };
        TestsRunStep step = new TestsRunStep(withFixtures(pauseAfterFirst),
                threeCases("TOUCH_EVENT", "TOUCH_EVENT", "TOUCH_EVENT"), null);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> run = pool.submit(() -> StepRunner.runLocal(ctx, step));
            awaitEventText(ctx, "PAUSED before");
            assertEquals(List.of("PETROCORE-100"), seen, "the paused case waits: nothing after it has run");
            ctx.pause().resume();
            assertTrue(run.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(List.of("PETROCORE-100", "PETROCORE-200", "PETROCORE-300"), seen);
        assertTrue(types(ctx).stream().anyMatch("log.line"::equals));
        assertTrue(events(ctx).stream().anyMatch(e -> e.toString().contains("CONTINUED: running")));
        assertEquals("3/3 tests completed", stepDetail(ctx, "Tests Run"));
    }

    @Test
    void aFakePosRunStartsTheFakePosAndItsBannersAndGivesThePinpadDouble() throws IOException {
        RunContext ctx = context("fake", fakePosRun());
        AtomicReference<RunServices> seen = new AtomicReference<>();
        List<String> declared = new ArrayList<>();
        RegistryFactory factory = services -> {
            seen.set(services);
            ActionRegistry registry = new ActionRegistry();
            registry.register("NEEDS_POS", (run, action) -> {
                declared.add("pinpad declared=" + run.declares(FixtureName.PINPAD)
                        + ", pos declared=" + run.declares(FixtureName.POS));
                run.fixture(FixtureName.POS);
            });
            return registry;
        };
        TestsRunStep step = new TestsRunStep(factory, threeCases("NEEDS_POS", "NEEDS_POS", "NEEDS_POS"), null);

        assertTrue(StepRunner.runLocal(ctx, step));

        RunServices services = seen.get();
        assertNotNull(services.fakePosUrl(), "the fake POS is started for the run");
        assertNotNull(services.pinpad(), "fake-POS implies the pinpad double");
        // A case declares the fixtures its actions need (FixtureRequirements), not every fixture the run has: NEEDS_POS
        // is no action the table names, so it declares neither.
        assertTrue(declared.get(0).contains("pinpad declared=false"), "the pinpad is not declared by a case that does not need it");
        assertTrue(declared.get(0).contains("pos declared=false"), "no POS driver, so POS is not declared");

        List<String> lines = new ArrayList<>();
        for (JsonNode event : events(ctx)) {
            if ("log.line".equals(event.get("type").asText())) {
                lines.add(event.get("message").asText());
            }
        }
        assertTrue(lines.stream().anyMatch(line -> line.contains("FAKE POS WAS USED")), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("FAKE PINPAD")), lines.toString());

        // No POS driver: every case fails, and says why, rather than passing.
        List<String> outcomes = finishedOutcomes(events(ctx));
        assertEquals(3, outcomes.stream().filter(o -> o.endsWith("=failed")).count());
        assertEquals("complete", stepState(ctx, "Tests Run"));
        assertEquals("3/3 tests completed, 3 failed", stepDetail(ctx, "Tests Run"));
        assertEquals("failed", runFinished(ctx).get("outcome").asText());
    }

    @Test
    void enteringTheStepIsTestsStartedEvenWhenItFailsBeforeItsFirstCase() throws IOException {
        // The Python's _step_tests sets tests_started first, so Cleanup's day close still runs.
        RunContext ctx = context("nocases", rigRun());
        TestsRunStep step = new TestsRunStep(touching(new ArrayList<>()), config -> {
            throw new IOException("petroapps could not be read");
        }, null);

        assertFalse(ctx.testsStarted());
        StepRunner.runLocal(ctx, step);

        assertEquals("failed", stepState(ctx, "Tests Run"));
        assertTrue(ctx.testsStarted());
    }

    @Test
    void anUnwiredStepIsUnavailableNotPassed() throws IOException {
        RunContext ctx = context("unwired", rigRun());
        TestsRunStep step = new TestsRunStep(null, null, null);

        assertFalse(StepRunner.runLocal(ctx, step));

        assertEquals("failed", stepState(ctx, "Tests Run"));
        assertTrue(stepDetail(ctx, "Tests Run").contains("unavailable"), stepDetail(ctx, "Tests Run"));
        assertFalse(types(ctx).contains("run.finished"), "no session ran, so the launcher writes the stand-in");
    }

    /**
     * A fixture a case declares that the run cannot give errors the case at setup, before its first action, as pytest
     * does with a fixture that raises (the Python's {@code legacy_rmi} under {@code --fake-pos}, a missing pinpad).
     */
    @Test
    void aDeclaredFixtureTheRunCannotGiveErrorsTheCaseAtSetup() throws IOException {
        RunContext ctx = context("setup-error", rigRun());
        List<String> seen = new ArrayList<>();
        RegistryFactory noPos = services -> {
            ActionRegistry registry = new ActionRegistry();
            registry.register("TOUCH_EVENT", (run, action) -> seen.add(run.kase().key()));
            return registry;
        };
        TestsRunStep step = new TestsRunStep(noPos, threeCases("TOUCH_EVENT", "TOUCH_EVENT", "TOUCH_EVENT"), null);

        assertTrue(StepRunner.runLocal(ctx, step));

        assertEquals(List.of(), seen, "no action ran");
        JsonNode first = finished(ctx, "PETROCORE-100");
        assertEquals("error", first.get("outcome").asText());
        assertEquals("DeviceUnavailable", first.get("exc_type").asText());
        assertTrue(first.get("detail").asText().contains("pos"), first.get("detail").asText());
        assertEquals("3/3 tests completed, 3 failed", stepDetail(ctx, "Tests Run"));
        assertEquals(3, runFinished(ctx).get("failed").asInt(), "the session counts its errors as failures");
    }

    /** A Scenario Outline runs once per Examples row, each its own test, as pytest-bdd parametrises it. */
    @Test
    void anOutlineRunsOnceForEachExamplesRow() throws IOException {
        RunContext ctx = context("outline", rigRun());
        List<String> lines = new ArrayList<>();
        RegistryFactory steps = withFixtures(services -> {
            ActionRegistry registry = new ActionRegistry();
            registry.register("CAT_STEP", (run, action) -> lines.add(action.get("STEP")));
            return registry;
        });
        Path file = Files.createDirectories(dir.resolve("authored")).resolve("PETROCORE-500-TestCase.feature");
        Files.writeString(file, "@PETROCORE-500\nFeature: outline\n\n  Scenario Outline: sell <item>\n"
                + "    When the cashier scans item <item>\n    Then the total is <total>\n\n    Examples:\n"
                + "      | item | total |\n      | 111  | 1.00  |\n      | 222  | 2.00  |\n", StandardCharsets.UTF_8);
        List<CaseEntry> entries = List.of(new CaseEntry("PETROCORE-500", "tests/suites/authored/" + file.getFileName(),
                file));
        TestsRunStep step = new TestsRunStep(steps, config -> entries, null);

        assertTrue(StepRunner.runLocal(ctx, step));

        assertEquals(List.of("the cashier scans item 111", "the total is 1.00", "the cashier scans item 222",
                "the total is 2.00"), lines);
        assertEquals(List.of("tests/suites/authored/PETROCORE-500-TestCase.feature[111-1.00]=passed",
                "tests/suites/authored/PETROCORE-500-TestCase.feature[222-2.00]=passed"), finishedOutcomes(events(ctx)));
        assertTrue(Files.isRegularFile(ctx.runFolder().resolve("PETROCORE-500-111-1.00").resolve("steps.log")));
        assertTrue(Files.isRegularFile(ctx.runFolder().resolve("PETROCORE-500-222-2.00").resolve("steps.log")));
        assertEquals("2/2 tests completed", stepDetail(ctx, "Tests Run"));
    }

    /** Polls the run's events file until {@code text} appears; the step writes it before it waits. */
    private static void awaitEventText(RunContext ctx, String text) throws IOException {
        Path events = ctx.runFolder().resolve("events.ndjson");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (Files.exists(events) && Files.readString(events).contains(text)) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("no event containing '" + text + "' within 10 s");
    }
}
