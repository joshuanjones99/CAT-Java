package com.verifone.cat.commander;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.verifone.cat.commander.snapshot.Restore;
import com.verifone.cat.commander.snapshot.SnapshotRestore;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.forecourt.ForecourtTimeouts;
import com.verifone.cat.devices.forecourt.PetroPumps;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.recovery.Timeouts;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.sims.ToolsRoot;

/**
 * Step 4's {@code commander-init}: CAT's Commander initialisation, run every {@code dev} run. A port of
 * {@code petrotest.commander_init}.
 *
 * <p>Imports petroapps' base configuration, then sends five CGILink commands 5 s apart:
 * {@code crefreshcfg}, {@code cfueldrvinit}, {@code cfuelinit}, {@code cdcrdriverinit}, {@code cdcrinit}
 * (rig owner, 2026-09-23). Each is sent once and its reply read: a fault fails the init with the fault's
 * text rather than being retried. The exceptions, each from a rig run the Python records:
 * <ul>
 *   <li>a DCR the Commander's syslog says is not idle (a pump an earlier case left authorised) is
 *       recovered and the commands sent again ({@link #recoverNotIdleDcr}): first the pump is put back to
 *       IDLEOFF through Petro, and only if it is still not idle is the FCC restarted;</li>
 *   <li>a command the FCC never answered (a fault only after coresvcs's 60 s RMI read) restarts the FCC
 *       and the commands go again;</li>
 *   <li>after the inits, a pump-status connection the FCC never accepted restarts the FCC once
 *       ({@link #unacceptedFuelLink}), and DCR 1 with no display gets the DCR link recovery
 *       ({@link #recoverDcrLink}): the two DCR inits again, then an FCC restart and the whole sequence.</li>
 * </ul>
 * With Petro not selected only {@code crefreshcfg} is sent: the other four address the forecourt Petro
 * simulates.
 *
 * <p><b>The base configuration is read live from the petroapps checkout</b> ({@link #baseConfigFolder}),
 * never from a committed copy (rig owner, 2026-09-22: "we always need to be up to date with petroapps,
 * that would defeat the purpose of testing if we did not").
 *
 * <p><b>The Commander snapshot</b> ({@code docs/commander-snapshot.md}): before the base import, every
 * restorable area that differs from the golden snapshot for the Commander's build is re-imported from it
 * ({@link SnapshotRestore}, the port of {@code commander_snapshot.restore_for_rig}). Before, not after, so
 * base's files have the last word. Inert until a snapshot is captured; {@code snapshot=false} and
 * {@code $CAT_NO_SNAPSHOT} skip it. An area left different is a {@code WARNING} line, never a failed init.
 *
 * <p>The pump release through Petro ({@link #releaseThroughPetro}, {@code PetroPumps.clear}) needs PATS's port
 * 1072, which the launch's step 4 lends to the init; when it cannot drive the pump it says so and reports
 * "not released", as the Python's does, so the FCC restart follows.
 *
 * <p>Every seam (the CGILink client, the Commander's and the registers' SSH, Petro's log, the pump
 * release, the waits and their budgets) is injected through {@link Builder}, so the tests drive the
 * whole flow against fakes. Evidence about this repository, not about a Commander.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python's own markings stand too: the 5 s spacing and
 * this order, the pump release, and the screensaver command have not all run on a rig.
 */
public final class CommanderInit {

    /** {@code $PETROAPPS_ROOT}: the petroapps checkout. */
    public static final String PETROAPPS_ENV = "PETROAPPS_ROOT";

    /** The checkout when {@link #PETROAPPS_ENV} is unset, as {@code petrotest.collect} defaults it. */
    public static final Path DEFAULT_PETROAPPS_ROOT = Path.of("C:\\gitrepos\\petroapps");

    /** {@code $CAT_NO_SNAPSHOT}: skips the snapshot restore, as the Python reads it. */
    public static final String NO_SNAPSHOT_ENV = "CAT_NO_SNAPSHOT";

    /**
     * {@code configurations/} folders imported after {@code base/} on every run: a setting a plan turns
     * on and may not turn off again, which base leaves alone (rig owner, 2026-10-14).
     */
    public static final List<String> RESET_CONFIGURATIONS = List.of("FuelWithoutAdditionalData");

    /** The commands, in the order they are sent (rig owner, 2026-09-23). */
    public static final List<String> INIT_SEQUENCE =
            List.of("crefreshcfg", "cfueldrvinit", "cfuelinit", "cdcrdriverinit", "cdcrinit");

    /** What is left of {@link #INIT_SEQUENCE} without Petro Simulator Suite. */
    public static final List<String> NO_FORECOURT_SEQUENCE = List.of("crefreshcfg");

