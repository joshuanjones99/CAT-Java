package com.verifone.cat.commander.snapshot;

import static com.verifone.cat.commander.snapshot.SnapshotCommander.NS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.commander.CommanderClient;
import com.verifone.cat.commander.CommanderInit;
import com.verifone.cat.commander.Say;
import com.verifone.cat.devices.commander.CgiReplies.CgiFunction;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.provision.transport.InMemoryTransport;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * The Commander snapshot: capture, diff, restore and coverage, through the real {@link PdkShim} and the CLI against
 * a loopback CGILink fake ({@link SnapshotCommander}). The 44 tests of the Python CAT's
 * {@code tests/unit/test_commander_snapshot.py}, one for one, in its order; the snapshots root is a {@code @TempDir}.
 *
 * <p>Evidence about this repository only: the fake answers the way {@code docs/commander-config-surface.md} says the
 * real Commander does (faults as HTTP 200, the OTP minted by the fault), but no Commander has run any of this.
 */
class CommanderSnapshotTest {

    static final String HOST = "commander.testrig.invalid";
    static final String DOMAIN = "urn:vfi-sapphire:np.domain.2001-07-01";
    static final String DOMAIN_ATTR = "xmlns:domain=\"" + DOMAIN + "\"";

    @TempDir
    Path tmp;

    Path root;
    Path configurations;
    SnapshotCommander commander;

    @BeforeEach
    void setUp() throws IOException {
        root = tmp.resolve("Snapshots");
        configurations = tmp.resolve("petroapps").resolve("AutomationTests").resolve("configurations");
        Map<String, List<String>> folders = new LinkedHashMap<>();
        folders.put("base", List.of("fuelcfg.xml", "roleadmin.xml"));
        folders.put("Buypass", List.of("fepcfg&fep=buypass.xml", "fepcfg&fep=incomm.xml"));
        folders.put("Odd", List.of("nopair&fep=x.xml", "rules.xml", "networkcfg.xml"));
        for (Map.Entry<String, List<String>> folder : folders.entrySet()) {
            Path dir = Files.createDirectories(configurations.resolve(folder.getKey()));
            for (String name : folder.getValue()) {
                Files.writeString(dir.resolve(name), "<x/>", StandardCharsets.UTF_8);
            }
        }
        commander = new SnapshotCommander();
    }

    @AfterEach
    void tearDown() {
        commander.close();
    }

    private Path petroapps() {
        return configurations.getParent().getParent();
    }

    private static Capture.Options quiet() {
        return new Capture.Options().pauser(d -> { });
    }

    private Map<String, Object> capture(SnapshotCommander fake, Path out) throws IOException {
        CommanderConfig client = fake.client();
        List<CgiFunction> functions = client.functionList();
        List<String> names = Areas.areasOf(functions, configurations);
        return Capture.capture(client, names, out, quiet().functions(functions));
    }

