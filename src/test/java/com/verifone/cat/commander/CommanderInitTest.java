package com.verifone.cat.commander;

import static com.verifone.cat.commander.CommanderFixtures.HOST;
import static com.verifone.cat.commander.CommanderFixtures.sent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.commander.CommanderFixtures.CommanderSsh;
import com.verifone.cat.commander.CommanderFixtures.FakeClient;
import com.verifone.cat.commander.snapshot.Restore;
import com.verifone.cat.commander.snapshot.SnapshotRestore;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.commander.FakeCommander;
import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.InMemoryTransport;
import com.verifone.cat.rig.Rig;

/**
 * {@link CommanderInit}: the base import, then five commands 5 s apart, and every recovery the Python's
 * {@code test_commander_init.py} covers. The petroapps base folder is a {@code @TempDir}; the Commander is a
 * recording client (once the real {@link PdkShim} on a loopback {@link FakeCommander}), its shell a fake.
 * Evidence about the ordering here, not about a rig.
 */
class CommanderInitTest {

    private static final String NOT_IDLE = "user.info: 2026-09-29T03:57:10.63-04:00 commander_a FCC  INFO  "
            + "DcrSiteAdmin [RMI] - All DCRs are not idle. Position = 9, and State = DCRPUMPAUTH\n";

    @TempDir
    Path dir;

    private Rig rig;
    private Path base;
    private final List<Object> calls = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, InMemoryTransport> registers = new HashMap<>();
    private final Say.Recorded say = new Say.Recorded();

    @BeforeEach
    void setUp() throws IOException {
        rig = CommanderFixtures.rig(dir);
        base = Files.createDirectories(dir.resolve("configurations").resolve("base"));
    }

    private void resetFolder() throws IOException {
        Files.createDirectories(base.resolveSibling("FuelWithoutAdditionalData"));
    }

    /** Fast FCC restarts (no settle, no resends after one), as the Python's {@code _fast_fcc_restart}. */
    private static CommanderInit.Tunables fast() {
        return CommanderInit.Tunables.defaults()
                .withFccRestart(Duration.ZERO, Duration.ZERO, Duration.ofSeconds(15), Duration.ofMillis(10));
    }

    /** The base folders the snapshot seam was asked to restore before, in order. */
    private final List<Path> restores = new ArrayList<>();

    /** Never the real restore: it would read C:\CICD\Snapshots. The snapshot's own tests drive the real one. */
    private SnapshotRestore snapshots = (folder, line) -> {
        restores.add(folder);
        line.accept("no Commander snapshot (test seam)");
        return null;
    };

    private CommanderInit.Builder init(CommanderClient client, CommanderSsh ssh) {
        return CommanderInit.builder(CommanderFixtures.access(rig, ssh, registers))
                .snapshots((folder, line) -> snapshots.restore(folder, line))
                .clients(() -> client)
                .pauser(CommanderFixtures.recording(calls))
                .tunables(fast())
                .petroLog(() -> dir.resolve("no-petro.log"))
                .pumps(pump -> null)
                .say(say);
    }

    private boolean run(CommanderClient client, CommanderSsh ssh) {
        return init(client, ssh).build().run(base, true, true);
    }

    private List<String> twice(List<String> first, List<String> then) {
        List<String> out = new ArrayList<>(first);
        out.addAll(then);
        return out;
    }

    // -- the plain run ----------------------------------------------------------------------------

    @Test
    void theBaseImportThenTheFiveCommands5sApart() throws IOException {
        resetFolder();
        assertTrue(run(new FakeClient(calls), new CommanderSsh("")));
        Duration five = Duration.ofSeconds(5);
        assertEquals(List.of("import:base", "import:FuelWithoutAdditionalData", "crefreshcfg", five, "cfueldrvinit",
                five, "cfuelinit", five, "cdcrdriverinit", five, "cdcrinit", "closed"), calls);
    }

