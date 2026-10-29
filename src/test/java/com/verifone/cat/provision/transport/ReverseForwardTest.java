package com.verifone.cat.provision.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceUnavailable;

/**
 * Reverse port forwards in the {@link Transport} API: the in-memory forwards the tunnel's tests drive, the JSch
 * daemon's close and half-close rules, and the default of a transport without forwards (hard rule 5).
 *
 * <p>No SSH session is opened: the JSch side is exercised through {@link JschForwardDaemon.Connection} with plain
 * streams. Whether a real sshd forwards to it is unverified (UNVERIFIED AGAINST HARDWARE).
 */
class ReverseForwardTest {

    static final Duration T = Duration.ofSeconds(5);

    @Test
    void anInMemoryForwardHandsTheHandlerOneEndAndTheCallerTheOther() throws IOException {
        InMemoryTransport transport = new InMemoryTransport();
        AtomicReference<ForwardedConnection> got = new AtomicReference<>();
        transport.requestReverseForward("127.0.0.1", 20000, got::set, T);
        assertEquals(List.of(20000), transport.forwards().requested());
        assertEquals("127.0.0.1", transport.forwards().forwards().get(0).bindAddress());

        ForwardedConnection box = transport.forwards().connect(20000);
        ForwardedConnection controller = got.get();
        assertEquals(20000, controller.port());
        box.output().write("hello".getBytes(StandardCharsets.US_ASCII));
        box.shutdownOutput();
        assertEquals("hello", new String(controller.input().readAllBytes(), StandardCharsets.US_ASCII));
        // A half-close: the box stopped sending, and still reads the reply.
        controller.output().write("back".getBytes(StandardCharsets.US_ASCII));
        controller.close();
        assertEquals("back", new String(box.input().readAllBytes(), StandardCharsets.US_ASCII));
        assertEquals(-1, box.input().read());
        assertThrows(IOException.class, () -> box.output().write(1), "the peer closed: writes fail");
    }

    @Test
    void writingToAClosedPeerFails() {
        InMemoryForwards forwards = new InMemoryForwards();
        forwards.request("127.0.0.1", 20000, ForwardedConnection::close);
        ForwardedConnection box = forwards.connect(20000);
        assertThrows(IOException.class, () -> box.output().write(1));
    }

    @Test
    void aRefusedOrRepeatedForwardIsADeviceErrorAndNothingForwardsAnUnaskedPort() {
        InMemoryForwards forwards = new InMemoryForwards();
        forwards.request("127.0.0.1", 20000, c -> {});
        DeviceError again = assertThrows(DeviceError.class, () -> forwards.request("127.0.0.1", 20000, c -> {}));
        assertTrue(again.getMessage().contains("listen port 20000"));
        forwards.refuse("TCP forwarding request denied");
        DeviceError refused = assertThrows(DeviceError.class, () -> forwards.request("127.0.0.1", 20001, c -> {}));
        assertTrue(refused.getMessage().endsWith("TCP forwarding request denied"));
        assertThrows(DeviceError.class, () -> forwards.connect(20001));
        assertEquals(List.of(20000, 20000, 20001), forwards.requested());
    }

    @Test
    void aDroppedSessionIsInactiveAndEndsItsForwards() {
        InMemoryTransport transport = new InMemoryTransport();
        assertTrue(transport.isActive());
        transport.keepalive(Duration.ofSeconds(30));
        assertEquals(Optional.of(Duration.ofSeconds(30)), transport.keepaliveInterval());
        transport.requestReverseForward("127.0.0.1", 20000, c -> {}, T);
        transport.dropSession();
        assertFalse(transport.isActive());
        assertEquals(List.of(), transport.forwards().forwards());
        InMemoryTransport closed = new InMemoryTransport();
        closed.close();
        assertFalse(closed.isActive());
    }

    @Test
    void aTransportWithoutForwardsSaysSoRatherThanPretending() {
        Transport bare = new Transport() {
            @Override
            public String host() {
                return "box.invalid";
            }

            @Override
            public CommandResult run(String command, Duration timeout) {
                return new CommandResult(0, "", "");
            }

            @Override
            public void put(String remotePath, byte[] content, int mode, Duration timeout) {}

            @Override
            public Optional<byte[]> get(String remotePath, Duration timeout) {
                return Optional.empty();
            }

            @Override
            public void mkdir(String remotePath, Duration timeout) {}

            @Override
            public boolean exists(String remotePath, Duration timeout) {
                return false;
            }

            @Override
            public void close() {}
        };
        assertThrows(DeviceUnavailable.class, () -> bare.requestReverseForward("127.0.0.1", 20000, c -> {}, T));
        assertThrows(DeviceUnavailable.class, () -> bare.keepalive(T));
        assertThrows(DeviceUnavailable.class, bare::isActive);
    }

    // --- the JSch daemon -----------------------------------------------------------------------------------------

    /** Records whether it was closed. */
    static final class Recording extends OutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean closed;

        @Override
        public void write(int b) {
            bytes.write(b);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void theJschHalfCloseSendsOnlyEofAndTheCloseEndsTheChannel() throws IOException {
        Recording pipe = new Recording();
        Recording channelOut = new Recording();
        AtomicBoolean disconnected = new AtomicBoolean();
        JschForwardDaemon.Connection connection = new JschForwardDaemon.Connection(20091,
                new ByteArrayInputStream("CONNECT h 1\n".getBytes(StandardCharsets.US_ASCII)), pipe, channelOut,
                () -> disconnected.set(true));
        assertEquals(20091, connection.port());
        connection.output().write('x');
        assertEquals("x", channelOut.bytes.toString(StandardCharsets.US_ASCII));
        assertEquals(0, pipe.bytes.size(), "bytes to the box go to the channel's stream, not JSch's pipe");
        connection.shutdownOutput();
        assertTrue(channelOut.closed, "the channel stream's close is JSch's EOF");
        assertFalse(pipe.closed || disconnected.get(), "a half-close leaves the channel open");
        connection.close();
        assertTrue(pipe.closed && disconnected.get());
        connection.close(); // quiet the second time
    }

    @Test
    void theJschDaemonTakesItsHandlerAndPortAndRefusesAnythingElse() {
        JschForwardDaemon daemon = new JschForwardDaemon();
        ForwardHandler handler = c -> {};
        daemon.setArg(JschForwardDaemon.arguments(handler, 20000));
        List<Object[]> bad = new ArrayList<>();
        bad.add(null);
        bad.add(new Object[] {handler});
        bad.add(new Object[] {"handler", 20000});
        bad.add(new Object[] {handler, "20000"});
        for (Object[] arg : bad) {
            assertThrows(IllegalArgumentException.class, () -> new JschForwardDaemon().setArg(arg));
        }
    }

    @Test
    void jschCanConstructTheDaemonByNameAsItDoes() throws Exception {
        Object made = Class.forName(JschForwardDaemon.class.getName()).getDeclaredConstructor().newInstance();
        assertTrue(made instanceof com.jcraft.jsch.ForwardedTCPIPDaemon);
    }
}
