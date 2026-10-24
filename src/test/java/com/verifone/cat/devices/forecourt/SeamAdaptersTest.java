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

import com.verifone.cat.pats.runner.handlers.devices.DcrDevice;
import com.verifone.cat.pats.runner.handlers.devices.FuelPumps;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The two seam adapters the handler families are wired to, over loopback fakes. */
class SeamAdaptersTest {

    @Test
    void fuelPumpsAdapterReadsStatesAndSendsTheFillAsTheClientDoes() {
        try (Fakes.Simulator fuelSim = new Fakes.Simulator();
                PetroPumps pumps = PetroPumps.started(HOST, Fakes.freePort(), fuelSim.port(), null, FAST)) {
            int listen = pumpListenPort(pumps);
            FuelPumps seam = new FuelPumpsAdapter(pumps);
            assertEquals(Optional.empty(), seam.state(1));
            push(listen, status(1, 0, "IDLEOFF", "ACK"), false);
            Fakes.until(() -> seam.state(1).isPresent(), Duration.ofSeconds(5));
            assertEquals(Optional.of("IDLEOFF"), seam.state(1));
            seam.fillAmount(1, "24.15", 1, false, false);
            assertArrayEquals(fixture("fuel_handle_up.bin"), fuelSim.waitFor(2).get(1));
        }
    }

    @Test
    void fuelPumpsAdapterWaitsAreBoundedAndNameTheState() {
        try (Fakes.Simulator fuelSim = new Fakes.Simulator();
                PetroPumps pumps = PetroPumps.started(HOST, Fakes.freePort(), fuelSim.port(), null, FAST)) {
            FuelPumps seam = new FuelPumpsAdapter(pumps);
            var expired = assertThrows(ForecourtWaitExpired.class,
                    () -> seam.waitUntilIdle(4, Duration.ofMillis(200)));
            assertTrue(expired.getMessage().contains("pump 4"), expired.getMessage());
        }
    }

    @Test
    void dcrDeviceAdapterSendsSoftKeysCardsAndKeyPresses() {
        try (Fakes.Simulator dcrSim = new Fakes.Simulator(fixture("dcr_ack.bin"));
                PetroDcr dcr = PetroDcr.started(HOST, 1, Fakes.freePort(), dcrSim.port(), FAST)) {
            DcrDevice seam = new DcrDeviceAdapter(dcr);
            seam.press("L3");
            seam.insertCard("VCMG_LOYALTY_CARD2");
            seam.keyPress("12345");
            var sent = dcrSim.waitFor(4);
            assertArrayEquals(fixture("dcr_key_l3.bin"), sent.get(1));
            assertArrayEquals(fixture("dcr_card.bin"), sent.get(2));
            assertArrayEquals(fixture("dcr_keyboard.bin"), sent.get(3));
        }
    }

    @Test
    void dcrDeviceAdapterRefusesAnUnknownSoftKeyBeforeSending() {
        try (Fakes.Simulator dcrSim = new Fakes.Simulator(fixture("dcr_ack.bin"));
                PetroDcr dcr = PetroDcr.started(HOST, 1, Fakes.freePort(), dcrSim.port(), FAST)) {
            DcrDevice seam = new DcrDeviceAdapter(dcr);
            assertThrows(IllegalArgumentException.class, () -> seam.press("X9"));
            assertEquals(1, dcrSim.waitFor(1).size()); // the login only
        }
    }

    @Test
    void fuelPumpsAdapterSeesLiftsClearsAndTheTouchedSet() {
        try (Fakes.Simulator fuelSim = new Fakes.Simulator();
                PetroPumps pumps = PetroPumps.started(HOST, Fakes.freePort(), fuelSim.port(), null, FAST)) {
            int listen = pumpListenPort(pumps);
            FuelPumps seam = new FuelPumpsAdapter(pumps);
            push(listen, status(1, 0, "IDLEOFF", "ACK"), false);
            Fakes.until(() -> seam.state(1).isPresent(), Duration.ofSeconds(5));
            assertFalse(seam.lifted(1));
            assertNull(seam.clear(1)); // idle: nothing to put back, and nothing sent
            seam.fillAmount(1, "24.15", 1, false, false);
            assertTrue(seam.lifted(1));
            assertEquals(Set.of(1), seam.touched());
            assertTrue(seam.waitForPostedSale(1, Duration.ofMillis(200))); // nothing hung up: nothing to wait for
        }
    }

    @Test
    void fuelPumpsAdapterReportsAnUnpostedSaleAsFalseNotAsAFailure() {
        try (Fakes.Simulator fuelSim = new Fakes.Simulator();
                PetroPumps pumps = PetroPumps.started(HOST, Fakes.freePort(), fuelSim.port(), null, FAST)) {
            int listen = pumpListenPort(pumps);
            FuelPumps seam = new FuelPumpsAdapter(pumps);
            push(listen, status(8, 0, "BUSYON", "ACK"), false);
            Fakes.until(() -> "BUSYON".equals(seam.state(8).orElse("")), Duration.ofSeconds(5));
            seam.handleDown(8, false); // hangs up; the suite owes an IDLEOFF before the sale is posted
            assertFalse(seam.waitForPostedSale(8, Duration.ofMillis(200)));
        }
    }

    @Test
    void dcrDeviceAdapterReportsItsDisplayPrintsAndCloses() {
        Fakes.Simulator dcrSim = new Fakes.Simulator(fixture("dcr_ack.bin"));
        PetroDcr dcr = PetroDcr.started(HOST, 1, Fakes.freePort(), dcrSim.port(), FAST);
        DcrDevice seam = new DcrDeviceAdapter(dcr);
        try {
            assertTrue(seam.promptLines().isEmpty(), "no display before the first push");
            assertEquals(0, seam.printCount());
            assertThrows(ForecourtWaitExpired.class, () -> seam.receiptSince(0, Duration.ofMillis(200)));
        } finally {
            seam.close();
            dcrSim.close();
        }
    }

    /** The listen port the client was started on: the test's own, read back from its listener. */
    private static int pumpListenPort(PetroPumps pumps) {
        return pumps.listenPort();
    }
}
