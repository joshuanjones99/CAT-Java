package com.verifone.cat.sims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * {@link Fepsim}: the Python CAT's {@code tests/unit/test_cat_sims_fepsim.py}, case for case. The jars are zips carrying
 * the class names read from petroapps (v1, v2); the registry rows are the real ones; the admin API is a loopback
 * {@code HttpServer}. Evidence about this code only.
 */
class FepsimTest {

    static final String RT = Fepsim.V1_CLASS;
    static final String REPO = Fepsim.V2_CLASS;
    static final String MAIN = "com/verifone/isd/pcapps/simulators/SimulatorMain.class";

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    /** A jar with the suite's main class and {@code classes}. */
    static Path jar(Path path, String... classes) throws IOException {
        Files.createDirectories(path.getParent());
        Object[] entries = new Object[(classes.length + 1) * 2];
        entries[0] = MAIN;
        entries[1] = new byte[] {(byte) 0xca, (byte) 0xfe};
        for (int i = 0; i < classes.length; i++) {
            entries[2 + 2 * i] = classes[i];
            entries[3 + 2 * i] = new byte[] {(byte) 0xca, (byte) 0xfe};
        }
        Files.write(path, Zips.zip(entries));
        return path;
    }

    @Test
    void theGenerationIsReadFromTheJarsClasses() throws IOException {
        assertEquals(0, Fepsim.generation(jar(dir.resolve("a/Petro_Simulator_Suite.jar"))));
        assertEquals(Fepsim.V1, Fepsim.generation(jar(dir.resolve("b/Petro_Simulator_Suite.jar"), RT)));
        assertEquals(Fepsim.V2, Fepsim.generation(jar(dir.resolve("c/Petro_Simulator_Suite.jar"), RT, REPO)));
    }

    @Test
    void aMissingOrBrokenJarIsNoFepSimulator() throws IOException {
        Path broken = dir.resolve("broken.jar");
        Files.writeString(broken, "not a zip");
        assertEquals(0, Fepsim.generation(null));
        assertEquals(0, Fepsim.generation(dir.resolve("absent.jar")));
        assertEquals(0, Fepsim.generation(broken));
    }

    private List<SimEntry> runRows(String... classes) throws IOException {
        SimContext sims = SimContext.of(dir, "");
        SimEntry petro = Registry.byName("Petro Simulator Suite").orElseThrow();
        jar(sims.jarFor(petro).orElseThrow(), classes);
        return List.of(Registry.byName("BUYPASS Host").orElseThrow(), petro);
    }

    @Test
    void v2SupersedesBuypassHostOnItsOwnPort() throws IOException {
        Fepsim.Split split = Fepsim.supersede(runRows(RT, REPO), SimContext.of(dir, ""), false);
        assertEquals(List.of("Petro Simulator Suite"), split.keep().stream().map(SimEntry::name).toList());
        assertEquals(1, split.superseded().size());
        Fepsim.Superseded gone = split.superseded().get(0);
        assertEquals("BUYPASS Host", gone.entry().name());
        assertEquals("BUYPASS-41107", gone.instance());
        assertEquals(41107, gone.port());
        assertEquals(Fepsim.V2, gone.generation());
        assertEquals(List.of(), split.notes());
        // Its answers come from the suite's WireMock, which step 3 checks as well.
        assertEquals(List.of(80), gone.needs());
        assertEquals("BUYPASS", gone.legacy());
        assertEquals("BUYPASS Host — served by Petro Simulator Suite's FEP simulator (v2), instance BUYPASS-41107 on "
                + "41107", gone.describe());
    }

    @Test
    void v1OrNoFepSimulatorKeepsBuypassHostAndSaysWhy() throws IOException {
        for (String[] run : new String[][] {{"v1", RT}, {"none"}}) {
            Path suite = dir.resolve("Petro_Simulator_Suite");
            com.verifone.cat.sims.base.Trees.deleteQuietly(suite);
            String[] classes = java.util.Arrays.copyOfRange(run, 1, run.length);
            Fepsim.Split split = Fepsim.supersede(runRows(classes), SimContext.of(dir, ""), false);
            assertEquals(2, split.keep().size());
            assertTrue(split.superseded().isEmpty());
            assertEquals(List.of("BUYPASS Host: Petro Simulator Suite's FEP simulator is " + run[0]
                    + ", it needs v2; BUYPASS Host runs"), split.notes());
        }
    }

    @Test
    void buypassHostRunsWhenPetroIsNotInTheRun() throws IOException {
        List<SimEntry> rows = runRows(RT, REPO);
        Fepsim.Split split = Fepsim.supersede(rows.subList(0, 1), SimContext.of(dir, ""), false);
        assertEquals(List.of("BUYPASS Host"), split.keep().stream().map(SimEntry::name).toList());
        assertTrue(split.superseded().isEmpty());
        assertEquals(List.of("BUYPASS Host: Petro Simulator Suite is not in this run; BUYPASS Host runs"),
                split.notes());
    }

