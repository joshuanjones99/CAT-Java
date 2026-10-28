package com.verifone.cat.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.posshim.FakeShim;
import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.preflight.RigContext;
import com.verifone.cat.preflight.ShimPosControl;
import com.verifone.cat.preflight.ShimPosStatus;

/**
 * The rungs with the register's DOM behind them ({@link PosScreen}), as the Python's {@code _live_soft_cancel},
 * {@code _live_void} and {@code _testable}: a fake screen, and {@link FakeShim} on loopback for the shim half. Nothing
 * reaches a register. Evidence about this repository only.
 */
class LiveRecoveryTargetScreenTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private FakeShim shim;
    private PosShimClient client;
    private FakeScreen screen;

    /** A screen whose answers the test sets, recording what was asked of it. */
    static final class FakeScreen implements PosScreen {
        final List<String> calls = new ArrayList<>();
        boolean idle;
        boolean atLogin;
        RuntimeException voidFails;

        @Override
        public boolean idle() {
            calls.add("idle");
            return idle;
        }

        @Override
        public boolean atLogin() {
            calls.add("atLogin");
            return atLogin;
        }

        @Override
        public boolean signedOn() {
            calls.add("signedOn");
            return true;
        }

        @Override
        public void softCancel() {
            calls.add("softCancel");
            idle = true;
        }

        @Override
        public String voidOpenSale() {
            calls.add("voidOpenSale");
            if (voidFails != null) {
                throw voidFails;
            }
            idle = true;
            return "voided";
        }
    }

    @BeforeEach
    void setUp() {
        shim = new FakeShim();
        client = new PosShimClient(shim.host(), shim.port(), null, TIMEOUT);
        screen = new FakeScreen();
    }

    @AfterEach
    void tearDown() {
        shim.close();
    }

    private LiveRecoveryTarget target(List<String> drained) {
        return new LiveRecoveryTarget("lab", null, new ShimPosStatus(client), new ShimPosControl(client), screen,
                new RunSummary("r"), drained::add);
    }

    private long shimCalls(String path) {
        return shim.seen.stream().filter(s -> s.pathAndQuery().startsWith(path)).count();
    }

    @Test
    void testabilityIsTheDomsIdleScreenElseItsLoginScreenNotTheShims() {
        // The shim says idle (it was ~16 hours stale on the lab rig): the DOM decides.
        shim.on("GET", "/pos/screen", 200,
                "{\"client\":\"topaz101\",\"state_present\":true,\"idle\":true,\"transaction_active\":false}");
        LiveRecoveryTarget live = target(new ArrayList<>());

        assertFalse(live.isTestable());
        assertEquals(List.of("idle", "atLogin"), screen.calls);

        screen.atLogin = true;
        assertTrue(live.isTestable(), "a register at its login screen is testable (live_at_login)");

        screen.calls.clear();
        screen.idle = true;
        assertTrue(live.isTestable());
        assertEquals(List.of("idle"), screen.calls, "the login screen is asked only when the first says no");
        assertEquals(0, shimCalls("/pos/screen"));
    }

    @Test
    void softCancelAsksTheShimThenTheScreenAndTheLadderEndsThere() {
        shim.on("POST", "/pos/control/cancel", 200, "{\"outcome\":\"cleared\",\"success\":true}");
        RunSummary summary = new RunSummary("run-1");

        RecoveryOutcome outcome = new RecoveryLadder(target(new ArrayList<>()), new Capturer(), "run-1", summary,
                Timeouts.defaults()).recover("PETROCORE-36853", "call raised PatsStepError: x");

        assertTrue(outcome.recovered());
        assertEquals(Rung.SOFT, outcome.rung());
        assertEquals(1, shimCalls("/pos/control/cancel"));
        assertEquals(List.of("softCancel", "idle"), screen.calls);
    }

    @Test
    void aShimThatRaisesDoesNotStopTheDomHalfOfSoft() {
        shim.on("POST", "/pos/control/cancel", 500, "{\"error\":\"down\"}");
        LiveRecoveryTarget live = target(new ArrayList<>());

        assertTrue(live.softCancel());
        assertEquals(List.of("softCancel"), screen.calls);
    }

    @Test
    void voidClearsTheSaleThroughTheScreenAndLeavesTheShimAlone() {
        LiveRecoveryTarget live = target(new ArrayList<>());

        assertTrue(live.voidOpenTicket());
        assertEquals(List.of("voidOpenSale"), screen.calls);
        assertEquals(0, shimCalls("/pos/control/ticket/void"));
    }

    @Test
    void voidFallsBackToTheShimWhenTheScreenRaises() {
        screen.voidFails = new DeviceError("CDP attach refused");
        shim.on("POST", "/pos/control/ticket/void", 200, "{\"outcome\":\"voided\",\"success\":true}");
        LiveRecoveryTarget live = target(new ArrayList<>());

        assertTrue(live.voidOpenTicket());
        assertEquals(1, shimCalls("/pos/control/ticket/void"));
    }

    @Test
    void theRigsTargetReadsPreflightsScreenFromTheDomAndLogsItsDrain() {
        RigContext context = new RigContext("lab", new ShimPosStatus(client), new ShimPosControl(client), null, null,
                null, null);
        List<String> drained = new ArrayList<>();

        LiveRecoveryTarget live = RigRecovery.forRig("lab", context, screen, new RunSummary("r"), drained::add);

        screen.idle = true;
        assertTrue(context.screenIdle().getAsBoolean(), "preflight's pos_idle reads live_pos_idle");
        assertTrue(context.signedOn().getAsBoolean(), "preflight's cashier_signed_on reads live_signed_on");
        assertEquals(List.of("idle", "signedOn"), screen.calls);
        assertEquals("lab", live.rigName());
        live.drain("rig lab is unhealthy");
        assertEquals(List.of("rig lab is unhealthy"), drained);
    }
}
