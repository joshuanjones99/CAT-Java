package com.verifone.cat.devices.forecourt;

import static com.verifone.cat.devices.forecourt.Fakes.FAST;
import static com.verifone.cat.devices.forecourt.Fakes.HOST;
import static com.verifone.cat.devices.forecourt.Fakes.fixture;
import static com.verifone.cat.devices.forecourt.Fakes.push;
import static com.verifone.cat.devices.forecourt.Fakes.status;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.devices.DeviceUnreachable;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The pump client against loopback fakes: the bytes it writes, the states it waits for, and the clear. Ported from the
 * Python {@code test_devices_petro_forecourt.py}'s pump section.
 */
class PetroPumpsTest {

    private Fakes.Simulator fuelSim;
    private PetroPumps pumps;
    private int listenPort;

    @BeforeEach
    void start() {
        fuelSim = new Fakes.Simulator();
        listenPort = Fakes.freePort();
        pumps = PetroPumps.started(HOST, listenPort, fuelSim.port(), Fakes.freePort(), FAST);
    }

    @AfterEach
    void stop() {
        pumps.close();
        fuelSim.close();
    }

    @Test
    void startingLogsInAndTheReportedStatesAreKept() {
        assertArrayEquals(fixture("fuel_login.bin"), fuelSim.waitFor(1).get(0));
        push(listenPort, fixture("fuel_status_info.bin"), false);
        pumps.waitUntilIdle(1, null);
        assertEquals("BUSYON", pumps.state(5));
    }

    @Test
    void aFillWaitsForAnIdlePumpThenLiftsTheHandle() {
        push(listenPort, status(1, 0, "IDLEOFF", "ACK"), false);
        pumps.fillAmount(1, new BigDecimal("24.15"), 1, false, false);
        assertArrayEquals(fixture("fuel_handle_up.bin"), fuelSim.waitFor(2).get(1));
    }

    @Test
    void aFillOnABusyPumpTimesOutNamingTheState() {
        push(listenPort, status(1, 0, "BUSYON", "ACK"), false);
        var expired = assertThrows(ForecourtWaitExpired.class,
                () -> pumps.fillAmount(1, new BigDecimal("24.15"), 1, false, false));
        assertTrue(expired.getMessage().contains("BUSYON"), expired.getMessage());
    }

    @Test
    void aPrepayFillNeedsThePumpAuthorised() {
        push(listenPort, status(1, 0, "IDLEOFF", "ACK"), false);
        assertThrows(ForecourtWaitExpired.class,
                () -> pumps.fillAmount(1, new BigDecimal("5.00"), 1, true, false));
    }

    @Test
    void aPresetFillLiftsOnlyOnceTheCommanderAuthorisesIt() {
        // PETROCORE-36945 lifted at IDLEOFF, mid-way through the Commander's meter read (run daf8f40e265c).
        push(listenPort, status(1, 0, "IDLEOFF", "ACK"), false);
        Fakes.after(Duration.ofMillis(300), () -> push(listenPort, status(1, 0, "BUSYOFF", "ACK"), false));
        pumps.fillAmount(1, new BigDecimal("24.15"), 1, false, true);
        assertEquals("BUSYOFF", pumps.state(1));
        assertArrayEquals(fixture("fuel_handle_up.bin"), fuelSim.waitFor(2).get(1));
    }

    @Test
    void aPresetNeverAuthorisedIsLiftedAnyway() {
        pumps.setPresetAuthoriseWait(Duration.ofMillis(200));
        push(listenPort, status(1, 0, "IDLEOFF", "ACK"), false);
        pumps.fillAmount(1, new BigDecimal("24.15"), 1, false, true);
        assertArrayEquals(fixture("fuel_handle_up.bin"), fuelSim.waitFor(2).get(1));
    }

    @Test
    void theHangUpCarriesTheLastFillAmount() {
        push(listenPort, status(5, 0, "IDLEOFF", "ACK"), false);
        pumps.fillAmount(5, new BigDecimal("14.50"), 1, false, false);
        push(listenPort, status(5, 1, "BUSYON", "ACK"), false);
        pumps.handleDown(5, false);
        assertArrayEquals(fixture("fuel_handle_down.bin"), fuelSim.waitFor(3).get(2));
    }

