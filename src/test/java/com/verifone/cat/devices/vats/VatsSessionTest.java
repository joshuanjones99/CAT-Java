package com.verifone.cat.devices.vats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.commander.Pauser;
import com.verifone.cat.commander.Say;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.rig.Rig;

/**
 * The KM4 relay's start, adopt and stop, a terminal-only session and {@code pinpad-clear}, with the stand-in SDK and
 * relay run as real subprocesses where a Python 3.9+ is found (skipped otherwise), and an in-memory agent where none is
 * needed. Evidence about this repository only.
 */
class VatsSessionTest {

    @TempDir
    Path dir;

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    private static Path python() {
        Path python = StandInSdk.python();
        org.junit.jupiter.api.Assumptions.assumeTrue(python != null, "no Python 3.9+ on this machine");
        return python;
    }

    @Test
    void theRelaysCommandLineIsPatslibsPlusTheTerminalPort() {
        VatsSdk sdk = new VatsSdk(dir.resolve("tc"));
        VatsRelay relay = new VatsRelay(sdk, "pinpad.invalid", 22001, 11000, dir.resolve("work"));
        assertEquals(List.of(sdk.python().toString(), sdk.relayScript().toString(), "--tcpip", "pinpad.invalid",
                "--tcpport", "22001", "--km4_port", "11000", "--ipc_comm", "0", "--workingdir",
                dir.resolve("work").toString(), "--autoreconnection"), relay.argv());
    }

    @Test
    void aRelayAlreadyServingTheKm4PortIsAdoptedAndNeverStarted() throws IOException {
        try (ServerSocket held = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
            VatsRelay relay = new VatsRelay(new VatsSdk(dir.resolve("absent")), "pinpad.invalid", 22001,
                    held.getLocalPort(), dir.resolve("work"));
            assertTrue(relay.start().adopted());
            relay.stop(); // an adopted relay is left alone
            assertTrue(Ports.listening(held.getLocalPort()));
        }
    }

    @Test
    void aStartedRelayOpensItsPortAndIsStoppedWithItsChildren() throws IOException {
        StandInSdk fake = StandInSdk.write(dir);
        int km4 = freePort();
        VatsRelay relay = new VatsRelay(fake.sdk(python()), "pinpad.invalid", 22001, km4, dir.resolve("work"))
                .start(Duration.ofSeconds(30));
        assertFalse(relay.adopted());
        assertTrue(Ports.listening(km4));
        relay.stop();
        assertTrue(Pauser.REAL.until(() -> !Ports.listening(km4), Duration.ofSeconds(10), Duration.ofMillis(100)));
    }

    @Test
    void aRelayThatExitsFirstIsNamedWithItsExitCodeAndLog() throws IOException {
        StandInSdk fake = StandInSdk.write(dir);
        int km4 = freePort();
        VatsRelay relay = new VatsRelay(fake.sdk(python()), "exit.invalid", 22001, km4, dir.resolve("work"));
        DeviceUnreachable e = assertThrows(DeviceUnreachable.class, () -> relay.start(Duration.ofSeconds(30)));
        assertTrue(e.getMessage().contains("exited with 3 before opening port " + km4), e.getMessage());
        assertTrue(e.getMessage().contains(VatsRelay.LOG_FILE), e.getMessage());
    }

    private Rig rig(int km4) throws IOException {
        return VatsRigs.load(dir, "vats", "  host: pinpad.invalid\n  km4_port: " + km4);
    }

    private VatsSession.Environment environment(Path python) {
        Btt btt = new Btt(List.of(), dir.resolve("btt-agent"), null, null, Pauser.REAL, Btt.CARD_SERVER_ANSWER_WAIT);
        return new VatsSession.Environment(dir, dir.resolve("petroapps"), dir.resolve("agent"), btt, python);
    }

    @Test
    void aTerminalOnlySessionClearsACardAnEarlierSessionLeftIn() throws IOException {
        Path python = python();
        StandInSdk fake = StandInSdk.write(dir);
        fake.show(List.of("Welcome"), List.of(), 1);
        int km4 = freePort();
        Rig rig = rig(km4);
        Say.Recorded say = new Say.Recorded();
        assertTrue(PinpadClear.run(rig, dir.resolve("work"),
                (r, workdir) -> VatsSession.openTerminal(r, workdir, environment(python)), say));
        assertEquals(List.of("pinpad-clear: a card was in the terminal's reader, left by an earlier session; removed"),
                say.outLines());
        assertTrue(fake.names().containsAll(List.of("insert_smartcard", "remove_smartcard")), fake.names().toString());
        assertTrue(Pauser.REAL.until(() -> !Ports.listening(km4), Duration.ofSeconds(10), Duration.ofMillis(100)),
                "the session's relay is stopped");
    }

