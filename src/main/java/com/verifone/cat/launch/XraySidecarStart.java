package com.verifone.cat.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.verifone.cat.report.events.CatEvent;

/**
 * Starts the Xray reporting sidecar for a run, and never waits for it. The Python CAT's
 * {@code cat.launch.xray_sidecar.start_xray_sidecar}.
 *
 * <p>The sidecar is a separate process, as in the Python: {@code java -cp <this classpath>
 * com.verifone.cat.Main xray --run <run-id> ...}, started detached with its output appended to
 * {@code <run folder>/xray.log}. It follows {@code events.ndjson} and stops on {@code run.ended}, which
 * {@link RunLauncher} writes when the run is over. Nothing here joins or kills it: a run the operator
 * stops still gets its execution closed with what was seen, and a launcher that exits mid-run leaves a
 * sidecar that finishes when the stream does. A thread in this JVM would die with the launcher, and a
 * sidecar's failure would share the run's heap; a process has neither problem.
 *
 * <p>Nothing here can end the run. A sidecar that fails to start is one WARNING line, and the run goes on.
 * The launcher starts it only after step 4, so a run that stopped before any test ran creates no Jira
 * execution (the Python's rule, rig owner 2026-10-19).
 *
 * <p>The sidecar's command line is the Python's {@code sidecar_argv}: the run, the results root, the run's
 * {@code --dist}, test-plan {@code --branch} and {@code --upgrade} mode, and {@code --build} when step 4 named the
 * build on the Commander ({@link RunContext#installedBuild}), for the execution's title.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the tests use a fake {@link Spawner}; no test starts a process.
 */
final class XraySidecarStart {

    /** The sidecar's log, beside the run's {@code events.ndjson}: the Python's {@code xray.log}. */
    static final String LOG_FILE = "xray.log";

    private static final String LOGGER = "cat.xray";

    private XraySidecarStart() {
    }

    /** Starts one process detached and returns its pid. The seam a test replaces. */
    @FunctionalInterface
    interface Spawner {

        /**
         * @param argv the program and its arguments
         * @param log  where its output is appended
         * @param cwd  its working folder
         * @return the new process's pid
         * @throws IOException when the process cannot be started
         */
        long spawn(List<String> argv, Path log, Path cwd) throws IOException;
    }

    /** The real spawner: a child process whose stdin is closed and whose output goes to {@code log}. */
    static Spawner detached() {
        return XraySidecarStart::spawnDetached;
    }

    /**
     * Starts the sidecar for {@code ctx}'s run if its config asks for it, and says so in the run's log.
     * The Python's messages, with their reasons.
     */
    static void start(RunContext ctx, Spawner spawner) {
        LaunchConfig config = ctx.config();
        if (!config.xrayReport()) {
            return;
        }
        if (config.fakePos()) {
            info(ctx, "Xray: not reported — a fake POS run is evidence about this repository, never about a rig");
            return;
        }
        if (config.testPlans().isEmpty()) {
            info(ctx, "Xray: not reported — no test plan was chosen, so there is no plan to report against");
            return;
        }
        Path log = ctx.runFolder().resolve(LOG_FILE);
        List<String> argv = argv(javaExecutable(), System.getProperty("java.class.path", ""), ctx.runId(),
                ctx.paths().resultsRoot(), config.dist(), config.testPlanBranch(), config.upgradeMode().wire(),
                ctx.installedBuild());
        try {
            long pid = spawner.spawn(argv, log, Path.of(System.getProperty("user.dir", ".")));
            info(ctx, "Xray: reporting sidecar started (pid " + pid + ") for "
                    + String.join(", ", config.testPlans()) + "; its log is " + log);
        } catch (IOException | RuntimeException e) {
            ctx.emit(new CatEvent.LogLine("WARNING", LOGGER, "WARN  Xray reporting sidecar failed to start ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + "); the run continues without it",
                    LaunchStepNames.SYSTEM_CHECKS));
        }
    }

    /** The line a run gets when steps 1 to 4 stopped it, and Xray was asked for: no execution was made. */
    static void notReported(RunContext ctx, String stoppedAt) {
        if (ctx.config().xrayReport()) {
            info(ctx, "Xray: not reported — the run stopped at " + stoppedAt
                    + " before any test ran, so no Jira execution was created");
        }
    }

    /**
     * The sidecar's command line: this build's {@code Main}, run as the {@code xray} subcommand, so the
     * classpath is the one this JVM was started with.
     */
    static List<String> argv(String java, String classpath, String runId, Path resultsRoot, String dist, String branch,
            String upgrade, String installedBuild) {
        List<String> argv = new java.util.ArrayList<>(List.of(java, "-cp", classpath, "com.verifone.cat.Main", "xray",
                "--run", runId,
                "--results-root", resultsRoot.toString(),
                "--dist", dist,
                "--branch", branch,
                "--upgrade", upgrade));
        if (installedBuild != null && !installedBuild.isEmpty()) {
            argv.add("--build");
            argv.add(installedBuild);
        }
        return List.copyOf(argv);
    }

    /** {@code javaw.exe} beside this JVM when there is one (no console window), else {@code java}. */
    static String javaExecutable() {
        Path bin = Path.of(System.getProperty("java.home", ""), "bin");
        Path javaw = bin.resolve("javaw.exe");
        return Files.isRegularFile(javaw) ? javaw.toString() : bin.resolve("java").toString();
    }

    private static long spawnDetached(List<String> argv, Path log, Path cwd) throws IOException {
        Files.createDirectories(log.toAbsolutePath().getParent());
        Process process = new ProcessBuilder(argv)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
        process.getOutputStream().close();
        return process.pid();
    }

    private static void info(RunContext ctx, String message) {
        ctx.emit(new CatEvent.LogLine("INFO", LOGGER, message, LaunchStepNames.SYSTEM_CHECKS));
    }
}
