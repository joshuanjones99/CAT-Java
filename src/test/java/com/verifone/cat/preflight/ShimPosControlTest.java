package com.verifone.cat.preflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.posshim.FakeShim;
import com.verifone.cat.posshim.PosShimClient;

/**
 * The POS repairs over the shim: {@code pos_idle}'s cancel-then-void, and the control routes that have
 * no source, against {@link FakeShim} on loopback. No key reaches a register. Evidence about this
 * repository only.
 */
class ShimPosControlTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final String BUSY_SCREEN =
            "{\"client\":\"topaz101\",\"state_present\":true,\"idle\":false,\"transaction_active\":true}";

    private FakeShim shim;
    private RigContext rig;

    @BeforeEach
    void setUp() {
        shim = new FakeShim();
        PosShimClient client = new PosShimClient(shim.host(), shim.port(), null, TIMEOUT);
        rig = new RigContext("lab", new ShimPosStatus(client), new ShimPosControl(client), null, null, null, null);
        shim.on("GET", "/pos/screen", 200, BUSY_SCREEN);
    }

    @AfterEach
    void tearDown() {
        shim.close();
    }

    @Test
    void posIdleRepairCancelsTheOverlayThroughTheShim() {
        shim.on("POST", "/pos/control/cancel", 200, "{\"outcome\":\"cleared\",\"success\":true}");

        Result repair = new PosChecks.PosIdle().repair(rig);

        assertEquals(Outcome.REPAIRED, repair.outcome());
        assertEquals("cancel/escape ladder returned POS to idle", repair.detail());
        assertEquals(1, shim.seen.stream().filter(s -> s.pathAndQuery().equals("/pos/control/cancel")).count());
        assertFalse(shim.seen.stream().anyMatch(s -> s.pathAndQuery().equals("/pos/control/ticket/void")),
                "the void must not run when the cancel ladder worked");
    }

    @Test
    void posIdleRepairVoidsTheOpenTicketWhenTheCancelLadderFails() {
        shim.on("POST", "/pos/control/cancel", 200, "{\"outcome\":\"stuck\",\"success\":false}");
        shim.on("POST", "/pos/control/ticket/void", 200, "{\"outcome\":\"voided\",\"success\":true}");

        Result repair = new PosChecks.PosIdle().repair(rig);

        assertEquals(Outcome.REPAIRED, repair.outcome());
        assertEquals("voided the open ticket", repair.detail());
    }

    @Test
    void posIdleRepairIsAFailureWhenBothRoutesFail() {
        shim.on("POST", "/pos/control/cancel", 200, "{\"outcome\":\"stuck\",\"success\":false}");
        shim.on("POST", "/pos/control/ticket/void", 200, "{\"outcome\":\"open\",\"success\":false}");

        Result repair = new PosChecks.PosIdle().repair(rig);

        assertEquals(Outcome.FAIL, repair.outcome());
        assertTrue(repair.detail().contains("both failed"), repair.detail());
    }

    @Test
    void routesWithNoSourceAreUnavailableNotFailed() {
        shim.on("POST", "/pos/control/printer/reopen", 501, "{\"error\":\"unsupported\",\"detail\":\"no printer source\"}");

        assertThrows(DeviceUnavailable.class, () -> rig.posControl().reopenPrinterPort());
    }

    @Test
    void verifyNeverReachesTheControlRoutes() {
        // verify() on a read-only rig: a probe that asked for a control surface would be refused.
        RigContext frozen = rig.readOnly();

        Result verify = new PosChecks.PosIdle().verify(frozen);

        assertEquals(Outcome.FAIL, verify.outcome());
        assertTrue(shim.seen.stream().noneMatch(s -> s.method().equals("POST")),
                "verify must send no POST to the register");
    }
}
