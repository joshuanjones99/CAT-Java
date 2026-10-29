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
}
