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

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.devices.eps.CommanderLinkEpsProbe;
import com.verifone.cat.devices.eps.CommanderLinks;
import com.verifone.cat.devices.forecourt.ForecourtTimeouts;
import com.verifone.cat.devices.forecourt.PetroPumpStatus;
import com.verifone.cat.devices.suspended.CommanderSuspends;
import com.verifone.cat.posshim.FakeShim;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.InMemoryTransport;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

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

    /** The netstat a Commander holding its Buypass link prints, for the rig file above. */
    static final String LIVE_LINK = "tcp6 0 0 10.0.0.11:52370 10.0.0.160:41107 ESTABLISHED";

    /** Offline Commander sources: the netstat and the Day summary come from memory, so no Commander is contacted. */
    private String netstat = LIVE_LINK;
    private int unsettled = 0;
    private final java.util.List<InMemoryTransport> transports = new java.util.concurrent.CopyOnWriteArrayList<>();

    private RigContextFactory.Sources offline(Rig rig, Map<String, String> env) {
        return new RigContextFactory.Sources(
                CommanderLinkEpsProbe.fromRig(rig, () -> {
                    InMemoryTransport t = new InMemoryTransport(rig.commander().host())
                            .command(CommanderLinks.NETSTAT, new CommandResult(0, netstat, ""));
                    transports.add(t);
                    return t;
                }),
                () -> new Surfaces.UnsettledSuspends(unsettled, unsettled == 0 ? "0.00" : "18.25"),
                null);
    }

    private int run(String... args) {
        PreflightCommand command = new PreflightCommand(this::offline);
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
        int code = run("--rig", rigFile(true).toString(), "--repair");

        assertEquals(PreflightCommand.EXIT_FAILED, code, stdout());
        assertTrue(stdout().contains("pos_idle"), stdout());
        assertTrue(stdout().contains("repair=error"), stdout());
        assertTrue(stdout().contains("rig bench is not testable"), stdout());
    }

    @Test
    void withoutRepairTheReportHasNoRepairAndNoRepairIsTheSame() throws IOException {
        shim.onFixture("GET", "/pos/screen", "screen.json");
        int code = run("--rig", rigFile(true).toString());

        assertEquals(PreflightCommand.EXIT_FAILED, code, stdout());
        assertFalse(stdout().contains("repair="), stdout());
        assertEquals(PreflightCommand.EXIT_FAILED, run("--rig", rigFile(true).toString(), "--no-repair"));
    }

    @Test
    void theCommanderRowsReadTheirSourcesReadOnly() throws IOException {
        shim.on("GET", "/pos/screen", 200, Fixtures.resource("screen-idle.json"));
        int code = run("--rig", rigFile(true).toString(), "--no-repair");

        assertEquals(PreflightCommand.EXIT_OK, code, stdout() + stderr());
        assertTrue(lineOf("eps_reachable").contains("pass"), stdout());
        assertTrue(lineOf("no_parked_transaction").contains("pass"), stdout());
        // pumps: [1, 2, 3] and no forecourt: nothing to read them from, so ERROR, not FAIL.
        assertTrue(lineOf("pumps_idle").contains("error"), stdout());
        assertFalse(transports.isEmpty());
        for (InMemoryTransport t : transports) {
            assertEquals(java.util.List.of(CommanderLinks.NETSTAT), t.ran(), "verify ran netstat and nothing else");
            assertTrue(t.closed());
        }
    }

    @Test
    void aMissingPaymentLinkAndAParkedTicketMakeTheRigUntestable() throws IOException {
        shim.on("GET", "/pos/screen", 200, Fixtures.resource("screen-idle.json"));
        netstat = "tcp6 81 0 10.0.0.11:40258 10.0.0.160:41107 CLOSE_WAIT";
        unsettled = 1;
        int code = run("--rig", rigFile(true).toString(), "--no-repair");

        assertEquals(PreflightCommand.EXIT_FAILED, code, stdout());
        assertTrue(lineOf("eps_reachable").contains("fail"), stdout());
        assertTrue(stdout().contains("buypass fep -> 10.0.0.160:41107"), stdout());
        assertTrue(lineOf("no_parked_transaction").contains("fail"), stdout());
    }

    @Test
    void theLiveSourcesAreTheCommandersAndBuildingThemContactsNothing() throws IOException {
        Rig rig = RigLoader.loadFile(rigFile(true)).rig();
        RigContextFactory.Sources live = RigContextFactory.Sources.live(rig, Map.of());

        assertTrue(live.eps() instanceof CommanderLinkEpsProbe, String.valueOf(live.eps()));
        assertTrue(live.parkedTickets() instanceof CommanderSuspends, String.valueOf(live.parkedTickets()));
        assertEquals(null, live.pumpStatus(), "no forecourt: declared, so no pump source");
        // No SSH key variable on this rig: the probe cannot look, and says why, without opening anything.
        DeviceUnavailable e = org.junit.jupiter.api.Assertions.assertThrows(DeviceUnavailable.class,
                () -> live.eps().reachable(java.time.Duration.ofSeconds(1)));
        assertTrue(e.getMessage().contains("no SSH key"), e.getMessage());
    }

    @Test
    void aPetroForecourtRigReadsItsPumpsFromPetroSimulatorSuiteOnTheController() throws IOException {
        Path file = rigFile(true);
        // The controller at an RFC 5737 documentation address; pumps() is never called, so nothing connects.
        String yaml = Files.readString(file).replace("host: 10.0.0.160", "host: 192.0.2.10")
                + "forecourt: petro\ntimeouts:\n  default_wait: 12s\n";
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        Rig rig = RigLoader.loadFile(file).rig();
        RigContextFactory.Sources live = RigContextFactory.Sources.live(rig, Map.of());

        PetroPumpStatus pumps = org.junit.jupiter.api.Assertions.assertInstanceOf(PetroPumpStatus.class,
                live.pumpStatus());
        assertEquals("192.0.2.10", pumps.simulatorHost());
        assertEquals(new ForecourtTimeouts(java.time.Duration.ofSeconds(90), java.time.Duration.ofSeconds(30),
                java.time.Duration.ofSeconds(12)), RigContextFactory.forecourtTimeouts(rig));
    }

    private String lineOf(String check) {
        return stdout().lines().filter(l -> l.strip().startsWith(check + " ")).findFirst().orElse("(no line for " + check + ")")
                .toLowerCase(java.util.Locale.ROOT);
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
