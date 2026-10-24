package com.verifone.cat.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.posshim.FakeShim;
import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.preflight.ShimPosControl;
import com.verifone.cat.preflight.ShimPosStatus;

/**
 * The live rung wiring over {@link FakeShim} on loopback: rung 1 clears the overlay through the same
 * shim route preflight's {@code pos_idle} repair uses, and the rig, not the rung, decides testability.
 * No key reaches a register. Evidence about this repository only.
 */
class LiveRecoveryTargetTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private FakeShim shim;
    private PosShimClient client;

    @BeforeEach
    void setUp() {
        shim = new FakeShim();
        client = new PosShimClient(shim.host(), shim.port(), null, TIMEOUT);
    }

    @AfterEach
    void tearDown() {
        shim.close();
    }

    private LiveRecoveryTarget target(RunSummary summary, List<String> drained) {
        return new LiveRecoveryTarget("lab", null, new ShimPosStatus(client), new ShimPosControl(client),
                summary, drained::add);
    }

    @Test
    void rungOneRecoversWhenTheShimReReadsAnIdleScreen() {
        shim.on("POST", "/pos/control/cancel", 200, "{\"outcome\":\"cleared\",\"success\":true}");
        shim.on("GET", "/pos/screen", 200,
                "{\"client\":\"topaz101\",\"state_present\":true,\"idle\":true,\"transaction_active\":false}");
        RunSummary summary = new RunSummary("run-1");
        List<String> drained = new ArrayList<>();

        RecoveryOutcome outcome = new RecoveryLadder(target(summary, drained), new Capturer(), "run-1", summary,
                Timeouts.defaults()).recover("PETROCORE-9", "");

        assertTrue(outcome.recovered());
        assertEquals(Rung.SOFT, outcome.rung());
        assertEquals(List.of(Rung.CAPTURE, Rung.SOFT), outcome.attempted());
        assertTrue(drained.isEmpty());
        assertEquals(Rung.SOFT, summary.recoveries().get(0).rung());
    }

    @Test
    void rungOneIsNotSuccessWhenTheScreenStillShowsATicket() {
        // The shim cancelled and the rig still has an open ticket: the ladder must not stop at SOFT.
        shim.on("POST", "/pos/control/cancel", 200, "{\"outcome\":\"stuck\",\"success\":false}");
        shim.on("POST", "/pos/control/ticket/void", 200, "{\"outcome\":\"open\",\"success\":false}");
        shim.on("GET", "/pos/screen", 200,
                "{\"client\":\"topaz101\",\"state_present\":true,\"idle\":false,\"transaction_active\":true}");
        LiveRecoveryTarget live = target(new RunSummary("run-1"), new ArrayList<>());

        assertTrue(live.softCancel(), "the rung ran; the rig decides");
        assertFalse(live.isTestable());
        assertFalse(live.voidOpenTicket());
    }

    @Test
    void testabilityIsTheIdleScreenWithNoOpenTicket() {
        shim.on("GET", "/pos/screen", 200,
                "{\"client\":\"topaz101\",\"state_present\":true,\"idle\":true,\"transaction_active\":true}");

        assertFalse(target(new RunSummary("r"), new ArrayList<>()).isTestable());
    }

    @Test
    void rungThreeIsUnavailableNotSuccessful() {
        LiveRecoveryTarget live = target(new RunSummary("r"), new ArrayList<>());

        DeviceUnavailable error = assertThrows(DeviceUnavailable.class, live::resetState);
        assertTrue(error.getMessage().contains("simhub"), error.getMessage());
    }

    @Test
    void rungFiveIsUnavailableNotSuccessful() {
        LiveRecoveryTarget live = target(new RunSummary("r"), new ArrayList<>());

        assertThrows(DeviceUnavailable.class, live::reprovision);
    }

    @Test
    void rungSixHandsTheDrainMessageToTheHook() {
        List<String> drained = new ArrayList<>();
        LiveRecoveryTarget live = target(new RunSummary("r"), drained);

        live.drain("rig lab is unhealthy");

        assertEquals(List.of("rig lab is unhealthy"), drained);
    }
}
