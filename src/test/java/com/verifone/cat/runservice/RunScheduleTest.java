package com.verifone.cat.runservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.launch.LaunchConfig;
import com.verifone.cat.launch.UpgradeMode;

/**
 * The schedule and the continuous run through {@link RunService}, with a fake clock and fake engines:
 * no run reaches a step, a device or {@code C:\CICD}. Nothing waits on the wall clock; the test calls
 * {@link RunService#tick()} itself. Evidence about this repository only.
 */
class RunScheduleTest {

    private static final LocalDateTime TEN = LocalDateTime.of(2026, 10, 24, 10, 0, 0);

    private static final String STARTED = "{\"type\":\"run.started\",\"at\":\"2026-10-24T10:00:00Z\","
            + "\"run_id\":\"abc123def456\",\"rig\":\"lab\",\"test_plans\":[\"PETROCORE-70960\"],\"fake_pos\":false}";
    private static final String VERDICT = "{\"type\":\"test.finished\",\"nodeid\":\"n1\",\"outcome\":\"passed\","
            + "\"warnings\":0}";
    private static final String FINISHED = "{\"type\":\"run.finished\",\"outcome\":\"passed\",\"passed\":1,"
            + "\"failed\":0,\"warned\":0,\"cancelled\":false,\"elapsed\":1.0}";

    @TempDir
    Path tmp;

