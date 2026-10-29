package com.verifone.cat.cloudtunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.provision.transport.ForwardHandler;
import com.verifone.cat.provision.transport.ForwardedConnection;

/**
 * The controller's half of a forwarded connection: a channel from the Commander's SSH forward is joined to the
 * upstream socket and the bytes are carried both ways until both directions finish or either fails. A port of the
 * relay half of the Python {@code petrotest.cloud_tunnel} ({@code _forward_handler}, {@code _open_forward},
 * {@code _relay}, {@code _close_connections}, {@code parse_connect}).
 *
 * <p>A forward to a fixed endpoint connects straight away. The gateway forward ({@link CloudTunnel#GATEWAY_FORWARD})
 * carries a {@code CONNECT host port} header first, which is read and parsed here. {@link #forwardHandler} returns at
 * once and does the connect on a worker thread, so neither DNS, the header read nor the outbound connect holds the
 * SSH transport's dispatcher; each direction then runs on a daemon thread of its own.
 *
 * <p>Deviation: the Python bounds the header read with {@code channel.settimeout(15)}. A forwarded channel here is a
 * pair of streams with no read timeout, so a timer closes the channel when no header arrived within
 * {@link #CONNECT_TIMEOUT_MILLIS}; the read then fails and the forward is dropped with the same warning.
 *
 * <p>The warning goes to standard error as {@code cloud-tunnel: forward: host:port: reason} (the Python's
 * {@code log.warning}, which its CLI prints through logging's last-resort handler); {@code open -> host:port} is a
 * debug line, as the Python's {@code log.info} is not shown by its CLI.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class CloudTunnelRelay {

    private static final Logger LOG = LoggerFactory.getLogger(CloudTunnelRelay.class);

    /** The timeout on the upstream connect and on reading the header. */
    static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    /** A gateway CONNECT header longer than this is refused. */
    static final int HEADER_MAX = 512;

    /** Closes a channel whose header is late. One daemon thread for every forward. */
    private static final ScheduledExecutorService DEADLINES = deadlines();

    /** A cancelled deadline leaves the queue at once, so it holds no channel for its 15 s. */
    private static ScheduledExecutorService deadlines() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "cloud-tunnel-deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private CloudTunnelRelay() {}

    /**
     * The handler every forward of one plan shares: the gateway's port reads a CONNECT header, the others go to their
     * route's endpoint. It starts a worker and returns.
     *
     * @param byPort each route's forward port to its endpoint
     */
    public static ForwardHandler forwardHandler(Map<Integer, Endpoint> byPort) {
        Map<Integer, Endpoint> routes = Map.copyOf(byPort);
        return channel -> {
            boolean gateway = channel.port() == CloudTunnel.GATEWAY_FORWARD;
            Endpoint endpoint = gateway ? null : routes.get(channel.port());
            if (endpoint == null && !gateway) {
                warn("?", channel.port(), "no route forwards this port");
                channel.close();
                return;
            }
            Thread worker = new Thread(() -> openForward(channel, endpoint), "cloud-tunnel-forward");
            worker.setDaemon(true);
            worker.start();
        };
    }

    /**
     * {@code CONNECT <host> <port>} from {@code CatNetRelay}.
     *
     * @throws IllegalArgumentException for anything else
     */
    public static Endpoint parseConnect(byte[] header) {
        for (byte b : header) {
            if (b < 0) {
                throw new IllegalArgumentException("not ASCII: header");
            }
        }
        String[] words = new String(header, StandardCharsets.US_ASCII).strip().split("\\s+");
        if (words.length != 3 || !words[0].equals("CONNECT")) {
            throw new IllegalArgumentException("not a CONNECT header: " + new String(header, StandardCharsets.US_ASCII));
        }
        int port;
        try {
            port = Integer.parseInt(words[2]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("port is not a number: " + words[2], e);
        }
        if (port <= 0 || port >= 65536) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        return new Endpoint(words[1], port);
    }

    /** {@link #openForward(ForwardedConnection, Endpoint)} for a socket channel. */
    public static void openForward(Socket channel, Endpoint endpoint) {
        openForward(ForwardedConnection.of(channel), endpoint);
    }

    /**
     * Opens one forwarded connection on {@code channel}: reads the header when {@code endpoint} is null, connects
     * upstream, and starts carrying bytes. A failure closes the channel (and an upstream socket already made) and
     * returns; it does not throw. Blocks for the header and the connect: call it on a worker.
     */
    public static void openForward(ForwardedConnection channel, Endpoint endpoint) {
        byte[] rest = new byte[0];
        Socket socket = null;
        String host = endpoint == null ? "?" : endpoint.host();
        int port = endpoint == null ? 0 : endpoint.port();
        try {
            if (endpoint == null) {
                ScheduledFuture<?> deadline = DEADLINES.schedule(channel::close, CONNECT_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS);
                byte[] header;
                try {
                    header = readHeader(channel.input());
                } finally {
                    deadline.cancel(false);
                }
                int newline = indexOf(header, header.length, (byte) '\n');
                Endpoint target = parseConnect(Arrays.copyOf(header, newline));
                host = target.host();
                port = target.port();
                rest = Arrays.copyOfRange(header, newline + 1, header.length);
            }
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(0);
            if (rest.length > 0) {
                OutputStream out = socket.getOutputStream();
                out.write(rest);
                out.flush();
            }
        } catch (IOException | IllegalArgumentException e) {
            warn(host, port, e.getMessage());
            closeAll(channel, socket == null ? null : ForwardedConnection.of(socket));
            return;
        }
        LOG.debug("open -> {}:{}", host, port);
        relay(channel, ForwardedConnection.of(socket));
    }

    private static void warn(String host, int port, String reason) {
        System.err.println("cloud-tunnel: forward: " + host + ":" + port + ": " + reason);
        System.err.flush();
    }

    /** Reads up to the first newline, refusing a header longer than {@link #HEADER_MAX}. */
    static byte[] readHeader(InputStream in) throws IOException {
        byte[] buffer = new byte[HEADER_MAX];
        int length = 0;
        while (indexOf(buffer, length, (byte) '\n') < 0) {
            int room = HEADER_MAX - length;
            if (room <= 0) {
                throw new IOException("no CONNECT header");
            }
            int read = in.read(buffer, length, room);
            if (read < 0) {
                throw new IOException("no CONNECT header");
            }
            length += read;
        }
        return Arrays.copyOf(buffer, length);
    }

    private static int indexOf(byte[] bytes, int length, byte value) {
        for (int i = 0; i < length; i++) {
            if (bytes[i] == value) {
                return i;
            }
        }
        return -1;
    }

    /** {@link #relay(ForwardedConnection, ForwardedConnection)} for two sockets. */
    static void relay(Socket left, Socket right) {
        relay(ForwardedConnection.of(left), ForwardedConnection.of(right));
    }

    /**
     * Owns both connections until both directions finish, or either one fails. A direction that ends cleanly
     * half-closes its sink, so the other direction keeps flowing (a reply after the request's end).
     */
    static void relay(ForwardedConnection left, ForwardedConnection right) {
        AtomicInteger remaining = new AtomicInteger(2);
        startPump(left, right, left, right, remaining);
        startPump(right, left, left, right, remaining);
    }

    private static void startPump(ForwardedConnection source, ForwardedConnection sink, ForwardedConnection left,
            ForwardedConnection right, AtomicInteger remaining) {
        Thread thread = new Thread(() -> {
            boolean failed = false;
            try {
                InputStream in = source.input();
                OutputStream out = sink.output();
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                    out.flush();
                }
                sink.shutdownOutput();
            } catch (IOException | RuntimeException e) {
                failed = true;
            } finally {
                int still = remaining.decrementAndGet();
                if (failed || still == 0) {
                    closeAll(left, right);
                }
            }
        }, "cloud-tunnel-pump");
        thread.setDaemon(true);
        thread.start();
    }

    /** Closes each connection, ignoring the ones that are already gone or null. */
    static void closeAll(ForwardedConnection... connections) {
        for (ForwardedConnection connection : connections) {
            if (connection != null) {
                connection.close();
            }
        }
    }

    /** {@link #closeAll(ForwardedConnection...)} for sockets. */
    static void closeAll(Socket... sockets) {
        for (Socket socket : sockets) {
            if (socket != null) {
                ForwardedConnection.of(socket).close();
            }
        }
    }
}
