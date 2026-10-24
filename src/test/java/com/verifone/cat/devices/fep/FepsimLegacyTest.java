package com.verifone.cat.devices.fep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.pats.runner.handlers.configuration.BuypassV2;

/**
 * {@link FepsimLegacy} making real HTTP calls to a loopback server that answers v2's three routes the way build
 * 461's {@code AdminRequestDispatcher} does ({@code legacy-config} GET/PUT, {@code legacy-packets} DELETE). A port
 * of the device half of the Python CAT's {@code tests/unit/test_fepsim_legacy.py}; the handler half
 * (UPDATE_CONFIGURATION's restore) is the configuration family's {@code SimulatorFilesTest}. Evidence about this
 * repository, not about v2.
 */
class FepsimLegacyTest {

    private static final String PREFIX = "/admin/fep/BUYPASS/41107/";
    private static final String DECLINE = "RequestedRules = [UMF RSP 500 (Decline)]\nActiveRules = [UMF]\n";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private HttpServer server;
    private Path packets;
    private volatile String config;
    private volatile int status = 200;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> contentTypes = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        packets = Files.createDirectories(dir.resolve("suite/legacyPackets/BUYPASS-41107"));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String action = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : "?" + path;
        String method = exchange.getRequestMethod();
        calls.add(method + " " + action);
        String ct = exchange.getRequestHeaders().getFirst("Content-Type");
        contentTypes.add(ct == null ? "" : ct);
        byte[] in = exchange.getRequestBody().readAllBytes();
        Map<String, Object> reply = new java.util.LinkedHashMap<>();
        reply.put("profile", "BUYPASS");
        if (status >= 400) {
            send(exchange, status, "no such listener");
            return;
        }
        switch (method + " " + action) {
            case "GET legacy-config" -> reply.put("configText", config);
            case "PUT legacy-config" -> config = new String(in, StandardCharsets.UTF_8);
            case "DELETE legacy-packets" -> {
                int deleted = 0;
                try (var files = Files.list(packets)) {
                    for (Path p : files.toList()) {
                        Files.delete(p);
                        deleted++;
                    }
                }
                reply.put("deleted", deleted);
            }
            default -> {
                send(exchange, 404, "unknown route");
                return;
            }
        }
        send(exchange, 200, JSON.writeValueAsString(reply));
    }

    private static void send(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private FepsimLegacy listener() {
        String admin = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + server.getAddress().getPort() + "/";
        return new FepsimLegacy(admin, "BUYPASS", 41107, packets);
    }

    @Test
    void itIsTheConfigurationFamilysBuypassV2Seam() {
        assertInstanceOf(BuypassV2.class, listener());
    }

    @Test
    void noConfigAppliedYetReadsAsNull() {
        assertNull(listener().configText());
        assertEquals(List.of("GET legacy-config"), calls);
    }

    @Test
    void anAppliedConfigIsPutAsTextAndReadBack() {
        FepsimLegacy v2 = listener();
        v2.applyConfig(DECLINE);
        assertEquals(DECLINE, config);
        assertEquals(DECLINE, v2.configText());
        assertEquals(List.of("PUT legacy-config", "GET legacy-config"), calls);
        assertTrue(contentTypes.get(0).startsWith("text/plain"), contentTypes.get(0));
    }

    @Test
    void resetPacketsIsADeleteThatEmptiesV2sOwnStore() throws IOException {
        Files.writeString(packets.resolve("stale.xml"), "<x/>");
        listener().clearPackets();
        assertEquals(List.of("DELETE legacy-packets"), calls);
        try (var left = Files.list(packets)) {
            assertEquals(0, left.count());
        }
    }

    @Test
    void anHttpErrorIsADeviceErrorWithTheStatus() {
        status = 500;
        DeviceError e = assertThrows(DeviceError.class, () -> listener().applyConfig(DECLINE));
        assertTrue(e.getMessage().contains("HTTP 500"), e.getMessage());
        assertTrue(e.getMessage().contains("PUT legacy-config"), e.getMessage());
    }

    @Test
    void nothingListeningIsUnreachable() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        FepsimLegacy gone = new FepsimLegacy("http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + port,
                "BUYPASS", 41107, packets);
        assertThrows(DeviceUnreachable.class, gone::configText);
    }

    @Test
    void theEnvironmentJsonRoundTripsAndATrailingSlashIsDropped() {
        FepsimLegacy v2 = listener();
        assertTrue(!v2.admin().endsWith("/"));
        Optional<FepsimLegacy> read = FepsimLegacy.buypassFepsim(Map.of(FepsimLegacy.ENV, v2.toJson()));
        assertEquals(Optional.of(v2), read);
        assertEquals("http://localhost:8123/admin/fep/BUYPASS/41107/legacy-config",
                new FepsimLegacy("http://localhost:8123", "BUYPASS", 41107, packets).url("legacy-config"));
    }

    @Test
    void anUnsetOrBlankVariableMeansV2DoesNotServeBuypass() {
        assertEquals(Optional.empty(), FepsimLegacy.buypassFepsim(Map.of()));
        assertEquals(Optional.empty(), FepsimLegacy.buypassFepsim(Map.of(FepsimLegacy.ENV, "  ")));
    }

    @Test
    void theVariableAsThePythonWritesItIsRead() {
        String text = "{\"admin\": \"http://localhost:8123/\", \"fep\": \"BUYPASS\", \"port\": 41107, \"packets\": \""
                + packets.toString().replace("\\", "\\\\") + "\"}";
        FepsimLegacy v2 = FepsimLegacy.fromJson(text);
        assertEquals(new FepsimLegacy("http://localhost:8123", "BUYPASS", 41107, packets), v2);
    }

    @Test
    void aVariableMissingAKeyIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FepsimLegacy.fromJson("{\"admin\": \"http://localhost:1\", \"fep\": \"BUYPASS\"}"));
        assertTrue(e.getMessage().contains("port"), e.getMessage());
    }
}
