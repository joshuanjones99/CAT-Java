package com.verifone.cat.closeday;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.report.ReportPaths;
import com.verifone.cat.rig.Durations;
import com.verifone.cat.rig.Rig;

/**
 * Closes the Commander's period, then confirms the close in the Commander's log. A port of the Python's
 * {@code petrotest.close_day.close_day} and the stuck-close recovery around it.
 *
 * <p>The order, as the Python: undo a forced automatic end of day (base's Buypass FEP re-imported), clear
 * the register's blocking nag ({@link RegisterPass}), note the log's length, send {@code cclosepdnow}, then
 * read {@code /var/log/messages} past the mark for {@code RELEASE CLIENTS to IDLE} (completed) or a
 * cancel. {@code cclosepdnow} replies when the close starts, so exit 0 alone never meant it closed
 * (HANDOFF.md 1.60). A deadlock in coresvcs is named from a thread dump and clears by a coresvcs restart.
 * After a restart {@code cclosepdnow} is sent again while coresvcs still faults; "Close already in progress"
 * is never sent again.
 *
 * <p>The waits are bounded ({@link Timing}); none is {@code Thread.sleep}. The register pass is
 * {@link RegisterPass#NOT_PORTED} in production; see its Javadoc.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python's lab evidence (HANDOFF.md 1.37, 1.58, 1.60,
 * 1.122, 1.166) is quoted in its docstrings, not re-proven here.
 */
public final class CloseDay {

    /** How a close ended. {@link #exitCode} is the command line's: 0 closed, 1 failed, 4 refused for a forced AEOD. */
    public enum Outcome {
        CLOSED(0), FAILED(1), FORCED_AEOD(4);

        private final int exitCode;

        Outcome(int exitCode) {
            this.exitCode = exitCode;
        }

        public int exitCode() {
            return exitCode;
        }
    }

    /**
     * The waits. Production uses {@link #production()}; a test shrinks them so it runs in milliseconds.
     *
     * @param budget           how long to wait for the close's outcome after {@code cclosepdnow} replies
     * @param poll             how often the log is read while waiting
     * @param afterRestart     how long {@code cclosepdnow} is sent again just after a coresvcs restart
     * @param afterRestartPoll how often, during that
     * @param restartWait      the pause before coresvcs is checked after {@code killCS -r}
     * @param restartTimeout   how long coresvcs has to come back
     * @param restartPoll      how often its readiness is checked
     * @param jstackMinimum    the least time a thread dump is given
     */
    public record Timing(Duration budget, Duration poll, Duration afterRestart, Duration afterRestartPoll,
            Duration restartWait, Duration restartTimeout, Duration restartPoll, Duration jstackMinimum) {

        public static Timing production() {
            return new Timing(Duration.ofMinutes(4), Duration.ofSeconds(2), Duration.ofMinutes(3),
                    Duration.ofSeconds(15), Duration.ofSeconds(15), Duration.ofSeconds(300),
                    Duration.ofSeconds(5), Duration.ofSeconds(60));
        }
    }

    /** The Python's {@code BUYPASS_FEP_FILE}: base's Buypass FEP, branded BUYPASS_GENERIC. */
    static final String BUYPASS_FEP_FILE = "fepcfg&fep=buypass.xml";

    /** The default petroapps checkout, as the Python's {@code DEFAULT_PETROAPPS_ROOT}. */
    static final Path DEFAULT_PETROAPPS_ROOT = Path.of("C:/gitrepos/petroapps");

    /** The JVM's own verdict in a thread dump. */
    static final String DEADLOCK = "Found one Java-level deadlock";

    /** The pid of coresvcs's main JVM (the one running SalesRT), or nothing. */
    static final String CORESVCS_PID = "ps -eo pid,user,args | awk '$2==\"coresvcs\" && "
            + "/java -server -Xms128M -Xmx512M/ {print $1; exit}'";

    /** A thread dump of that JVM, as its own user. */
    static final String CORESVCS_JSTACK = "pid=$(" + CORESVCS_PID + "); [ -n \"$pid\" ] && "
            + "su - coresvcs -c \"/usr/java/j2se/bin/jstack $pid\"";

    /** {@code killCS -r} stops coresvcs and starts it again. */
    static final String CORESVCS_RESTART =
            "su - coresvcs -c 'setsid nohup /home/coresvcs/bin/killCS -r >/dev/null 2>&1 </dev/null &'";

