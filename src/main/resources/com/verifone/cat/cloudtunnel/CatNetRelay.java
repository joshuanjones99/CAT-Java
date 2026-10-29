/*
 * CatNetRelay: the Commander's end of the cloud tunnel's gateway mode (petrotest.cloud_tunnel).
 *
 * An iptables REDIRECT sends every outbound TCP connection the Commander makes off its own
 * subnets to this relay on loopback. The relay reads where each connection was really going
 * from /proc/net/nf_conntrack (the JDK cannot read SO_ORIGINAL_DST), connects to the
 * controller's SSH reverse forward on loopback, writes one header line
 * "CONNECT <host> <port>\n", and then copies bytes both ways. The controller makes the real
 * connection. TLS stays end to end: nothing here reads the payload.
 *
 * JDK only, one file, run as source (Java 11+): java CatNetRelay.java <listen> <upstream>
 *
 * Gateway hardware history is recorded in HANDOFF.md; half-close changes have local
 * socket regression coverage in tests/unit/test_tunnel_lifecycle.py.
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class CatNetRelay {
    private static final Path CONNTRACK = Path.of("/proc/net/nf_conntrack");
    private static final int CONNECT_TIMEOUT_MS = 15_000;

    private CatNetRelay() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: java CatNetRelay.java <listen-port> <upstream-port>");
            System.exit(2);
        }
        int listen = Integer.parseInt(args[0]);
        int upstream = Integer.parseInt(args[1]);
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(loopback, listen), 128);
            log("listening on " + listen + ", upstream " + upstream);
            while (true) {
                Socket client = server.accept();
                Thread worker = new Thread(() -> handle(client, listen, loopback, upstream));
                worker.setDaemon(true);
                worker.start();
            }
        }
    }

    private static void handle(Socket client, int listen, InetAddress loopback, int upstream) {
        String peer = client.getInetAddress().getHostAddress();
        int peerPort = client.getPort();
        String target;
        try {
            target = originalDestination(peer, peerPort, listen);
        } catch (IOException e) {
            target = null;
            log("conntrack unreadable: " + e);
        }
        if (target == null) {
            log("no original destination for " + peer + ":" + peerPort);
            closeQuietly(client);
            return;
        }
        Socket tunnel = new Socket();
        try {
            tunnel.connect(new InetSocketAddress(loopback, upstream), CONNECT_TIMEOUT_MS);
            OutputStream out = tunnel.getOutputStream();
            out.write(("CONNECT " + target + "\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException e) {
            log("upstream " + upstream + " refused for " + target + ": " + e);
            closeQuietly(client);
            closeQuietly(tunnel);
            return;
        }
        relay(client, tunnel);
    }

    /**
     * "<host> <port>" for the connection from peer:peerPort that was redirected to listen.
     * A conntrack line carries the original tuple first and the reply tuple second:
     * src=S dst=D sport=X dport=P ... src=127.0.0.1 dst=S sport=LISTEN dport=X.
     */
    static String originalDestination(String peer, int peerPort, int listen) throws IOException {
        List<String> lines = Files.readAllLines(CONNTRACK, StandardCharsets.US_ASCII);
        return match(lines, peer, peerPort, listen);
    }

    static String match(List<String> lines, String peer, int peerPort, int listen) {
        for (String line : lines) {
            if (!line.contains(" tcp ")) {
                continue;
            }
            String[] src = fields(line, "src=");
            String[] dst = fields(line, "dst=");
            String[] sport = fields(line, "sport=");
            String[] dport = fields(line, "dport=");
            if (src.length < 2 || dst.length < 1 || sport.length < 2 || dport.length < 1) {
                continue;
            }
            if (src[0].equals(peer)
                    && sport[0].equals(Integer.toString(peerPort))
                    && sport[1].equals(Integer.toString(listen))) {
                return dst[0] + " " + dport[0];
            }
        }
        return null;
    }

    private static String[] fields(String line, String key) {
        StringBuilder found = new StringBuilder();
        int at = 0;
        while ((at = line.indexOf(" " + key, at)) >= 0) {
            int start = at + 1 + key.length();
            int end = line.indexOf(' ', start);
            found.append(end < 0 ? line.substring(start) : line.substring(start, end)).append('\n');
            at = start;
        }
        return found.length() == 0 ? new String[0] : found.toString().split("\n");
    }

    static void relay(Socket left, Socket right) {
        AtomicInteger remaining = new AtomicInteger(2);
        pump(left, right, remaining);
        pump(right, left, remaining);
    }

    private static void pump(Socket from, Socket to, AtomicInteger remaining) {
        Thread copier = new Thread(() -> {
            byte[] buffer = new byte[65536];
            boolean failed = false;
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, n);
                }
                to.shutdownOutput();
            } catch (IOException ignored) {
                failed = true;
            } finally {
                int left = remaining.decrementAndGet();
                if (failed || left == 0) {
                    closeQuietly(from);
                    closeQuietly(to);
                }
            }
        });
        copier.setDaemon(true);
        copier.start();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    private static void log(String message) {
        System.out.println(Instant.now() + " " + message);
        System.out.flush();
    }
}
