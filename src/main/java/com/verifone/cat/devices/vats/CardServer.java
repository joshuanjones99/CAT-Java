package com.verifone.cat.devices.vats;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceUnreachable;

/**
 * BTT's Card Simulator Server, spoken natively (CSSP: BER-TLV over TCP). A port of the Python CAT's
 * {@code petrotest.devices.vats.cssp}.
 *
 * <p>The Brand Test Tool's card server ({@code Actions -> Start Card Simulation Server}, TCP port
 * {@code server_tcpip_port}: 9050 on the lab bench, rig owner 2026-10-06) loads a card image onto the
 * SmartLink/SmartWave probe in the terminal. PATS reaches it through {@code vats_runner/vats_collis.py}; this is the
 * same three requests, encoded the way that file encodes them:
 *
 * <pre>
 *     SELECT image   EA &lt;len&gt; DF01 02 A100  DF03 &lt;len&gt; &lt;utf-8 image path&gt;
 *     START          EA 05    DF01 02 B100
 *     STOP           EA 05    DF01 02 C100
 * </pre>
 *
 * each behind a two-byte big-endian length. Every reply is 13 bytes; byte 12 is the status. On status 0x99 ("invalid
 * state-event coupling": the server still holds the previous card) {@code vats_collis} sends STOP and retries once,
 * and so does this.
 *
 * <p>One connection is held from SELECT to STOP, as PATS holds it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python's encoder was checked against {@code vats_collis}'s byte for
 * byte; this one is checked against the Python's own test vectors. A CSSP load/stop ran live from the Python on
 * 2026-10-06; none from Java.
 */
public final class CardServer implements AutoCloseable {

    public static final byte[] START = HexFormat.of().parseHex("0007EA05DF0102B100");
    public static final byte[] STOP = HexFormat.of().parseHex("0007EA05DF0102C100");

    /** Byte 12 of a reply, as {@code vats_collis._collis_receive} names it. */
    public static final Map<Integer, String> STATUS = Map.of(
            0x00, "ok",
            0x14, "invalid card image",
            0x30, "invalid request syntax",
            0x99, "invalid state-event coupling");

    static final int COUPLING = 0x99;
    static final int REPLY_LEN = 13;

    /** The Python's default connection timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private final String host;
    private final int port;
    private final Duration timeout;
    private Socket socket;

    public CardServer(String host, int port) {
        this(host, port, DEFAULT_TIMEOUT);
    }

    public CardServer(String host, int port, Duration timeout) {
        this.host = host;
        this.port = port;
        this.timeout = timeout;
    }

    /** The server refused a request: the Python's {@code CardServerError}. */
    public static final class CardServerError extends DeviceError {

        private final int status;

        public CardServerError(String request, int status) {
            super("BTT card server refused " + request + ": " + STATUS.getOrDefault(status, "general error")
                    + " (0x" + String.format(Locale.ROOT, "%02X", status) + ")");
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    static byte[] tlvLength(int n) {
        if (n <= 127) {
            return new byte[] {(byte) n};
        }
        if (n <= 255) {
            return new byte[] {(byte) 0x81, (byte) n};
        }
        if (n <= 0xFFFF) {
            return new byte[] {(byte) 0x82, (byte) (n >> 8), (byte) n};
        }
        throw new IllegalArgumentException("TLV value too long: " + n + " bytes");
    }

    /** The SELECT request for {@code image}, byte for byte as {@code collis_select_image} builds it. */
    public static byte[] selectRequest(String image) {
        byte[] path = image.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(HexFormat.of().parseHex("DF0102A100"));
        body.writeBytes(HexFormat.of().parseHex("DF03"));
        body.writeBytes(tlvLength(path.length));
        body.writeBytes(path);
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        message.write(0xEA);
        message.writeBytes(tlvLength(body.size()));
        message.writeBytes(body.toByteArray());
        int length = message.size();
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        framed.write(length >> 8);
        framed.write(length);
        framed.writeBytes(message.toByteArray());
        return framed.toByteArray();
    }

    private Socket connect() {
        if (socket == null) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(host, port), millis(timeout));
                s.setSoTimeout(millis(timeout));
            } catch (IOException e) {
                closeQuietly(s);
                throw new DeviceUnreachable("BTT card server " + host + ":" + port,
                        e.getMessage() + " — is BTT open with its card simulation server started?", e);
            }
            socket = s;
        }
        return socket;
    }

    private int send(byte[] request, String what) {
        Socket s = connect();
        byte[] reply = new byte[REPLY_LEN];
        try {
            OutputStream out = s.getOutputStream();
            out.write(request);
            out.flush();
            InputStream in = s.getInputStream();
            int got = 0;
            while (got < REPLY_LEN) {
                int n = in.read(reply, got, REPLY_LEN - got);
                if (n < 0) {
                    close();
                    throw new DeviceUnreachable("BTT card server " + host + ":" + port, "closed during " + what);
                }
                got += n;
            }
        } catch (IOException e) {
            close();
            throw new DeviceUnreachable("BTT card server " + host + ":" + port, e.getMessage() + " during " + what, e);
        }
        return reply[12] & 0xFF;
    }

    private void request(byte[] request, String what) {
        int status = send(request, what);
        if (status == COUPLING) {
            send(STOP, "STOP");
            status = send(request, what);
        }
        if (status != 0) {
            throw new CardServerError(what, status);
        }
    }

    /** Select {@code image} and start simulating it: the card is now on the probe. */
    public void load(Path image) {
        load(image.toString());
    }

    /** {@link #load(Path)} with the path as text, as the server receives it. */
    public void load(String image) {
        request(selectRequest(image), "SELECT " + Path.of(image).getFileName());
        request(START, "START");
    }

    /**
     * Whether the server replies at all: one STOP, harmless with no card loaded. A hung BTT keeps the port open and
     * answers nothing ({@link Btt#cardServerAnswers}).
     */
    public boolean ping() {
        try {
            send(STOP, "STOP");
            return true;
        } catch (DeviceError e) {
            return false;
        } finally {
            close();
        }
    }

    /** Stop simulating; the probe goes quiet. A stop with nothing loaded is fine. */
    public void stop() {
        stop(null);
    }

    /**
     * {@link #stop()}, with {@code replyTimeout} capping this one reply, for a caller (recovery) that must not wait out
     * a hung server's full timeout.
     */
    public void stop(Duration replyTimeout) {
        if (socket == null) {
            return;
        }
        try {
            if (replyTimeout != null) {
                socket.setSoTimeout(millis(replyTimeout));
            }
            int status = send(STOP, "STOP");
            if (status != 0 && status != COUPLING) {
                throw new CardServerError("STOP", status);
            }
        } catch (IOException e) {
            throw new DeviceUnreachable("BTT card server " + host + ":" + port, e.getMessage(), e);
        } finally {
            close();
        }
    }

    /** Whether a connection is held (a card was loaded since the last stop). */
    public boolean connected() {
        return socket != null;
    }

    @Override
    public void close() {
        Socket s = socket;
        socket = null;
        closeQuietly(s);
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing a dead connection
            }
        }
    }

    private static int millis(Duration d) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, d.toMillis()));
    }
}
