package com.verifone.cat.commander.links;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.verifone.cat.commander.Pauser;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * {@code petrotest.commander_links} in CAT-Java: check first, import once, restart at most once. A port of
 * {@code tests/unit/test_commander_links.py}. Evidence about this repository: a scripted transport serves canned
 * {@code netstat} output and records the commands. The shapes are real (copied from the lab Commander, 2026-09-21
 * and 2026-10-07), including the {@code CLOSE_WAIT} POP row that made a socket-state check the wrong answer.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
class CommanderLinksTest {

    private static final String CONTROLLER = "192.0.2.10";
    private static final String NETSTAT = "netstat -ant";
    private static final String PINPAD = "192.0.2.126";
    private static final String COMMANDER = "192.0.2.11";
    private static final String LOGIN_GREP = "grep -E 'LogIn [*][*][*] Success' /var/log/messages | tail -n 1";

    private static final String HEADER = "Active Internet connections (servers and established)\n"
            + "Proto Recv-Q Send-Q Local Address           Foreign Address         State";
    private static final String LIVE_FEP = "tcp6       0      0 192.0.2.11:52370        192.0.2.10:41107        ESTABLISHED";
    private static final String HALF_CLOSED_POP = "tcp6      81      0 192.0.2.11:40258        192.0.2.10:5000         CLOSE_WAIT ";
    private static final String LISTENER = "tcp6       0      0 :::8080                 :::*                    LISTEN";

    private static final String LOGIN_1 =
            "user.info: 2026-09-28T23:15:07.239-04:00 commander_a COR  INFO  x - - LogIn *** Success";
    private static final String LOGIN_2 =
            "user.info: 2026-09-28T23:20:07.238-04:00 commander_a COR  INFO  x - - LogIn *** Success";

