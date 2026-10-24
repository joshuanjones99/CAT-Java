package com.verifone.cat.cloudtunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The controller's half of a forwarded connection: a channel from the Commander's SSH forward is joined to the
 * upstream socket and the bytes are carried both ways until both directions finish or either fails. A port of the
 * relay half of the Python {@code petrotest.cloud_tunnel} ({@code _open_forward}, {@code _relay}, {@code parse_connect}).
 *
 * <p>A forward to a fixed endpoint connects straight away. The gateway forward carries a {@code CONNECT host port}
 * header first, which is read and parsed here. Each direction runs on a daemon thread, so a blocking connect or read
 * never holds a caller.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class CloudTunnelRelay {

    /** The timeout on the upstream connect and on reading the header. */
    static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    /** A gateway CONNECT header longer than this is refused. */
    static final int HEADER_MAX = 512;

    private CloudTunnelRelay() {}

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

    /**
     * Opens one forwarded connection on {@code channel}: reads the header when {@code endpoint} is null, connects
     * upstream, and starts carrying bytes. A failure closes the channel and returns; it does not throw.
     */
    public static void openForward(Socket channel, Endpoint endpoint) {
        byte[] rest = new byte[0];
        Socket upstream = null;
        String host = endpoint == null ? "?" : endpoint.host();
        int port = endpoint == null ? 0 : endpoint.port();
        try {
            if (endpoint == null) {
                channel.setSoTimeout(CONNECT_TIMEOUT_MILLIS);
                byte[] header = readHeader(channel.getInputStream());
                int newline = indexOf(header, (byte) '\n');
                Endpoint target = parseConnect(Arrays.copyOf(header, newline));
                host = target.host();
                port = target.port();
                rest = Arrays.copyOfRange(header, newline + 1, header.length);
            }
            upstream = new Socket();
            upstream.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            upstream.setSoTimeout(0);
            channel.setSoTimeout(0);
            if (rest.length > 0) {
                upstream.getOutputStream().write(rest);
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("cloud-tunnel: forward: " + host + ":" + port + ": " + e.getMessage());
            closeAll(channel, upstream);
            return;
        }
        relay(channel, upstream);
    }

    /** Reads up to the first newline, refusing a header longer than {@link #HEADER_MAX}. */
    static byte[] readHeader(InputStream in) throws IOException {
        byte[] buffer = new byte[HEADER_MAX];
        int length = 0;
        while (indexOf(Arrays.copyOf(buffer, length), (byte) '\n') < 0) {
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

    private static int indexOf(byte[] bytes, byte value) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == value) {
                return i;
            }
        }
        return -1;
    }

    /** Owns both connections until both directions finish, or either one fails. */
    static void relay(Socket left, Socket right) {
        AtomicInteger remaining = new AtomicInteger(2);
        startPump(left, right, remaining);
        startPump(right, left, remaining);
    }

    private static void startPump(Socket source, Socket sink, AtomicInteger remaining) {
        Thread thread = new Thread(() -> {
            boolean failed = false;
            try {
                InputStream in = source.getInputStream();
                OutputStream out = sink.getOutputStream();
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                    out.flush();
                }
                sink.shutdownOutput();
            } catch (IOException e) {
                failed = true;
            } finally {
                int left = remaining.decrementAndGet();
                if (failed || left == 0) {
                    closeAll(source, sink);
                }
            }
        }, "cloud-tunnel-pump");
        thread.setDaemon(true);
        thread.start();
    }

    /** Shuts down and closes each socket, ignoring the ones that are already gone. */
    static void closeAll(Socket... sockets) {
        for (Socket socket : sockets) {
            if (socket == null) {
                continue;
            }
            try {
                socket.shutdownInput();
            } catch (IOException ignored) {
                // already gone
            }
            try {
                socket.shutdownOutput();
            } catch (IOException ignored) {
                // already gone
            }
            try {
                socket.close();
            } catch (IOException ignored) {
                // already gone
            }
        }
    }
}
