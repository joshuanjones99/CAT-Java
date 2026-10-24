package com.verifone.cat.devices.forecourt;

import static com.verifone.cat.devices.forecourt.Fakes.FAST;
import static com.verifone.cat.devices.forecourt.Fakes.HOST;
import static com.verifone.cat.devices.forecourt.Fakes.push;
import static com.verifone.cat.devices.forecourt.Fakes.status;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.preflight.Bounded;
import com.verifone.cat.preflight.Surfaces;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The preflight read of the suite's pumps, the listener's drop-don't-die rules and the port sink. Ported from the Python
 * {@code test_devices_petro_forecourt.py} (pumps_idle, PortSink and listener sections). The Python's CLI-level pumps_idle
 * tests are not ported: they drive preflight, which is not this slice.
 */
class PumpStatusAndListenerTest {

    /** The suite's login answer, pushed by a thread that waits for the login first. */
    private static Surfaces.PumpStatus readThroughSuite(Fakes.Simulator suite, int listen, byte[] answer) {
        Thread thread = new Thread(() -> {
            suite.waitFor(1);
            push(listen, answer, false);
        }, "fake-suite-login-answer");
        thread.setDaemon(true);
        thread.start();
        return new PetroPumpStatus(HOST, listen, suite.port(), FAST);
    }

    @Test
    void theRigReadsItsPumpsFromTheSuiteAtLogin() {
        try (Fakes.Simulator suite = new Fakes.Simulator()) {
            int listen = Fakes.freePort();
            Surfaces.PumpStatus read = readThroughSuite(suite, listen,
                    status(1, 1, "IDLEOFF", "ACK", 2, 1, "IDLEOFF", "ACK", 3, 1, "IDLEOFF", "ACK"));
            List<Surfaces.PumpState> states = read.pumps(List.of(1, 2, 3));
            assertEquals(3, states.size());
            assertTrue(states.stream().allMatch(Surfaces.PumpState::idle), states.toString());
            assertArrayEquals(ForecourtWire.encodeLogin(true), suite.waitFor(1).get(0)); // a login, nothing else
        }
    }

    @Test
    void aLiftedAndAnAuthorisedPumpAreNotIdle() {
        try (Fakes.Simulator suite = new Fakes.Simulator()) {
            int listen = Fakes.freePort();
            Surfaces.PumpStatus read = readThroughSuite(suite, listen,
                    status(1, 1, "IDLEOFF", "ACK", 2, 1, "BUSYOFF", "ACK", 3, 1, "IDLEON", "ACK"));
            List<Surfaces.PumpState> states = read.pumps(List.of(1, 2, 3));
            assertTrue(states.get(0).idle());
            Surfaces.PumpState authorised = states.get(1);
            assertEquals("BUSYOFF", authorised.state());
            assertTrue(authorised.pendingPrepay());
            assertFalse(authorised.idle());
            Surfaces.PumpState lifted = states.get(2);
            assertTrue(lifted.handleUp());
            assertFalse(lifted.idle());
        }
    }

    @Test
    void aPumpTheSuiteNeverReportsTimesOutRatherThanPassing() {
        try (Fakes.Simulator suite = new Fakes.Simulator()) {
            int listen = Fakes.freePort();
            ForecourtTimeouts shortWait = new ForecourtTimeouts(Duration.ofMillis(500), Duration.ofMillis(500),
                    Duration.ofMillis(500));
            Thread thread = new Thread(() -> {
                suite.waitFor(1);
                push(listen, status(1, 1, "IDLEOFF", "ACK", 2, 1, "IDLEOFF", "ACK"), false);
            }, "fake-suite-login-answer");
            thread.setDaemon(true);
            thread.start();
            Surfaces.PumpStatus read = new PetroPumpStatus(HOST, listen, suite.port(), shortWait);
            var expired = assertThrows(Bounded.TimeoutExpired.class, () -> read.pumps(List.of(1, 2, 3)));
            assertTrue(expired.getMessage().contains("pump 3"), expired.getMessage());
        }
    }

    @Test
    void aHandlerThatRaisesDoesNotStopTheListener() throws IOException {
        AtomicInteger seen = new AtomicInteger();
        int port = Fakes.freePort();
        PushListener listener = new PushListener(port, obj -> {
            if (seen.getAndIncrement() == 0) {
                throw new IllegalStateException("fuelingPosition");
            }
        }, Duration.ofSeconds(5), null);
        try {
            push(port, status(1, 0, "IDLEOFF", "ACK"), false);
            push(port, status(2, 0, "IDLEOFF", "ACK"), false);
            Fakes.until(() -> seen.get() == 2, Duration.ofSeconds(5));
        } finally {
            listener.close();
        }
    }

    @Test
    void aProbeThatClosesWithoutAStreamIsDroppedAndTheListenerKeepsGoing() throws IOException {
        AtomicInteger seen = new AtomicInteger();
        int port = Fakes.freePort();
        PushListener listener = new PushListener(port, obj -> seen.incrementAndGet(), Duration.ofSeconds(5), null);
        try {
            new Socket(HOST, port).close();
            push(port, status(1, 0, "IDLEOFF", "ACK"), false);
            Fakes.until(() -> seen.get() == 1, Duration.ofSeconds(5));
        } finally {
            listener.close();
        }
    }

    @Test
    void aPortAlreadyHeldIsRefusedAsDeviceUnreachable() throws IOException {
        int port = Fakes.freePort();
        try (PortSink held = new PortSink(port)) {
            assertEquals(port, held.port());
            assertThrows(DeviceUnreachable.class, () -> new PortSink(port));
        }
        new PortSink(port).close(); // given back on close
    }

    @Test
    void aReplyMustStartWithTheStreamHeader() {
        assertThrows(IllegalArgumentException.class,
                () -> new PushListener(Fakes.freePort(), obj -> { }, FAST.http(), new byte[] {1, 2, 3, 4, 5}));
    }
}
