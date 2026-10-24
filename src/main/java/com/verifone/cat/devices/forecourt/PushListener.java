package com.verifone.cat.devices.forecourt;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accepts Petro Simulator Suite's pushes on one port and hands each decoded object to {@code onObject}.
 *
 * <p>A push is one stream. {@code reply}, when given, is written back: its stream header as soon as the push is
 * accepted, then the rest of the object once the push has been read. The DCR side waits for the reply before it
 * closes, and a suite that opens its reader before it writes (master build 129) blocks on that header. A probe that
 * closes without a stream is dropped, and so is a push that does not parse: the listener keeps running.
 *
 * <p>Binding throws {@link IOException} when something else holds the port (for example PATS itself).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class PushListener {

    private static final Logger LOG = LoggerFactory.getLogger(PushListener.class);
    private static final int ACCEPT_POLL_MILLIS = 500;

    private final int port;
    private final Consumer<Object> onObject;
    private final Duration timeout;
    private final byte[] reply;
    private final ServerSocket server;
    private volatile boolean stopped;

    PushListener(int port, Consumer<Object> onObject, Duration timeout, byte[] reply) throws IOException {
        if (reply != null && !startsWithHeader(reply)) {
            throw new IllegalArgumentException("a reply is one whole stream, starting with its header");
        }
        this.port = port;
        this.onObject = onObject;
        this.timeout = timeout;
        this.reply = reply;
        this.server = new ServerSocket(port);
        this.server.setSoTimeout(ACCEPT_POLL_MILLIS);
        Thread thread = new Thread(this::loop, "petro-" + port);
        thread.setDaemon(true);
        thread.start();
    }

    int port() {
        return port;
    }

    void close() {
        stopped = true;
        try {
            server.close();
        } catch (IOException e) {
            LOG.debug("closing the listener on port {}: {}", port, e.getMessage());
        }
    }

    private void loop() {
        while (!stopped) {
            Socket conn;
            try {
                conn = server.accept();
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                return; // closed
            }
            try (conn) {
                conn.setSoTimeout((int) Math.min(Integer.MAX_VALUE, timeout.toMillis()));
                deliver(exchange(conn));
            } catch (SocketTimeoutException e) {
                // A probe closes. A push that stalls is lost, and a DCR whose pushes all stall shows nothing.
                LOG.warn("port {}: a push stalled; nothing arrived in {} s", port, timeout.toSeconds());
            } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException e) {
                // a probe, or a stream this reader cannot parse
            } catch (RuntimeException e) {
                LOG.error("port {}: dropped a push this reader cannot parse", port, e);
            }
        }
    }

    private Object exchange(Socket conn) throws IOException {
        OutputStream out = conn.getOutputStream();
        if (reply != null) {
            out.write(Arrays.copyOf(reply, JavaSerial.MAGIC.length));
            out.flush();
        }
        Object obj = Transport.readObject(conn.getInputStream());
        if (reply != null) {
            out.write(Arrays.copyOfRange(reply, JavaSerial.MAGIC.length, reply.length));
            out.flush();
        }
        return obj;
    }

    private void deliver(Object obj) {
        try {
            onObject.accept(obj);
        } catch (RuntimeException e) {
            LOG.error("port {}: dropped a push its handler could not read", port, e);
        }
    }

    private static boolean startsWithHeader(byte[] reply) {
        return reply.length >= JavaSerial.MAGIC.length
                && Arrays.equals(Arrays.copyOf(reply, JavaSerial.MAGIC.length), JavaSerial.MAGIC);
    }
}