    /** {@code CGIPortal.InternalError}, as CGILink reports a refused close, a forced AEOD and a booting coresvcs. */
    static final String INTERNAL_ERROR = "InternalError";

    private static final Pattern FORCE_AEOD = Pattern.compile("<forceAEOD>\\s*true\\s*</forceAEOD>");
    private static final Pattern BRAND = Pattern.compile("<brandSelection>([^<]*)</brandSelection>");
    private static final Pattern WAITING = Pattern.compile("^\"([^\"]+)\":\\n  waiting to lock", Pattern.MULTILINE);

    private final CommanderAccess commander;
    private final RegisterPass register;
    private final Path baseFolder;
    private final Path resultsRoot;
    private final Timing timing;
    private final PrintStream out;
    private final PrintStream err;
    private Path keptDump;

    /**
     * @param commander  how the Commander is reached (SSH and CGILink)
     * @param register   the register pass before the close
     * @param baseFolder {@code configurations/base}, where the Buypass FEP file is read
     * @param resultsRoot where a deadlocked coresvcs's thread dump is kept
     * @param timing     the waits
     * @param out        progress lines (the Python's stdout)
     * @param err        warnings and failures (the Python's stderr)
     */
    public CloseDay(CommanderAccess commander, RegisterPass register, Path baseFolder, Path resultsRoot,
            Timing timing, PrintStream out, PrintStream err) {
        this.commander = commander;
        this.register = register;
        this.baseFolder = baseFolder;
        this.resultsRoot = resultsRoot;
        this.timing = timing;
        this.out = out;
        this.err = err;
    }

    /** The production close for a rig: the Commander from the rig file, the SSH key from {@code env}. */
    public static CloseDay forRig(Rig rig, Map<String, String> env, PrintStream out, PrintStream err) {
        return new CloseDay(CommanderAccess.forRig(rig, env), RegisterPass.NOT_PORTED, baseConfigFolder(env),
                ReportPaths.resultsRoot(env), Timing.production(), out, err);
    }

    /** {@code $PETROAPPS_ROOT/AutomationTests/configurations/base}, with the Python's default root. */
    public static Path baseConfigFolder(Map<String, String> env) {
        String configured = env.getOrDefault("PETROAPPS_ROOT", "").strip();
        Path root = configured.isEmpty() ? DEFAULT_PETROAPPS_ROOT : Path.of(configured);
        return root.resolve("AutomationTests").resolve("configurations").resolve("base");
    }

    /** Where the last deadlocked coresvcs's thread dump was kept, or null. For the failed step's report. */
    public Path keptDump() {
        return keptDump;
    }

    /** Closes the period. Never throws for a Commander fault: every failure is an {@link Outcome}. */
    public Outcome close() {
        return closeOnce(true, null);
    }

    private Outcome closeOnce(boolean recover, Duration afterRestart) {
        undoForcedAeod();
        register.clearBlockingNag(err);
        String host = commander.host();
        CommanderLog log = new CommanderLog(commander.ssh());
        try {
            String logError = "";
            try {
                log.mark();
            } catch (RuntimeException e) {
                // Reported after the close; the close is still sent.
                logError = describe(e);
                err.println("cannot read " + CommanderLog.MESSAGES + " on " + host + " (" + logError
                        + "); closing anyway");
            }

            DeviceError failure = null;
            try (CommanderAccess.CgiClient client = commander.cgi()) {
                sendClose(client, log, logError.isEmpty() ? afterRestart : null);
            } catch (DeviceError e) {
                failure = e;
            }
            if (failure != null) {
                String message = String.valueOf(failure.getMessage());
                err.println("close day on " + host + " failed: " + message);
                String reason = message.contains(INTERNAL_ERROR) ? forcedAeodReason() : "";
                if (!reason.isEmpty()) {
                    err.println("close day on " + host + ": " + reason);
                    return Outcome.FORCED_AEOD;
                }
                boolean stuck = recover && logError.isEmpty() && log.sinceMark(CommanderLog.IN_PROGRESS);
                if (stuck) {
                    out.println("close day on " + host + ": an earlier close never ended ('"
                            + CommanderLog.IN_PROGRESS + "')");
                    recoverStuckClose(false);
                    return closeOnce(false, timing.afterRestart());
                }
                return Outcome.FAILED;
            }

            if (!logError.isEmpty()) {
                err.println("close day on " + host + ": cclosepdnow sent, outcome unknown ("
                        + CommanderLog.MESSAGES + " unreadable: " + logError + ")");
                return Outcome.FAILED;
            }
            String wait = Durations.format(timing.budget());
            out.println("cclosepdnow sent to " + host + "; waiting up to " + wait + " for the outcome in "
                    + CommanderLog.MESSAGES);
            Optional<String> line;
            try {
                line = Waits.until(log::outcome, timing.budget(), timing.poll());
            } catch (RuntimeException e) {
                err.println("close day on " + host + ": outcome unknown, " + CommanderLog.MESSAGES
                        + " became unreadable (" + describe(e) + ")");
                return Outcome.FAILED;
            }
            if (line.isEmpty()) {
                err.println("close day on " + host + ": no outcome within " + wait + " (neither '"
                        + CommanderLog.CLOSED + "' nor '" + CommanderLog.CANCELLED + "' in "
                        + CommanderLog.MESSAGES + ")");
                // The same deadlock, on this close itself: only a dump that names it is restarted under.
                if (recover && recoverStuckClose(true)) {
                    return closeOnce(false, timing.afterRestart());
                }
                return Outcome.FAILED;
            }
            if (CommanderLog.cancelled(line.get())) {
                err.println("close day on " + host + " was CANCELLED by the Commander: " + line.get());
                return Outcome.FAILED;
            }
            out.println("closed the period on " + host + ": " + line.get());
            return Outcome.CLOSED;
        } finally {
            log.close();
        }
    }

