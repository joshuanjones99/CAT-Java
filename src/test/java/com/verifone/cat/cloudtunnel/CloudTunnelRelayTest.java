package com.verifone.cat.cloudtunnel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The relay's connection ownership over real loopback sockets: the Python {@code tests/unit/test_tunnel_lifecycle.py}
 * (a reply after the request's half-close, both ways; a reset closes both), and the shipped {@code CatNetRelay.java}.
 *
 * <p>Evidence about this repository only: loopback sockets, no SSH, no Commander.
 */
class CloudTunnelRelayTest {

    /** A connected pair on loopback: {@code [ours, theirs]}. */
    private static Socket[] pair(ServerSocket server) throws IOException {
        Socket ours = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
        Socket theirs = server.accept();
        ours.setSoTimeout(5000);
        theirs.setSoTimeout(5000);
        return new Socket[] {ours, theirs};
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            all.write(buffer, 0, n);
        }
        return all.toByteArray();
    }

    private static Thread sendThenHalfClose(Socket socket, byte[] data) {
        Thread sender = new Thread(() -> {
            try {
                OutputStream out = socket.getOutputStream();
                out.write(data);
                out.flush();
                socket.shutdownOutput();
            } catch (IOException ignored) {
                // the assertion below fails instead
            }
        });
        sender.setDaemon(true);
        sender.start();
        return sender;
    }

    @Test
    void aReplyAfterTheRequestsHalfCloseStillArrivesEitherWay() throws Exception {
        for (boolean reverse : new boolean[] {false, true}) {
            try (ServerSocket server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress())) {
                Socket[] a = pair(server);
                Socket[] b = pair(server);
                Socket client = a[0];
                Socket left = a[1];
                Socket right = b[0];
                Socket destination = b[1];
                if (reverse) {
                    Socket swap = client;
                    client = destination;
                    destination = swap;
                }
                CloudTunnelRelay.relay(left, right);
                byte[] request = "request\0".repeat(20_000).getBytes(StandardCharsets.US_ASCII);
                byte[] response = "response\0".repeat(20_000).getBytes(StandardCharsets.US_ASCII);
                Thread sender = sendThenHalfClose(client, request);
                assertArrayEquals(request, readAll(destination.getInputStream()));
                sender.join(5000);
                // A reply starts only after the destination has seen the request's end.
                sender = sendThenHalfClose(destination, response);
                assertArrayEquals(response, readAll(client.getInputStream()));
                sender.join(5000);
                long deadline = System.nanoTime() + 5_000_000_000L;
                while (!(left.isClosed() && right.isClosed()) && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertTrue(left.isClosed() && right.isClosed(), "the relay closes both once both ends finished");
                client.close();
                destination.close();
            }
        }
    }

    @Test
    void aResetClosesBothRelaySockets() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress())) {
            Socket[] a = pair(server);
            Socket[] b = pair(server);
            Socket peer = a[0];
            Socket destination = b[1];
            peer.setSoLinger(true, 0);
            peer.close(); // a TCP reset, rather than a normal end
            CloudTunnelRelay.relay(a[1], b[0]);
            assertEquals(-1, destination.getInputStream().read());
            destination.close();
        }
    }

    @Test
    void theRelaySourceShipsInThisBuild() {
        String source = new String(CloudTunnel.relaySource(), StandardCharsets.UTF_8);
        assertTrue(source.contains("public final class CatNetRelay"));
        assertTrue(source.contains("CONNECT "));
    }

    @Test
    void theRelaySourceIsThePythonCatsOwnWhenThatCheckoutIsHere() throws IOException {
        // Both CATs push the same file, so neither restarts the other's relay (CloudTunnel.applyGateway compares).
        Path python = Path.of("C:\\gitrepos\\CAT\\bridge\\net-relay\\CatNetRelay.java");
        Assumptions.assumeTrue(Files.isRegularFile(python), "no Python CAT checkout here");
        assertArrayEquals(CloudTunnel.lfOnly(Files.readAllBytes(python)), CloudTunnel.lfOnly(CloudTunnel.relaySource()));
    }
}