    /** The two DCR commands, for a DCR the Commander will not drive (HANDOFF.md section 1.100). */
    public static final List<String> DCR_REINIT_SEQUENCE = List.of("cdcrdriverinit", "cdcrinit");

    /**
     * The Commander's FCC, TWS and COR errors from the end of its syslog, then its "not idle" lines on
     * their own (cloud-sync noise once pushed the "not idle" line out of the error tail, run 5a18b4c3030d).
     */
    public static final String SYSLOG_ERRORS_COMMAND = "tail -n 5000 /var/log/messages | grep -E"
            + " ' (FCC|TWS|COR) +(ERROR|WARN|[a-z]+[.][a-zA-Z.]+(Exception|Error)|[.][.][.])'"
            + " | grep -v -e HISTORY -e 'not idle' | tail -n 150;"
            + " tail -n 5000 /var/log/messages | grep 'not idle'"
            + " | grep -v HISTORY | tail -n 10";

    /** Written to the C18's screensaver command pipe: {@code DIS:} plus six spaces (rig owner, 2026-10-06). */
    public static final String SCREENSAVER_COMMAND = "echo -n 'DIS:      ' > /tmp/.io-screensaver-cmd";

    /** Where the FCC's {@code SFCEventListener} takes Petro's pump-status callbacks. */
    public static final int FUEL_CALLBACK_PORT = 9732;

    /** The Commander's sockets on {@link #FUEL_CALLBACK_PORT}. */
    public static final String FUEL_LINK_COMMAND = "netstat -tnp 2>/dev/null | grep ':" + FUEL_CALLBACK_PORT + " '";

    /** {@code killFCC -r}, detached: run in the SSH session's own shell it kills that shell first. */
    public static final String FCC_RESTART =
            "su - fcc -c 'setsid nohup /home/fcc/bin/killFCC -r >/dev/null 2>&1 </dev/null &'";

    /** The FCC's JVM PID; empty when none runs. */
    public static final String FCC_PID = "pgrep -u fcc -f 'java.*fcc' | head -1";

    /** {@code killFCC -r} always leaves {@code ~/.restart}; removed after the restart. */
    public static final String FCC_RESTART_MARKER_CLEANUP = "su - fcc -c 'rm -f ~/.restart'";

    /** Petro's line for a display the Commander pushed: the two forms {@link #dcrDisplaySignal} matches. */
    private static final Pattern POSITION = Pattern.compile("Position = (\\d+)");

    /**
     * Puts a pump back to IDLEOFF through Petro Simulator Suite; returns the state it was in, or
     * {@code null} when it was idle already or could not be driven.
     */
    @FunctionalInterface
    public interface PumpRelease {
        String release(int pump);
    }

    /**
     * The production release, the Python's {@code release_pump}: a {@link PetroPumps} client on PATS's port
     * {@value PetroPumps#PATS_FUEL_PORT} (which the launch lends to the init), the pump's reported state, then
     * {@link PetroPumps#clear}. Null, with a line saying why, when the port is held, Petro does not answer, or the
     * pump does not reach IDLEOFF. UNVERIFIED AGAINST HARDWARE (Java port).
     */
    static String releaseThroughPetro(Rig rig, int pump, Say say) {
        ForecourtTimeouts timeouts = ForecourtTimeouts.of(Timeouts.of(rig.timeouts()));
        PetroPumps pumps;
        try {
            pumps = PetroPumps.started(rig.controller().host(), timeouts);
        } catch (RuntimeException e) {
            say.out("pump " + pump + ": not released through Petro (" + e.getMessage() + ")");
            return null;
        }
        try {
            // clear reads the last state reported, and Petro answers the login with them.
            pumps.reported(pump, timeouts.http());
            return pumps.clear(pump);
        } catch (RuntimeException e) {
            say.out("pump " + pump + ": not released through Petro (" + e.getMessage() + ")");
            return null;
        } finally {
            pumps.close();
        }
    }

