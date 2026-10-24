package com.verifone.cat.preflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.devices.DeviceUnreachable;

/**
 * DESIGN.md 6.2's state machine with fake checks. Ported from the Python CAT's
 * {@code tests/unit/test_preflight_orchestration.py} and {@code test_preflight_testable.py}, minus
 * the cases that need the recovery run summary.
 */
class RunnerTest {

    private static final Duration BUDGET = Duration.ofSeconds(5);

    /** A check whose verify() answers from a script, in order, and whose repair() is recorded. */
    static class ScriptedCheck extends BaseCheck {
        final List<Boolean> verdicts;
        final boolean repairOk;
        final List<String> log;
        final AtomicInteger verifyCalls = new AtomicInteger();
        final AtomicInteger repairCalls = new AtomicInteger();

        ScriptedCheck(String name, List<Boolean> verdicts, boolean repairOk, List<String> log) {
            super(name, BUDGET);
            this.verdicts = verdicts;
            this.repairOk = repairOk;
            this.log = log;
        }

        @Override
        public Result verify(RigContext rig) {
            int n = verifyCalls.incrementAndGet();
            boolean verdict = verdicts.get(Math.min(n - 1, verdicts.size() - 1));
            log.add("verify:" + name() + ":" + (verdict ? "pass" : "fail"));
            return verdict ? Result.passed(name()) : Result.failed(name(), "scripted failure");
        }

        @Override
        public Result repair(RigContext rig) {
            repairCalls.incrementAndGet();
            log.add("repair:" + name());
            return repairOk ? new Result(name(), Outcome.REPAIRED, "scripted repair")
                    : Result.failed(name(), "scripted repair failure");
        }
    }

    static ScriptedCheck scripted(String name, Boolean... verdicts) {
        return new ScriptedCheck(name, List.of(verdicts), true, new ArrayList<>());
    }

    static PreflightReport run(RigContext rig, Check... checks) {
        return PreflightRunner.run(rig, List.of(checks), PreflightRunner.RunOptions.defaults());
    }

    static PreflightRunner.RunOptions noRepair() {
        return new PreflightRunner.RunOptions(false, BUDGET, false);
    }

    @Test
    void allPassingMeansHealthyAndNoRepairs() {
        ScriptedCheck a = scripted("a", true);
        ScriptedCheck b = scripted("b", true);
        PreflightReport report = run(Fixtures.rig(new FakePos()), a, b);

        assertTrue(report.healthy());
        assertTrue(report.unhealthyChecks().isEmpty());
        assertEquals(1, a.verifyCalls.get());
        assertEquals(0, a.repairCalls.get());
        assertTrue(report.repairs().isEmpty());
    }

    @Test
    void verifyRunsOnEveryCheckBeforeAnyRepair() {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Check a = new ScriptedCheck("a", List.of(false, true), true, log);
        Check b = new ScriptedCheck("b", List.of(false, true), true, log);
        Check c = new ScriptedCheck("c", List.of(true), true, log);
        run(Fixtures.rig(new FakePos()), a, b, c);

        int firstRepair = -1;
        for (int i = 0; i < log.size(); i++) {
            if (log.get(i).startsWith("repair:")) {
                firstRepair = i;
                break;
            }
        }
        assertTrue(firstRepair > 0, log.toString());
        long verifiesBefore = log.subList(0, firstRepair).stream().filter(e -> e.startsWith("verify:")).count();
        assertEquals(3, verifiesBefore, log.toString());
    }

    @Test
    void failedCheckIsRepairedThenReVerified() {
        ScriptedCheck check = scripted("printer_connected", false, true);
        PreflightReport report = run(Fixtures.rig(new FakePos()), check);

        CheckRecord record = report.records().get(0);
        assertEquals(Outcome.FAIL, record.first().outcome());
        assertEquals(Outcome.REPAIRED, record.repair().outcome());
        assertEquals(Outcome.PASS, record.second().outcome());
        assertTrue(record.ok());
        assertTrue(report.healthy());
        assertFalse(record.failedTwice());
        assertEquals(1, report.repairs().size());
    }

    @Test
    void onlyTheRepairedChecksAreReVerified() {
        ScriptedCheck good = scripted("good", true);
        ScriptedCheck bad = scripted("bad", false, true);
        run(Fixtures.rig(new FakePos()), good, bad);

        assertEquals(1, good.verifyCalls.get());
        assertEquals(2, bad.verifyCalls.get());
    }

