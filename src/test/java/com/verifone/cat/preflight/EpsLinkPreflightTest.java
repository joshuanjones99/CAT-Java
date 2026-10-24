package com.verifone.cat.preflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.verifone.cat.devices.eps.CommanderLinkEpsProbe;
import com.verifone.cat.devices.eps.CommanderLinks;
import com.verifone.cat.devices.eps.TestRig;
import com.verifone.cat.posshim.FakeShim;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.InMemoryTransport;
import com.verifone.cat.rig.Rig;

/**
 * {@code eps_reachable} (DESIGN.md 6.2 row 8) and {@code pumps_idle} (row 6) through the real registry: a port of
 * the Python CAT's {@code tests/unit/test_preflight_eps_link.py}, plus the forecourt rows.
 *
 * <p>Nothing under test is patched. Every verdict comes out of {@code PreflightRunner.run(RigContextFactory.build(...),
 * DefaultChecks.defaults())}, the path {@code cat-java preflight} takes; only the transport at the far end is a fake
 * ({@link InMemoryTransport} serving {@code netstat}), and the register's shim is a {@link FakeShim} on loopback.
 * The row shapes are the lab Commander's from 2026-09-21, with {@code .invalid} names where the addresses were.
 */
class EpsLinkPreflightTest {

    private static final String HEADER = "Active Internet connections (servers and established)\n"
            + "Proto Recv-Q Send-Q Local Address           Foreign Address         State";
    private static final String C = TestRig.COMMANDER;
    private static final String K = TestRig.CONTROLLER;
    private static final int PORT = TestRig.EPS_PORT;
    private static final String LIVE_FEP = "tcp6       0      0 " + C + ":52370        " + K + ":" + PORT + "        ESTABLISHED";
    private static final String HALF_CLOSED = "tcp6      81      0 " + C + ":40258        " + K + ":" + PORT + "  CLOSE_WAIT ";
    private static final String LISTENING = "tcp6       0      0 :::" + PORT + "                 :::*              LISTEN";
    private static final String UNRELATED = "tcp6       0      0 " + C + ":8080         " + K + ":50112        ESTABLISHED";

    @TempDir
    Path dir;

    private FakeShim shim;
    private Rig rig;

    @BeforeEach
    void start() {
        shim = new FakeShim();
        rig = TestRig.load(dir, "", "", shim.host());
    }

    @AfterEach
    void stop() {
        shim.close();
    }

    private static InMemoryTransport transport(String stdout, int exit) {
        return new InMemoryTransport(C).command(CommanderLinks.NETSTAT, new CommandResult(exit, stdout, ""));
    }

    private PreflightReport run(RigContextFactory.Sources sources) {
        RigContext context = RigContextFactory.build(rig, rig.registers().get(0), shim.port(), Map.of(), sources);
        return PreflightRunner.run(context, DefaultChecks.defaults().checks(),
                new PreflightRunner.RunOptions(false, PreflightRunner.VERIFY_BUDGET, false));
    }

    private CheckRecord eps(InMemoryTransport transport) {
        return record(run(new RigContextFactory.Sources(CommanderLinkEpsProbe.fromRig(rig, () -> transport),
                () -> new Surfaces.UnsettledSuspends(0, ""), null)), "eps_reachable");
    }

