package com.verifone.cat.commander.connect;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.verifone.cat.devices.csite.CSiteClient;
import com.verifone.cat.devices.csite.CSiteResponse;

/**
 * The Commander Connect golden state: capture, diff and restore of the config datasets the suites address
 * ({@code pcms/v1/config/{service_id}/<name>}). A port of the Python {@code petrotest.cloud_snapshot}, without its
 * command line (whose restore waits on the Commander's log through {@code cloud_settle}).
 *
 * <p>Each dataset is one of three kinds, read from its GET:
 *
 * <ul>
 * <li>a <b>list</b> of items with a {@code sysid}: restored per item. An item the snapshot lacks is deleted (or, where
 * the cloud refuses the delete and the item is {@code enabled}, PUT back off); a changed one is PUT to
 * {@code <name>/<sysid>}; a missing one is POSTed to {@code <name>}.</li>
 * <li>a <b>setting</b>: one object, restored by a PUT of {@link #settingBody} only for {@link #RESTORABLE_SETTINGS},
 * and read back.</li>
 * <li><b>unreadable</b>: the GET answers other than 200. Recorded, never restored.</li>
 * </ul>
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python's capture and diff read live (HANDOFF.md §1.161); its
 * restore's writes, and every call from this port, have not run against Commander Connect.
 */
public final class CloudSnapshot {

    public static final String MANIFEST = "manifest.json";
    public static final int FORMAT = 1;
    /** What every GET carries that is not the configuration. */
    public static final List<String> VOLATILE_KEYS = List.of("datasetVersion", "objectId", "count");
    /** Settings whose PUT body shape a suite shows (Round-Trip's payloads, 2026-10-20). */
    public static final List<String> RESTORABLE_SETTINGS = List.of("ntp", "fueltaxreceipts", "salesloginlogoutmsg");
    /** The Commander's own key in a setting, never in a suite's PUT body. */
    public static final List<String> SITE_KEYS = List.of("site");

    private static final Pattern DATASET = Pattern.compile("pcms/v1/config/\\{service_id\\}/([A-Za-z]+)");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private CloudSnapshot() {}

    /** The C-Site call this module makes, as {@link CSiteClient#send} takes it. */
    @FunctionalInterface
    public interface Send {
        CSiteResponse send(String method, String endpoint, Map<String, String> headers, String body);
    }

    /** A GET's status, and its parsed body, or null when it was not a 200 with JSON. */
    public record Reading(int status, JsonNode body) {}

    /** Where a list's items sit in a GET's config, and the items. */
    public record Items(List<String> path, List<JsonNode> items) {}

    /** One item difference: {@code +} extra now, {@code ~} changed, {@code -} missing now; and the item. */
    record Change(String op, String sysid, JsonNode item) {}

    /** The calls for one service. */
    public static final class Cloud {

        private final String serviceId;
        private final Send send;

        public Cloud(String serviceId, Send send) {
            this.serviceId = Objects.requireNonNull(serviceId, "serviceId");
            this.send = Objects.requireNonNull(send, "send");
        }

        /** A live cloud: {@code client}'s sends, retried as PATS retries them. */
        public static Cloud live(CSiteClient client, String serviceId) {
            return new Cloud(serviceId, (method, endpoint, headers, body) -> client.send(method, endpoint, headers,
                    body, true));
        }

        public String serviceId() {
            return serviceId;
        }

        public CSiteResponse call(String method, String path, JsonNode body) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Accept", "application/json");
            headers.put("X-ServiceId", serviceId);
            if (body != null) {
                headers.put("Content-Type", "application/json");
            }
            return send.send(method, "pcms/v1/config/" + serviceId + "/" + path, headers,
                    body == null ? null : write(body));
        }