    @Test
    void failingTwiceMarksTheRigUnhealthy() {
        ScriptedCheck check = new ScriptedCheck("eps_reachable", List.of(false, false), false, new ArrayList<>());
        PreflightReport report = run(Fixtures.rig(new FakePos()), check);

        assertFalse(report.healthy());
        assertEquals(List.of("eps_reachable"), report.unhealthyChecks());
        assertTrue(report.records().get(0).failedTwice());
        assertEquals("fail", report.repairs().get(0).outcome().value());
    }

    @Test
    void aDeclinedRepairCountsAsFailingTwice() {
        Check human = new BaseCheck("build_matches", BUDGET) {
            @Override
            public Result verify(RigContext rig) {
                return Result.failed(name(), "installed build is '056.00', rig expects '057.00'");
            }
        };
        PreflightReport report = run(Fixtures.rig(new FakePos()), human);

        assertEquals(List.of("build_matches"), report.unhealthyChecks());
        assertEquals(Outcome.UNREPAIRABLE, report.repairs().get(0).outcome());
    }

    @Test
    void repairIsSkippedEntirelyWhenDisabled() {
        ScriptedCheck check = scripted("pos_idle", false);
        PreflightReport report = PreflightRunner.run(Fixtures.rig(new FakePos()), List.of(check), noRepair());

        assertEquals(0, check.repairCalls.get());
        assertNull(report.records().get(0).second());
        assertFalse(report.healthy());
    }

    @Test
    void verifyThatTouchesAControlSurfaceIsAnErrorNotAFailure() {
        Check naughty = new BaseCheck("naughty", BUDGET) {
            @Override
            public Result verify(RigContext rig) {
                rig.posControl().cancelLadder();
                return Result.passed(name());
            }
        };
        PreflightReport report = PreflightRunner.run(Fixtures.rig(new FakePos()), List.of(naughty), noRepair());

        Result result = report.records().get(0).first();
        assertEquals(Outcome.ERROR, result.outcome());
        assertTrue(result.detail().contains("§6.2"), result.detail());
    }

    @Test
    void readOnlyViewPermitsStatusAndBlocksControl() {
        RigContext rig = Fixtures.rig(new FakePos());
        RigContext frozen = rig.readOnly();

        assertTrue(frozen.isReadOnly());
        assertEquals("testrig", frozen.name());
        assertEquals("BP-UAT", frozen.expectations().configFolder());
        assertThrows(MutationInVerifyError.class, frozen::posControl);
        assertThrows(MutationInVerifyError.class, frozen::configControl);
        assertThrows(MutationInVerifyError.class, () -> frozen.setCashDrawers(null));
        assertFalse(rig.isReadOnly());
    }

