package com.verifone.cat.devices.vats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.commander.Pauser;
import com.verifone.cat.connections.Readiness;
import com.verifone.cat.rig.Rig;

/**
 * The VATS lines of System Checks, the rig schema's {@code vats:} block and the SDK's location, over a temp dir's
 * stand-ins and loopback sockets: the Python's {@code test_vats_parts.py} rig and readiness tests. Evidence about this
 * repository only.
 */
class VatsReadinessTest {

    @TempDir
    Path dir;

    private Btt btt(Path fime) {
        return new Btt(List.of(new Btt.InstallGlob(fime, "*")), dir.resolve("agent"), null, null, Pauser.REAL,
                Btt.CARD_SERVER_ANSWER_WAIT);
    }

    private Path stagedSdk() throws IOException {
        Path root = dir.resolve("tools").resolve(VatsSdk.CONNECTOR_DIR);
        Path python = root.resolve("python-3.9.11-embed-amd64").resolve("python.exe");
        Files.createDirectories(python.getParent());
        Files.write(python, new byte[0]);
        Path relay = root.resolve("app").resolve("vats_runner").resolve("vats_connector.py");
        Files.createDirectories(relay.getParent());
        Files.writeString(relay, "");
        return root;
    }

    @Test
    void pinpadVatsWithoutAVatsBlockFailsAtLoad() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> VatsRigs.load(dir, "vats", null));
        assertTrue(String.valueOf(e.getMessage()).contains("pinpad: vats requires a vats: block")
                || String.valueOf(e.getCause()).contains("pinpad: vats requires a vats: block"), e.toString());
    }

    @Test
    void theVatsBlockTakesTheAqaPagesDefaultsAndTheBenchsCardPort() throws IOException {
        Rig rig = VatsRigs.load(dir, "vats", "  host: pinpad.invalid\n  card_images:\n    EMV CREDIT: Visa IINE.xml");
        Rig.Vats vats = rig.vats();
        assertEquals(List.of(22001, 11000, 9050, 1983), List.of(vats.port(), vats.km4Port(), vats.cardServerPort(),
                vats.automationPort()));
        assertEquals("contact", vats.interfaceKind());
        assertEquals("Visa IINE.xml", vats.cardImages().get("EMV CREDIT"));
    }

    @Test
    void aClosedVatsPortFailsSystemChecksWithWhatToDo() throws IOException {
        Rig rig = VatsRigs.vats(dir, "pinpad.testrig.invalid", 22001);
        List<Readiness> lines = new VatsReadiness(dir.resolve("absent"), btt(dir.resolve("none")))
                .checks(rig, Duration.ofMillis(500));
        Readiness port = lines.get(0);
        assertFalse(port.ok());
        assertEquals("pinpad VATS port closed", port.label());
        assertTrue(port.detail().contains(VatsReadiness.VATS_PORT_HINT), port.detail());
        assertTrue(lines.stream().allMatch(Readiness::required));
    }

    @Test
    void anOpenVatsPortTheStagedSdkAndAnInstalledBttPass() throws IOException {
        Path root = stagedSdk();
        Path fime = dir.resolve("Fime");
        Path home = fime.resolve("5.9.0");
        Files.createDirectories(home);
        Files.write(home.resolve("Brand Test Tool 5.9.0.exe"), new byte[0]);
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Rig rig = VatsRigs.vats(dir, "localhost", listener.getLocalPort());
            List<Readiness> lines = new VatsReadiness(root, btt(fime)).checks(rig, Duration.ofSeconds(1));
            assertEquals(List.of("pinpad VATS port", "VATS SDK staged", "BTT installed"),
                    lines.stream().map(Readiness::label).toList());
            assertTrue(lines.stream().allMatch(Readiness::ok), lines.toString());
            assertEquals("BTT 5.9.0 at " + home, lines.get(2).detail());
        }
    }

    @Test
    void anUnstagedSdkAndNoBttAreNamed() throws IOException {
        Rig rig = VatsRigs.load(dir, "vats", "  host: pinpad.invalid\n  btt_version: \"5.8.0\"");
        List<Readiness> lines = new VatsReadiness(dir.resolve("absent"), btt(dir.resolve("none")))
                .checks(rig, Duration.ofMillis(200));
        assertEquals("VATS SDK not staged", lines.get(1).label());
        assertTrue(lines.get(1).detail().contains("not staged: "), lines.get(1).detail());
        assertTrue(lines.get(1).detail().contains("terminalConnector-V4.zip from Artifactory"), lines.get(1).detail());
        assertEquals("BTT not installed", lines.get(2).label());
        assertTrue(lines.get(2).detail().startsWith("BTT 5.8.0 under "), lines.get(2).detail());
        assertTrue(lines.get(2).detail().endsWith(" — install it from Fime"), lines.get(2).detail());
    }

    @Test
    void rigsNotOnVatsGetNoVatsLines() throws IOException {
        // a vats: block declared, but not the selected pinpad
        Rig rig = VatsRigs.load(dir, "viperpay", "  host: pinpad.invalid");
        assertTrue(rig.vats() != null);
        assertEquals(List.of(), new VatsReadiness(dir, btt(dir)).checks(rig, Duration.ofMillis(100)));
    }

    @Test
    void theSdkIsLocatedOrNamedAsMissing() throws IOException {
        Path root = stagedSdk();
        VatsSdk sdk = VatsSdk.locate(root);
        assertEquals(root.resolve("app").resolve("vats_runner"), sdk.vatsRunner());
        assertEquals(root.resolve("app").resolve("Collis_Images"), sdk.collisImages());
        Path interpreter = Path.of("python3");
        assertEquals(interpreter, new VatsSdk(root, interpreter).python());
    }
}