    @Test
    void idleMeansReportedIdleAfterTheLastAction() {
        push(listenPort, status(1, 0, "IDLEOFF", "ACK"), false);
        pumps.fillAmount(1, new BigDecimal("3.00"), 1, false, false);
        assertThrows(ForecourtWaitExpired.class, () -> pumps.waitUntilIdle(1, Duration.ofMillis(200)));
        push(listenPort, status(1, 0, "IDLEOFF", "ACK"), false);
        pumps.waitUntilIdle(1, null);
    }

    @Test
    void aPrepayHangUpReturnsOnceThePumpIsIdleAgain() {
        push(listenPort, status(1, 0, "BUSYON", "ACK"), false);
        Fakes.after(Duration.ofMillis(200), () -> push(listenPort, status(1, 0, "IDLEOFF", "ACK"), false));
        pumps.handleDown(1, true);
        assertEquals("IDLEOFF", pumps.state(1));
    }

    @Test
    void aHungUpFillIsPostedAtTheIdleOffAfterItsEot() {
        // PETROCORE-36907 tapped pump 8's tile mid-EOT and opened its detail view instead.
        pumps.waitForPostedSale(8, null); // nothing hung up: no wait
        reported(8, "BUSYON");
        pumps.handleDown(8, false);
        reported(8, "EOT");
        var expired = assertThrows(ForecourtWaitExpired.class,
                () -> pumps.waitForPostedSale(8, Duration.ofMillis(200)));
        assertTrue(expired.getMessage().contains("EOT"), expired.getMessage());
        Fakes.after(Duration.ofMillis(200), () -> push(listenPort, status(8, 0, "IDLEOFF", "ACK"), false));
        pumps.waitForPostedSale(8, null);
        reported(8, "IDLEON");
        pumps.waitForPostedSale(8, Duration.ofMillis(200)); // posted already: a later lift is not waited on
    }

    @Test
    void aLiftAfterTheHangUpIsNotWaitedOn() {
        reported(2, "BUSYON");
        pumps.handleDown(2, false);
        reported(2, "IDLEOFF");
        pumps.fillAmount(2, new BigDecimal("5.00"), 1, false, false);
        reported(2, "IDLEON");
        pumps.waitForPostedSale(2, Duration.ofMillis(200)); // PETROCORE-36907's approve tap on the lifted pump
    }

    @Test
    void aBareLiftCarriesNoPresetAndCountsAsLiftedUntilHungUp() {
        reported(3, "IDLEOFF");
        pumps.handleUp(3, 1, false);
        assertArrayEquals(ForecourtWire.encodeHandleUp(3, BigDecimal.ZERO, 1), fuelSim.waitFor(2).get(1));
        assertTrue(pumps.lifted(3));
        reported(3, "BUSYON");
        pumps.handleDown(3, false);
        assertArrayEquals(ForecourtWire.encodeHandleDown(3, null), fuelSim.waitFor(3).get(2));
        assertFalse(pumps.lifted(3));
    }

    @Test
    void aLiftTakesThePresetAFillLeftAndForgetsIt() {
        reported(5, "IDLEOFF");
        pumps.fillAmount(5, new BigDecimal("14.50"), 1, false, false);
        reported(5, "BUSYOFF");
        pumps.handleUp(5, 2, false);
        assertArrayEquals(ForecourtWire.encodeHandleUp(5, new BigDecimal("14.50"), 2), fuelSim.waitFor(3).get(2));
        reported(5, "BUSYON");
        pumps.handleDown(5, false);
        assertArrayEquals(ForecourtWire.encodeHandleDown(5, null), fuelSim.waitFor(4).get(3));
    }

    @Test
    void aPrepayLiftNeedsThePumpAuthorised() {
        reported(1, "IDLEOFF");
        var expired = assertThrows(ForecourtWaitExpired.class, () -> pumps.handleUp(1, 1, true));
        assertTrue(expired.getMessage().contains("BUSYOFF"), expired.getMessage());
    }

    @Test
    void clearLeavesAnIdlePumpAlone() {
        reported(1, "IDLEOFF");
        assertNull(pumps.clear(1));
        assertEquals(1, fuelSim.waitFor(1).size()); // the login only
    }