    /**
     * The budgets, each named as the Python names its constant. {@link #defaults()} are the Python's;
     * a test shortens them.
     *
     * @param betweenCommands  {@code BETWEEN_COMMANDS_SECONDS}: between one reply and the next command
     * @param fccNoAnswer      {@code FCC_NO_ANSWER_SECONDS}: a fault this late was never answered by the FCC
     * @param fccReadyTimeout  {@code FCC_READY_TIMEOUT}: after a restart, how long the commands are resent
     * @param fccReadyRetry    {@code FCC_READY_RETRY_SECONDS}: between those tries
     * @param fccSettle        {@code FCC_SETTLE_SECONDS}: how long the new FCC JVM must have run
     * @param fccRestartTimeout {@code FCC_RESTART_TIMEOUT}: the restart's ceiling
     * @param fccRestartPoll   the restart's poll interval (5 s in the Python)
     * @param dcrLinkWait      {@code DCR_LINK_WAIT}: for DCR 1's first display after the inits
     * @param dcrLinkPoll      {@code DCR_LINK_POLL}
     * @param fuelLinkWait     {@code FUEL_LINK_WAIT}: for Petro's new connection to be the FCC's
     * @param fuelLinkRetry    {@code FUEL_LINK_RETRY_SECONDS}
     */
    public record Tunables(Duration betweenCommands, Duration fccNoAnswer, Duration fccReadyTimeout,
            Duration fccReadyRetry, Duration fccSettle, Duration fccRestartTimeout, Duration fccRestartPoll,
            Duration dcrLinkWait, Duration dcrLinkPoll, Duration fuelLinkWait, Duration fuelLinkRetry) {

        public static Tunables defaults() {
            return new Tunables(Duration.ofSeconds(5), Duration.ofSeconds(55), Duration.ofSeconds(300),
                    Duration.ofSeconds(15), Duration.ofSeconds(30), Duration.ofSeconds(240), Duration.ofSeconds(5),
                    Duration.ofSeconds(90), Duration.ofSeconds(2), Duration.ofSeconds(60), Duration.ofSeconds(5));
        }

        public Tunables withFccNoAnswer(Duration value) {
            return new Tunables(betweenCommands, value, fccReadyTimeout, fccReadyRetry, fccSettle,
                    fccRestartTimeout, fccRestartPoll, dcrLinkWait, dcrLinkPoll, fuelLinkWait, fuelLinkRetry);
        }

        /** The FCC restart's budgets: the settle, the ceiling of the resends after it, and both polls. */
        public Tunables withFccRestart(Duration settle, Duration readyTimeout, Duration readyRetry, Duration poll) {
            return new Tunables(betweenCommands, fccNoAnswer, readyTimeout, readyRetry, settle,
                    fccRestartTimeout, poll, dcrLinkWait, dcrLinkPoll, fuelLinkWait, fuelLinkRetry);
        }

        public Tunables withDcrLink(Duration wait, Duration poll) {
            return new Tunables(betweenCommands, fccNoAnswer, fccReadyTimeout, fccReadyRetry, fccSettle,
                    fccRestartTimeout, fccRestartPoll, wait, poll, fuelLinkWait, fuelLinkRetry);
        }

        public Tunables withFuelLink(Duration wait, Duration retry) {
            return new Tunables(betweenCommands, fccNoAnswer, fccReadyTimeout, fccReadyRetry, fccSettle,
                    fccRestartTimeout, fccRestartPoll, dcrLinkWait, dcrLinkPoll, wait, retry);
        }
    }

    /** An init command the FCC did not answer within coresvcs's RMI timeout. */
    public static final class FccNoAnswer extends DeviceError {

        private static final long serialVersionUID = 1L;

