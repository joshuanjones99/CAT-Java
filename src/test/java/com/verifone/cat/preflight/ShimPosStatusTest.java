package com.verifone.cat.preflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.verifone.cat.posshim.FakeShim;
import com.verifone.cat.posshim.PosShimClient;

/**
 * The POS checks over {@link ShimPosStatus} against {@link FakeShim}: the screen's idle and
 * transaction bits drive pos_idle, and a shim that has pushed nothing is ERROR, never a pass.
 * Evidence about this repository, not about a register; the canned replies are the lab shim's.
 */
class ShimPosStatusTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private FakeShim shim;
    private PosShimClient client;

    @BeforeEach
    void start() {
        shim = new FakeShim();
        client = new PosShimClient(shim.host(), shim.port(), null, TIMEOUT);
    }

    @AfterEach
    void stop() {
        shim.close();
    }

    private RigContext rig() {
        return Fixtures.rig(new ShimPosStatus(client),
                Surfaces.unavailable(Surfaces.PosControl.class, "POS control", "not under test"));
    }

    private Result verifyOne(Check check) {
        return PreflightRunner.verifyOne(check, rig());
    }

    @Test
    void anIdleScreenPassesPosIdle() {
        shim.on("GET", "/pos/screen", 200, Fixtures.resource("screen-idle.json"));
        Result result = verifyOne(new PosChecks.PosIdle());

        assertEquals(Outcome.PASS, result.outcome(), result.toString());
    }

    @Test
    void anActiveTransactionFailsPosIdle() {
        shim.onFixture("GET", "/pos/screen", "screen.json");
        Result result = verifyOne(new PosChecks.PosIdle());

        assertEquals(Outcome.FAIL, result.outcome(), result.toString());
        assertEquals("a transaction is still active", result.detail());
    }

    @Test
    void aShimWithNoPushedStateIsErrorEvenWhenItSaysIdle() {
        // screen-no-state.json carries idle:true and state_present:false: the POS is not talking to the shim.
        shim.onFixture("GET", "/pos/screen", "screen-no-state.json");
        Result result = verifyOne(new PosChecks.PosIdle());

        assertEquals(Outcome.ERROR, result.outcome(), result.toString());
        assertTrue(result.detail().startsWith("probe raised DeviceUnreachable"), result.detail());
    }

    @Test
    void thePrinterPortIsErrorNotFailBecauseTheShimDoesNotCarryIt() {
        shim.on("GET", "/pos/screen", 200, Fixtures.resource("screen-idle.json"));
        Result result = verifyOne(new PosChecks.PrinterConnected());

        assertEquals(Outcome.ERROR, result.outcome(), result.toString());
        assertTrue(result.detail().startsWith("probe raised DeviceUnavailable"), result.detail());
    }

    @Test
    void theParkedListIsErrorNotAPassOnAShimThatDoesNotCarryIt() {
        shim.on("GET", "/pos/screen", 200, Fixtures.resource("screen-idle.json"));
        Result result = verifyOne(new PosChecks.NoParkedTransaction());

        assertEquals(Outcome.ERROR, result.outcome(), result.toString());
        assertTrue(result.detail().startsWith("probe raised DeviceUnavailable"), result.detail());
    }

    @Test
    void theUnreadablePrinterIsNotRepairedAndNotUnhealthy() {
        shim.on("GET", "/pos/screen", 200, Fixtures.resource("screen-idle.json"));
        PreflightReport report = PreflightRunner.run(rig(),
                List.of(new PosChecks.PrinterConnected()),
                PreflightRunner.RunOptions.defaults());

        assertNull(report.records().get(0).repair());
        assertTrue(report.unhealthyChecks().isEmpty());
        assertTrue(report.testable());
    }
}