    @Test
    void clearHangsUpALiftedPumpNobodyAuthorised() {
        // HANDOFF.md 1.61: pumps 1 and 5 left in IDLEON by a failed case.
        reported(1, "IDLEON");
        answer(2, "IDLEOFF");
        assertEquals("IDLEON", pumps.clear(1));
        assertArrayEquals(ForecourtWire.encodeHandleDown(1, null), fuelSim.waitFor(2).get(1));
    }

    @Test
    void clearFuelsAnUnfuelledPrepayToItsLimitThenHangsUp() {
        // HANDOFF.md 1.62: 61381 failed after its prepay and before its fill.
        reported(1, "BUSYOFF");
        answer(2, "BUSYON");
        answer(3, "IDLEOFF");
        assertEquals("BUSYOFF", pumps.clear(1));
        var sent = fuelSim.waitFor(3);
        assertArrayEquals(ForecourtWire.encodeHandleUp(1, PetroPumps.CLEAR_FILL, 1), sent.get(1));
        assertArrayEquals(ForecourtWire.encodeHandleDown(1, PetroPumps.CLEAR_FILL), sent.get(2));
    }

    @Test
    void aPumpOnlyWaitedOnCountsAsTouched() {
        assertThrows(ForecourtWaitExpired.class, () -> pumps.waitUntilIdle(3, Duration.ofMillis(200)));
        assertEquals(Set.of(3), pumps.touched());
    }

    @Test
    void aHangUpWaitsForAuthorisationNotJustTheLift() {
        // In IDLEON the simulator would cancel the fill rather than sell it.
        push(listenPort, status(1, 0, "IDLEOFF", "ACK"), false);
        pumps.fillAmount(1, new BigDecimal("24.15"), 1, false, false);
        push(listenPort, status(1, 1, "IDLEON", "ACK"), false);
        var expired = assertThrows(ForecourtWaitExpired.class, () -> pumps.handleDown(1, false));
        assertTrue(expired.getMessage().contains("IDLEON"), expired.getMessage());
        assertEquals(2, fuelSim.waitFor(2).size()); // login and the lift; no hang-up
    }

    @Test
    void theSuitesUiPortIsHeardToo() {
        int ui = Fakes.freePort();
        try (PetroPumps withUi = PetroPumps.started(HOST, Fakes.freePort(), fuelSim.port(), ui, FAST)) {
            push(ui, status(1, 0, "IDLEOFF", "ACK"), false);
            assertNull(withUi.state(1)); // thrown away, not read as PATS's copy
        }
    }

    @Test
    void aUiPortHeldElsewhereDoesNotStopThePumps() {
        try (PortSink taken = new PortSink(Fakes.freePort())) {
            assertThrows(DeviceUnreachable.class, () -> new PortSink(taken.port()));
            PetroPumps.started(HOST, Fakes.freePort(), fuelSim.port(), taken.port(), FAST).close();
        }
    }

    @Test
    void aStreamTheDecoderChokesOnDoesNotStopTheListener() {
        byte[] malformed = status(1, 0, "BUSYON", "ACK");
        malformed[4] = 0x71; // a back-reference before any object: dropped, not fatal
        push(listenPort, malformed, false);
        reported(1, "IDLEOFF");
    }

    @Test
    void aFailedPumpLoginGivesBothPortsBack() {
        int listen = Fakes.freePort();
        int ui = Fakes.freePort();
        assertThrows(DeviceUnreachable.class,
                () -> PetroPumps.started(HOST, listen, Fakes.freePort(), ui, FAST));
        new PortSink(listen).close();
        new PortSink(ui).close();
    }

    /** Reports {@code state} for pump {@code pump}, then waits until the listener has recorded it. */
    private void reported(int pump, String state) {
        push(listenPort, status(pump, 0, state, "ACK"), false);
        Fakes.until(() -> state.equals(pumps.state(pump)), Duration.ofSeconds(5));
    }

    /** In the background: after the simulator's {@code count}th request, reports {@code state} for pump 1. */
    private void answer(int count, String state) {
        Thread thread = new Thread(() -> {
            fuelSim.waitFor(count);
            push(listenPort, status(1, 0, state, "ACK"), false);
        }, "fake-suite-answer");
        thread.setDaemon(true);
        thread.start();
    }
}