        public FccNoAnswer(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Petro's log and its size when it was taken. */
    record Mark(Path log, long offset) {}

    private final Rig rig;
    private final Supplier<CommanderClient> clients;
    private final Supplier<Transport> commanderSsh;
    private final Function<Rig.Register, Transport> registerSsh;
    private final Function<Rig.Register, String> registerName;
    private final Supplier<Path> petroLog;
    private final PumpRelease pumps;
    private final Pauser pauser;
    private final Tunables tunables;
    private final Say say;
    private final Duration sshTimeout;
    private final Map<String, String> environ;
    private final SnapshotRestore snapshots;

    private CommanderInit(Builder b) {
        this.rig = b.rig;
        this.clients = b.clients;
        this.commanderSsh = b.commanderSsh;
        this.registerSsh = b.registerSsh;
        this.registerName = b.registerName;
        this.petroLog = b.petroLog;
        this.pumps = b.pumps;
        this.pauser = b.pauser;
        this.tunables = b.tunables;
        this.say = b.say;
        this.sshTimeout = b.sshTimeout;
        this.environ = b.environ;
        this.snapshots = b.snapshots;
    }

    /** The production init on {@code access}'s Commander, writing to {@code say}. */
    public static CommanderInit forRig(CommanderAccess access, Say say) {
        return builder(access).say(say).build();
    }

    /** A builder whose every seam defaults to {@code access}'s (the real Commander and registers). */
    public static Builder builder(CommanderAccess access) {
        return new Builder(access);
    }

    // -- where things are ---------------------------------------------------------------------

    /** {@code $PETROAPPS_ROOT/AutomationTests/configurations/base}, resolved at each call. */
    public static Path baseConfigFolder(Map<String, String> environ) {
        String root = environ == null ? null : environ.get(PETROAPPS_ENV);
        Path petroapps = root == null || root.isBlank() ? DEFAULT_PETROAPPS_ROOT : Path.of(root.strip());
        return petroapps.resolve("AutomationTests").resolve("configurations").resolve("base");
    }

    /** {@link #baseConfigFolder(Map)} with the process environment. */
    public static Path baseConfigFolder() {
        return baseConfigFolder(System.getenv());
    }

    /** Petro Simulator Suite's own log, as CAT's simulator registry tails it, under the tools root. */
    public static Path petroLog(Path toolsRoot) {
        return toolsRoot.resolve("Petro_Simulator_Suite").resolve("log4j-application.log");
    }

    /** Whether {@code $CAT_NO_SNAPSHOT} switches the restore off: anything but blank, 0, false or no. */
    public static boolean snapshotDisabled(Map<String, String> environ) {
        String value = environ == null ? null : environ.get(NO_SNAPSHOT_ENV);
        String v = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return !(v.isEmpty() || v.equals("0") || v.equals("false") || v.equals("no"));
    }

    /**
     * Petro's line for a display the Commander pushed to DCR {@code dcr}. Older suites log "Sending Display
     * command to DCR[n]"; master055.02 build 41 logs that line as a bare "DCR[{]}", so only
     * {@code DcrSimRT}'s "displayPromptMenu Position n" names the DCR (run 5230d1306c3c).
     */
    public static Pattern dcrDisplaySignal(int dcr) {
        return Pattern.compile("Sending Display command to DCR\\[" + dcr + "\\]|displayPromptMenu Position " + dcr
                + "(?!\\d)");
    }

    /**
     * How many lines matching {@code signal} the suite has logged since byte {@code offset}. 0 when the log
     * cannot be read or was rotated shorter than {@code offset}.
     */
    public static int reinitSignal(Path log, long offset, Pattern signal) {
        String tail;
        try (SeekableByteChannel channel = Files.newByteChannel(log)) {
            if (channel.size() < offset) {
                return 0;
            }
            channel.position(offset);
            try (InputStream in = Channels.newInputStream(channel)) {
                tail = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return 0;
        }
        int count = 0;
        for (String line : tail.split("\\R")) {
            if (signal.matcher(line).find()) {
                count++;
            }
        }
        return count;
    }

    // -- commander-init -------------------------------------------------------------------------

    /**
     * Imports {@code baseFolder} and {@link #RESET_CONFIGURATIONS}, then sends the sequence
     * {@code betweenCommands} apart and checks the fuel and DCR links.
     *
     * @param baseFolder the base configuration, or {@code null} for {@link #baseConfigFolder(Map)}
     * @param forecourt  Petro Simulator Suite runs; {@code false} sends {@link #NO_FORECOURT_SEQUENCE}
     * @param snapshot   restore the Commander snapshot first; {@code false} is the Python's
     *                   {@code --no-snapshot}. The restore runs before the base import ({@link SnapshotRestore})
     * @return whether the Commander was initialised; a failure's reason is the last {@link Say#err} line
     */
    public boolean run(Path baseFolder, boolean forecourt, boolean snapshot) {
        Path base = baseFolder != null ? baseFolder : baseConfigFolder(environ);
        List<String> sequence = forecourt ? INIT_SEQUENCE : NO_FORECOURT_SEQUENCE;
        String host = rig.commander().host();
        CommanderClient client = clients.get();
        String captured = null; // the syslog, once read for this fault
        Set<String> recovered = new HashSet<>(); // "not idle" lines already acted on
        try {
            restoreSnapshot(snapshot, base);
            List<Path> files = filesOf(base);
            say.out("importing " + files.size() + " files from " + base);
            Map<String, String> applied = client.importConfigurationFolder(base);
            say.out("imported " + applied.size() + " of " + files.size() + " files");
            for (String name : RESET_CONFIGURATIONS) {
                Path folder = base.resolveSibling(name);
                if (Files.isDirectory(folder)) {
                    client.importConfigurationFolder(folder);
                    say.out("imported " + name + " (reset on every run)");
                } else {
                    say.err("no " + folder + ": " + name + " not reset");
                }
            }
            disableRegisterScreensavers();
            if (!forecourt) {
                say.out("no forecourt simulator: skipping the fuel and DCR driver inits");
            }
            Mark mark = forecourt ? petroMark() : null;
            try {
                send(client, sequence);
            } catch (FccNoAnswer e) {
                // A hung FCC, not a refusal: restart it and send the commands again.
                printCommanderErrors();
                say.out(e.getMessage() + "; restarting the Commander's FCC and retrying");
                restartFcc();
                sendAfterRestart(client, sequence);
            } catch (DeviceError e) {
                String line = printCommanderErrors();
                captured = line;
                if (!line.contains("not idle")) {
                    throw e;
                }
                // Rig owner, 2026-09-29: a DCR an earlier case left authorised refuses the DCR driver init.
                say.out(e.getMessage() + " (" + notIdleMessage(line) + "); recovering the DCR");
                recovered.add(line);
                captured = null; // the retry's fault, if any, is read afresh
                recoverNotIdleDcr(client, sequence, line, recovered, forecourt);
            }
            if (forecourt) {
                Optional<String> problem = confirmFuelLink(client, sequence);
                if (problem.isEmpty()) {
                    problem = confirmDcrLink(client, mark);
                }
                if (problem.isPresent()) {
                    say.err("commander init on " + host + " failed: " + problem.get());
                    return false;
                }
            }
            say.out("commander init complete");
            return true;
        } catch (DeviceError e) {
            // The capture first, so the fault stays the last line: step 4 shows only that one.
            String cause = captured != null ? captured : printCommanderErrors();
            String why = cause.isEmpty() ? "" : " (" + notIdleMessage(cause) + ")";
            if (recovered.contains(cause)) {
                why = " (after recovering from: " + notIdleMessage(cause) + ")";
            }
            say.err("commander init on " + host + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + why);
            return false;
        } catch (UncheckedIOException e) {
            say.err("commander init on " + host + " failed: cannot read " + base + ": " + e.getMessage());
            return false;
        } finally {
            client.close();
        }
    }

    private static List<Path> filesOf(Path folder) {
        try (Stream<Path> listing = Files.list(folder)) {
            return listing.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("the base configuration folder cannot be listed", e);
        }
    }

    /**
     * The Python's {@code _restore_snapshot}: {@code restore_for_rig}, unless switched off. Never fails the init
     * on its own account: an area left different is a {@code WARNING} line, and an unreadable snapshot one line.
     * A CGILink failure ({@link DeviceError}) propagates as the Python's does.
     */
    private void restoreSnapshot(boolean enabled, Path base) {
        if (!enabled || snapshotDisabled(environ)) {
            String why = !enabled ? "--no-snapshot" : "$" + NO_SNAPSHOT_ENV;
            say.out("Commander snapshot not restored (" + why + ")");
            return;
        }
        Restore.RestoreReport report;
        try {
            report = snapshots.restore(base, say::out);
        } catch (UncheckedIOException | IllegalArgumentException | java.util.NoSuchElementException e) {
            say.err("Commander snapshot not restored: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        if (report != null && !report.ok()) {
            say.err("WARNING " + report.summary());
        }
    }

    // -- the commands -----------------------------------------------------------------------------

    private void send(CommanderClient client, List<String> sequence) {
        for (int index = 1; index <= sequence.size(); index++) {
            String command = sequence.get(index - 1);
            if (index > 1) {
                pauser.pause(tunables.betweenCommands());
            }
            say.out("sending " + command + " (" + index + " of " + sequence.size() + ")");
            long started = System.nanoTime();
            try {
                client.controlFunction(command);
            } catch (DeviceError e) {
                Duration took = Duration.ofNanos(System.nanoTime() - started);
                if (took.compareTo(tunables.fccNoAnswer()) < 0) {
                    throw e;
                }
                throw new FccNoAnswer(command + ": no answer from the FCC in " + took.toSeconds() + " s ("
                        + e.getMessage() + ")", e);
            }
            say.out(command + " answered in " + seconds(System.nanoTime() - started, 1) + " s");
        }
    }

    /** {@link #send}, resent while the restarted FCC refuses it, for up to {@code fccReadyTimeout}. */
    private void sendAfterRestart(CommanderClient client, List<String> sequence) {
        long end = System.nanoTime() + tunables.fccReadyTimeout().toNanos();
        while (true) {
            try {
                send(client, sequence);
                return;
            } catch (DeviceError e) {
                if (System.nanoTime() + tunables.fccReadyRetry().toNanos() > end) {
                    throw e;
                }
                say.out(e.getMessage() + "; the restarted FCC is not ready yet, retrying");
                pauser.pause(tunables.fccReadyRetry());
            }
        }
    }

    /** {@link #DCR_REINIT_SEQUENCE}, {@code betweenCommands} apart. A fault raises. No import, no restart. */
    public void reinitDcrs() {
        CommanderClient client = clients.get();
        try {
            send(client, DCR_REINIT_SEQUENCE);
        } finally {
            client.close();
        }
    }

    // -- the Commander's own account --------------------------------------------------------------

    /**
     * Best effort: the Commander's latest FCC/TWS/COR errors, to {@link Say#err}, for the run's log.
     *
     * @return the newest "not idle" syslog line, timestamp and all, or "" when there is none or the syslog
     *         cannot be read. The timestamp tells a fresh refusal from one already recovered.
     */
    public String printCommanderErrors() {
        CommandResult result;
        try {
            Transport transport = commanderSsh.get();
            try {
                result = transport.run(SYSLOG_ERRORS_COMMAND, sshTimeout);
            } finally {
                transport.close();
            }
        } catch (RuntimeException e) { // diagnostics only; never mask the fault being reported
            say.err("could not read the Commander's syslog: " + e.getMessage());
            return "";
        }
        // rsyslog escapes a message's own newline and tab as #012 and #011.
        String lines = result.stdout().strip().replace("#012", "\n").replace("#011", "    ");
        say.err("Commander syslog, FCC/TWS/COR errors:\n" + (lines.isEmpty() ? "(none in the last 5000 lines)" : lines));
        List<String> idle = new ArrayList<>();
        for (String line : lines.split("\\R")) {
            if (line.contains("not idle.")) {
                idle.add(line);
            }
        }
        for (int i = idle.size() - 1; i >= 0; i--) {
            if (idle.get(i).contains("Position")) {
                return idle.get(i);
            }
        }
        return idle.isEmpty() ? "" : idle.get(idle.size() - 1);
    }

    /** "All DCRs are not idle. Position = 9, and State = DCRPUMPAUTH", from its syslog line. */
    static String notIdleMessage(String line) {
        int at = line.indexOf(" - ");
        return at < 0 ? line : line.substring(at + 3);
    }

    static Integer notIdlePosition(String line) {
        Matcher m = POSITION.matcher(line);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    // -- registers ------------------------------------------------------------------------------

    /**
     * Sends {@link #SCREENSAVER_COMMAND} to every register the rig declares, over SSH. Best effort: a
     * register that cannot be reached or refuses is reported and skipped, never failing the init.
     */
    public void disableRegisterScreensavers() {
        for (Rig.Register register : rig.registers()) {
            String name = registerName.apply(register);
            Transport transport;
            try {
                transport = registerSsh.apply(register);
            } catch (RuntimeException e) {
                say.err("screensaver command not sent to " + name + ": " + e.getMessage());
                continue;
            }
            try {
                CommandResult result = transport.run(SCREENSAVER_COMMAND, sshTimeout);
                if (result.ok()) {
                    say.out("screensaver disabled on " + name);
                } else {
                    String detail = (result.stderr().isEmpty() ? result.stdout() : result.stderr()).strip();
                    say.err("screensaver command failed on " + name + ": "
                            + (detail.isEmpty() ? "exit " + result.exitStatus() : detail));
                }
            } catch (RuntimeException e) { // best effort; never fail the Commander init for it
                say.err("screensaver command not sent to " + name + ": " + e.getMessage());
            } finally {
                transport.close();
            }
        }
    }

    // -- a DCR left not idle ----------------------------------------------------------------------

    /**
     * Brings back the DCR a "not idle" syslog {@code line} names, then sends {@code sequence} again. First
     * the pump at the line's {@code Position = N} is released through Petro (skipped without a forecourt);
     * only while the DCR is still refused, the FCC is restarted. A fault raises; every line acted on joins
     * {@code recovered}, so {@link #run} never reports one as a later fault's cause.
     */
    void recoverNotIdleDcr(CommanderClient client, List<String> sequence, String line, Set<String> recovered,
            boolean forecourt) {
        Integer position = notIdlePosition(line);
        String was = forecourt && position != null ? releasePump(position) : null;
        if (was != null) {
            say.out("pump " + position + " put back to IDLEOFF from " + was + "; retrying");
            try {
                send(client, sequence);
                return;
            } catch (DeviceError e) {
                String again = printCommanderErrors();
                if (!again.contains("not idle") || recovered.contains(again)) {
                    throw e;
                }
                recovered.add(again);
                say.out(e.getMessage() + " (" + notIdleMessage(again) + "); still not idle");
            }
        }
        say.out("restarting the Commander's FCC and retrying");
        restartFcc();
        sendAfterRestart(client, sequence);
    }

    /**
     * Puts {@code pump} back to IDLEOFF through Petro; the state it was in, or {@code null}. Not a PATS
     * step. The production release is {@link #releaseThroughPetro}.
     */
    public String releasePump(int pump) {
        return pumps.release(pump);
    }

    // -- the FCC ----------------------------------------------------------------------------------

    /** Restarts the Commander's fuel controller and waits until its new JVM has run {@code fccSettle}. */
    public void restartFcc() {
        Transport transport = commanderSsh.get();
        try {
            String old = transport.run(FCC_PID, sshTimeout).stdout().strip();
            transport.run(FCC_RESTART, sshTimeout);
            long[] started = {Long.MIN_VALUE};
            boolean settled = pauser.until(() -> {
                String pid = transport.run(FCC_PID, sshTimeout).stdout().strip();
                if (pid.isEmpty() || pid.equals(old)) {
                    return false;
                }
                if (started[0] == Long.MIN_VALUE) {
                    started[0] = System.nanoTime();
                }
                return System.nanoTime() - started[0] >= tunables.fccSettle().toNanos();
            }, tunables.fccRestartTimeout(), tunables.fccRestartPoll());
            if (!settled) {
                throw new DeviceError("the FCC did not restart within " + tunables.fccRestartTimeout().toSeconds()
                        + " s");
            }
            transport.run(FCC_RESTART_MARKER_CLEANUP, sshTimeout);
        } finally {
            transport.close();
        }
    }

    // -- the pump-status link ---------------------------------------------------------------------

    /**
     * The Commander's {@code netstat} line for a pump-status connection the FCC never accepted
     * ({@code ESTABLISHED} with no process), or {@code null} when there is none or the Commander cannot be
     * read (best effort). Run 60754dd24fc8: after a network outage the FCC held a dead socket and every
     * lift went unseen.
     */
    public String unacceptedFuelLink() {
        String out;
        try {
            Transport transport = commanderSsh.get();
            try {
                out = transport.run(FUEL_LINK_COMMAND, sshTimeout).stdout();
            } finally {
                transport.close();
            }
        } catch (RuntimeException e) { // a diagnostic; the inits themselves answered
            say.out("pump-status link not checked: " + e.getMessage());
            return null;
        }
        for (String line : out.split("\\R")) {
            String[] fields = line.strip().split("\\s+");
            if (fields.length >= 7 && fields[3].endsWith(":" + FUEL_CALLBACK_PORT) && fields[5].equals("ESTABLISHED")
                    && fields[6].equals("-")) {
                return line.strip();
            }
        }
        return null;
    }

    private Optional<String> confirmFuelLink(CommanderClient client, List<String> sequence) {
        String stale = unacceptedFuelLink();
        if (stale == null) {
            return Optional.empty();
        }
        say.out("pump status: the FCC never accepted Petro's connection (" + stale + "); it holds an older one. "
                + "Restarting the Commander's FCC and sending the inits again");
        restartFcc();
        sendAfterRestart(client, sequence);
        if (!pauser.until(() -> unacceptedFuelLink() == null, tunables.fuelLinkWait(), tunables.fuelLinkRetry())) {
            String still = unacceptedFuelLink();
            return Optional.of("the FCC has not accepted Petro's pump-status connection after a restart ("
                    + (still == null ? "netstat unreadable" : still) + ")");
        }
        say.out("pump status: Petro's connection is the FCC's");
        return Optional.empty();
    }

    // -- DCR 1's link -----------------------------------------------------------------------------

    /** Petro's log and its size now, or {@code null} when the suite's log is not on this PC. */
    Mark petroMark() {
        Path log = petroLog.get();
        try {
            return new Mark(log, Files.size(log));
        } catch (IOException e) {
            return null;
        }
    }

    boolean dcrDisplayAfter(Mark mark, int dcr) {
        return reinitSignal(mark.log(), mark.offset(), dcrDisplaySignal(dcr)) > 0;
    }

    /** Up to {@code dcrLinkWait} for a display on DCR {@code dcr} since {@code mark}. */
    boolean waitForDcrDisplay(Mark mark, int dcr) {
        return pauser.until(() -> dcrDisplayAfter(mark, dcr), tunables.dcrLinkWait(), tunables.dcrLinkPoll());
    }

    /**
     * Brings DCR {@code dcr} back from a Commander that will not drive it. Two stages, each only while the
     * DCR still shows no display: {@link #DCR_REINIT_SEQUENCE} (HANDOFF.md section 1.100), then an FCC restart
     * and the whole {@link #INIT_SEQUENCE} again (a restarted FCC has no fuel context either: run
     * d1cd4a608b29 answered DCR 1's first key "PUMP NOT READY"). Neither stage is a PATS step.
     *
     * @param client the client to send with, or {@code null} for one of this init's own (closed after)
     * @return empty when the DCR is driven, or why not
     */
    public Optional<String> recoverDcrLink(CommanderClient client, int dcr) {
        Mark mark = petroMark();
        if (mark == null) {
            return Optional.of("Petro's log is not on this PC (" + petroLog.get() + "), so DCR " + dcr
                    + "'s link cannot be read");
        }
        boolean own = client == null;
        CommanderClient c = own ? clients.get() : client;
        try {
            say.out("DCR " + dcr + ": re-sending the Commander's DCR inits");
            send(c, DCR_REINIT_SEQUENCE);
            if (waitForDcrDisplay(mark, dcr)) {
                return Optional.empty();
            }
            say.out("DCR " + dcr + ": no display " + tunables.dcrLinkWait().toSeconds()
                    + " s after the inits; restarting the FCC");
            restartFcc();
            Mark fresh = petroMark();
            mark = fresh != null ? fresh : mark;
            sendAfterRestart(c, INIT_SEQUENCE);
            if (waitForDcrDisplay(mark, dcr)) {
                return Optional.empty();
            }
        } catch (DeviceError e) {
            return Optional.of("DCR " + dcr + ": its recovery failed (" + e.getMessage() + ")");
        } finally {
            if (own) {
                c.close();
            }
        }
        return Optional.of("DCR " + dcr + ": the Commander sent it no display within "
                + tunables.dcrLinkWait().toSeconds() + " s of the DCR inits, nor after an FCC restart and the inits "
                + "again (Petro's log shows the reconnect loop; HANDOFF.md sections 1.99, 1.100)");
    }

    private Optional<String> confirmDcrLink(CommanderClient client, Mark mark) {
        if (mark == null) {
            say.out("DCR 1: Petro's log is not on this PC (" + petroLog.get() + "); link not checked");
            return Optional.empty();
        }
        if (waitForDcrDisplay(mark, 1)) {
            say.out("DCR 1: the Commander is driving it (a display reached Petro)");
            return Optional.empty();
        }
        say.out("DCR 1: no display " + tunables.dcrLinkWait().toSeconds() + " s after the inits; recovering");
        Optional<String> problem = recoverDcrLink(client, 1);
        if (problem.isEmpty()) {
            say.out("DCR 1: connected after the recovery");
        }
        return problem;
    }

    private static String seconds(long nanos, int places) {
        return String.format(Locale.ROOT, "%." + places + "f", nanos / 1_000_000_000.0);
    }

    /** Every seam of the init, defaulting to the real Commander's. */
    public static final class Builder {

        private final Rig rig;
        private Supplier<CommanderClient> clients;
        private Supplier<Transport> commanderSsh;
        private Function<Rig.Register, Transport> registerSsh;
        private Function<Rig.Register, String> registerName;
        private Supplier<Path> petroLog;
        private PumpRelease pumps;
        private Pauser pauser = Pauser.REAL;
        private Tunables tunables = Tunables.defaults();
        private Say say;
        private Duration sshTimeout;
        private Map<String, String> environ;
        private SnapshotRestore snapshots;

        private Builder(CommanderAccess access) {
            this.rig = access.rig();
            this.clients = access::client;
            this.commanderSsh = access::transport;
            this.registerSsh = access::registerTransport;
            this.registerName = register -> register.id() + " (" + register.host() + ")";
            this.petroLog = () -> CommanderInit.petroLog(ToolsRoot.resolve(access.environ().get(ToolsRoot.ENV), null));
            this.pumps = pump -> releaseThroughPetro(rig, pump, say);
            this.say = Say.to(System.out, System.err);
            this.sshTimeout = access.sshTimeout();
            this.environ = access.environ();
            this.snapshots = SnapshotRestore.forRig(access);
        }

        public Builder clients(Supplier<CommanderClient> value) {
            clients = Objects.requireNonNull(value);
            return this;
        }

        public Builder commanderSsh(Supplier<Transport> value) {
            commanderSsh = Objects.requireNonNull(value);
            return this;
        }

        public Builder registerSsh(Function<Rig.Register, Transport> value) {
            registerSsh = Objects.requireNonNull(value);
            return this;
        }

        public Builder petroLog(Supplier<Path> value) {
            petroLog = Objects.requireNonNull(value);
            return this;
        }

        public Builder pumps(PumpRelease value) {
            pumps = Objects.requireNonNull(value);
            return this;
        }

        public Builder pauser(Pauser value) {
            pauser = Objects.requireNonNull(value);
            return this;
        }

        public Builder tunables(Tunables value) {
            tunables = Objects.requireNonNull(value);
            return this;
        }

        public Builder say(Say value) {
            say = Objects.requireNonNull(value);
            return this;
        }

        public Builder environ(Map<String, String> value) {
            environ = value == null ? Map.of() : Map.copyOf(value);
            return this;
        }

        /** The snapshot restore step 4 runs before the base import; {@link SnapshotRestore#forRig} by default. */
        public Builder snapshots(SnapshotRestore value) {
            snapshots = Objects.requireNonNull(value);
            return this;
        }

        public CommanderInit build() {
            return new CommanderInit(this);
        }
    }
}
