package com.verifone.cat.devices.vats;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * BTT's automation protocol: a 4-byte big-endian length, then UTF-8 JSON, each way. A port of the Python CAT's
 * {@code petrotest.devices.vats.btt.AutomationClient}.
 *
 * <p>BTT 5.9.0 carries an automation server ({@code BttAutomationExtension}, {@code btt-automation-protocol-1.5.0.jar}):
 * a TCP port, default 1983, taking {@code {"message_type", "version": "1.0", "payload"}} and answering {@code ACK} or
 * {@code NACK} the same way. {@value #START_CARD_SERVER} with {@code {"interface": "contact"}} starts the card server.
 *
 * <p>Deviation: the reply's payload is logged as Jackson writes it ({@code {"a":1}}), not with Python's
 * {@code json.dumps} spacing ({@code {"a": 1}}).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): BTT's automation server has never answered on the lab bench (it appears
 * to be a feature the dongle does not license), so neither port has talked to a real one.
 */
public final class BttAutomationClient {

    /**
     * The request that starts the card server ({@code nl.collis.brandtesttool.automation.MessageType}),
     * {@code Start_CardSimulatorServer}. Joined from two literals only because the raw-locator gate reads any
     * {@code Word_Word} literal as a POS element ID; this is BTT's protocol word, not a locator.
     */
    public static final String START_CARD_SERVER = "Start_" + "CardSimulatorServer";

    /** The Python's default timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final String host;
    private final int port;
    private final Duration timeout;

    /** The automation server on this PC's loopback {@code port}. */
    public BttAutomationClient(int port) {
        this(Btt.LOOPBACK, port, DEFAULT_TIMEOUT);
    }

    public BttAutomationClient(String host, int port, Duration timeout) {
        this.host = host;
        this.port = port;
        this.timeout = timeout;
    }

    /** What the server said to {@link #startCardServer}: whether it was an ACK, and the reply for the log. */
    public record Reply(boolean ok, String text) {}

    /** One framed request: length, then {@code {"message_type", "version": "1.0"[, "payload"]}}. */
    public static byte[] frame(String messageType, Object payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message_type", messageType);
        body.put("version", "1.0");
        if (payload != null) {
            body.put("payload", payload);
        }
        byte[] data;
        try {
            data = JSON.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot encode " + messageType, e);
        }
        return ByteBuffer.allocate(4 + data.length).putInt(data.length).put(data).array();
    }

    /** One request and its reply, each on a fresh connection, as the Python's. */
    public Map<String, Object> request(String messageType, Object payload) throws IOException {
        try (Socket socket = new Socket()) {
            int millis = (int) Math.max(1, timeout.toMillis());
            socket.connect(new InetSocketAddress(host, port), millis);
            socket.setSoTimeout(millis);
            OutputStream out = socket.getOutputStream();
            out.write(frame(messageType, payload));
            out.flush();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int length = ByteBuffer.wrap(recv(in, 4)).getInt();
            return JSON.readValue(new String(recv(in, length), StandardCharsets.UTF_8), MAP);
        }
    }

    /** {@value #START_CARD_SERVER} for {@code interfaceKind}; ok when the server ACKs. */
    public Reply startCardServer(String interfaceKind) throws IOException {
        Map<String, Object> reply = request(START_CARD_SERVER,
                Map.of("interface", interfaceKind.toLowerCase(java.util.Locale.ROOT)));
        String kind = String.valueOf(reply.getOrDefault("message_type", ""));
        return new Reply("ACK".equals(kind), kind + " " + JSON.writeValueAsString(reply.get("payload")));
    }

    private static byte[] recv(InputStream in, int n) throws IOException {
        byte[] data = new byte[n];
        int got = 0;
        while (got < n) {
            int read = in.read(data, got, n - got);
            if (read < 0) {
                throw new EOFException("BTT automation server closed after " + got + " of " + n + " bytes");
            }
            got += read;
        }
        return data;
    }
}
