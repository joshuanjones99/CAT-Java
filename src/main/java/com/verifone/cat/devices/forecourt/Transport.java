package com.verifone.cat.devices.forecourt;

import com.verifone.cat.devices.DeviceProtocolError;
import com.verifone.cat.devices.DeviceUnreachable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * The socket work under the forecourt client: one request to the simulator, and reading one whole Java stream off a
 * connection. Ported from the Python {@code _send} and {@code _read_object}.
 *
 * <p>An I/O failure is {@link DeviceUnreachable} (the simulator is not answering, or the port is taken). A reply that
 * is not a stream this reader can parse is {@link DeviceProtocolError}. Every call is bounded by {@code timeout}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class Transport {

    private static final int CHUNK = 65536;

    private Transport() {
    }

    /**
     * Connects to {@code host:port}, writes {@code payload}, and, when {@code reply} is set, reads the simulator's
     * one-object answer before it closes.
     */
    static void send(String host, int port, byte[] payload, Duration timeout, boolean reply) {
        int millis = (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
        String target = host + ":" + port;
        try (Socket conn = new Socket()) {
            conn.connect(new InetSocketAddress(host, port), millis);
            conn.setSoTimeout(millis);
            OutputStream out = conn.getOutputStream();
            out.write(payload);
            out.flush();
            if (reply) {
                readObject(conn.getInputStream());
            }
        } catch (IOException e) {
            throw new DeviceUnreachable("the Petro forecourt at " + target, String.valueOf(e.getMessage()), e);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new DeviceProtocolError("the Petro forecourt's reply from " + target, String.valueOf(e.getMessage()), e);
        }
    }

    /**
     * Reads until one whole object has arrived. The DCR side does not close first. At end of stream an unreadable
     * object throws, as the Python's {@code loads} does.
     */
    static Object readObject(InputStream in) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] chunk = new byte[CHUNK];
        while (true) {
            int n = in.read(chunk);
            if (n < 0) {
                return JavaSerial.loads(data.toByteArray());
            }
            data.write(chunk, 0, n);
            try {
                return JavaSerial.loads(data.toByteArray());
            } catch (IllegalArgumentException | IndexOutOfBoundsException truncatedSoFar) {
                // not a whole object yet: read on
            }
        }
    }
}
