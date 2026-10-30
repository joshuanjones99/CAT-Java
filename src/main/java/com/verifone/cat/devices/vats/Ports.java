package com.verifone.cat.devices.vats;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * Whether something accepts a TCP connection: the Python CAT's {@code petrotest.devices.vats.relay.listening}, which
 * the relay, BTT and the readiness checks all ask.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class Ports {

    /** The Python's default: half a second. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(500);

    private Ports() {}

    /** Whether something accepts a connection on this PC's loopback {@code port}. */
    public static boolean listening(int port) {
        return listening(port, null, DEFAULT_TIMEOUT);
    }

    /** Whether something accepts a connection on {@code host:port}; a null host is this PC's loopback. */
    public static boolean listening(int port, String host, Duration timeout) {
        try (Socket socket = new Socket()) {
            InetSocketAddress address = host == null
                    ? new InetSocketAddress(InetAddress.getLoopbackAddress(), port)
                    : new InetSocketAddress(host, port);
            if (address.isUnresolved()) {
                return false;
            }
            socket.connect(address, (int) Math.max(1, timeout.toMillis()));
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }
}