    @Test
    void buypassHostAskedForRunsWithV2Staged() throws IOException {
        Fepsim.Split split = Fepsim.supersede(runRows(RT, REPO), SimContext.of(dir, ""), true);
        assertEquals(2, split.keep().size());
        assertTrue(split.superseded().isEmpty());
        assertEquals(List.of("BUYPASS Host: Petro Simulator Suite's FEP simulator is v2, but this run asks for "
                + "BUYPASS Host (--buypass-host); BUYPASS Host runs"), split.notes());
    }

    /** A suite holding v2's example for {@code instance}, shaped like build 461's. */
    private static Path suite(Path root, String instance, Object port) throws IOException {
        Path examples = Files.createDirectories(root.resolve("config/fepsim/instances.examples"));
        Map<String, Object> example = new java.util.LinkedHashMap<>();
        example.put("instanceId", instance);
        example.put("enabled", true);
        example.put("autoStart", false);
        example.put("mode", "SIMULATE");
        example.put("fepName", instance.substring(0, instance.lastIndexOf('-')));
        example.put("port", port);
        example.put("upstream", Map.of("host", "<certification host>", "port", 8030));
        Files.writeString(examples.resolve(instance + ".json"), JSON.writeValueAsString(example));
        return root;
    }

    private static JsonNode read(Path path) throws IOException {
        return JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
    }

    @Test
    void theInstanceIsV2sExampleAutoStartedInSimulateWithoutUpstream() throws IOException {
        suite(dir, "BUYPASS-41107", 41107);
        Path written = Fepsim.installInstance(dir, "BUYPASS-41107", "BUYPASS");
        assertEquals(dir.resolve("config/fepsim/instances/BUYPASS-41107.json"), written);
        JsonNode config = read(written);
        assertTrue(config.get("autoStart").booleanValue());
        assertEquals("SIMULATE", config.get("mode").asText());
        assertFalse(config.has("upstream"));
        assertEquals("BUYPASS", config.get("fepName").asText());
        assertEquals(41107, config.get("port").intValue());
        assertEquals("BUYPASS", config.get("legacy").get("profile").asText());
    }

    @Test
    void aSuiteWithoutTheExampleRaises() {
        assertThrows(NoSuchFileException.class, () -> Fepsim.installInstance(dir, "BUYPASS-41107", "BUYPASS"));
    }

    @Test
    void aRetiredInstanceNoLongerStartsAndInstallTurnsItBackOn() throws IOException {
        suite(dir, "BUYPASS-41107", 41107);
        assertNull(Fepsim.retireInstance(dir, "BUYPASS-41107"), "never installed");
        Path written = Fepsim.installInstance(dir, "BUYPASS-41107");
        assertEquals(written, Fepsim.retireInstance(dir, "BUYPASS-41107"));
        JsonNode config = read(written);
        assertFalse(config.get("autoStart").booleanValue());
        assertFalse(config.get("enabled").booleanValue());
        assertNull(Fepsim.retireInstance(dir, "BUYPASS-41107"), "already off: no restart");
        Fepsim.installInstance(dir, "BUYPASS-41107");
        assertTrue(read(written).get("autoStart").booleanValue());
    }

    @Test
    void instancePortReadsTheExample() throws IOException {
        assertEquals(8030, Fepsim.instancePort(suite(dir, "WEX-OTR-8030", 8030), "WEX-OTR-8030"));
        Path other = suite(dir.resolve("b"), "Comdata-5003", "5003");
        IllegalArgumentException named = assertThrows(IllegalArgumentException.class,
                () -> Fepsim.instancePort(other, "Comdata-5003"));
        assertTrue(named.getMessage().contains("names no port"), named.getMessage());
        assertThrows(NoSuchFileException.class, () -> Fepsim.instancePort(dir, "PropFleet-4448"));
    }

    @Test
    void aSecondaryFepInstanceIsWrittenAutoStartedWithoutALegacyProfile() throws IOException {
        JsonNode written = read(Fepsim.installInstance(suite(dir, "WEX-OTR-8030", 8030), "WEX-OTR-8030"));
        assertTrue(written.get("autoStart").booleanValue());
        assertFalse(written.has("upstream"));
        assertFalse(written.has("legacy"));
    }

    @Test
    void theWiremockAndAdminPortsAreTheSuitesOwn() throws IOException {
        assertEquals(80, Fepsim.wiremockPort(dir));
        assertEquals(8099, Fepsim.adminPort(dir));
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/HttpSim_config.properties"), "httpServerPort=8081\n");
        Files.writeString(dir.resolve("config/FEPSim_config.properties"), "fepsim.admin.port = 9099\n");
        assertEquals(8081, Fepsim.wiremockPort(dir));
        assertEquals(9099, Fepsim.adminPort(dir));
    }

