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

    /** A registry whose TOUCH_EVENT records the case and whether the case before it failed. */
    private static RegistryFactory touching(List<String> seen) {
        return services -> {
            ActionRegistry registry = new ActionRegistry();
            registry.register("TOUCH_EVENT", (run, action) ->
                    seen.add(run.kase().key() + " afterFailure=" + run.afterFailure()));
            registry.register("BOOM", (run, action) -> {
                throw new IllegalStateException("boom");
            });
            return registry;
        };
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
        TestsRunStep step = new TestsRunStep(stopAfterFirst,
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
        TestsRunStep step = new TestsRunStep(pauseAfterFirst,
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
