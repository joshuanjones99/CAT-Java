package com.verifone.cat.rig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The §8 schema and its validators, driven through {@link RigLoader#loadFile} from real files, so
 * each message is the one a person reading a rig file would see. A fake-backed pass is evidence
 * about this repository, not about a rig.
 */
class RigSchemaTest {

    /** A complete, valid rig in documentation addresses. Each test edits one line of it. */
    private static final String VALID = """
            name: bench
            commander:
              host: 10.0.0.11
              ssh_user: root
              eps_port: 41107
            registers:
              - id: topaz101
                host: 10.0.0.101
            controller:
              host: 10.0.0.160
            pinpad: viperpay
            build:
              expected_version: "057.02"
            """;

    @TempDir
    Path dir;

    private Path write(String text) throws IOException {
        Path file = dir.resolve("bench.yaml");
        Files.writeString(file, text);
        return file;
    }

    private Rig loadOk(String text) throws IOException {
        return RigLoader.loadFile(write(text)).rig();
    }

    private IllegalArgumentException loadBad(String text) throws IOException {
        return assertThrows(IllegalArgumentException.class, () -> RigLoader.loadFile(write(text)));
    }

    private static void assertContains(String needle, String haystack) {
        assertTrue(haystack.contains(needle), "expected <" + needle + "> in: " + haystack);
    }

    @Test
    void aCompleteRigLoadsWithEverySchemaDefault() throws IOException {
        Rig rig = loadOk(VALID);

        assertEquals("bench", rig.name());
        assertEquals(Rig.Pinpad.VIPERPAY, rig.pinpad());
        assertFalse(rig.posShim());
        assertEquals(8080, rig.commander().rciPort());
        assertEquals("", rig.commander().sshKeyPathEnv());
        assertEquals(9222, rig.register(null).cdpPort());
        assertEquals(Duration.ofSeconds(15), rig.timeouts().defaultWait());
        assertEquals(Duration.ofSeconds(30), rig.timeouts().promptWait());
        assertEquals(Duration.ofSeconds(90), rig.timeouts().fuelComplete());
        assertEquals(List.of(), rig.pumps());
        assertEquals("057.02", rig.build().expectedVersion());
        assertNull(rig.vats());
        assertNull(rig.wiremock());
    }

    @Test
    void anUnknownKeyIsRefusedNotIgnored() throws IOException {
        IllegalArgumentException e = loadBad(VALID + "registars: []\n");

        assertContains("unknown field", e.getMessage());
        assertContains("registars", e.getMessage());
    }

    @Test
    void aSourcePathKeyIsAnUnknownFieldBecauseItIsNotInTheSchema() throws IOException {
        IllegalArgumentException e = loadBad(VALID + "source_path: /x.yaml\n");

        assertContains("source_path", e.getMessage());
    }

    @Test
    void aMissingRequiredKeyIsNamed() throws IOException {
        String withoutPinpad = VALID.replace("pinpad: viperpay\n", "");
        IllegalArgumentException e = loadBad(withoutPinpad);

        assertContains("pinpad", e.getMessage());
        assertContains("Field required", e.getMessage());
    }

    @Test
    void aNestedMissingRequiredKeyNamesItsPath() throws IOException {
        String noSshUser = VALID.replace("  ssh_user: root\n", "");
        IllegalArgumentException e = loadBad(noSshUser);

        assertContains("commander.ssh_user", e.getMessage());
    }

    @Test
    void theSimhubPinpadIsRefusedNamingThePinpadField() throws IOException {
        IllegalArgumentException e = loadBad(VALID.replace("pinpad: viperpay", "pinpad: simhub"));

        assertContains("pinpad", e.getMessage());
    }

    @Test
    void pinpadVatsNeedsAVatsBlock() throws IOException {
        IllegalArgumentException e = loadBad(VALID.replace("pinpad: viperpay", "pinpad: vats"));

        assertContains("pinpad: vats requires a vats: block", e.getMessage());
    }

    @Test
    void vatsDefaultsAreTheAqaPorts() throws IOException {
        Rig rig = loadOk(VALID.replace("pinpad: viperpay", "pinpad: vats")
                + "vats:\n  host: 10.0.0.126\n");

        Rig.Vats vats = rig.vats();
        assertEquals(22001, vats.port());
        assertEquals(11000, vats.km4Port());
        assertEquals(9050, vats.cardServerPort());
        assertEquals(1983, vats.automationPort());
        assertEquals("contact", vats.interfaceKind());
    }

    @Test
    void aTimeoutIsParsedAtLoadTimeAndAMalformedOneNamesItsField() throws IOException {
        Rig rig = loadOk(VALID + "timeouts:\n  default_wait: 2m\n  prompt_wait: 500ms\n  fuel_complete: 90\n");
        assertEquals(Duration.ofMinutes(2), rig.timeouts().defaultWait());
        assertEquals(Duration.ofMillis(500), rig.timeouts().promptWait());
        assertEquals(Duration.ofSeconds(90), rig.timeouts().fuelComplete());
        assertEquals(Map.of("default_wait", "120s", "prompt_wait", "500ms", "fuel_complete", "90s"),
                rig.timeouts().asMap());

        IllegalArgumentException e = loadBad(VALID + "timeouts:\n  default_wait: 15 sec\n");
        assertContains("timeouts.default_wait", e.getMessage());
        assertContains("'15 sec'", e.getMessage());
    }

    @Test
    void aTimeoutWithAZeroIsRefused() throws IOException {
        IllegalArgumentException e = loadBad(VALID + "timeouts:\n  prompt_wait: 0s\n");

        assertContains("greater than zero", e.getMessage());
    }

    @Test
    void aBuildWrittenUnquotedIsRefusedBecauseItWouldBeAFloat() throws IOException {
        IllegalArgumentException e = loadBad(VALID.replace("expected_version: \"057.02\"",
                "expected_version: 057.02"));

        assertContains("build.expected_version", e.getMessage());
        assertContains("valid string", e.getMessage());
    }

    @Test
    void posShimMustBeABooleanNotAString() throws IOException {
        IllegalArgumentException e = loadBad(VALID + "pos_shim: \"true\"\n");

        assertContains("pos_shim", e.getMessage());
        assertContains("valid boolean", e.getMessage());
    }

    @Test
    void posShimTrueIsAccepted() throws IOException {
        assertTrue(loadOk(VALID + "pos_shim: true\n").posShim());
    }

    @Test
    void aPortOutsideItsRangeIsRefused() throws IOException {
        IllegalArgumentException e = loadBad(VALID.replace("eps_port: 41107", "eps_port: 70000"));

        // The block's own constructor names the field; Jackson supplies the block's path.
        assertContains("commander", e.getMessage());
        assertContains("eps_port: Input should be between 1 and 65535", e.getMessage());
    }

    @Test
    void aHostWithAPortIsRefused() throws IOException {
        IllegalArgumentException e = loadBad(VALID.replace("host: 10.0.0.101", "host: 10.0.0.101:9222"));

        assertContains("hostname or IP address", e.getMessage());
    }

    @Test
    void duplicateRegisterIdsAreRefusedAtRigLevel() throws IOException {
        String two = VALID.replace("controller:\n",
                "  - id: topaz101\n    host: 10.0.0.102\ncontroller:\n");
        IllegalArgumentException e = loadBad(two);

        assertContains("registers[].id must be unique", e.getMessage());
        assertContains("topaz101", e.getMessage());
    }

    @Test
    void theSameHostAndPortTwiceIsAPortCollision() throws IOException {
        // WireMock on the controller, on the Commander's EPS port: two services, one port.
        IllegalArgumentException e = loadBad(VALID + "wiremock:\n  port: 41107\n  mappings: ../maps\n");

        assertContains("commander.eps_port and wiremock.port are both 41107 on 10.0.0.160", e.getMessage());
    }

    @Test
    void the8080DefaultsOnTwoBoxesAreNoCollision() throws IOException {
        // RCI answers on the Commander and WireMock on the controller, so both default to 8080 without conflict.
        Rig rig = loadOk(VALID + "wiremock:\n  mappings: ../maps\n");

        assertEquals(8080, rig.commander().rciPort());
        assertEquals(8080, rig.wiremock().port());
    }

    @Test
    void theTlsPortMayNotBeAPrinterPort() throws IOException {
        IllegalArgumentException e = loadBad(VALID
                .replace("controller:\n  host: 10.0.0.160\n", "controller:\n  host: 10.0.0.160\n  tls_port: COM2\n")
                .replace("    host: 10.0.0.101\n", "    host: 10.0.0.101\n    printer_port: com2\n"));

        assertContains("tls_port", e.getMessage());
        assertContains("printer", e.getMessage());
    }

    @Test
    void pumpsMustBePositiveAndUnique() throws IOException {
        IllegalArgumentException zero = loadBad(VALID + "pumps: [0, 1]\n");
        assertContains("positive fuelling-point numbers", zero.getMessage());

        IllegalArgumentException dup = loadBad(VALID + "pumps: [1, 2, 2]\n");
        assertContains("duplicated: [2]", dup.getMessage());
    }

    @Test
    void pumpsDeclaredInOrder() throws IOException {
        assertEquals(List.of(1, 2, 3), loadOk(VALID + "pumps: [1, 2, 3]\n").pumps());
    }

    @Test
    void cashierMayNotHaveTwoSecrets() throws IOException {
        IllegalArgumentException e = loadBad(VALID + "cashier:\n  id: \"1\"\n  password: x\n  password_env: P\n");

        assertContains("set either cashier.password or cashier.password_env", e.getMessage());
    }

    @Test
    void cashierReportsItsSecretSourceWithoutTheValue() throws IOException {
        Rig.Cashier env = loadOk(VALID + "cashier:\n  id: \"1\"\n  password_env: CAT_TEST_PW\n").cashier();
        assertEquals("env:CAT_TEST_PW", env.secretSource());
        assertEquals("secret", env.resolvePassword(Map.of("CAT_TEST_PW", "secret")));
        assertEquals("", env.resolvePassword(Map.of()));

        // Quoted: unquoted 123 is an integer, which the strict string field refuses (pydantic does too).
        Rig.Cashier inline = loadOk(VALID + "cashier:\n  id: \"1\"\n  password: \"123\"\n").cashier();
        assertEquals("inline", inline.secretSource());
        assertEquals("123", inline.resolvePassword(Map.of()));
    }

    @Test
    void aRigNameMustBeLowercase() throws IOException {
        IllegalArgumentException e = loadBad(VALID.replace("name: bench", "name: Bench"));

        assertContains("lowercase rig name", e.getMessage());
    }

    @Test
    void anEmptyOrNonMappingFileIsRefused() throws IOException {
        assertContains("file is empty", loadBad("").getMessage());
        assertContains("expected a YAML mapping", loadBad("- a\n- b\n").getMessage());
    }

    @Test
    void theRegisterListMustNotBeEmpty() throws IOException {
        IllegalArgumentException e = loadBad(VALID.replace("registers:\n  - id: topaz101\n    host: 10.0.0.101\n",
                "registers: []\n"));

        assertContains("at least 1 item", e.getMessage());
    }

    @Test
    void theDeviceTableIsReadAsDeclared() throws IOException {
        Rig rig = loadOk(VALID + "devices:\n  pinpad_hardware:\n    host: 10.0.0.126\n    port: 22001\n");

        assertEquals(22001, rig.devices().get("pinpad_hardware").port());
        assertEquals("10.0.0.126", rig.devices().get("pinpad_hardware").host());
    }

    @Test
    void wiremockMappingsResolveRelativeToTheRigFile() throws IOException {
        Path file = write(VALID + "wiremock:\n  mappings: ../AutomationTests/configurations/EndPoints/\n");

        String resolved = RigLoader.loadFile(file).wiremockMappings();

        assertTrue(resolved.endsWith("AutomationTests" + java.io.File.separator + "configurations"
                + java.io.File.separator + "EndPoints"), resolved);
        assertFalse(resolved.contains(".."), resolved);
    }

    @Test
    void aNamedRigMustMatchItsFileName() throws IOException {
        Files.writeString(dir.resolve("lab.yaml"), VALID);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RigLoader.load("lab", dir));
        assertContains("name: is 'bench' but the file is 'lab'", e.getMessage());
    }

    @Test
    void aMissingRigSaysWhatIsKnown() throws IOException {
        Files.writeString(dir.resolve("bench.yaml"), VALID);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RigLoader.load("nope", dir));
        assertContains("Known rigs: bench", e.getMessage());
    }

    @Test
    void theSshKeyNamedByEnvironmentIsResolvedAndBlankMeansNone() throws IOException {
        Rig.Commander c = loadOk(VALID.replace("  eps_port: 41107\n",
                "  eps_port: 41107\n  ssh_key_path_env: CAT_TEST_KEY\n")).commander();

        assertEquals("C:\\keys\\id.pem", c.resolveKeyFilename(Map.of("CAT_TEST_KEY", "C:\\keys\\id.pem")));
        assertNull(c.resolveKeyFilename(Map.of()));
        assertNull(loadOk(VALID).commander().resolveKeyFilename(Map.of()));
    }
}