    @Test
    void exchangesCountTheEpsRequestsAfterTheMark() throws IOException {
        Path path = Fepsim.captureFile(dir, "WEX-OTR-8030");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{\"source\": \"VIPER\", \"messageType\": \"IC\"}\n");
        long mark = Fepsim.captureSize(dir, "WEX-OTR-8030");
        Files.writeString(path, "{\"source\": \"VIPER\", \"messageType\": \"IC\"}\n"
                + "{\"source\": \"WIREMOCK\", \"messageType\": \"PC\"}\n"
                + "{\"source\": \"VIPER\", \"messageType\": \"AC\"}\n"
                + "{\"source\": \"VIPER\", \"messageType\": \"IC\"}\n"
                + "{\"source\": \"VIP", java.nio.file.StandardOpenOption.APPEND);
        assertEquals(Map.of("IC", 2, "AC", 1), Fepsim.exchanges(dir, "WEX-OTR-8030", mark));
        assertEquals(4, Fepsim.exchanges(dir, "WEX-OTR-8030", 0).values().stream().mapToInt(i -> i).sum());
        assertEquals(Map.of(), Fepsim.exchanges(dir, "Comdata-5003", 0));
        assertEquals(0, Fepsim.captureSize(dir, "Comdata-5003"));
        assertTrue(path.getFileName().toString().matches("\\d{8}\\.jsonl"), path.toString());
    }

    @Test
    void anUnknownMessageTypeIsReadOffTheFrame() throws IOException {
        String[][] frames = {
            {"WEX-OTR-8030", "|IC|513272|04.04|1|SSVR:ROSS|SEQN:000735", "IC"},
            {"Comdata-5003", "EX777 TCPISP00014/00036/A5600170000000001=4912", "SP00014"},
        };
        for (String[] frame : frames) {
            Path path = Fepsim.captureFile(dir, frame[0]);
            Files.createDirectories(path.getParent());
            Files.writeString(path, JSON.writeValueAsString(Map.of("source", "VIPER", "messageType", "UNKNOWN",
                    "text", frame[1])) + "\n");
            assertEquals(Map.of(frame[2], 1), Fepsim.exchanges(dir, frame[0], 0));
        }
    }

    @Test
    void theListenerSaysWhatAnswersTheFep() {
        SimEntry host = Registry.byName("Petro Simulator Suite").orElseThrow();
        Fepsim.FepListener listener = new Fepsim.FepListener("WEX-OTR", host, "WEX-OTR-8030", 8030, 2, List.of(80));
        assertEquals("Secondary FEP WEX-OTR", listener.label());
        assertTrue(listener.describe().contains("instance WEX-OTR-8030 on 8030"), listener.describe());
    }

    /** A loopback admin API answering {@code (method path) -> status}; records each request. */
    private static HttpServer admin(Map<String, Integer> answers, List<String> seen) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            seen.add(key);
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answers.getOrDefault(key, 500), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private String register(Map<String, Integer> answers, List<String> seen) throws IOException {
        Path root = suite(dir, "WEX-OTR-8030", 8030);
        Fepsim.installInstance(root, "WEX-OTR-8030");
        HttpServer server = admin(answers, seen);
        try {
            return Fepsim.registerInstance("http://localhost:" + server.getAddress().getPort(), root,
                    "WEX-OTR-8030");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aRunningSuiteIsHandedTheInstanceOverItsAdminApi() throws IOException {
        List<String> seen = new CopyOnWriteArrayList<>();
        String how = register(Map.of("POST /admin/fep/instances", 201), seen);
        assertTrue(how.startsWith("created"), how);
        assertEquals(List.of("POST /admin/fep/instances"), seen);
    }

    @Test
    void anInstanceTheSuiteAlreadyHasIsStarted() throws IOException {
        List<String> seen = new CopyOnWriteArrayList<>();
        String how = register(Map.of("POST /admin/fep/instances", 409,
                "PUT /admin/fep/instances/WEX-OTR-8030/start", 200), seen);
        assertTrue(how.startsWith("started"), how);
        assertEquals("PUT /admin/fep/instances/WEX-OTR-8030/start", seen.get(seen.size() - 1));
    }

    @Test
    void aRefusedRegistrationRaises() {
        List<String> seen = new CopyOnWriteArrayList<>();
        IOException refused = assertThrows(IOException.class, () -> register(Map.of(), seen));
        assertTrue(refused.getMessage().contains("HTTP 500"), refused.getMessage());
    }
}
