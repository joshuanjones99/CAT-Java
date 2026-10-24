package com.verifone.cat.closeday;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;

/**
 * In-memory stand-ins for the Commander, at the two transport boundaries the day close crosses: an SSH
 * session whose shell plays {@code /var/log/messages} and coresvcs, and a CGILink client. Evidence about
 * this code, not about a Commander: the wire format of CGILink is {@code PdkShimTest}'s.
 *
 * <p>The log lines, the shell's reads and the close's timing follow the Python's
 * {@code tests/unit/test_close_day.py} {@code _CommanderBox}.
 */
final class CloseDayFakes {

    static final String HOST = "commander.testrig.invalid";

    /** Lines copied from HANDOFF.md 1.60's reading of the lab Commander's log. */
    static final String LOCKING = "07:27:12.63  PeriodCloseProcessor - Changing state from IDLE to LOCK CLIENTS";
    static final String CANCELED = "07:29:33.593 LockClientsState     - PERIOD CLOSE CANCELED: Period close was "
            + "unacknowleged by Register101";
    static final String ACKED = "12:14:22.9   CashierCloseState    - Cashier close ack from Register101";
    static final String IDLE = "12:14:23.1   PeriodCloseProcessor - Changing state from RELEASE CLIENTS to IDLE";
    static final String UNRELATED = "07:26:59.001 XMLConfigServlet     - vtransnumlist";

    /** The shell's log reads, as {@link CommanderLog} sends them. */
    private static final Pattern LOG_READ = Pattern.compile(
            "f=/var/log/messages; if \\[ \\$\\(wc -l < \\$f\\) -lt (\\d+) \\]; then cat \\$f; "
                    + "else tail -n \\+(\\d+) \\$f; fi");

    private CloseDayFakes() {}

    /** Commander state shared by the SSH and CGILink fakes of one test: the log, coresvcs, and what was called. */
    static final class Box {

        final List<String> lines = new ArrayList<>();
        /** Appended to the log on the reads after {@code cclosepdnow} was sent. */
        final List<String> afterClose = new ArrayList<>();
        /** How many reads after the close pass before {@link #afterClose} shows. */
        int delayReads;
        /** Whether the log is emptied when the close is sent (a rotation). */
        boolean rotate;
        /** Whether every SSH command fails to connect. */
        boolean sshDown;
        /** How many log reads find {@code /var/log/messages} gone. */
        int missingReads;
        /** The thread dump coresvcs's {@code jstack} returns. */
        String jstack = "";
        /** coresvcs's main pid; a restart gives it a new one. */
        String pid = "111";
        int restarts;
        /** Runs when coresvcs is restarted, as a restart that brings the next close's outcome. */
        Runnable onRestart = () -> {};
        boolean closed;
        final List<String> commands = new ArrayList<>();
        /** Every CGILink call and register pass, in order. */
        final List<String> calls = new ArrayList<>();

        Box(List<String> before, List<String> after) {
            lines.addAll(before);
            afterClose.addAll(after);
        }

        void closeSent() {
            closed = true;
            if (rotate) {
                lines.clear();
            }
        }

        CommandResult shell(String command) {
            commands.add(command);
            if (sshDown) {
                throw new DeviceUnreachable(HOST, "No route to host");
            }
            if (command.equals("wc -l < " + CommanderLog.MESSAGES)) {
                return ok(lines.size() + "\n");
            }
            Matcher read = LOG_READ.matcher(command);
            if (read.matches()) {
                if (missingReads > 0) {
                    missingReads--;
                    return new CommandResult(1, "", "tail: cannot open '/var/log/messages' for reading: "
                            + "No such file or directory");
                }
                if (closed && !afterClose.isEmpty()) {
                    if (delayReads > 0) {
                        delayReads--;
                    } else {
                        lines.addAll(afterClose);
                        afterClose.clear();
                    }
                }
                int mark = Integer.parseInt(read.group(1));
                int from = Integer.parseInt(read.group(2));
                List<String> shown = lines.size() < mark ? lines : lines.subList(from - 1, lines.size());
                StringBuilder text = new StringBuilder();
                for (String line : shown) {
                    text.append(line).append('\n');
                }
                return ok(text.toString());
            }
            if (command.equals(CloseDay.CORESVCS_JSTACK)) {
                return ok(jstack);
            }
            if (command.equals(CloseDay.CORESVCS_PID)) {
                return ok(pid + "\n");
            }
            if (command.equals(CloseDay.CORESVCS_RESTART)) {
                restarts++;
                pid = "222";
                onRestart.run();
                return ok("");
            }
            return ok("");
        }

        private static CommandResult ok(String stdout) {
            return new CommandResult(0, stdout, "");
        }
    }

    /** An SSH session onto a {@link Box}: the shell, with no socket. */
    static final class BoxTransport implements Transport {