    private static CheckRecord record(PreflightReport report, String name) {
        return report.records().stream().filter(r -> r.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " in\n" + report.render()));
    }

    // -- a healthy rig passes -----------------------------------------------------------------

    @Test
    void anEstablishedLinkToTheControllersPaymentHostPasses() {
        InMemoryTransport t = transport(String.join("\n", HEADER, UNRELATED, LIVE_FEP), 0);
        CheckRecord record = eps(t);
        assertEquals(Outcome.PASS, record.first().outcome(), record.render());
        assertEquals(List.of(CommanderLinks.NETSTAT), t.ran(), "read-only: verify ran netstat and nothing else");
        assertTrue(t.closed(), "one SSH session per probe, always closed");
    }

    @Test
    void anIpv4MappedPeerOnADualStackSocketStillCounts() {
        String mapped = "tcp6       0      0 " + C + ":52370   ::ffff:" + K + ":" + PORT + "   ESTABLISHED";
        assertEquals(Outcome.PASS, eps(transport(HEADER + "\n" + mapped, 0)).first().outcome());
    }

    // -- a broken one fails, for the true reason ----------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"absent", "close-wait", "listen", "none"})
    void noLiveLinkFailsAndNamesTheLink(String shape) {
        String netstat = switch (shape) {
            case "absent" -> HEADER + "\n" + UNRELATED;
            case "close-wait" -> HEADER + "\n" + HALF_CLOSED;
            case "listen" -> HEADER + "\n" + LISTENING;
            default -> "";
        };
        CheckRecord record = eps(transport(netstat, 0));
        assertEquals(Outcome.FAIL, record.first().outcome(), record.render());
        assertTrue(record.first().detail().contains("buypass fep -> " + K + ":" + PORT), record.first().detail());
        assertTrue(record.first().detail().contains("ESTABLISHED"), record.first().detail());
    }

    @Test
    void theLinkIsLookedForOnTheControllerNotOnTheCommander() {
        String toTheCommander = "tcp6  0  0 " + K + ":40000  " + C + ":" + PORT + "  ESTABLISHED";
        assertEquals(Outcome.FAIL, eps(transport(toTheCommander, 0)).first().outcome());
    }

    // -- cannot tell is ERROR, never FAIL (hard rule 5) ---------------------------------------

    @Test
    void noSshKeyIsAnErrorInTheReportNotAFailedPaymentPath() {
        CheckRecord record = record(run(new RigContextFactory.Sources(CommanderLinkEpsProbe.fromRig(rig, Map.of()),
                () -> new Surfaces.UnsettledSuspends(0, ""), null)), "eps_reachable");
        assertEquals(Outcome.ERROR, record.first().outcome(), record.render());
        assertTrue(record.first().detail().contains("SSH key"), record.first().detail());
    }

    @Test
    void aFailingNetstatIsAnErrorNotAMissingLink() {
        InMemoryTransport t = transport("", 127);
        CheckRecord record = eps(t);
        assertEquals(Outcome.ERROR, record.first().outcome(), record.render());
        assertTrue(record.first().detail().contains("netstat"), record.first().detail());
        assertTrue(t.closed());
    }

    @Test
    void theRepairNamesTheEscalationAndTouchesNothing() {
        Result repair = new ServiceChecks.EpsReachable().repair(Fixtures.rig(new FakePos()));
        assertEquals(Outcome.FAIL, repair.outcome());
        assertTrue(repair.detail().contains("provision --rig X --force"), repair.detail());
    }

    // -- the parked-ticket and forecourt rows from the same build -----------------------------

    @Test
    void theParkedTicketRowReadsTheCommandersCount() {
        PreflightReport report = run(new RigContextFactory.Sources(
                CommanderLinkEpsProbe.fromRig(rig, () -> transport(LIVE_FEP, 0)),
                () -> new Surfaces.UnsettledSuspends(1, "18.25"), null));
        CheckRecord record = record(report, "no_parked_transaction");
        assertEquals(Outcome.FAIL, record.first().outcome(), record.render());
        assertTrue(record.first().detail().contains("1 parked ticket(s) totalling 18.25"), record.first().detail());
    }

    @Test
    void pumpsWithNoForecourtAreAnErrorNotAFail() {
        CheckRecord record = record(run(new RigContextFactory.Sources(
                CommanderLinkEpsProbe.fromRig(rig, () -> transport(LIVE_FEP, 0)),
                () -> new Surfaces.UnsettledSuspends(0, ""), null)), "pumps_idle");
        assertEquals(Outcome.ERROR, record.first().outcome(), record.render());
        assertTrue(record.first().detail().contains("no forecourt"), record.first().detail());
    }

    @Test
    void idlePumpsPassAndABusyOneFailsNamingWhy() {
        Surfaces.PumpStatus idle = numbers -> numbers.stream()
                .map(n -> new Surfaces.PumpState(n, "idle", false, false)).toList();
        CheckRecord pass = record(run(new RigContextFactory.Sources(
                CommanderLinkEpsProbe.fromRig(rig, () -> transport(LIVE_FEP, 0)),
                () -> new Surfaces.UnsettledSuspends(0, ""), idle)), "pumps_idle");
        assertEquals(Outcome.PASS, pass.first().outcome(), pass.render());
        assertTrue(pass.first().detail().contains("2 pump(s) idle"), pass.first().detail());

        Surfaces.PumpStatus busy = numbers -> List.of(new Surfaces.PumpState(1, "idle", false, false),
                new Surfaces.PumpState(2, "fuelling", true, true));
        CheckRecord fail = record(run(new RigContextFactory.Sources(
                CommanderLinkEpsProbe.fromRig(rig, () -> transport(LIVE_FEP, 0)),
                () -> new Surfaces.UnsettledSuspends(0, ""), busy)), "pumps_idle");
        assertEquals(Outcome.FAIL, fail.first().outcome(), fail.render());
        assertTrue(fail.first().detail().contains("2=fuelling/handle-up/prepay"), fail.first().detail());
        assertEquals(Outcome.UNREPAIRABLE, new ForecourtChecks.PumpsIdle().repair(Fixtures.rig(new FakePos())).outcome());
    }

    @Test
    void thePumpRowIsHeldBackMidRunAndSkippedOnAnIndoorRig() {
        Surfaces.PumpStatus idle = numbers -> List.of();
        RigContext context = RigContextFactory.build(rig, rig.registers().get(0), shim.port(), Map.of(),
                new RigContextFactory.Sources(CommanderLinkEpsProbe.fromRig(rig, () -> transport(LIVE_FEP, 0)),
                        null, idle));
        PreflightReport midRun = PreflightRunner.run(context, DefaultChecks.defaults().checks(),
                new PreflightRunner.RunOptions(false, PreflightRunner.VERIFY_BUDGET, true));
        assertTrue(midRun.heldBack().containsKey("pumps_idle"), midRun.render());
        assertTrue(midRun.records().stream().anyMatch(r -> r.name().equals("eps_reachable")), midRun.render());

        RigContext indoor = new RigContext("indoor", new FakePos(), new FakePos(),
                CommanderLinkEpsProbe.fromRig(rig, () -> transport(LIVE_FEP, 0)),
                Surfaces.unavailable(Surfaces.ConfigStatus.class, "config", "not under test"),
                Surfaces.unavailable(Surfaces.ConfigControl.class, "config", "not under test"),
                new Expectations("", "", "", "", List.of()));
        PreflightReport report = PreflightRunner.run(indoor, DefaultChecks.defaults().checks(),
                PreflightRunner.RunOptions.defaults());
        assertEquals(List.of("pumps_idle"), report.skipped());
        assertFalse(report.records().stream().anyMatch(r -> r.name().equals("pumps_idle")));
    }

    @Test
    void theContextCarriesTheRigsDefaultWaitIntoItsReadOnlyView() {
        RigContext context = RigContextFactory.build(rig, rig.registers().get(0), shim.port(), Map.of(),
                new RigContextFactory.Sources(CommanderLinkEpsProbe.fromRig(rig, () -> transport(LIVE_FEP, 0)),
                        null, null));
        assertEquals(rig.timeouts().defaultWait(), context.defaultWait());
        assertEquals(context.defaultWait(), context.readOnly().defaultWait());
    }
}
