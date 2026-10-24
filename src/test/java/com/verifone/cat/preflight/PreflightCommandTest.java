package com.verifone.cat.preflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.posshim.FakeShim;

/**
 * The preflight command driven end to end: a real rig file, the real loader, the real shim client,
 * and a {@link FakeShim} on loopback. Only the transport is faked. Evidence about this repository.
 */
class PreflightCommandTest {

    @TempDir
    Path dir;

    private FakeShim shim;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;

    @BeforeEach
    void start() {
        shim = new FakeShim();
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
    }

    @AfterEach
    void stop() {
        shim.close();
    }

    /** A rig like the Python CAT's example, with its one register on the fake shim. */
    private Path rigFile(boolean posShim) throws IOException {
        Path file = dir.resolve("bench.yaml");
        Files.writeString(file, """
                name: bench
                commander:
                  host: 10.0.0.11
                  ssh_user: root
                  eps_port: 41107
                registers:
                  - id: topaz101
                    host: %s
                    cdp_port: 9222
                    cash_drawer: false
                controller:
                  host: 10.0.0.160
                pinpad: viperpay
                pos_shim: %s
                pumps: [1, 2, 3]
                build:
                  expected_version: "057.02"
                cashier:
                  id: PATS
                """.formatted(shim.host(), posShim ? "true" : "false"), StandardCharsets.UTF_8);
        return file;
    }

    private int run(String... args) {
        PreflightCommand command = new PreflightCommand();
        PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);
        String[] full = new String[args.length + 2];
        full[0] = "--shim-port";
        full[1] = String.valueOf(shim.port());
        System.arraycopy(args, 0, full, 2, args.length);
        return command.run(full, Map.of(), outStream, errStream);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void anIdlePosRigIsTestableAndTheUnreadableRowsAreNamedNotFailed() throws IOException {
        shim.on("GET", "/pos/screen", 200, Fixtures.resource("screen-idle.json"));
        int code = run("--rig", rigFile(true).toString());

        assertEquals(PreflightCommand.EXIT_OK, code, stdout() + stderr());
        assertTrue(stdout().contains("pos_idle"), stdout());
        assertTrue(stdout().contains("printer_connected"), stdout());
        assertTrue(stdout().contains("error"), stdout());
        assertFalse(stdout().contains("not testable"), stdout());
    }

    @Test
    void aBusyPosIsNotTestableAndTheRepairIsRecordedAsUnavailable() throws IOException {
        shim.onFixture("GET", "/pos/screen", "screen.json");
        int code = run("--rig", rigFile(true).toString());

        assertEquals(PreflightCommand.EXIT_FAILED, code, stdout());
        assertTrue(stdout().contains("pos_idle"), stdout());
        assertTrue(stdout().contains("repair=error"), stdout());
        assertTrue(stdout().contains("rig bench is not testable"), stdout());
    }

    @Test
    void noRepairLeavesTheRepairOutOfTheReport() throws IOException {
        shim.onFixture("GET", "/pos/screen", "screen.json");
        int code = run("--rig", rigFile(true).toString(), "--no-repair");

        assertEquals(PreflightCommand.EXIT_FAILED, code, stdout());
        assertFalse(stdout().contains("repair="), stdout());
    }

    @Test
    void aMissingRigFlagIsAUsageError() {
        int code = run();
        assertEquals(PreflightCommand.EXIT_USAGE, code);
        assertTrue(stderr().contains("--rig is required"), stderr());
    }

    @Test
    void aRigWithoutAShimIsRefusedAsUsage() throws IOException {
        int code = run("--rig", rigFile(false).toString());
        assertEquals(PreflightCommand.EXIT_USAGE, code);
        assertTrue(stderr().contains("pos_shim: true"), stderr());
    }

    @Test
    void aMissingRegisterIsAUsageError() throws IOException {
        int code = run("--rig", rigFile(true).toString(), "--register", "nosuch");
        assertEquals(PreflightCommand.EXIT_USAGE, code);
        assertTrue(stderr().contains("nosuch"), stderr());
    }
}
