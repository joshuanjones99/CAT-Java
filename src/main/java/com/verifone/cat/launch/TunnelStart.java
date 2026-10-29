package com.verifone.cat.launch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

import com.verifone.cat.cloudtunnel.CloudTunnel;
import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.commander.Say;
import com.verifone.cat.provision.ProvisionPaths;
import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.report.ReportPaths;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.sims.PowerShellProcessOps;
import com.verifone.cat.sims.ProcessOps;

/**
 * The cloud tunnel: adopt a running {@code cloud-tunnel} for the rig, or start one. A port of the Python CAT's
 * {@code cat.launch.tunnel}, which is the specification; step 4 runs it before the inits when the run onboards to
 * Commander Connect.
 *
 * <p><b>Never stopped by CAT (rig owner, 2026-10-06; Python HANDOFF §1.101):</b> the Commander must stay online after
 * the run, so the tunnel outlives the run and CAT itself, and the next start adopts it the way step 3 adopts a running
 * simulator (D9). To stop it: end the process, then {@code cat-java cloud-tunnel --rig <rig> --stop} removes what it
 * set on the Commander.
 *
 * <p>A started tunnel answers within its first lines: {@code cloud-tunnel: up (...)}, or it exits 0 because the
 * Commander has its own default route and needs no tunnel. Any other exit fails the stage, with its last line.
 *
 * <p>What CAT-Java starts is {@code cat-java cloud-tunnel --rig <rig>}: this JVM's {@code javaw.exe} (no console)
 * running {@code com.verifone.cat.Main} on this JVM's class path, its output to {@link #log} (the Python's
 * {@code cloud_tunnel_log}: {@code <results root>/cloud-tunnel/<rig>.log}, not a run folder: it outlives the run).
 * Adoption reads every Java and Python command line ({@link #EXES}), so a tunnel either CAT started is adopted; an
 * adopted tunnel gets this checkout's gateway rules ({@link #refreshGateway}, the Python's {@code refresh_gateway}).
 *
 * <p>Deviations: the Python makes no checklist row for the tunnel (its lines go to the step's log); here it is step
 * 4's {@code cloud-tunnel} row. The gateway's relay source is the Python checkout's
 * {@code bridge/net-relay/CatNetRelay.java} ({@link #relaySource}), as the Python's tunnel pushes it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): no tunnel has been started or adopted from CAT-Java.
 */
public final class TunnelStart {

    /** How long {@code cloud-tunnel} may take to say "up" (an sshd reload, two SSH logins). */
    public static final Duration START_TIMEOUT = Duration.ofSeconds(180);
    /** How often the tunnel's log is read while waiting for "up". */
    public static final Duration POLL = Duration.ofMillis(500);
    /**
     * Executables a running tunnel may show up as: CAT-Java's {@code java}/{@code javaw}, the Python CAT's
     * {@code python -m petrotest.cli}, or the {@code petrotest} console script started by hand.
     */
    public static final List<String> EXES = List.of("java.exe", "javaw.exe", "python.exe", "pythonw.exe",
            "petrotest.exe");
    /** The line a serving tunnel prints once its forwards are up. */
    public static final String UP = "cloud-tunnel: up";
    /** How long the command-line probe may take. */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

    /** What starting the tunnel reaches outside itself; {@link #system} in production, fakes in tests. */
    public interface Seams {
        /** The command lines of every running {@link #EXES} process; an error is "could not tell". */
        ProcessOps.Snapshot running();

        /** Starts {@code argv} detached, its output (both streams) truncated into {@code log}. */
        Process spawn(List<String> argv, Path cwd, Path log) throws IOException;

        /** The adopted tunnel's gateway rules re-applied; what it did, for the log. */
        String refreshGateway(Rig rig);

        /** The monotonic clock, in nanoseconds. */
        default long nanoTime() {
            return System.nanoTime();
        }

        /** Parks the caller between log reads. */
        default void pause(Duration duration) {
            LockSupport.parkNanos(duration.toNanos());
        }
    }

    private TunnelStart() {}

    /** Where a tunnel CAT starts writes its output: {@code <results root>/cloud-tunnel/<rig>.log}. */
    public static Path log(Map<String, String> environ, String rig) {
        return ReportPaths.resultsRoot(environ).resolve("cloud-tunnel").resolve(rig + ".log");
    }

    /** True for a running {@code cloud-tunnel --rig <rig>} serve (not a {@code --stop}). */
    public static boolean isTunnelFor(String commandLine, String rig) {
        List<String> words = Arrays.stream(commandLine.replace('"', ' ').strip().split("\\s+"))
                .filter(w -> !w.isEmpty()).toList();
        if (!words.contains("cloud-tunnel") || words.contains("--stop")) {
            return false;
        }
        if (words.contains("--rig=" + rig)) {
            return true;
        }
        for (int i = 0; i + 1 < words.size(); i++) {
            if (words.get(i).equals("--rig") && words.get(i + 1).equals(rig)) {
                return true;
            }
        }
        return false;
    }

    /** The command line of the tunnel CAT-Java starts. */
    static List<String> argv(String java, String classpath, String rig) {
        return List.of(java, "-cp", classpath, "com.verifone.cat.Main", "cloud-tunnel", "--rig", rig);
    }

