package com.verifone.cat.fep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Element;

import com.verifone.cat.commander.Say;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.commander.CgiReplies;
import com.verifone.cat.devices.commander.CgiReplies.FepDetail;
import com.verifone.cat.devices.commander.CgiReplies.CloudConnectStatus;
import com.verifone.cat.devices.commander.FakeCommander;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.InMemoryTransport;

/**
 * The secondary FEPs: the table, the fepConfig edits, the selection and Commander Connect. A port of
 * {@code tests/unit/test_secondary_fep.py} (the command-line cases are not ported: the CLI is not wired).
 *
 * <p>Evidence about this repository: the Commander is a loopback {@link FakeCommander} with a FEP table, and the
 * {@link PdkShim} under it is the real client. None of it has met a Commander.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
class SecondaryFepsTest {

    private static final String CONTROLLER_IP = "192.0.2.10";
    private static final String CONTROLLER = "controller.testrig.invalid";
    private static final String OK = FepCommander.OK;

    @TempDir
    Path dir;

    private final List<SecondaryFep> catalogue = SecondaryFeps.loadCatalogue();

    // ----------------------------------------------------------------------------------------------------
    // The table
    // ----------------------------------------------------------------------------------------------------

    @Test
    void theTableLoadsWithOneRowPerFep() {
        assertEquals(10, catalogue.size());
        assertEquals(10, catalogue.stream().map(SecondaryFep::name).distinct().count());
    }

    @Test
    void everyFileTheTableImportsIsInPetroapps() {
        Path configurations = petroappsConfigurations();
        assumeTrue(Files.isDirectory(configurations), "no petroapps checkout on this machine");
        List<String> missing = catalogue.stream().flatMap(row -> row.files().stream())
                .filter(rel -> !Files.isRegularFile(configurations.resolve(rel))).toList();
        assertTrue(missing.isEmpty(), missing.toString());
    }

    @Test
    void eachRowImportsItsOwnFepcfgOrNoneAtAll() {
        for (SecondaryFep row : catalogue) {
            if (!row.files().isEmpty()) {
                assertTrue(row.files().stream().anyMatch(rel -> Path.of(rel).getFileName().toString()
                        .equals("fepcfg&fep=" + row.name() + ".xml")), row.name());
            }
        }
    }

    static Stream<Arguments> badTables() {
        return Stream.of(
                Arguments.of("- name: X\n  label: X\n  colour: red\n", "unknown keys"),
                Arguments.of("- name: X\n  label: X\n  enabled_by: magic\n", "enabled_by"),
                Arguments.of("- name: X\n  label: X\n  points_at: 'https://{controller_host}'\n", "no simulator"),
                Arguments.of("- name: X\n  label: X\n  listens_on: a.properties:key\n", "no simulator"),
                Arguments.of("- name: X\n  label: X\n  simulator: S\n  listens_on: no-colon\n", "<file>:<key>"),
                Arguments.of("- name: X\n  label: X\n- name: x\n  label: Y\n", "twice"),
                Arguments.of("- name: X\n  label: [unclosed\n", "does not parse"),
                Arguments.of("- name: A\n  label: A\n  enabled_by: license\n  simulator: S\n  points_at: \"h:1\"\n"
                        + "  cloud: {provider: x}\n", "cloud"),
                Arguments.of("- name: A\n  label: A\n  enabled_by: license\n  simulator: S\n  points_at: \"h:1\"\n"
                        + "  cloud: {provider: x, payload: text}\n", "cloud"));
    }

    @ParameterizedTest
    @MethodSource("badTables")
    void aBadTableIsAnIllegalArgument(String yaml, String complaint) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SecondaryFeps.parseCatalogue(yaml));
        assertTrue(e.getMessage().contains(complaint), e.getMessage());
    }

    @Test
    void findIgnoresCaseAndReturnsTheTablesSpelling() {
        SecondaryFep row = SecondaryFeps.find("PROPFLEET", catalogue);
        assertNotNull(row);
        assertEquals("PropFleet", row.name());
        assertNull(SecondaryFeps.find("nonesuch", catalogue));
    }

    @Test
    void statusSaysWhatAnswersIt() {
        assertEquals("Petro Simulator Suite, needs license", SecondaryFeps.find("mudflap", catalogue).status());
        assertEquals("no host needed", SecondaryFeps.find("FullServiceAttendant", catalogue).status());
        assertEquals("no simulator", SecondaryFeps.find("PropFleet", catalogue).status());
        assertEquals("Petro Simulator Suite, needs license", SecondaryFeps.find("WEX-OTR", catalogue).status());
    }

    @Test
    void theLicenseFepsAndTheirSimulatorsAreAsTheTableSays() {
        SecondaryFep wex = SecondaryFeps.find("WEX-OTR", catalogue);
        assertEquals("WEX-OTR-8030", wex.fepsimInstance());
        assertEquals("{controller_host}:8030", wex.pointsAt());
        assertEquals("Comdata-5003", SecondaryFeps.find("Comdata", catalogue).fepsimInstance());
        assertEquals("{controller_host}:5003", SecondaryFeps.find("Comdata", catalogue).pointsAt());
        for (String name : List.of("WEX-OTR", "Comdata", "mudflap")) {
            assertEquals("Petro Simulator Suite", SecondaryFeps.find(name, catalogue).simulator());
        }
        for (String name : List.of("quikq", "multiservice")) {
            SecondaryFep row = SecondaryFeps.find(name, catalogue);
            assertTrue(row.licenseEnabled() && row.simulator().isEmpty(), name);
        }
    }

    @Test
    void tagsNameTheirFepsAndTagsThatNameNoneAreNoError() {
        assertEquals(Set.of("WEX-OTR", "Comdata"), Set.copyOf(
                SecondaryFeps.featuresForTags(List.of("@comdata", "@wex_otr", "@fepsim", "@plan_end"), catalogue)));
        assertEquals(List.of("WEX-OTR"), SecondaryFeps.featuresForTags(List.of("@WEX-OTR"), catalogue));
    }

    @Test
    void resolveKeepsOrderDropsRepeatsAndRefusesNoneWithOthers() {
        List<String> names = SecondaryFeps.resolve(List.of("incomm", "PROPFLEET", "incomm"), catalogue).stream()
                .map(SecondaryFep::name).toList();
        assertEquals(List.of("incomm", "PropFleet"), names);
        assertEquals(List.of(), SecondaryFeps.resolve(List.of("none"), catalogue));
        IllegalArgumentException none = assertThrows(IllegalArgumentException.class,
                () -> SecondaryFeps.resolve(List.of("none", "incomm"), catalogue));
        assertTrue(none.getMessage().contains("'none' cannot be given with other FEPs"));
        IllegalArgumentException bogus = assertThrows(IllegalArgumentException.class,
                () -> SecondaryFeps.resolve(List.of("Bogus"), catalogue));
        assertTrue(bogus.getMessage().contains("no FEP 'Bogus'"), bogus.getMessage());
    }

    // ----------------------------------------------------------------------------------------------------
    // Editing a fepConfig
    // ----------------------------------------------------------------------------------------------------

    @Test
    void switchingEnabledTouchesNothingElse() {
        String before = FepCommander.fepConfig("Cullinan", true, "<domainName>host.invalid</domainName>");
        String after = SecondaryFeps.editFepConfig(before, false, null, false);
        assertTrue(after.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"), after);
        assertTrue(after.contains("<viper:fepConfig"), after);
        Element config = config(after);
        assertEquals("false", childText(config, "enabled"));
        assertEquals("false", childText(config, "enabledInsidePrompting"));
        assertTrue(after.contains("elementName=\"enabled\" enabled=\"true\""), after);
        assertEquals("host.invalid", textAt(config, "commOptions", "commOption", "ip", "address", "domainName"));
    }

    @Test
    void aMissingEnabledGoesInAfterTheFepName() {
        String text = FepCommander.fepConfig("X", true, "<domainName>host.invalid</domainName>")
                .replace("    <enabled>true</enabled>\n", "");
        Element config = config(SecondaryFeps.editFepConfig(text, true, null, false));
        assertEquals(List.of("fepName", "enabled"), childNames(config).subList(0, 2));
    }

    @Test
    void aUrlIsTheWholeDomainNameAndThePortStays() {
        String text = FepCommander.fepConfig("mudflap", false, "<ipV4>192.0.2.7</ipV4>");
        Element ip = ip(SecondaryFeps.editFepConfig(text, null, "https://" + CONTROLLER, false));
        assertEquals(List.of("domainName"), childNames(childOf(ip, "address")));
        assertEquals("https://" + CONTROLLER, textAt(ip, "address", "domainName"));
        assertEquals("80", childText(ip, "port"));
    }

    @ParameterizedTest
    @CsvSource({"192.0.2.160:4448,ipV4,192.0.2.160", CONTROLLER + ":4448,domainName," + CONTROLLER})
    void hostAndPortSetBoth(String target, String tag, String host) {
        Element ip = ip(SecondaryFeps.editFepConfig(fepPropFleet(), true, target, false));
        assertEquals(List.of(tag), childNames(childOf(ip, "address")));
        assertEquals(host, textAt(ip, "address", tag));
        assertEquals("4448", childText(ip, "port"));
    }

    @Test
    void aTargetThatIsNeitherIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SecondaryFeps.editFepConfig(fepPropFleet(), true, "just-a-host", false));
        assertTrue(e.getMessage().contains("neither a URL nor host:port"), e.getMessage());
    }

    @Test
    void aDocumentThatIsNotAFepConfigIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SecondaryFeps.editFepConfig("<?xml version=\"1.0\"?><other/>", true, null, false));
        assertTrue(e.getMessage().contains("not a fepConfig"), e.getMessage());
    }

    @Test
    void aUrlTargetWithNoPortGetsTheSchemasRequiredOneInPlace() {
        String text = FepCommander.fepConfig("mudflap", true, "<domainName>x</domainName>")
                .replace("<port>80</port>", "<heartbeatFrequency>30</heartbeatFrequency>");
        Element ip = ip(SecondaryFeps.editFepConfig(text, null, "https://" + CONTROLLER_IP, false));
        assertEquals(List.of("address", "port", "heartbeatFrequency"), childNames(ip));
        assertEquals("443", childText(ip, "port"));
    }

    @Test
    void aHostAndPortTargetPutsANewPortAfterTheSecondaryAddress() {
        String text = FepCommander.fepConfig("PropFleet", true, "<domainName>x</domainName>")
                .replace("<port>80</port>", "<secondaryAddress><ipV4>192.0.2.5</ipV4></secondaryAddress>"
                        + "<heartbeatFrequency>30</heartbeatFrequency>");
        Element ip = ip(SecondaryFeps.editFepConfig(text, null, CONTROLLER_IP + ":4448", false));
        assertEquals(List.of("address", "secondaryAddress", "port", "heartbeatFrequency"), childNames(ip));
        assertEquals("4448", childText(ip, "port"));
    }

    @Test
    void pointingASocketFepAtASimulatorTurnsItsTlsOff() {
        String text = FepCommander.fepConfig("WEX-OTR", false, "")
                .replace("<address></address>", "<address sslEnabled=\"true\"/>");
        Element address = childOf(ip(SecondaryFeps.editFepConfig(text, null, CONTROLLER_IP + ":8030", true)), "address");
        assertEquals("false", address.getAttribute("sslEnabled"));
        assertEquals(CONTROLLER_IP, childText(address, "domainName"));
    }

    @Test
    void theFepAddressReadsWhereAConfigPoints() {
        assertEquals("192.0.2.1:80", SecondaryFeps.fepAddress(
                FepCommander.fepConfig("X", true, "<ipV4>192.0.2.1</ipV4>")));
        assertEquals("https://192.0.2.1", SecondaryFeps.fepAddress(
                FepCommander.fepConfig("X", true, "<domainName>https://192.0.2.1</domainName>")));
        assertEquals("", SecondaryFeps.fepAddress("not xml"));
    }

    // ----------------------------------------------------------------------------------------------------
    // vfepdetails, read as the shim reads it
    // ----------------------------------------------------------------------------------------------------

    @Test
    void fepDetailsAreReadByName() {
        Map<String, FepDetail> details = CgiReplies.fepDetails(new FepCommander(
                Map.of("buypass", true, "PropFleet", false), Set.of()).details());
        assertTrue(details.get("buypass").enabled());
        assertTrue(details.get("buypass").primary());
        assertEquals("Secondary", details.get("PropFleet").fepType());
        assertFalse(details.get("PropFleet").enabled());
    }

    // ----------------------------------------------------------------------------------------------------
    // The selection, against the loopback Commander
    // ----------------------------------------------------------------------------------------------------

    /** A running FEP-table Commander and the real client over it. */
    private final class Bench implements AutoCloseable {

        final FepCommander table;
        final FakeCommander server;
        final PdkShim shim;

        Bench(FepCommander table) {
            this.table = table;
            this.server = new FakeCommander(table);
            this.shim = PdkShim.onPort(server.host(), server.port(), Duration.ofSeconds(5), null);
        }

        @Override
        public void close() {
            server.close();
        }
    }

    /** Cloud calls by endpoint, with what Commander Connect holds and whether a PUT is refused. */
    private static final class FakeCloud implements SecondaryFeps.CloudSend {

        final Map<String, String> held = new HashMap<>();
        final List<String[]> calls = new ArrayList<>();
        int putStatus = 200;

        @Override
        public SecondaryFeps.Reply send(String method, String endpoint, String body) {
            calls.add(new String[] {method, endpoint, body});
            String provider = endpoint.substring(endpoint.lastIndexOf('/') + 1);
            if (method.equals("GET")) {
                return held.containsKey(provider) ? new SecondaryFeps.Reply(200, held.get(provider))
                        : new SecondaryFeps.Reply(404, "not found");
            }
            if (putStatus == 200) {
                held.put(provider, body);
                return new SecondaryFeps.Reply(200, "");
            }
            return new SecondaryFeps.Reply(putStatus, "refused");
        }
    }

    /** Petroapps with PropFleet's two files, its fepcfg switched off. */
    private Path petroapps() throws IOException {
        Path folder = dir.resolve("petroapps").resolve("AutomationTests").resolve("configurations").resolve("propfleet");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("fepcfg&fep=PropFleet.xml"),
                FepCommander.fepConfig("PropFleet", false, "<ipV4>192.0.2.160</ipV4>"), StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("fepcardcfg&fep=PropFleet.xml"), "<fepCardConfig/>", StandardCharsets.UTF_8);
        return dir.resolve("petroapps");
    }

    /** A tools root whose HTTPSIM binds and certifies {@code httpSimAddress}. */
    private Path tools(String httpSimAddress) throws IOException {
        Path config = dir.resolve("tools").resolve("Petro_Simulator_Suite").resolve("config");
        Files.createDirectories(config);
        Files.writeString(config.resolve("HttpSim_config.properties"),
                "httpServerPort=80\nhttpSServerPort=443\nipAddress=" + httpSimAddress + "\n", StandardCharsets.UTF_8);
        return dir.resolve("tools");
    }

    private SecondaryFeps.Context context(FakeCloud cloud, Supplier<String> route, Path tools) throws IOException {
        return new SecondaryFeps.Context(petroapps(), CONTROLLER_IP, tools,
                cloud, cloud == null ? "" : "3964320", route, null);
    }

    private boolean select(Bench bench, String names, Path tools) throws IOException {
        return SecondaryFeps.select(FepClient.of(bench.shim),
                SecondaryFeps.resolve(List.of(names.split(" ")), catalogue), catalogue,
                context(null, null, tools), say);
    }

    private final Say.Recorded say = new Say.Recorded();

    @Test
    void noneWithNothingLeftOnIsOneRead() throws IOException {
        try (Bench bench = new Bench(new FepCommander(Map.of("buypass", true, "PropFleet", false,
                "mudflap", false), Set.of()))) {
            assertTrue(select(bench, "none", tools("192.0.2.10")));
            assertEquals(List.of("vfepdetails"), bench.table.commands);
        }
    }

    @Test
    void noneSwitchesOffWhatAPastRunLeftOnButNotALicensedFep() throws IOException {
        try (Bench bench = new Bench(new FepCommander(Map.of("buypass", true, "PropFleet", true, "mudflap", true),
                Set.of("mudflap")))) {
            assertTrue(select(bench, "none", tools("192.0.2.10")));
            assertEquals(Map.of("buypass", true, "PropFleet", false, "mudflap", true), bench.table.enabled);
            assertEquals(List.of("vfepdetails", "vfepcfg&fep=PropFleet", "ufepcfg&fep=PropFleet", "crefreshcfg"),
                    bench.table.commands);
            assertTrue(say.outText().contains("PropFleet: switched off"), say.outText());
            assertTrue(say.outText().contains("mudflap: left on"), say.outText());
        }
    }

    @Test
    void propFleetImportsItsFilesSwitchedOnWithThePetroappsAddress() throws IOException {
        try (Bench bench = new Bench(new FepCommander(Map.of("buypass", true, "PropFleet", false,
                "incomm", true), Set.of()))) {
            assertTrue(select(bench, "PropFleet", tools("192.0.2.10")));
            assertTrue(bench.table.enabled.get("PropFleet"));
            assertFalse(bench.table.enabled.get("incomm"));
            Element sent = config(bench.table.posted.get("ufepcfg&fep=PropFleet"));
            assertEquals("true", childText(sent, "enabled"));
            assertEquals("192.0.2.160", textAt(sent, "commOptions", "commOption", "ip", "address", "ipV4"));
            assertTrue(bench.table.posted.containsKey("ufepcardcfg&fep=PropFleet"));
            List<String> tail = bench.table.commands.subList(bench.table.commands.size() - 2,
                    bench.table.commands.size());
            assertEquals(List.of("crefreshcfg", "vfepdetails"), tail);
        }
    }

    @Test
    void mudflapIsPointedAtTheControllerAndFailsWithoutTheLicense() throws IOException {
        try (Bench bench = new Bench(new FepCommander(Map.of("buypass", true, "mudflap", false),
                Set.of("mudflap")))) {
            assertFalse(select(bench, "mudflap", tools("192.0.2.10")));
            Element sent = config(bench.table.posted.get("ufepcfg&fep=mudflap"));
            assertEquals("https://" + CONTROLLER_IP,
                    textAt(sent, "commOptions", "commOption", "ip", "address", "domainName"));
            assertEquals("false", childText(sent, "enabled"));
            assertTrue(say.errText().contains("Commercial Fleet license"), say.errText());
        }
    }

    @Test
    void mudflapWithTheLicensePasses() throws IOException {
        try (Bench bench = new Bench(new FepCommander(Map.of("buypass", true, "mudflap", true), Set.of("mudflap")))) {
            assertTrue(select(bench, "mudflap", tools("192.0.2.10")));
        }
    }

    @Test
    void aFepWithNoPetroappsFileIsSwitchedOnFromItsOwnConfig() throws IOException {
        try (Bench bench = new Bench(new FepCommander(Map.of("buypass", true, "Cullinan", false), Set.of()))) {
            assertTrue(select(bench, "Cullinan", tools("192.0.2.10")));
            assertTrue(bench.table.enabled.get("Cullinan"));
            assertTrue(say.outText().contains("WARNING Cullinan enabled, but no simulator answers"), say.outText());
        }
    }

    @Test
    void aLicensedFepWithNoSimulatorSendsNothingAndNeedsTheLicense() throws IOException {
        try (Bench bench = new Bench(new FepCommander(Map.of("buypass", true, "quikq", false), Set.of("quikq")))) {
            assertFalse(select(bench, "quikq", tools("192.0.2.10")));
            assertTrue(bench.table.posted.isEmpty());
            assertEquals(List.of("vfepdetails", "vcloudconnectstatus", "vfepdetails"), bench.table.commands);
        }
    }

    @Test
    void aFepTheCommanderDoesNotOfferFailsBeforeWriting() throws IOException {
        try (Bench bench = new Bench(new FepCommander(Map.of("buypass", true), Set.of()))) {
            assertFalse(select(bench, "Comdata", tools("192.0.2.10")));
            assertEquals(List.of("vfepdetails"), bench.table.commands);
            assertTrue(say.errText().contains("distribution does not offer it"), say.errText());
        }
    }

    @Test
    void severalFepsGoOnTogetherWithOneRefresh() throws IOException {
        try (Bench bench = new Bench(new FepCommander(Map.of("buypass", true, "PropFleet", false, "Cullinan", false,
                "incomm", true), Set.of()))) {
            assertTrue(select(bench, "PropFleet Cullinan", tools("192.0.2.10")));
            assertEquals(Map.of("buypass", true, "PropFleet", true, "Cullinan", true, "incomm", false),
                    bench.table.enabled);
            assertEquals(1, bench.table.commands.stream().filter("crefreshcfg"::equals).count());
            assertEquals("vfepdetails", bench.table.commands.get(bench.table.commands.size() - 1));
        }
    }

    @Test
    void eachFepThatDidNotTakeIsNamed() throws IOException {
        try (Bench bench = new Bench(new FepCommander(Map.of("buypass", true, "PropFleet", false,
                "mudflap", false), Set.of("mudflap")))) {
            assertFalse(select(bench, "PropFleet mudflap", tools("192.0.2.10")));
            assertTrue(bench.table.enabled.get("PropFleet"));
            assertTrue(say.errText().contains("mudflap is not enabled"), say.errText());
            assertFalse(say.errText().contains("PropFleet is not enabled"), say.errText());
        }
    }

    @Test
    void aLicenseFepOnACommanderNotOnboardedFailsBeforeWriting() throws IOException {
        FepCommander table = new FepCommander(Map.of("buypass", true, "PropFleet", true, "mudflap", false),
                Set.of("mudflap"));
        table.onboarded = false;
        try (Bench bench = new Bench(table)) {
            assertFalse(select(bench, "mudflap", tools("192.0.2.10")));
            assertEquals(List.of("vfepdetails", "vcloudconnectstatus"), bench.table.commands);
            assertTrue(bench.table.posted.isEmpty());
            assertTrue(bench.table.enabled.get("PropFleet"), "nothing switched off either");
            assertTrue(say.errText().contains("Commander Connect is not enabled on this Commander (2: NOT REGISTERED)"),
                    say.errText());
            assertTrue(say.errText().contains("Commander Connect > Onboard"), say.errText());
        }
    }

    @Test
    void commanderConnectIsNotReadWithoutALicenseFep() throws IOException {
        FepCommander table = new FepCommander(Map.of("buypass", true, "PropFleet", false), Set.of());
        table.onboarded = false;
        try (Bench bench = new Bench(table)) {
            assertTrue(select(bench, "PropFleet", tools("192.0.2.10")));
            assertFalse(bench.table.commands.contains("vcloudconnectstatus"));
        }
    }

    /**
     * A client whose import applies nothing: what a Commander that keeps faulting the file looks like to the step.
     * The shim's own retry (15 attempts, 20 s apart) is PATS's and is not run here.
     */
    private static FepClient importsNothing(FepClient real) {
        return new FepClient() {
            @Override
            public Map<String, FepDetail> fepDetails() {
                return real.fepDetails();
            }

            @Override
            public String viewConfiguration(String command) {
                return real.viewConfiguration(command);
            }

            @Override
            public void updateConfiguration(String command, String payload) {
                real.updateConfiguration(command, payload);
            }

            @Override
            public Map<String, String> importConfigurationFolder(Path folder) {
                return Map.of();
            }

            @Override
            public void refreshConfiguration() {
                real.refreshConfiguration();
            }

            @Override
            public CloudConnectStatus cloudConnectStatus() {
                return real.cloudConnectStatus();
            }
        };
    }

    @Test
    void aFepCommanderWillNotTakeRaises() throws IOException {
        try (Bench bench = new Bench(new FepCommander(Map.of("buypass", true, "PropFleet", false), Set.of()))) {
            DeviceError e = assertThrows(DeviceError.class, () -> SecondaryFeps.select(
                    importsNothing(FepClient.of(bench.shim)),
                    List.of(SecondaryFeps.find("PropFleet", catalogue)), catalogue,
                    context(null, null, tools("192.0.2.10")), say));
            assertTrue(e.getMessage().contains("was not applied"), e.getMessage());
        }
    }

    @Test
    void aChosenFepWhoseTrustIsNeededButNotAvailableFails() throws IOException {
        SecondaryFep trusting = new SecondaryFep("Cullinan", "Cullinan", "", "fepcfg", List.of(), false,
                "", "", "", "", "", "", "{controller_host}:443", "");
        try (Bench bench = new Bench(new FepCommander(Map.of("buypass", true, "Cullinan", false), Set.of()))) {
            assertFalse(SecondaryFeps.select(FepClient.of(bench.shim), List.of(trusting), catalogue,
                    context(null, null, tools("192.0.2.10")), say));
            // The Python prints the row's own placeholder, not the resolved host.
            assertTrue(say.errText().contains("needs {controller_host}:443 trusted"), say.errText());
            assertTrue(bench.table.posted.isEmpty());
        }
    }

    // ----------------------------------------------------------------------------------------------------
    // Mudflap's simulator: HTTPSIM's address
    // ----------------------------------------------------------------------------------------------------

    @Test
    void httpSimOnAnotherAddressFailsBeforeWriting() throws IOException {
        try (Bench bench = new Bench(new FepCommander(Map.of("buypass", true, "mudflap", true), Set.of("mudflap")))) {
            assertFalse(select(bench, "mudflap", tools("192.0.2.99")));
            assertTrue(bench.table.posted.isEmpty());
            assertTrue(say.errText().contains("listens on, and names in its certificate, 192.0.2.99"), say.errText());
            assertTrue(say.errText().contains("Set ipAddress=" + CONTROLLER_IP + " there"), say.errText());
        }
    }

    @Test
    void aControllerNamedByDnsFailsMudflapsCertificateCheck() throws IOException {
        Path tools = tools(CONTROLLER);
        SecondaryFeps.Context ctx = new SecondaryFeps.Context(petroapps(), CONTROLLER, tools, null, "", null, null);
        try (Bench bench = new Bench(new FepCommander(Map.of("buypass", true, "mudflap", true), Set.of("mudflap")))) {
            assertFalse(SecondaryFeps.select(FepClient.of(bench.shim),
                    List.of(SecondaryFeps.find("mudflap", catalogue)), catalogue, ctx, say));
            assertTrue(bench.table.posted.isEmpty());
            assertTrue(say.errText().contains("must be one, not '" + CONTROLLER + "'"), say.errText());
        }
    }

    @Test
    void anUnstagedHttpSimFailsBeforeWriting() throws IOException {
        try (Bench bench = new Bench(new FepCommander(Map.of("buypass", true, "mudflap", true), Set.of("mudflap")))) {
            assertFalse(select(bench, "mudflap", dir.resolve("no-tools")));
            assertTrue(bench.table.posted.isEmpty());
            assertTrue(say.errText().contains("cannot read"), say.errText());
        }
    }

    // ----------------------------------------------------------------------------------------------------
    // Commander Connect's provider config (2026-10-20)
    // ----------------------------------------------------------------------------------------------------

    @Test
    void aLicensedWexOtrIsPointedAtTheControllerInTheCloudAndOnTheCommander() throws IOException {
        FepCommander table = new FepCommander(Map.of("buypass", true, "WEX-OTR", true), Set.of("WEX-OTR"));
        FakeCloud cloud = new FakeCloud();
        try (Bench bench = new Bench(table)) {
            assertTrue(SecondaryFeps.select(FepClient.of(bench.shim), SecondaryFeps.resolve(List.of("WEX-OTR"),
                    catalogue), catalogue, context(cloud, null, tools("192.0.2.10")), say));
            String endpoint = "pcms/v1/config/3964320/commercialfleetproviders/WEX-OTR";
            assertEquals(List.of("GET", endpoint), List.of(cloud.calls.get(0)[0], cloud.calls.get(0)[1]));
            assertEquals(List.of("PUT", endpoint), List.of(cloud.calls.get(1)[0], cloud.calls.get(1)[1]));
            assertTrue(cloud.calls.get(1)[2].contains("\"host\":\"" + CONTROLLER_IP + "\""), cloud.calls.get(1)[2]);
            assertTrue(cloud.calls.get(1)[2].contains("\"port\":8030"), cloud.calls.get(1)[2]);
            Element ip = ip(table.posted.get("ufepcfg&fep=WEX-OTR"));
            assertEquals(CONTROLLER_IP, textAt(ip, "address", "domainName"));
            assertNull(textOrNull(ip, "address", "ipV4"));
            assertEquals("8030", childText(ip, "port"));
            assertTrue(say.outText().contains("WEX-OTR: Commander Connect held none (HTTP 404)"), say.outText());
            assertTrue(say.outText().contains("WEX-OTR points at " + CONTROLLER_IP + ":8030"), say.outText());
        }
    }

    @Test
    void whatTheCloudHeldBeforeIsPrintedAndReplaced() throws IOException {
        FepCommander table = new FepCommander(Map.of("buypass", true, "Comdata", true), Set.of("Comdata"));
        FakeCloud cloud = new FakeCloud();
        cloud.held.put("comdata", "{\"providerName\":\"Comdata\",\"connectionSettings\":"
                + "{\"host\":\"earlier.testrig.invalid\"}}");
        try (Bench bench = new Bench(table)) {
            assertTrue(SecondaryFeps.select(FepClient.of(bench.shim), SecondaryFeps.resolve(List.of("Comdata"),
                    catalogue), catalogue, context(cloud, null, tools("192.0.2.10")), say));
            assertTrue(say.outText().contains("earlier.testrig.invalid"), say.outText());
            assertTrue(cloud.held.get("comdata").contains("\"host\":\"" + CONTROLLER_IP + "\""), cloud.held.toString());
        }
    }

    @Test
    void aRefusedCloudPutIsAWarningAndTheCommanderIsStillPointed() throws IOException {
        FepCommander table = new FepCommander(Map.of("buypass", true, "mudflap", true), Set.of("mudflap"));
        FakeCloud cloud = new FakeCloud();
        cloud.putStatus = 403;
        try (Bench bench = new Bench(table)) {
            assertTrue(SecondaryFeps.select(FepClient.of(bench.shim), SecondaryFeps.resolve(List.of("mudflap"),
                    catalogue), catalogue, context(cloud, null, tools("192.0.2.10")), say));
            assertTrue(say.outText().contains("WARNING mudflap: PUT pcms/v1/config/3964320/commercialfleetproviders/"
                    + "mudflap"), say.outText());
            assertTrue(bench.table.posted.containsKey("ufepcfg&fep=mudflap"));
        }
    }

    @Test
    void noCloudAccountWarnsThatALaterPushCouldRepointIt() throws IOException {
        try (Bench bench = new Bench(new FepCommander(Map.of("buypass", true, "WEX-OTR", true), Set.of("WEX-OTR")))) {
            assertTrue(select(bench, "WEX-OTR", tools("192.0.2.10")));
            assertTrue(say.outText().contains("WEX-OTR: Commander Connect not updated"), say.outText());
        }
    }

    @Test
    void aLicenseFepLeftOffNamesTheCommandersRoute() throws IOException {
        FepCommander table = new FepCommander(Map.of("buypass", true, "WEX-OTR", false), Set.of("WEX-OTR"));
        try (Bench bench = new Bench(table)) {
            assertFalse(SecondaryFeps.select(FepClient.of(bench.shim), SecondaryFeps.resolve(List.of("WEX-OTR"),
                    catalogue), catalogue, context(new FakeCloud(), () -> "NONE", tools("192.0.2.10")), say));
            String err = say.errText();
            assertTrue(err.contains("WEX-OTR is not enabled"), err);
            assertTrue(err.contains("description CFD"), err);
            assertTrue(err.contains("route is NONE"), err);
        }
    }

    @Test
    void aFepTheCommanderDidNotRepointFails() throws IOException {
        FepCommander table = new FepCommander(Map.of("buypass", true, "WEX-OTR", true), Set.of("WEX-OTR"));
        table.forgetPosts = true;
        try (Bench bench = new Bench(table)) {
            assertFalse(select(bench, "WEX-OTR", tools("192.0.2.10")));
            assertTrue(say.errText().contains("WEX-OTR points at host.invalid:80, not " + CONTROLLER_IP + ":8030"),
                    say.errText());
        }
    }

    @Test
    void theRouteIsReadOffPmcsRouteFile() {
        InMemoryTransport transport = new InMemoryTransport("commander.test").command(
                "cat " + SecondaryFeps.FLEET_ROUTE_FILE,
                new CommandResult(0, "#Mon Oct 20\ncommercialFleetRoute=NONE\n", ""));
        assertEquals("NONE", SecondaryFeps.fleetRoute(transport, Duration.ofSeconds(5)));
    }

    @Test
    void aTrustSeamIsCalledWithTheResolvedEndpointAndAnAlias() throws IOException {
        // Not run against a Commander: the trust seam is a recorder here.
        SecondaryFep trusting = new SecondaryFep("Cullinan", "Cullinan", "", "fepcfg", List.of(), false, "",
                "", "", "", "", "", "{controller_host}:443", "");
        List<String> seen = new ArrayList<>();
        SecondaryFeps.Context ctx = new SecondaryFeps.Context(petroapps(), CONTROLLER_IP, tools("192.0.2.10"), null,
                "", null, (endpoint, alias) -> seen.add(endpoint + " as " + alias));
        try (Bench bench = new Bench(new FepCommander(Map.of("buypass", true, "Cullinan", false), Set.of()))) {
            assertTrue(SecondaryFeps.select(FepClient.of(bench.shim), List.of(trusting), catalogue, ctx, say));
            assertEquals(List.of(CONTROLLER_IP + ":443 as cat-cullinan"), seen);
        }
    }

    // ----------------------------------------------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------------------------------------------

    private static Path petroappsConfigurations() {
        String root = System.getenv("PETROAPPS_ROOT");
        return Path.of(root == null || root.isBlank() ? "C:\\gitrepos\\petroapps" : root, "AutomationTests",
                "configurations");
    }

    private static String fepPropFleet() {
        return FepCommander.fepConfig("PropFleet", true, "<domainName>host.invalid</domainName>");
    }

    private static Element config(String xml) {
        return childOf(document(xml), "config");
    }

    private static Element ip(String xml) {
        return childOf(childOf(childOf(config(xml), "commOptions"), "commOption"), "ip");
    }

    private static Element document(String xml) {
        try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(
                    new java.io.StringReader(xml.strip()))).getDocumentElement();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static org.w3c.dom.Element childOf(org.w3c.dom.Element parent, String name) {
        for (org.w3c.dom.Element child : elements(parent)) {
            if (child.getLocalName().equals(name)) {
                return child;
            }
        }
        throw new IllegalStateException("no <" + name + "> under <" + parent.getLocalName() + ">");
    }

    private static String childText(org.w3c.dom.Element parent, String name) {
        for (org.w3c.dom.Element child : elements(parent)) {
            if (child.getLocalName().equals(name)) {
                return child.getTextContent().strip();
            }
        }
        return null;
    }

    private static String textAt(org.w3c.dom.Element root, String... path) {
        org.w3c.dom.Element at = root;
        for (String name : path) {
            at = childOf(at, name);
        }
        return at.getTextContent().strip();
    }

    private static String textOrNull(org.w3c.dom.Element root, String... path) {
        org.w3c.dom.Element at = root;
        for (String name : path) {
            org.w3c.dom.Element next = null;
            for (org.w3c.dom.Element child : elements(at)) {
                if (child.getLocalName().equals(name)) {
                    next = child;
                }
            }
            if (next == null) {
                return null;
            }
            at = next;
        }
        return at.getTextContent().strip();
    }

    private static List<String> childNames(org.w3c.dom.Element parent) {
        List<String> names = new ArrayList<>();
        for (org.w3c.dom.Element child : elements(parent)) {
            names.add(child.getLocalName());
        }
        return names;
    }

    private static List<org.w3c.dom.Element> elements(org.w3c.dom.Element parent) {
        List<org.w3c.dom.Element> out = new ArrayList<>();
        for (org.w3c.dom.Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof org.w3c.dom.Element element) {
                out.add(element);
            }
        }
        return out;
    }
}