    @Test
    void aHangingRepairIsCutOffAtItsBudget() throws InterruptedException {
        CountDownLatch released = new CountDownLatch(1);
        try {
            Check hangs = new BaseCheck("hangs", Duration.ofMillis(50)) {
                @Override
                public Result verify(RigContext rig) {
                    return Result.failed(name(), "down");
                }

                @Override
                public Result repair(RigContext rig) {
                    try {
                        released.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return Result.passed(name());
                }
            };
            PreflightReport report = run(Fixtures.rig(new FakePos()), hangs);

            assertEquals(Outcome.TIMEOUT, report.records().get(0).repair().outcome());
            assertFalse(report.healthy());
        } finally {
            released.countDown();
        }
    }

    @Test
    void aRaisingProbeDoesNotStopPreflightAndADeviceErrorIsAnErrorNotAFailure() {
        Check unreachable = new BaseCheck("shim_down", BUDGET) {
            @Override
            public Result verify(RigContext rig) {
                throw new DeviceUnreachable("POS shim", "connection refused");
            }
        };
        Check shimReply = new BaseCheck("shim_503", BUDGET) {
            @Override
            public Result verify(RigContext rig) {
                throw new IllegalStateException("shim returned 503");
            }
        };
        ScriptedCheck other = scripted("fine", true);
        PreflightReport report = PreflightRunner.run(Fixtures.rig(new FakePos()),
                List.of(unreachable, shimReply, other), noRepair());

        CheckRecord first = report.records().get(0);
        assertEquals(Outcome.ERROR, first.first().outcome());
        assertTrue(first.first().detail().startsWith("probe raised DeviceUnreachable"), first.first().detail());
        assertEquals(Outcome.ERROR, report.records().get(1).first().outcome());
        assertTrue(report.records().get(1).first().detail().contains("shim returned 503"));
        assertEquals(1, other.verifyCalls.get(), "one broken probe must not block the others");
    }

    @Test
    void inapplicableChecksAreSkippedNotSilentlyPassed() {
        Check indoor = new BaseCheck("pumps_idle", BUDGET) {
            @Override
            public boolean applicable(RigContext rig) {
                return false;
            }

            @Override
            public Result verify(RigContext rig) {
                throw new AssertionError("skipped checks must not run");
            }
        };
        PreflightReport report = run(Fixtures.rig(new FakePos()), indoor);

        assertEquals(List.of("pumps_idle"), report.skipped());
        assertTrue(report.records().isEmpty());
        assertTrue(report.healthy());
    }

    @Test
    void midRunHoldsBackAnOperatorOnlyCheckAndReportsWhy() {
        Check summaryOnly = new BaseCheck("no_parked_transaction", BUDGET) {
            @Override
            public String operatorOnly(RigContext rig) {
                return "a case may park a ticket for the next one";
            }

            @Override
            public Result verify(RigContext rig) {
                throw new AssertionError("held-back checks must not run");
            }
        };
        PreflightReport report = PreflightRunner.run(Fixtures.rig(new FakePos()), List.of(summaryOnly),
                new PreflightRunner.RunOptions(true, BUDGET, true));

        assertTrue(report.records().isEmpty());
        assertEquals("a case may park a ticket for the next one", report.heldBack().get("no_parked_transaction"));
        assertTrue(report.testable());
    }

    @Test
    void aProbeWithNoSourceIsErrorButNotUntestable() {
        Check noSource = new BaseCheck("drawer_closed", BUDGET) {
            @Override
            public Result verify(RigContext rig) {
                throw new DeviceUnavailable("POS shim drawer", "not on the pushed panel surface");
            }
        };
        ScriptedCheck idle = scripted("pos_idle", true);
        PreflightReport report = run(Fixtures.rig(new FakePos()), noSource, idle);

        assertEquals(Outcome.ERROR, report.records().get(0).finalResult().outcome());
        assertFalse(report.healthy());
        assertTrue(report.testable());
    }

    @Test
    void aProbeWithNoSourceIsNotRepaired() {
        List<String> repaired = Collections.synchronizedList(new ArrayList<>());
        Check noSource = new BaseCheck("drawer_closed", BUDGET) {
            @Override
            public Result verify(RigContext rig) {
                throw new DeviceUnavailable("POS shim drawer", "not on the pushed panel surface");
            }

            @Override
            public Result repair(RigContext rig) {
                repaired.add(name());
                return Result.passed(name());
            }
        };
        ScriptedCheck idle = new ScriptedCheck("pos_idle", List.of(false), false, new ArrayList<>()) {
            @Override
            public Result repair(RigContext rig) {
                repaired.add(name());
                return super.repair(rig);
            }
        };
        PreflightReport report = run(Fixtures.rig(new FakePos()), noSource, idle);

        assertEquals(List.of("pos_idle"), repaired);
        assertNull(report.records().get(0).repair());
        assertEquals(List.of("pos_idle"), report.unhealthyChecks());
    }

    @Test
    void aReadableCheckThatFailsIsUntestable() {
        ScriptedCheck idle = scripted("pos_idle", false);
        PreflightReport report = PreflightRunner.run(Fixtures.rig(new FakePos()), List.of(idle), noRepair());
        assertFalse(report.testable());
    }

    @Test
    void anyOtherProbeErrorIsUntestable() {
        Check broken = new BaseCheck("pos_idle", BUDGET) {
            @Override
            public Result verify(RigContext rig) {
                throw new IllegalStateException("shim crashed");
            }
        };
        PreflightReport report = PreflightRunner.run(Fixtures.rig(new FakePos()), List.of(broken), noRepair());
        assertFalse(report.testable());
    }

    @Test
    void theRenderedReportNamesEveryCheckAndTheUnhealthyOnes() {
        ScriptedCheck bad = scripted("pos_idle", false, false);
        ScriptedCheck good = scripted("printer_connected", true);
        PreflightReport report = run(Fixtures.rig(new FakePos()), bad, good);
        String text = report.render();

        assertTrue(text.startsWith("preflight testrig: UNHEALTHY [pos_idle]"), text);
        assertTrue(text.contains("printer_connected"), text);
        assertTrue(text.contains("repair=repaired"), text);
        assertTrue(text.contains("reverify=fail"), text);
    }

    @Test
    void theRegistryRejectsDuplicatesAndUnknownNames() {
        CheckRegistry registry = new CheckRegistry(List.of(scripted("a", true)));
        assertThrows(IllegalArgumentException.class, () -> registry.register(scripted("a", true)));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> registry.get("zzz"));
        assertTrue(unknown.getMessage().contains("known: [a]"), unknown.getMessage());
        assertEquals(1, registry.select(List.of("a")).size());
        assertNotNull(registry.names());
    }

}
