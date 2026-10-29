package com.verifone.cat.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.devices.DeviceUnreachable;

/**
 * {@code cat-java coverage}: the flags, the exit codes, and each command reaching its module. Nothing reaches a
 * device (the transport factory refuses at once) and {@code jacococli} is a stub.
 */
class CoverageCommandTest {

    private static final JacocoTools TOOLS = new JacocoTools(Path.of("java.exe"), Path.of("jacococli.jar"),
            Path.of("jacocoagent.jar"));

    @TempDir
    Path tmp;

    private final List<String> out = new ArrayList<>();
    private final List<String> err = new ArrayList<>();
    private final List<List<String>> calls = new ArrayList<>();

    private Jacoco.CliRunner stub() {
        return (tools, args, timeout) -> {
            calls.add(args);
            try {
                Path xml = Path.of(args.get(args.indexOf("--xml") + 1));
                Files.createDirectories(xml.getParent());
                Files.writeString(xml, "<report name=\"x\"><counter type=\"LINE\" missed=\"1\" covered=\"3\"/></report>");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new Jacoco.CliResult(true, "");
        };
    }

    /** Device seams whose transport refuses at once: a test never opens a socket. */
    private static Function<Consumer<String>, DeviceOps> refusingDevices() {
        return log -> new DeviceOps(spec -> {
            throw new DeviceUnreachable(spec.name(), "no device in a test");
        }, null, null, TOOLS, (tools, args, timeout) -> new Jacoco.CliResult(false, "no CLI"), log, null, null);
    }

    private CoverageCommand command(Map<String, String> env) {
        return new CoverageCommand(env, refusingDevices(), TOOLS, stub());
    }

    private int run(Map<String, String> env, String... args) {
        return command(env).execute(List.of(args), out::add, err::add);
    }

    private Map<String, String> rigsEnv() throws IOException {
        Path rigs = Files.createDirectories(tmp.resolve("rigs"));
        try (InputStream in = CoverageCommandTest.class.getResourceAsStream("/launch/rigs/bench.yaml")) {
            Files.write(rigs.resolve("bench.yaml"), in.readAllBytes());
        }
        return Map.of("PETROTEST_RIGS_DIR", rigs.toString());
    }

    @Test
    void usageErrorsExitTwo() {
        assertEquals(2, run(Map.of()));
        assertEquals(2, run(Map.of(), "frobnicate"));
        assertEquals(2, run(Map.of(), "prepare"));
        assertEquals(2, run(Map.of(), "collect", "--rig", "bench"));
        assertEquals(2, run(Map.of(), "report"));
        assertEquals(2, run(Map.of(), "report", "--dir", "x", "--rig", "bench"));
        assertEquals(2, run(Map.of(), "report", "--dir"));
        assertTrue(String.join("\n", err).contains("usage: cat-java coverage prepare --rig <name>"), err.toString());
        assertEquals(0, run(Map.of(), "--help"));
    }

    @Test
    void aRigThatDoesNotLoadExitsTwo() {
        assertEquals(2, run(Map.of("PETROTEST_RIGS_DIR", tmp.toString()), "prepare", "--rig", "nosuch"));
        assertTrue(err.get(0).startsWith("cannot load rig nosuch: "), err.toString());
    }

    @Test
    void prepareAndCollectReachTheDevicesAndFailWhenTheyCannot() throws IOException {
        Map<String, String> env = rigsEnv();
        assertEquals(1, run(env, "coverage", "prepare", "--rig", "bench"));
        assertTrue(out.stream().anyMatch(l -> l.startsWith("FAIL  commander: DeviceUnreachable")), out.toString());

        out.clear();
        Path exec = tmp.resolve("run").resolve("coverage").resolve("exec");
        assertEquals(1, run(env, "collect", "--rig", "bench", "--out", exec.toString(), "--no-reboot"));
        assertTrue(Files.isRegularFile(exec.resolve("collect.json")), "collect writes its manifest");
        assertTrue(out.stream().anyMatch(l -> l.startsWith("SKIP  Commander file-output JVMs — --no-reboot")),
                out.toString());
    }

    @Test
    void reportWithNoOverlayFailsAndSaysWhy() {
        assertEquals(1, run(Map.of("CAT_BUILDS_ROOT", tmp.toString()), "report", "--dir", tmp.toString()));
        assertEquals("FAIL  coverage report — no overlay.zip for this run (no --build or --overlay); the class files "
                + "come from it", err.get(0));
        err.clear();
        assertEquals(1, run(Map.of("CAT_BUILDS_ROOT", tmp.toString()), "report", "--dir", tmp.toString(), "--build",
                "Solutions1082"));
        assertTrue(err.get(0).contains("no overlay.zip for build 'Solutions1082'"), err.toString());
    }

    @Test
    void reportFindsTheBuildsOverlayReportsAndPrintsTheSummary() throws IOException {
        Path builds = tmp.resolve("builds");
        TarFixtures.overlay(builds.resolve("Solutions1082").resolve("buypass").resolve("overlay.zip"));
        Path coverage = tmp.resolve("results").resolve("run7").resolve("coverage");
        Files.createDirectories(coverage.resolve("exec"));
        Files.writeString(coverage.resolve("exec").resolve("fcc.exec"), "data");

        int code = run(Map.of("CAT_BUILDS_ROOT", builds.toString()), "report", "--dir", coverage.toString(),
                "--dist", "Buypass", "--build", "Solutions1082", "--plan", "PETROCORE-1", "--plan", "PETROCORE-2",
                "--results-root", tmp.resolve("results").toString());

        assertEquals(0, code, err.toString());
        String printed = String.join("\n", out);
        assertTrue(printed.contains("# JaCoCo coverage — run run7"), printed);
        assertTrue(printed.contains("plans PETROCORE-1, PETROCORE-2."), printed);
        assertTrue(printed.contains("| fcc | 75.0% (3/4) |"), printed);
        assertTrue(Files.isRegularFile(coverage.resolve("coverage-run7.zip")));
        // The class files came from the build's overlay, cached beside it.
        assertTrue(calls.get(0).contains(builds.resolve("Solutions1082").resolve("buypass").resolve("jacoco-classes")
                .resolve("v2").resolve("buypass").resolve("psc").resolve("fcc").resolve("isdApps.psc.fcc.app-V1.jar")
                .toString()), calls.get(0).toString());
    }

    @Test
    void aReportThatMeasuredNothingExitsOne() throws IOException {
        Path overlay = TarFixtures.overlay(tmp.resolve("o").resolve("overlay.zip"));
        Path coverage = Files.createDirectories(tmp.resolve("r").resolve("coverage"));
        assertEquals(1, run(Map.of(), "report", "--dir", coverage.toString(), "--overlay", overlay.toString(),
                "--results-root", tmp.resolve("results").toString()));
        assertTrue(String.join("\n", out).contains("Line Δ"), out.toString());
    }
}