    /**
     * {@code cclosepdnow}; just after a coresvcs restart ({@code settle} not null), again while it faults.
     * coresvcs answers reads before its period-close manager is up, and then the close faults
     * {@code InternalError}; it is sent again every {@code afterRestartPoll} for at most {@code settle}.
     * Never on "Close already in progress": that is the deadlock back, and the fault is raised.
     */
    private void sendClose(CommanderAccess.CgiClient client, CommanderLog log, Duration settle) {
        if (settle == null) {
            client.closeDay();
            return;
        }
        DeviceError[] last = new DeviceError[1];
        Optional<Boolean> accepted = Waits.<Boolean>until(() -> {
            try {
                client.closeDay();
                return Optional.of(Boolean.TRUE);
            } catch (DeviceError e) {
                if (!String.valueOf(e.getMessage()).contains(INTERNAL_ERROR) || log.sinceMark(CommanderLog.IN_PROGRESS)) {
                    throw e;
                }
                last[0] = e;
                out.println("cclosepdnow faulted while coresvcs starts (" + e.getMessage() + "); sending it again");
                return Optional.<Boolean>empty();
            }
        }, settle, timing.afterRestartPoll());
        if (accepted.isEmpty()) {
            throw last[0] != null ? last[0] : new DeviceError("cclosepdnow not accepted");
        }
    }

    /**
     * Re-imports base's Buypass FEP when {@code refinteg} reads {@code forceAEOD=true}. Nothing is imported
     * otherwise. Any failure is reported and swallowed: the close that follows names a forced AEOD that is still there.
     */
    void undoForcedAeod() {
        try (CommanderAccess.CgiClient client = commander.cgi()) {
            if (!forcesAeod(client)) {
                return;
            }
            Matcher brand = BRAND.matcher(client.viewConfiguration("fepcfg&fep=buypass"));
            Path source = baseFolder.resolve(BUYPASS_FEP_FILE);
            out.println("the Commander forces automatic end of day"
                    + (brand.find() ? " (Buypass brand " + brand.group(1) + ")" : "") + "; re-importing " + source);
            importBuypass(client, source);
            client.refreshConfiguration();
            if (forcesAeod(client)) {
                err.println("the Commander still forces automatic end of day after the base Buypass import");
            } else {
                out.println("forced AEOD undone: refinteg forceAEOD=false");
            }
        } catch (IOException | RuntimeException e) {
            err.println("could not undo the forced AEOD (" + describe(e) + "); closing anyway");
        }
    }

    private static void importBuypass(CommanderAccess.CgiClient client, Path source) throws IOException {
        Path staging = Files.createTempDirectory("close-day-buypass");
        try {
            Files.copy(source, staging.resolve(BUYPASS_FEP_FILE));
            client.importConfigurationFolder(staging);
        } finally {
            try {
                Files.deleteIfExists(staging.resolve(BUYPASS_FEP_FILE));
                Files.deleteIfExists(staging);
            } catch (IOException ignored) {
                // A temporary folder left behind is harmless; the import has already been made.
            }
        }
    }

