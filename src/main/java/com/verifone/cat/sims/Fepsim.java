package com.verifone.cat.sims;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Petro Simulator Suite's common FEP simulator (FEPSIM): which one is staged, and its listeners. The Python CAT's
 * {@code cat.sims.fepsim}.
 *
 * <p>Two lines of it exist in petroapps (read 2026-10-12): <b>v1</b> (PETROCORE-138626), whose Buypass mappings have no
 * {@code FileDownload} and no {@code HostTotals}, so it cannot stand in for BUYPASS Host; and <b>v2</b> (branch
 * PETROCORE-142214), with persisted instances loaded at start-up ({@code config/fepsim/instances/<id>.json},
 * {@code autoStart}), the Buypass table, key and day-close mappings, an ArbitraryHostSim rule set, and captures under
 * {@code captures/<instance>/}. It can. Rig owner, 2026-10-12: CAT supports both; with v2 staged, Buypass goes to it and
 * BUYPASS Host is not started; otherwise BUYPASS Host as before. The registry's {@code superseded_by:} names the
 * condition ({@link SimEntry.SupersededBy}).
 *
 * <p>The generation is read from {@code Petro_Simulator_Suite.jar} itself, not from the config folder: a copy's config
 * can be swapped without its jar, and the jar is what runs.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): tested against jars, instance files and capture files a test writes, and
 * a loopback admin API. The Python's own FEPSIM v2 path has not run end to end on the rig either.
 */
public final class Fepsim {

    /** In every FEPSIM jar: the runtime the suite starts the simulator through. */
    static final String V1_CLASS = "com/verifone/isd/pcapps/simulators/fepsim/rt/CommonFEPSimRT.class";
    /** Only in v2: the loader for {@code config/fepsim/instances/}, which CAT's listener relies on. */
    static final String V2_CLASS = "com/verifone/isd/pcapps/simulators/fepsim/comm/client/InstanceRepository.class";

    public static final int V1 = 1;
    public static final int V2 = 2;

    /** v2's {@code fepsim.instances.dir} default, under the suite. */
    public static final Path INSTANCES_DIR = Path.of("config", "fepsim", "instances");
    /** The examples v2 ships beside it; "not loaded" until copied into {@link #INSTANCES_DIR}. */
    static final Path EXAMPLES_DIR = Path.of("config", "fepsim", "instances.examples");
    /** v2's {@code fepsim.capture.dir} default: {@code captures/<instanceId>/<yyyyMMdd>.jsonl}. */
    public static final Path CAPTURES_DIR = Path.of("captures");
    /** Where a listener with a legacy profile writes BUYPASS Host's packet files, per instance. */
    public static final Path LEGACY_PACKETS_DIR = Path.of("legacyPackets");
    /** v2's admin API port when its properties do not say ({@code fepsim.admin.port}). */
    public static final int DEFAULT_ADMIN_PORT = 8099;
    /** HTTPSIM's port when its properties do not say ({@code httpServerPort}). */
    public static final int DEFAULT_WIREMOCK_PORT = 80;

    static final Path PROPERTIES = Path.of("config", "FEPSim_config.properties");
    static final Path HTTPSIM_PROPERTIES = Path.of("config", "HttpSim_config.properties");

    /** The admin calls' bound, as the Python's {@code timeout=10.0}. */
    public static final Duration ADMIN_TIMEOUT = Duration.ofSeconds(10);

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * The request type in a frame's text when FEPSIM leaves {@code messageType} UNKNOWN, as build 533 does for every
     * WEX-OTR and Comdata frame (2026-10-20): WEX-OTR's second pipe-delimited field, Comdata's report number.
     */
    private static final List<Pattern> KINDS = List.of(Pattern.compile("^\\|?([A-Z]{2})\\|"),
            Pattern.compile("(SP\\d{5})/"));

    private Fepsim() {
    }

