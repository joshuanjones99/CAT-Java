package com.verifone.cat.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.verifone.cat.devices.DeviceUnavailable;

/**
 * DESIGN.md 6.3's ladder against a fake POS that recovers at a chosen rung. Evidence about this
 * repository only: no rung touches a register. Ported from the Python {@code test_recovery_ladder.py}.
 */
class RecoveryLadderTest {

    /** A rig that becomes testable once the ladder reaches {@code recoversAt}; null never recovers. */
    static final class FakeTarget implements RecoveryTarget {
        final Rung recoversAt;
        final Rung raisesAt;
        final CountDownLatch hangsSoft;
        final List<String> calls = new ArrayList<>();
        final List<String> drained = new ArrayList<>();
        boolean reached;

        FakeTarget(Rung recoversAt, Rung raisesAt, CountDownLatch hangsSoft) {
            this.recoversAt = recoversAt;
            this.raisesAt = raisesAt;
            this.hangsSoft = hangsSoft;
        }

        private boolean act(Rung rung) {
            calls.add(rung.name());
            if (rung == raisesAt) {
                throw new IllegalStateException(rung.name() + " blew up");
            }
            if (recoversAt != null && rung.compareTo(recoversAt) >= 0) {
                reached = true;
            }
            return true;
        }

        @Override
        public String rigName() {
            return "testrig";
        }

        @Override
        public boolean isTestable() {
            calls.add("is_testable");
            return reached;
        }

