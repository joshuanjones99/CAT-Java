package com.verifone.cat.sims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PetroTls}: the Python CAT's {@code tests/unit/test_cat_sims_petro_tls.py} property and configure cases, and the
 * agent built with the test JDK's own {@code javac}. Evidence about the files CAT writes; not evidence that the
 * simulator answers a Commander poll.
 */
class PetroTlsTest {

    /** The shipped files' shapes: CRLF, {@code :} in the common file, {@code =} in TLS_config. */
    static final String COMMON = "#Simulators to Start if mode selected is 2 or 4 \r\n"
            + "Simulators:DCR, FUEL, VIPERPAY ,HTTPSIM , WEBSOCKETSIM\r\n"
            + "filesPath:config/\r\n";
    static final String TLS = "Tanks=1,2,3\r\nSerialPort=COM1\r\nbaudRate=9600\r\n";
    static final SimEntry.Tls SPEC = new SimEntry.Tls("config/common.properties", "config/TLS_config.properties");

    @TempDir
    Path dir;

    static Path suite(Path root) throws IOException {
        Files.createDirectories(root.resolve("config"));
        Files.write(root.resolve(SPEC.simulators()), COMMON.getBytes(StandardCharsets.ISO_8859_1));
        Files.write(root.resolve(SPEC.port()), TLS.getBytes(StandardCharsets.ISO_8859_1));
        return root;
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
    }

    @Test
    void theSeparatorAndLineEndingAreKept() {
        String fresh = PetroTls.setProperty(COMMON, "Simulators", "DCR, TLS");
        assertEquals(COMMON.replace("DCR, FUEL, VIPERPAY ,HTTPSIM , WEBSOCKETSIM", "DCR, TLS"), fresh);
        assertEquals("DCR, FUEL, VIPERPAY ,HTTPSIM , WEBSOCKETSIM", PetroTls.getProperty(COMMON, "Simulators"));
    }

    @Test
    void anAbsentKeyIsAppended() {
        assertEquals("a=1\r\nSerialPort=COM2\r\n", PetroTls.setProperty("a=1\r\n", "SerialPort", "COM2"));
        assertEquals("SerialPort=COM2\n", PetroTls.setProperty("", "SerialPort", "COM2"));
    }

    @Test
    void tlsIsAddedOnceAndRemoved() {
        String on = PetroTls.simulatorsWith("DCR, FUEL, tls ,HTTPSIM", true);
        assertEquals("DCR, FUEL, HTTPSIM, TLS", on);
        assertEquals("DCR, FUEL, HTTPSIM", PetroTls.simulatorsWith(on, false));
    }

    @Test
    void aPortTurnsItOnThere() throws IOException {
        Path root = suite(dir);
        PetroTls.configure(root, SPEC, "COM2", Set.of("COM2"));
        assertTrue(read(root.resolve(SPEC.simulators()))
                .contains("Simulators:DCR, FUEL, VIPERPAY, HTTPSIM, WEBSOCKETSIM, TLS\r\n"));
        assertEquals(TLS.replace("COM1", "COM2"), read(root.resolve(SPEC.port())));
    }

    @Test
    void noPortTurnsItOffAndLeavesThePortAlone() throws IOException {
        Path root = suite(dir);
        PetroTls.configure(root, SPEC, "COM2", Set.of("COM2"));
        String line = PetroTls.configure(root, SPEC, "", Set.of("COM2"));
        assertEquals("tank level simulator off: the rig declares no controller.tls_port", line);
        String common = read(root.resolve(SPEC.simulators()));
        assertTrue(common.contains("WEBSOCKETSIM\r\n"), common);
        assertFalse(common.contains("TLS\r\n"), common);
        assertTrue(read(root.resolve(SPEC.port())).contains("SerialPort=COM2"));
    }

    @Test
    void aPortTheControllerLacksIsNamed() throws IOException {
        Path root = suite(dir);
        assertFalse(PetroTls.configure(root, SPEC, "com2", Set.of("COM2")).contains("not on this controller"));
        String line = PetroTls.configure(root, SPEC, "COM2", Set.of("COM1"));
        assertTrue(line.contains("COM2 is not on this controller (it has COM1)"), line);
    }

    @Test
    void theRealPortMapIsReadOnWindows() {
        assumeTrue(System.getProperty("os.name", "").startsWith("Windows"));
        assertTrue(PetroTls.serialPorts() != null);
    }

    @Test
    void petroNamesTheTwoFilesTheSuiteReads() {
        SimEntry petro = Registry.byName("Petro Simulator Suite").orElseThrow();
        assertEquals(new SimEntry.Tls("config/simulator_common_config.properties", "config/TLS_config.properties"),
                petro.tls());
        assertEquals(java.util.List.of("config/fuelPosTotals.xml"), petro.wellFormed());
    }

    @Test
    void theAgentSourceShipsAndTheJarIsBuiltOnce() throws IOException {
        Path javac = javac();
        Path jar = PetroTls.agentJar(javac, dir);
        assertEquals(jar, PetroTls.agentJar(javac, dir));
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".tmp")));
        }
        try (JarFile file = new JarFile(jar.toFile())) {
            assertEquals(PetroTls.AGENT_CLASS, file.getManifest().getMainAttributes().getValue("Premain-Class"));
            assertTrue(file.getEntry("cat/petro/RxtxAgent.class") != null);
        }
    }

    /** The test JDK's {@code javac}: the only compiler a test runs. */
    static Path javac() {
        Path home = SimTestSupport.testJdkHome().orElseThrow();
        Path javac = home.resolve("bin").resolve(System.getProperty("os.name", "").startsWith("Windows")
                ? "javac.exe" : "javac");
        assumeTrue(Files.isRegularFile(javac), "the test JVM is not a JDK");
        return javac;
    }
}
