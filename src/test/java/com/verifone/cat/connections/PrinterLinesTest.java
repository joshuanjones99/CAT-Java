package com.verifone.cat.connections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.devices.printer.SerialPorts;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * System Checks' printer lines ({@link RigReadiness#printerLines}): the Python CAT's {@code test_cat_readiness.py}
 * printer cases, with a fake serial listing. No COM port is listed or opened.
 */
class PrinterLinesTest {

    @TempDir
    Path dir;

    private Rig rig(String... registers) throws IOException {
        StringBuilder yaml = new StringBuilder("""
                name: printers
                commander:
                  host: commander.invalid
                  ssh_user: root
                  eps_port: 41107
                registers:
                """);
        for (String register : registers) {
            yaml.append(register);
        }
        yaml.append("""
                controller:
                  host: 127.0.0.1
                pinpad: viperpay
                build:
                  expected_version: "057.02"
                """);
        Path file = dir.resolve("printers.yaml");
        Files.writeString(file, yaml.toString());
        return RigLoader.loadFile(file).rig();
    }

    private static String register(String id, String port) {
        return "  - id: " + id + "\n    host: " + id + ".invalid\n" + (port == null ? "" : "    printer_port: " + port + "\n");
    }

    /** Lists {@code listed}; a port in {@code refuses} will not open, with that reason. */
    private static SerialPorts ports(List<String> listed, Map<String, String> refuses, List<String> opened) {
        return new SerialPorts() {
            @Override
            public List<String> listed() {
                return listed;
            }

            @Override
            public Optional<String> openProblem(String port) {
                opened.add(port);
                return Optional.ofNullable(refuses.get(port));
            }
        };
    }

    @Test
    void aListedPortThatOpensPasses() throws IOException {
        List<String> opened = new ArrayList<>();
        List<Readiness> lines = RigReadiness.printerLines(rig(register("topaz101", "COM3")),
                ports(List.of("com3"), Map.of(), opened));
        assertEquals(List.of(new Readiness(true, "printer port for topaz101", "COM3 is listed and opens", true)), lines);
        assertEquals(List.of("COM3"), opened);
    }

    @Test
    void aListedPortThatWillNotOpenFailsWithTheReplugHint() throws IOException {
        List<Readiness> lines = RigReadiness.printerLines(rig(register("topaz101", "COM3")),
                ports(List.of("COM3"), Map.of("COM3", "will not open: error 5"), new ArrayList<>()));
        assertFalse(lines.get(0).ok());
        assertEquals("COM3 is listed but will not open: error 5 — " + RigReadiness.SERIAL_HINT, lines.get(0).detail());
    }

    @Test
    void aMissingPortSaysReplugTheAdapterAndIsNotOpened() throws IOException {
        List<String> opened = new ArrayList<>();
        List<Readiness> lines = RigReadiness.printerLines(rig(register("topaz101", "COM3"), register("topaz102", "COM4")),
                ports(List.of("COM4"), Map.of(), opened));
        assertEquals(2, lines.size());
        assertEquals("COM3 is not listed — " + RigReadiness.SERIAL_HINT, lines.get(0).detail());
        assertTrue(lines.get(0).required());
        assertTrue(lines.get(1).ok());
        assertEquals(List.of("COM4"), opened);
    }

    @Test
    void aListingThatCannotBeTakenIsOneFailedLine() throws IOException {
        SerialPorts broken = new SerialPorts() {
            @Override
            public List<String> listed() {
                throw new UnsatisfiedLinkError("no native serial library");
            }

            @Override
            public Optional<String> openProblem(String port) {
                throw new AssertionError("not opened");
            }
        };
        List<Readiness> lines = RigReadiness.printerLines(rig(register("topaz101", "COM3")), broken);
        assertEquals(1, lines.size());
        assertEquals("printer serial port", lines.get(0).label());
        assertFalse(lines.get(0).ok());
    }

    @Test
    void noPrinterPortMeansNoSerialLine() throws IOException {
        SerialPorts never = new SerialPorts() {
            @Override
            public List<String> listed() {
                throw new AssertionError("not listed");
            }

            @Override
            public Optional<String> openProblem(String port) {
                throw new AssertionError("not opened");
            }
        };
        assertEquals(List.of(), RigReadiness.printerLines(rig(register("topaz101", null)), never));
    }
}
