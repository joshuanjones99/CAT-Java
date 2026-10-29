package com.verifone.cat.cloudtunnel;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * What a run needs to adopt or start a detached {@code cloud-tunnel} for its rig: the constants and pure rules of the
 * Python {@code cat.launch.tunnel}, kept beside the tunnel so the command line it spawns and the one it looks for
 * cannot drift apart. The spawn and the wait for {@code up} are the launch's ({@code launch.TunnelStart}).
 *
 * <p>The contract, as {@code cat/launch/tunnel.py} has it:
 * <ul>
 * <li><b>Command line:</b> {@link #argv} runs this build's {@code Main} as {@code cloud-tunnel --rig <rig>} on the
 * classpath this JVM was started with, with no other switch (gateway on; not forced).</li>
 * <li><b>Output:</b> standard output and error both go to {@link #logPath} ({@code <results root>/cloud-tunnel/
 * <rig>.log}, the Python's {@code cloud_tunnel_log}); not a run folder, as the tunnel outlives the run.</li>
 * <li><b>Started:</b> a line starting {@link #UP_LINE} means it serves (left running); an exit 0 before that is
 * {@code not needed} (a pass); any other exit fails step 4 with the last line as its detail; no {@code up} within
 * {@link #START_TIMEOUT} kills it and fails. The log is re-read every {@link #POLL}.</li>
 * <li><b>Running:</b> found by command line, not a pid file: a process whose image is one of {@link #EXES} and whose
 * command line {@link #isTunnelFor} the rig. The Python's tunnel ({@code python -m petrotest.cli cloud-tunnel --rig
 * <rig>}) matches too, and must: two tunnels for one Commander would ask sshd for the same forward ports.</li>
 * <li><b>Never stopped by CAT</b> (rig owner, 2026-10-06): to stop one, end the process, then
 * {@code cat-java cloud-tunnel --rig <rig> --stop} removes what it set on the Commander.</li>
 * </ul>
 */
public final class CloudTunnelProcess {

    /** How long {@code cloud-tunnel} may take to say {@code up} (an sshd reload, two SSH logins). */
    public static final Duration START_TIMEOUT = Duration.ofSeconds(180);
    /** How often the log is re-read while waiting for {@code up}. */
    public static final Duration POLL = Duration.ofMillis(500);
    /** The line a serving tunnel prints once its forwards are up. */
    public static final String UP_LINE = "cloud-tunnel: up";
    /**
     * Images a running tunnel may show up as: this port's {@code java}/{@code javaw}, and the Python CAT's
     * {@code python -m petrotest.cli} or {@code petrotest} console script.
     */
    public static final List<String> EXES = List.of("java.exe", "javaw.exe", "python.exe", "pythonw.exe",
            "petrotest.exe");
    /** The main class {@link #argv} runs. */
    public static final String MAIN_CLASS = "com.verifone.cat.Main";

    private CloudTunnelProcess() {}

    /** {@code <java> -cp <classpath> com.verifone.cat.Main cloud-tunnel --rig <rig>}. */
    public static List<String> argv(String java, String classpath, String rig) {
        return List.of(java, "-cp", classpath, MAIN_CLASS, "cloud-tunnel", "--rig", rig);
    }

    /** Where a tunnel CAT starts writes its output: {@code <resultsRoot>/cloud-tunnel/<rig>.log}. */
    public static Path logPath(Path resultsRoot, String rig) {
        return resultsRoot.resolve("cloud-tunnel").resolve(rig + ".log");
    }

    /** True for a running {@code cloud-tunnel --rig <rig>} serve (not a {@code --stop}); the Python's rule. */
    public static boolean isTunnelFor(String commandLine, String rig) {
        String[] words = commandLine.replace('"', ' ').strip().split("\\s+");
        List<String> list = List.of(words);
        if (!list.contains("cloud-tunnel") || list.contains("--stop")) {
            return false;
        }
        if (list.contains("--rig=" + rig)) {
            return true;
        }
        for (int i = 0; i + 1 < words.length; i++) {
            if (words[i].equals("--rig") && words[i + 1].equals(rig)) {
                return true;
            }
        }
        return false;
    }
}