        @Override
        public boolean softCancel() {
            if (hangsSoft != null) {
                try {
                    hangsSoft.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return act(Rung.SOFT);
        }

        @Override
        public boolean voidOpenTicket() {
            return act(Rung.VOID);
        }

        @Override
        public boolean resetState() {
            return act(Rung.RESET);
        }

        @Override
        public boolean runPreflight() {
            return act(Rung.PREFLIGHT);
        }

        @Override
        public boolean reprovision() {
            return act(Rung.REPROVISION);
        }

        @Override
        public void drain(String reason) {
            calls.add("DRAIN");
            drained.add(reason);
        }
    }

    private static Timeouts fast() {
        return new Timeouts(Duration.ofSeconds(1), null);
    }

    private static RecoveryLadder ladder(FakeTarget target, RunSummary summary, Capturer capturer) {
        return new RecoveryLadder(target, capturer, "run-1", summary, fast());
    }

    @ParameterizedTest
    @EnumSource(value = Rung.class, names = {"SOFT", "VOID", "RESET", "PREFLIGHT", "REPROVISION"})
    void escalatesInOrderAndStopsAtTheFirstRungThatWorks(Rung recoversAt) {
        FakeTarget target = new FakeTarget(recoversAt, null, null);
        RunSummary summary = new RunSummary("run-1");

        RecoveryOutcome outcome = ladder(target, summary, new Capturer()).recover("PETROCORE-1", "");

        List<Rung> expected = new ArrayList<>(List.of(Rung.CAPTURE));
        expected.addAll(Rung.ESCALATION.subList(0, Rung.ESCALATION.indexOf(recoversAt) + 1));
        assertEquals(expected, outcome.attempted());
        assertEquals(recoversAt, outcome.rung());
        assertTrue(outcome.recovered());
        assertEquals(1, summary.recoveries().size());
        assertEquals(recoversAt, summary.recoveries().get(0).rung());
        assertTrue(summary.drains().isEmpty(), "a recovered trip drains nothing");
    }

    @Test
    void aRigThatNeverRecoversEndsAtDrainAndTheHookIsTold() {
        FakeTarget target = new FakeTarget(null, null, null);
        RunSummary summary = new RunSummary("run-1");

        RecoveryOutcome outcome = ladder(target, summary, new Capturer()).recover("PETROCORE-2", "overlay");

        assertFalse(outcome.recovered());
        assertEquals(Rung.DRAIN, outcome.rung());
        assertEquals(Rung.DRAIN, outcome.attempted().get(outcome.attempted().size() - 1));
        assertEquals(1, target.drained.size());
        assertTrue(target.drained.get(0).contains("exhausted rungs 1-5 after overlay"), target.drained.get(0));
        assertEquals(1, summary.drains().size());
        assertEquals(Rung.DRAIN, summary.recoveries().get(0).rung());
    }

    @Test
    void captureRunsBeforeAnyRung() {
        FakeTarget target = new FakeTarget(Rung.SOFT, null, null);
        Capturer capturer = new Capturer(List.of(new Capturer.Source() {
            @Override
            public String name() {
                return "logs";
            }

            @Override
            public String collect() {
                target.calls.add("capture");
                return "3 lines";
            }
        }), Capturer.CAPTURE_BUDGET);

        RecoveryOutcome outcome = ladder(target, new RunSummary("run-1"), capturer).recover("PETROCORE-3", "");

        assertEquals(List.of("capture", "SOFT", "is_testable"), target.calls);
        assertEquals(Rung.CAPTURE, outcome.attempts().get(0).rung());
        assertEquals(List.of("logs: 3 lines"), outcome.bundle().collected());
        assertTrue(outcome.bundle().complete());
    }

    @Test
    void aRungThatRaisesDoesNotStopTheLadder() {
        FakeTarget target = new FakeTarget(Rung.RESET, Rung.VOID, null);

        RecoveryOutcome outcome = ladder(target, new RunSummary("run-1"), new Capturer()).recover("PETROCORE-4", "");

        RungAttempt voidAttempt = outcome.attempts().stream().filter(a -> a.rung() == Rung.VOID).findFirst().orElseThrow();
        assertFalse(voidAttempt.performed());
        assertTrue(voidAttempt.detail().contains("raised IllegalStateException"), voidAttempt.detail());
        assertEquals(Rung.RESET, outcome.rung());
        assertTrue(outcome.recovered());
    }

    @Test
    void aRungThatOverrunsItsBudgetIsRecordedAndTheLadderEscalates() throws InterruptedException {
        CountDownLatch never = new CountDownLatch(1);
        try {
            FakeTarget target = new FakeTarget(Rung.VOID, null, never);

            RecoveryOutcome outcome = ladder(target, new RunSummary("run-1"), new Capturer())
                    .recover("PETROCORE-5", "");

            RungAttempt soft = outcome.attempts().stream().filter(a -> a.rung() == Rung.SOFT).findFirst().orElseThrow();
            assertFalse(soft.performed(), "an overrun is not a performed rung");
            assertTrue(soft.detail().contains("overran its 1s budget"), soft.detail());
            assertEquals(Rung.VOID, outcome.rung());
        } finally {
            never.countDown();
        }
    }

    @Test
    void rungBudgetsComeFromTheRigsDefaultWait() {
        Map<Rung, Duration> budgets = RecoveryLadder.rungBudgets(new Timeouts(Duration.ofSeconds(15), null));

        assertEquals(Duration.ofSeconds(15), budgets.get(Rung.SOFT));
        assertEquals(Duration.ofSeconds(15), budgets.get(Rung.VOID));
        assertEquals(Duration.ofSeconds(30), budgets.get(Rung.RESET));
        assertEquals(Duration.ofSeconds(760), budgets.get(Rung.PREFLIGHT));
        assertEquals(Duration.ofSeconds(600), budgets.get(Rung.REPROVISION));
    }

    @Test
    void preflightBudgetGrowsPastTheFixedCeilingOnASlowRig() {
        Map<Rung, Duration> budgets = RecoveryLadder.rungBudgets(new Timeouts(Duration.ofSeconds(100), null));

        assertEquals(Duration.ofSeconds(800), budgets.get(Rung.PREFLIGHT));
        assertEquals(Duration.ofSeconds(2000), budgets.get(Rung.REPROVISION));
    }

    @Test
    void unavailableRungsAreRecordedAsNotPerformedNotAsSuccess() {
        LiveRecoveryTarget live = new LiveRecoveryTarget("lab", null, null, null, null, null);

        DeviceUnavailable reset = org.junit.jupiter.api.Assertions.assertThrows(DeviceUnavailable.class, live::resetState);
        DeviceUnavailable provision = org.junit.jupiter.api.Assertions.assertThrows(DeviceUnavailable.class, live::reprovision);

        assertTrue(reset.getMessage().contains("simhub"), reset.getMessage());
        assertTrue(provision.getMessage().contains("no provision is wired"), provision.getMessage());
    }
}
