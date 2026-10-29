package com.verifone.cat.runservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.launch.LaunchConfig;
import com.verifone.cat.launch.UpgradeMode;

/**
 * The one current run, driven through a fake engine. Evidence about this repository only: no step,
 * no rig and no {@code C:\CICD} is touched.
 */
class RunServiceTest {

    private static final String STARTED = "{\"type\":\"run.started\",\"at\":\"2026-10-24T10:00:00Z\","
            + "\"run_id\":\"abc123def456\",\"rig\":\"lab\",\"test_plans\":[\"PETROCORE-70960\"],\"fake_pos\":false}";

    @TempDir
    Path tmp;

    private static LaunchConfig config() {
        return new LaunchConfig("lab", List.of(), List.of("PETROCORE-70960"), UpgradeMode.SKIP, false, false,
                false, List.of(), null);
    }

    private RunService service(FakeRunEngine engine) {
        return new RunService("lab", () -> engine, () -> "REPORT", Clock.systemUTC());
    }

    @Test
    void aRunGoesFromStartedToCompleteAndASecondStartIsRefusedWhileItRuns() throws Exception {
        FakeRunEngine engine = new FakeRunEngine(tmp.resolve("run"), List.of(STARTED,
                "{\"type\":\"step.entered\",\"step\":\"System Checks\"}",
                "{\"type\":\"step.finished\",\"step\":\"System Checks\",\"state\":\"complete\",\"detail\":\"ok\"}",
                "{\"type\":\"test.finished\",\"nodeid\":\"n1\",\"outcome\":\"passed\",\"warnings\":0}"), null)
                .closingWith("{\"type\":\"run.finished\",\"outcome\":\"passed\",\"passed\":1,\"failed\":0,"
                        + "\"warned\":0,\"cancelled\":false,\"elapsed\":1.0}");
        RunService service = service(engine);

        service.start(config());
        engine.awaitWritten();
        RunService.View midRun = service.view();

        assertTrue(midRun.busy());
        assertEquals("running", midRun.status());
        assertEquals("abc123def456", midRun.snapshot().runId());
        assertEquals(1, midRun.snapshot().passed());
        assertEquals("complete", midRun.snapshot().steps().get(0).status());
        assertEquals("pending", midRun.snapshot().steps().get(1).status());

        service.start(config());
        assertEquals(RunService.BUSY, service.view().notice());

        engine.release();
        FakeRunEngine.awaitIdle(service);
        RunService.View done = service.view();
        assertEquals("complete", done.status());
        assertTrue(done.ended());
        assertEquals(List.of("PETROCORE-70960"), engine.launchedWith().testPlans());
    }

    @Test
    void pauseAndContinueTogglesTheEngine() throws Exception {
        FakeRunEngine engine = new FakeRunEngine(tmp.resolve("run"), List.of(STARTED), null);
        RunService service = service(engine);
        service.start(config());
        engine.awaitWritten();

        service.togglePause();
        assertTrue(service.view().paused());
        assertEquals("Paused", service.view().status());

        service.togglePause();
        assertFalse(service.view().paused());
        engine.release();
        FakeRunEngine.awaitIdle(service);
    }

    @Test
    void stopIsSentToTheEngineAndPauseAndStopAreRefusedWithNoRun() {
        FakeRunEngine engine = new FakeRunEngine(tmp.resolve("run"), List.of(), null);
        RunService service = service(engine);

        service.togglePause();
        assertEquals(RunService.NOT_RUNNING, service.view().notice());
        service.stop();
        assertEquals(RunService.NOT_RUNNING, service.view().notice());
        assertFalse(engine.stopRequested());
    }

    @Test
    void stopReachesARunThatIsWaiting() throws Exception {
        FakeRunEngine engine = new FakeRunEngine(tmp.resolve("run"), List.of(STARTED), null);
        RunService service = service(engine);
        service.start(config());
        engine.awaitWritten();

        service.stop();

        assertTrue(engine.stopRequested());
        assertTrue(service.view().notice().startsWith("Stopping"));
        FakeRunEngine.awaitIdle(service);
    }

    @Test
    void newRunClearsAFinishedRunAndIsRefusedWhileOneGoes() throws Exception {
        FakeRunEngine engine = new FakeRunEngine(tmp.resolve("run"), List.of(STARTED), null);
        RunService service = service(engine);
        service.start(config());
        engine.awaitWritten();

        service.newRun();
        assertEquals(RunService.BUSY, service.view().notice());

        engine.release();
        FakeRunEngine.awaitIdle(service);
        service.newRun();

        assertFalse(service.view().started());
        assertEquals("", service.view().notice());
    }

    @Test
    void aLaunchThatThrowsIsShownAsAFailedRunNotAnError() throws Exception {
        RunService service = service(new FakeRunEngine(tmp.resolve("run"), List.of(), "boom"));

        service.start(config());
        FakeRunEngine.awaitIdle(service);

        assertEquals("IllegalStateException: boom", service.view().error());
        assertEquals("failed", service.view().status());
    }