    private static boolean forcesAeod(CommanderAccess.CgiClient client) {
        return FORCE_AEOD.matcher(client.viewConfiguration("refinteg")).find();
    }

    /** Why a refused close was refused, when the Commander forces AEOD; else "". */
    private String forcedAeodReason() {
        try (CommanderAccess.CgiClient client = commander.cgi()) {
            if (!forcesAeod(client)) {
                return "";
            }
            Matcher brand = BRAND.matcher(client.viewConfiguration("fepcfg&fep=buypass"));
            return "the Commander forces automatic end of day (refinteg forceAEOD=true"
                    + (brand.find() ? ", Buypass brand " + brand.group(1) : "")
                    + "), so it refuses a manual close; the next run's Commander Configuration re-imports the "
                    + "base config and closes the day";
        } catch (DeviceError e) {
            return "";
        }
    }

    /**
     * A close that never ended: names a deadlock if coresvcs holds one, then restarts coresvcs. With
     * {@code onlyIfDeadlocked}, nothing is restarted unless the dump names the deadlock (a slow close, not a
     * stuck one). True when coresvcs was restarted.
     */
    public boolean recoverStuckClose(boolean onlyIfDeadlocked) {
        try (Transport ssh = commander.ssh()) {
            String before = ssh.run(CORESVCS_PID, CommanderAccess.SSH_TIMEOUT).stdout().strip();
            Duration dumpTimeout = timing.jstackMinimum().compareTo(CommanderAccess.SSH_TIMEOUT) > 0
                    ? timing.jstackMinimum() : CommanderAccess.SSH_TIMEOUT;
            String dump = ssh.run(CORESVCS_JSTACK, dumpTimeout).stdout();
            if (dump.contains(DEADLOCK)) {
                List<String> threads = new ArrayList<>();
                Matcher waiting = WAITING.matcher(dump);
                while (waiting.find()) {
                    threads.add(waiting.group(1));
                }
                err.println("the stuck close is a Java-level deadlock in coresvcs between "
                        + threads.stream().limit(2).map(t -> "'" + t + "'").collect(Collectors.joining(" and ")));
                keepDump(dump);
            } else if (onlyIfDeadlocked) {
                return false;
            }
            err.println("restarting coresvcs to clear the stuck period close");
            restartCoresvcs(ssh, before);
            return true;
        }
    }

    /** {@code killCS -r}, then waits until a new coresvcs VM answers a signed-in CGILink read. */
    private void restartCoresvcs(Transport ssh, String before) {
        ssh.run(CORESVCS_RESTART, CommanderAccess.SSH_TIMEOUT);
        Waits.pause(timing.restartWait());
        Optional<Boolean> back = Waits.<Boolean>until(
                () -> coresvcsBack(ssh, before) ? Optional.of(Boolean.TRUE) : Optional.<Boolean>empty(),
                timing.restartTimeout(), timing.restartPoll());
        if (back.isEmpty()) {
            throw new DeviceError("coresvcs to come back (a new coresvcs VM that answers CGILink reads) did not happen "
                    + "within " + Durations.format(timing.restartTimeout()));
        }
    }

    /**
     * A new coresvcs VM is running and CGILink answers a read. Not the Commander Connect status: on a
     * Commander that is not onboarded that faults whatever state coresvcs is in.
     */
    private boolean coresvcsBack(Transport ssh, String before) {
        String pid = ssh.run(CORESVCS_PID, CommanderAccess.SSH_TIMEOUT).stdout().strip();
        if (pid.isEmpty() || pid.equals(before)) {
            return false;
        }
        try (CommanderAccess.CgiClient client = commander.cgi()) {
            client.viewConfiguration("refinteg");
            return true;
        } catch (DeviceError e) {
            return false;
        }
    }

    /** Saves a deadlocked coresvcs's thread dump for the defect report. Best effort. */
    private void keepDump(String dump) {
        LocalDateTime now = LocalDateTime.now();
        Path folder = resultsRoot.resolve("defect-evidence").resolve(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        Path path = folder.resolve("coresvcs-deadlock-jstack-" + now.format(DateTimeFormatter.ofPattern("HHmmss")) + ".txt");
        try {
            Files.createDirectories(folder);
            Files.writeString(path, dump, StandardCharsets.UTF_8);
            keptDump = path;
            err.println("coresvcs thread dump kept in " + path);
        } catch (IOException e) {
            err.println("could not keep the coresvcs thread dump: " + e.getMessage());
        }
    }

    private static String describe(Exception e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