    @Test
    void anEmptyReaderHasNothingToDo() throws IOException {
        FakeTerminal terminal = new FakeTerminal();
        terminal.cardin = 0;
        Rig rig = rig(11999);
        Say.Recorded say = new Say.Recorded();
        assertTrue(PinpadClear.run(rig, dir, (r, w) -> session(terminal, r), say));
        assertEquals(List.of("pinpad-clear: the terminal's reader holds no card; nothing to do"), say.outLines());
        assertTrue(terminal.closed, "the session is closed");
        terminal.closed = false;
        terminal.cardin = -1;
        Say.Recorded unknown = new Say.Recorded();
        PinpadClear.run(rig, dir, (r, w) -> session(terminal, r), unknown);
        assertEquals(List.of("pinpad-clear: the terminal's reader holds unknown; nothing to do"), unknown.outLines());
    }

    @Test
    void anUnreachableTerminalIsAWarningAndARigWithoutVatsIsAFailure() throws IOException {
        Say.Recorded say = new Say.Recorded();
        assertTrue(PinpadClear.run(rig(11999), dir, (r, w) -> {
            throw new DeviceUnreachable("the VATS relay", "exited with 1");
        }, say));
        assertEquals(List.of("pinpad-clear: WARN the terminal was not reachable: the VATS relay is unreachable: "
                + "exited with 1"), say.outLines());
        Say.Recorded none = new Say.Recorded();
        Rig viperpay = VatsRigs.load(dir, "viperpay", null);
        assertFalse(PinpadClear.run(viperpay, dir, (r, w) -> {
            throw new AssertionError("opened a session for a rig with no vats: block");
        }, none));
        assertEquals("pinpad-clear: the rig has no vats: block", none.lastErr());
    }

    @Test
    void aSessionOnARigWithNoStagedSdkIsUnavailable() throws IOException {
        Rig rig = rig(11999);
        DeviceUnavailable e = assertThrows(DeviceUnavailable.class,
                () -> VatsSession.openTerminal(rig, dir.resolve("work"), environment(null)));
        assertTrue(e.getMessage().contains("the VATS SDK is not available: not staged"), e.getMessage());
    }

    @Test
    void theCommandsExitCodesAreThePythons() throws IOException {
        VatsRigs.load(dir, "vats", "  host: pinpad.invalid");
        Map<String, String> env = Map.of("PETROTEST_RIGS_DIR", dir.toString());
        List<Rig> cleared = new ArrayList<>();
        PinpadClearCommand ok = new PinpadClearCommand((rig, e, say) -> cleared.add(rig));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream r = new PrintStream(err, true, StandardCharsets.UTF_8);
        assertEquals(0, ok.run(new String[] {"pinpad-clear", "--rig", "vatsbench"}, env, o, r));
        assertEquals("vatsbench", cleared.get(0).name());
        assertEquals(1, new PinpadClearCommand((rig, e, say) -> false)
                .run(new String[] {"--rig", "vatsbench"}, env, o, r));
        assertEquals(2, ok.run(new String[] {}, env, o, r));
        assertEquals(2, ok.run(new String[] {"--rig", "nosuch"}, env, o, r));
        assertEquals(2, ok.run(new String[] {"--bogus"}, env, o, r));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--rig is required"));
    }

    /** A session over an in-memory agent and a relay that was never started. */
    private static VatsSession session(FakeTerminal terminal, Rig rig) {
        VatsRelay relay = new VatsRelay(new VatsSdk(Path.of("absent")), "pinpad.invalid", 22001, 11999, Path.of("w"));
        VatsPinpad pad = new VatsPinpad(terminal, rig.vats(), new CardImages(List.of()), null, Duration.ofSeconds(1),
                "BTT is not started for a terminal-only session");
        return new VatsSession(relay, terminal, pad, Map.of());
    }
}
