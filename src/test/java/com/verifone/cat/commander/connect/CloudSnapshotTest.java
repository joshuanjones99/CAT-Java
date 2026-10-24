package com.verifone.cat.commander.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.verifone.cat.devices.csite.CSiteResponse;

/**
 * The Commander Connect golden state, captured and restored. A port of the Python
 * {@code tests/unit/test_cloud_snapshot.py}, without the command-line tests (the CLI and its wait on the
 * Commander's log are not ported).
 *
 * <p>An in-memory Commander Connect answering the GET/PUT/POST/DELETE shapes QAT answered on 2026-10-20 stands in
 * for the cloud. UNVERIFIED AGAINST HARDWARE: restore's writes.
 */
class CloudSnapshotTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    record Sent(String method, String path, JsonNode body) {}

    /** Datasets by name; {@code refuseDelete} names datasets whose DELETE answers 500 (tips). */
    static final class FakeCloud {
        final ObjectNode data;
        final Set<String> refuseDelete;
        final List<Sent> sent = new ArrayList<>();

        FakeCloud(ObjectNode data, Set<String> refuseDelete) {
            this.data = data;
            this.refuseDelete = refuseDelete;
        }

        ArrayNode items(String name) {
            CloudSnapshot.Items found = CloudSnapshot.itemsAt(data.get(name));
            assertNotNull(found);
            JsonNode node = data.get(name).get("config");
            for (String key : found.path()) {
                node = node.get(key);
            }
            return (ArrayNode) node;
        }

        CSiteResponse send(String method, String endpoint, Map<String, String> headers, String body) {
            String rest = endpoint.split("/", 5)[4];
            int slash = rest.indexOf('/');
            String name = slash < 0 ? rest : rest.substring(0, slash);
            String sysid = slash < 0 ? "" : rest.substring(slash + 1);
            JsonNode payload = body == null ? null : json(body);
            sent.add(new Sent(method, rest, payload));
            if (method.equals("GET")) {
                if (!data.has(name)) {
                    return reply(500, "{}", endpoint);
                }
                return reply(200, data.get(name).toString(), endpoint);
            }
            if (data.has(name) && CloudSnapshot.itemsAt(data.get(name)) != null && !sysid.isEmpty()) {
                ArrayNode items = items(name);
                if (method.equals("DELETE")) {
                    if (refuseDelete.contains(name)) {
                        return reply(500, "{}", endpoint);
                    }
                    for (int i = items.size() - 1; i >= 0; i--) {
                        if (CloudSnapshot.sysid(items.get(i)).equals(sysid)) {
                            items.remove(i);
                        }
                    }
                } else if (method.equals("PUT")) {
                    for (int i = 0; i < items.size(); i++) {
                        if (CloudSnapshot.sysid(items.get(i)).equals(sysid)) {
                            items.set(i, payload);
                        }
                    }
                }
                return reply(200, "{}", endpoint);
            }
            if (method.equals("POST")) {
                items(name).add(payload);
            } else if (method.equals("PUT")) { // a setting: the body is the inner object
                ObjectNode config = (ObjectNode) data.get(name).get("config");
                String cfg = config.fieldNames().next();
                ObjectNode replaced = JSON.createObjectNode().put("site", "CL120");
                replaced.setAll((ObjectNode) payload);
                config.set(cfg, replaced);
            }
            return reply(200, "{}", endpoint);
        }

        private static CSiteResponse reply(int status, String body, String url) {
            return new CSiteResponse(status, "application/json", body.getBytes(StandardCharsets.UTF_8), url);
        }
    }

    static ObjectNode golden() {
        return (ObjectNode) json("{"
                + "\"fueltaxexemptions\": {\"datasetVersion\": 1, \"config\": {\"fuelTaxExemptionConfig\":"
                + " {\"fuelTaxExemptions\": {\"fuelTaxExemption\": []}}}, \"dataset\": \"fueltaxexemptcfg\"},"
                + "\"taxrates\": {\"datasetVersion\": 1, \"config\": {\"taxRateConfig\": {\"taxRates\":"
                + " {\"taxRate\": [{\"sysid\": 1, \"rate\": 5}]}}}, \"dataset\": \"taxratecfg\"},"
                + "\"tips\": {\"config\": {\"tipConfig\": {\"tip\": []}}, \"dataset\": \"tipcfg\"},"
                + "\"ntp\": {\"config\": {\"ntpConfig\": {\"site\": \"CL120\", \"syncWithNTPServer\": false}}},"
                + "\"screens\": {\"config\": {\"screenConfig\": {\"a\": 1, \"b\": 2}}}"
                + "}");
    }

    @TempDir
    Path directory;
    FakeCloud fake;
    CloudSnapshot.Cloud cloud;
    JsonNode manifest;

    @BeforeEach
    void capture() {
        ObjectNode data = golden();
        fake = new FakeCloud(data.deepCopy(), Set.of("tips"));
        List<String> names = new ArrayList<>();
        data.fieldNames().forEachRemaining(names::add);
        names.add("menus");
        cloud = new CloudSnapshot.Cloud("1181078", fake::send);
        manifest = CloudSnapshot.capture(cloud, names, directory,
                Clock.fixed(Instant.parse("2026-10-22T01:20:00Z"), ZoneOffset.UTC));
    }

    private ArrayNode itemsOf(String name) {
        return fake.items(name);
    }

    @Test
    void captureReadsEachDatasetsKind() {
        Map<String, String> kinds = new HashMap<>();
        manifest.get("datasets").fields().forEachRemaining(e -> kinds.put(e.getKey(), e.getValue().get("kind").asText()));
        assertEquals(Map.of("fueltaxexemptions", "list", "taxrates", "list", "tips", "list", "ntp", "setting",
                "screens", "setting", "menus", "unreadable"), kinds);
        assertEquals(500, manifest.get("datasets").get("menus").get("status").asInt());
        assertEquals("[1]", manifest.get("datasets").get("taxrates").get("sysids").toString());
        assertEquals("[\"taxRateConfig\",\"taxRates\",\"taxRate\"]",
                manifest.get("datasets").get("taxrates").get("path").toString());
        assertEquals("2026-10-22T01:20:00+00:00", manifest.get("captured_at").asText());
        assertEquals("1181078", manifest.get("service_id").asText());
        assertTrue(Files.exists(directory.resolve("taxrates.json")));
        assertFalse(Files.exists(directory.resolve("menus.json")));
    }

    @Test
    void theManifestLoadsBack() {
        assertEquals(manifest, CloudSnapshot.load(directory).orElseThrow());
        assertTrue(CloudSnapshot.load(directory.resolve("none")).isEmpty());
    }

    @Test
    void aPlansLeftoversArePutBack() {
        // Round-Trip 139167's exemption, Tax 134173's rate, Tipping's tip (2026-10-20).
        itemsOf("fueltaxexemptions").add(json("{\"sysid\": 3, \"name\": \"Tax exem 3\"}"));
        ((ObjectNode) itemsOf("taxrates").get(0)).put("rate", 9.9999);
        itemsOf("tips").add(json("{\"sysid\": 1, \"enabled\": true}"));
        ((ObjectNode) fake.data.get("ntp").get("config").get("ntpConfig")).put("syncWithNTPServer", true);
        assertEquals(Set.of("fueltaxexemptions", "taxrates", "tips", "ntp"),
                CloudSnapshot.diff(cloud, directory, manifest).keySet());

        Map<String, List<String>> left = CloudSnapshot.restore(cloud, directory, manifest, line -> {});
        // The tip the cloud would not delete is off, which is as close as it gets.
        assertEquals(Map.of(), left);
        assertTrue(fake.sent.contains(new Sent("DELETE", "fueltaxexemptions/3", null)));
        assertTrue(fake.sent.contains(new Sent("PUT", "taxrates/1", json("{\"sysid\": 1, \"rate\": 5}"))));
        assertTrue(fake.sent.contains(new Sent("PUT", "tips/1", json("{\"sysid\": 1, \"enabled\": false}"))));
        assertTrue(fake.sent.contains(new Sent("PUT", "ntp", json("{\"syncWithNTPServer\": false}")))); // no site
        assertEquals(Map.of(), CloudSnapshot.diff(cloud, directory, manifest).entrySet().stream()
                .filter(e -> !e.getKey().equals("tips")).collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue)));
    }

    @Test
    void theDiffNamesEachChange() {
        itemsOf("fueltaxexemptions").add(json("{\"sysid\": 3}"));
        itemsOf("taxrates").removeAll();
        Map<String, List<String>> differs = CloudSnapshot.diff(cloud, directory, manifest);
        assertEquals(List.of("+3"), differs.get("fueltaxexemptions"));
        assertEquals(List.of("-1"), differs.get("taxrates"));
        fake.data.remove("ntp");
        assertEquals(List.of("unreadable now (HTTP 500)"), CloudSnapshot.diff(cloud, directory, manifest).get("ntp"));
    }

    @Test
    void aMissingItemIsPostedBack() {
        itemsOf("taxrates").removeAll();
        CloudSnapshot.restore(cloud, directory, manifest, line -> {});
        assertTrue(fake.sent.contains(new Sent("POST", "taxrates", json("{\"sysid\": 1, \"rate\": 5}"))));
    }

    @Test
    void aSettingWithNoKnownPutShapeIsReportedNotWritten() {
        ((ObjectNode) fake.data.get("screens").get("config").get("screenConfig")).put("a", 9);
        Map<String, List<String>> left = CloudSnapshot.restore(cloud, directory, manifest, line -> {});
        assertTrue(left.containsKey("screens"));
        assertFalse(fake.sent.stream().anyMatch(s -> !s.method().equals("GET") && s.path().equals("screens")));
    }

    @Test
    void theSettingBodyIsWhatRoundTripsPayloadsAre() {
        // 139309's ntp payload and 139173's fuel tax receipt payload (petroapps).
        assertEquals(json("{\"syncWithNTPServer\": false}"),
                CloudSnapshot.settingBody(json("{\"config\": {\"ntpConfig\": {\"site\": \"CL120\","
                        + " \"syncWithNTPServer\": false}}}")));
        assertEquals(json("{\"line\": [1]}"), CloudSnapshot.settingBody(json(
                "{\"config\": {\"fuelTaxExemptionReceiptConfig\": {\"fuelTaxReceipt\": {\"line\": [1]}}}}")));
    }

    @Test
    void itemsAreFoundAtMostThreeLevelsDown() {
        CloudSnapshot.Items found = CloudSnapshot.itemsAt(json(
                "{\"config\": {\"a\": {\"b\": {\"c\": [{\"sysid\": 2}]}}}}"));
        assertEquals(List.of("a", "b", "c"), found.path());
        assertEquals(1, found.items().size());
        // Deeper lists belong to a setting.
        assertNull(CloudSnapshot.itemsAt(json("{\"config\": {\"a\": {\"b\": {\"c\": {\"d\": [{\"sysid\": 2}]}}}}}")));
        // A list without sysids is not a list of items; an empty one where items would sit is.
        assertNull(CloudSnapshot.itemsAt(json("{\"config\": {\"a\": {\"lines\": [{\"text\": \"x\"}]}}}")));
        assertEquals(List.of("a", "tip"), CloudSnapshot.itemsAt(json("{\"config\": {\"a\": {\"tip\": []}}}")).path());
    }

    @Test
    void theDatasetsAreTheOnesTheSuitesAddress(@TempDir Path suites) throws Exception {
        Files.createDirectories(suites.resolve("a"));
        Files.writeString(suites.resolve("a").resolve("x.feature"),
                "When PATS sends \"POST\" \"pcms/v1/config/{service_id}/fueltaxexemptions\" to \"CSITE\"\n"
                        + "When C-Site is sent \"PUT\" \"pcms/v1/config/{service_id}/tips/1\" with payload \"t.json\"\n",
                StandardCharsets.UTF_8);
        assertEquals(List.of("fueltaxexemptions", "tips"), CloudSnapshot.suiteDatasets(suites));
    }

    @Test
    void restoreNamesTheDatasetsItWrote() {
        ((ObjectNode) itemsOf("taxrates").get(0)).put("rate", 9.9999);
        itemsOf("fueltaxexemptions").add(json("{\"sysid\": 3}"));
        itemsOf("fueltaxexemptions").add(json("{\"sysid\": 4}"));
        itemsOf("tips").add(json("{\"sysid\": 1, \"enabled\": false}"));
        Map<String, Integer> wrote = new HashMap<>();
        List<String> said = new ArrayList<>();
        CloudSnapshot.restore(cloud, directory, manifest, said::add, wrote);
        // By the id the Commander logs; the tip the cloud would not delete is not counted.
        assertEquals(Map.of("taxratecfg", 1, "fueltaxexemptcfg", 2), wrote);
        assertTrue(said.contains("cloud-snapshot: tips +1: HTTP 500"), said.toString());
    }

    @Test
    void everyCallNamesTheService() {
        CloudSnapshot.Cloud other = new CloudSnapshot.Cloud("3964320", (method, endpoint, headers, body) -> {
            assertEquals("pcms/v1/config/3964320/ntp", endpoint);
            assertEquals("3964320", headers.get("X-ServiceId"));
            assertEquals(Set.of("Accept", "X-ServiceId", "Content-Type"), new LinkedHashSet<>(headers.keySet()));
            return new CSiteResponse(204, null, new byte[0], endpoint);
        });
        assertEquals(204, other.call("PUT", "ntp", json("{}")).status());
    }
}