    private Map<String, Object> capture(Path out) throws IOException {
        return capture(commander, out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static void writeManifest(Path out, Map<String, Object> manifest) throws IOException {
        Files.writeString(out.resolve(Snapshots.MANIFEST), Snapshots.manifestJson(manifest), StandardCharsets.UTF_8);
    }

    private Map<String, Object> legacyIncomplete(Path out) throws IOException {
        Map<String, Object> manifest = capture(out);
        map(manifest.get("areas")).remove("fepcfg&fep=incomm");
        map(manifest.get("failed")).put("fepcfg&fep=incomm", "view failed");
        writeManifest(out, manifest);
        return manifest;
    }

    private static List<String> captureDirs(Path out) throws IOException {
        try (Stream<Path> list = Files.list(out)) {
            return list.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("capture-")).toList();
        }
    }

    // -- the CLI's world: a rig file, streams, and a connector to the fake ------------------------

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    private Map<String, String> env() throws IOException {
        Path rigs = Files.createDirectories(tmp.resolve("rigs"));
        Path file = rigs.resolve("example.yaml");
        if (!Files.exists(file)) {
            Files.writeString(file, String.join("\n",
                    "name: example",
                    "commander:",
                    "  host: " + HOST,
                    "  ssh_user: root",
                    "  eps_port: 41107",
                    "registers:",
                    "  - id: reg1",
                    "    host: r1.invalid",
                    "controller:",
                    "  host: controller.invalid",
                    "pinpad: viperpay",
                    "pos_shim: true",
                    "build:",
                    "  expected_version: \"057.02\"",
                    ""), StandardCharsets.UTF_8);
        }
        Map<String, String> env = new HashMap<>();
        env.put(RigLoader.RIGS_DIR_ENV, rigs.toString());
        env.put(Snapshots.SNAPSHOTS_ROOT_ENV, root.toString());
        env.put(CommanderInit.PETROAPPS_ENV, petroapps().toString());
        return env;
    }

    private int cli(java.util.function.Supplier<Map<String, String>> suite, String... argv) throws IOException {
        return cli(suite, plan -> Optional.empty(), argv);
    }

    private int cli(java.util.function.Supplier<Map<String, String>> suite,
            java.util.function.Function<String, Optional<List<String>>> cases, String... argv) throws IOException {
        CommanderSnapshotCommand command = new CommanderSnapshotCommand(
                (rig, e) -> new CommanderSnapshotCommand.Connection(commander.client(), suite, rig.commander().host()),
                d -> { }, cases);
        return command.run(argv, env(), new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    private String out() {
        String text = outBytes.toString(StandardCharsets.UTF_8);
        outBytes.reset();
        return text;
    }

    // ------------------------------------------------------------------------------------ areas

    /** No hardcoded list: a {@code u} with a {@code v} partner, plus file-named variants of one. */
    @Test
    void areasAreTheFunclistsPairsPlusParameterisedFiles() {
        List<CgiFunction> functions = commander.client().functionList();
        assertEquals(Set.of("vpairingsecret", "upairingsecret"), functions.stream().filter(CgiFunction::otpRequired)
                .map(CgiFunction::cmd).collect(Collectors.toSet()));
        // Not ureportonly (no view), nopair&fep=x (no pair), rules (not in the funcList), nor bare fepcfg: it
        // answers only per FEP (PMC, 2026-10-21).
        assertEquals(List.of("fepcfg&fep=buypass", "fepcfg&fep=incomm", "fuelcfg", "Maintenance", "mysterycfg",
                "networkcfg", "pairingsecret", "taxratecfg"), Areas.areasOf(functions, configurations));
    }

    @Test
    void policyRestoresOnlyReviewedAreas() {
        assertNull(Areas.exclusion("fuelcfg"));
        assertNull(Areas.exclusion("fepcfg&fep=buypass")); // its base's decision
        assertTrue(Areas.exclusion("networkcfg").contains("IP"));
        assertTrue(Areas.exclusion("mysterycfg").startsWith("unreviewed"));
        Set<String> both = new TreeSet<>(Areas.EXCLUDED.keySet());
        both.retainAll(Areas.RESTORABLE);
        assertTrue(both.isEmpty(), both.toString());
    }

    @Test
    void areaFileNamesAreReversible() {
        String name = Areas.areaFile("fepcfg&fep=buypass");
        assertEquals("fepcfg%26fep%3Dbuypass.xml", name);
        assertEquals("fepcfg&fep=buypass", Areas.areaOfFile(name));
    }

    // ---------------------------------------------------------------------------------- capture

    @Test
    void captureWritesEachAreaAndAManifest() throws IOException {
        commander.faulting.add("vmysterycfg");
        Path out = tmp.resolve("snap");
        Map<String, Object> manifest = capture(out);

        assertEquals(manifest, Snapshots.loadManifest(out));
        Map<String, Object> areas = map(manifest.get("areas"));
        assertEquals(commander.state.get("fepcfg&fep=buypass"),
                Files.readString(out.resolve((String) map(areas.get("fepcfg&fep=buypass")).get("file"))));
        assertEquals(Set.of("taxratecfg", "fepcfg&fep=buypass", "fepcfg&fep=incomm", "fuelcfg", "networkcfg",
                "pairingsecret"), areas.keySet());
        // An OTP-gated view: minted by the fault, then sent with the OTP read over "SSH".
        assertTrue(commander.posted.contains("vpairingsecret (otp)"), commander.posted.toString());
        assertEquals(true, map(areas.get("pairingsecret")).get("otp"));
        // An excluded view may fault without invalidating the configuration baseline.
        Map<String, Object> failed = map(manifest.get("failed"));
        assertEquals(Set.of("Maintenance", "mysterycfg"), failed.keySet());
        assertTrue(((String) failed.get("mysterycfg")).contains("InternalError"));
        assertEquals(Set.of("networkcfg", "pairingsecret"), map(manifest.get("excluded")).keySet());
        assertEquals(commander.functions.size(), manifest.get("funclist_commands"));
        assertEquals(List.of(), commander.updates()); // a capture never writes
    }

    @Test
    void maintenanceRequiresADatasetButItsVariantsAreRestorable() {
        assertTrue(Areas.exclusion("Maintenance").contains("dataset"));
        assertNull(Areas.exclusion("Maintenance&dataset=Item"));
    }

    @Test
    void captureRefusesToOverwrite() throws IOException {
        capture(tmp.resolve("snap"));
        assertThrows(FileAlreadyExistsException.class, () -> capture(tmp.resolve("snap")));
    }

    @Test
    void failedRequiredCaptureIsNotPublished() throws IOException {
        commander.faulting.add("vfuelcfg");
        Path out = tmp.resolve("snap");
        Map<String, Object> manifest = capture(out);
        assertTrue(map(manifest.get("failed")).containsKey("fuelcfg"));
        assertNull(Snapshots.loadManifest(out));
        assertEquals(List.of(), captureDirs(out));
    }

    /**
     * Run cbb2f8708cc3's capture faulted on views CAT asked wrongly (coresvcs/PMC DEBUG, 2026-10-21): bare vfepcfg
     * (only per FEP), fepcfg&fep=Chevron (no such FEP), and vfepdetails names FEPs no configurations/ file does.
     */
    @Test
    void theAreasAreWhatThisCommanderAnswers() {
        Map<String, Set<String>> feps = new LinkedHashMap<>();
        feps.put("buypass", Set.of("vfepcfg", "vfepcardtypecfg"));
        feps.put("Comdata", Set.of("vfepcfg"));
        feps.put("incomm", Set.of("vfepcfg", "vfepcardcfg"));
        List<String> names = List.of("fepcfg", "fepcardcfg", "fepcfg&fep=Chevron", "fepcfg&fep=buypass",
                "fepcardcfg&fep=buypass", "fuelcfg");
        Areas.SiteAreas site = Areas.siteAreas(names, feps);
        assertEquals(List.of("fepcardcfg&fep=incomm", "fepcardtypecfg&fep=buypass", "fepcfg&fep=buypass",
                "fepcfg&fep=Comdata", "fepcfg&fep=incomm", "fuelcfg"), site.kept());
        assertEquals(Set.of("fepcfg", "fepcardcfg", "fepcfg&fep=Chevron", "fepcardcfg&fep=buypass"),
                site.dropped().keySet());
        assertTrue(site.dropped().get("fepcfg&fep=Chevron").contains("no FEP Chevron"));
        // With vfepdetails unreadable, only the bare forms go.
        List<String> rest = new ArrayList<>(names.subList(2, names.size()));
        rest.sort(Areas.ORDER);
        assertEquals(rest, Areas.siteAreas(names, null).kept());
    }

    /** vscoregistercfg&regNum=103: "Invalid register 103" (no 103.ver on the Commander). */
    @Test
    void anScoRegisterThisCommanderLacksIsAbsentNotFailed() throws IOException {
        commander.functions.add(new Object[] {"vscoregistercfg", false});
        commander.functions.add(new Object[] {"uscoregistercfg", false});
        commander.faulting.add("vscoregistercfg&regNum=103");
        Map<String, Object> manifest = Capture.capture(commander.client(), List.of("fuelcfg",
                "scoregistercfg&regNum=103"), tmp.resolve("snap"), quiet());
        assertTrue(map(manifest.get("absent")).containsKey("scoregistercfg&regNum=103"));
        assertTrue(map(manifest.get("failed")).isEmpty());
        assertNotNull(Snapshots.loadManifest(tmp.resolve("snap")));
    }

    @Test
    void plusAreNotAConfigurationView() {
        String why = Areas.exclusion("PLUs");
        assertTrue(why.contains("PLUSelect") || why.contains("PLU database"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"view", "publish"})
    void failedReplacementPreservesPreviousSnapshot(String failure) throws IOException {
        Path out = tmp.resolve("snap");
        Map<String, Object> original = capture(out);
        byte[] before = Files.readAllBytes(out.resolve(Snapshots.MANIFEST));
        Map<String, byte[]> contents = new LinkedHashMap<>();
        for (Object entry : map(original.get("areas")).values()) {
            String file = (String) map(entry).get("file");
            contents.put(file, Files.readAllBytes(out.resolve(file)));
        }
        commander.state.put("fuelcfg", commander.state.get("fuelcfg").replace("S1", "S2"));
        CommanderConfig client = commander.client();
        List<CgiFunction> functions = client.functionList();
        List<String> names = Areas.areasOf(functions, configurations);
        if (failure.equals("view")) {
            commander.faulting.add("vfuelcfg");
            Capture.capture(client, names, out, quiet().overwrite(true));
        } else {
            Capture.Options interrupted = quiet().overwrite(true).publisher((pending, manifest) -> {
                throw new IOException("publication interrupted");
            });
            IOException e = assertThrows(IOException.class, () -> Capture.capture(client, names, out, interrupted));
            assertEquals("publication interrupted", e.getMessage());
        }
        assertEquals(new String(before, StandardCharsets.UTF_8),
                Files.readString(out.resolve(Snapshots.MANIFEST), StandardCharsets.UTF_8));
        for (Map.Entry<String, byte[]> entry : contents.entrySet()) {
            assertEquals(new String(entry.getValue(), StandardCharsets.UTF_8),
                    Files.readString(out.resolve(entry.getKey()), StandardCharsets.UTF_8));
        }
        assertEquals(1, captureDirs(out).size());
    }

    @Test
    void successfulReplacementKeepsPreviousGenerationReadable() throws IOException {
        Path out = tmp.resolve("snap");
        Map<String, Object> original = capture(out);
        String oldFile = (String) map(map(original.get("areas")).get("fuelcfg")).get("file");
        String text = Files.readString(out.resolve(oldFile));
        commander.state.put("fuelcfg", commander.state.get("fuelcfg").replace("S1", "S2"));
        Map<String, Object> current = Capture.capture(commander.client(), new ArrayList<>(commander.state.keySet()),
                out, quiet().overwrite(true));
        assertEquals(current, Snapshots.loadManifest(out));
        assertNotEquals(oldFile, map(map(current.get("areas")).get("fuelcfg")).get("file"));
        assertEquals(text, Files.readString(out.resolve(oldFile)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "missing", "changed", "malformed"})
    void restoreRejectsADamagedBaselineButRestoresAroundAnUncapturedArea(String damage) throws IOException {
        Path out = tmp.resolve("snap");
        Map<String, Object> manifest = capture(out);
        Path path = out.resolve((String) map(map(manifest.get("areas")).get("taxratecfg")).get("file"));
        switch (damage) {
            case "failed" -> {
                map(manifest.get("areas")).remove("taxratecfg");
                map(manifest.get("failed")).put("taxratecfg", "capture faulted");
            }
            case "missing" -> Files.delete(path);
            case "changed" -> Files.writeString(path, "<changed/>", StandardCharsets.UTF_8);
            default -> manifest.put("requested_areas", null);
        }
        writeManifest(out, manifest);
        commander.state.put("fuelcfg", commander.state.get("fuelcfg").replace("S1", "S2"));
        Restore.RestoreReport report = Restore.restore(commander.client(), out, line -> { });
        assertFalse(report.ok());
        assertFalse(report.failed().isEmpty());
        if (damage.equals("failed")) { // that area's failure; the others are still put back
            assertEquals(List.of("taxratecfg"), new ArrayList<>(report.failed().keySet()));
            assertEquals(List.of("ufuelcfg"), commander.updates());
        } else {
            assertEquals(List.of(), commander.updates());
        }
    }

    @Test
    void legacyFlatSnapshotRemainsRestorable() throws IOException {
        Path out = tmp.resolve("snap");
        Map<String, Object> manifest = capture(out);
        for (Map.Entry<String, Object> area : map(manifest.get("areas")).entrySet()) {
            Map<String, Object> entry = map(area.getValue());
            Files.write(out.resolve(Areas.areaFile(area.getKey())),
                    Files.readAllBytes(out.resolve((String) entry.get("file"))));
            entry.put("file", Areas.areaFile(area.getKey()));
            entry.remove("content_sha256");
        }
        manifest.remove("requested_areas");
        writeManifest(out, manifest);
        commander.state.put("fuelcfg", commander.state.get("fuelcfg").replace("S1", "S2"));
        assertTrue(Restore.restore(commander.client(), out, line -> { }).ok());
        assertEquals(List.of("ufuelcfg"), commander.updates());
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "missing"})
    void ifMissingRepairsAnUnusableExistingSnapshot(String damage) throws IOException {
        Path out = tmp.resolve("snap");
        if (damage.equals("failed")) {
            legacyIncomplete(out);
        } else {
            Map<String, Object> manifest = capture(out);
            Files.delete(out.resolve((String) map(map(manifest.get("areas")).get("fuelcfg")).get("file")));
        }
        byte[] original = Files.readAllBytes(out.resolve(Snapshots.MANIFEST));
        String[] argv = {"capture", "--rig", "example", "--dir", out.toString(), "--base-level", "test",
                "--petroapps", petroapps().toString(), "--if-missing"};
        commander.faulting.add("vtaxratecfg");
        assertEquals(1, cli(() -> Map.of("Base_Level", "test"), argv));
        assertEquals(new String(original, StandardCharsets.UTF_8), Files.readString(out.resolve(Snapshots.MANIFEST)));
        commander.faulting.remove("vtaxratecfg");
        assertEquals(0, cli(() -> Map.of("Base_Level", "test"), argv));
        assertTrue(Capture.snapshotErrors(out, Snapshots.loadManifest(out)).isEmpty());
        assertNotEquals(new String(original, StandardCharsets.UTF_8),
                Files.readString(out.resolve(Snapshots.MANIFEST)));
    }

    @Test
    void captureTwiceReportsWhatChangedOnItsOwn() throws IOException {
        AtomicInteger views = new AtomicInteger();
        Pattern stamp = Pattern.compile("stamp=\"\\d+\"");
        commander.beforeEach = command -> { // the Commander's own clock, between two views
            if (command.equals("vtaxratecfg")) {
                int n = views.incrementAndGet();
                commander.state.put("taxratecfg",
                        stamp.matcher(commander.state.get("taxratecfg")).replaceAll("stamp=\"" + n + "\""));
            }
        };
        List<Duration> waited = new ArrayList<>();
        Map<String, Object> manifest = Capture.capture(commander.client(), List.of("taxratecfg", "fuelcfg"),
                tmp.resolve("snap"), new Capture.Options().twice(Duration.ofSeconds(1)).pauser(waited::add));
        assertEquals(List.of(Duration.ofSeconds(1)), waited);
        Map<String, Object> found = map(manifest.get("volatile"));
        assertEquals(List.of("taxratecfg"), new ArrayList<>(found.keySet()));
        @SuppressWarnings("unchecked")
        List<String> lines = (List<String>) found.get("taxratecfg");
        assertTrue(lines.stream().anyMatch(line -> line.contains("@stamp")), lines.toString());
    }

    // ------------------------------------------------------------------------- normalise / diff

    @Test
    void normaliseIgnoresLayoutPrefixesAndAttributeOrder() {
        String a = "<domain:x " + NS + " b=\"2\" a=\"1\">\n  <y>v</y>\n</domain:x>";
        String b = "<ns0:x xmlns:ns0=\"" + DOMAIN + "\" a=\"1\" b=\"2\"><y>v</y></ns0:x>";
        assertEquals(Normalise.normalise("fuelcfg", a), Normalise.normalise("fuelcfg", b));
        assertNotEquals(Normalise.normalise("fuelcfg", a), Normalise.normalise("fuelcfg", a.replace(">v<", "> v<")));
        assertEquals(Normalise.normalise("PLUs", "<a>\n<b>"), Normalise.normalise("PLUs", "<a><b>")); // not XML
    }

    @Test
    void diffFindsAChangedAreaAndIgnoresAVolatilePath() throws IOException {
        Path out = tmp.resolve("snap");
        capture(out);
        commander.state.put("fuelcfg", commander.state.get("fuelcfg").replace("collect=\"0\"", "collect=\"1\""));
        commander.state.put("taxratecfg", commander.state.get("taxratecfg").replace("stamp=\"1\"", "stamp=\"2\""));

        Set<String> before = Restore.diff(commander.client(), out).stream().filter(d -> d.status().equals("different"))
                .map(Restore.AreaDiff::area).collect(Collectors.toSet());
        assertEquals(Set.of("fuelcfg", "taxratecfg"), before);

        Rules rules = Rules.DEFAULT.withVolatile("taxratecfg", "taxRateConfig@stamp");
        Map<String, Restore.AreaDiff> after = new HashMap<>();
        for (Restore.AreaDiff d : Restore.diff(commander.client(), out, null, false, false, rules)) {
            after.put(d.area(), d);
        }
        assertEquals("same", after.get("taxratecfg").status());
        assertEquals("different", after.get("fuelcfg").status());
        assertTrue(after.get("fuelcfg").detail().contains("-fuelConfig/fuel@collect=0"), after.get("fuelcfg").toString());
        assertTrue(after.get("fuelcfg").detail().contains("+fuelConfig/fuel@collect=1"));
        assertFalse(after.containsKey("networkcfg")); // excluded: not even compared unless asked
    }

    // ---------------------------------------------------------------------------------- restore

    @Test
    void restoreSendsOnlyDifferingRestorableAreasAndVerifies() throws IOException {
        Path out = tmp.resolve("snap");
        capture(out);
        String goldenFuel = commander.state.get("fuelcfg");
        commander.state.put("fuelcfg", goldenFuel.replace("collect=\"0\"", "collect=\"1\""));
        commander.state.put("networkcfg", commander.state.get("networkcfg").replace("lan", "other"));
        commander.state.put("mysterycfg", commander.state.get("mysterycfg").replace("1", "2"));
        commander.posted.clear();

        Restore.RestoreReport report = Restore.restore(commander.client(), out, line -> { });

        assertTrue(report.ok());
        assertEquals(List.of("fuelcfg"), report.restored());
        assertEquals(List.of("ufuelcfg"), commander.updates()); // never networkcfg, never mysterycfg
        assertEquals(goldenFuel, commander.state.get("fuelcfg"));
        assertTrue(commander.state.get("networkcfg").contains("other"));
        // And it looked again after the update, to confirm.
        int at = commander.posted.indexOf("ufuelcfg");
        assertTrue(at < commander.posted.size() - 1);
        assertEquals("vfuelcfg", commander.posted.get(at + 1));
    }

    @Test
    void restoreReportsAnAreaTheCommanderWillNotTake() throws IOException {
        Path out = tmp.resolve("snap");
        capture(out);
        commander.state.put("fuelcfg", commander.state.get("fuelcfg").replace("<grade>1", "<grade>2"));
        commander.ignoredUpdates.add("fuelcfg");
        commander.posted.clear();

        Restore.RestoreReport report = Restore.restore(commander.client(), out, line -> { });

        assertFalse(report.ok());
        assertEquals(Set.of("fuelcfg"), report.stillDifferent().keySet());
        assertEquals(java.util.Collections.nCopies(Restore.RESTORE_PASSES, "ufuelcfg"), commander.updates());
    }

    /** {@code commander-snapshot} end to end, the rig file resolved for real. */
    @Test
    void cliCaptureDiffAndRestore() throws IOException {
        Map<String, String> suite = Map.of("Base_Level", "057.02.00", "Commit_Hash", "abc");
        String petroapps = petroapps().toString();
        assertEquals(0, cli(() -> suite, "commander-snapshot", "capture", "--rig", "example", "--petroapps", petroapps),
                errBytes.toString(StandardCharsets.UTF_8));
        Path directory = Snapshots.buildDir("057.02.00", Map.of("Commit_Hash", "abc"), root);
        assertEquals(Snapshots.snapshotDir("057.02.00", root).resolve("abc"), directory); // one folder per build
        Map<String, Object> manifest = Snapshots.loadManifest(directory);
        assertNotNull(manifest);
        assertEquals("057.02.00", manifest.get("base_level"));
        assertEquals("abc", map(manifest.get("suiteinfo")).get("Commit_Hash"));
        assertEquals(Capture.folderDigest(configurations.resolve("base")), manifest.get("petroapps_base_digest"));

        assertEquals(0, cli(() -> suite, "commander-snapshot", "diff", "--rig", "example"));
        // Step 4 after every fresh install: an existing snapshot is left as it is.
        String before = Files.readString(directory.resolve(Snapshots.MANIFEST));
        assertEquals(0, cli(() -> suite, "commander-snapshot", "capture", "--rig", "example", "--petroapps",
                petroapps, "--if-missing"));
        assertEquals(before, Files.readString(directory.resolve(Snapshots.MANIFEST)));
        commander.state.put("fuelcfg", commander.state.get("fuelcfg").replace("S1", "S2"));
        assertEquals(1, cli(() -> suite, "commander-snapshot", "diff", "--rig", "example"));
        out();
        assertEquals(0, cli(() -> suite, "commander-snapshot", "restore", "--rig", "example"));
        assertTrue(out().contains("restored fuelcfg"));
        assertEquals(0, cli(() -> suite, "commander-snapshot", "diff", "--rig", "example"));
    }

    // --------------------------------------------------------------------------------- coverage

    @Test
    void coverageFlagsAnAreaAPlanImportsThatCannotBeRestored() throws IOException {
        Path out = tmp.resolve("snap");
        legacyIncomplete(out);
        Map<String, List<String>> folders = Map.of("K1", List.of("Buypass"), "K2", List.of("Odd", "Gone"),
                "K3", List.of("base"));

        Coverage.Report report = Coverage.coverage(out, configurations, Map.of("P", List.of("K1", "K2", "K3")),
                folders::get);

        Map<String, Coverage.Row> rows = new HashMap<>();
        report.rows().forEach(r -> rows.put(r.area(), r));
        assertTrue(rows.get("fepcfg&fep=buypass").ok());
        assertEquals(List.of("P:K1"), rows.get("fepcfg&fep=buypass").cases());
        assertFalse(rows.get("fepcfg&fep=incomm").ok()); // its view faulted at capture
        assertTrue(rows.get("fepcfg&fep=incomm").status().startsWith("capture failed"));
        assertFalse(rows.get("rules").ok()); // no v/u pair: the snapshot has nothing to put back
        assertTrue(rows.get("networkcfg").status().startsWith("excluded:"));
        assertTrue(rows.get("roleadmin").ok()); // excluded, but base re-imports it every run
        assertEquals(Set.of("fepcfg&fep=incomm", "networkcfg", "nopair&fep=x", "rules"),
                report.blocking().stream().map(Coverage.Row::area).collect(Collectors.toSet()));
        assertEquals(Map.of("P:K2", List.of("Gone")), report.missingFolders());
    }

    @Test
    void cliCoverageReadsThePlansFeatureFiles() throws IOException {
        Path out = tmp.resolve("snap");
        legacyIncomplete(out);
        Path suites = tmp.resolve("repo").resolve("tests").resolve("suites");
        Path demo = Files.createDirectories(suites.resolve("demo"));
        Files.writeString(demo.resolve("PETROCORE-1-TestCase.feature"),
                "Feature: x\n  Scenario: y\n    When the Commander imports configuration \"Buypass\"\n",
                StandardCharsets.UTF_8);
        String[] argv = {"commander-snapshot", "coverage", "--dir", out.toString(), "--plans", "PETROCORE-9",
                "--petroapps", petroapps().toString(), "--suites", suites.toString()};
        java.util.function.Function<String, Optional<List<String>>> cases = plan -> Optional.of(List.of("PETROCORE-1"));

        assertEquals(1, cli(() -> Map.of(), cases, argv)); // incomm failed at capture
        String printed = out();
        assertTrue(Pattern.compile("GAP fepcfg&fep=incomm\\s+1 case").matcher(printed).find(), printed);
        assertTrue(Pattern.compile("ok  fepcfg&fep=buypass\\s+1 case").matcher(printed).find(), printed);

        Files.delete(configurations.resolve("Buypass").resolve("fepcfg&fep=incomm.xml"));
        assertEquals(0, cli(() -> Map.of(), cases, argv));
    }

    // ----------------------------------------------------------------------------------- step 4

    private Rig rig() throws IOException {
        env();
        return RigLoader.load("example", tmp.resolve("rigs"));
    }

    /** {@code commander-init} with Petro off, the real PdkShim on the fake, and the restore under {@link #root}. */
    private List<String> init(Map<String, String> suite, boolean snapshot, Map<String, String> environ,
            Say.Recorded say) throws IOException {
        Path base = Files.createDirectories(tmp.resolve("base"));
        Files.writeString(base.resolve("fuelcfg.xml"), commander.state.get("fuelcfg"), StandardCharsets.UTF_8);
        CommanderAccess access = new CommanderAccess(rig(), target -> new InMemoryTransport(target.host()), environ);
        CommanderInit init = CommanderInit.builder(access)
                .clients(() -> CommanderClient.of(commander.shim()))
                .snapshots((folder, line) -> Restore.restoreForRig(root, () -> suite, commander.client(), folder,
                        line, Rules.DEFAULT))
                .pauser(d -> { })
                .petroLog(() -> tmp.resolve("no-petro.log"))
                .pumps(pump -> null)
                .say(say)
                .build();
        assertTrue(init.run(base, false, snapshot), say.errText());
        return commander.postedWithoutLogins();
    }

    private List<String> init(Say.Recorded say) throws IOException {
        return init(Map.of("Base_Level", "057.02.00"), true, Map.of(), say);
    }

    @Test
    void step4RestoresNothingWithoutASnapshot() throws IOException {
        Say.Recorded say = new Say.Recorded();
        assertEquals(List.of("ufuelcfg", "crefreshcfg"), init(say));
        assertTrue(say.outText().contains("no Commander snapshot under"), say.outText());
    }

    @Test
    void step4RestoresTheSnapshotBeforeTheBaseImport() throws IOException {
        capture(Snapshots.snapshotDir("057.02.00", root));
        String golden = commander.state.get("mysterycfg");
        commander.state.put("taxratecfg", commander.state.get("taxratecfg").replace("primary", "leaked"));
        commander.posted.clear();

        Say.Recorded say = new Say.Recorded();
        List<String> posted = init(say);

        assertTrue(posted.indexOf("utaxratecfg") >= 0, posted.toString());
        assertTrue(posted.indexOf("utaxratecfg") < posted.indexOf("ufuelcfg"), posted.toString());
        assertTrue(posted.indexOf("ufuelcfg") < posted.indexOf("crefreshcfg"), posted.toString());
        assertTrue(commander.state.get("taxratecfg").contains("primary"));
        assertEquals(golden, commander.state.get("mysterycfg"));
        assertTrue(say.outText().contains("snapshot: restored taxratecfg"), say.outText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"flag", "env"})
    void step4SnapshotSwitch(String which) throws IOException {
        capture(Snapshots.snapshotDir("057.02.00", root));
        commander.state.put("taxratecfg", commander.state.get("taxratecfg").replace("primary", "leaked"));
        commander.posted.clear();
        Map<String, String> environ = which.equals("env") ? Map.of(CommanderInit.NO_SNAPSHOT_ENV, "1") : Map.of();

        List<String> posted = init(Map.of("Base_Level", "057.02.00"), !which.equals("flag"), environ,
                new Say.Recorded());

        assertEquals(List.of("ufuelcfg", "crefreshcfg"), posted);
        assertTrue(commander.state.get("taxratecfg").contains("leaked"));
    }

    /**
     * The Python's {@code commander-init --no-snapshot} flag. CAT-Java has no {@code commander-init} command line
     * yet; the flag's meaning is {@code CommanderInit.run}'s {@code snapshot=false}, said in one line.
     */
    @Test
    void commanderInitTakesNoSnapshot() throws IOException {
        Say.Recorded say = new Say.Recorded();
        init(Map.of("Base_Level", "057.02.00"), false, Map.of(), say);
        assertTrue(say.outText().contains("Commander snapshot not restored (--no-snapshot)"), say.outText());
    }

    /** Run 3bc049ab4919: these read back after their own update differing only so. */
    @Test
    void anUpdateStampAndEmvinitsPopOrderAreNotDifferences() {
        String a = "<panelCfg creationDate=\"2026-10-15T14:46:36-04:00\"><panel id=\"1\"/></panelCfg>";
        String b = "<panelCfg creationDate=\"2026-10-20T00:42:00-04:00\"><panel id=\"1\"/></panelCfg>";
        assertEquals(Normalise.normalise("panelcfg", a), Normalise.normalise("panelcfg", b));
        assertNotEquals(Normalise.normalise("panelcfg", a), Normalise.normalise("panelcfg", a.replace("\"1\"", "\"2\"")));
        String pops = Stream.of(114, 115, 116).map(n -> "<availablePOP>" + n + "</availablePOP>")
                .collect(Collectors.joining());
        String swapped = Stream.of(116, 115, 114).map(n -> "<availablePOP>" + n + "</availablePOP>")
                .collect(Collectors.joining());
        String view = "<popEMVInitialize><availablePOPEntries>%s</availablePOPEntries></popEMVInitialize>";
        assertEquals(Normalise.normalise("emvinit", String.format(view, pops)),
                Normalise.normalise("emvinit", String.format(view, swapped)));
    }

    /**
     * 2026-10-20: Solutions 125's capture against 134 live: the same configModule entries, {@code dcrChannel11E}
     * moved. A changed module still differs.
     */
    @Test
    void managedModulesInAnotherOrderAreNotADifference() {
        String mod = "<configModule><moduleName>%s</moduleName><desc>%s</desc></configModule>";
        String a = String.format(mod, "dcrChannel11E", "Emulation") + String.format(mod, "dcrChannel11B", "Bennett");
        String b = String.format(mod, "dcrChannel11B", "Bennett") + String.format(mod, "dcrChannel11E", "Emulation");
        String view = "<moduleConfigurations><applicationModules/>%s</moduleConfigurations>";
        assertEquals(Normalise.normalise("managedmodulecfg", String.format(view, a)),
                Normalise.normalise("managedmodulecfg", String.format(view, b)));
        String changed = String.format(view, b.replace("Bennett", "Gilbarco"));
        assertNotEquals(Normalise.normalise("managedmodulecfg", String.format(view, a)),
                Normalise.normalise("managedmodulecfg", changed));
    }

    static String naxml(String action, String... codes) {
        StringBuilder items = new StringBuilder();
        for (String c : codes) {
            items.append("<ITTDetail><POSCode>").append(c).append("</POSCode></ITTDetail>");
        }
        return "<NAXML-MaintenanceRequest><ItemMaintenance><TableAction type=\"" + action + "\"/>" + items
                + "</ItemMaintenance></NAXML-MaintenanceRequest>";
    }

    @Test
    void aMaintenanceDatasetIsRestoredWithInitializeAndNothingElseIs() {
        String sent = Restores.restoreDocument("Maintenance&dataset=Item", naxml("update", "1"));
        assertTrue(sent.contains("TableAction type=\"initialize\"") && !sent.contains("type=\"update\""));
        assertEquals(naxml("update", "1"), Restores.restoreDocument("fuelcfg", naxml("update", "1")));
    }

    /**
     * NAXML's own semantics: {@code update} keeps every record it does not name, {@code initialize} replaces the
     * table; the view always reads {@code update}, as the lab Commander's does.
     */
    static final class NaxmlCommander extends SnapshotCommander {
        @Override
        String answer(String body) {
            Head head = head(body);
            String raw = body.split("\n\n", 2)[0];
            if (raw.startsWith("cmd=uMaintenance") && head.payload().contains("type=\"initialize\"")) {
                String area = "Maintenance&dataset=" + raw.split("dataset=")[1].split("&")[0];
                posted.add("uMaintenance&dataset=" + area.split("=")[1]);
                state.put(area, head.payload().replace("type=\"initialize\"", "type=\"update\""));
                return OK;
            }
            if (raw.startsWith("cmd=uMaintenance") && head.payload().contains("type=\"update\"")) {
                String area = "Maintenance&dataset=" + raw.split("dataset=")[1].split("&")[0];
                String old = state.get(area);
                StringBuilder kept = new StringBuilder();
                for (String c : List.of("100373")) {
                    if (old.contains(c) && !head.payload().contains(c)) {
                        kept.append("<ITTDetail><POSCode>").append(c).append("</POSCode></ITTDetail>");
                    }
                }
                posted.add("uMaintenance&dataset=" + area.split("=")[1]);
                state.put(area, head.payload().replace("</ItemMaintenance>", kept + "</ItemMaintenance>"));
                return OK;
            }
            return super.answer(body);
        }
    }

    /** Run 9e29cf0d56f2 (2026-10-20): {@code update} left PLU-Liquor12 (100373) on every restore. */
    @Test
    void aPlansExtraItemIsRemovedAndEveryMaintenanceDatasetFollows() throws IOException {
        try (NaxmlCommander naxml = new NaxmlCommander()) {
            naxml.faulting.clear(); // bare vMaintenance faults; these carry a dataset
            List<String> datasets = List.of("Item", "ItemList", "Combo", "MixMatch");
            for (String name : datasets) {
                String area = "Maintenance&dataset=" + name;
                naxml.state.put(area, naxml("update", "17"));
                Files.writeString(configurations.resolve("base").resolve(area + ".xml"), "<x/>",
                        StandardCharsets.UTF_8);
            }
            Path out = tmp.resolve("snap");
            capture(naxml, out);
            naxml.state.put("Maintenance&dataset=Item", naxml("update", "17", "100373"));
            naxml.posted.clear();

            Restore.RestoreReport report = Restore.restore(naxml.client(), out, line -> { });

            assertTrue(report.ok(), report.stillDifferent().toString() + report.failed());
            assertFalse(naxml.state.get("Maintenance&dataset=Item").contains("100373"));
            assertEquals(datasets.stream().map(n -> "uMaintenance&dataset=" + n).toList(),
                    naxml.updates().stream().filter(u -> u.contains("Maintenance")).toList());
        }
    }

    /** Run 9e29cf0d56f2 (2026-10-20): what an SCO configuration put in these stayed. */
    @Test
    void upsertOnlyAreasAreEmptiedBeforeTheSnapshotIsSent() {
        String cash = "<?xml version=\"1.0\"?>\n<domain:cashRecyclerCfg " + DOMAIN_ATTR + ">"
                + "<site>CL120</site></domain:cashRecyclerCfg>";
        List<String> sent = Restores.restoreDocuments("cashrecyclercfg", cash);
        assertEquals(List.of(cash.replace("</site>",
                "</site><operation type=\"empty\"><cashRecycler id=\"1\"/></operation>")), sent);
        String image = "<domain:imageConfig " + DOMAIN_ATTR + "><site>CL120</site><image name=\"a\"/>"
                + "</domain:imageConfig>";
        sent = Restores.restoreDocuments("imagecfg", image);
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("</site><operation type=\"empty\"/><image name=\"a\"/>"));
        // The operation never closes there, so the empty is a document of its own.
        String prefs = "<?xml version=\"1.0\"?>\n<domain:empPrefsCfg " + DOMAIN_ATTR + "/>";
        sent = Restores.restoreDocuments("empprefcfg", prefs);
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).endsWith("<domain:empPrefsCfg " + DOMAIN_ATTR + "><operation type=\"empty\"/>"
                + "<empPrefs sysid=\"1\"><virtualKeyboardCoordinates deviceType=\"topaz\"/></empPrefs>"
                + "</domain:empPrefsCfg>"), sent.get(0));
        String kept = "<domain:empPrefsCfg " + DOMAIN_ATTR + "><empPrefs sysid=\"2\"/></domain:empPrefsCfg>";
        assertEquals(kept, Restores.restoreDocuments("empprefcfg", kept).get(1));
        assertEquals(List.of(cash), Restores.restoreDocuments("fuelcfg", cash));
    }

    /** Run 201c8f66f8c4 (2026-10-20): Menu's menus, panels and PLU promotions stayed. */
    @Test
    void menusPanelsScreensAndPluPromosAreEmptiedBeforeTheSnapshotIsSent() {
        String menus = "<domain:menuConfig " + DOMAIN_ATTR + "><site>CL120</site><expandedMenu/></domain:menuConfig>";
        assertTrue(only(Restores.restoreDocuments("menucfg", menus))
                .contains("</site><operation type=\"empty\"/><expandedMenu/>"));
        for (String area : List.of("panelcfg", "screencfgv2")) {
            assertTrue(only(Restores.restoreDocuments(area, "<sc:x xmlns:sc=\"u\"><site>CL120</site><a/></sc:x>"))
                    .contains("</site><operation type=\"empty\"/><a/>"));
        }
        // The restrictions handler ignores a top-level empty: it goes inside the table.
        String rules = "<domain:restrictionsConfig " + DOMAIN_ATTR + "><site>CL120</site><ageValidations/>"
                + "<pluPromos><pluPromo/></pluPromos></domain:restrictionsConfig>";
        assertTrue(only(Restores.restoreDocuments("restrictionscfg", rules))
                .contains("<ageValidations/><pluPromos><operation type=\"empty\"/><pluPromo/>"));
        String promos = "<domain:pluPromoConfig " + DOMAIN_ATTR + "><site>CL120</site><pluPromos/>"
                + "</domain:pluPromoConfig>";
        assertTrue(only(Restores.restoreDocuments("plupromocfg", promos))
                .contains("<pluPromos><operation type=\"empty\"/></pluPromos></domain:pluPromoConfig>"));
        String bare = "<domain:pluPromoConfig " + DOMAIN_ATTR + "><site>CL120</site></domain:pluPromoConfig>";
        assertTrue(only(Restores.restoreDocuments("plupromocfg", bare))
                .contains("</site><pluPromos><operation type=\"empty\"/></pluPromos>"));
    }

    private static String only(List<String> documents) {
        assertEquals(1, documents.size(), documents.toString());
        return documents.get(0);
    }

    @Test
    void tlssiteCannotBeDeletedSoItsDefaultsReadAsTheEmptyFreshInstall() {
        String fresh = "<domain:tlsSiteInfo " + DOMAIN_ATTR + "><site>CL120</site></domain:tlsSiteInfo>";
        String sent = only(Restores.restoreDocuments("tlssite", fresh));
        assertTrue(sent.contains("<unitOfMeasure>G</unitOfMeasure><tankVariance>0</tankVariance>"
                + "<lowLimitAlarm>0</lowLimitAlarm>"), sent);
        assertEquals(Normalise.normalise("tlssite", fresh), Normalise.normalise("tlssite", sent));
        String changed = sent.replace("<tankVariance>0<", "<tankVariance>5<");
        assertNotEquals(Normalise.normalise("tlssite", fresh), Normalise.normalise("tlssite", changed));
    }

    // ----------------------------------------------------------------------------------- builds
    // One snapshot per build, the nearest older one merge-restored (rig owner, 2026-10-20).

    static Map<String, String> suite(String commit, String day, String time) {
        return Map.of("Base_Level", "057.02.00", "Commit_Hash", commit, "Timestamp", day + "T" + time + "+00:00");
    }

    static final Map<String, String> S125 = suite("0cb76ccfb4920000", "2026-10-10", "14:17:38"); // the flat capture
    static final Map<String, String> S134 = suite("bbbbbbbbbbbb1111", "2026-10-17", "12:00:00");

    private static void manifestOnly(Path directory, Map<String, String> suite, String capturedAt) throws IOException {
        Files.createDirectories(directory);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", Snapshots.MANIFEST_FORMAT);
        manifest.put("captured_at", capturedAt);
        manifest.put("suiteinfo", suite);
        writeManifest(directory, manifest);
    }

    private static void manifestOnly(Path directory, Map<String, String> suite) throws IOException {
        manifestOnly(directory, suite, "2026-10-15T18:49:15+00:00");
    }

    @Test
    void aBuildIsNamedByItsBuildDateAndCommit() {
        assertEquals("2026-10-10-0cb76ccfb492", Snapshots.buildKey(S125));
        assertEquals(Path.of("R", "057.02.00", "2026-10-10-0cb76ccfb492"), Snapshots.buildDir("057.02.00", S125,
                Path.of("R")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Snapshots.buildKey(Map.of("Base_Level", "057.02.00")));
        assertTrue(e.getMessage().contains("Commit_Hash"));
    }

    @Test
    void theExactBuildIsChosenElseTheNearestOlderElseTheOldestNewer() throws IOException {
        Path base = tmp.resolve("057.02.00");
        manifestOnly(base, S125); // the flat snapshot of before 2026-10-20: Solutions 125
        manifestOnly(base.resolve(Snapshots.buildKey(S134)), S134);

        Snapshots.Choice exact = Snapshots.chooseSnapshot(base, S134);
        assertTrue(exact != null && exact.exact() && exact.directory().equals(base.resolve(Snapshots.buildKey(S134))));
        Snapshots.Choice flat = Snapshots.chooseSnapshot(base, S125);
        assertTrue(flat != null && flat.exact() && flat.directory().equals(base)); // the legacy layout

        Snapshots.Choice newer = Snapshots.chooseSnapshot(base, suite("c".repeat(16), "2026-10-19", "12:00:00"));
        assertTrue(newer != null && !newer.exact());
        assertEquals(base.resolve(Snapshots.buildKey(S134)), newer.directory()); // the nearest older, not 125
        assertTrue(newer.note().contains("onto 2026-10-19-cccccccccccc: merge restore"), newer.note());
        Snapshots.Choice between = Snapshots.chooseSnapshot(base, suite("d".repeat(16), "2026-10-12", "12:00:00"));
        assertTrue(between != null && between.directory().equals(base) && !between.exact());
        // Only newer builds have one: the oldest of them, whole, warned (run c497ffd31f78).
        Snapshots.Choice onlyNewer = Snapshots.chooseSnapshot(base, suite("e".repeat(16), "2026-10-01", "12:00:00"));
        assertTrue(onlyNewer != null && onlyNewer.exact() && onlyNewer.directory().equals(base));
        assertTrue(onlyNewer.note().startsWith("WARNING: only newer builds"), onlyNewer.note());
        assertNull(Snapshots.chooseSnapshot(tmp.resolve("058.00.00"), S134));
    }

    @Test
    void theLatestCaptureOfABuildWins() throws IOException {
        Path base = tmp.resolve("057.02.00");
        manifestOnly(base, S125, "2026-10-15T18:49:15+00:00");
        manifestOnly(base.resolve(Snapshots.buildKey(S125)), S125, "2026-10-21T08:00:00+00:00");
        Snapshots.Choice choice = Snapshots.chooseSnapshot(base, S125);
        assertTrue(choice != null && choice.directory().equals(base.resolve(Snapshots.buildKey(S125))));
    }

    /**
     * A capture whose suiteinfo was unreadable cannot be placed; it is what step 4 did with every snapshot before
     * builds were told apart, so it is still used whole.
     */
    @Test
    void anUnrecordedBuildIsRestoredAsCaptured() throws IOException {
        Path base = tmp.resolve("057.02.00");
        manifestOnly(base, Map.of());
        Snapshots.Choice choice = Snapshots.chooseSnapshot(base, S134);
        assertTrue(choice != null && choice.exact() && choice.directory().equals(base));
    }

    @Test
    void mergeWritesTheSnapshotValuesIntoTheNewBuildsView() {
        String saved = "<domain:c xmlns:domain=\"" + DOMAIN + "\" mode=\"1\"><a>1</a><gone>x</gone></domain:c>";
        String live = "<?xml version=\"1.0\"?>\n<domain:c xmlns:domain=\"" + DOMAIN + "\" mode=\"2\" added=\"y\">"
                + "<a>2</a><new>9</new></domain:c>";
        Merge.Merged merged = Merge.mergeDocument("fuelcfg", saved, live);
        assertTrue(merged.xml().startsWith("<?xml"));
        assertTrue(merged.xml().contains("<domain:c "), merged.xml()); // the Commander's own prefix, not ns0
        assertEquals(Normalise.normalise("fuelcfg", "<domain:c xmlns:domain=\"" + DOMAIN
                + "\" mode=\"1\" added=\"y\"><a>1</a><new>9</new></domain:c>"), Normalise.normalise("fuelcfg",
                merged.xml()));
        assertEquals(List.of("dropped c/gone (not on this build)"), merged.notes());
    }

    @Test
    void mergeOfAViewThatOnlyLostAndGainedElementsIsNoDifference() {
        String saved = "<c><a>1</a><gone>x</gone></c>";
        String live = "<c><a>1</a><new>9</new></c>";
        Merge.Merged merged = Merge.mergeDocument("fuelcfg", saved, live);
        assertEquals(Normalise.normalise("fuelcfg", live), Normalise.normalise("fuelcfg", merged.xml()));
    }

    private static String cfg(Object... items) {
        StringBuilder out = new StringBuilder("<cfg>");
        for (int i = 0; i < items.length; i += 2) {
            out.append("<text sysid=\"").append(items[i]).append("\">").append(items[i + 1]).append("</text>");
        }
        return out.append("</cfg>").toString();
    }

    @Test
    void mergePairsRepeatedSiblingsByTheirKey() {
        String saved = cfg(1, "WELCOME", 2, "INSERT");
        String live = cfg(2, "CHANGED", 1, "WELCOME", 3, "NEW");
        Merge.Merged merged = Merge.mergeDocument("pinpadmsgcfg", saved, live);
        String want = cfg(2, "INSERT", 1, "WELCOME", 3, "NEW");
        assertEquals(Normalise.normalise("pinpadmsgcfg", want), Normalise.normalise("pinpadmsgcfg", merged.xml()));
        assertEquals(List.of("kept cfg/text[sysid=3] (only on this build)"), merged.notes());
        String byChild = "<m><module><moduleName>%s</moduleName><v>%s</v></module></m>";
        Merge.Merged one = Merge.mergeDocument("x", String.format(byChild, "A", "1"), String.format(byChild, "A", "2"));
        assertTrue(one.xml().contains("<v>1</v>"), one.xml());
    }

    @Test
    void unkeyedSiblingsThatDoNotPairAreNotMergeRestorable() {
        String saved = "<cfg><line>a</line><line>b</line></cfg>";
        Merge.Merged sameCount = Merge.mergeDocument("x", saved, "<cfg><line>x</line><line>y</line></cfg>");
        assertTrue(sameCount.xml().contains("<line>a</line><line>b</line>")); // paired by position
        Merge.NotMergeable count = assertThrows(Merge.NotMergeable.class, () -> Merge.mergeDocument("x", saved,
                "<cfg><line>a</line><line>b</line><line>c</line></cfg>"));
        assertTrue(count.getMessage().contains("2 in the snapshot, 3 now, no key"), count.getMessage());
        Merge.NotMergeable notXml = assertThrows(Merge.NotMergeable.class,
                () -> Merge.mergeDocument("PLUs", "<a><b>", "<a><b>"));
        assertTrue(notXml.getMessage().contains("not XML"), notXml.getMessage());
    }

    /**
     * The newer build's view has an element the snapshot never had: kept, and the snapshot's value is restored
     * around it. UNVERIFIED on a Commander.
     */
    @Test
    void step4MergeRestoresAnOlderBuildsSnapshot() throws IOException {
        CommanderConfig client = commander.client();
        List<CgiFunction> functions = client.functionList();
        List<String> names = Areas.areasOf(functions, configurations);
        Path folder = Snapshots.buildDir("057.02.00", S125, root);
        Capture.capture(client, names, folder, quiet().functions(functions).info(Map.of("suiteinfo", S125)));
        commander.state.put("taxratecfg", commander.state.get("taxratecfg").replace("<name>primary</name>",
                "<name>leaked</name><extra>134</extra>"));
        commander.posted.clear();

        Say.Recorded say = new Say.Recorded();
        List<String> posted = init(S134, true, Map.of(), say);

        assertTrue(posted.contains("utaxratecfg"), posted.toString());
        assertTrue(commander.state.get("taxratecfg").contains("<name>primary</name>"), commander.state.get("taxratecfg"));
        assertTrue(commander.state.get("taxratecfg").contains("<extra>134</extra>")); // the new build's own, kept
        assertTrue(say.outText().contains("onto 2026-10-17-bbbbbbbbbbbb: merge restore"), say.outText());
        assertTrue(say.outText().contains("snapshot: restored taxratecfg"), say.outText());
    }

    /**
     * Step 4's {@code capture --if-missing}: a snapshot of another build (the flat 125 one) is no reason not to
     * capture this build; this build's own is.
     */
    @Test
    void afterAPsiInstallEachBuildIsCapturedOnce() throws IOException {
        Map<String, String> current = new HashMap<>(S134);
        CommanderConfig client = commander.client();
        List<CgiFunction> functions = client.functionList();
        List<String> names = Areas.areasOf(functions, configurations);
        Path base = Snapshots.snapshotDir("057.02.00", root);
        Capture.capture(client, names, base, quiet().functions(functions).info(Map.of("suiteinfo", S125)));
        String[] argv = {"commander-snapshot", "capture", "--rig", "example", "--if-missing", "--petroapps",
                petroapps().toString(), "--build-label", "Solutions134"};

        assertEquals(0, cli(() -> Map.copyOf(current), argv), errBytes.toString(StandardCharsets.UTF_8));
        Path mine = Snapshots.buildDir("057.02.00", S134, root);
        Map<String, Object> manifest = Snapshots.loadManifest(mine);
        assertTrue(manifest != null && "Solutions134".equals(manifest.get("build_label")));
        String before = Files.readString(mine.resolve(Snapshots.MANIFEST));
        out();
        assertEquals(0, cli(() -> Map.copyOf(current), argv)); // this build has one now: left as it is
        assertEquals(before, Files.readString(mine.resolve(Snapshots.MANIFEST)));
        assertTrue(out().contains("a snapshot of this build (Solutions134 (2026-10-17-bbbbbbbbbbbb)) exists"));
        current.clear();
        current.putAll(S125); // the flat snapshot is that build's: nothing to capture
        assertEquals(0, cli(() -> Map.copyOf(current), argv));
        List<String> withManifest;
        try (Stream<Path> list = Files.list(base)) {
            withManifest = list.filter(p -> Files.isRegularFile(p.resolve(Snapshots.MANIFEST)))
                    .map(p -> p.getFileName().toString()).sorted().toList();
        }
        assertEquals(List.of("2026-10-17-bbbbbbbbbbbb"), withManifest);
    }
}