    private MutableClock clock;
    private Queue<FakeRunEngine> engines;
    private RunService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(TEN);
        engines = new ConcurrentLinkedQueue<>();
        service = new RunService("lab", () -> {
            FakeRunEngine next = engines.poll();
            if (next == null) {
                throw new IllegalStateException("the test made no engine for this run");
            }
            return next;
        }, () -> "REPORT", clock);
    }

    /** Queues an engine whose run writes one verdict, so it counts as a pass that ran tests. */
    private FakeRunEngine queue(String folder) {
        FakeRunEngine engine = new FakeRunEngine(tmp.resolve(folder), List.of(STARTED, VERDICT), null)
                .closingWith(FINISHED);
        engines.add(engine);
        return engine;
    }

    private static LaunchConfig config() {
        return new LaunchConfig("lab", List.of(), List.of("PETROCORE-70960"), UpgradeMode.SKIP, false, false,
                false, List.of(), null);
    }

    private static RunStart.Plan plan() {
        return new RunStart.Plan(config(), "");
    }

    /** Waits until the engine has been asked to launch, so a test can read what it was given. */
    private static void awaitLaunch(FakeRunEngine engine) {
        long deadline = System.nanoTime() + FakeRunEngine.WAIT.toNanos();
        while (engine.launchedWith() == null) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("the run was never launched");
            }
            Thread.onSpinWait();
        }
    }

    @Test
    void aScheduledRunFiresAtItsTimeAndNotBefore() throws InterruptedException {
        FakeRunEngine first = queue("scheduled");
        service.schedule("10:05", plan());
        assertEquals("Scheduled for 2026-10-24 10:05:00.", service.scheduleView().status());
        assertEquals(1, service.scheduleView().pending().size());

        clock.set(LocalDateTime.of(2026, 10, 24, 10, 4, 59));
        service.tick();
        assertEquals(null, first.launchedWith());

        clock.set(LocalDateTime.of(2026, 10, 24, 10, 5, 0));
        service.tick();
        first.awaitWritten();
        assertEquals(List.of("PETROCORE-70960"), first.launchedWith().testPlans());
        assertTrue(service.scheduleView().pending().isEmpty());

        first.release();
        FakeRunEngine.awaitIdle(service);
    }

    @Test
    void clearScheduleCancelsEverythingPending() {
        FakeRunEngine never = queue("cleared");
        service.schedule("10:05, 10:10", plan());
        service.clearSchedule();

        assertEquals("Schedule cleared.", service.scheduleView().status());
        assertTrue(service.scheduleView().pending().isEmpty());
        clock.set(LocalDateTime.of(2026, 10, 24, 10, 10, 0));
        service.tick();
        assertEquals(null, never.launchedWith());
    }

    @Test
    void cancellingOneRunLeavesTheOthers() throws InterruptedException {
        FakeRunEngine kept = queue("kept");
        service.schedule("10:05, 10:10", plan());
        int firstId = service.scheduleView().pending().get(0).id();
        service.cancelScheduled(firstId);

        assertEquals(1, service.scheduleView().pending().size());
        clock.set(LocalDateTime.of(2026, 10, 24, 10, 5, 0));
        service.tick();
        assertEquals(1, engines.size(), "the cancelled run must not take an engine");

        clock.set(LocalDateTime.of(2026, 10, 24, 10, 10, 0));
        service.tick();
        kept.awaitWritten();
        kept.release();
        FakeRunEngine.awaitIdle(service);
    }

    @Test
    void aDueRunWaitsWhileAnotherRunHoldsTheRig() throws InterruptedException {
        FakeRunEngine manual = queue("manual");
        FakeRunEngine scheduled = queue("scheduled");
        service.start(config());
        manual.awaitWritten();

        service.schedule("10:05", plan());
        clock.set(LocalDateTime.of(2026, 10, 24, 10, 5, 0));
        service.tick();
        assertEquals(null, scheduled.launchedWith());
        assertEquals(1, service.scheduleView().pending().size());

        manual.release();
        FakeRunEngine.awaitIdle(service);
        service.tick();
        scheduled.awaitWritten();
        scheduled.release();
        FakeRunEngine.awaitIdle(service);
    }

    @Test
    void aRefusedPlanIsSaidAndNothingIsScheduled() {
        service.schedule("10:05", new RunStart.Plan(null, "Nothing to run yet: tick a test plan."));

        assertEquals("Cannot schedule: Nothing to run yet: tick a test plan.", service.scheduleView().status());
        assertTrue(service.scheduleView().pending().isEmpty());
    }

    @Test
    void aBadTimeIsSaidAndNothingIsScheduled() {
        service.schedule("tea time", plan());

        assertTrue(service.scheduleView().status().startsWith("Could not read that time - "));
        assertTrue(service.scheduleView().pending().isEmpty());
    }

    @Test
    void continuousStartsTheNextPassWhenOneFinishesAndStopsWhenAskedAfterThat() throws InterruptedException {
        FakeRunEngine one = queue("pass1");
        FakeRunEngine two = queue("pass2");
        service.toggleContinuous(plan());
        one.awaitWritten();
        assertEquals("Continuous run: pass 1 running.", service.scheduleView().status());

        one.release();
        awaitLaunch(two);
        two.awaitWritten();
        assertEquals("Continuous run: pass 2 running.", service.scheduleView().status());
        assertEquals(2, service.scheduleView().pass());

        service.toggleContinuous(plan());
        assertEquals("The continuous run will finish after pass 2.", service.view().notice());
        assertEquals("Continuous run: finishing after this pass.", service.scheduleView().status());

        two.release();
        FakeRunEngine.awaitIdle(service);
        assertFalse(service.scheduleView().continuous());
        assertEquals("Continuous run finished after 2 pass(es).", service.scheduleView().status());
        assertTrue(engines.isEmpty(), "no third pass may start once the continuous run is finished");
    }

    @Test
    void stoppingAPassEndsTheContinuousRun() throws InterruptedException {
        FakeRunEngine one = queue("stopped");
        service.toggleContinuous(plan());
        one.awaitWritten();

        service.stop();
        FakeRunEngine.awaitIdle(service);

        assertFalse(service.scheduleView().continuous());
        assertEquals("Continuous run ended: pass 1 was stopped or never started.",
                service.scheduleView().status());
    }

    @Test
    void aPassThatRunsNoTestsEndsTheContinuousRun() throws InterruptedException {
        FakeRunEngine empty = new FakeRunEngine(tmp.resolve("empty"), List.of(STARTED), null)
                .closingWith("{\"type\":\"run.finished\",\"outcome\":\"passed\",\"passed\":0,\"failed\":0,"
                        + "\"warned\":0,\"cancelled\":false,\"elapsed\":1.0}");
        engines.add(empty);
        service.toggleContinuous(plan());
        empty.awaitWritten();

        empty.release();
        FakeRunEngine.awaitIdle(service);

        assertFalse(service.scheduleView().continuous());
        assertEquals("Continuous run ended: pass 1 ran no test cases.", service.scheduleView().status());
    }

    @Test
    void aContinuousRunIsRefusedWhileARunGoesOnItsOwn() throws InterruptedException {
        FakeRunEngine manual = queue("manual");
        service.start(config());
        manual.awaitWritten();

        service.toggleContinuous(plan());

        assertEquals(RunService.BUSY, service.view().notice());
        assertFalse(service.scheduleView().continuous());
        manual.release();
        FakeRunEngine.awaitIdle(service);
    }

    @Test
    void aPassHeldForTheForcedAeodStartsOnlyAfterTheHoldHasPassed() throws InterruptedException {
        FakeRunEngine first = queue("held");
        clock.set(LocalDateTime.of(2026, 10, 24, 2, 58, 0));
        service.toggleContinuous(plan());

        assertEquals("03:25", service.scheduleView().holdUntil());
        assertEquals(null, first.launchedWith());

        clock.set(LocalDateTime.of(2026, 10, 24, 3, 24, 0));
        service.tick();
        assertEquals(null, first.launchedWith());

        clock.set(LocalDateTime.of(2026, 10, 24, 3, 25, 0));
        service.tick();
        assertEquals("Continuous run: pass 1 running.", service.scheduleView().status());
        assertEquals("", service.scheduleView().holdUntil());

        first.awaitWritten();
        service.toggleContinuous(plan());
        first.release();
        FakeRunEngine.awaitIdle(service);
        assertFalse(service.scheduleView().continuous());
    }

    @Test
    void aHeldContinuousRunEndsAtOnceWhenAskedAndNeverStarts() {
        FakeRunEngine first = queue("unused");
        clock.set(LocalDateTime.of(2026, 10, 24, 2, 58, 0));
        service.toggleContinuous(plan());

        service.toggleContinuous(plan());
        assertEquals("Continuous run finished.", service.scheduleView().status());
        assertFalse(service.scheduleView().continuous());

        clock.set(LocalDateTime.of(2026, 10, 24, 3, 25, 0));
        service.tick();
        assertEquals(null, first.launchedWith());
    }

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(LocalDateTime start) {
            this.now = start.toInstant(ZoneOffset.UTC);
        }

        void set(LocalDateTime at) {
            now = at.toInstant(ZoneOffset.UTC);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