    /** 0 (no FEPSIM, or no readable jar), {@link #V1} or {@link #V2}, from the classes in {@code jar}. */
    public static int generation(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return 0;
        }
        Set<String> names = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            boolean any = false;
            while ((entry = zip.getNextEntry()) != null) {
                any = true;
                names.add(entry.getName());
            }
            if (!any) {
                return 0; // not a zip at all: ZipInputStream reads such a file as empty
            }
        } catch (IOException | IllegalArgumentException e) {
            return 0;
        }
        if (names.contains(V2_CLASS)) {
            return V2;
        }
        return names.contains(V1_CLASS) ? V1 : 0;
    }

    /**
     * A row step 3 does not start, because {@code host} serves it from FEPSIM {@code instance}.
     *
     * @param needs other controller ports the listener's answers depend on (its suite's WireMock)
     */
    public record Superseded(SimEntry entry, SimEntry host, String instance, int port, int generation, String legacy,
            List<Integer> needs) {

        public Superseded {
            legacy = legacy == null ? "" : legacy;
            needs = List.copyOf(needs == null ? List.of() : needs);
        }

        public String label() {
            return entry.name();
        }

        public String describe() {
            return entry.name() + " — served by " + host.name() + "'s FEP simulator (v" + generation + "), instance "
                    + instance + " on " + port;
        }
    }

    /**
     * A secondary FEP's host, answered by FEPSIM instance {@code instance} in {@code host}'s suite
     * ({@code secondary_feps.yaml}'s {@code fepsim_instance:}): no registry row is replaced, unlike {@link Superseded}.
     */
    public record FepListener(String fep, SimEntry host, String instance, int port, int generation,
            List<Integer> needs) {

        public FepListener {
            needs = List.copyOf(needs == null ? List.of() : needs);
        }

        public String label() {
            return "Secondary FEP " + fep;
        }

        public String describe() {
            return label() + " — answered by " + host.name() + "'s FEP simulator (v" + generation + "), instance "
                    + instance + " on " + port;
        }
    }

    /**
     * The run's rows split three ways: those to start, those superseded, and a note for each row that could have been
     * superseded and was not, saying why.
     */
    public record Split(List<SimEntry> keep, List<Superseded> superseded, List<String> notes) {
        public Split {
            keep = List.copyOf(keep);
            superseded = List.copyOf(superseded);
            notes = List.copyOf(notes);
        }
    }

    /**
     * Splits the run's rows: a row with {@code superseded_by:} is superseded when its {@code simulator} is also in
     * {@code entries} and that row's jar reads as FEPSIM generation {@code fepsim} or later. Otherwise it stays, and a
     * note says why, so a run on an older build shows that BUYPASS Host was chosen and not just that it started.
     * {@code keepLegacy} ({@code LaunchConfig.buypassHost}, {@code --buypass-host}) keeps every such row whatever the
     * jar; the caller then turns the FEPSIM instance off ({@link #retireInstance}), since it would take the same port.
     */
    public static Split supersede(List<SimEntry> entries, SimContext sims, boolean keepLegacy) {
        return supersede(entries, sims::jarFor, keepLegacy);
    }

    /** {@link #supersede(List, SimContext, boolean)} with the jar lookup a test passes. */
    static Split supersede(List<SimEntry> entries, Function<SimEntry, java.util.Optional<Path>> jarFor,
            boolean keepLegacy) {
        Map<String, SimEntry> byName = new LinkedHashMap<>();
        entries.forEach(entry -> byName.put(entry.name(), entry));
        List<SimEntry> keep = new ArrayList<>();
        List<Superseded> gone = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (SimEntry entry : entries) {
            SimEntry.SupersededBy spec = entry.supersededBy();
            if (spec == null) {
                keep.add(entry);
                continue;
            }
            SimEntry host = byName.get(spec.simulator());
            if (host == null) {
                notes.add(entry.name() + ": " + spec.simulator() + " is not in this run; " + entry.name() + " runs");
                keep.add(entry);
                continue;
            }
            int found;
            try {
                found = generation(jarFor.apply(host).orElse(null));
            } catch (IllegalArgumentException e) { // a typo'd placeholder in the host's argv
                found = 0;
            }
            int wanted = spec.fepsim();
            if (found < wanted) {
                String have = found > 0 ? "v" + found : "none";
                notes.add(entry.name() + ": " + host.name() + "'s FEP simulator is " + have + ", it needs v" + wanted
                        + "; " + entry.name() + " runs");
                keep.add(entry);
                continue;
            }
            if (keepLegacy) {
                notes.add(entry.name() + ": " + host.name() + "'s FEP simulator is v" + found
                        + ", but this run asks for " + entry.name() + " (--buypass-host); " + entry.name() + " runs");
                keep.add(entry);
                continue;
            }
            gone.add(new Superseded(entry, host, spec.instance(), spec.readyTcp(), found, spec.legacy(),
                    spec.needsTcp()));
        }
        return new Split(keep, gone, notes);
    }

    private static int intProperty(Path path, String key, int fallback) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.ISO_8859_1);
        } catch (IOException | UncheckedIOException e) {
            return fallback;
        }
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String name = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (name.equals(key) && !value.isEmpty() && value.chars().allMatch(Character::isDigit)) {
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    /** {@code fepsim.admin.port} from the suite's {@code config/FEPSim_config.properties}, else 8099. */
    public static int adminPort(Path suite) {
        return intProperty(suite.resolve(PROPERTIES), "fepsim.admin.port", DEFAULT_ADMIN_PORT);
    }

    /** HTTPSIM's {@code httpServerPort} ({@code config/HttpSim_config.properties}), else 80. */
    public static int wiremockPort(Path suite) {
        return intProperty(suite.resolve(HTTPSIM_PROPERTIES), "httpServerPort", DEFAULT_WIREMOCK_PORT);
    }

    /** The packet files {@code instanceId}'s legacy profile writes: what VALIDATE_PACKETS reads. */
    public static Path legacyPacketsDir(Path suite, String instanceId) {
        return suite.resolve(LEGACY_PACKETS_DIR).resolve(instanceId);
    }

    /**
     * Today's capture file of {@code instanceId}: what {@code emv-keys --host-packets} reads. Append-only, as BUYPASS
     * Host's {@code packetFile.txt} is; the day is the controller's local date (v2 names the file in Java's default
     * time zone), so a run across midnight reads the new day's file.
     */
    public static Path captureFile(Path suite, String instanceId) {
        return captureFile(suite, instanceId, LocalDate.now());
    }

    /** {@code instanceId}'s capture file for {@code day}. */
    public static Path captureFile(Path suite, String instanceId, LocalDate day) {
        return suite.resolve(CAPTURES_DIR).resolve(instanceId).resolve(DAY.format(day) + ".jsonl");
    }

    private static ObjectNode example(Path suite, String instanceId) throws IOException {
        Path file = suite.resolve(EXAMPLES_DIR).resolve(instanceId + ".json");
        JsonNode node = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        if (!(node instanceof ObjectNode object)) {
            throw new IOException(file + " is not a JSON object");
        }
        return object;
    }

    /**
     * The port v2's example {@code instanceId} listens on.
     *
     * @throws NoSuchFileException no such example in this build
     * @throws IllegalArgumentException it names no port
     */
    public static int instancePort(Path suite, String instanceId) throws IOException {
        JsonNode port = example(suite, instanceId).get("port");
        if (port == null || !port.isInt() || port.intValue() <= 0 || port.intValue() >= 65536) {
            throw new IllegalArgumentException(instanceId + ": its example names no port ("
                    + (port == null ? "None" : port.toString()) + ")");
        }
        return port.intValue();
    }

    /**
     * Writes {@code instanceId} into the suite's instances folder, auto-started, from v2's example. The example ships
     * {@code autoStart: false} and an {@code upstream} of placeholders for PROXY mode; CAT runs SIMULATE, so
     * {@code upstream} is dropped. {@code legacy} is the listener's legacy profile ({@code "legacy": {"profile":
     * "BUYPASS"}}), which loads the ArbitraryHostSim rule set and writes {@code legacyPackets/}. Rewritten on every
     * call: the example is the source.
     *
     * @throws NoSuchFileException when the suite ships no such example
     */
    public static Path installInstance(Path suite, String instanceId, String legacy) throws IOException {
        ObjectNode config = example(suite, instanceId);
        config.remove("upstream");
        config.put("enabled", true);
        config.put("autoStart", true);
        config.put("mode", "SIMULATE");
        if (legacy != null && !legacy.isEmpty()) {
            config.putObject("legacy").put("profile", legacy);
        }
        Path target = suite.resolve(INSTANCES_DIR).resolve(instanceId + ".json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, JSON.writeValueAsString(config) + "\n", StandardCharsets.UTF_8);
        return target;
    }

    /** {@link #installInstance(Path, String, String)} with no legacy profile (a secondary FEP's listener). */
    public static Path installInstance(Path suite, String instanceId) throws IOException {
        return installInstance(suite, instanceId, "");
    }

    /**
     * Turns {@code instanceId} off in the suite's instances folder ({@code autoStart} and {@code enabled} false), so
     * the suite does not open its port at start-up. The path when it was on before (a suite already running has the
     * port open and must be restarted to let it go), else null.
     */
    public static Path retireInstance(Path suite, String instanceId) {
        Path target = suite.resolve(INSTANCES_DIR).resolve(instanceId + ".json");
        ObjectNode config;
        try {
            JsonNode node = JSON.readTree(Files.readString(target, StandardCharsets.UTF_8));
            if (!(node instanceof ObjectNode object)) {
                return null;
            }
            config = object;
        } catch (IOException | UncheckedIOException e) {
            return null;
        }
        if (!(truthy(config.get("autoStart")) || truthy(config.get("enabled")))) {
            return null;
        }
        config.put("enabled", false);
        config.put("autoStart", false);
        try {
            Files.writeString(target, JSON.writeValueAsString(config) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        return target;
    }

    private static boolean truthy(JsonNode node) {
        if (node == null || node.isNull()) {
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

    /**
     * Hands {@code instanceId}, as {@link #installInstance} wrote it, to a running suite's admin API. A suite CAT
     * adopted read its instances folder when it started, before this run wrote the instance, so its port stays
     * closed. {@code POST /admin/fep/instances} creates and starts it live; one that exists already (409) is started
     * with {@code PUT .../{id}/start}. Returns what happened, in a few words.
     *
     * @throws IOException on a transport failure or any other status
     */
    public static String registerInstance(String admin, Path suite, String instanceId) throws IOException {
        return registerInstance(admin, suite, instanceId, ADMIN_TIMEOUT);
    }

    /** {@link #registerInstance(String, Path, String)} with its own bound. */
    public static String registerInstance(String admin, Path suite, String instanceId, Duration timeout)
            throws IOException {
        String body = Files.readString(suite.resolve(INSTANCES_DIR).resolve(instanceId + ".json"),
                StandardCharsets.UTF_8);
        String trimmed = admin;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String base = trimmed + "/admin/fep/instances";
        HttpClient http = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<String> created;
        try {
            created = http.send(HttpRequest.newBuilder(URI.create(base)).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            if (created.statusCode() == 200 || created.statusCode() == 201) {
                return "created through " + base;
            }
            if (created.statusCode() == 409) {
                HttpResponse<String> started = http.send(HttpRequest.newBuilder(
                                URI.create(base + "/" + instanceId + "/start")).timeout(timeout)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
                if (started.statusCode() >= 200 && started.statusCode() < 300) {
                    return "started through " + base + "/" + instanceId + "/start";
                }
                created = started;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(base + ": interrupted", e);
        } catch (IOException | IllegalArgumentException e) {
            throw new IOException(base + ": " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
        String text = created.body() == null ? "" : created.body();
        throw new IOException(base + ": HTTP " + created.statusCode() + ": "
                + text.substring(0, Math.min(200, text.length())));
    }

    /** Today's capture file's size, 0 if there is none: a mark {@link #exchanges} counts from. */
    public static long captureSize(Path suite, String instanceId) {
        try {
            return Files.size(captureFile(suite, instanceId));
        } catch (IOException | UncheckedIOException e) {
            return 0L;
        }
    }

    /**
     * The requests the EPS sent {@code instanceId} after byte {@code since} of today's capture file, by message type:
     * the evidence that a FEP was exercised, not just that a sale passed. v2 writes one JSON record per frame, with
     * {@code source} and {@code messageType}; a record from VIPER is a request. A line that is not JSON (a write in
     * progress) is skipped. Sorted by type.
     */
    public static Map<String, Integer> exchanges(Path suite, String instanceId, long since) {
        return exchanges(captureFile(suite, instanceId), since);
    }

    /** {@link #exchanges(Path, String, long)} over one capture file. */
    public static Map<String, Integer> exchanges(Path capture, long since) {
        Map<String, Integer> counted = new TreeMap<>();
        byte[] bytes;
        try (SeekableByteChannel channel = Files.newByteChannel(capture, StandardOpenOption.READ);
                InputStream in = java.nio.channels.Channels.newInputStream(channel.position(Math.max(0, since)))) {
            bytes = in.readAllBytes();
        } catch (IOException | UncheckedIOException e) {
            return counted;
        }
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode record;
            try {
                record = JSON.readTree(line);
            } catch (JsonProcessingException e) {
                continue;
            }
            if (record != null && record.isObject()
                    && "VIPER".equalsIgnoreCase(record.path("source").asText(""))) {
                counted.merge(kind(record), 1, Integer::sum);
            }
        }
        return counted;
    }

    static String kind(JsonNode record) {
        JsonNode type = record.get("messageType");
        String kind = type == null || type.isNull() ? "" : type.asText("");
        if (!kind.isEmpty() && !kind.equalsIgnoreCase("UNKNOWN")) {
            return kind;
        }
        JsonNode textNode = record.get("text");
        String text = textNode == null || textNode.isNull() ? "" : textNode.asText("");
        for (Pattern pattern : KINDS) {
            Matcher match = pattern.matcher(text);
            if (match.find()) {
                return match.group(1);
            }
        }
        return kind.isEmpty() ? "?" : kind;
    }
}