    @Test
    void preflightIsStoredAndRefusedWhileARunGoes() throws Exception {
        FakeRunEngine engine = new FakeRunEngine(tmp.resolve("run"), List.of(STARTED), null);
        RunService service = service(engine);

        service.preflight();
        awaitPreflight(service);
        assertEquals("REPORT", service.view().preflight());

        service.start(config());
        engine.awaitWritten();
        service.preflight();
        assertEquals(RunService.BUSY, service.view().notice());

        engine.release();
        FakeRunEngine.awaitIdle(service);
    }

    @Test
    void theLogIsEmptyBeforeTheRunMakesItsFolder() throws Exception {
        FakeRunEngine engine = new FakeRunEngine(tmp.resolve("run"), List.of(), null);
        RunService service = service(engine);
        assertTrue(service.logLines().isEmpty());
        service.start(config());
        engine.awaitWritten();
        assertTrue(service.logLines().isEmpty());
        engine.release();
        FakeRunEngine.awaitIdle(service);
    }

    private static void awaitPreflight(RunService service) {
        long deadline = System.nanoTime() + FakeRunEngine.WAIT.toNanos();
        while (service.view().preflighting()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("the preflight never ended");
            }
            Thread.onSpinWait();
        }
    }

    @Test
    void aMissingSignInRefusesTheStartAsThePythonDoes() {
        FakeRunEngine engine = new FakeRunEngine(tmp.resolve("run"), List.of(STARTED), null);
        RunService service = service(engine).gates(() -> java.util.Map.of("Jira sign-in", "missing"),
                () -> false, () -> false);

        service.start(config());

        assertFalse(service.view().busy());
        assertEquals("Run not started: sign in first on the Setup page. Missing: Jira sign-in",
                service.view().notice());
        assertEquals(null, engine.launchedWith());
    }

    @Test
    void aRecordingOrADownloadStillStagingRefusesTheStart() {
        FakeRunEngine engine = new FakeRunEngine(tmp.resolve("run"), List.of(STARTED), null);
        RunService recording = service(engine).gates(java.util.Map::of, () -> false, () -> true);
        recording.start(config());
        assertEquals(RunService.RECORDING, recording.view().notice());
        recording.preflight();
        assertEquals(RunService.RECORDING, recording.view().notice());

        RunService staging = service(engine).gates(java.util.Map::of, () -> true, () -> false);
        staging.start(config());
        assertEquals(RunService.STAGING, staging.view().notice());
    }

    @Test
    void stopAbandonsAPreflightAndStartIsRefusedWhileOneRuns() throws Exception {
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        FakeRunEngine engine = new FakeRunEngine(tmp.resolve("run"), List.of(STARTED), null);
        RunService service = new RunService("lab", () -> engine, () -> {
            try {
                hold.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "LATE REPORT";
        }, Clock.systemUTC());

        service.preflight();
        assertTrue(service.view().preflighting());
        service.start(config());
        assertEquals(RunService.PREFLIGHTING, service.view().notice());

        service.stop();
        assertFalse(service.view().preflighting());
        assertEquals("Preflight stopped before it finished.", service.view().preflight());
        hold.countDown();
    }

    @Test
    void aStartFromTheConfigTabFetchesTheCasesFirstThenStartsOnTheFreshChoices() throws Exception {
        FakeRunEngine engine = new FakeRunEngine(tmp.resolve("run"), List.of(STARTED), null);
        List<List<String>> fetched = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
        RunService service = service(engine).caseFetch(plans -> {
            fetched.add(plans);
            ready.set(true);
        });

        service.startFromForm(() -> ready.get() ? new RunStart.Plan(config(), "")
                : new RunStart.Plan(null, "Nothing to run yet: a test plan with at least one migrated case.",
                        List.of(), true), List.of("PETROCORE-70960"));
        engine.awaitWritten();

        assertEquals(List.of(List.of("PETROCORE-70960")), fetched);
        assertEquals(List.of("PETROCORE-70960"), engine.launchedWith().testPlans());
        engine.release();
        FakeRunEngine.awaitIdle(service);
    }

    @Test
    void aStartThatStillCannotRunAfterTheFetchSaysWhyAndGoesBackToPreRun() throws Exception {
        FakeRunEngine engine = new FakeRunEngine(tmp.resolve("run"), List.of(STARTED), null);
        RunService service = service(engine).caseFetch(plans -> { });

        service.startFromForm(() -> new RunStart.Plan(null, "Nothing to run yet: no cases.", List.of(), true),
                List.of("PETROCORE-1"));
        FakeRunEngine.awaitIdle(service);

        assertFalse(service.view().started());
        assertEquals("Nothing to run yet: no cases.", service.view().notice());
        assertEquals(null, engine.launchedWith());
    }

    @Test
    void aRefusalThatAFetchCannotFixIsNotStarted() {
        FakeRunEngine engine = new FakeRunEngine(tmp.resolve("run"), List.of(STARTED), null);
        RunService service = service(engine).caseFetch(plans -> { });

        service.startFromForm(() -> new RunStart.Plan(null, "No rig is named.", List.of(), false),
                List.of("PETROCORE-1"));

        assertFalse(service.view().busy());
        assertEquals("No rig is named.", service.view().notice());
    }
}
