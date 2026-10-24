package com.verifone.cat.devices.eps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.InMemoryTransport;
import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.rig.Rig;

/**
 * {@link CommanderLinks} and {@link CommanderLinkEpsProbe} against {@link InMemoryTransport}: the read-only half
 * of the Python CAT's {@code commander_links} tests and the probe half of {@code test_preflight_eps_link.py}.
 * The netstat row shapes are the lab Commander's from 2026-09-21, with {@code .invalid} names where the
 * addresses were. Evidence about this repository, not about a Commander.
 */
class CommanderLinksTest {

    static final String HEADER = "Active Internet connections (servers and established)\n"
            + "Proto Recv-Q Send-Q Local Address           Foreign Address         State";
    static final String C = TestRig.COMMANDER;
    static final String K = TestRig.CONTROLLER;
    static final int PORT = TestRig.EPS_PORT;
    static final String LIVE_FEP = "tcp6       0      0 " + C + ":52370        " + K + ":" + PORT + "        ESTABLISHED";
    static final String HALF_CLOSED_FEP = "tcp6      81      0 " + C + ":40258        " + K + ":" + PORT + "  CLOSE_WAIT ";
    static final String LISTENING = "tcp6       0      0 :::" + PORT + "                 :::*              LISTEN";
    static final String UNRELATED = "tcp6       0      0 " + C + ":8080         " + K + ":50112        ESTABLISHED";

    private static final Duration T = Duration.ofSeconds(5);

    @TempDir
    Path dir;

    static InMemoryTransport transport(String stdout, int exit) {
        return new InMemoryTransport(C).command(CommanderLinks.NETSTAT, new CommandResult(exit, stdout, ""));
    }

    @Test
    void thePaymentLinkIsTheBuypassFepOnTheController() {
        Rig rig = TestRig.load(dir);
        List<CommanderLink> links = CommanderLinks.paymentLinks(rig);
        assertEquals(List.of(new CommanderLink("buypass fep", K, PORT)), links);
        assertEquals("buypass fep -> " + K + ":" + PORT, links.get(0).toString());
        assertEquals(41107, CommanderLinks.BUYPASS_FEP_PORT);
    }

    @Test
    void onlyEstablishedPeersAreLive() {
        Set<CommanderLinks.Endpoint> live = CommanderLinks.establishedEndpoints(
                transport(String.join("\n", HEADER, LIVE_FEP, HALF_CLOSED_FEP, LISTENING, UNRELATED), 0), T);
        assertEquals(Set.of(new CommanderLinks.Endpoint(K, PORT), new CommanderLinks.Endpoint(K, 50112)), live);
    }

    @Test
    void headerAndListenerRowsFallOutAndMappedAddressesAreUnwrapped() {
        List<CommanderLinks.NetstatRow> rows = CommanderLinks.parse(String.join("\n", HEADER, LISTENING,
                "tcp6  0  0 ::ffff:" + C + ":52370   ::ffff:" + K + ":" + PORT + "   ESTABLISHED", "", "x"));
        assertEquals(List.of(new CommanderLinks.NetstatRow(C + ":52370", K, PORT, "ESTABLISHED")), rows);
    }

    @Test
    void peerSocketsCountEveryStateTheFarEndAccepted() {
        String text = String.join("\n", HEADER,
                "tcp 0 0 " + C + ":40001 " + K + ":5000 LAST_ACK",
                "tcp 0 0 " + C + ":40002 " + K + ":5000 TIME_WAIT",
                "tcp 0 0 " + C + ":40003 " + K + ":5000 SYN_SENT",
                "tcp 0 0 " + C + ":40004 " + K + ":5001 ESTABLISHED");
        Map<String, String> sockets = CommanderLinks.peerSockets(transport(text, 0), K, 5000, T);
        assertEquals(Map.of(C + ":40001", "LAST_ACK", C + ":40002", "TIME_WAIT"), sockets);
    }