        public Reading get(String name) {
            CSiteResponse reply = call("GET", name, null);
            if (reply.status() != 200) {
                return new Reading(reply.status(), null);
            }
            byte[] bytes = reply.body();
            String text = bytes.length == 0 ? "{}" : new String(bytes, StandardCharsets.UTF_8);
            try {
                return new Reading(200, JSON.readTree(text));
            } catch (JsonProcessingException e) {
                return new Reading(reply.status(), null);
            }
        }
    }

    /** Every dataset a {@code .feature} under {@code suites} addresses, sorted. */
    public static List<String> suiteDatasets(Path suites) {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.walk(suites)) {
            for (Path feature : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".feature"))::iterator) {
                Matcher m = DATASET.matcher(Files.readString(feature, StandardCharsets.UTF_8));
                while (m.find()) {
                    names.add(m.group(1));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(names);
    }

    private static JsonNode config(JsonNode body) {
        if (body != null && body.isObject() && body.has("config")) {
            return body.get("config");
        }
        return body;
    }

    /**
     * The path to, and the items of, the first list of {@code sysid} items in a GET's config, at most three levels
     * down (deeper lists belong to a setting: {@code salesloginlogoutmsg}). An empty list where a list would sit reads
     * as an empty list of items. Null when there is none: a setting.
     */
    public static Items itemsAt(JsonNode body) {
        JsonNode config = config(body);
        Items found = walk(config, new ArrayList<>());
        if (found == null && config != null && config.isObject()) {
            List<String> empty = firstEmptyList(config, new ArrayList<>());
            if (empty != null) {
                return new Items(empty, List.of());
            }
        }
        return found;
    }

    private static Items walk(JsonNode node, List<String> path) {
        if (node == null) {
            return null;
        }
        if (node.isArray()) {
            if (node.size() == 0) {
                return null;
            }
            boolean allObjects = true;
            boolean anySysid = false;
            for (JsonNode x : node) {
                allObjects &= x.isObject();
                anySysid |= x.isObject() && x.has("sysid");
            }
            if (allObjects && anySysid) {
                List<JsonNode> items = new ArrayList<>();
                node.forEach(items::add);
                return new Items(List.copyOf(path), items);
            }
            return null;
        }
        if (node.isObject() && path.size() < 3) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> entry = it.next();
                List<String> next = new ArrayList<>(path);
                next.add(entry.getKey());
                Items found = walk(entry.getValue(), next);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static List<String> firstEmptyList(JsonNode node, List<String> path) {
        if (node.isArray() && node.size() == 0) {
            return List.copyOf(path);
        }
        if (node.isObject() && path.size() < 3) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> entry = it.next();
                List<String> next = new ArrayList<>(path);
                next.add(entry.getKey());
                List<String> found = firstEmptyList(entry.getValue(), next);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * A setting's PUT body: its config object, descended through single-key wrappers, without the Commander's own
     * {@code site} ({@code ntp} gives {@code {"syncWithNTPServer": ...}}, {@code fueltaxreceipts} gives
     * {@code {"line": [...]}}, as Round-Trip's 139309/139173 payloads are).
     */
    public static JsonNode settingBody(JsonNode body) {
        JsonNode node = config(body);
        if (node != null && node.isObject() && node.size() == 1) {
            node = node.elements().next();
        }
        while (node != null && node.isObject() && node.size() == 1 && node.elements().next().isObject()) {
            node = node.elements().next();
        }
        if (node != null && node.isObject()) {
            ObjectNode copy = ((ObjectNode) node).deepCopy();
            copy.remove(SITE_KEYS);
            node = copy;
        }
        return node;
    }

    /** Key-sorted JSON without the top level's volatile keys: two values compare equal when this does. */
    static String canonical(JsonNode value) {
        JsonNode node = value;
        if (node != null && node.isObject()) {
            ObjectNode copy = ((ObjectNode) node).deepCopy();
            copy.remove(VOLATILE_KEYS);
            node = copy;
        }
        return write(sorted(node));
    }

    private static JsonNode sorted(JsonNode node) {
        if (node == null) {
            return NODES.nullNode();
        }
        if (node.isObject()) {
            ObjectNode out = NODES.objectNode();
            Set<String> keys = new TreeSet<>();
            node.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                out.set(key, sorted(node.get(key)));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = NODES.arrayNode();
            node.forEach(x -> out.add(sorted(x)));
            return out;
        }
        return node;
    }

    private static String write(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Python's {@code str(item.get("sysid"))}: a number or text as written, {@code None} when absent. */
    static String sysid(JsonNode item) {
        JsonNode id = item == null ? null : item.get("sysid");
        if (id == null || id.isNull()) {
            return "None";
        }
        return id.isTextual() ? id.textValue() : id.toString();
    }

    /** Python truthiness of a JSON value: false, null, 0, "" and empty containers are false. */
    static boolean truthy(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.doubleValue() != 0;
        }
        if (node.isTextual()) {
            return !node.textValue().isEmpty();
        }
        return node.size() > 0;
    }

    /** GETs each dataset into {@code directory}, with a manifest of each one's kind. */
    public static ObjectNode capture(Cloud cloud, Iterable<String> names, Path directory) {
        return capture(cloud, names, directory, Clock.systemUTC());
    }

    /** {@link #capture} at {@code clock}'s time. */
    public static ObjectNode capture(Cloud cloud, Iterable<String> names, Path directory, Clock clock) {
        try {
            Files.createDirectories(directory);
            ObjectNode datasets = NODES.objectNode();
            for (String name : names) {
                Reading reading = cloud.get(name);
                ObjectNode entry = NODES.objectNode();
                if (reading.body() == null) {
                    entry.put("kind", "unreadable");
                    entry.put("status", reading.status());
                    datasets.set(name, entry);
                    continue;
                }
                Files.writeString(directory.resolve(name + ".json"),
                        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(sorted(reading.body())) + "\n",
                        StandardCharsets.UTF_8);
                Items found = itemsAt(reading.body());
                if (found == null) {
                    entry.put("kind", "setting");
                } else {
                    entry.put("kind", "list");
                    ArrayNode path = entry.putArray("path");
                    found.path().forEach(path::add);
                    ArrayNode sysids = entry.putArray("sysids");
                    for (JsonNode item : found.items()) {
                        JsonNode id = item.get("sysid");
                        sysids.add(id == null ? NODES.nullNode() : id);
                    }
                }
                datasets.set(name, entry);
            }
            ObjectNode manifest = NODES.objectNode();
            manifest.put("format", FORMAT);
            manifest.put("service_id", cloud.serviceId());
            manifest.put("captured_at", OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
                    .truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx")));
            manifest.set("datasets", datasets);
            Files.writeString(directory.resolve(MANIFEST),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n", StandardCharsets.UTF_8);
            return manifest;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The snapshot's manifest, or empty when there is none, it does not parse, or it is another format. */
    public static Optional<JsonNode> load(Path directory) {
        try {
            JsonNode manifest = JSON.readTree(Files.readString(directory.resolve(MANIFEST), StandardCharsets.UTF_8));
            if (manifest != null && manifest.path("format").asInt(-1) == FORMAT) {
                return Optional.of(manifest);
            }
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static JsonNode saved(Path directory, String name) {
        try {
            return JSON.readTree(Files.readString(directory.resolve(name + ".json"), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean sameSetting(JsonNode saved, JsonNode now) {
        return canonical(settingBody(saved)).equals(canonical(settingBody(now)));
    }

    /**
     * Per dataset that differs, one line per difference ({@code +3} extra, {@code -3} missing, {@code ~3} changed,
     * {@code setting} changed, {@code unreadable now}). Unreadable snapshots are skipped.
     */
    public static Map<String, List<String>> diff(Cloud cloud, Path directory, JsonNode manifest) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = manifest.path("datasets").fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> entry = it.next();
            String name = entry.getKey();
            String kind = entry.getValue().path("kind").asText();
            if (kind.equals("unreadable")) {
                continue;
            }
            Reading now = cloud.get(name);
            if (now.body() == null) {
                out.put(name, List.of("unreadable now (HTTP " + now.status() + ")"));
                continue;
            }
            JsonNode saved = saved(directory, name);
            if (kind.equals("setting")) {
                if (!sameSetting(saved, now.body())) {
                    out.put(name, List.of("setting"));
                }
                continue;
            }
            List<String> lines = new ArrayList<>();
            for (Change change : itemChanges(saved, now.body())) {
                lines.add(change.op() + change.sysid());
            }
            if (!lines.isEmpty()) {
                out.put(name, lines);
            }
        }
        return out;
    }

    /** ({@code +} extra now / {@code ~} changed / {@code -} missing now, sysid, the item to act with). */
    static List<Change> itemChanges(JsonNode saved, JsonNode now) {
        Map<String, JsonNode> had = bySysid(saved);
        Map<String, JsonNode> has = bySysid(now);
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : has.entrySet()) {
            JsonNode before = had.get(entry.getKey());
            if (before == null) {
                changes.add(new Change("+", sysid(entry.getValue()), entry.getValue()));
            } else if (!canonical(entry.getValue()).equals(canonical(before))) {
                changes.add(new Change("~", sysid(before), before));
            }
        }
        for (Map.Entry<String, JsonNode> entry : had.entrySet()) {
            if (!has.containsKey(entry.getKey())) {
                changes.add(new Change("-", sysid(entry.getValue()), entry.getValue()));
            }
        }
        return changes;
    }

    private static Map<String, JsonNode> bySysid(JsonNode body) {
        Items items = itemsAt(body);
        Map<String, JsonNode> out = new LinkedHashMap<>();
        if (items != null) {
            for (JsonNode item : items.items()) {
                out.put(sysid(item), item);
            }
        }
        return out;
    }

    /** {@link #restore} without counting the writes. */
    public static Map<String, List<String>> restore(Cloud cloud, Path directory, JsonNode manifest,
            Consumer<String> say) {
        return restore(cloud, directory, manifest, say, null);
    }

    /**
     * Puts every differing dataset back; returns what still differs afterwards, per dataset.
     *
     * @param wrote when not null, counts the writes the cloud accepted (2xx), by the dataset id the Commander logs its
     *              push under (the GET's {@code dataset}: {@code departments} is {@code departmentcfg})
     */
    public static Map<String, List<String>> restore(Cloud cloud, Path directory, JsonNode manifest,
            Consumer<String> say, Map<String, Integer> wrote) {
        Map<String, List<String>> left = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = manifest.path("datasets").fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> entry = it.next();
            String name = entry.getKey();
            String kind = entry.getValue().path("kind").asText();
            if (kind.equals("unreadable")) {
                continue;
            }
            Reading now = cloud.get(name);
            if (now.body() == null) {
                left.put(name, List.of("unreadable now (HTTP " + now.status() + ")"));
                continue;
            }
            JsonNode saved = saved(directory, name);
            if (kind.equals("setting")) {
                if (sameSetting(saved, now.body())) {
                    continue;
                }
                if (!RESTORABLE_SETTINGS.contains(name)) {
                    left.put(name, List.of("setting differs (no PUT shape known; left alone)"));
                    continue;
                }
                CSiteResponse reply = cloud.call("PUT", name, settingBody(saved));
                say.accept("cloud-snapshot: " + name + ": PUT " + reply.status());
                accepted(wrote, name, saved, reply.status());
            } else {
                for (Change change : itemChanges(saved, now.body())) {
                    String op = change.op();
                    CSiteResponse reply;
                    if (op.equals("+")) {
                        reply = cloud.call("DELETE", name + "/" + change.sysid(), null);
                        int status = reply.status();
                        boolean gone = status == 200 || status == 204 || status == 404;
                        if (!gone && truthy(change.item()) && truthy(change.item().get("enabled"))) {
                            ObjectNode off = ((ObjectNode) change.item()).deepCopy();
                            off.put("enabled", false);
                            reply = cloud.call("PUT", name + "/" + change.sysid(), off);
                            op = "off";
                        }
                    } else if (op.equals("~")) {
                        reply = cloud.call("PUT", name + "/" + change.sysid(), change.item());
                    } else {
                        reply = cloud.call("POST", name, change.item());
                    }
                    say.accept("cloud-snapshot: " + name + " " + op + change.sysid() + ": HTTP " + reply.status());
                    accepted(wrote, name, saved, reply.status());
                }
            }
            Reading after = cloud.get(name);
            if (after.body() == null) {
                left.put(name, List.of("unreadable after restore"));
            } else if (kind.equals("setting")) {
                if (!sameSetting(saved, after.body())) {
                    left.put(name, List.of("setting still differs"));
                }
            } else {
                List<String> still = new ArrayList<>();
                for (Change change : itemChanges(saved, after.body())) {
                    // A tip the cloud will not delete, PUT back off, is as close as it comes.
                    boolean offExtra = change.op().equals("+") && truthy(change.item())
                            && !truthy(change.item().get("enabled"));
                    if (!offExtra) {
                        still.add(change.op() + change.sysid());
                    }
                }
                if (!still.isEmpty()) {
                    left.put(name, still);
                }
            }
        }
        return left;
    }

    private static void accepted(Map<String, Integer> wrote, String name, JsonNode saved, int status) {
        if (wrote == null || status < 200 || status >= 300) {
            return;
        }
        String dataset = saved != null && saved.isObject() && saved.has("dataset") ? saved.get("dataset").asText()
                : name;
        wrote.merge(dataset, 1, Integer::sum);
    }
}