        private final Box box;

        BoxTransport(Box box) {
            this.box = box;
        }

        @Override
        public String host() {
            return HOST;
        }

        @Override
        public CommandResult run(String command, Duration timeout) {
            return box.shell(command);
        }

        @Override
        public void put(String remotePath, byte[] content, int mode, Duration timeout) {
            throw new UnsupportedOperationException("the day close never writes a remote file");
        }

        @Override
        public Optional<byte[]> get(String remotePath, Duration timeout) {
            throw new UnsupportedOperationException("the day close never reads a remote file");
        }

        @Override
        public void mkdir(String remotePath, Duration timeout) {
            throw new UnsupportedOperationException("the day close never makes a remote folder");
        }

        @Override
        public boolean exists(String remotePath, Duration timeout) {
            throw new UnsupportedOperationException("the day close never tests a remote path");
        }

        @Override
        public void close() {
            // Nothing held open.
        }
    }

    /**
     * CGILink as the day close calls it. Each {@code cclosepdnow} runs the next entry of {@link #closeActions}
     * first, which may write to the log and throw; with none queued the close is sent and the log is told.
     */
    static final class Cgi implements CommanderAccess.CgiClient {

        final Box box;
        /** Each entry runs in place of a close: it may add a log line and throw, as a fault does. */
        final Deque<Runnable> closeActions = new ArrayDeque<>();
        String refinteg = "<reportConfig><aeod><forceAEOD>false</forceAEOD></aeod></reportConfig>";
        String buypass = "<fepParams><brandSelection>BUYPASS_GENERIC</brandSelection></fepParams>";
        /** Whether importing base's Buypass FEP clears the forced AEOD, as it does on the lab. */
        boolean importClearsForce;
        /** Whether every view fails, as a Commander that cannot be reached does. */
        boolean viewFails;
        /** The file names each import carried, and their contents. */
        final List<List<String>> importedNames = new ArrayList<>();
        final List<String> importedContent = new ArrayList<>();

        Cgi(Box box) {
            this.box = box;
        }

        @Override
        public String viewConfiguration(String command) {
            box.calls.add("view " + command);
            if (viewFails) {
                throw new DeviceUnreachable(HOST, "refused");
            }
            return command.equals("refinteg") ? refinteg : buypass;
        }

        @Override
        public void closeDay() {
            box.calls.add("cclosepdnow");
            Runnable action = closeActions.poll();
            if (action != null) {
                action.run();
                return;
            }
            box.closeSent();
        }

        @Override
        public void importConfigurationFolder(Path folder) {
            box.calls.add("import");
            try (Stream<Path> files = Files.list(folder)) {
                List<String> names = new ArrayList<>();
                files.forEach(file -> names.add(file.getFileName().toString()));
                importedNames.add(names);
                for (String name : names) {
                    importedContent.add(Files.readString(folder.resolve(name), StandardCharsets.UTF_8));
                }
                if (importClearsForce && names.contains(CloseDay.BUYPASS_FEP_FILE)) {
                    refinteg = "<reportConfig><aeod><forceAEOD>false</forceAEOD></aeod></reportConfig>";
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void refreshConfiguration() {
            box.calls.add("refresh");
        }

        @Override
        public void close() {
            // Nothing held open.
        }
    }

    /** The Commander as the day close reaches it: the same {@link Box} over SSH and {@link Cgi} over CGILink. */
    static final class Access implements CommanderAccess {

        final Box box;
        final Cgi cgi;

        Access(Box box) {
            this.box = box;
            this.cgi = new Cgi(box);
        }

        @Override
        public String host() {
            return HOST;
        }

        @Override
        public Transport ssh() {
            return new BoxTransport(box);
        }

        @Override
        public CgiClient cgi() {
            return cgi;
        }
    }

    /** Captures a stream's text as UTF-8. */
    static final class Capture {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        String text() {
            stream.flush();
            return bytes.toString(StandardCharsets.UTF_8);
        }

        /** The last non-blank line written. */
        String lastLine() {
            String[] lines = text().strip().split("\n");
            return lines[lines.length - 1].strip();
        }
    }

    /** The waits a test runs under: milliseconds, with the one-second budget the Python's messages quote. */
    static CloseDay.Timing fast() {
        return new CloseDay.Timing(Duration.ofSeconds(1), Duration.ofMillis(10), Duration.ofMillis(300),
                Duration.ofMillis(10), Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(10),
                Duration.ofSeconds(1));
    }

    /** A close over {@code access} with the register pass recorded in {@code box.calls}. */
    static CloseDay closeDay(Access access, Path base, Path results, Capture out, Capture err) {
        RegisterPass register = errStream -> access.box.calls.add("clear");
        return new CloseDay(access, register, base, results, fast(), out.stream, err.stream);
    }
}