    private static final String POP_TABLE_XML = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <POPConfig xmlns="http://www.verifone.com/eps/viper/namespace/v1" version="0">
                <POPRow>
                    <POPID>001</POPID>
                    <IPAddressType>
                        <IPAddress>%s</IPAddress>
                        <Port>4000</Port>
                        <securePort>5000</securePort>
                    </IPAddressType>
                </POPRow>
            </POPConfig>
            """;

    @TempDir
    Path dir;

    /** A scripted commander shell: a responder per command, and every command it was sent. */
    static final class Box implements Transport {

        final Function<String, CommandResult> answer;
        final List<String> ran = new ArrayList<>();

        Box(Function<String, CommandResult> answer) {
            this.answer = answer;
        }

        @Override
        public String host() {
            return "commander.test";
        }

        @Override
        public CommandResult run(String command, Duration timeout) {
            ran.add(command);
            return answer.apply(command);
        }

        @Override
        public void put(String remotePath, byte[] content, int mode, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<byte[]> get(String remotePath, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void mkdir(String remotePath, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(String remotePath, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {}
    }

    /** A pauser that never waits: a check is tried up to 50 times, the way a poll would try it. */
    static final Pauser INSTANT = new Pauser() {
        @Override
        public void pause(Duration duration) {}

        @Override
        public boolean until(BooleanSupplier check, Duration timeout, Duration interval) {
            for (int i = 0; i < 50; i++) {
                if (check.getAsBoolean()) {
                    return true;
                }
            }
            return false;
        }
    };

    private static CommandResult ok(String stdout) {
        return new CommandResult(0, stdout, "");
    }

    private static CommandResult failed(int status, String stderr) {
        return new CommandResult(status, "", stderr);
    }

    /** A box whose {@code netstat} is {@code netstat} and that has no EPS login line in its syslog. */
    private static Box box(String netstat, Map<String, CommandResult> extra) {
        return new Box(command -> {
            if (command.equals(NETSTAT)) {
                return ok(netstat);
            }
            return extra.getOrDefault(command, failed(127, "no such command: " + command));
        });
    }

    private static Rig rig(Path dir, boolean vats) throws IOException {
        String pinpad = vats ? "pinpad: vats\nvats:\n  host: pinpad.testrig.invalid" : "pinpad: viperpay";
        String yaml = String.join("\n",
                "name: bench",
                "commander:",
                "  host: commander.invalid",
                "  ssh_user: root",
                "  eps_port: 41107",
                "registers:",
                "  - id: reg1",
                "    host: r1.invalid",
                "controller:",
                "  host: " + CONTROLLER,
                pinpad,
                "pos_shim: true",
                "build:",
                "  expected_version: \"057.02\"",
                "");
        Files.createDirectories(dir);
        Path file = dir.resolve(vats ? "vats.yaml" : "bench.yaml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return RigLoader.loadFile(file).rig();
    }

    private static CommanderLink buypass() {
        return new CommanderLink("buypass fep", CONTROLLER, 41107);
    }

    // ----------------------------------------------------------------------------------------------------
    // Reading the Commander's sockets
    // ----------------------------------------------------------------------------------------------------

    @Test
    void onlyEstablishedRowsCountAsALiveLink() {
        Box box = box(HEADER + "\n" + LIVE_FEP + "\n" + HALF_CLOSED_POP, Map.of());
        Set<CommanderLink.Endpoint> found = CommanderLinks.established(box, Duration.ofSeconds(5));
        assertTrue(found.contains(new CommanderLink.Endpoint(CONTROLLER, 41107)));
        assertFalse(found.contains(new CommanderLink.Endpoint(CONTROLLER, 5000)), "CLOSE_WAIT is not a working link");
    }

    static Stream<Arguments> pinpadSockets() {
        String gnu = "tcp6       1      0 ::ffff:" + COMMANDER + ":40258 ::ffff:" + PINPAD + ":5000 LAST_ACK\n"
                + "tcp6       0      0 ::ffff:" + COMMANDER + ":40312 ::ffff:" + PINPAD + ":5000 TIME_WAIT\n"
                + "tcp6       0      1 ::ffff:" + COMMANDER + ":40410 ::ffff:" + PINPAD + ":5000 SYN_SENT\n";
        String busybox = "tcp        1      0 192.0.2.11:40258        " + PINPAD + ":5000           LAST_ACK\n"
                + "tcp        0      0 192.0.2.11:40312        " + PINPAD + ":5000           TIME_WAIT\n"
                + "tcp        0      1 192.0.2.11:40410        " + PINPAD + ":5000           SYN_SENT\n";
        return Stream.of(Arguments.of(gnu), Arguments.of(busybox));
    }

    @ParameterizedTest
    @MethodSource("pinpadSockets")
    void peerSocketsKeepsEveryAcceptedSocketToThePinpad(String rows) {
        // 2026-10-07: the POP's sockets to the M400 sat in LAST_ACK (HANDOFF.md section 1.111).
        Box box = box(HEADER + "\n" + LISTENER + "\n" + LIVE_FEP + "\n" + HALF_CLOSED_POP + "\n" + rows, Map.of());
        assertEquals(Map.of(COMMANDER + ":40258", "LAST_ACK", COMMANDER + ":40312", "TIME_WAIT"),
                CommanderLinks.peerSockets(box, PINPAD, 5000, Duration.ofSeconds(5)));
    }

    @Test
    void peerSocketsRaisesOnAFailingNetstat() {
        Box box = new Box(command -> failed(1, "no netstat"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CommanderLinks.peerSockets(box, PINPAD, 5000, Duration.ofSeconds(5)));
        assertTrue(e.getMessage().contains("netstat"), e.getMessage());
    }

    @Test
    void listeningSocketsAreNotMistakenForOutboundLinks() {
        Box box = box(HEADER + "\n" + LISTENER, Map.of());
        assertTrue(CommanderLinks.established(box, Duration.ofSeconds(5)).isEmpty());
    }

    @Test
    void aFailingNetstatRaisesRatherThanReportingNothingConnected() {
        // Hard rule 5: an unavailable probe must not read as a dead rig.
        Box box = new Box(command -> failed(127, "not found"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CommanderLinks.established(box, Duration.ofSeconds(5)));
        assertTrue(e.getMessage().contains("netstat"), e.getMessage());
    }

    // ----------------------------------------------------------------------------------------------------
    // What the plan says
    // ----------------------------------------------------------------------------------------------------

    @Test
    void thePinpadPopIsNotGatedOnASocket() throws IOException {
        List<String> names = CommanderLinks.paymentLinks(rig(dir, false)).stream().map(CommanderLink::name).toList();
        assertEquals(List.of("buypass fep"), names);
    }

    @Test
    void linksAreAddressedFromTheRigNotFromALiteral() throws IOException {
        assertTrue(CommanderLinks.paymentLinks(rig(dir, false)).stream().allMatch(l -> l.host().equals(CONTROLLER)));
    }

    @Test
    void aCommanderHoldingEveryLinkIsReadyAndNeedsNoRestart() {
        LinkPlan plan = CommanderLinks.plan(List.of(buypass()), Set.of(new CommanderLink.Endpoint(CONTROLLER, 41107)));
        assertTrue(plan.ready());
        assertFalse(plan.restartNeeded());
        assertTrue(plan.summary().contains("no restart needed"), plan.summary());
    }

    @Test
    void aMissingLinkOwesExactlyOneRestart() {
        LinkPlan plan = CommanderLinks.plan(List.of(buypass()), Set.of());
        assertFalse(plan.ready());
        assertTrue(plan.restartNeeded());
        assertTrue(plan.summary().contains("buypass fep"), plan.summary());
    }

    @Test
    void anEmptyPlanIsReady() {
        assertTrue(LinkPlan.empty().ready());
    }

    // ----------------------------------------------------------------------------------------------------
    // The whole gate
    // ----------------------------------------------------------------------------------------------------

    @Test
    void aReadyCommanderIsNotImportedIntoAndNotRestarted() {
        Box box = box(HEADER + "\n" + LIVE_FEP, Map.of());
        int[] imports = {0};
        int[] restarts = {0};
        LinkPlan plan = CommanderLinks.ensurePaymentLinks(List.of(buypass()), box, () -> imports[0]++,
                t -> restarts[0]++, INSTANT, false);
        assertTrue(plan.ready());
        assertFalse(plan.restarted());
        assertEquals(0, imports[0], "nothing to fix, so nothing should be imported");
        assertEquals(0, restarts[0], "nothing to fix, so the Commander must not be restarted");
        assertEquals(List.of(NETSTAT), box.ran, "one check, no side effects");
    }

    @Test
    void aBrokenCommanderImportsOnceAndRestartsOnce() {
        AtomicInteger netstat = new AtomicInteger();
        Box box = new Box(command -> {
            if (command.equals(NETSTAT)) {
                return ok(HEADER + "\n" + (netstat.incrementAndGet() == 1 ? "" : LIVE_FEP));
            }
            return failed(1, "");
        });
        int[] imports = {0};
        int[] restarts = {0};
        LinkPlan plan = CommanderLinks.ensurePaymentLinks(List.of(buypass()), box, () -> imports[0]++,
                t -> restarts[0]++, INSTANT, false);
        assertTrue(plan.ready(), plan.summary());
        assertTrue(plan.restarted());
        assertEquals(1, imports[0], "imported exactly once, for all missing links together");
        assertEquals(1, restarts[0], "restarted exactly once, not once per link");
    }

    @Test
    void theRestartUsesTheCommandersOwnServiceCommandNotAReboot() {
        Box box = box(HEADER + "\n" + LIVE_FEP, Map.of(CommanderLinks.PMC_RESTART.get(0), ok("")));
        CommanderLinks.restartPmc(box, Duration.ofSeconds(5));
        assertEquals(CommanderLinks.PMC_RESTART, box.ran.subList(box.ran.size() - 1, box.ran.size()));
        assertTrue(box.ran.stream().noneMatch(c -> c.contains("reboot")));
        assertTrue(box.ran.stream().anyMatch(c -> c.contains("stopapp -r")));
    }

    @Test
    void theRestartRunsFromPmcsOwnLoginShell() {
        assertTrue(CommanderLinks.PMC_RESTART.stream().allMatch(c -> c.startsWith("su - pmc -c ")));
        assertTrue(CommanderLinks.PMC_RESTART.stream().noneMatch(c -> c.contains("sudo")));
    }

    @Test
    void aFailingRestartIsRaisedNotSwallowed() {
        Box box = box(HEADER + "\n" + LIVE_FEP, Map.of(CommanderLinks.PMC_RESTART.get(0), failed(1, "denied")));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CommanderLinks.restartPmc(box, Duration.ofSeconds(5)));
        assertTrue(e.getMessage().contains("denied"), e.getMessage());
    }

    @Test
    void aLinkPrintsAsNameHostAndPort() {
        assertEquals("buypass fep -> " + CONTROLLER + ":41107", new CommanderLink("buypass fep", CONTROLLER, 41107)
                .toString());
    }

    @Test
    void theLoginWaitEndsOnANewerLoginEvenIfTheSyslogRotated() {
        // Counting logins in the syslog's last 4 MB stopped rising once the file rotated (2026-09-28).
        AtomicInteger netstat = new AtomicInteger();
        List<String> logins = new ArrayList<>(List.of(LOGIN_1, "", LOGIN_2));
        Box box = new Box(command -> {
            if (command.equals(NETSTAT)) {
                return ok(HEADER + "\n" + (netstat.incrementAndGet() == 1 ? "" : LIVE_FEP));
            }
            if (command.equals(LOGIN_GREP)) {
                return ok(logins.remove(0) + "\n");
            }
            return failed(1, "");
        });
        LinkPlan plan = CommanderLinks.ensurePaymentLinks(List.of(buypass()), box, null, t -> {}, INSTANT, false);
        assertTrue(plan.notes().contains("the Commander logged back into its EPS"), plan.notes().toString());
    }

    @Test
    void anAnswerThatIsNotALoginLineIsNoReading() {
        Box box = new Box(command -> ok("Active Internet connections\n"));
        assertTrue(CommanderLinks.epsLastLogin(box, Duration.ofSeconds(5)).isEmpty());
    }

    // ----------------------------------------------------------------------------------------------------
    // POP 1: the pinpad the Commander dials (HANDOFF.md section 1.110)
    // ----------------------------------------------------------------------------------------------------

    @Test
    void popAddressIsReadFromPmcsTable() {
        Box box = box(HEADER + "\n" + LIVE_FEP, Map.of("cat " + CommanderLinks.POP_TABLE,
                ok(POP_TABLE_XML.formatted(PINPAD))));
        assertEquals(Optional.of(PINPAD), CommanderLinks.popAddress(box, Duration.ofSeconds(5)));
    }

    @Test
    void thePinpadIsTheTerminalOnAVatsRigAndTheControllerOtherwise() throws IOException {
        assertEquals(CONTROLLER, CommanderLinks.pinpadHost(rig(dir, false)));
        assertEquals("pinpad.testrig.invalid", CommanderLinks.pinpadHost(rig(dir.resolve("v"), true)));
    }

    @Test
    void aWrongPopForcesOneImportAndRestartEvenWithEveryLinkUp() {
        Box box = box(HEADER + "\n" + LIVE_FEP, Map.of("cat " + CommanderLinks.POP_TABLE,
                ok(POP_TABLE_XML.formatted(PINPAD))));
        int[] imports = {0};
        int[] restarts = {0};
        LinkPlan plan = CommanderLinks.ensurePaymentLinks(List.of(buypass()), box, () -> imports[0]++,
                t -> restarts[0]++, INSTANT, true);
        assertTrue(plan.restarted());
        assertEquals(1, imports[0]);
        assertEquals(1, restarts[0]);
    }

    /** The petroapps layout the staging reads: the simulator folder and the physical-pinpad folder. */
    private Path petroapps() throws IOException {
        Path configs = dir.resolve("petroapps").resolve("AutomationTests").resolve("configurations");
        String viper = "<popEntry><ipAddress>%s</ipAddress><securePortNumber>5000</securePortNumber></popEntry>";
        for (Map.Entry<String, String> folder : Map.of("buypass-host-simulator-config", "192.0.2.160",
                "buypass-viper-pinpad-devices", PINPAD).entrySet()) {
            Files.createDirectories(configs.resolve(folder.getKey()));
            Files.writeString(configs.resolve(folder.getKey()).resolve("vipercfg.xml"),
                    viper.formatted(folder.getValue()), StandardCharsets.UTF_8);
        }
        Files.writeString(configs.resolve("buypass-host-simulator-config").resolve("fepcfg&fep=buypass.xml"),
                "<fep/>", StandardCharsets.UTF_8);
        return dir.resolve("petroapps");
    }

    @Test
    void stagedConfigPointsPop1AtTheRigsOwnPinpad() throws IOException {
        Path root = petroapps();
        Rig simulator = rig(dir, false);
        Rig vats = rig(dir.resolve("v"), true);
        for (Map.Entry<Rig, String> case_ : Map.of(simulator, CONTROLLER, vats, "pinpad.testrig.invalid").entrySet()) {
            Path staged = CommanderLinks.stagePaymentConfig(root, case_.getKey(),
                    dir.resolve("out-" + case_.getValue()));
            try (java.util.stream.Stream<Path> names = Files.list(staged)) {
                assertEquals(List.of("fepcfg&fep=buypass.xml", "vipercfg.xml"),
                        names.map(p -> p.getFileName().toString()).sorted().toList());
            }
            assertTrue(Files.readString(staged.resolve("vipercfg.xml"))
                    .contains("<ipAddress>" + case_.getValue() + "</ipAddress>"));
        }
    }

    // ----------------------------------------------------------------------------------------------------
    // The Buypass FEP's protocol, which a held socket does not prove
    // ----------------------------------------------------------------------------------------------------

    /** The shape of petroapps' {@code buypass-host-simulator-config/fepcfg&fep=buypass.xml}. */
    private static final String SIMULATOR_FEP =
            "<viper:fepConfig xmlns:viper=\"core.viper.verifone.com.2011-11-08\">"
            + "<ip><address>\n</address><port>41107\n</port></ip>"
            + "<sslEnabled>false</sslEnabled>"
            + "<secureChannelSettings><secureChannelEnabled>false</secureChannelEnabled>"
            + "</secureChannelSettings></viper:fepConfig>";

    @Test
    void aFepConfigMatchingTheSimulatorHasNoDrift() {
        assertEquals(List.of(), CommanderLinks.fepConfigDrift(SIMULATOR_FEP, SIMULATOR_FEP));
    }

    @Test
    void sslOnTheCommanderIsDriftEvenWithTheLinkUp() {
        // The lab Commander on 2026-10-12, run c5c26c1b2b08: sslEnabled true, port right.
        String live = SIMULATOR_FEP.replace("<sslEnabled>false", "<sslEnabled>true");
        assertEquals(List.of("sslEnabled true (want false)"), CommanderLinks.fepConfigDrift(live, SIMULATOR_FEP));
    }

    @Test
    void aFieldTheCommanderLacksIsDriftAndOneTheSimulatorOmitsIsNot() {
        String live = SIMULATOR_FEP.replace("<sslEnabled>false</sslEnabled>", "");
        assertEquals(List.of("sslEnabled absent (want false)"), CommanderLinks.fepConfigDrift(live, SIMULATOR_FEP));
        assertEquals(List.of("port 41108 (want 41107)"),
                CommanderLinks.fepConfigDrift(live.replace("41107", "41108"), live));
    }

}