    /**
     * Adopts a running tunnel for {@code rigName}, or starts one; false fails step 4 with the last {@code say.err}.
     *
     * @param rigName the rig as the run names it, which the spawned command loads
     * @param rig     the loaded rig, for an adopted tunnel's gateway refresh
     */
    public static boolean start(String rigName, Rig rig, Map<String, String> environ, Path cwd, Seams seams,
            Say say) {
        ProcessOps.Snapshot running = seams.running();
        boolean found = running.text().lines().anyMatch(line -> isTunnelFor(line, rigName));
        if (found) {
            say.out("cloud-tunnel already running for " + rigName + ": adopted, left running");
            // Its rules as this run's code writes them: an adopted tunnel keeps the code it started with, and
            // re-applies that after a Commander reboot (Python HANDOFF §1.159).
            String done;
            try {
                done = seams.refreshGateway(rig);
            } catch (RuntimeException e) { // the adopted tunnel still serves; never fail step 4 here
                done = "gateway refresh failed (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
            }
            say.out("cloud-tunnel: " + done);
            return true;
        }
        if (!running.error().isEmpty()) {
            say.out("could not check for a running cloud-tunnel (" + running.error() + "); starting one");
        }

        List<String> argv = argv(javaExecutable(), System.getProperty("java.class.path", ""), rigName);
        Path logPath = log(environ, rigName);
        say.out("cat-java cloud-tunnel --rig " + rigName + "  (left running; output: " + logPath + ")");
        Process process;
        try {
            process = seams.spawn(argv, cwd, logPath);
        } catch (IOException e) {
            say.err("cloud-tunnel could not be started: " + e.getMessage());
            return false;
        }
        long deadline = seams.nanoTime() + START_TIMEOUT.toNanos();
        int shown = 0;
        String last = "";
        while (true) {
            boolean exited = !process.isAlive();
            String text = read(logPath);
            // Whole lines only while it runs; the rest too once it has exited.
            List<String> lines = new ArrayList<>(Arrays.asList(text.split("\r\n|\n|\r", -1)));
            if (!exited || (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty())) {
                lines.remove(lines.size() - 1);
            }
            for (int i = shown; i < lines.size(); i++) {
                String line = lines.get(i).stripTrailing();
                if (!line.isEmpty()) {
                    last = line;
                    say.out(line);
                }
                if (line.startsWith(UP)) {
                    return true;
                }
            }
            shown = Math.max(shown, lines.size());
            if (exited) {
                int code = process.exitValue();
                if (code == 0) {
                    return true;
                }
                say.err(last.isEmpty() ? "cat-java cloud-tunnel exited " + code : last);
                return false;
            }
            if (seams.nanoTime() >= deadline) {
                process.destroyForcibly();
                say.err("cloud-tunnel did not come up in " + START_TIMEOUT.toSeconds() + " s");
                return false;
            }
            seams.pause(POLL);
        }
    }

    private static String read(Path log) {
        try {
            return new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return "";
        } catch (IOException e) {
            return "";
        }
    }

    /** {@code javaw.exe} beside this JVM when there is one (no console window), else {@code java}. */
    static String javaExecutable() {
        Path bin = Path.of(System.getProperty("java.home", ""), "bin");
        Path javaw = bin.resolve("javaw.exe");
        return Files.isRegularFile(javaw) ? javaw.toString() : bin.resolve("java").toString();
    }

    /** The Python checkout's {@code bridge/net-relay/CatNetRelay.java}: what the gateway pushes and compiles there. */
    public static Path relaySource(Map<String, String> environ) {
        return ProvisionPaths.pythonRoot(environ).resolve("bridge").resolve("net-relay").resolve("CatNetRelay.java");
    }

    /**
     * The Python's {@code refresh_gateway}: when the Commander's NAT table hooks the gateway chain (a tunnel in gateway
     * mode), this checkout's gateway rules applied ({@link CloudTunnel#refreshGateway}); else left alone.
     */
    public static String refreshGateway(Rig rig, Map<String, String> environ) {
        CommanderAccess access = new CommanderAccess(rig, JschTransport::new, environ);
        byte[] source;
        try {
            source = Files.readAllBytes(relaySource(environ));
        } catch (IOException e) {
            return "gateway not refreshed: " + relaySource(environ) + " unreadable (" + e.getMessage() + ")";
        }
        Transport transport = access.connectedTransport();
        try {
            return CloudTunnel.refreshGateway(transport, CloudTunnel.controllerDns(), source, access.sshTimeout(),
                    line -> { });
        } finally {
            transport.close();
        }
    }

    /** The production seams: PowerShell's process listing, a detached child, SSH to the Commander. */
    public static Seams system(Map<String, String> environ) {
        ProcessOps ops = new PowerShellProcessOps();
        return new Seams() {
            @Override
            public ProcessOps.Snapshot running() {
                return ops.commandLines(EXES, PROBE_TIMEOUT);
            }

            @Override
            public Process spawn(List<String> argv, Path cwd, Path log) throws IOException {
                Files.createDirectories(log.toAbsolutePath().getParent());
                Process process = new ProcessBuilder(argv)
                        .directory(cwd.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()))
                        .start();
                process.getOutputStream().close();
                return process;
            }

            @Override
            public String refreshGateway(Rig rig) {
                return TunnelStart.refreshGateway(rig, environ);
            }
        };
    }
}