    @Test
    void eachCommandIsSentOnceThroughTheRealClientAndItsFaultFailsTheInit() {
        try (FakeCommander fake = new FakeCommander(request -> switch (request.cmd()) {
            case "validate" -> FakeCommander.Reply.ok(FakeCommander.FRESH_COOKIE);
            case "cfuelinit" -> FakeCommander.Reply.ok(FakeCommander.fault("CGIPortal.InternalError"));
            default -> FakeCommander.Reply.ok(FakeCommander.NS_OK);
        })) {
            PdkShim shim = PdkShim.onPort(fake.host(), fake.port(), Duration.ofSeconds(5), null);
            CommanderInit init = init(CommanderClient.of(shim), new CommanderSsh("")).build();

            assertFalse(init.run(base, true, true));
            List<String> posted = fake.requests.stream().map(FakeCommander.Request::cmd)
                    .filter(cmd -> !cmd.equals("validate")).toList();
            assertEquals(List.of("crefreshcfg", "cfueldrvinit", "cfuelinit"), posted);
            assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(5)), calls);
            assertTrue(say.lastErr().contains("CGIPortal.InternalError"), say.lastErr());
        }
    }

    @Test
    void aFailedInitCommandStopsTheSequenceAndFails() {
        assertFalse(run(new FakeClient(calls, "cfuelinit"), new CommanderSsh("")));
        assertFalse(calls.contains("cdcrdriverinit"));
        assertEquals("closed", calls.get(calls.size() - 1));
    }

    @Test
    void everyCommandAndItsAnswerIsLogged() {
        run(new FakeClient(calls), new CommanderSsh(""));
        String out = say.outText();
        assertTrue(out.contains("sending crefreshcfg (1 of 5)"), out);
        assertTrue(out.contains("crefreshcfg answered in"), out);
        List<String> lines = say.outLines();
        assertEquals("commander init complete", lines.get(lines.size() - 1));
    }

    @Test
    void withoutTheForecourtOnlyCrefreshcfgIsSent() {
        assertTrue(init(new FakeClient(calls), new CommanderSsh("")).build().run(base, false, true));
        assertEquals(List.of("import:base", "crefreshcfg", "closed"), calls);
        assertTrue(say.errText().contains("FuelWithoutAdditionalData not reset"), say.errText());
        assertTrue(say.outText().contains("no forecourt simulator: skipping the fuel and DCR driver inits"));
    }

    @Test
    void aMissingBaseFolderFailsTheInitAndNamesIt() {
        Path absent = dir.resolve("nowhere").resolve("base");
        assertFalse(init(new FakeClient(calls), new CommanderSsh("")).build().run(absent, true, true));
        assertTrue(say.lastErr().startsWith("commander init on " + HOST + " failed: cannot read " + absent),
                say.lastErr());
        assertEquals(List.of("closed"), calls);
    }

    // -- the base folder, read live -------------------------------------------------------------

    @Test
    void theBaseFolderIsReadFromPetroappsAtCallTime() {
        Path root = dir.resolve("petroapps");
        assertEquals(root.resolve("AutomationTests").resolve("configurations").resolve("base"),
                CommanderInit.baseConfigFolder(Map.of(CommanderInit.PETROAPPS_ENV, root.toString())));
        Path fallback = CommanderInit.baseConfigFolder(Map.of());
        assertEquals(CommanderInit.DEFAULT_PETROAPPS_ROOT.resolve("AutomationTests").resolve("configurations")
                .resolve("base"), fallback);
    }

    @Test
    void noCommittedCopyOfTheConfigurationIsInThisRepository() {
        // Rig owner, 2026-09-22: never a snapshot of petroapps' configurations/. Its absence is the thing to keep.
        for (String tree : List.of("src/main/resources", "src/test/resources")) {
            assertFalse(Files.exists(Path.of(tree, "configurations")), tree);
            assertFalse(Files.exists(Path.of(tree, "commander", "configurations")), tree);
        }
    }

    // -- the snapshot restore, before the base import ------------------------------------------------

    @Test
    void theSnapshotIsRestoredBeforeTheBaseImport() {
        snapshots = (folder, line) -> {
            restores.add(folder);
            calls.add("restore");
            return null;
        };
        assertTrue(run(new FakeClient(calls), new CommanderSsh("")));
        assertEquals(List.of(base), restores);
        assertTrue(calls.indexOf("restore") < calls.indexOf("import:base"), calls.toString());
        assertFalse(say.errText().contains("WARNING"), say.errText());
    }

    @Test
    void aRestoreThatLeavesAnAreaDifferentWarnsAndTheInitGoesOn() {
        Restore.RestoreReport left = new Restore.RestoreReport() {
            @Override
            public boolean ok() {
                return false;
            }
        };
        snapshots = (folder, line) -> left;
        assertTrue(run(new FakeClient(calls), new CommanderSsh("")));
        assertTrue(say.errText().contains("WARNING " + left.summary()), say.errText());
        assertEquals("commander init complete", say.outLines().get(say.outLines().size() - 1));
    }

    @Test
    void anUnreadableSnapshotIsOneLineAndTheInitGoesOn() {
        snapshots = (folder, line) -> {
            throw new IllegalArgumentException("invalid snapshot manifest");
        };
        assertTrue(run(new FakeClient(calls), new CommanderSsh("")));
        assertTrue(say.errText().contains(
                "Commander snapshot not restored: IllegalArgumentException: invalid snapshot manifest"), say.errText());
    }

    @Test
    void noSnapshotAndTheEnvironmentSwitchSkipTheRestore() {
        assertTrue(init(new FakeClient(calls), new CommanderSsh("")).build().run(base, true, false));
        assertTrue(say.outText().contains("Commander snapshot not restored (--no-snapshot)"));

        Say.Recorded quiet = new Say.Recorded();
        assertTrue(init(new FakeClient(new ArrayList<>()), new CommanderSsh("")).say(quiet)
                .environ(Map.of(CommanderInit.NO_SNAPSHOT_ENV, "1")).build().run(base, true, true));
        assertTrue(quiet.outText().contains("Commander snapshot not restored ($CAT_NO_SNAPSHOT)"));
        assertFalse(quiet.errText().contains("WARNING"));

        assertFalse(CommanderInit.snapshotDisabled(Map.of(CommanderInit.NO_SNAPSHOT_ENV, " False ")));
        assertFalse(CommanderInit.snapshotDisabled(Map.of(CommanderInit.NO_SNAPSHOT_ENV, "0")));
        assertTrue(CommanderInit.snapshotDisabled(Map.of(CommanderInit.NO_SNAPSHOT_ENV, "yes")));
    }

    // -- the Commander's own account of a fault --------------------------------------------------------

    @Test
    void aFaultPrintsTheCommandersOwnErrorsOnce() {
        CommanderSsh ssh = new CommanderSsh("FCC  ERROR DcrAdapter - boom#012#011at x.y(Z.java:1)\n");
        assertFalse(run(new FakeClient(calls, "cdcrdriverinit"), ssh));

        assertEquals(List.of(CommanderInit.SYSLOG_ERRORS_COMMAND), ssh.commands); // once; no FCC restart
        String err = say.errText();
        assertTrue(err.contains("cdcrdriverinit: CGIPortal.InternalError"), err);
        assertTrue(err.contains("FCC  ERROR DcrAdapter - boom\n    at x.y(Z.java:1)"), err);
    }

    @Test
    void theFailureNamesTheDcrThatIsNotIdleAndStaysTheLastLine() {
        String syslog = "user.info: 2026-09-29T03:57:10.63-04:00 commander_a FCC  INFO  DcrSiteAdmin"
                + " [RMI TCP Connection(439)-192.0.2.11] - All DCRs are not idle. Position = 9,"
                + " and State = DCRPUMPAUTH\n"
                + "user.err: 2026-09-29T03:57:10.631-04:00 commander_a FCC  ERROR DcrSiteAdmin"
                + " [RMI TCP Connection(439)-192.0.2.11] - All DCRs are not idle.\n"
                + "user.err: 2026-09-29T03:57:10.633-04:00 commander_a COR  com.verifone.isd.psc.fcc"
                + ".dcr.pres.rmi.AllDcrsNotIdleException\n";
        assertFalse(run(new FakeClient(calls, "cdcrdriverinit"), new CommanderSsh(syslog)));

        String last = say.lastErr();
        assertTrue(last.startsWith("commander init on " + HOST + " failed:"), last);
        // The fake's syslog never changes, so the line is the refusal already recovered from.
        assertTrue(last.endsWith("All DCRs are not idle. Position = 9, and State = DCRPUMPAUTH)"), last);
        assertTrue(CommanderInit.SYSLOG_ERRORS_COMMAND.contains("grep -v HISTORY"));
    }

    @Test
    void theFailureLineNamesTheFaultNotTheSyslogsNewestException() {
        CommanderSsh ssh = new CommanderSsh("user.err: COR  java.net.UnknownHostException: petroqat-gsc\n");
        assertFalse(run(new FakeClient(calls, "cdcrdriverinit"), ssh));
        assertTrue(say.lastErr().contains("failed: DeviceError: cdcrdriverinit: CGIPortal.InternalError"),
                say.lastErr());
    }

    @Test
    void anUnreadableSyslogDoesNotHideTheFault() {
        CommanderInit init = init(new FakeClient(calls, "cfuelinit"), new CommanderSsh(""))
                .commanderSsh(() -> {
                    throw new DeviceError("no route");
                })
                .build();
        assertFalse(init.run(base, true, true));
        String err = say.errText();
        assertTrue(err.contains("cfuelinit: CGIPortal.InternalError"), err);
        assertTrue(err.contains("could not read the Commander's syslog: no route"), err);
    }

    @Test
    void aQuickFaultWithNoNotIdleLineStillFailsWithoutARestart() {
        CommanderSsh ssh = new CommanderSsh("");
        assertFalse(run(new FakeClient(calls, "cfueldrvinit"), ssh));
        assertFalse(ssh.commands.contains(CommanderInit.FCC_RESTART));
    }

    // -- a DCR left not idle ----------------------------------------------------------------------

    @Test
    void aDcrLeftNotIdleRestartsTheFccAndRetriesOnce() {
        AtomicBoolean refused = new AtomicBoolean();
        FakeClient failsOnce = new FakeClient(calls) {
            @Override
            public void controlFunction(String command) {
                if (command.equals("cdcrdriverinit") && refused.compareAndSet(false, true)) {
                    calls.add(command);
                    throw new DeviceError("cdcrdriverinit: CGIPortal.InternalError");
                }
                super.controlFunction(command);
            }
        };
        CommanderSsh ssh = new CommanderSsh(NOT_IDLE);
        assertTrue(run(failsOnce, ssh));

        assertEquals(1, ssh.count(CommanderInit.FCC_RESTART));
        assertTrue(ssh.commands.contains(CommanderInit.FCC_RESTART_MARKER_CLEANUP));
        assertEquals(twice(CommanderInit.INIT_SEQUENCE.subList(0, 4), CommanderInit.INIT_SEQUENCE), sent(calls));
    }

    /** The Python's {@code _NotIdleUntil}: {@code cdcrdriverinit} refused until idle, each refusal a fresh line. */
    private static final class NotIdleUntil extends FakeClient {

        final CommanderSsh ssh;
        volatile boolean idle;
        final AtomicInteger failNext = new AtomicInteger();
        int refusals;

        NotIdleUntil(List<Object> calls, CommanderSsh ssh) {
            super(calls);
            this.ssh = ssh;
        }

        @Override
        public void controlFunction(String command) {
            if (failNext.get() > 0) {
                failNext.decrementAndGet();
                calls.add(command);
                throw new DeviceError(command + ": CGIPortal.InternalError");
            }
            if (command.equals("cdcrdriverinit") && !idle) {
                refusals++;
                calls.add(command);
                ssh.syslog = NOT_IDLE.replace("10.63", "1" + refusals + ".00");
                throw new DeviceError("cdcrdriverinit: CGIPortal.InternalError");
            }
            super.controlFunction(command);
        }
    }

    private final List<Integer> released = new ArrayList<>();

    private CommanderInit recovering(NotIdleUntil commander, CommanderSsh ssh, boolean releaseWorks, String pumpState,
            int failAfterRestart) {
        ssh.onRestart = () -> {
            commander.idle = true;
            commander.failNext.set(failAfterRestart);
        };
        return init(commander, ssh).pumps(pump -> {
            released.add(pump);
            if (releaseWorks) {
                commander.idle = true;
            }
            return pumpState;
        }).build();
    }

    @Test
    void aDcrLeftNotIdleIsReleasedThroughPetroWithoutAnFccRestart() {
        CommanderSsh ssh = new CommanderSsh("");
        NotIdleUntil commander = new NotIdleUntil(calls, ssh);
        assertTrue(recovering(commander, ssh, true, "BUSYOFF", 0).run(base, true, true));

        assertEquals(List.of(9), released);
        assertFalse(ssh.commands.contains(CommanderInit.FCC_RESTART));
        assertEquals(twice(CommanderInit.INIT_SEQUENCE.subList(0, 4), CommanderInit.INIT_SEQUENCE), sent(calls));
        assertTrue(say.outText().contains("pump 9 put back to IDLEOFF from BUSYOFF; retrying"));
    }

    @Test
    void aDcrStillNotIdleAfterTheReleaseRestartsTheFcc() {
        CommanderSsh ssh = new CommanderSsh("");
        NotIdleUntil commander = new NotIdleUntil(calls, ssh);
        assertTrue(recovering(commander, ssh, false, "BUSYOFF", 0).run(base, true, true));

        assertEquals(List.of(9), released);
        assertEquals(1, ssh.count(CommanderInit.FCC_RESTART));
        assertEquals(2, commander.refusals);
    }

    @Test
    void aPumpPetroReportsIdleGoesStraightToTheFccRestart() {
        CommanderSsh ssh = new CommanderSsh("");
        NotIdleUntil commander = new NotIdleUntil(calls, ssh);
        assertTrue(recovering(commander, ssh, false, null, 0).run(base, true, true));

        assertEquals(1, ssh.count(CommanderInit.FCC_RESTART));
        assertEquals(1, commander.refusals); // no retry between an empty release and the restart
    }

    @Test
    void theRestartedFccIsRetriedUntilItAnswers() {
        CommanderSsh ssh = new CommanderSsh("");
        NotIdleUntil commander = new NotIdleUntil(calls, ssh);
        ssh.onRestart = () -> {
            commander.idle = true;
            commander.failNext.set(2); // cfueldrvinit's stale RMI, twice
        };
        CommanderInit patient = init(commander, ssh).pumps(pump -> "BUSYOFF")
                .tunables(fast().withFccRestart(Duration.ZERO, Duration.ofSeconds(60), Duration.ofSeconds(15),
                        Duration.ofMillis(10)))
                .build();
        assertTrue(patient.run(base, true, true));
        assertEquals(List.of("cdcrinit", "closed"), calls.subList(calls.size() - 2, calls.size()));
        assertTrue(say.outText().contains("the restarted FCC is not ready yet, retrying"));
    }

    @Test
    void aLaterFaultIsNotBlamedOnTheRefusalAlreadyRecovered() {
        CommanderSsh ssh = new CommanderSsh("");
        NotIdleUntil commander = new NotIdleUntil(calls, ssh);
        assertFalse(recovering(commander, ssh, false, null, 1).run(base, true, true));

        String last = say.lastErr();
        assertTrue(last.contains("failed: DeviceError: crefreshcfg: CGIPortal.InternalError"), last);
        assertTrue(last.endsWith("(after recovering from: All DCRs are not idle. Position = 9, and State = DCRPUMPAUTH)"),
                last);
    }

    @Test
    void theProductionPumpReleaseThatCannotReachPetroSaysSoAndReleasesNothing() throws Exception {
        // PATS's pump port is held here first, so the release cannot listen and says so. (Resolving the fixture's
        // controller.invalid alone was not enough: with a real Petro running on this controller, its pushes reached
        // the test's listener on 1072 and the release "succeeded" — 2026-10-28, after rig run b34a01ad8176.)
        try (java.net.ServerSocket held = new java.net.ServerSocket(
                com.verifone.cat.devices.forecourt.PetroPumps.PATS_FUEL_PORT)) {
            CommanderInit init = CommanderInit.builder(CommanderFixtures.access(rig, new CommanderSsh(""), registers))
                    .say(say).build();
            assertNull(init.releasePump(3));
            assertTrue(say.outText().contains("pump 3: not released through Petro ("), say.outText());
        }
    }

    // -- an init the FCC never answered ------------------------------------------------------------

    @Test
    void anInitTheFccNeverAnsweredRestartsTheFccAndRetries() {
        AtomicBoolean hung = new AtomicBoolean();
        FakeClient hangsOnce = new FakeClient(calls) {
            @Override
            public void controlFunction(String command) {
                if (command.equals("cfueldrvinit") && hung.compareAndSet(false, true)) {
                    calls.add(command);
                    throw new DeviceError("CGILink fault: CGIPortal.InternalError");
                }
                super.controlFunction(command);
            }
        };
        CommanderSsh ssh = new CommanderSsh("");
        // Every fault counts as unanswered here (threshold 0 s).
        assertTrue(init(hangsOnce, ssh).tunables(fast().withFccNoAnswer(Duration.ZERO)).build().run(base, true, true));

        assertEquals(1, ssh.count(CommanderInit.FCC_RESTART));
        assertEquals(twice(CommanderInit.INIT_SEQUENCE.subList(0, 2), CommanderInit.INIT_SEQUENCE), sent(calls));
        assertTrue(say.outText().contains("cfueldrvinit: no answer from the FCC in 0 s"), say.outText());
    }

    // -- the pump-status link ---------------------------------------------------------------------

    private boolean fuelLinkRun(CommanderSsh ssh) {
        return init(new FakeClient(calls), ssh)
                .tunables(fast().withFuelLink(Duration.ofMillis(50), Duration.ofMillis(10)))
                .build().run(base, true, true);
    }

    @Test
    void theUnacceptedPumpStatusConnectionIsTheOneWithNoProcess() {
        CommanderSsh ssh = new CommanderSsh("");
        ssh.healsAfter = 1;
        CommanderInit init = init(new FakeClient(calls), ssh).build();
        assertEquals(CommanderSsh.FUEL_LINK_QUEUED.strip(), init.unacceptedFuelLink());
        ssh.restarts = 1;
        assertNull(init.unacceptedFuelLink());
    }

    @Test
    void aPumpStatusLinkTheFccNeverAcceptedRestartsTheFcc() {
        CommanderSsh ssh = new CommanderSsh("");
        ssh.healsAfter = 1;
        assertTrue(fuelLinkRun(ssh));
        assertEquals(1, ssh.restarts);
        assertEquals(twice(CommanderInit.INIT_SEQUENCE, CommanderInit.INIT_SEQUENCE), sent(calls));
        assertTrue(say.outText().contains("pump status: Petro's connection is the FCC's"));
    }

    @Test
    void aHealthyPumpStatusLinkIsLeftAlone() {
        CommanderSsh ssh = new CommanderSsh("");
        assertTrue(fuelLinkRun(ssh));
        assertEquals(0, ssh.restarts);
        assertEquals(CommanderInit.INIT_SEQUENCE, sent(calls));
    }

    @Test
    void aPumpStatusLinkStillUnacceptedAfterTheRestartFailsTheInit() {
        CommanderSsh ssh = new CommanderSsh("");
        ssh.healsAfter = 99;
        assertFalse(fuelLinkRun(ssh));
        assertEquals(1, ssh.restarts);
        assertTrue(say.lastErr().contains("has not accepted Petro's pump-status connection"), say.lastErr());
    }

    // -- the registers' screensavers --------------------------------------------------------------

    @Test
    void theScreensaverCommandGoesToEveryRegisterAndNotTheCommander() {
        assertEquals("echo -n 'DIS:      ' > /tmp/.io-screensaver-cmd", CommanderInit.SCREENSAVER_COMMAND);
        registers.put("reg2", new InMemoryTransport("r2.invalid")
                .command(CommanderInit.SCREENSAVER_COMMAND, new CommandResult(1, "", "no")));
        CommanderSsh ssh = new CommanderSsh("");
        init(new FakeClient(calls), ssh).build().disableRegisterScreensavers();

        assertEquals(Map.of("reg1", true, "reg2", true), Map.of("reg1", registers.containsKey("reg1"),
                "reg2", registers.containsKey("reg2")));
        for (InMemoryTransport transport : registers.values()) {
            assertEquals(List.of(CommanderInit.SCREENSAVER_COMMAND), transport.ran());
            assertTrue(transport.closed());
        }
        assertTrue(ssh.commands.isEmpty());
        assertTrue(say.outText().contains("screensaver disabled on reg1 (r1.invalid)"));
        assertTrue(say.errText().contains("screensaver command failed on reg2 (r2.invalid): no"));
    }

    // -- DCR 1 after the inits --------------------------------------------------------------------
    // The lab's fresh PSI install of Solutions 125 (2026-10-10, run c99efd2c8293): every init answered 200 and
    // DCR 1 never got a display. A fake Petro log stands in for the suite's.

    private static final String OLDER_DISPLAY = "Oct 10 12:00:00.000 PATS INFO  DRWAdapterImpl "
            + "[KeepAlive Thread for DCR[13]] - Sending Display command to DCR[1]:\n";
    private static final String BUILD_41_DISPLAY =
            "Oct 10 12:00:00.418 PATS INFO  DRWAdapterImpl [pool-1-thread-36] - Sending Display command to DCR[{]}:\n"
                    + "Oct 10 12:00:00.418 PATS INFO  DcrSimRT [pool-1-thread-36] - displayPromptMenu Position 1\n";

    private Path petroLog;

    private void display(String line) {
        try {
            Files.writeString(petroLog, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A Commander whose {@code displayAfter} command makes Petro log DCR 1's {@code display}; with
     * {@code "restart_fcc"}, the {@code cdcrinit} after an FCC restart does.
     */
    private void writePetroLog() throws IOException {
        petroLog = Files.writeString(dir.resolve("log4j-application.log"), "Oct 10 12:00:00.000 PATS INFO  start\n");
    }

    private CommanderInit dcrSetup(String displayAfter, String display, CommanderSsh ssh) throws IOException {
        writePetroLog();
        ssh.onRestart = () -> calls.add("restart_fcc");
        FakeClient client = new FakeClient(calls) {
            @Override
            public void controlFunction(String command) {
                super.controlFunction(command);
                boolean afterRestart = "restart_fcc".equals(displayAfter) && calls.contains("restart_fcc");
                if (command.equals(displayAfter) || (afterRestart && command.equals("cdcrinit"))) {
                    display(display);
                }
            }
        };
        return init(client, ssh).petroLog(() -> petroLog)
                .tunables(fast().withDcrLink(Duration.ofMillis(500), Duration.ofMillis(50))
                        .withFccRestart(Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ofMillis(10)))
                .build();
    }

    private List<Object> commandsAndRestarts() {
        List<Object> out = new ArrayList<>();
        for (Object call : calls) {
            if (call instanceof String s && !s.startsWith("import:") && !s.equals("closed")) {
                out.add(s);
            }
        }
        return out;
    }

    @Test
    void aDcr1TheInitsDriveNeedsNoRecovery() throws IOException {
        assertTrue(dcrSetup("cdcrinit", OLDER_DISPLAY, new CommanderSsh("")).run(base, true, true));
        assertEquals(CommanderInit.INIT_SEQUENCE, sent(calls));
        assertTrue(say.outText().contains("DCR 1: the Commander is driving it (a display reached Petro)"));
    }

    @Test
    void aDisplayLoggedTheBuild41WayNeedsNoRecovery() throws IOException {
        assertTrue(dcrSetup("cdcrinit", BUILD_41_DISPLAY, new CommanderSsh("")).run(base, true, true));
        assertEquals(CommanderInit.INIT_SEQUENCE, sent(calls));
    }

    @Test
    void aDcr1WithNoDisplayGetsTheDcrInitsAgainFirst() throws IOException {
        CommanderSsh ssh = new CommanderSsh("");
        AtomicInteger sentCount = new AtomicInteger();
        writePetroLog();
        ssh.onRestart = () -> calls.add("restart_fcc");
        // The seventh command is the re-init's cdcrinit: Petro then logs the display (stage 1, HANDOFF 1.100).
        CommanderInit staged = init(new FakeClient(calls) {
            @Override
            public void controlFunction(String command) {
                super.controlFunction(command);
                if (sentCount.incrementAndGet() == 7) {
                    display(OLDER_DISPLAY);
                }
            }
        }, ssh).petroLog(() -> petroLog).tunables(fast().withDcrLink(Duration.ofMillis(500), Duration.ofMillis(50)))
                .build();
        assertTrue(staged.run(base, true, true));
        assertEquals(twice(CommanderInit.INIT_SEQUENCE, CommanderInit.DCR_REINIT_SEQUENCE), sent(calls));
        assertFalse(calls.contains("restart_fcc"));
        assertTrue(say.outText().contains("DCR 1: connected after the recovery"));
    }

    @Test
    void aDcr1TheInitsCannotBringBackGetsAnFccRestartAndTheWholeSequence() throws IOException {
        assertTrue(dcrSetup("restart_fcc", OLDER_DISPLAY, new CommanderSsh("")).run(base, true, true));
        List<Object> expected = new ArrayList<>(CommanderInit.INIT_SEQUENCE);
        expected.addAll(CommanderInit.DCR_REINIT_SEQUENCE);
        expected.add("restart_fcc");
        expected.addAll(CommanderInit.INIT_SEQUENCE); // the fuel inits too (run d1cd4a608b29)
        assertEquals(expected, commandsAndRestarts());
    }

    @Test
    void aDcr1NothingBringsBackFailsTheInitAndSaysSo() throws IOException {
        assertFalse(dcrSetup(null, OLDER_DISPLAY, new CommanderSsh("")).run(base, true, true));
        String last = say.lastErr();
        assertTrue(last.startsWith("commander init on " + HOST + " failed: DCR 1"), last);
        assertTrue(last.contains("nor after an FCC restart"), last);
        assertEquals(1, calls.stream().filter("restart_fcc"::equals).count());
    }

    @Test
    void withoutPetrosLogOnThisPcTheLinkIsNotChecked() throws IOException {
        writePetroLog(); // Petro's log exists, but not where this init looks
        CommanderInit absent = init(new FakeClient(calls), new CommanderSsh(""))
                .petroLog(() -> dir.resolve("absent.log")).build();
        assertTrue(absent.run(base, true, true));
        assertFalse(calls.contains("restart_fcc"));
        assertTrue(say.outText().contains("link not checked"));
    }

    @Test
    void recoverDcrLinkWithoutPetrosLogSaysWhy() {
        CommanderInit init = init(new FakeClient(calls), new CommanderSsh(""))
                .petroLog(() -> dir.resolve("absent.log")).build();
        String why = init.recoverDcrLink(null, 1).orElseThrow();
        assertTrue(why.startsWith("Petro's log is not on this PC"), why);
        assertTrue(calls.isEmpty());
    }

    @Test
    void theDisplaySignalAndItsCountSinceAnOffset() throws IOException {
        assertTrue(CommanderInit.dcrDisplaySignal(1).matcher("Sending Display command to DCR[1]:").find());
        assertTrue(CommanderInit.dcrDisplaySignal(1).matcher("displayPromptMenu Position 1").find());
        assertFalse(CommanderInit.dcrDisplaySignal(1).matcher("displayPromptMenu Position 12").find());
        assertFalse(CommanderInit.dcrDisplaySignal(1).matcher("Sending Display command to DCR[{]}:").find());

        Path log = Files.writeString(dir.resolve("petro.log"), OLDER_DISPLAY);
        long size = Files.size(log);
        assertEquals(1, CommanderInit.reinitSignal(log, 0, CommanderInit.dcrDisplaySignal(1)));
        assertEquals(0, CommanderInit.reinitSignal(log, size, CommanderInit.dcrDisplaySignal(1)));
        assertEquals(0, CommanderInit.reinitSignal(log, size + 100, CommanderInit.dcrDisplaySignal(1))); // rotated
        assertEquals(0, CommanderInit.reinitSignal(dir.resolve("none.log"), 0, CommanderInit.dcrDisplaySignal(1)));
        assertEquals(dir.resolve("Petro_Simulator_Suite").resolve("log4j-application.log"), CommanderInit.petroLog(dir));
    }

    @Test
    void reinitDcrsSendsTheTwoDcrCommandsAndClosesItsClient() {
        init(new FakeClient(calls), new CommanderSsh("")).build().reinitDcrs();
        assertEquals(List.of("cdcrdriverinit", Duration.ofSeconds(5), "cdcrinit", "closed"), calls);
    }
}