    @Test
    void aPlanSplitsWantedLinksAndSummarisesThem() {
        CommanderLink fep = new CommanderLink("buypass fep", K, PORT);
        CommanderLink pop = new CommanderLink("viperpay pop", K, 5000);
        LinkPlan plan = CommanderLinks.plan(List.of(fep, pop), Set.of(new CommanderLinks.Endpoint(K, PORT)));
        assertEquals(List.of(fep), plan.present());
        assertEquals(List.of(pop), plan.missing());
        assertFalse(plan.ready());
        assertTrue(plan.restartNeeded());
        assertEquals("commander missing 1 payment link(s): viperpay pop -> " + K + ":5000", plan.summary());

        LinkPlan ready = CommanderLinks.plan(List.of(fep), Set.of(new CommanderLinks.Endpoint(K, PORT)));
        assertEquals("commander ready: 1 payment link(s) up, no restart needed", ready.summary());
    }

    @Test
    void aFailingNetstatRaisesRatherThanReportingNoSockets() {
        DeviceError e = assertThrows(DeviceError.class,
                () -> CommanderLinks.establishedEndpoints(transport("", 127), T));
        assertTrue(e.getMessage().contains("netstat"), e.getMessage());
    }

    // -- the probe --------------------------------------------------------------------------

    @Test
    void theProbeRunsNetstatOnlyAndClosesItsTransport() {
        InMemoryTransport t = transport(String.join("\n", HEADER, UNRELATED, LIVE_FEP), 0);
        CommanderLinkEpsProbe probe = CommanderLinkEpsProbe.fromRig(TestRig.load(dir), () -> t);
        assertTrue(probe.reachable(T));
        assertEquals(List.of(CommanderLinks.NETSTAT), t.ran());
        assertTrue(t.closed());
        assertEquals("buypass fep -> " + K + ":" + PORT, probe.describe());
    }

    @Test
    void theLinkIsLookedForOnTheControllerNotOnTheCommander() {
        String toCommander = "tcp6  0  0 " + K + ":40000  " + C + ":" + PORT + "  ESTABLISHED";
        CommanderLinkEpsProbe probe = CommanderLinkEpsProbe.fromRig(TestRig.load(dir), () -> transport(toCommander, 0));
        assertFalse(probe.reachable(T));
        assertEquals(1, probe.missing(T).size());
    }

    @Test
    void aTransportIsClosedEvenWhenNetstatFails() {
        InMemoryTransport t = transport("", 1);
        CommanderLinkEpsProbe probe = CommanderLinkEpsProbe.fromRig(TestRig.load(dir), () -> t);
        assertThrows(DeviceError.class, () -> probe.reachable(T));
        assertTrue(t.closed());
    }

    @Test
    void aRigWithNoKeyVariableCannotLook() {
        CommanderLinkEpsProbe probe = CommanderLinkEpsProbe.fromRig(TestRig.load(dir), Map.of());
        DeviceUnavailable e = assertThrows(DeviceUnavailable.class, () -> probe.reachable(T));
        assertTrue(e.getMessage().contains("no SSH key"), e.getMessage());
        assertTrue(e.getMessage().contains("the rig file sets no commander.ssh_key_path_env"), e.getMessage());
    }

    @Test
    void aKeyVariableThatIsNotSetIsNamed() {
        Rig rig = TestRig.load(dir, "ssh_key_path_env: PETROTEST_TEST_NO_SUCH_KEY", "");
        CommanderLinkEpsProbe probe = CommanderLinkEpsProbe.fromRig(rig, Map.of());
        DeviceUnavailable e = assertThrows(DeviceUnavailable.class, () -> probe.reachable(T));
        assertTrue(e.getMessage().contains("$PETROTEST_TEST_NO_SUCH_KEY is not set"), e.getMessage());
    }

    @Test
    void aResolvedKeyGivesAnSshOpenerThatHasNotConnected() {
        Rig rig = TestRig.load(dir, "ssh_key_path_env: PETROTEST_TEST_KEY", "");
        CommanderSsh.Opener opener = CommanderSsh.opener(rig, Map.of("PETROTEST_TEST_KEY", "C:/nowhere/key"));
        assertTrue(opener.available());
        // Building the transport connects nothing; it is never used here.
        try (var transport = opener.open().get()) {
            assertInstanceOf(JschTransport.class, transport);
            assertEquals(C, transport.host());
        }
    }
}
